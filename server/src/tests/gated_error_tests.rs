use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::json;
use tower::ServiceExt;

use crate::tests::common::{TestUser, create_test_user, setup_test_app};

#[tracing_test::traced_test]
#[tokio::test]
async fn test_backup_endpoints_invalid_auth() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let endpoints = vec![
        "/v0/backup/upload_url",
        "/v0/backup/complete_upload",
        "/v0/backup/list",
        "/v0/backup/download_url",
        "/v0/backup/delete",
        "/v0/backup/settings",
    ];

    for endpoint in endpoints {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method(http::Method::POST)
                    .uri(endpoint)
                    .header(http::header::CONTENT_TYPE, "application/json")
                    .header(http::header::AUTHORIZATION, "Bearer invalid-token")
                    .body(Body::from(
                        serde_json::to_vec(&json!({
                            "backup_version": 1,
                            "backup_size": 1024,
                            "backup_enabled": true
                        }))
                        .unwrap(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(
            response.status(),
            StatusCode::UNAUTHORIZED,
            "Endpoint {} should return UNAUTHORIZED for invalid auth",
            endpoint
        );
    }
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_error_payload_shape_invalid_auth() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/v0/backup/list")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(http::header::AUTHORIZATION, "Bearer invalid-token")
                .body(Body::from("{}"))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    let body_bytes = response.into_body().collect().await.unwrap().to_bytes();
    let json_body: serde_json::Value =
        serde_json::from_slice(&body_bytes).expect("failed to parse error response");

    assert_eq!(
        json_body.get("status").and_then(|v| v.as_str()),
        Some("ERROR")
    );
    let code = json_body
        .get("code")
        .and_then(|v| v.as_str())
        .expect("missing error code");
    let message = json_body
        .get("message")
        .and_then(|v| v.as_str())
        .expect("missing error message");
    let reason = json_body
        .get("reason")
        .and_then(|v| v.as_str())
        .expect("missing error reason");

    assert_eq!(code, "INVALID_TOKEN");
    assert!(!message.is_empty());
    assert!(!reason.is_empty());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_backup_endpoints_missing_auth() {
    let (app, _, _guard) = setup_test_app().await;

    let endpoints = vec![
        "/v0/backup/upload_url",
        "/v0/backup/complete_upload",
        "/v0/backup/list",
        "/v0/backup/download_url",
        "/v0/backup/delete",
        "/v0/backup/settings",
    ];

    for endpoint in endpoints {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method(http::Method::POST)
                    .uri(endpoint)
                    .header(http::header::CONTENT_TYPE, "application/json")
                    .body(Body::from(
                        serde_json::to_vec(&json!({
                            "backup_version": 1,
                            "backup_size": 1024,
                            "backup_enabled": true
                        }))
                        .unwrap(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(
            response.status(),
            StatusCode::UNAUTHORIZED,
            "Endpoint {} should return UNAUTHORIZED for missing auth",
            endpoint
        );
    }
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_backup_endpoints_malformed_json() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let endpoints = vec![
        "/v0/backup/upload_url",
        "/v0/backup/complete_upload",
        "/v0/backup/download_url",
        "/v0/backup/delete",
        "/v0/backup/settings",
    ];

    for endpoint in endpoints {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method(http::Method::POST)
                    .uri(endpoint)
                    .header(http::header::CONTENT_TYPE, "application/json")
                    .header(
                        http::header::AUTHORIZATION,
                        format!("Bearer {}", access_token),
                    )
                    .body(Body::from("invalid json"))
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(
            response.status(),
            StatusCode::BAD_REQUEST,
            "Endpoint {} should return BAD_REQUEST for malformed JSON",
            endpoint
        );
    }
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_complete_upload_missing_fields() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    // Test missing s3_key
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/v0/backup/complete_upload")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "backup_version": 1,
                        "backup_size": 1024
                        // Missing s3_key
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    // The server returns UNPROCESSABLE_ENTITY (422) when JSON deserialization fails
    // due to missing required fields
    assert!(
        response.status() == StatusCode::BAD_REQUEST
            || response.status() == StatusCode::UNPROCESSABLE_ENTITY,
        "Expected BAD_REQUEST (400) or UNPROCESSABLE_ENTITY (422), got {}",
        response.status()
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_get_upload_url_missing_fields() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    // Test missing backup_version
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/v0/backup/upload_url")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "backup_size": 1024
                        // Missing backup_version
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert!(
        response.status() == StatusCode::BAD_REQUEST
            || response.status() == StatusCode::UNPROCESSABLE_ENTITY,
        "Expected BAD_REQUEST (400) or UNPROCESSABLE_ENTITY (422), got {}",
        response.status()
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_delete_backup_missing_version() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/v0/backup/delete")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        // Missing backup_version
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert!(
        response.status() == StatusCode::BAD_REQUEST
            || response.status() == StatusCode::UNPROCESSABLE_ENTITY,
        "Expected BAD_REQUEST (400) or UNPROCESSABLE_ENTITY (422), got {}",
        response.status()
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_update_backup_settings_missing_enabled() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/v0/backup/settings")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        // Missing backup_enabled
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert!(
        response.status() == StatusCode::BAD_REQUEST
            || response.status() == StatusCode::UNPROCESSABLE_ENTITY,
        "Expected BAD_REQUEST (400) or UNPROCESSABLE_ENTITY (422), got {}",
        response.status()
    );
}
