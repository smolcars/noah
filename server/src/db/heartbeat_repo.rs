use anyhow::Result;
use sqlx::{PgPool, Postgres, Transaction};
use uuid::Uuid;

use crate::types::HeartbeatStatus;
#[cfg(test)]
use std::str::FromStr;

pub struct HeartbeatRepository<'a> {
    pool: &'a PgPool,
}

impl<'a> HeartbeatRepository<'a> {
    pub fn new(pool: &'a PgPool) -> Self {
        Self { pool }
    }

    /// Creates a new heartbeat notification record
    pub async fn create_notification(&self, pubkey: &str) -> Result<String> {
        let notification_id = Uuid::new_v4().to_string();

        sqlx::query(
            "INSERT INTO heartbeat_notifications (pubkey, notification_id, status)
             VALUES ($1, $2, $3)",
        )
        .bind(pubkey)
        .bind(notification_id.clone())
        .bind(HeartbeatStatus::Pending.to_string())
        .execute(self.pool)
        .await?;

        Ok(notification_id)
    }

    /// Marks a heartbeat notification as responded for the owning user.
    pub async fn mark_as_responded(&self, notification_id: &str, pubkey: &str) -> Result<bool> {
        let result = sqlx::query(
            "UPDATE heartbeat_notifications
             SET responded_at = now(), status = $1
             WHERE notification_id = $2
               AND pubkey = $3
               AND status = $4",
        )
        .bind(HeartbeatStatus::Responded.to_string())
        .bind(notification_id)
        .bind(pubkey)
        .bind(HeartbeatStatus::Pending.to_string())
        .execute(self.pool)
        .await?;

        Ok(result.rows_affected() > 0)
    }

    /// Marks stale pending heartbeat notifications as timeout after the given age threshold.
    pub async fn mark_stale_pending_as_timeout(
        pool: &sqlx::PgPool,
        older_than_minutes: i64,
    ) -> Result<u64> {
        let result = sqlx::query(
            "UPDATE heartbeat_notifications
             SET status = $1
             WHERE status = $2
               AND sent_at <= now() - ($3::bigint * interval '1 minute')",
        )
        .bind(HeartbeatStatus::Timeout.to_string())
        .bind(HeartbeatStatus::Pending.to_string())
        .bind(older_than_minutes)
        .execute(pool)
        .await?;

        Ok(result.rows_affected())
    }

    /// Deletes a heartbeat notification by its ID
    pub async fn delete_notification(&self, notification_id: &str) -> Result<()> {
        sqlx::query("DELETE FROM heartbeat_notifications WHERE notification_id = $1")
            .bind(notification_id)
            .execute(self.pool)
            .await?;
        Ok(())
    }

    /// Deletes all heartbeat notifications for a user by pubkey
    pub async fn delete_by_pubkey_tx(
        tx: &mut Transaction<'_, Postgres>,
        pubkey: &str,
    ) -> Result<()> {
        sqlx::query("DELETE FROM heartbeat_notifications WHERE pubkey = $1")
            .bind(pubkey)
            .execute(&mut **tx)
            .await?;
        Ok(())
    }

    /// Counts consecutive missed heartbeats for a user (most recent first)
    #[cfg(test)]
    pub async fn count_consecutive_missed(&self, pubkey: &str) -> Result<i32> {
        let rows = sqlx::query_scalar::<_, String>(
            "SELECT status
             FROM heartbeat_notifications
             WHERE pubkey = $1
             ORDER BY sent_at DESC
             LIMIT 10",
        )
        .bind(pubkey)
        .fetch_all(self.pool)
        .await?;

        let mut consecutive_missed = 0;
        for status_str in rows {
            let status = HeartbeatStatus::from_str(&status_str)?;
            if matches!(status, HeartbeatStatus::Pending | HeartbeatStatus::Timeout) {
                consecutive_missed += 1;
            } else {
                break;
            }
        }

        Ok(consecutive_missed)
    }

    /// Gets all users who have push tokens (active users)
    pub async fn get_active_users(&self) -> Result<Vec<String>> {
        let pubkeys = sqlx::query_scalar::<_, String>(
            "SELECT DISTINCT pt.pubkey
             FROM push_tokens pt
             INNER JOIN users u ON pt.pubkey = u.pubkey
             WHERE u.status = 'active'",
        )
        .fetch_all(self.pool)
        .await?;

        Ok(pubkeys)
    }

    /// Cleans up old heartbeat notifications (keeps only last 15 per user)
    pub async fn cleanup_old_notifications(&self) -> Result<()> {
        sqlx::query(
            "DELETE FROM heartbeat_notifications
             WHERE id NOT IN (
                 SELECT id FROM (
                     SELECT id,
                            ROW_NUMBER() OVER (PARTITION BY pubkey ORDER BY sent_at DESC) as rn
                     FROM heartbeat_notifications
                 ) ranked WHERE rn <= 15
             )",
        )
        .execute(self.pool)
        .await?;

        Ok(())
    }

    /// Gets users who have missed 10 or more consecutive heartbeats
    pub async fn get_users_to_deregister(&self) -> Result<Vec<String>> {
        let pubkeys = sqlx::query_scalar::<_, String>(
            "WITH recent_heartbeats AS (
                SELECT hn.pubkey, hn.status, hn.sent_at,
                       ROW_NUMBER() OVER (PARTITION BY hn.pubkey ORDER BY hn.sent_at DESC) as rn
                FROM heartbeat_notifications hn
                INNER JOIN users u ON hn.pubkey = u.pubkey
                WHERE u.status = 'active'
            ),
            consecutive_missed AS (
                SELECT pubkey,
                       COUNT(*) as missed_count
                FROM recent_heartbeats
                WHERE rn <= 10 AND status IN ($1, $2)
                GROUP BY pubkey
                HAVING COUNT(*) >= 10
            )
            SELECT pubkey FROM consecutive_missed",
        )
        .bind(HeartbeatStatus::Pending.to_string())
        .bind(HeartbeatStatus::Timeout.to_string())
        .fetch_all(self.pool)
        .await?;

        Ok(pubkeys)
    }

    /// [TEST ONLY] Inserts a heartbeat with explicit status and sent timestamp.
    #[cfg(test)]
    pub async fn create_with_status_and_sent_at(
        pool: &sqlx::PgPool,
        pubkey: &str,
        notification_id: &str,
        status: HeartbeatStatus,
        sent_at: chrono::DateTime<chrono::Utc>,
    ) -> Result<()> {
        sqlx::query(
            "INSERT INTO heartbeat_notifications (pubkey, notification_id, status, sent_at)
             VALUES ($1, $2, $3, $4)",
        )
        .bind(pubkey)
        .bind(notification_id)
        .bind(status.to_string())
        .bind(sent_at)
        .execute(pool)
        .await?;

        Ok(())
    }

    /// [TEST ONLY] Reads status and responded_at by notification id.
    #[cfg(test)]
    pub async fn find_status_and_responded_at(
        pool: &sqlx::PgPool,
        notification_id: &str,
    ) -> Result<Option<(String, Option<chrono::DateTime<chrono::Utc>>)>> {
        let row = sqlx::query_as::<_, (String, Option<chrono::DateTime<chrono::Utc>>)>(
            "SELECT status, responded_at
             FROM heartbeat_notifications
             WHERE notification_id = $1",
        )
        .bind(notification_id)
        .fetch_optional(pool)
        .await?;

        Ok(row)
    }
}
