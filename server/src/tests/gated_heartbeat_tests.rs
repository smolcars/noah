use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use chrono::{Duration, Utc};
use http_body_util::BodyExt;
use serde_json::json;
use tower::ServiceExt;

use crate::db::{
    heartbeat_repo::HeartbeatRepository,
    mailbox_authorization_repo::MailboxAuthorizationRepository,
    push_token_repo::PushTokenRepository, user_repo::UserRepository,
};
use crate::tests::common::{TestUser, create_test_user, setup_test_app};
use crate::types::{DefaultSuccessPayload, HeartbeatStatus, UserStatus};

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_response_success() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    // Create a heartbeat notification first
    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);
    let notification_id = heartbeat_repo
        .create_notification(&user.pubkey().to_string())
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/heartbeat_response")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "notification_id": notification_id
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: DefaultSuccessPayload = serde_json::from_slice(&body).unwrap();
    assert!(res.success);

    // Verify the heartbeat was marked as responded in the database
    let (status, responded_at): (String, Option<chrono::DateTime<Utc>>) = sqlx::query_as(
        "SELECT status, responded_at FROM heartbeat_notifications WHERE notification_id = $1",
    )
    .bind(&notification_id)
    .fetch_one(&app_state.db_pool)
    .await
    .unwrap();

    assert_eq!(status, "responded");
    assert!(responded_at.is_some());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_response_invalid_notification_id() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/heartbeat_response")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "notification_id": "non-existent-notification-id"
                    }))
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
async fn test_heartbeat_response_already_responded() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    // Create a heartbeat notification and mark it as already responded
    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);
    let notification_id = heartbeat_repo
        .create_notification(&user.pubkey().to_string())
        .await
        .unwrap();

    // Mark it as responded first
    heartbeat_repo
        .mark_as_responded(&notification_id, &user.pubkey().to_string())
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/heartbeat_response")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "notification_id": notification_id
                    }))
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
async fn test_heartbeat_response_rejects_other_users_notification() {
    let (app, app_state, _guard) = setup_test_app().await;

    let owner = TestUser::new();
    create_test_user(&app_state, &owner, None).await;

    let responder = TestUser::new_with_key(&[0xab; 32]);
    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)")
        .bind(responder.pubkey().to_string())
        .bind("responder@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();
    let responder_access_token = responder.access_token(&app_state);

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);
    let notification_id = heartbeat_repo
        .create_notification(&owner.pubkey().to_string())
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/heartbeat_response")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", responder_access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "notification_id": notification_id
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NOT_FOUND);

    let (status, responded_at): (String, Option<chrono::DateTime<Utc>>) = sqlx::query_as(
        "SELECT status, responded_at FROM heartbeat_notifications WHERE notification_id = $1",
    )
    .bind(&notification_id)
    .fetch_one(&app_state.db_pool)
    .await
    .unwrap();

    assert_eq!(status, HeartbeatStatus::Pending.to_string());
    assert!(responded_at.is_none());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_response_invalid_token() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    // Create a heartbeat notification
    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);
    let notification_id = heartbeat_repo
        .create_notification(&user.pubkey().to_string())
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/heartbeat_response")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(http::header::AUTHORIZATION, "Bearer invalid-token")
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "notification_id": notification_id
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_repo_create_notification() {
    let (_, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);

    let notification_id = heartbeat_repo
        .create_notification(&user.pubkey().to_string())
        .await
        .unwrap();

    assert!(!notification_id.is_empty());

    let (pubkey, status): (String, String) = sqlx::query_as(
        "SELECT pubkey, status FROM heartbeat_notifications WHERE notification_id = $1",
    )
    .bind(&notification_id)
    .fetch_one(&app_state.db_pool)
    .await
    .unwrap();

    assert_eq!(pubkey, user.pubkey().to_string());
    assert_eq!(status, HeartbeatStatus::Pending.to_string());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_repo_count_consecutive_missed() {
    let (_, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);

    for i in 0..5 {
        let sent_at = Utc::now() - Duration::seconds((100 + i) as i64);
        sqlx::query(
            "INSERT INTO heartbeat_notifications (pubkey, notification_id, status, sent_at)
             VALUES ($1, $2, $3, $4)",
        )
        .bind(user.pubkey().to_string())
        .bind(format!("old-{}", i))
        .bind(HeartbeatStatus::Pending.to_string())
        .bind(sent_at)
        .execute(&app_state.db_pool)
        .await
        .unwrap();
    }

    let responded_sent_at = Utc::now() - Duration::seconds(50);
    sqlx::query(
        "INSERT INTO heartbeat_notifications (pubkey, notification_id, status, sent_at, responded_at)
         VALUES ($1, $2, $3, $4, $5)",
    )
    .bind(user.pubkey().to_string())
    .bind("responded")
    .bind(HeartbeatStatus::Responded.to_string())
    .bind(responded_sent_at)
    .bind(responded_sent_at + Duration::seconds(1))
    .execute(&app_state.db_pool)
    .await
    .unwrap();

    for i in 0..3 {
        let sent_at = Utc::now() - Duration::seconds((10 + i) as i64);
        sqlx::query(
            "INSERT INTO heartbeat_notifications (pubkey, notification_id, status, sent_at)
             VALUES ($1, $2, $3, $4)",
        )
        .bind(user.pubkey().to_string())
        .bind(format!("recent-{}", i))
        .bind(HeartbeatStatus::Pending.to_string())
        .bind(sent_at)
        .execute(&app_state.db_pool)
        .await
        .unwrap();
    }

    // Should count only the 3 most recent missed notifications
    let consecutive_missed = heartbeat_repo
        .count_consecutive_missed(&user.pubkey().to_string())
        .await
        .unwrap();

    assert_eq!(consecutive_missed, 3);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_repo_count_consecutive_missed_includes_timeout() {
    let (_, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);
    let pubkey = user.pubkey().to_string();
    let now = Utc::now();

    HeartbeatRepository::create_with_status_and_sent_at(
        &app_state.db_pool,
        &pubkey,
        "mix-timeout-1",
        HeartbeatStatus::Timeout,
        now - Duration::seconds(10),
    )
    .await
    .unwrap();

    HeartbeatRepository::create_with_status_and_sent_at(
        &app_state.db_pool,
        &pubkey,
        "mix-pending-1",
        HeartbeatStatus::Pending,
        now - Duration::seconds(20),
    )
    .await
    .unwrap();

    HeartbeatRepository::create_with_status_and_sent_at(
        &app_state.db_pool,
        &pubkey,
        "mix-timeout-2",
        HeartbeatStatus::Timeout,
        now - Duration::seconds(30),
    )
    .await
    .unwrap();

    HeartbeatRepository::create_with_status_and_sent_at(
        &app_state.db_pool,
        &pubkey,
        "mix-responded-stop",
        HeartbeatStatus::Responded,
        now - Duration::seconds(40),
    )
    .await
    .unwrap();

    HeartbeatRepository::create_with_status_and_sent_at(
        &app_state.db_pool,
        &pubkey,
        "older-pending",
        HeartbeatStatus::Pending,
        now - Duration::seconds(50),
    )
    .await
    .unwrap();

    let consecutive_missed = heartbeat_repo
        .count_consecutive_missed(&pubkey)
        .await
        .unwrap();
    assert_eq!(consecutive_missed, 3);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_repo_get_users_to_deregister() {
    let (_, app_state, _guard) = setup_test_app().await;

    // Create users with different secret keys
    let user1 = TestUser::new_with_key(&[0xcd; 32]);
    let user2 = TestUser::new_with_key(&[0xab; 32]);

    // Create users with unique lightning addresses
    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)")
        .bind(user1.pubkey().to_string())
        .bind("user1@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)")
        .bind(user2.pubkey().to_string())
        .bind("user2@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);

    // User1: Create 10 missed notifications (should be deregistered)
    for _ in 0..10 {
        heartbeat_repo
            .create_notification(&user1.pubkey().to_string())
            .await
            .unwrap();
        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
    }

    // User2: Create 5 missed notifications (should NOT be deregistered)
    for _ in 0..5 {
        heartbeat_repo
            .create_notification(&user2.pubkey().to_string())
            .await
            .unwrap();
        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
    }

    let users_to_deregister = heartbeat_repo.get_users_to_deregister().await.unwrap();

    assert_eq!(users_to_deregister.len(), 1);
    assert_eq!(users_to_deregister[0], user1.pubkey().to_string());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_repo_get_users_to_deregister_includes_timeout() {
    let (_, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);
    let pubkey = user.pubkey().to_string();

    for i in 0..5 {
        HeartbeatRepository::create_with_status_and_sent_at(
            &app_state.db_pool,
            &pubkey,
            &format!("pending-{}", i),
            HeartbeatStatus::Pending,
            Utc::now() - Duration::minutes((20 - i) as i64),
        )
        .await
        .unwrap();
    }

    for i in 0..5 {
        HeartbeatRepository::create_with_status_and_sent_at(
            &app_state.db_pool,
            &pubkey,
            &format!("timeout-{}", i),
            HeartbeatStatus::Timeout,
            Utc::now() - Duration::minutes((10 - i) as i64),
        )
        .await
        .unwrap();
    }

    let users_to_deregister = heartbeat_repo.get_users_to_deregister().await.unwrap();

    assert_eq!(users_to_deregister.len(), 1);
    assert_eq!(users_to_deregister[0], pubkey);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_stale_pending_heartbeats_are_marked_timeout_after_one_hour() {
    let (_app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let pubkey = user.pubkey().to_string();

    let old_notification_id = "old-pending-heartbeat";
    let fresh_notification_id = "fresh-pending-heartbeat";

    HeartbeatRepository::create_with_status_and_sent_at(
        &app_state.db_pool,
        &pubkey,
        old_notification_id,
        HeartbeatStatus::Pending,
        Utc::now() - Duration::minutes(61),
    )
    .await
    .unwrap();

    HeartbeatRepository::create_with_status_and_sent_at(
        &app_state.db_pool,
        &pubkey,
        fresh_notification_id,
        HeartbeatStatus::Pending,
        Utc::now() - Duration::minutes(30),
    )
    .await
    .unwrap();

    crate::cron::timeout_stale_pending_heartbeats(app_state.clone())
        .await
        .unwrap();

    let old_row =
        HeartbeatRepository::find_status_and_responded_at(&app_state.db_pool, old_notification_id)
            .await
            .unwrap()
            .unwrap();
    assert_eq!(old_row.0, HeartbeatStatus::Timeout.to_string());
    assert!(old_row.1.is_none());

    let fresh_row = HeartbeatRepository::find_status_and_responded_at(
        &app_state.db_pool,
        fresh_notification_id,
    )
    .await
    .unwrap()
    .unwrap();
    assert_eq!(fresh_row.0, HeartbeatStatus::Pending.to_string());
    assert!(fresh_row.1.is_none());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_repo_cleanup_old_notifications() {
    let (_, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);

    // Create 20 notifications (more than the 15 limit)
    for _ in 0..20 {
        heartbeat_repo
            .create_notification(&user.pubkey().to_string())
            .await
            .unwrap();
        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
    }

    // Cleanup old notifications
    heartbeat_repo.cleanup_old_notifications().await.unwrap();

    // Verify only 15 notifications remain
    let count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM heartbeat_notifications WHERE pubkey = $1")
            .bind(user.pubkey().to_string())
            .fetch_one(&app_state.db_pool)
            .await
            .unwrap();

    assert_eq!(count, 15);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_repo_delete_notification() {
    let (_, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);

    // Create a heartbeat notification
    let notification_id = heartbeat_repo
        .create_notification(&user.pubkey().to_string())
        .await
        .unwrap();

    // Verify it exists
    let count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM heartbeat_notifications WHERE notification_id = $1",
    )
    .bind(notification_id.clone())
    .fetch_one(&app_state.db_pool)
    .await
    .unwrap();
    assert_eq!(count, 1);

    // Delete the notification
    heartbeat_repo
        .delete_notification(&notification_id)
        .await
        .unwrap();

    // Verify it no longer exists
    let count_after: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM heartbeat_notifications WHERE notification_id = $1",
    )
    .bind(notification_id.clone())
    .fetch_one(&app_state.db_pool)
    .await
    .unwrap();
    assert_eq!(count_after, 0);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_repo_delete_nonexistent_notification() {
    let (_, app_state, _guard) = setup_test_app().await;

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);

    // Attempt to delete a non-existent notification - should not error
    let result = heartbeat_repo
        .delete_notification("non-existent-notification-id")
        .await;

    assert!(result.is_ok());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_heartbeat_repo_delete_by_pubkey() {
    let (_, app_state, _guard) = setup_test_app().await;

    let user1 = TestUser::new();
    let user2 = TestUser::new_with_key(&[0xab; 32]);
    create_test_user(&app_state, &user1, None).await;

    // Create user2 with unique lightning address
    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)")
        .bind(user2.pubkey().to_string())
        .bind("user2@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    let heartbeat_repo = HeartbeatRepository::new(&app_state.db_pool);

    // Create multiple heartbeat notifications for user1
    let _notification_id1 = heartbeat_repo
        .create_notification(&user1.pubkey().to_string())
        .await
        .unwrap();
    let _notification_id2 = heartbeat_repo
        .create_notification(&user1.pubkey().to_string())
        .await
        .unwrap();

    // Create a heartbeat notification for user2
    let _notification_id3 = heartbeat_repo
        .create_notification(&user2.pubkey().to_string())
        .await
        .unwrap();

    // Verify all notifications exist
    let count1: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM heartbeat_notifications WHERE pubkey = $1")
            .bind(user1.pubkey().to_string())
            .fetch_one(&app_state.db_pool)
            .await
            .unwrap();
    assert_eq!(count1, 2);

    let count2: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM heartbeat_notifications WHERE pubkey = $1")
            .bind(user2.pubkey().to_string())
            .fetch_one(&app_state.db_pool)
            .await
            .unwrap();
    assert_eq!(count2, 1);

    // Delete all heartbeat notifications for user1
    let mut tx = app_state.db_pool.begin().await.unwrap();
    HeartbeatRepository::delete_by_pubkey_tx(&mut tx, &user1.pubkey().to_string())
        .await
        .unwrap();
    tx.commit().await.unwrap();

    // Verify user1's notifications are deleted
    let count1_after: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM heartbeat_notifications WHERE pubkey = $1")
            .bind(user1.pubkey().to_string())
            .fetch_one(&app_state.db_pool)
            .await
            .unwrap();
    assert_eq!(count1_after, 0);

    // Verify user2's notifications are still there
    let count2_after: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM heartbeat_notifications WHERE pubkey = $1")
            .bind(user2.pubkey().to_string())
            .fetch_one(&app_state.db_pool)
            .await
            .unwrap();
    assert_eq!(count2_after, 1);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_check_and_deregister_inactive_users_removes_mailbox_authorization() {
    let (_, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let pubkey = user.pubkey().to_string();

    let push_token_repo = PushTokenRepository::new(&app_state.db_pool);
    push_token_repo
        .upsert(&pubkey, "test_push_token")
        .await
        .unwrap();

    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    mailbox_repo
        .upsert(&pubkey, "deadbeef", "cafebabe", 1_900_000_000_i64)
        .await
        .unwrap();

    for i in 0..10 {
        HeartbeatRepository::create_with_status_and_sent_at(
            &app_state.db_pool,
            &pubkey,
            &format!("missed-{}", i),
            HeartbeatStatus::Pending,
            Utc::now() - Duration::minutes((20 - i) as i64),
        )
        .await
        .unwrap();
    }

    crate::cron::check_and_deregister_inactive_users(app_state.clone())
        .await
        .unwrap();

    let push_token = push_token_repo.find_by_pubkey(&pubkey).await.unwrap();
    assert!(push_token.is_none(), "Push token should be deleted");

    let mailbox_auth = mailbox_repo.find_by_pubkey(&pubkey).await.unwrap();
    assert!(
        mailbox_auth.is_none(),
        "Mailbox authorization should be deleted"
    );

    let heartbeat_count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM heartbeat_notifications WHERE pubkey = $1")
            .bind(&pubkey)
            .fetch_one(&app_state.db_pool)
            .await
            .unwrap();
    assert_eq!(
        heartbeat_count, 0,
        "Heartbeat notifications should be deleted"
    );

    let user_record = UserRepository::new(&app_state.db_pool)
        .find_by_pubkey(&pubkey)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(user_record.status, UserStatus::Deregistered);
}
