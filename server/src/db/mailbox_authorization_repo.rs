use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{FromRow, PgPool, Postgres, Transaction};

#[derive(Debug, Clone, PartialEq, Eq, FromRow)]
pub struct ActiveMailboxAuthorization {
    pub pubkey: String,
    pub mailbox_id: String,
    pub authorization_hex: String,
    pub authorization_expires_at: i64,
    pub auth_version: i64,
    pub last_checkpoint: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, FromRow)]
pub struct RevokedMailboxAuthorization {
    pub pubkey: String,
    pub mailbox_id: String,
    pub last_checkpoint: i64,
}

pub struct MailboxAuthorizationRepository<'a> {
    pool: &'a PgPool,
}

#[derive(Debug, Clone, PartialEq, Eq, FromRow)]
pub struct BulkRenewMailboxClaim {
    pub pubkey: String,
    pub renewed: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, FromRow)]
pub struct MailboxAuthorizationBackfillCandidate {
    pub pubkey: String,
    pub mailbox_id: String,
    pub authorization_hex: String,
    pub authorization_expires_at: i64,
    pub auth_version: i64,
}

impl<'a> MailboxAuthorizationRepository<'a> {
    pub fn new(pool: &'a PgPool) -> Self {
        Self { pool }
    }

    pub async fn upsert(
        &self,
        pubkey: &str,
        mailbox_id: &str,
        authorization_hex: &str,
        authorization_expires_at: i64,
    ) -> Result<()> {
        sqlx::query(
            "INSERT INTO mailbox_authorizations (
                pubkey,
                mailbox_id,
                authorization_hex,
                authorization_expires_at,
                enabled,
                auth_version,
                status,
                failure_count,
                last_error,
                lease_owner,
                lease_expires_at,
                next_retry_at
            )
            VALUES ($1, $2, $3, $4, TRUE, 1, 'active', 0, NULL, NULL, NULL, NULL)
            ON CONFLICT (pubkey) DO UPDATE SET
                mailbox_id = excluded.mailbox_id,
                authorization_hex = excluded.authorization_hex,
                authorization_expires_at = excluded.authorization_expires_at,
                last_checkpoint = CASE
                    WHEN mailbox_authorizations.mailbox_id IS DISTINCT FROM excluded.mailbox_id
                        THEN 0
                    ELSE mailbox_authorizations.last_checkpoint
                END,
                enabled = TRUE,
                auth_version = mailbox_authorizations.auth_version + 1,
                status = 'active',
                failure_count = 0,
                last_error = NULL,
                lease_owner = NULL,
                lease_expires_at = NULL,
                next_retry_at = NULL,
                updated_at = now()",
        )
        .bind(pubkey)
        .bind(mailbox_id)
        .bind(authorization_hex)
        .bind(authorization_expires_at)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn find_by_pubkey(&self, pubkey: &str) -> Result<Option<ActiveMailboxAuthorization>> {
        let record = sqlx::query_as::<_, ActiveMailboxAuthorization>(
            "SELECT
                pubkey,
                mailbox_id,
                authorization_hex,
                authorization_expires_at,
                auth_version,
                last_checkpoint
             FROM mailbox_authorizations
             WHERE pubkey = $1
               AND enabled = TRUE
               AND authorization_hex IS NOT NULL
               AND authorization_expires_at IS NOT NULL",
        )
        .bind(pubkey)
        .fetch_optional(self.pool)
        .await?;

        Ok(record)
    }

    pub async fn has_active_authorization(&self, pubkey: &str, now: i64) -> Result<bool> {
        let exists = sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS (
                SELECT 1
                FROM mailbox_authorizations
                WHERE pubkey = $1
                  AND enabled = TRUE
                  AND status = 'active'
                  AND authorization_hex IS NOT NULL
                  AND authorization_expires_at IS NOT NULL
                  AND authorization_expires_at > $2
            )",
        )
        .bind(pubkey)
        .bind(now)
        .fetch_one(self.pool)
        .await?;

        Ok(exists)
    }

    pub async fn find_all_enabled(&self) -> Result<Vec<ActiveMailboxAuthorization>> {
        let records = sqlx::query_as::<_, ActiveMailboxAuthorization>(
            "SELECT
                ma.pubkey,
                ma.mailbox_id,
                ma.authorization_hex,
                ma.authorization_expires_at,
                ma.auth_version,
                ma.last_checkpoint
             FROM mailbox_authorizations ma
             INNER JOIN users u ON ma.pubkey = u.pubkey
             WHERE ma.enabled = TRUE
               AND ma.authorization_hex IS NOT NULL
               AND ma.authorization_expires_at IS NOT NULL
               AND u.status = 'active'",
        )
        .fetch_all(self.pool)
        .await?;

        Ok(records)
    }

    pub async fn claim_runnable(
        &self,
        now: DateTime<Utc>,
        worker_id: &str,
        lease_expires_at: DateTime<Utc>,
        limit: i64,
    ) -> Result<Vec<ActiveMailboxAuthorization>> {
        let records = sqlx::query_as::<_, ActiveMailboxAuthorization>(
            "WITH candidates AS (
                SELECT ma.pubkey
                FROM mailbox_authorizations ma
                INNER JOIN users u ON ma.pubkey = u.pubkey
                WHERE ma.enabled = TRUE
                  AND ma.status = 'active'
                  AND ma.authorization_hex IS NOT NULL
                  AND ma.authorization_expires_at IS NOT NULL
                  AND ma.authorization_expires_at > $1
                  AND (ma.next_retry_at IS NULL OR ma.next_retry_at <= $2)
                  AND (ma.lease_expires_at IS NULL OR ma.lease_expires_at <= $2)
                  AND u.status = 'active'
                ORDER BY COALESCE(ma.last_connected_at, to_timestamp(0)) ASC, ma.updated_at ASC
                LIMIT $4
                FOR UPDATE SKIP LOCKED
             )
             UPDATE mailbox_authorizations AS mailbox
             SET lease_owner = $3,
                 lease_expires_at = $5,
                 last_connected_at = now(),
                 updated_at = now()
             FROM candidates
             WHERE mailbox.pubkey = candidates.pubkey
             RETURNING
                mailbox.pubkey,
                mailbox.mailbox_id,
                mailbox.authorization_hex,
                mailbox.authorization_expires_at,
                mailbox.auth_version,
                mailbox.last_checkpoint",
        )
        .bind(now.timestamp())
        .bind(now)
        .bind(worker_id)
        .bind(limit)
        .bind(lease_expires_at)
        .fetch_all(self.pool)
        .await?;

        Ok(records)
    }

    pub async fn find_revoked_by_pubkey(
        &self,
        pubkey: &str,
    ) -> Result<Option<RevokedMailboxAuthorization>> {
        let record = sqlx::query_as::<_, RevokedMailboxAuthorization>(
            "SELECT
                pubkey,
                mailbox_id,
                last_checkpoint
             FROM mailbox_authorizations
             WHERE pubkey = $1
               AND enabled = FALSE",
        )
        .bind(pubkey)
        .fetch_optional(self.pool)
        .await?;

        Ok(record)
    }

    pub async fn update_checkpoint(
        &self,
        pubkey: &str,
        checkpoint: i64,
        auth_version: i64,
        worker_id: &str,
    ) -> Result<bool> {
        let result = sqlx::query(
            "UPDATE mailbox_authorizations
             SET last_checkpoint = $2, updated_at = now()
             WHERE pubkey = $1
               AND auth_version = $3
               AND lease_owner = $4",
        )
        .bind(pubkey)
        .bind(checkpoint)
        .bind(auth_version)
        .bind(worker_id)
        .execute(self.pool)
        .await?;

        Ok(result.rows_affected() > 0)
    }

    pub async fn current_failure_count(&self, pubkey: &str) -> Result<i32> {
        let count = sqlx::query_scalar::<_, i32>(
            "SELECT failure_count
             FROM mailbox_authorizations
             WHERE pubkey = $1",
        )
        .bind(pubkey)
        .fetch_one(self.pool)
        .await?;

        Ok(count)
    }

    pub async fn clear_error(
        &self,
        pubkey: &str,
        auth_version: i64,
        worker_id: &str,
    ) -> Result<()> {
        sqlx::query(
            "UPDATE mailbox_authorizations
             SET failure_count = 0,
                 last_error = NULL,
                 lease_owner = NULL,
                 lease_expires_at = NULL,
                 next_retry_at = NULL,
                 status = 'active',
                 updated_at = now()
             WHERE pubkey = $1
               AND auth_version = $2
               AND lease_owner = $3",
        )
        .bind(pubkey)
        .bind(auth_version)
        .bind(worker_id)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn mark_retry(
        &self,
        pubkey: &str,
        next_retry_at: DateTime<Utc>,
        last_error: &str,
        auth_version: i64,
        worker_id: &str,
    ) -> Result<()> {
        sqlx::query(
            "UPDATE mailbox_authorizations
             SET failure_count = failure_count + 1,
                 last_error = $2,
                 lease_owner = NULL,
                 lease_expires_at = NULL,
                 next_retry_at = $3,
                 status = 'active',
                 updated_at = now()
             WHERE pubkey = $1
               AND auth_version = $4
               AND lease_owner = $5",
        )
        .bind(pubkey)
        .bind(last_error)
        .bind(next_retry_at)
        .bind(auth_version)
        .bind(worker_id)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn mark_invalid(
        &self,
        pubkey: &str,
        last_error: &str,
        auth_version: i64,
        worker_id: &str,
    ) -> Result<()> {
        sqlx::query(
            "UPDATE mailbox_authorizations
             SET status = 'invalid',
                 last_error = $2,
                 lease_owner = NULL,
                 lease_expires_at = NULL,
                 next_retry_at = NULL,
                 updated_at = now()
             WHERE pubkey = $1
               AND auth_version = $3
               AND lease_owner = $4",
        )
        .bind(pubkey)
        .bind(last_error)
        .bind(auth_version)
        .bind(worker_id)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn mark_expired(
        &self,
        pubkey: &str,
        last_error: &str,
        auth_version: i64,
        worker_id: &str,
    ) -> Result<()> {
        sqlx::query(
            "UPDATE mailbox_authorizations
             SET status = 'expired',
                 last_error = $2,
                 lease_owner = NULL,
                 lease_expires_at = NULL,
                 next_retry_at = NULL,
                 updated_at = now()
             WHERE pubkey = $1
               AND auth_version = $3
               AND lease_owner = $4",
        )
        .bind(pubkey)
        .bind(last_error)
        .bind(auth_version)
        .bind(worker_id)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn mark_expired_authorizations(&self, now: i64) -> Result<u64> {
        let result = sqlx::query(
            "UPDATE mailbox_authorizations
             SET status = 'expired',
                 last_error = 'mailbox authorization expired',
                 lease_owner = NULL,
                 lease_expires_at = NULL,
                 next_retry_at = NULL,
                 updated_at = now()
             WHERE enabled = TRUE
               AND status = 'active'
               AND authorization_expires_at IS NOT NULL
               AND authorization_expires_at <= $1",
        )
        .bind(now)
        .execute(self.pool)
        .await?;

        Ok(result.rows_affected())
    }

    pub async fn find_active_authorizations_for_backfill(
        &self,
    ) -> Result<Vec<MailboxAuthorizationBackfillCandidate>> {
        let records = sqlx::query_as::<_, MailboxAuthorizationBackfillCandidate>(
            "SELECT
                pubkey,
                mailbox_id,
                authorization_hex,
                authorization_expires_at,
                auth_version
             FROM mailbox_authorizations
             WHERE enabled = TRUE
               AND status = 'active'
               AND authorization_hex IS NOT NULL
               AND authorization_expires_at IS NOT NULL
             ORDER BY updated_at ASC",
        )
        .fetch_all(self.pool)
        .await?;

        Ok(records)
    }

    pub async fn normalize_authorization(
        &self,
        pubkey: &str,
        auth_version: i64,
        mailbox_id: &str,
        authorization_expires_at: i64,
        authorization_hex: &str,
    ) -> Result<()> {
        sqlx::query(
            "UPDATE mailbox_authorizations
             SET mailbox_id = $3,
                 authorization_expires_at = $4,
                 authorization_hex = $5,
                 failure_count = 0,
                 last_error = NULL,
                 lease_owner = NULL,
                 lease_expires_at = NULL,
                 next_retry_at = NULL,
                 status = 'active',
                 updated_at = now()
             WHERE pubkey = $1
               AND auth_version = $2
               AND enabled = TRUE
               AND status = 'active'",
        )
        .bind(pubkey)
        .bind(auth_version)
        .bind(mailbox_id)
        .bind(authorization_expires_at)
        .bind(authorization_hex)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn mark_backfill_expired(
        &self,
        pubkey: &str,
        auth_version: i64,
        mailbox_id: Option<&str>,
        authorization_expires_at: Option<i64>,
        last_error: &str,
    ) -> Result<()> {
        sqlx::query(
            "UPDATE mailbox_authorizations
             SET mailbox_id = COALESCE($3, mailbox_id),
                 authorization_expires_at = COALESCE($4, authorization_expires_at),
                 status = 'expired',
                 last_error = $5,
                 lease_owner = NULL,
                 lease_expires_at = NULL,
                 next_retry_at = NULL,
                 updated_at = now()
             WHERE pubkey = $1
               AND auth_version = $2
               AND enabled = TRUE
               AND status = 'active'",
        )
        .bind(pubkey)
        .bind(auth_version)
        .bind(mailbox_id)
        .bind(authorization_expires_at)
        .bind(last_error)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn mark_backfill_invalid(
        &self,
        pubkey: &str,
        auth_version: i64,
        last_error: &str,
    ) -> Result<()> {
        sqlx::query(
            "UPDATE mailbox_authorizations
             SET status = 'invalid',
                 last_error = $3,
                 lease_owner = NULL,
                 lease_expires_at = NULL,
                 next_retry_at = NULL,
                 updated_at = now()
             WHERE pubkey = $1
               AND auth_version = $2
               AND enabled = TRUE
               AND status = 'active'",
        )
        .bind(pubkey)
        .bind(auth_version)
        .bind(last_error)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn bulk_renew_claims(
        &self,
        active_claims: &[(String, i64)],
        worker_id: &str,
        now: DateTime<Utc>,
        renew_after: DateTime<Utc>,
        lease_expires_at: DateTime<Utc>,
    ) -> Result<Vec<BulkRenewMailboxClaim>> {
        if active_claims.is_empty() {
            return Ok(vec![]);
        }

        let pubkeys = active_claims
            .iter()
            .map(|(pubkey, _)| pubkey.clone())
            .collect::<Vec<_>>();
        let auth_versions = active_claims
            .iter()
            .map(|(_, auth_version)| *auth_version)
            .collect::<Vec<_>>();

        let rows = sqlx::query_as::<_, BulkRenewMailboxClaim>(
            "WITH active(pubkey, auth_version) AS (
                SELECT * FROM UNNEST($1::text[], $2::bigint[])
             ),
             valid AS (
                SELECT mailbox.pubkey, mailbox.auth_version
                FROM mailbox_authorizations AS mailbox
                INNER JOIN active
                  ON mailbox.pubkey = active.pubkey
                 AND mailbox.auth_version = active.auth_version
                WHERE mailbox.lease_owner = $3
                  AND mailbox.enabled = TRUE
                  AND mailbox.status = 'active'
                  AND mailbox.authorization_hex IS NOT NULL
                  AND mailbox.authorization_expires_at IS NOT NULL
                  AND mailbox.authorization_expires_at > $4
                  AND mailbox.lease_expires_at IS NOT NULL
                  AND mailbox.lease_expires_at > $7
             ),
             renewed AS (
                UPDATE mailbox_authorizations AS mailbox
                SET lease_expires_at = $6,
                    updated_at = now()
                FROM valid
                WHERE mailbox.pubkey = valid.pubkey
                  AND mailbox.auth_version = valid.auth_version
                  AND mailbox.lease_owner = $3
                  AND mailbox.enabled = TRUE
                  AND mailbox.status = 'active'
                  AND mailbox.authorization_hex IS NOT NULL
                  AND mailbox.authorization_expires_at IS NOT NULL
                  AND mailbox.authorization_expires_at > $4
                  AND mailbox.lease_expires_at IS NOT NULL
                  AND mailbox.lease_expires_at > $7
                  AND mailbox.lease_expires_at <= $5
                RETURNING mailbox.pubkey
             )
             SELECT
                valid.pubkey,
                renewed.pubkey IS NOT NULL AS renewed
             FROM valid
             LEFT JOIN renewed ON renewed.pubkey = valid.pubkey",
        )
        .bind(pubkeys)
        .bind(auth_versions)
        .bind(worker_id)
        .bind(now.timestamp())
        .bind(renew_after)
        .bind(lease_expires_at)
        .bind(now)
        .fetch_all(self.pool)
        .await?;

        Ok(rows)
    }

    pub async fn claim_is_active(
        &self,
        pubkey: &str,
        auth_version: i64,
        worker_id: &str,
        now: DateTime<Utc>,
    ) -> Result<bool> {
        let exists = sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS (
                SELECT 1
                FROM mailbox_authorizations
                WHERE pubkey = $1
                  AND auth_version = $2
                  AND lease_owner = $3
                  AND enabled = TRUE
                  AND status = 'active'
                  AND authorization_hex IS NOT NULL
                  AND authorization_expires_at IS NOT NULL
                  AND authorization_expires_at > $4
                  AND lease_expires_at IS NOT NULL
                  AND lease_expires_at > $5
            )",
        )
        .bind(pubkey)
        .bind(auth_version)
        .bind(worker_id)
        .bind(now.timestamp())
        .bind(now)
        .fetch_one(self.pool)
        .await?;

        Ok(exists)
    }

    pub async fn revoke(&self, pubkey: &str) -> Result<()> {
        sqlx::query(
            "UPDATE mailbox_authorizations
             SET enabled = FALSE,
                 authorization_hex = NULL,
                 authorization_expires_at = NULL,
                 auth_version = auth_version + 1,
                 status = 'revoked',
                 last_error = NULL,
                 lease_owner = NULL,
                 lease_expires_at = NULL,
                 next_retry_at = NULL,
                 updated_at = now()
             WHERE pubkey = $1",
        )
        .bind(pubkey)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn release_claim(
        &self,
        pubkey: &str,
        auth_version: i64,
        worker_id: &str,
    ) -> Result<()> {
        sqlx::query(
            "UPDATE mailbox_authorizations
             SET lease_owner = NULL,
                 lease_expires_at = NULL,
                 updated_at = now()
             WHERE pubkey = $1
               AND auth_version = $2
               AND lease_owner = $3",
        )
        .bind(pubkey)
        .bind(auth_version)
        .bind(worker_id)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn delete_by_pubkey(tx: &mut Transaction<'_, Postgres>, pubkey: &str) -> Result<()> {
        sqlx::query("DELETE FROM mailbox_authorizations WHERE pubkey = $1")
            .bind(pubkey)
            .execute(&mut **tx)
            .await?;

        Ok(())
    }
}
