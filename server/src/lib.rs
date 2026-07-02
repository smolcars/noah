use std::sync::Arc;

use sqlx::PgPool;
use sqlx::postgres::PgPoolOptions;

pub mod cache;
pub mod config;
pub mod db;
pub mod email_client;
pub mod errors;
pub mod mailbox_auth;
pub mod mailbox_worker;
pub mod push;
pub mod telegram;
pub mod types;
pub mod utils;
pub mod zoho;

use crate::{
    cache::{
        email_verification_store::EmailVerificationStore, invoice_store::InvoiceStore,
        k1_store::K1Store, maintenance_store::MaintenanceStore, redis_client::RedisClient,
    },
    config::Config,
    email_client::EmailClient,
};

pub type AppState = Arc<AppStruct>;
pub const K1_TTL_SECONDS: usize = 600;

#[derive(Clone)]
pub struct AppStruct {
    pub config: Arc<Config>,
    pub lnurl_domain: String,
    pub db_pool: PgPool,
    pub k1_cache: K1Store,
    pub invoice_store: InvoiceStore,
    pub email_verification_store: EmailVerificationStore,
    pub email_client: EmailClient,
    pub maintenance_store: MaintenanceStore,
}

pub async fn build_app_state(config: Config) -> anyhow::Result<AppState> {
    let db_pool = PgPoolOptions::new()
        .max_connections(config.postgres_max_connections)
        .min_connections(config.postgres_min_connections.unwrap_or(1))
        .connect(&config.postgres_url)
        .await?;

    sqlx::query("SELECT 1").execute(&db_pool).await?;
    db::migrations::run_migrations(&db_pool).await?;

    let redis_client = RedisClient::with_pool_size(&config.redis_url, config.redis_pool_size)?;
    redis_client.check_connection().await?;

    let k1_cache = K1Store::new(redis_client.clone(), K1_TTL_SECONDS);
    let invoice_store = InvoiceStore::new(redis_client.clone());
    let maintenance_store = MaintenanceStore::new(redis_client.clone());
    let email_verification_store = EmailVerificationStore::new(redis_client);
    let email_client =
        EmailClient::new(config.ses_from_address.clone(), config.email_dev_mode).await?;

    Ok(Arc::new(AppStruct {
        config: Arc::new(config.clone()),
        lnurl_domain: config.lnurl_domain.clone(),
        db_pool,
        k1_cache,
        invoice_store,
        email_verification_store,
        email_client,
        maintenance_store,
    }))
}
