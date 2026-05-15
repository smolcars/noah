use crate::db::{
    mailbox_authorization_repo::MailboxAuthorizationRepository,
    push_token_repo::PushTokenRepository,
};
use crate::routes::public_api_v0::{GetK1, LnurlpDefaultResponse};
use crate::tests::common::{TestUser, create_test_user, setup_public_test_app};
use crate::types::{ApiErrorResponse, AppVersionCheckPayload, AppVersionInfo};
use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use chrono::Utc;
use http_body_util::BodyExt;
use tower::ServiceExt;

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_request_default() {
    let (app, app_state, _guard) = setup_public_test_app().await;

    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)")
        .bind("test_pubkey")
        .bind("test@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::GET)
                .uri("/.well-known/lnurlp/test")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LnurlpDefaultResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.tag, "payRequest");
    assert_eq!(res.callback, "https://localhost/.well-known/lnurlp/test");
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_invoice_request_rejects_missing_mailbox_authorization() {
    let (app, app_state, _guard) = setup_public_test_app().await;
    let user = TestUser::new();
    let pubkey = user.pubkey().to_string();
    create_test_user(&app_state, &user, None).await;

    PushTokenRepository::new(&app_state.db_pool)
        .upsert(&pubkey, "ExpoPushToken[test-token]")
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::GET)
                .uri("/.well-known/lnurlp/test?amount=330000")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let err: ApiErrorResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(err.code, "INVALID_ARGUMENT");
    assert_eq!(
        err.message,
        "Lightning payments require mailbox notifications to be enabled."
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_invoice_request_rejects_expired_mailbox_authorization() {
    let (app, app_state, _guard) = setup_public_test_app().await;
    let user = TestUser::new();
    let pubkey = user.pubkey().to_string();
    create_test_user(&app_state, &user, None).await;

    PushTokenRepository::new(&app_state.db_pool)
        .upsert(&pubkey, "ExpoPushToken[test-token]")
        .await
        .unwrap();
    MailboxAuthorizationRepository::new(&app_state.db_pool)
        .upsert(&pubkey, "deadbeef", "cafebabe", Utc::now().timestamp() - 60)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::GET)
                .uri("/.well-known/lnurlp/test?amount=330000")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let err: ApiErrorResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(err.code, "INVALID_ARGUMENT");
    assert_eq!(
        err.message,
        "Lightning payments require mailbox notifications to be enabled."
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_get_k1() {
    let (app, app_state, _guard) = setup_public_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::GET)
                .uri("/getk1")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: GetK1 = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.tag, "login");
    assert!(
        app_state
            .k1_cache
            .contains(&res.k1)
            .await
            .expect("failed to verify k1 in Redis")
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_app_version_check_update_required() {
    let (app, _app_state, _guard) = setup_public_test_app().await;

    let payload = AppVersionCheckPayload {
        client_version: "0.0.0".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/app_version")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: AppVersionInfo = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.minimum_required_version, "0.0.1");
    assert!(res.update_required);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_app_version_check_no_update_required() {
    let (app, _app_state, _guard) = setup_public_test_app().await;

    let payload = AppVersionCheckPayload {
        client_version: "0.0.1".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/app_version")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: AppVersionInfo = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.minimum_required_version, "0.0.1");
    assert!(!res.update_required);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_app_version_check_newer_version() {
    let (app, _app_state, _guard) = setup_public_test_app().await;

    let payload = AppVersionCheckPayload {
        client_version: "1.0.0".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/app_version")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: AppVersionInfo = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.minimum_required_version, "0.0.1");
    assert!(!res.update_required);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_app_version_check_invalid_version() {
    let (app, _app_state, _guard) = setup_public_test_app().await;

    let payload = AppVersionCheckPayload {
        client_version: "invalid".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/app_version")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}
