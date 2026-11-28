use std::{net::SocketAddr, sync::Arc};

use axum::{Extension, Router, extract::ConnectInfo};
use bitcoin::key::Keypair;
use once_cell::sync::Lazy;
use sqlx::{PgPool, postgres::PgPoolOptions};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

use crate::auth::mint_access_token;
use crate::barkd_client::ForwardingInvoiceProvider;
use crate::cache::{
    email_verification_store::EmailVerificationStore, invoice_store::InvoiceStore,
    k1_store::K1Store, maintenance_store::MaintenanceStore, redis_client::RedisClient,
};
use crate::config::Config;
use crate::email_client::EmailClient;
use crate::types::AuthLoginPayload;
use crate::{AppState, AppStruct};

static TEST_DB_SEMAPHORE: Lazy<Arc<Semaphore>> = Lazy::new(|| Arc::new(Semaphore::new(1)));

pub struct TestDbGuard {
    _permit: OwnedSemaphorePermit,
}

async fn acquire_test_db_guard() -> TestDbGuard {
    let permit = TEST_DB_SEMAPHORE
        .clone()
        .acquire_owned()
        .await
        .expect("failed to acquire test DB semaphore");
    TestDbGuard { _permit: permit }
}

pub struct TestUser {
    keypair: Keypair,
    secp: bitcoin::secp256k1::Secp256k1<bitcoin::secp256k1::All>,
}

impl TestUser {
    pub fn new() -> Self {
        let secp = bitcoin::secp256k1::Secp256k1::new();
        let secret_key = bitcoin::secp256k1::SecretKey::from_slice(&[0xcd; 32]).unwrap();
        let keypair = Keypair::from_secret_key(&secp, &secret_key);
        Self { keypair, secp }
    }

    pub fn new_with_key(key_bytes: &[u8; 32]) -> Self {
        let secp = bitcoin::secp256k1::Secp256k1::new();
        let secret_key = bitcoin::secp256k1::SecretKey::from_slice(key_bytes).unwrap();
        let keypair = Keypair::from_secret_key(&secp, &secret_key);
        Self { keypair, secp }
    }

    pub fn pubkey(&self) -> bitcoin::key::PublicKey {
        self.keypair.public_key().into()
    }

    pub fn get_config() -> Config {
        Config {
            s3_bucket_name: "test-bucket".to_string(),
            host: "localhost".to_string(),
            port: 3000,
            private_port: 3001,
            lnurl_domain: "localhost".to_string(),
            postgres_url: "postgres://postgres:postgres@localhost:5432/noah_test".to_string(),
            postgres_max_connections: 5,
            postgres_min_connections: Some(1),
            expo_access_token: "test-token".to_string(),
            apns_key_p8: None,
            apns_key_id: None,
            apns_team_id: None,
            apns_topic: None,
            fcm_service_account_json: None,
            ark_server_url: "http://localhost:8081".to_string(),
            server_network: "regtest".to_string(),
            sentry_url: Some("http://localhost:8082".to_string()),
            backup_cron: "0 0 * * *".to_string(),
            maintenance_interval_rounds: 10,
            maintenance_notification_advance_secs: 30,
            heartbeat_cron: "0 0 * * *".to_string(),
            deregister_cron: "0 0 * * *".to_string(),
            fiat_rate_refresh_cron: "0 0 * * *".to_string(),
            mailbox_auth_cleanup_cron: "0 0 * * *".to_string(),
            fiat_rate_backfill_days: 60,
            coingecko_demo_api_key: None,
            notification_spacing_minutes: 45,
            minimum_app_version: "0.0.1".to_string(),
            redis_url: std::env::var("TEST_REDIS_URL")
                .unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string()),
            redis_pool_size: 32,
            ses_from_address: "test@noahwallet.com".to_string(),
            email_dev_mode: true,
            auth_jwt_secret: "test-jwt-secret".to_string(),
            auth_jwt_ttl_hours: 24,
            barkd_forwarded_invoices_enabled: false,
            barkd_url: None,
            barkd_auth_token: None,
            barkd_request_timeout_seconds: 10,
            lnurl_pay_receive_metadata_encryption_key: Some([0x42; 32]),
            lnurl_pay_receive_metadata_enabled: true,
            lnurl_pay_receive_metadata_advertised: true,
            zoho_client_id: None,
            zoho_client_secret: None,
            zoho_refresh_token: None,
            zoho_org_id: None,
            zoho_department_id: None,
            zoho_accounts_url: "https://accounts.zoho.com".to_string(),
            zoho_api_domain: "https://desk.zoho.com".to_string(),
            zoho_agent_ticket_base_url:
                "https://desk.zoho.com/agent/noahsupport/noah/tickets/details".to_string(),
            telegram_bot_token: None,
            telegram_support_chat_id: None,
            telegram_support_message_thread_id: None,
        }
    }

    pub fn auth_payload(&self, k1: &str) -> AuthLoginPayload {
        let hash = bitcoin::sign_message::signed_msg_hash(k1);
        let msg = bitcoin::secp256k1::Message::from_digest_slice(&hash[..]).unwrap();
        let sig = self.secp.sign_ecdsa(&msg, &self.keypair.secret_key());
        AuthLoginPayload {
            key: self.pubkey().to_string(),
            sig: sig.to_string(),
            k1: k1.to_string(),
        }
    }

    pub fn access_token(&self, app_state: &AppState) -> String {
        mint_access_token(&app_state.config, &self.pubkey().to_string())
            .expect("failed to mint access token")
            .token
    }
}

pub async fn setup_test_app() -> (Router, AppState, TestDbGuard) {
    setup_test_app_with_barkd(None).await
}

pub async fn setup_test_app_with_barkd(
    barkd_invoice_provider: Option<Arc<dyn ForwardingInvoiceProvider>>,
) -> (Router, AppState, TestDbGuard) {
    setup_test_app_with_push(barkd_invoice_provider, None).await
}

/// The production router over test state, as seen from one peer address.
pub async fn setup_test_app_with_push(
    barkd_invoice_provider: Option<Arc<dyn ForwardingInvoiceProvider>>,
    push_deliveries: Option<
        tokio::sync::mpsc::UnboundedSender<(String, crate::push::PushNotificationData)>,
    >,
) -> (Router, AppState, TestDbGuard) {
    // Ensure tests run sequentially against the shared Postgres instance
    let guard = acquire_test_db_guard().await;

    unsafe {
        std::env::set_var("S3_BUCKET_NAME", "test-bucket");
        std::env::set_var("AWS_ACCESS_KEY_ID", "test-key");
        std::env::set_var("AWS_SECRET_ACCESS_KEY", "test-secret");
        std::env::set_var("AWS_REGION", "us-east-1");
        std::env::set_var("EMAIL_DEV_MODE", "true");
    }

    let db_pool = setup_test_database().await;

    let k1_cache = setup_test_k1_store().await;
    let invoice_store = setup_test_invoice_store().await;
    let email_verification_store = setup_test_email_verification_store().await;
    let email_client = EmailClient::new("test@noahwallet.com".to_string(), true)
        .await
        .expect("Failed to create email client");

    let maintenance_store = setup_test_maintenance_store().await;

    let app_state = Arc::new(AppStruct {
        push_deliveries,
        direct_push: Arc::new(
            crate::direct_push::DirectPush::from_config(&TestUser::get_config()).unwrap(),
        ),
        lnurl_domain: "localhost".to_string(),
        ark_server_pubkey: Arc::new(tokio::sync::RwLock::new(None)),
        db_pool: db_pool.clone(),
        redis: RedisClient::new(&TestUser::get_config().redis_url).unwrap(),
        k1_cache: k1_cache.clone(),
        invoice_store,
        email_verification_store,
        email_client,
        maintenance_store,
        barkd_invoice_provider,
        lnurl_pay_receive_metadata_repo: TestUser::get_config()
            .lnurl_pay_receive_metadata_encryption_key
            .map(|key| {
                crate::db::lnurl_pay_receive_metadata_repo::LnurlPayReceiveMetadataRepository::new(
                    &db_pool, &key,
                )
            }),
        config: Arc::new(TestUser::get_config()),
    });

    let app = crate::app_router(app_state.clone()).layer(Extension(ConnectInfo(SocketAddr::from(
        ([127, 0, 0, 1], 3000),
    ))));

    (app, app_state, guard)
}

// Helper function to create a test user in the database
/// A signed BOLT11 invoice; `seed` sets the payment hash, so two seeds give two invoices.
pub fn test_invoice(network: bitcoin::Network, amount_msat: u64, seed: u8) -> String {
    use bitcoin::hashes::{Hash, sha256};
    use bitcoin::secp256k1::{Secp256k1, SecretKey};
    use lightning_invoice::{Currency, InvoiceBuilder, PaymentSecret};

    let private_key = SecretKey::from_slice(&[3; 32]).unwrap();
    InvoiceBuilder::new(Currency::from(network))
        .description("Test invoice".to_string())
        .payment_hash(sha256::Hash::from_byte_array([seed; 32]))
        .payment_secret(PaymentSecret([2; 32]))
        .current_timestamp()
        .min_final_cltv_expiry_delta(18)
        .amount_milli_satoshis(amount_msat)
        .build_signed(|hash| Secp256k1::new().sign_ecdsa_recoverable(hash, &private_key))
        .unwrap()
        .to_string()
}

pub async fn create_test_user(app_state: &AppState, user: &TestUser, ark_address: Option<&str>) {
    sqlx::query("INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, $3)")
        .bind(user.pubkey().to_string())
        .bind("test@localhost")
        .bind(ark_address)
        .execute(&app_state.db_pool)
        .await
        .unwrap();
}

async fn setup_test_database() -> PgPool {
    let database_url = std::env::var("TEST_DATABASE_URL")
        .unwrap_or_else(|_| "postgres://postgres:postgres@localhost:5432/noah_test".to_string());

    let pool = PgPoolOptions::new()
        .max_connections(10)
        .connect(&database_url)
        .await
        .expect("Failed to connect to TEST_DATABASE_URL");

    crate::db::migrations::run_migrations(&pool)
        .await
        .expect("Failed to run migrations");
    reset_database(&pool)
        .await
        .expect("Failed to reset database");

    pool
}

async fn setup_test_k1_store() -> K1Store {
    let redis_url =
        std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string());
    let redis_client = RedisClient::new(&redis_url).expect("Failed to create Redis client");
    let k1_store = K1Store::new(redis_client, 600);
    k1_store
        .clear_all()
        .await
        .expect("Failed to clear Redis cache");
    k1_store
}

async fn setup_test_invoice_store() -> InvoiceStore {
    let redis_url =
        std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string());
    let redis_client = RedisClient::new(&redis_url).expect("Failed to create Redis client");
    InvoiceStore::new(redis_client)
}

async fn setup_test_email_verification_store() -> EmailVerificationStore {
    let redis_url =
        std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string());
    let redis_client = RedisClient::new(&redis_url).expect("Failed to create Redis client");
    EmailVerificationStore::new(redis_client)
}

async fn setup_test_maintenance_store() -> MaintenanceStore {
    let redis_url =
        std::env::var("TEST_REDIS_URL").unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string());
    let redis_client = RedisClient::new(&redis_url).expect("Failed to create Redis client");
    MaintenanceStore::new(redis_client)
}

async fn reset_database(pool: &PgPool) -> sqlx::Result<()> {
    sqlx::query(
        r#"
        TRUNCATE TABLE
            fiat_rates,
            heartbeat_notifications,
            job_status_reports,
            devices,
            backup_metadata,
            backup_settings,
            mailbox_authorizations,
            push_tokens,
            recurring_payment_schedules,
            users
        RESTART IDENTITY CASCADE
        "#,
    )
    .execute(pool)
    .await
    .map(|_| ())
}
