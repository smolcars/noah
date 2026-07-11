use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{PgPool, Row, postgres::PgRow};
use std::convert::TryFrom;

use crate::types::{BackupInfo, BackupObjectInfo};

const COMPLETED_BACKUP_RETENTION: i64 = 3;

/// Represents a record from the `backup_metadata` table.
#[derive(Debug)]
pub struct BackupMetadata {
    pub pubkey: String,
    pub s3_key: String,
    pub backup_size: u64,
    pub backup_version: i32,
}

#[derive(Debug)]
pub struct BackupObject {
    pub backup_id: uuid::Uuid,
    pub pubkey: String,
    pub object_key: String,
    pub format_version: i32,
    pub encrypted_size: u64,
    pub encrypted_sha256: String,
    pub completed_at: Option<DateTime<Utc>>,
}

impl<'r> sqlx::FromRow<'r, PgRow> for BackupObject {
    fn from_row(row: &'r PgRow) -> Result<Self, sqlx::Error> {
        Ok(Self {
            backup_id: row.try_get("backup_id")?,
            pubkey: row.try_get("pubkey")?,
            object_key: row.try_get("object_key")?,
            format_version: row.try_get("format_version")?,
            encrypted_size: row.try_get::<i64, _>("encrypted_size")? as u64,
            encrypted_sha256: row.try_get("encrypted_sha256")?,
            completed_at: row.try_get("completed_at")?,
        })
    }
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

    pub async fn create_pending_object(
        &self,
        backup_id: uuid::Uuid,
        pubkey: &str,
        object_key: &str,
        format_version: i32,
        encrypted_size: u64,
        encrypted_sha256: &str,
    ) -> Result<BackupObject> {
        let size = i64::try_from(encrypted_size)?;
        Ok(sqlx::query_as::<_, BackupObject>(
            "INSERT INTO backup_objects
                (backup_id, pubkey, object_key, format_version, encrypted_size, encrypted_sha256)
             VALUES ($1, $2, $3, $4, $5, $6)
             ON CONFLICT (pubkey) WHERE status = 'pending'
             DO UPDATE SET pubkey = backup_objects.pubkey
             RETURNING backup_id, pubkey, object_key, format_version, encrypted_size,
                       encrypted_sha256, completed_at",
        )
        .bind(backup_id)
        .bind(pubkey)
        .bind(object_key)
        .bind(format_version)
        .bind(size)
        .bind(encrypted_sha256)
        .fetch_one(self.pool)
        .await?)
    }

    pub async fn find_object(
        &self,
        pubkey: &str,
        backup_id: uuid::Uuid,
    ) -> Result<Option<BackupObject>> {
        Ok(sqlx::query_as::<_, BackupObject>(
            "SELECT backup_id, pubkey, object_key, format_version, encrypted_size,
                    encrypted_sha256, completed_at
             FROM backup_objects
             WHERE pubkey = $1 AND backup_id = $2",
        )
        .bind(pubkey)
        .bind(backup_id)
        .fetch_optional(self.pool)
        .await?)
    }

    pub async fn complete_object(&self, pubkey: &str, backup_id: uuid::Uuid) -> Result<bool> {
        let result = sqlx::query(
            "UPDATE backup_objects
             SET status = 'completed', completed_at = COALESCE(completed_at, now())
             WHERE pubkey = $1 AND backup_id = $2 AND status = 'pending'",
        )
        .bind(pubkey)
        .bind(backup_id)
        .execute(self.pool)
        .await?;
        Ok(result.rows_affected() == 1)
    }

    pub async fn list_completed_objects(&self, pubkey: &str) -> Result<Vec<BackupObjectInfo>> {
        let rows = sqlx::query(
            "SELECT backup_id, format_version, completed_at, encrypted_size, encrypted_sha256
             FROM backup_objects
             WHERE pubkey = $1 AND status = 'completed'
             ORDER BY completed_at DESC",
        )
        .bind(pubkey)
        .fetch_all(self.pool)
        .await?;

        rows.into_iter()
            .map(|row| {
                let completed_at: DateTime<Utc> = row.try_get("completed_at")?;
                let encrypted_size: i64 = row.try_get("encrypted_size")?;
                Ok(BackupObjectInfo {
                    backup_id: row.try_get::<uuid::Uuid, _>("backup_id")?.to_string(),
                    format_version: row.try_get("format_version")?,
                    created_at: completed_at.to_rfc3339(),
                    encrypted_size: encrypted_size as u64,
                    encrypted_sha256: row.try_get("encrypted_sha256")?,
                })
            })
            .collect::<Result<Vec<_>, sqlx::Error>>()
            .map_err(Into::into)
    }

    pub async fn find_completed_object(
        &self,
        pubkey: &str,
        backup_id: Option<uuid::Uuid>,
    ) -> Result<Option<BackupObject>> {
        let object = if let Some(backup_id) = backup_id {
            sqlx::query_as::<_, BackupObject>(
                "SELECT backup_id, pubkey, object_key, format_version, encrypted_size,
                        encrypted_sha256, completed_at
                 FROM backup_objects
                 WHERE pubkey = $1 AND backup_id = $2 AND status = 'completed'",
            )
            .bind(pubkey)
            .bind(backup_id)
            .fetch_optional(self.pool)
            .await?
        } else {
            sqlx::query_as::<_, BackupObject>(
                "SELECT backup_id, pubkey, object_key, format_version, encrypted_size,
                        encrypted_sha256, completed_at
                 FROM backup_objects
                 WHERE pubkey = $1 AND status = 'completed'
                 ORDER BY completed_at DESC
                 LIMIT 1",
            )
            .bind(pubkey)
            .fetch_optional(self.pool)
            .await?
        };
        Ok(object)
    }

    pub async fn completed_objects_beyond_retention(
        &self,
        pubkey: &str,
    ) -> Result<Vec<BackupObject>> {
        Ok(sqlx::query_as::<_, BackupObject>(
            "SELECT backup_id, pubkey, object_key, format_version, encrypted_size,
                    encrypted_sha256, completed_at
             FROM backup_objects
             WHERE pubkey = $1 AND status = 'completed'
             ORDER BY completed_at DESC
             OFFSET $2",
        )
        .bind(pubkey)
        .bind(COMPLETED_BACKUP_RETENTION)
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn stale_pending_objects(
        &self,
        created_before: DateTime<Utc>,
    ) -> Result<Vec<BackupObject>> {
        Ok(sqlx::query_as::<_, BackupObject>(
            "SELECT backup_id, pubkey, object_key, format_version, encrypted_size,
                    encrypted_sha256, completed_at
             FROM backup_objects
             WHERE status = 'pending' AND created_at < $1
             ORDER BY created_at ASC",
        )
        .bind(created_before)
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn delete_object(&self, pubkey: &str, backup_id: uuid::Uuid) -> Result<bool> {
        let result = sqlx::query("DELETE FROM backup_objects WHERE pubkey = $1 AND backup_id = $2")
            .bind(pubkey)
            .bind(backup_id)
            .execute(self.pool)
            .await?;
        Ok(result.rows_affected() == 1)
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
