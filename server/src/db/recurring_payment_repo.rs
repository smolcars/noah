use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{PgPool, Postgres, Transaction};

/// A wake-up entry for a client-side recurring payment.
///
/// The server only knows an opaque id and the next due time. Amounts,
/// recipients and the spending policy never leave the device.
#[derive(Debug, Clone, PartialEq, Eq, sqlx::FromRow)]
pub struct RecurringPaymentSchedule {
    pub schedule_id: String,
    pub next_run_at: DateTime<Utc>,
    pub last_notified_at: Option<DateTime<Utc>>,
}

/// A user that has at least one recurring payment waiting to be executed.
#[derive(Debug, Clone, PartialEq, Eq, sqlx::FromRow)]
pub struct DueRecurringPaymentUser {
    pub pubkey: String,
    pub due_count: i64,
}

pub struct RecurringPaymentRepository<'a> {
    pool: &'a PgPool,
}

impl<'a> RecurringPaymentRepository<'a> {
    pub fn new(pool: &'a PgPool) -> Self {
        Self { pool }
    }

    /// Replaces the full set of active schedules for a user.
    ///
    /// Schedules missing from `schedules` are deleted (paused, cancelled or
    /// completed on the device). `last_notified_at` is preserved when the due
    /// time did not change, so re-syncing does not trigger duplicate pushes.
    pub async fn replace_for_pubkey(
        tx: &mut Transaction<'_, Postgres>,
        pubkey: &str,
        schedules: &[(String, DateTime<Utc>)],
    ) -> Result<()> {
        let ids: Vec<String> = schedules.iter().map(|(id, _)| id.clone()).collect();
        let next_runs: Vec<DateTime<Utc>> = schedules.iter().map(|(_, at)| *at).collect();

        sqlx::query(
            "DELETE FROM recurring_payment_schedules
             WHERE pubkey = $1 AND NOT (schedule_id = ANY($2))",
        )
        .bind(pubkey)
        .bind(&ids)
        .execute(&mut **tx)
        .await?;

        sqlx::query(
            "INSERT INTO recurring_payment_schedules (pubkey, schedule_id, next_run_at)
             SELECT $1, schedule_id, next_run_at
             FROM UNNEST($2::text[], $3::timestamptz[]) AS s(schedule_id, next_run_at)
             ON CONFLICT (pubkey, schedule_id) DO UPDATE
             SET next_run_at = EXCLUDED.next_run_at,
                 last_notified_at = CASE
                     WHEN recurring_payment_schedules.next_run_at = EXCLUDED.next_run_at
                         THEN recurring_payment_schedules.last_notified_at
                     ELSE NULL
                 END,
                 updated_at = now()",
        )
        .bind(pubkey)
        .bind(&ids)
        .bind(&next_runs)
        .execute(&mut **tx)
        .await?;

        Ok(())
    }

    pub async fn list_for_pubkey(&self, pubkey: &str) -> Result<Vec<RecurringPaymentSchedule>> {
        let rows = sqlx::query_as::<_, RecurringPaymentSchedule>(
            "SELECT schedule_id, next_run_at, last_notified_at
             FROM recurring_payment_schedules
             WHERE pubkey = $1
             ORDER BY next_run_at ASC, schedule_id ASC",
        )
        .bind(pubkey)
        .fetch_all(self.pool)
        .await?;

        Ok(rows)
    }

    /// Returns users with schedules that are due and have not been nudged
    /// recently. Schedules overdue by more than `max_overdue_hours` are ignored;
    /// the device will surface those via its own local reminder instead.
    pub async fn find_due_users(
        &self,
        now: DateTime<Utc>,
        renotify_after_minutes: i64,
        max_overdue_hours: i64,
    ) -> Result<Vec<DueRecurringPaymentUser>> {
        let rows = sqlx::query_as::<_, DueRecurringPaymentUser>(
            "SELECT pubkey, COUNT(*)::bigint AS due_count
             FROM recurring_payment_schedules
             WHERE next_run_at <= $1
               AND next_run_at > $1 - ($3::bigint * interval '1 hour')
               AND (last_notified_at IS NULL
                    OR last_notified_at <= $1 - ($2::bigint * interval '1 minute'))
             GROUP BY pubkey
             ORDER BY pubkey",
        )
        .bind(now)
        .bind(renotify_after_minutes)
        .bind(max_overdue_hours)
        .fetch_all(self.pool)
        .await?;

        Ok(rows)
    }

    /// Marks all currently due schedules of a user as notified.
    pub async fn mark_due_notified(&self, pubkey: &str, now: DateTime<Utc>) -> Result<u64> {
        let result = sqlx::query(
            "UPDATE recurring_payment_schedules
             SET last_notified_at = $2
             WHERE pubkey = $1 AND next_run_at <= $2",
        )
        .bind(pubkey)
        .bind(now)
        .execute(self.pool)
        .await?;

        Ok(result.rows_affected())
    }

    pub async fn delete_by_pubkey(tx: &mut Transaction<'_, Postgres>, pubkey: &str) -> Result<()> {
        sqlx::query("DELETE FROM recurring_payment_schedules WHERE pubkey = $1")
            .bind(pubkey)
            .execute(&mut **tx)
            .await?;
        Ok(())
    }
}
