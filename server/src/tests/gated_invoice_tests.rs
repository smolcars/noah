use std::time::{Duration, SystemTime};

use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use bitcoin::{
    hashes::{Hash, sha256},
    secp256k1::{Secp256k1, SecretKey},
};
use http_body_util::BodyExt;
use lightning_invoice::{Currency, InvoiceBuilder, PaymentSecret};
use serde_json::json;
use tower::ServiceExt;
use uuid::Uuid;

use crate::tests::common::{TestUser, setup_test_app};
use crate::types::{
    DefaultSuccessPayload, LnurlPayReceiveMetadataListResponse, LnurlPayReceivePayerData,
};

fn test_invoice_with_timestamp_and_expiry(
    amount_msat: u64,
    payment_hash_byte: u8,
    timestamp: SystemTime,
    expiry: Duration,
) -> String {
    let secp = Secp256k1::new();
    let private_key = SecretKey::from_slice(&[0x31; 32]).unwrap();
    InvoiceBuilder::new(Currency::Regtest)
        .description("Noah test invoice".to_string())
        .payment_hash(sha256::Hash::from_byte_array([payment_hash_byte; 32]))
        .payment_secret(PaymentSecret([0x22; 32]))
        .amount_milli_satoshis(amount_msat)
        .timestamp(timestamp)
        .expiry_time(expiry)
        .min_final_cltv_expiry_delta(18)
        .build_signed(|hash| secp.sign_ecdsa_recoverable(hash, &private_key))
        .unwrap()
        .to_string()
}

fn test_invoice_with_expiry(amount_msat: u64, payment_hash_byte: u8, expiry: Duration) -> String {
    test_invoice_with_timestamp_and_expiry(
        amount_msat,
        payment_hash_byte,
        SystemTime::now(),
        expiry,
    )
}

pub(crate) fn test_invoice(amount_msat: u64, payment_hash_byte: u8) -> String {
    test_invoice_with_expiry(amount_msat, payment_hash_byte, Duration::from_secs(3600))
}

async fn insert_user(app_state: &crate::AppState, user: &TestUser) {
    sqlx::query("INSERT INTO users (pubkey, lightning_address) VALUES ($1, $2)")
        .bind(user.pubkey().to_string())
        .bind("test@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();
}

fn submit_request(access_token: &str, transaction_id: &str, invoice: &str) -> Request<Body> {
    Request::builder()
        .method(http::Method::POST)
        .uri("/lnurlp/submit_invoice")
        .header(http::header::CONTENT_TYPE, "application/json")
        .header(
            http::header::AUTHORIZATION,
            format!("Bearer {access_token}"),
        )
        .body(Body::from(
            serde_json::to_vec(&json!({
                "transaction_id": transaction_id,
                "invoice": invoice,
            }))
            .unwrap(),
        ))
        .unwrap()
}

#[tracing_test::traced_test]
#[tokio::test]
async fn submit_invoice_validates_and_publishes_staged_invoice() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    insert_user(&app_state, &user).await;
    let transaction_id = Uuid::new_v4().to_string();
    let invoice = test_invoice(330_000, 1);
    app_state
        .lnurl_pay_receive_metadata_store
        .store_request(
            &transaction_id,
            &user.pubkey().to_string(),
            330_000,
            None,
            None,
        )
        .await
        .unwrap();

    let wrong_amount = app
        .clone()
        .oneshot(submit_request(
            &user.access_token(&app_state),
            &transaction_id,
            &test_invoice(331_000, 9),
        ))
        .await
        .unwrap();
    assert_eq!(wrong_amount.status(), StatusCode::BAD_REQUEST);

    let response = app
        .oneshot(submit_request(
            &user.access_token(&app_state),
            &transaction_id,
            &invoice,
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    assert!(
        serde_json::from_slice::<DefaultSuccessPayload>(&body)
            .unwrap()
            .success
    );
    assert_eq!(
        app_state.invoice_store.get(&transaction_id).await.unwrap(),
        Some(invoice)
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn submit_invoice_accepts_configured_48_hour_expiry_with_clock_skew() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    insert_user(&app_state, &user).await;
    let transaction_id = Uuid::new_v4().to_string();
    let invoice = test_invoice_with_timestamp_and_expiry(
        330_000,
        6,
        SystemTime::now() + Duration::from_secs(30),
        Duration::from_secs(48 * 60 * 60),
    );
    app_state
        .lnurl_pay_receive_metadata_store
        .store_request(
            &transaction_id,
            &user.pubkey().to_string(),
            330_000,
            None,
            None,
        )
        .await
        .unwrap();

    let response = app
        .oneshot(submit_request(
            &user.access_token(&app_state),
            &transaction_id,
            &invoice,
        ))
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        app_state.invoice_store.get(&transaction_id).await.unwrap(),
        Some(invoice)
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn submit_invoice_is_idempotent_but_rejects_conflicts() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    insert_user(&app_state, &user).await;
    let transaction_id = Uuid::new_v4().to_string();
    let invoice = test_invoice(330_000, 2);
    app_state
        .lnurl_pay_receive_metadata_store
        .store_request(
            &transaction_id,
            &user.pubkey().to_string(),
            330_000,
            None,
            None,
        )
        .await
        .unwrap();
    let token = user.access_token(&app_state);

    let first = app
        .clone()
        .oneshot(submit_request(&token, &transaction_id, &invoice))
        .await
        .unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    let retry = app
        .clone()
        .oneshot(submit_request(&token, &transaction_id, &invoice))
        .await
        .unwrap();
    assert_eq!(retry.status(), StatusCode::OK);
    let conflict = app
        .oneshot(submit_request(
            &token,
            &transaction_id,
            &test_invoice(330_000, 3),
        ))
        .await
        .unwrap();
    assert_eq!(conflict.status(), StatusCode::BAD_REQUEST);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn submit_invoice_requires_auth_and_existing_user() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let transaction_id = Uuid::new_v4().to_string();
    let invoice = test_invoice(330_000, 4);

    let unauthenticated = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/lnurlp/submit_invoice")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "transaction_id": transaction_id,
                        "invoice": invoice,
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(unauthenticated.status(), StatusCode::UNAUTHORIZED);

    let missing_user = app
        .oneshot(submit_request(
            &user.access_token(&app_state),
            &transaction_id,
            &invoice,
        ))
        .await
        .unwrap();
    assert_eq!(missing_user.status(), StatusCode::UNAUTHORIZED);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn settled_metadata_can_be_listed_and_acknowledged() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    insert_user(&app_state, &user).await;
    let recipient = user.pubkey().to_string();
    let transaction_id = Uuid::new_v4().to_string();
    let invoice = test_invoice(330_000, 5);
    let parsed: lightning_invoice::Bolt11Invoice = invoice.parse().unwrap();
    let payment_hash = parsed.payment_hash().to_string();
    app_state
        .lnurl_pay_receive_metadata_store
        .store_request(
            &transaction_id,
            &recipient,
            330_000,
            Some(LnurlPayReceivePayerData {
                name: Some("Alice".to_string()),
                identifier: Some("alice@example.com".to_string()),
            }),
            Some("Hello".to_string()),
        )
        .await
        .unwrap();
    app_state
        .lnurl_pay_receive_metadata_store
        .bind_invoice(
            &transaction_id,
            &recipient,
            &payment_hash,
            330_000,
            &invoice,
            3600,
        )
        .await
        .unwrap();
    assert!(
        app_state
            .lnurl_pay_receive_metadata_store
            .mark_ready(&recipient, &payment_hash, 330_000)
            .await
            .unwrap()
    );

    let token = user.access_token(&app_state);
    let post_settlement_retry = app
        .clone()
        .oneshot(submit_request(&token, &transaction_id, &invoice))
        .await
        .unwrap();
    assert_eq!(post_settlement_retry.status(), StatusCode::OK);

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/lnurlp/receive_metadata/list")
                .header(http::header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let listed: LnurlPayReceiveMetadataListResponse = serde_json::from_slice(&body).unwrap();
    assert_eq!(listed.items.len(), 1);
    assert_eq!(listed.items[0].payment_hash, payment_hash);
    assert_eq!(listed.items[0].amount_sat, 330);
    assert_eq!(listed.items[0].comment.as_deref(), Some("Hello"));

    let acknowledged = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/lnurlp/receive_metadata/ack")
                .header(http::header::AUTHORIZATION, format!("Bearer {token}"))
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    serde_json::to_vec(&json!({ "ids": [listed.items[0].id] })).unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(acknowledged.status(), StatusCode::OK);

    let empty = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/lnurlp/receive_metadata/list")
                .header(http::header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let body = empty.into_body().collect().await.unwrap().to_bytes();
    assert!(
        serde_json::from_slice::<LnurlPayReceiveMetadataListResponse>(&body)
            .unwrap()
            .items
            .is_empty()
    );
}
