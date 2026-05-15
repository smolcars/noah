use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::PgPool;

use crate::types::NotificationData;

/// Repository for reading notification timing used by spacing rules.
///
/// Notification send time is now derived from:
/// - `job_status_reports.created_at` for backup/maintenance dispatches
/// - `heartbeat_notifications.sent_at` for heartbeat dispatches
pub struct NotificationTrackingRepository<'a> {
    pool: &'a PgPool,
}

impl<'a> NotificationTrackingRepository<'a> {
    pub fn new(pool: &'a PgPool) -> Self {
        Self { pool }
    }

    /// Check if enough time has passed since the last notification of any type to this user
    /// Returns true if we can send a notification (respecting minimum spacing)
    pub async fn can_send_notification(
        &self,
        pubkey: &str,
        min_spacing_minutes: i64,
    ) -> Result<bool> {
        let last_sent = self.get_last_notification_time(pubkey).await?;
        if let Some(last_sent) = last_sent {
            let min_time = Utc::now() - chrono::Duration::minutes(min_spacing_minutes);
            return Ok(last_sent < min_time);
        }

        Ok(true)
    }

    /// Get the last time any notification was sent to this user
    pub async fn get_last_notification_time(&self, pubkey: &str) -> Result<Option<DateTime<Utc>>> {
        let last_sent = sqlx::query_scalar::<_, Option<DateTime<Utc>>>(
            "SELECT MAX(sent_at) FROM (
                 SELECT created_at AS sent_at
                 FROM job_status_reports
                 WHERE pubkey = $1
                 UNION ALL
                 SELECT sent_at
                 FROM heartbeat_notifications
                 WHERE pubkey = $1
             ) notifications",
        )
        .bind(pubkey)
        .fetch_one(self.pool)
        .await?;

        Ok(last_sent)
    }

    /// Get all users who are eligible for a notification based on spacing requirements.
    /// Returns list of pubkeys that can receive the notification
    pub async fn get_eligible_users(&self, min_spacing_minutes: i64) -> Result<Vec<String>> {
        let min_time = Utc::now() - chrono::Duration::minutes(min_spacing_minutes);

        let pubkeys = sqlx::query_scalar::<_, String>(
            "SELECT u.pubkey
             FROM users u
             WHERE NOT EXISTS (
                 SELECT 1 FROM (
                     SELECT created_at AS sent_at
                     FROM job_status_reports
                     WHERE pubkey = u.pubkey
                     UNION ALL
                     SELECT sent_at
                     FROM heartbeat_notifications
                     WHERE pubkey = u.pubkey
                 ) notifications
                 WHERE notifications.sent_at > $1
             )",
        )
        .bind(min_time)
        .fetch_all(self.pool)
        .await?;

        Ok(pubkeys)
    }

    /// Get the last time a specific notification type was sent to a user.
    ///
    /// # Type Safety
    /// Accepts `&NotificationData` to ensure type safety. Only the notification
    /// type is extracted and used for the query.
    pub async fn get_last_notification_time_by_type(
        &self,
        pubkey: &str,
        notification_data: &NotificationData,
    ) -> Result<Option<DateTime<Utc>>> {
        let last_sent = match notification_data {
            NotificationData::Maintenance(_) => {
                sqlx::query_scalar::<_, Option<DateTime<Utc>>>(
                    "SELECT MAX(created_at)
                     FROM job_status_reports
                     WHERE pubkey = $1 AND report_type = 'Maintenance'",
                )
                .bind(pubkey)
                .fetch_one(self.pool)
                .await?
            }
            NotificationData::BackupTrigger(_) => {
                sqlx::query_scalar::<_, Option<DateTime<Utc>>>(
                    "SELECT MAX(created_at)
                     FROM job_status_reports
                     WHERE pubkey = $1 AND report_type = 'Backup'",
                )
                .bind(pubkey)
                .fetch_one(self.pool)
                .await?
            }
            NotificationData::Heartbeat(_) => {
                sqlx::query_scalar::<_, Option<DateTime<Utc>>>(
                    "SELECT MAX(sent_at)
                     FROM heartbeat_notifications
                     WHERE pubkey = $1",
                )
                .bind(pubkey)
                .fetch_one(self.pool)
                .await?
            }
            NotificationData::LightningInvoiceRequest(_)
            | NotificationData::LightningClaimRequest(_) => None,
        };

        Ok(last_sent)
    }
}
