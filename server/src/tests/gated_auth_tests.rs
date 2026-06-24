use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use chrono::Utc;
use http_body_util::BodyExt;
use serde_json::json;
use tower::ServiceExt;

use crate::tests::common::{TestUser, create_test_user, setup_test_app};
use crate::types::{AuthLoginResponse, RegisterResponse};
use crate::utils::make_k1;

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
                        "encoded": "cafebabe"
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

    assert_eq!(record.mailbox_id, "deadbeef");
    assert_eq!(record.authorization_hex, "cafebabe");
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
                        "expiry": Utc::now().timestamp() + 60,
                        "encoded": "deadbeef"
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
                        "expiry": Utc::now().timestamp() - 60,
                        "encoded": "deadbeef"
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
                        "expiry": Utc::now().timestamp() + max_ttl_secs + 1,
                        "encoded": "deadbeef"
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
