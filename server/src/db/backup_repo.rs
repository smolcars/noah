use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{PgPool, Row, postgres::PgRow};
use std::convert::TryFrom;

use crate::types::BackupInfo;

/// Represents a record from the `backup_metadata` table.
#[derive(Debug)]
pub struct BackupMetadata {
    pub pubkey: String,
    pub s3_key: String,
    pub backup_size: u64,
    pub backup_version: i32,
}

impl<'r> sqlx::FromRow<'r, PgRow> for BackupMetadata {
    fn from_row(row: &'r PgRow) -> Result<Self, sqlx::Error> {
        Ok(Self {
            pubkey: row.try_get("pubkey")?,
            s3_key: row.try_get("s3_key")?,
            backup_size: row.try_get::<i64, _>("backup_size")? as u64,
            backup_version: row.try_get("backup_version")?,
        })
    }
}

/// A struct to encapsulate backup-related database operations.
pub struct BackupRepository<'a> {
    pool: &'a PgPool,
}

impl<'a> BackupRepository<'a> {
    /// Creates a new repository instance.
    pub fn new(pool: &'a PgPool) -> Self {
        Self { pool }
    }

    /// Inserts or updates backup metadata.
    pub async fn upsert_metadata(
        &self,
        pubkey: &str,
        s3_key: &str,
        backup_size: u64,
        backup_version: i32,
    ) -> Result<()> {
        let size = i64::try_from(backup_size)?;
        sqlx::query(
            "INSERT INTO backup_metadata (pubkey, s3_key, backup_size, backup_version)
             VALUES ($1, $2, $3, $4)
             ON CONFLICT(pubkey, backup_version)
             DO UPDATE SET
                s3_key = excluded.s3_key,
                backup_size = excluded.backup_size,
                created_at = now()",
        )
        .bind(pubkey)
        .bind(s3_key)
        .bind(size)
        .bind(backup_version)
        .execute(self.pool)
        .await?;
        Ok(())
    }

    /// [TEST ONLY] Inserts or updates backup metadata with a specific creation timestamp.
    #[cfg(test)]
    pub async fn upsert_metadata_with_timestamp(
        &self,
        pubkey: &str,
        s3_key: &str,
        backup_size: u64,
        backup_version: i32,
        created_at_iso: &str,
    ) -> Result<()> {
        let size = i64::try_from(backup_size)?;
        sqlx::query(
            "INSERT INTO backup_metadata (pubkey, s3_key, backup_size, backup_version, created_at)
             VALUES ($1, $2, $3, $4, $5)
             ON CONFLICT (pubkey, backup_version)
             DO UPDATE SET s3_key = excluded.s3_key,
                            backup_size = excluded.backup_size,
                            created_at = excluded.created_at",
        )
        .bind(pubkey)
        .bind(s3_key)
        .bind(size)
        .bind(backup_version)
        .bind(chrono::DateTime::parse_from_rfc3339(created_at_iso)?.with_timezone(&Utc))
        .execute(self.pool)
        .await?;
        Ok(())
    }

    /// Lists all backups for a given user.
    pub async fn list(&self, pubkey: &str) -> Result<Vec<BackupInfo>> {
        let records = sqlx::query(
            "SELECT backup_version, created_at, backup_size
             FROM backup_metadata
             WHERE pubkey = $1
             ORDER BY created_at DESC",
        )
        .bind(pubkey)
        .fetch_all(self.pool)
        .await?;

        let mut backups = Vec::with_capacity(records.len());
        for row in records {
            let created_at: DateTime<Utc> = row.try_get("created_at")?;
            let version: i32 = row.try_get("backup_version")?;
            let size: i64 = row.try_get("backup_size")?;
            backups.push(BackupInfo {
                backup_version: version,
                created_at: created_at.to_rfc3339(),
                backup_size: size as u64,
            });
        }
        Ok(backups)
    }

    /// Finds a specific backup by version.
    /// Returns a tuple of (s3_key, backup_size).
    pub async fn find_by_version(
        &self,
        pubkey: &str,
        version: i32,
    ) -> Result<Option<(String, u64)>> {
        let record = sqlx::query_as::<_, (String, i64)>(
            "SELECT s3_key, backup_size
             FROM backup_metadata
             WHERE pubkey = $1 AND backup_version = $2",
        )
        .bind(pubkey)
        .bind(version)
        .fetch_optional(self.pool)
        .await?;

        Ok(record.map(|(key, size)| (key, size as u64)))
    }

    /// Finds the latest backup for a user.
    /// Returns a tuple of (s3_key, backup_size).
    pub async fn find_latest(&self, pubkey: &str) -> Result<Option<(String, u64)>> {
        let record = sqlx::query_as::<_, (String, i64)>(
            "SELECT s3_key, backup_size
             FROM backup_metadata WHERE pubkey = $1
             ORDER BY created_at DESC LIMIT 1",
        )
        .bind(pubkey)
        .fetch_optional(self.pool)
        .await?;
        Ok(record.map(|(key, size)| (key, size as u64)))
    }

    /// Finds the S3 key for a specific backup version.
    pub async fn find_s3_key_by_version(
        &self,
        pubkey: &str,
        version: i32,
    ) -> Result<Option<String>> {
        let key = sqlx::query_scalar::<_, String>(
            "SELECT s3_key FROM backup_metadata WHERE pubkey = $1 AND backup_version = $2",
        )
        .bind(pubkey)
        .bind(version)
        .fetch_optional(self.pool)
        .await?;

        Ok(key)
    }

    /// Finds the full metadata for a specific backup version.
    #[cfg(test)]
    pub async fn find_by_pubkey_and_version(
        &self,
        pubkey: &str,
        version: i32,
    ) -> Result<Option<BackupMetadata>> {
        let metadata = sqlx::query_as::<_, BackupMetadata>(
            "SELECT pubkey, s3_key, backup_size::bigint as backup_size, backup_version
             FROM backup_metadata
             WHERE pubkey = $1 AND backup_version = $2",
        )
        .bind(pubkey)
        .bind(version)
        .fetch_optional(self.pool)
        .await?;

        Ok(metadata)
    }

    /// Deletes a backup record by its version.
    pub async fn delete_by_version(&self, pubkey: &str, version: i32) -> Result<()> {
        sqlx::query("DELETE FROM backup_metadata WHERE pubkey = $1 AND backup_version = $2")
            .bind(pubkey)
            .bind(version)
            .execute(self.pool)
            .await?;
        Ok(())
    }

    /// Inserts or updates backup settings for a user.
    pub async fn upsert_settings(&self, pubkey: &str, enabled: bool) -> Result<()> {
        sqlx::query(
            "INSERT INTO backup_settings (pubkey, backup_enabled)
             VALUES ($1, $2)
             ON CONFLICT(pubkey)
             DO UPDATE SET backup_enabled = excluded.backup_enabled",
        )
        .bind(pubkey)
        .bind(enabled)
        .execute(self.pool)
        .await?;
        Ok(())
    }

    /// Gets the backup settings for a user.
    pub async fn get_settings(&self, pubkey: &str) -> Result<Option<bool>> {
        let enabled = sqlx::query_scalar::<_, bool>(
            "SELECT backup_enabled FROM backup_settings WHERE pubkey = $1",
        )
        .bind(pubkey)
        .fetch_optional(self.pool)
        .await?;

        Ok(enabled)
    }

    /// Finds all pubkeys that have backups enabled.
    pub async fn find_pubkeys_with_backup_enabled(&self) -> Result<Vec<String>> {
        let pubkeys = sqlx::query_scalar::<_, String>(
            "SELECT bs.pubkey
             FROM backup_settings bs
             INNER JOIN users u ON bs.pubkey = u.pubkey
             WHERE bs.backup_enabled = TRUE
               AND u.status = 'active'",
        )
        .fetch_all(self.pool)
        .await?;

        Ok(pubkeys)
    }
}
