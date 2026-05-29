use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::json;
use tower::ServiceExt;

use crate::db::user_repo::UserRepository;
use crate::tests::common::{TestUser, create_test_user, setup_test_app};
use crate::types::{
    LightningAddressSuggestionsPayload, LightningAddressSuggestionsResponse, UserStatus,
};

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_requires_auth() {
    let (app, _app_state, _guard) = setup_test_app().await;

    let payload = LightningAddressSuggestionsPayload {
        query: "ali".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_invalid_auth() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let payload = LightningAddressSuggestionsPayload {
        query: "ali".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(http::header::AUTHORIZATION, "Bearer invalid-token")
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_prefix() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    sqlx::query(
        "INSERT INTO users (pubkey, lightning_address, ark_address) VALUES
            ('pk1', 'alice@localhost', NULL),
            ('pk2', 'alicia@localhost', NULL),
            ('pk3', 'bob@localhost', NULL)",
    )
    .execute(&app_state.db_pool)
    .await
    .unwrap();

    let payload = LightningAddressSuggestionsPayload {
        query: "ali".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LightningAddressSuggestionsResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(
        res.suggestions,
        vec![
            "alice@localhost".to_string(),
            "alicia@localhost".to_string()
        ]
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_excludes_inactive_short_prefix_matches() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    sqlx::query(
        "INSERT INTO users (pubkey, lightning_address, ark_address, status) VALUES
            ('active-short', 'al@localhost', NULL, 'active'),
            ('inactive-short', 'alex@localhost', NULL, 'inactive'),
            ('deregistered-short', 'ally@localhost', NULL, 'deregistered')",
    )
    .execute(&app_state.db_pool)
    .await
    .unwrap();

    let payload = LightningAddressSuggestionsPayload {
        query: "al".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LightningAddressSuggestionsResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(res.suggestions, vec!["al@localhost".to_string()]);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_excludes_inactive_fuzzy_matches() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    sqlx::query(
        "INSERT INTO users (pubkey, lightning_address, ark_address) VALUES
            ('active-fuzzy', 'alice@localhost', NULL),
            ('inactive-fuzzy', 'alicia@localhost', NULL),
            ('deregistered-fuzzy', 'alina@localhost', NULL)",
    )
    .execute(&app_state.db_pool)
    .await
    .unwrap();

    let user_repo = UserRepository::new(&app_state.db_pool);
    user_repo
        .set_status("inactive-fuzzy", UserStatus::Inactive)
        .await
        .unwrap();
    user_repo
        .set_status("deregistered-fuzzy", UserStatus::Deregistered)
        .await
        .unwrap();

    let payload = LightningAddressSuggestionsPayload {
        query: "ali".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LightningAddressSuggestionsResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(res.suggestions, vec!["alice@localhost".to_string()]);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_short_query_returns_empty() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let payload = LightningAddressSuggestionsPayload {
        query: "a".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LightningAddressSuggestionsResponse = serde_json::from_slice(&body).unwrap();
    assert!(res.suggestions.is_empty());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_domain_divergence_returns_empty() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let payload = LightningAddressSuggestionsPayload {
        query: "alice@gmail".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LightningAddressSuggestionsResponse = serde_json::from_slice(&body).unwrap();
    assert!(res.suggestions.is_empty());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_limit() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    for i in 0..12 {
        let pubkey = format!("pk{}", i);
        let address = format!("alice{}@localhost", i);
        sqlx::query(
            "INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)",
        )
        .bind(pubkey)
        .bind(address)
        .execute(&app_state.db_pool)
        .await
        .unwrap();
    }

    let payload = LightningAddressSuggestionsPayload {
        query: "alice".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LightningAddressSuggestionsResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(res.suggestions.len(), 8);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_query_too_long() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let payload = json!({
        "query": "a".repeat(200)
    });

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(payload.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_ln_address_suggestions_blocked_prefix_returns_empty() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let payload = LightningAddressSuggestionsPayload {
        query: "bc1qexample".to_string(),
    };

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/ln_address_suggestions")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(serde_json::to_string(&payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LightningAddressSuggestionsResponse = serde_json::from_slice(&body).unwrap();
    assert!(res.suggestions.is_empty());
}
