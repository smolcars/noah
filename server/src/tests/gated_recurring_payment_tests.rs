use axum::Router;
use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use chrono::{Duration, TimeZone, Utc};
use serde_json::{Value, json};
use tower::ServiceExt;

use crate::AppState;
use crate::cron::{RECURRING_PAYMENT_MAX_OVERDUE_HOURS, RECURRING_PAYMENT_RENOTIFY_MINUTES};
use crate::db::recurring_payment_repo::RecurringPaymentRepository;
use crate::routes::gated_api_v0::MAX_RECURRING_PAYMENT_SCHEDULES;
use crate::tests::common::{TestUser, create_test_user, setup_test_app};

async fn sync(app: &Router, app_state: &AppState, user: &TestUser, body: Value) -> StatusCode {
    app.clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/recurring_payments/sync")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", user.access_token(app_state)),
                )
                .body(Body::from(serde_json::to_vec(&body).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap()
        .status()
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_sync_recurring_payments_replaces_schedule_set() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let pubkey = user.pubkey().to_string();
    let repo = RecurringPaymentRepository::new(&app_state.db_pool);

    let first = Utc::now().timestamp() + 3600;
    let second = first + 86_400;
    let status = sync(
        &app,
        &app_state,
        &user,
        json!({ "schedules": [
            { "schedule_id": "rent", "next_run_at": first },
            { "schedule_id": "donation-1", "next_run_at": second },
        ]}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);

    let stored = repo.list_for_pubkey(&pubkey).await.unwrap();
    assert_eq!(stored.len(), 2);
    assert_eq!(stored[0].schedule_id, "rent");
    assert_eq!(stored[0].next_run_at.timestamp(), first);
    assert_eq!(stored[1].schedule_id, "donation-1");

    // Pausing/cancelling on the device drops the schedule from the next sync.
    let status = sync(
        &app,
        &app_state,
        &user,
        json!({ "schedules": [{ "schedule_id": "donation-1", "next_run_at": second }] }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let stored = repo.list_for_pubkey(&pubkey).await.unwrap();
    assert_eq!(stored.len(), 1);
    assert_eq!(stored[0].schedule_id, "donation-1");

    let status = sync(&app, &app_state, &user, json!({ "schedules": [] })).await;
    assert_eq!(status, StatusCode::OK);
    assert!(repo.list_for_pubkey(&pubkey).await.unwrap().is_empty());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_sync_recurring_payments_rejects_invalid_payloads() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let due = Utc::now().timestamp() + 3600;

    let invalid_bodies = [
        json!({ "schedules": [{ "schedule_id": "bad id!", "next_run_at": due }] }),
        json!({ "schedules": [{ "schedule_id": "", "next_run_at": due }] }),
        json!({ "schedules": [
            { "schedule_id": "dup", "next_run_at": due },
            { "schedule_id": "dup", "next_run_at": due },
        ]}),
        json!({ "schedules": [{ "schedule_id": "neg", "next_run_at": -1 }] }),
        json!({ "schedules": [{ "schedule_id": "far", "next_run_at": due + 20 * 366 * 86_400 }] }),
    ];
    for body in invalid_bodies {
        assert_eq!(
            sync(&app, &app_state, &user, body.clone()).await,
            StatusCode::BAD_REQUEST,
            "expected rejection for {body}"
        );
    }

    let too_many: Vec<Value> = (0..=MAX_RECURRING_PAYMENT_SCHEDULES)
        .map(|i| json!({ "schedule_id": format!("s{i}"), "next_run_at": due }))
        .collect();
    assert_eq!(
        sync(&app, &app_state, &user, json!({ "schedules": too_many })).await,
        StatusCode::BAD_REQUEST
    );

    let repo = RecurringPaymentRepository::new(&app_state.db_pool);
    assert!(
        repo.list_for_pubkey(&user.pubkey().to_string())
            .await
            .unwrap()
            .is_empty()
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_sync_recurring_payments_requires_auth() {
    let (app, _app_state, _guard) = setup_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/recurring_payments/sync")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    serde_json::to_vec(&json!({ "schedules": [] })).unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_due_recurring_payments_are_throttled_and_bounded() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let pubkey = user.pubkey().to_string();
    let repo = RecurringPaymentRepository::new(&app_state.db_pool);

    let now = Utc.with_ymd_and_hms(2026, 3, 1, 12, 0, 0).unwrap();
    let mut tx = app_state.db_pool.begin().await.unwrap();
    RecurringPaymentRepository::replace_for_pubkey(
        &mut tx,
        &pubkey,
        &[
            ("due".to_string(), now - Duration::minutes(5)),
            ("future".to_string(), now + Duration::days(1)),
            (
                "stale".to_string(),
                now - Duration::hours(RECURRING_PAYMENT_MAX_OVERDUE_HOURS + 1),
            ),
        ],
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    let due = repo
        .find_due_users(
            now,
            RECURRING_PAYMENT_RENOTIFY_MINUTES,
            RECURRING_PAYMENT_MAX_OVERDUE_HOURS,
        )
        .await
        .unwrap();
    assert_eq!(due.len(), 1);
    assert_eq!(due[0].pubkey, pubkey);
    assert_eq!(due[0].due_count, 1);

    repo.mark_due_notified(&pubkey, now).await.unwrap();

    // Not nudged again within the re-notify window.
    let soon = now + Duration::minutes(RECURRING_PAYMENT_RENOTIFY_MINUTES - 1);
    let due = repo
        .find_due_users(
            soon,
            RECURRING_PAYMENT_RENOTIFY_MINUTES,
            RECURRING_PAYMENT_MAX_OVERDUE_HOURS,
        )
        .await
        .unwrap();
    assert!(due.is_empty());

    // Nudged again once the window passed and the payment is still due.
    let later = now + Duration::minutes(RECURRING_PAYMENT_RENOTIFY_MINUTES);
    let due = repo
        .find_due_users(
            later,
            RECURRING_PAYMENT_RENOTIFY_MINUTES,
            RECURRING_PAYMENT_MAX_OVERDUE_HOURS,
        )
        .await
        .unwrap();
    assert_eq!(due.len(), 1);

    // After the device executes and syncs the next due time, notification state resets.
    let mut tx = app_state.db_pool.begin().await.unwrap();
    RecurringPaymentRepository::replace_for_pubkey(
        &mut tx,
        &pubkey,
        &[("due".to_string(), now + Duration::days(30))],
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();
    let stored = repo.list_for_pubkey(&pubkey).await.unwrap();
    assert_eq!(stored.len(), 1);
    assert!(stored[0].last_notified_at.is_none());
    let due = repo
        .find_due_users(
            later,
            RECURRING_PAYMENT_RENOTIFY_MINUTES,
            RECURRING_PAYMENT_MAX_OVERDUE_HOURS,
        )
        .await
        .unwrap();
    assert!(due.is_empty());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_deregister_removes_recurring_payment_schedules() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let pubkey = user.pubkey().to_string();

    let due = Utc::now().timestamp() + 3600;
    assert_eq!(
        sync(
            &app,
            &app_state,
            &user,
            json!({ "schedules": [{ "schedule_id": "rent", "next_run_at": due }] }),
        )
        .await,
        StatusCode::OK
    );

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/deregister")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", user.access_token(&app_state)),
                )
                .body(Body::from("{}"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);

    let repo = RecurringPaymentRepository::new(&app_state.db_pool);
    assert!(repo.list_for_pubkey(&pubkey).await.unwrap().is_empty());
}
