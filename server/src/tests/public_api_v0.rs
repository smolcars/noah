use crate::db::{
    fiat_rate_repo::FiatRateRepository, mailbox_authorization_repo::MailboxAuthorizationRepository,
    push_token_repo::PushTokenRepository, user_repo::UserRepository,
};
use crate::routes::public_api_v0::{GetK1, LnurlpDefaultResponse, LnurlpInvoiceResponse};
use crate::tests::common::{TestUser, create_test_user, setup_public_test_app, setup_test_app};
use crate::types::{
    ApiErrorResponse, AppVersionCheckPayload, AppVersionInfo, FiatPricesPayload,
    FiatPricesResponse, HistoricalFiatPricePayload, HistoricalFiatPriceResponse, UserStatus,
};
use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use bitcoin::secp256k1::{PublicKey, Secp256k1, SecretKey};
use chrono::Utc;
use http_body_util::BodyExt;
use tower::ServiceExt;

fn test_ark_address(server_key_byte: u8) -> (PublicKey, String) {
    let secp = Secp256k1::new();
    let server_secret_key = SecretKey::from_slice(&[server_key_byte; 32]).unwrap();
    let server_pubkey = PublicKey::from_secret_key(&secp, &server_secret_key);
    let user_secret_key = SecretKey::from_slice(&[0x42; 32]).unwrap();
    let user_pubkey = PublicKey::from_secret_key(&secp, &user_secret_key);
    let blinded_id = ark::mailbox::BlindedMailboxIdentifier::from_pubkey(user_pubkey);
    let address = ark::Address::builder()
        .testnet(true)
        .server_pubkey(server_pubkey)
        .pubkey_policy(user_pubkey)
        .delivery(ark::address::VtxoDelivery::ServerMailbox { blinded_id })
        .into_address()
        .unwrap();

    (server_pubkey, address.to_string())
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_request_default() {
    let (app, app_state, _guard) = setup_public_test_app().await;
    let (_, ark_address) = test_ark_address(0x11);

    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, $3)")
        .bind("test_pubkey")
        .bind("test@localhost")
        .bind(ark_address)
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
    assert_eq!(res.ark, None);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_request_advertises_address_for_matching_ark_server() {
    let (app, app_state, _guard) = setup_public_test_app().await;
    let (server_pubkey, ark_address) = test_ark_address(0x11);
    *app_state.ark_server_pubkey.write().await = Some(server_pubkey.to_string());

    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, $3)")
        .bind("test_pubkey")
        .bind("test@localhost")
        .bind(&ark_address)
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::GET)
                .uri(format!("/.well-known/lnurlp/test?ark={server_pubkey}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LnurlpDefaultResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.ark.as_deref(), Some(ark_address.as_str()));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_request_omits_address_for_different_ark_server() {
    let (app, app_state, _guard) = setup_public_test_app().await;
    let (server_pubkey, ark_address) = test_ark_address(0x11);
    let (different_server_pubkey, _) = test_ark_address(0x12);
    *app_state.ark_server_pubkey.write().await = Some(server_pubkey.to_string());

    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, $3)")
        .bind("test_pubkey")
        .bind("test@localhost")
        .bind(ark_address)
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::GET)
                .uri(format!(
                    "/.well-known/lnurlp/test?ark={different_server_pubkey}"
                ))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LnurlpDefaultResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.ark, None);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_request_supports_legacy_noah_wallet_parameter() {
    let (app, app_state, _guard) = setup_public_test_app().await;
    let (_, ark_address) = test_ark_address(0x11);

    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, $3)")
        .bind("test_pubkey")
        .bind("test@localhost")
        .bind(&ark_address)
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::GET)
                .uri("/.well-known/lnurlp/test?wallet=noahwallet")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LnurlpDefaultResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.ark.as_deref(), Some(ark_address.as_str()));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_invoice_request_returns_matching_ark_address_without_mailbox() {
    let (app, app_state, _guard) = setup_public_test_app().await;
    let (server_pubkey, ark_address) = test_ark_address(0x11);
    *app_state.ark_server_pubkey.write().await = Some(server_pubkey.to_string());

    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, $3)")
        .bind("test_pubkey")
        .bind("test@localhost")
        .bind(&ark_address)
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::GET)
                .uri(format!(
                    "/.well-known/lnurlp/test?amount=330000&ark={server_pubkey}"
                ))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LnurlpInvoiceResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.pr, "");
    assert_eq!(res.ark.as_deref(), Some(ark_address.as_str()));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_request_omits_ark_address_when_user_has_none() {
    let (app, app_state, _guard) = setup_public_test_app().await;
    let (server_pubkey, _) = test_ark_address(0x11);

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
                .uri(format!("/.well-known/lnurlp/test?ark={server_pubkey}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: LnurlpDefaultResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.ark, None);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_lnurlp_request_rejects_deregistered_user() {
    let (app, app_state, _guard) = setup_public_test_app().await;
    let user = TestUser::new();
    let pubkey = user.pubkey().to_string();
    create_test_user(&app_state, &user, None).await;

    UserRepository::new(&app_state.db_pool)
        .set_status(&pubkey, UserStatus::Deregistered)
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

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let err: ApiErrorResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(err.code, "INVALID_ARGUMENT");
    assert_eq!(
        err.message,
        "Lightning payments are not available for this user right now."
    );
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

#[tracing_test::traced_test]
#[tokio::test]
async fn test_fiat_prices_requires_authentication() {
    let (app, _app_state, _guard) = setup_test_app().await;

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/prices")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    serde_json::to_vec(&FiatPricesPayload {}).unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_fiat_prices_requires_registered_user() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/prices")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&FiatPricesPayload {}).unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: ApiErrorResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.code, "USER_NOT_FOUND");
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_fiat_prices_returns_cached_typed_response() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;

    let observed_at = Utc::now();
    let repo = FiatRateRepository::new(&app_state.db_pool);
    repo.upsert_rate(
        "USD",
        observed_at.date_naive(),
        12345.67,
        observed_at,
        "test",
    )
    .await
    .unwrap();
    repo.upsert_rate(
        "BRL",
        observed_at.date_naive(),
        67890.12,
        observed_at,
        "test",
    )
    .await
    .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/prices")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&FiatPricesPayload {}).unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: FiatPricesResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.rates["USD"], 12345.67);
    assert_eq!(res.rates["BRL"], 67890.12);
    assert!(res.time > 0);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_historical_fiat_price_returns_cached_typed_response() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;

    let observed_at = Utc::now();
    let timestamp = observed_at.timestamp();
    let repo = FiatRateRepository::new(&app_state.db_pool);
    repo.upsert_rate(
        "KRW",
        observed_at.date_naive(),
        98765432.1,
        observed_at,
        "test",
    )
    .await
    .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/historical-price")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&HistoricalFiatPricePayload {
                        currency: "KRW".to_string(),
                        timestamp,
                    })
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: HistoricalFiatPriceResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.currency, "KRW");
    assert_eq!(res.rate, 98765432.1);
    assert_eq!(res.time, timestamp);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_historical_fiat_price_rejects_unsupported_currency() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/historical-price")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&HistoricalFiatPricePayload {
                        currency: "XYZ".to_string(),
                        timestamp: 1767139200,
                    })
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}
