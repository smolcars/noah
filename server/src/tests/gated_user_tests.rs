use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use chrono::{Duration, Utc};
use http_body_util::BodyExt;
use serde_json::json;
use tower::ServiceExt;

use crate::db::backup_repo::BackupRepository;
use crate::db::heartbeat_repo::HeartbeatRepository;
use crate::db::job_status_repo::JobStatusRepository;
use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
use crate::db::push_token_repo::PushTokenRepository;
use crate::db::user_repo::UserRepository;
use crate::tests::common::{TestUser, create_test_user, setup_test_app};
use crate::types::{UserInfoResponse, UserStatus};

#[tracing_test::traced_test]
#[tokio::test]
async fn test_get_user_info() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    // Setup: Create user with the repository
    let mut tx = app_state.db_pool.begin().await.unwrap();
    UserRepository::create(
        &mut tx,
        &user.pubkey().to_string(),
        "existing@localhost",
        None,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/user_info")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: UserInfoResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.lightning_address, "existing@localhost");
    assert_eq!(res.display_name, None);
    assert_eq!(res.user_status, UserStatus::Active);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_update_ln_address() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    // Setup: Create user with the repository
    let mut tx = app_state.db_pool.begin().await.unwrap();
    UserRepository::create(
        &mut tx,
        &user.pubkey().to_string(),
        "existing@localhost",
        None,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/update_ln_address")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "ln_address": "new@localhost"
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // Verification: Check for updated address with the repository
    let user_repo = UserRepository::new(&app_state.db_pool);
    let updated_user = user_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        updated_user.lightning_address,
        Some("new@localhost".to_string())
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_update_profile_display_name() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    let mut tx = app_state.db_pool.begin().await.unwrap();
    UserRepository::create(
        &mut tx,
        &user.pubkey().to_string(),
        "existing@localhost",
        None,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/update_profile")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "display_name": "  Nitesh  "
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let user_repo = UserRepository::new(&app_state.db_pool);
    let updated_user = user_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(updated_user.display_name, Some("Nitesh".to_string()));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_update_profile_clears_empty_display_name() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    let mut tx = app_state.db_pool.begin().await.unwrap();
    UserRepository::create(
        &mut tx,
        &user.pubkey().to_string(),
        "existing@localhost",
        None,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    let user_repo = UserRepository::new(&app_state.db_pool);
    user_repo
        .update_display_name(&user.pubkey().to_string(), Some("Noah User"))
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/update_profile")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "display_name": ""
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let updated_user = user_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(updated_user.display_name, None);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_update_profile_rejects_long_display_name() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    let mut tx = app_state.db_pool.begin().await.unwrap();
    UserRepository::create(
        &mut tx,
        &user.pubkey().to_string(),
        "existing@localhost",
        None,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/update_profile")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "display_name": "x".repeat(81)
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_deregister_user() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    // 1. Create user and associated data using repositories
    let mut tx = app_state.db_pool.begin().await.unwrap();
    UserRepository::create(&mut tx, &user.pubkey().to_string(), "test@localhost", None)
        .await
        .unwrap();
    tx.commit().await.unwrap();

    let push_token_repo = PushTokenRepository::new(&app_state.db_pool);
    push_token_repo
        .upsert(&user.pubkey().to_string(), "test_push_token")
        .await
        .unwrap();

    let backup_repo = BackupRepository::new(&app_state.db_pool);
    backup_repo
        .upsert_metadata(&user.pubkey().to_string(), "test_s3_key", 1024, 1)
        .await
        .unwrap();
    backup_repo
        .upsert_settings(&user.pubkey().to_string(), true)
        .await
        .unwrap();

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);
    let _heartbeat_notification_id = heartbeat_repo
        .create_notification(&user.pubkey().to_string())
        .await
        .unwrap();

    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    mailbox_repo
        .upsert(
            &user.pubkey().to_string(),
            "deadbeef",
            "deadbeef",
            1_900_000_000_i64,
        )
        .await
        .unwrap();

    // 2. Call deregister endpoint
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/deregister")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // 3. Verify data is deleted from the correct places
    let push_token_repo = PushTokenRepository::new(&app_state.db_pool);
    let token = push_token_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap();
    assert!(token.is_none(), "Push token should be deleted");

    let mailbox_auth = mailbox_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap();
    assert!(
        mailbox_auth.is_none(),
        "Mailbox authorization should be deleted"
    );

    // 4. Verify data is NOT deleted from other tables
    let user_repo = UserRepository::new(&app_state.db_pool);
    let user_record = user_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap();
    let user_record = user_record.expect("User should not be deleted");
    assert_eq!(user_record.status, UserStatus::Deregistered);

    let backup_repo = BackupRepository::new(&app_state.db_pool);
    let metadata = backup_repo
        .find_by_pubkey_and_version(&user.pubkey().to_string(), 1)
        .await
        .unwrap();
    assert!(metadata.is_some(), "Backup metadata should not be deleted");

    let settings = backup_repo
        .get_settings(&user.pubkey().to_string())
        .await
        .unwrap();
    assert!(settings.is_some(), "Backup settings should not be deleted");

    // 5. Verify heartbeat notifications are deleted
    let heartbeat_count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM heartbeat_notifications WHERE pubkey = $1")
            .bind(user.pubkey().to_string())
            .fetch_one(&app_state.db_pool)
            .await
            .unwrap();
    assert_eq!(
        heartbeat_count, 0,
        "Heartbeat notifications should be deleted"
    );

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/user_info")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(
        response.status(),
        StatusCode::OK,
        "Deregistered users should still be able to make authenticated requests"
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_register_push_token_reactivates_deregistered_user() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    let pubkey = user.pubkey().to_string();

    let mut tx = app_state.db_pool.begin().await.unwrap();
    UserRepository::create(&mut tx, &pubkey, "test@localhost", None)
        .await
        .unwrap();
    tx.commit().await.unwrap();

    let user_repo = UserRepository::new(&app_state.db_pool);
    user_repo
        .set_status(&pubkey, UserStatus::Deregistered)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/register_push_token")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "push_token": "ExpoPushToken[test-token]"
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let user_record = user_repo.find_by_pubkey(&pubkey).await.unwrap().unwrap();
    assert_eq!(user_record.status, UserStatus::Active);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_report_job_status_pruning() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    use crate::db::job_status_repo::JobStatusRepository;
    use crate::types::{ReportStatus, ReportType};

    // Insert enough rows through the dispatch path helper to trigger pruning.
    for i in 0..53 {
        let mut tx = app_state.db_pool.begin().await.unwrap();
        JobStatusRepository::create_with_k1_and_prune(
            &mut tx,
            &user.pubkey().to_string(),
            &format!("k1-{}", i),
            &ReportType::Maintenance,
            &ReportStatus::Failure,
            Some(format!("Report {}", i)),
        )
        .await
        .unwrap();
        tx.commit().await.unwrap();

        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
    }

    // Verify that only 30 reports are kept for this report type.
    let count =
        JobStatusRepository::count_by_pubkey(&app_state.db_pool, &user.pubkey().to_string())
            .await
            .unwrap();
    assert_eq!(count, 30);

    // Verify that the remaining reports are the last 30.
    let messages = JobStatusRepository::find_error_messages_by_pubkey_ordered(
        &app_state.db_pool,
        &user.pubkey().to_string(),
    )
    .await
    .unwrap();

    let expected: Vec<String> = (23..53).map(|i| format!("Report {}", i)).collect();
    assert_eq!(messages, expected);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_report_job_status_pruning_keeps_30_per_report_type_with_mixed_statuses() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    use crate::db::job_status_repo::JobStatusRepository;
    use crate::types::{ReportStatus, ReportType};

    for i in 0..35 {
        let mut tx = app_state.db_pool.begin().await.unwrap();
        JobStatusRepository::create_with_k1_and_prune(
            &mut tx,
            &user.pubkey().to_string(),
            &format!("k1-failure-{}", i),
            &ReportType::Maintenance,
            &ReportStatus::Failure,
            Some(format!("Failure {}", i)),
        )
        .await
        .unwrap();
        tx.commit().await.unwrap();
    }

    for i in 0..35 {
        let mut tx = app_state.db_pool.begin().await.unwrap();
        JobStatusRepository::create_with_k1_and_prune(
            &mut tx,
            &user.pubkey().to_string(),
            &format!("k1-success-{}", i),
            &ReportType::Maintenance,
            &ReportStatus::Success,
            None,
        )
        .await
        .unwrap();
        tx.commit().await.unwrap();
    }

    let maintenance_count = JobStatusRepository::count_by_pubkey_and_report_type(
        &app_state.db_pool,
        &user.pubkey().to_string(),
        &ReportType::Maintenance,
    )
    .await
    .unwrap();
    assert_eq!(maintenance_count, 30);

    let total_count =
        JobStatusRepository::count_by_pubkey(&app_state.db_pool, &user.pubkey().to_string())
            .await
            .unwrap();
    assert_eq!(total_count, 30);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_report_job_status_pruning_keeps_30_per_report_type() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    use crate::db::job_status_repo::JobStatusRepository;
    use crate::types::{ReportStatus, ReportType};

    for i in 0..35 {
        let mut tx = app_state.db_pool.begin().await.unwrap();
        JobStatusRepository::create_with_k1_and_prune(
            &mut tx,
            &user.pubkey().to_string(),
            &format!("k1-maintenance-failure-{}", i),
            &ReportType::Maintenance,
            &ReportStatus::Failure,
            Some(format!("Maintenance failure {}", i)),
        )
        .await
        .unwrap();
        tx.commit().await.unwrap();
    }

    for i in 0..35 {
        let mut tx = app_state.db_pool.begin().await.unwrap();
        JobStatusRepository::create_with_k1_and_prune(
            &mut tx,
            &user.pubkey().to_string(),
            &format!("k1-backup-failure-{}", i),
            &ReportType::Backup,
            &ReportStatus::Failure,
            Some(format!("Backup failure {}", i)),
        )
        .await
        .unwrap();
        tx.commit().await.unwrap();
    }

    let maintenance_count = JobStatusRepository::count_by_pubkey_and_report_type(
        &app_state.db_pool,
        &user.pubkey().to_string(),
        &ReportType::Maintenance,
    )
    .await
    .unwrap();
    assert_eq!(maintenance_count, 30);

    let backup_count = JobStatusRepository::count_by_pubkey_and_report_type(
        &app_state.db_pool,
        &user.pubkey().to_string(),
        &ReportType::Backup,
    )
    .await
    .unwrap();
    assert_eq!(backup_count, 30);

    let total_count =
        JobStatusRepository::count_by_pubkey(&app_state.db_pool, &user.pubkey().to_string())
            .await
            .unwrap();
    assert_eq!(total_count, 60);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_report_job_status_updates_existing_pending_entry() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    use crate::types::{ReportJobStatusPayload, ReportStatus, ReportType};

    let notification_k1 = "maintenance-notification-k1";

    sqlx::query(
        "INSERT INTO job_status_reports (pubkey, notification_k1, report_type, status, error_message)
         VALUES ($1, $2, $3, $4, $5)",
    )
    .bind(user.pubkey().to_string())
    .bind(notification_k1)
    .bind("Maintenance")
    .bind("Pending")
    .bind(Option::<String>::None)
    .execute(&app_state.db_pool)
    .await
    .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/report_job_status")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&ReportJobStatusPayload {
                        notification_k1: notification_k1.to_string(),
                        report_type: ReportType::Maintenance,
                        status: ReportStatus::Success,
                        error_message: None,
                    })
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*)
         FROM job_status_reports
         WHERE pubkey = $1 AND notification_k1 = $2",
    )
    .bind(user.pubkey().to_string())
    .bind(notification_k1)
    .fetch_one(&app_state.db_pool)
    .await
    .unwrap();
    assert_eq!(count, 1, "Expected update-in-place, not an extra row");

    let status: String = sqlx::query_scalar(
        "SELECT status
         FROM job_status_reports
         WHERE pubkey = $1 AND notification_k1 = $2",
    )
    .bind(user.pubkey().to_string())
    .bind(notification_k1)
    .fetch_one(&app_state.db_pool)
    .await
    .unwrap();
    assert_eq!(status, "Success");
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_report_job_status_missing_pending_entry_returns_not_found() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    use crate::types::{ReportJobStatusPayload, ReportStatus, ReportType};

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/report_job_status")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&ReportJobStatusPayload {
                        notification_k1: "missing-pending-k1".to_string(),
                        report_type: ReportType::Maintenance,
                        status: ReportStatus::Failure,
                        error_message: Some("missing pending".to_string()),
                    })
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_report_job_status_rejects_pending_status() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/report_job_status")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "notification_k1": "pending-status-k1",
                        "report_type": "maintenance",
                        "status": "pending",
                        "error_message": null
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let err: crate::types::ApiErrorResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(err.code, "INVALID_ARGUMENT");
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_report_job_status_rejects_timeout_status() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/report_job_status")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "notification_k1": "timeout-status-k1",
                        "report_type": "maintenance",
                        "status": "timeout",
                        "error_message": null
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let err: crate::types::ApiErrorResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(err.code, "INVALID_ARGUMENT");
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_stale_pending_job_reports_are_marked_timeout_after_one_hour() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    use crate::types::{ReportStatus, ReportType};

    let old_k1 = "old-pending-k1";
    let fresh_k1 = "fresh-pending-k1";
    let old_created_at = Utc::now() - Duration::minutes(61);
    let fresh_created_at = Utc::now() - Duration::minutes(30);

    JobStatusRepository::create_with_k1_and_created_at(
        &app_state.db_pool,
        &user.pubkey().to_string(),
        old_k1,
        &ReportType::Maintenance,
        &ReportStatus::Pending,
        None,
        old_created_at,
    )
    .await
    .unwrap();

    JobStatusRepository::create_with_k1_and_created_at(
        &app_state.db_pool,
        &user.pubkey().to_string(),
        fresh_k1,
        &ReportType::Backup,
        &ReportStatus::Pending,
        None,
        fresh_created_at,
    )
    .await
    .unwrap();

    crate::cron::timeout_stale_pending_job_reports(app_state.clone())
        .await
        .unwrap();

    let old_row = JobStatusRepository::find_status_and_error_by_k1(
        &app_state.db_pool,
        &user.pubkey().to_string(),
        old_k1,
    )
    .await
    .unwrap()
    .unwrap();
    assert_eq!(old_row.0, "Timeout");
    assert_eq!(
        old_row.1,
        Some("Timed out after 1 hour waiting for client response".to_string())
    );

    let fresh_row = JobStatusRepository::find_status_and_error_by_k1(
        &app_state.db_pool,
        &user.pubkey().to_string(),
        fresh_k1,
    )
    .await
    .unwrap()
    .unwrap();
    assert_eq!(fresh_row.0, "Pending");
    assert_eq!(fresh_row.1, None);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_stale_pending_timeout_cleanup_does_not_override_existing_error_message() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    use crate::types::{ReportStatus, ReportType};

    let k1 = "old-pending-with-error-k1";
    let old_created_at = Utc::now() - Duration::minutes(75);

    JobStatusRepository::create_with_k1_and_created_at(
        &app_state.db_pool,
        &user.pubkey().to_string(),
        k1,
        &ReportType::Maintenance,
        &ReportStatus::Pending,
        Some("Already has an error".to_string()),
        old_created_at,
    )
    .await
    .unwrap();

    crate::cron::timeout_stale_pending_job_reports(app_state.clone())
        .await
        .unwrap();

    let row = JobStatusRepository::find_status_and_error_by_k1(
        &app_state.db_pool,
        &user.pubkey().to_string(),
        k1,
    )
    .await
    .unwrap()
    .unwrap();
    assert_eq!(row.0, "Timeout");
    assert_eq!(row.1, Some("Already has an error".to_string()));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_register_new_user_with_ark_address() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    let ark_address = Some(
        "tark1p0qtgclpzqqppvmzrkt3kyyqd4lv3jxex32zagcu0fwfm4dkr8ud58h5ej53u4wcpqqtzhwd8"
            .to_string(),
    );

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/register")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "ln_address": "newuserark@localhost",
                        "ark_address": ark_address,
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // Verify ark_address in DB
    let user_repo = UserRepository::new(&app_state.db_pool);
    let registered_user = user_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(registered_user.ark_address, ark_address);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_register_existing_user_update_ark_address() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await; // Register without ark_address
    let access_token = user.access_token(&app_state);

    let new_ark_address =
        Some("tark1newarkaddress1234567890abcdefghijklmnopqrstuvwxyza".to_string());

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/register")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "ln_address": "existinguserark@localhost", // Can be same or different
                        "ark_address": new_ark_address,
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // Verify ark_address is updated in DB
    let user_repo = UserRepository::new(&app_state.db_pool);
    let updated_user = user_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(updated_user.ark_address, new_ark_address);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_register_ark_address_taken() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user1 = TestUser::new();
    let user2 = TestUser::new_with_key(&[0x01; 32]);
    let access_token_1 = user1.access_token(&app_state);
    let access_token_2 = user2.access_token(&app_state);
    let taken_ark_address = Some(
        "tark1p0qtgclpzqqppvmzrkt3kyyqd4lv3jxex32zagcu0fwfm4dkr8ud58h5ej53u4wcpqqtzhwd8"
            .to_string(),
    );

    // Register user1 with the ark_address
    let response1 = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/register")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token_1),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "ln_address": "user1ark@localhost",
                        "ark_address": taken_ark_address,
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response1.status(), StatusCode::OK);

    // Try to register user2 with the same ark_address
    let response2 = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/register")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token_2),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "ln_address": "user2ark@localhost",
                        "ark_address": taken_ark_address,
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response2.status(), StatusCode::BAD_REQUEST);
    let body = response2.into_body().collect().await.unwrap().to_bytes();
    assert!(String::from_utf8_lossy(&body).contains("Ark address already taken"));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_update_ark_address_taken() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user1 = TestUser::new();
    let user2 = TestUser::new_with_key(&[0x01; 32]);
    let access_token_1 = user1.access_token(&app_state);
    let access_token_2 = user2.access_token(&app_state);
    let ark_address1 = Some("tark1user1unique1234567890abcdefghijklmnopqrstuvwxyza".to_string());
    let ark_address2 = Some("tark1user2unique1234567890abcdefghijklmnopqrstuvwxyza".to_string());

    // Register user1 with ark_address1
    app.clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/register")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token_1),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "ln_address": "user1@localhost",
                        "ark_address": ark_address1,
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    // Register user2 with ark_address2
    app.clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/register")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token_2),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "ln_address": "user2@localhost",
                        "ark_address": ark_address2,
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    // Try to update user1's ark_address to ark_address2 (which is taken)
    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/register") // Still using /register for update
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token_1),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "ln_address": "user1@localhost", // Can be same or different
                        "ark_address": ark_address2, // This is the taken address
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    assert!(String::from_utf8_lossy(&body).contains("Ark address already taken"));

    // Verify user1's ark_address is still ark_address1
    let user_repo = UserRepository::new(&app_state.db_pool);
    let current_user1 = user_repo
        .find_by_pubkey(&user1.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(current_user1.ark_address, ark_address1);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_report_last_login() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    let mut tx = app_state.db_pool.begin().await.unwrap();
    UserRepository::create(
        &mut tx,
        &user.pubkey().to_string(),
        "testuser@localhost",
        None,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    // Verify last_login_at is initially NULL
    let user_repo = UserRepository::new(&app_state.db_pool);
    let initial_last_login = user_repo
        .get_last_login_at(&user.pubkey().to_string())
        .await
        .unwrap();
    assert!(initial_last_login.is_none());

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/report_last_login")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // Verify last_login_at is now set
    let updated_last_login = user_repo
        .get_last_login_at(&user.pubkey().to_string())
        .await
        .unwrap();
    assert!(updated_last_login.is_some());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_report_last_login_updates_timestamp() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    let mut tx = app_state.db_pool.begin().await.unwrap();
    UserRepository::create(
        &mut tx,
        &user.pubkey().to_string(),
        "testuser2@localhost",
        None,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    // First login
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/report_last_login")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let user_repo = UserRepository::new(&app_state.db_pool);
    let first_login = user_repo
        .get_last_login_at(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();

    // Small delay to ensure timestamp difference
    tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;

    // Second login
    let response2 = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/report_last_login")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response2.status(), StatusCode::OK);

    let second_login = user_repo
        .get_last_login_at(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();

    assert!(second_login > first_login);
}
