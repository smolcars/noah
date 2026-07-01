use ark::{ProtocolEncoding, mailbox::MailboxAuthorization};
use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use bitcoin::secp256k1::{Keypair, Secp256k1, SecretKey};
use chrono::{Duration, Local, Utc};
use http_body_util::BodyExt;
use serde_json::json;
use tower::ServiceExt;

use crate::tests::common::{TestUser, create_test_user, setup_test_app};
use crate::types::{AuthLoginResponse, RegisterResponse};
use crate::utils::make_k1;

fn test_mailbox_authorization(expiry: chrono::DateTime<Local>) -> (String, i64, String) {
    let secp = Secp256k1::new();
    let secret_key = SecretKey::from_slice(&[0x42; 32]).unwrap();
    let mailbox_key = Keypair::from_secret_key(&secp, &secret_key);
    let authorization = MailboxAuthorization::new(&mailbox_key, expiry);

    (
        authorization.mailbox().serialize_hex(),
        authorization.expiry().timestamp(),
        authorization.serialize_hex(),
    )
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_auth_login_success() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let k1 = make_k1(&app_state.k1_cache)
        .await
        .expect("failed to create k1");
    let auth_payload = user.auth_payload(&k1);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/auth/login")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_vec(&auth_payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: AuthLoginResponse = serde_json::from_slice(&body).unwrap();

    assert!(!res.access_token.is_empty());
    assert_eq!(res.token_type, "Bearer");
    assert_eq!(res.expires_in_seconds, 24 * 60 * 60);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_auth_login_reused_k1_is_rejected() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let k1 = make_k1(&app_state.k1_cache)
        .await
        .expect("failed to create k1");
    let auth_payload = user.auth_payload(&k1);

    let first_response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/auth/login")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_vec(&auth_payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(first_response.status(), StatusCode::OK);

    let second_response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/auth/login")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_vec(&auth_payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(second_response.status(), StatusCode::BAD_REQUEST);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_register_new_user() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

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
                        "ln_address": "test@localhost"
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: RegisterResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.status, "OK");
    assert!(res.event.is_some());
    assert_eq!(res.lightning_address, Some("test@localhost".to_string()));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_register_existing_user() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    sqlx::query("INSERT INTO users (pubkey, lightning_address) VALUES ($1, $2)")
        .bind(user.pubkey().to_string())
        .bind("existing@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();

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
                        "ln_address": "test@localhost"
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: RegisterResponse = serde_json::from_slice(&body).unwrap();

    assert_eq!(res.status, "OK");
    assert!(res.event.is_none());
    assert_eq!(
        res.lightning_address,
        Some("existing@localhost".to_string())
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_auth_login_invalid_signature() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let k1 = make_k1(&app_state.k1_cache)
        .await
        .expect("failed to create k1");
    let mut auth_payload = user.auth_payload(&k1);
    auth_payload.sig = "invalid_sig".to_string();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/auth/login")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_vec(&auth_payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_auth_login_invalid_k1() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let k1 = make_k1(&app_state.k1_cache)
        .await
        .expect("failed to create k1");
    let mut auth_payload = user.auth_payload(&k1);
    auth_payload.k1 = "invalid_k1".to_string();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/auth/login")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_vec(&auth_payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_auth_login_expired_k1() {
    let (app, app_state, _guard) = setup_test_app().await;

    let k1_hex = "5a9b8f7c6d5e4d3c2b1a0f9e8d7c6b5a4d3c2b1a0f9e8d7c6b5a4d3c2b1a0f9e";
    let old_timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs()
        - 700;
    let k1 = format!("{}_{}", k1_hex, old_timestamp);

    app_state
        .k1_cache
        .insert_with_timestamp(&k1, old_timestamp)
        .await
        .expect("failed to insert expired k1");

    let user = TestUser::new();
    let auth_payload = user.auth_payload(&k1);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/auth/login")
                .header(http::header::CONTENT_TYPE, "application/json")
                .body(Body::from(serde_json::to_vec(&auth_payload).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_register_push_token() {
    let (app, app_state, _guard) = setup_test_app().await;

    let user = TestUser::new();
    let access_token = user.access_token(&app_state);

    sqlx::query("INSERT INTO users (pubkey, lightning_address) VALUES ($1, $2)")
        .bind(user.pubkey().to_string())
        .bind("existing@localhost")
        .execute(&app_state.db_pool)
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
                        "push_token": "test_push_token"
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    use crate::db::push_token_repo::PushTokenRepository;
    let push_token_repo = PushTokenRepository::new(&app_state.db_pool);
    let token = push_token_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(token, "test_push_token");
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_authorize_mailbox() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;
    let (mailbox_id, expiry, encoded) =
        test_mailbox_authorization(Local::now() + Duration::seconds(60));

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/mailbox/authorize")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "mailbox_id": &mailbox_id,
                        "expiry": expiry,
                        "encoded": &encoded
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    let record = mailbox_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();

    assert_eq!(record.mailbox_id, mailbox_id);
    assert_eq!(record.authorization_hex, encoded);
    assert!(record.authorization_expires_at > Utc::now().timestamp());
    assert_eq!(record.auth_version, 1);
    assert_eq!(record.last_checkpoint, 0);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_authorize_mailbox_rejects_invalid_hex() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/mailbox/authorize")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "mailbox_id": "deadbeef",
                        "expiry": Utc::now().timestamp() + 60,
                        "encoded": "not-hex"
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    let record = mailbox_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap();
    assert!(record.is_none());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_authorize_mailbox_rejects_invalid_mailbox_id_hex() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;
    let (_mailbox_id, expiry, encoded) =
        test_mailbox_authorization(Local::now() + Duration::seconds(60));

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/mailbox/authorize")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "mailbox_id": "mailbox-123",
                        "expiry": expiry,
                        "encoded": &encoded
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    let record = mailbox_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap();
    assert!(record.is_none());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_authorize_mailbox_rejects_past_expiry() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;
    let (mailbox_id, expiry, encoded) =
        test_mailbox_authorization(Local::now() - Duration::seconds(60));

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/mailbox/authorize")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "mailbox_id": &mailbox_id,
                        "expiry": expiry,
                        "encoded": &encoded
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
async fn test_authorize_mailbox_rejects_expiry_beyond_max_ttl() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;

    let max_ttl_secs = 90 * 24 * 60 * 60;
    let (mailbox_id, expiry, encoded) =
        test_mailbox_authorization(Local::now() + Duration::seconds(max_ttl_secs + 1));

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/mailbox/authorize")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "mailbox_id": &mailbox_id,
                        "expiry": expiry,
                        "encoded": &encoded
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
async fn test_authorize_mailbox_rejects_expiry_mismatch() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;
    let (mailbox_id, expiry, encoded) =
        test_mailbox_authorization(Local::now() + Duration::seconds(60));

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/mailbox/authorize")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "mailbox_id": &mailbox_id,
                        "expiry": expiry + 1,
                        "encoded": &encoded
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
async fn test_authorize_mailbox_rejects_invalid_signature() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;
    let (mailbox_id, expiry, encoded) =
        test_mailbox_authorization(Local::now() + Duration::seconds(60));
    let mut encoded_bytes = hex::decode(&encoded).unwrap();
    let last_index = encoded_bytes.len() - 1;
    encoded_bytes[last_index] ^= 0x01;
    let invalid_encoded = hex::encode(encoded_bytes);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/mailbox/authorize")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "mailbox_id": &mailbox_id,
                        "expiry": expiry,
                        "encoded": &invalid_encoded
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
async fn test_revoke_mailbox_authorization() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    let access_token = user.access_token(&app_state);
    create_test_user(&app_state, &user, None).await;

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    mailbox_repo
        .upsert(
            &user.pubkey().to_string(),
            "deadbeef",
            "deadbeef",
            Utc::now().timestamp() + 60,
        )
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/mailbox/revoke")
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

    let active_record = mailbox_repo
        .find_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap();
    assert!(active_record.is_none());

    let revoked_record = mailbox_repo
        .find_revoked_by_pubkey(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(revoked_record.mailbox_id, "deadbeef");
    assert_eq!(revoked_record.last_checkpoint, 0);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_has_active_authorization_requires_active_status() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    let pubkey = user.pubkey().to_string();
    mailbox_repo
        .upsert(&pubkey, "deadbeef", "cafebabe", Utc::now().timestamp() + 60)
        .await
        .unwrap();

    assert!(
        mailbox_repo
            .has_active_authorization(&pubkey, Utc::now().timestamp())
            .await
            .unwrap()
    );

    sqlx::query("UPDATE mailbox_authorizations SET status = 'expired' WHERE pubkey = $1")
        .bind(&pubkey)
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    assert!(
        !mailbox_repo
            .has_active_authorization(&pubkey, Utc::now().timestamp())
            .await
            .unwrap()
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_mark_expired_mailbox_authorizations_updates_only_active_expired_rows() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user1 = TestUser::new_with_key(&[0xa1; 32]);
    let user2 = TestUser::new_with_key(&[0xa2; 32]);
    let user3 = TestUser::new_with_key(&[0xa3; 32]);
    for (user, address) in [
        (&user1, "expired-active@localhost"),
        (&user2, "future-active@localhost"),
        (&user3, "expired-invalid@localhost"),
    ] {
        sqlx::query(
            "INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)",
        )
        .bind(user.pubkey().to_string())
        .bind(address)
        .execute(&app_state.db_pool)
        .await
        .unwrap();
    }

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    let now = Utc::now().timestamp();
    mailbox_repo
        .upsert(
            &user1.pubkey().to_string(),
            "deadbeef",
            "cafebabe",
            now - 60,
        )
        .await
        .unwrap();
    mailbox_repo
        .upsert(
            &user2.pubkey().to_string(),
            "feedface",
            "cafed00d",
            now + 60,
        )
        .await
        .unwrap();
    mailbox_repo
        .upsert(&user3.pubkey().to_string(), "badc0de", "c001d00d", now - 60)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE mailbox_authorizations
         SET status = 'invalid',
             lease_owner = 'worker-a',
             lease_expires_at = now() + interval '1 minute',
             next_retry_at = now() + interval '1 minute'
         WHERE pubkey = $1",
    )
    .bind(user3.pubkey().to_string())
    .execute(&app_state.db_pool)
    .await
    .unwrap();

    let affected = mailbox_repo.mark_expired_authorizations(now).await.unwrap();

    assert_eq!(affected, 1);
    let rows = sqlx::query_as::<_, (String, String, Option<String>)>(
        "SELECT pubkey, status, lease_owner FROM mailbox_authorizations ORDER BY pubkey",
    )
    .fetch_all(&app_state.db_pool)
    .await
    .unwrap();

    assert!(rows.iter().any(|(pubkey, status, lease_owner)| {
        pubkey == &user1.pubkey().to_string() && status == "expired" && lease_owner.is_none()
    }));
    assert!(rows.iter().any(|(pubkey, status, _)| {
        pubkey == &user2.pubkey().to_string() && status == "active"
    }));
    assert!(rows.iter().any(|(pubkey, status, lease_owner)| {
        pubkey == &user3.pubkey().to_string() && status == "invalid" && lease_owner.is_some()
    }));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_backfill_mailbox_authorizations_classifies_existing_rows() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let users = [
        (
            TestUser::new_with_key(&[0xb1; 32]),
            "valid-backfill@localhost",
        ),
        (
            TestUser::new_with_key(&[0xb2; 32]),
            "normalize-backfill@localhost",
        ),
        (
            TestUser::new_with_key(&[0xb3; 32]),
            "expired-backfill@localhost",
        ),
        (
            TestUser::new_with_key(&[0xb4; 32]),
            "invalid-backfill@localhost",
        ),
    ];
    for (user, address) in &users {
        sqlx::query(
            "INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)",
        )
        .bind(user.pubkey().to_string())
        .bind(address)
        .execute(&app_state.db_pool)
        .await
        .unwrap();
    }

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    let (valid_mailbox_id, valid_expiry, valid_encoded) =
        test_mailbox_authorization(Local::now() + Duration::seconds(60));
    mailbox_repo
        .upsert(
            &users[0].0.pubkey().to_string(),
            &valid_mailbox_id,
            &valid_encoded,
            valid_expiry,
        )
        .await
        .unwrap();

    let (normalize_mailbox_id, normalize_expiry, normalize_encoded) =
        test_mailbox_authorization(Local::now() + Duration::seconds(120));
    mailbox_repo
        .upsert(
            &users[1].0.pubkey().to_string(),
            &normalize_mailbox_id.to_ascii_uppercase(),
            &normalize_encoded.to_ascii_uppercase(),
            normalize_expiry + 30,
        )
        .await
        .unwrap();

    let (expired_mailbox_id, expired_expiry, expired_encoded) =
        test_mailbox_authorization(Local::now() - Duration::seconds(60));
    mailbox_repo
        .upsert(
            &users[2].0.pubkey().to_string(),
            &expired_mailbox_id,
            &expired_encoded,
            expired_expiry + 120,
        )
        .await
        .unwrap();

    mailbox_repo
        .upsert(
            &users[3].0.pubkey().to_string(),
            "deadbeef",
            "not-protocol-hex",
            Utc::now().timestamp() + 60,
        )
        .await
        .unwrap();

    let report = crate::mailbox_auth::backfill_mailbox_authorizations(&app_state.db_pool, false)
        .await
        .unwrap();

    assert_eq!(report.checked, 4);
    assert_eq!(report.valid, 1);
    assert_eq!(report.normalized, 1);
    assert_eq!(report.expired, 1);
    assert_eq!(report.invalid, 1);

    let normalized = mailbox_repo
        .find_by_pubkey(&users[1].0.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(normalized.mailbox_id, normalize_mailbox_id);
    assert_eq!(normalized.authorization_hex, normalize_encoded);
    assert_eq!(normalized.authorization_expires_at, normalize_expiry);

    let statuses = sqlx::query_as::<_, (String, String)>(
        "SELECT pubkey, status FROM mailbox_authorizations ORDER BY pubkey",
    )
    .fetch_all(&app_state.db_pool)
    .await
    .unwrap();
    assert!(statuses.iter().any(|(pubkey, status)| {
        pubkey == &users[2].0.pubkey().to_string() && status == "expired"
    }));
    assert!(statuses.iter().any(|(pubkey, status)| {
        pubkey == &users[3].0.pubkey().to_string() && status == "invalid"
    }));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_claim_runnable_mailboxes_is_exclusive_per_worker() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new_with_key(&[0x91; 32]);
    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)")
        .bind(user.pubkey().to_string())
        .bind("claim-exclusive@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    mailbox_repo
        .upsert(
            &user.pubkey().to_string(),
            "deadbeef",
            "cafebabe",
            Utc::now().timestamp() + 60,
        )
        .await
        .unwrap();

    let now = Utc::now();
    let lease_until = now + chrono::TimeDelta::seconds(30);
    let first_claim = mailbox_repo
        .claim_runnable(now, "worker-a", lease_until, 10)
        .await
        .unwrap();
    let second_claim = mailbox_repo
        .claim_runnable(now, "worker-b", lease_until, 10)
        .await
        .unwrap();

    assert_eq!(first_claim.len(), 1);
    assert_eq!(first_claim[0].pubkey, user.pubkey().to_string());
    assert_eq!(second_claim.len(), 0);

    let last_connected_at = sqlx::query_scalar::<_, Option<chrono::DateTime<Utc>>>(
        "SELECT last_connected_at
         FROM mailbox_authorizations
         WHERE pubkey = $1",
    )
    .bind(user.pubkey().to_string())
    .fetch_one(&app_state.db_pool)
    .await
    .unwrap();
    assert!(last_connected_at.is_some());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_claim_runnable_skips_rows_already_leased_by_same_worker() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user1 = TestUser::new_with_key(&[0x92; 32]);
    let user2 = TestUser::new_with_key(&[0x93; 32]);
    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)")
        .bind(user1.pubkey().to_string())
        .bind("claim-same-worker-1@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, NULL)")
        .bind(user2.pubkey().to_string())
        .bind("claim-same-worker-2@localhost")
        .execute(&app_state.db_pool)
        .await
        .unwrap();

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);

    mailbox_repo
        .upsert(
            &user1.pubkey().to_string(),
            "deadbeef",
            "cafebabe",
            Utc::now().timestamp() + 60,
        )
        .await
        .unwrap();
    mailbox_repo
        .upsert(
            &user2.pubkey().to_string(),
            "feedface",
            "cafed00d",
            Utc::now().timestamp() + 60,
        )
        .await
        .unwrap();

    let now = Utc::now();
    let lease_until = now + chrono::TimeDelta::seconds(30);

    sqlx::query(
        "UPDATE mailbox_authorizations
         SET lease_owner = $2,
             lease_expires_at = $3
         WHERE pubkey = $1",
    )
    .bind(user1.pubkey().to_string())
    .bind("worker-a")
    .bind(lease_until)
    .execute(&app_state.db_pool)
    .await
    .unwrap();

    let claimed = mailbox_repo
        .claim_runnable(now, "worker-a", lease_until, 2)
        .await
        .unwrap();

    assert_eq!(claimed.len(), 1);
    assert_eq!(claimed[0].pubkey, user2.pubkey().to_string());
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_reauthorize_with_new_mailbox_resets_checkpoint() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    let pubkey = user.pubkey().to_string();

    mailbox_repo
        .upsert(&pubkey, "deadbeef", "cafebabe", Utc::now().timestamp() + 60)
        .await
        .unwrap();

    sqlx::query(
        "UPDATE mailbox_authorizations
         SET last_checkpoint = 42,
             lease_owner = NULL,
             lease_expires_at = NULL
         WHERE pubkey = $1",
    )
    .bind(&pubkey)
    .execute(&app_state.db_pool)
    .await
    .unwrap();

    mailbox_repo
        .upsert(&pubkey, "feedface", "cafed00d", Utc::now().timestamp() + 60)
        .await
        .unwrap();

    let record = mailbox_repo.find_by_pubkey(&pubkey).await.unwrap().unwrap();
    assert_eq!(record.mailbox_id, "feedface");
    assert_eq!(record.last_checkpoint, 0);
    assert_eq!(record.auth_version, 2);
}
