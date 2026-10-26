use crate::{
    AppState,
    db::{
        job_status_repo::JobStatusRepository,
        notification_tracking_repo::NotificationTrackingRepository,
    },
    push::{PushDispatchReceipt, send_push_notification_with_unique_k1},
    types::{NotificationRequestData, ReportStatus},
};
use anyhow::Result;
use chrono::Utc;
use expo_push_notification_client::Priority;
use tracing::{debug, info, warn};

#[derive(Debug, Clone)]
pub struct NotificationRequest {
    pub priority: Priority,
    pub data: NotificationRequestData,
    pub target_pubkey: Option<String>, // None means broadcast to all users
}

pub struct NotificationCoordinator {
    app_state: AppState,
    min_spacing_minutes: i64,
}

impl NotificationCoordinator {
    pub fn new(app_state: AppState) -> Self {
        let min_spacing_minutes = app_state.config.notification_spacing_minutes;
        Self {
            app_state,
            min_spacing_minutes,
        }
    }

    /// Send a notification with coordination and spacing rules
    pub async fn send_notification(&self, request: NotificationRequest) -> Result<()> {
        let tracking_repo = NotificationTrackingRepository::new(&self.app_state.db_pool);

        match request.target_pubkey {
            Some(ref pubkey) => {
                self.send_to_user(pubkey, &request, &tracking_repo).await?;
            }
            None => {
                self.broadcast_notification(&request, &tracking_repo)
                    .await?;
            }
        }

        Ok(())
    }

    /// Send a notification to a specific user with coordination checks
    async fn send_to_user(
        &self,
        pubkey: &str,
        request: &NotificationRequest,
        tracking_repo: &NotificationTrackingRepository<'_>,
    ) -> Result<()> {
        // Check if user should receive this notification
        if !self
            .should_send_to_user(pubkey, request, tracking_repo)
            .await?
        {
            debug!(
                "Skipping {} notification to {} due to coordination rules",
                request.data.notification_type(),
                pubkey
            );
            return Ok(());
        }

        // Send the notification
        let dispatches = send_push_notification_with_unique_k1(
            self.app_state.clone(),
            request.data.clone(),
            Some(pubkey.to_string()),
        )
        .await?;

        // The push module logs why: no push token, or the send failed.
        if dispatches.is_empty() {
            debug!(
                "No {} notification dispatched to {}",
                request.data.notification_type(),
                pubkey
            );
            return Ok(());
        }

        self.record_pending_job_reports(&request.data, &dispatches)
            .await?;

        info!(
            "Sent {} notification to {}",
            request.data.notification_type(),
            pubkey
        );

        Ok(())
    }

    /// Broadcast a notification to all eligible users
    async fn broadcast_notification(
        &self,
        request: &NotificationRequest,
        tracking_repo: &NotificationTrackingRepository<'_>,
    ) -> Result<()> {
        let eligible_users = if request.priority == Priority::High {
            // `Priority::High` is used for critical notifications that go to all users
            self.get_all_users().await?
        } else {
            // Normal notifications respect spacing
            tracking_repo
                .get_eligible_users(self.min_spacing_minutes)
                .await?
        };

        if eligible_users.is_empty() {
            debug!(
                "No eligible users for {} notification",
                request.data.notification_type()
            );
            return Ok(());
        }

        info!(
            "Broadcasting {} notification to {} users",
            request.data.notification_type(),
            eligible_users.len()
        );

        let mut sent_count = 0;
        let mut skipped_count = 0;

        for pubkey in eligible_users {
            // For Normal priority, users are already filtered by get_eligible_users()
            // For High priority, we need to check individually (e.g., spacing rules)
            let should_send = if request.priority == Priority::High {
                self.should_send_to_user(&pubkey, request, tracking_repo)
                    .await?
            } else {
                true
            };

            if should_send {
                // Send the notification
                let dispatches = match send_push_notification_with_unique_k1(
                    self.app_state.clone(),
                    request.data.clone(),
                    Some(pubkey.clone()),
                )
                .await
                {
                    Ok(dispatches) => dispatches,
                    Err(e) => {
                        warn!("Failed to send notification to {}: {}", pubkey, e);
                        continue;
                    }
                };

                if dispatches.is_empty() {
                    debug!(
                        "No {} notification dispatched to {}",
                        request.data.notification_type(),
                        pubkey
                    );
                    continue;
                }

                self.record_pending_job_reports(&request.data, &dispatches)
                    .await?;

                sent_count += 1;
            } else {
                skipped_count += 1;
            }
        }

        info!(
            "Broadcast complete for {}: sent={}, skipped={}",
            request.data.notification_type(),
            sent_count,
            skipped_count
        );

        Ok(())
    }

    /// Determine if a notification should be sent to a specific user
    async fn should_send_to_user(
        &self,
        pubkey: &str,
        request: &NotificationRequest,
        tracking_repo: &NotificationTrackingRepository<'_>,
    ) -> Result<bool> {
        // `Priority::High` notifications bypass spacing checks
        if request.priority == Priority::High {
            return Ok(true);
        }

        // For normal priority, check spacing
        let can_send = tracking_repo
            .can_send_notification(pubkey, self.min_spacing_minutes)
            .await?;

        if !can_send
            && let Some(last_time) = tracking_repo.get_last_notification_time(pubkey).await?
        {
            let minutes_since = (Utc::now() - last_time).num_minutes();
            debug!(
                "Spacing check failed for {}: last notification {} minutes ago (need {})",
                pubkey, minutes_since, self.min_spacing_minutes
            );
        }

        Ok(can_send)
    }

    async fn record_pending_job_reports(
        &self,
        notification_data: &NotificationRequestData,
        dispatches: &[PushDispatchReceipt],
    ) -> Result<()> {
        let Some(report_type) = notification_data.report_type() else {
            return Ok(());
        };

        for dispatch in dispatches {
            if dispatch.notification_k1.is_empty() {
                warn!(
                    "Missing notification k1 for dispatched {} notification to {}",
                    notification_data.notification_type(),
                    dispatch.pubkey
                );
                continue;
            }

            let mut tx = self.app_state.db_pool.begin().await?;
            JobStatusRepository::create_with_k1_and_prune(
                &mut tx,
                &dispatch.pubkey,
                &dispatch.notification_k1,
                &report_type,
                &ReportStatus::Pending,
                None,
            )
            .await?;
            tx.commit().await?;
        }

        Ok(())
    }

    /// Get all users from the database
    async fn get_all_users(&self) -> Result<Vec<String>> {
        let pubkeys = sqlx::query_scalar::<_, String>("SELECT pubkey FROM users")
            .fetch_all(&self.app_state.db_pool)
            .await?;

        Ok(pubkeys)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_priority_levels() {
        assert_eq!(Priority::High, Priority::High);
        assert_ne!(Priority::High, Priority::Normal);
    }
}
