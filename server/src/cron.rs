use crate::{
    AppState,
    db::{
        backup_repo::BackupRepository, fiat_rate_repo::FiatRateRepository,
        heartbeat_repo::HeartbeatRepository, job_status_repo::JobStatusRepository,
        mailbox_authorization_repo::MailboxAuthorizationRepository,
        push_token_repo::PushTokenRepository, user_repo::UserRepository,
    },
    fiat_rates,
    notification_coordinator::{NotificationCoordinator, NotificationRequest},
    s3_client::S3BackupClient,
    types::{HeartbeatNotification, NotificationRequestData, UserStatus},
};
use expo_push_notification_client::Priority;
use tokio_cron_scheduler::{Job, JobScheduler};

const STALE_PENDING_JOB_TIMEOUT_MINUTES: i64 = 60;
const STALE_PENDING_JOB_SWEEP_SCHEDULE: &str = "every 10 minutes";
const STALE_PENDING_JOB_ERROR_MESSAGE: &str = "Timed out after 1 hour waiting for client response";
const STALE_PENDING_HEARTBEAT_TIMEOUT_MINUTES: i64 = 60;
const STALE_PENDING_HEARTBEAT_SWEEP_SCHEDULE: &str = "every 10 minutes";
const STALE_BACKUP_UPLOAD_TIMEOUT_MINUTES: i64 = 30;
const STALE_BACKUP_UPLOAD_SWEEP_SCHEDULE: &str = "every 1 hour";
const FIAT_RATE_REFRESH_LOCK_ID: i64 = 2025110501;

pub async fn send_backup_notifications(app_state: AppState) -> anyhow::Result<()> {
    let backup_repo = BackupRepository::new(&app_state.db_pool);

    let pubkeys = backup_repo.find_pubkeys_with_backup_enabled().await?;
    tracing::info!(
        job = "backup",
        user_count = pubkeys.len(),
        "starting backup notifications"
    );

    let coordinator = NotificationCoordinator::new(app_state.clone());

    for pubkey in pubkeys {
        let request = NotificationRequest {
            priority: Priority::Normal,
            data: NotificationRequestData::BackupTrigger,
            target_pubkey: Some(pubkey.clone()),
        };

        if let Err(e) = coordinator.send_notification(request).await {
            tracing::error!(job = "backup", pubkey = %pubkey, error = %e, "notification failed");
        }
    }

    Ok(())
}

pub async fn send_heartbeat_notifications(app_state: AppState) -> anyhow::Result<()> {
    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);

    let active_users = heartbeat_repo.get_active_users().await?;
    tracing::info!(
        job = "heartbeat",
        user_count = active_users.len(),
        "starting heartbeat notifications"
    );

    let coordinator = NotificationCoordinator::new(app_state.clone());

    for pubkey in active_users {
        let notification_id = heartbeat_repo.create_notification(&pubkey).await?;

        let notification_data = NotificationRequestData::Heartbeat(HeartbeatNotification {
            notification_id: notification_id.clone(),
        });

        let request = NotificationRequest {
            priority: Priority::High,
            data: notification_data,
            target_pubkey: Some(pubkey.clone()),
        };

        if let Err(e) = coordinator.send_notification(request).await {
            tracing::error!(job = "heartbeat", pubkey = %pubkey, error = %e, "notification failed");
            // Rollback the created notification record
            if let Err(delete_err) = heartbeat_repo.delete_notification(&notification_id).await {
                tracing::error!(job = "heartbeat", notification_id = %notification_id, error = %delete_err, "failed to delete orphaned notification");
            }
        }
    }

    // Cleanup old notifications
    heartbeat_repo.cleanup_old_notifications().await?;

    Ok(())
}

pub async fn check_and_deregister_inactive_users(app_state: AppState) -> anyhow::Result<()> {
    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);

    let users_to_deregister = heartbeat_repo.get_users_to_deregister().await?;

    if users_to_deregister.is_empty() {
        return Ok(());
    }

    tracing::info!(
        job = "deregister_inactive",
        user_count = users_to_deregister.len(),
        "starting"
    );

    for pubkey in users_to_deregister {
        tracing::debug!(job = "deregister_inactive", pubkey = %pubkey, "processing user");

        // Use a transaction to ensure all or nothing is deleted
        let mut tx = app_state.db_pool.begin().await?;

        if let Err(e) =
            UserRepository::set_status_tx(&mut tx, &pubkey, UserStatus::Deregistered).await
        {
            tracing::error!(job = "deregister_inactive", pubkey = %pubkey, step = "user_status", error = %e, "status update failed");
            continue;
        }

        if let Err(e) = PushTokenRepository::delete_by_pubkey(&mut tx, &pubkey).await {
            tracing::error!(job = "deregister_inactive", pubkey = %pubkey, step = "push_token", error = %e, "delete failed");
            continue;
        }

        if let Err(e) = MailboxAuthorizationRepository::delete_by_pubkey(&mut tx, &pubkey).await {
            tracing::error!(job = "deregister_inactive", pubkey = %pubkey, step = "mailbox", error = %e, "delete failed");
            continue;
        }

        if let Err(e) = HeartbeatRepository::delete_by_pubkey_tx(&mut tx, &pubkey).await {
            tracing::error!(job = "deregister_inactive", pubkey = %pubkey, step = "heartbeat", error = %e, "delete failed");
            continue;
        }

        if let Err(e) = tx.commit().await {
            tracing::error!(job = "deregister_inactive", pubkey = %pubkey, step = "commit", error = %e, "transaction failed");
        } else {
            tracing::info!(job = "deregister_inactive", pubkey = %pubkey, "user deregistered");
        }
    }

    Ok(())
}

async fn redis_keepalive(app_state: AppState) -> anyhow::Result<()> {
    app_state.k1_cache.contains("keepalive").await?;
    Ok(())
}

pub async fn timeout_stale_pending_job_reports(app_state: AppState) -> anyhow::Result<()> {
    let affected = JobStatusRepository::mark_stale_pending_as_timeout(
        &app_state.db_pool,
        STALE_PENDING_JOB_TIMEOUT_MINUTES,
        STALE_PENDING_JOB_ERROR_MESSAGE,
    )
    .await?;

    if affected > 0 {
        tracing::info!(
            job = "job_status_pending_timeout",
            updated_count = affected,
            timeout_minutes = STALE_PENDING_JOB_TIMEOUT_MINUTES,
            "marked stale pending job reports as timeout"
        );
    }

    Ok(())
}

pub async fn timeout_stale_pending_heartbeats(app_state: AppState) -> anyhow::Result<()> {
    let affected = HeartbeatRepository::mark_stale_pending_as_timeout(
        &app_state.db_pool,
        STALE_PENDING_HEARTBEAT_TIMEOUT_MINUTES,
    )
    .await?;

    if affected > 0 {
        tracing::info!(
            job = "heartbeat_pending_timeout",
            updated_count = affected,
            timeout_minutes = STALE_PENDING_HEARTBEAT_TIMEOUT_MINUTES,
            "marked stale pending heartbeat notifications as timeout"
        );
    }

    Ok(())
}

pub async fn cleanup_stale_backup_uploads(app_state: AppState) -> anyhow::Result<()> {
    let repo = BackupRepository::new(&app_state.db_pool);
    let cutoff =
        chrono::Utc::now() - chrono::Duration::minutes(STALE_BACKUP_UPLOAD_TIMEOUT_MINUTES);
    let stale_uploads = repo.stale_pending_objects(cutoff).await?;
    if stale_uploads.is_empty() {
        return Ok(());
    }

    let s3_client = S3BackupClient::new(app_state.config.s3_bucket_name.clone()).await?;
    let mut deleted_count = 0;
    for upload in stale_uploads {
        if let Err(error) = s3_client.delete_object(&upload.object_key).await {
            tracing::warn!(
                job = "stale_backup_upload_cleanup",
                backup_id = %upload.backup_id,
                error = %error,
                "failed to delete stale backup object"
            );
            continue;
        }
        if repo.delete_object(&upload.pubkey, upload.backup_id).await? {
            deleted_count += 1;
        }
    }

    tracing::info!(
        job = "stale_backup_upload_cleanup",
        deleted_count,
        "removed stale pending backup uploads"
    );
    Ok(())
}

pub async fn mark_expired_mailbox_authorizations(app_state: AppState) -> anyhow::Result<()> {
    let affected = MailboxAuthorizationRepository::new(&app_state.db_pool)
        .mark_expired_authorizations(chrono::Utc::now().timestamp())
        .await?;

    if affected > 0 {
        tracing::info!(
            job = "mailbox_auth_expiry",
            updated_count = affected,
            "marked expired mailbox authorizations"
        );
    }

    Ok(())
}

pub async fn refresh_fiat_rates(app_state: AppState) -> anyhow::Result<()> {
    let mut conn = app_state.db_pool.acquire().await?;
    let lock_acquired: bool = sqlx::query_scalar("SELECT pg_try_advisory_lock($1)")
        .bind(FIAT_RATE_REFRESH_LOCK_ID)
        .fetch_one(&mut *conn)
        .await?;

    if !lock_acquired {
        tracing::debug!(
            job = "fiat_rates",
            "refresh skipped because another worker holds lock"
        );
        return Ok(());
    }

    let result = async {
        let repo = FiatRateRepository::new(&app_state.db_pool);
        fiat_rates::refresh_latest_rates(&app_state.config, &repo).await?;
        fiat_rates::backfill_recent_rates(&app_state.config, &repo).await?;
        anyhow::Ok(())
    }
    .await;

    let unlock_result: Result<bool, sqlx::Error> =
        sqlx::query_scalar("SELECT pg_advisory_unlock($1)")
            .bind(FIAT_RATE_REFRESH_LOCK_ID)
            .fetch_one(&mut *conn)
            .await;

    if let Err(e) = unlock_result {
        tracing::error!(job = "fiat_rates", error = %e, "failed to release advisory lock");
    }

    result
}

pub async fn cron_scheduler(
    app_state: AppState,
    backup_cron: String,
    heartbeat_cron: String,
    deregister_cron: String,
    fiat_rate_refresh_cron: String,
    mailbox_auth_cleanup_cron: String,
) -> anyhow::Result<JobScheduler> {
    let sched = JobScheduler::new().await?;

    tracing::info!(
        service = "cron",
        backup_schedule = %backup_cron,
        heartbeat_schedule = %heartbeat_cron,
        deregister_schedule = %deregister_cron,
        fiat_rate_refresh_schedule = %fiat_rate_refresh_cron,
        mailbox_auth_cleanup_schedule = %mailbox_auth_cleanup_cron,
        stale_pending_job_cleanup_schedule = %STALE_PENDING_JOB_SWEEP_SCHEDULE,
        stale_pending_job_timeout_minutes = STALE_PENDING_JOB_TIMEOUT_MINUTES,
        stale_pending_heartbeat_cleanup_schedule = %STALE_PENDING_HEARTBEAT_SWEEP_SCHEDULE,
        stale_pending_heartbeat_timeout_minutes = STALE_PENDING_HEARTBEAT_TIMEOUT_MINUTES,
        stale_backup_upload_cleanup_schedule = %STALE_BACKUP_UPLOAD_SWEEP_SCHEDULE,
        stale_backup_upload_timeout_minutes = STALE_BACKUP_UPLOAD_TIMEOUT_MINUTES,
        "scheduler initialized"
    );

    let backup_app_state = app_state.clone();
    let backup_job = Job::new_async(&backup_cron, move |_, _| {
        let app_state = backup_app_state.clone();
        Box::pin(async move {
            if let Err(e) = send_backup_notifications(app_state).await {
                tracing::error!(job = "backup", error = %e, "job failed");
            }
        })
    })?;
    sched.add(backup_job).await?;

    // Heartbeat notifications
    let heartbeat_app_state = app_state.clone();
    let heartbeat_job = Job::new_async(&heartbeat_cron, move |_, _| {
        let app_state = heartbeat_app_state.clone();
        Box::pin(async move {
            if let Err(e) = send_heartbeat_notifications(app_state).await {
                tracing::error!(job = "heartbeat", error = %e, "job failed");
            }
        })
    })?;
    sched.add(heartbeat_job).await?;

    // Check for inactive users
    let inactive_check_app_state = app_state.clone();
    let inactive_check_job = Job::new_async(&deregister_cron, move |_, _| {
        let app_state = inactive_check_app_state.clone();
        Box::pin(async move {
            if let Err(e) = check_and_deregister_inactive_users(app_state).await {
                tracing::error!(job = "deregister_inactive", error = %e, "job failed");
            }
        })
    })?;
    sched.add(inactive_check_job).await?;

    let fiat_rate_refresh_state = app_state.clone();
    let fiat_rate_refresh_job = Job::new_async(&fiat_rate_refresh_cron, move |_, _| {
        let app_state = fiat_rate_refresh_state.clone();
        Box::pin(async move {
            if let Err(e) = refresh_fiat_rates(app_state).await {
                tracing::error!(job = "fiat_rates", error = %e, "job failed");
            }
        })
    })?;
    sched.add(fiat_rate_refresh_job).await?;

    let mailbox_auth_cleanup_state = app_state.clone();
    let mailbox_auth_cleanup_job = Job::new_async(&mailbox_auth_cleanup_cron, move |_, _| {
        let app_state = mailbox_auth_cleanup_state.clone();
        Box::pin(async move {
            if let Err(e) = mark_expired_mailbox_authorizations(app_state).await {
                tracing::error!(job = "mailbox_auth_expiry", error = %e, "job failed");
            }
        })
    })?;
    sched.add(mailbox_auth_cleanup_job).await?;

    // Mark stale pending job reports as timeout
    let stale_pending_job_cleanup_state = app_state.clone();
    let stale_pending_job_cleanup =
        Job::new_async(STALE_PENDING_JOB_SWEEP_SCHEDULE, move |_, _| {
            let app_state = stale_pending_job_cleanup_state.clone();
            Box::pin(async move {
                if let Err(e) = timeout_stale_pending_job_reports(app_state).await {
                    tracing::error!(job = "job_status_pending_timeout", error = %e, "job failed");
                }
            })
        })?;
    sched.add(stale_pending_job_cleanup).await?;

    // Mark stale pending heartbeat notifications as timeout
    let stale_pending_heartbeat_cleanup_state = app_state.clone();
    let stale_pending_heartbeat_cleanup =
        Job::new_async(STALE_PENDING_HEARTBEAT_SWEEP_SCHEDULE, move |_, _| {
            let app_state = stale_pending_heartbeat_cleanup_state.clone();
            Box::pin(async move {
                if let Err(e) = timeout_stale_pending_heartbeats(app_state).await {
                    tracing::error!(job = "heartbeat_pending_timeout", error = %e, "job failed");
                }
            })
        })?;
    sched.add(stale_pending_heartbeat_cleanup).await?;

    let stale_backup_upload_cleanup_state = app_state.clone();
    let stale_backup_upload_cleanup =
        Job::new_async(STALE_BACKUP_UPLOAD_SWEEP_SCHEDULE, move |_, _| {
            let app_state = stale_backup_upload_cleanup_state.clone();
            Box::pin(async move {
                if let Err(e) = cleanup_stale_backup_uploads(app_state).await {
                    tracing::error!(
                        job = "stale_backup_upload_cleanup",
                        error = %e,
                        "job failed"
                    );
                }
            })
        })?;
    sched.add(stale_backup_upload_cleanup).await?;

    // Redis keepalive to prevent Upstash idle connection timeout
    let keepalive_app_state = app_state.clone();
    let keepalive_job = Job::new_async("every 2 minutes", move |_, _| {
        let app_state = keepalive_app_state.clone();
        Box::pin(async move {
            if let Err(e) = redis_keepalive(app_state).await {
                tracing::warn!(job = "redis_keepalive", error = %e, "ping failed");
            }
        })
    })?;
    sched.add(keepalive_job).await?;

    Ok(sched)
}
