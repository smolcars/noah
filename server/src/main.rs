use axum::{
    Router,
    http::StatusCode,
    middleware,
    routing::{get, post},
};
mod auth;
mod cache;
mod config;
mod routes;
mod types;
use bitcoin::Network;
use sentry::integrations::{
    tower::{NewSentryLayer, SentryHttpLayer},
    tracing::EventFilter,
};
use std::{net::SocketAddr, sync::Arc};

use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use crate::{
    cache::{
        email_verification_store::EmailVerificationStore, invoice_store::InvoiceStore,
        k1_store::K1Store, maintenance_store::MaintenanceStore, redis_client::RedisClient,
    },
    config::Config,
    cron::cron_scheduler,
    email_client::EmailClient,
    mailbox_worker::{Beta8MailboxTransport, MailboxWorker, MailboxWorkerConfig},
    routes::{
        app_middleware,
        gated_api_v0::{
            authorize_mailbox, complete_upload, delete_backup, deregister, get_download_url,
            get_upload_url, get_user_info, heartbeat_response, list_backups,
            ln_address_suggestions, register_push_token, report_job_status, report_last_login,
            revoke_mailbox_authorization, submit_invoice, update_backup_settings,
            update_ln_address, update_profile,
        },
        public_api_v0::{
            auth_login, check_app_version, fiat_prices, get_k1, historical_fiat_price,
            lnurlp_request, register, send_verification_email, verify_email,
        },
    },
};

mod ark_client;
mod cron;
pub mod db;
mod email_client;
mod errors;
mod fiat_rates;
mod mailbox_auth;
mod mailbox_worker;
mod notification_coordinator;
mod push;
mod rate_limit;
mod s3_client;
#[cfg(test)]
mod tests;
mod trace_layer;
mod utils;
mod wide_event;

use sqlx::PgPool;
use sqlx::postgres::PgPoolOptions;

type AppState = Arc<AppStruct>;
const K1_TTL_SECONDS: usize = 600;

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

fn main() -> anyhow::Result<()> {
    let config = Config::load()?;

    let server_network = config.network()?;

    // Initialize Sentry first if we're on production networks
    let _sentry_guard = if server_network == Network::Bitcoin || server_network == Network::Signet {
        config.sentry_url.clone().map(|sentry_url| {
            sentry::init((
                sentry_url,
                sentry::ClientOptions {
                    release: sentry::release_name!(),
                    enable_logs: true,
                    send_default_pii: false,
                    traces_sample_rate: 1.0,
                    ..Default::default()
                },
            ))
        })
    } else {
        None
    };

    // Build subscriber with conditional Sentry layer
    let subscriber = tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "server=debug,tower_http=debug".into()),
        )
        .with(tracing_subscriber::fmt::layer());

    // Initialize subscriber with or without Sentry layer
    if _sentry_guard.is_some() {
        let sentry_layer =
            sentry::integrations::tracing::layer().event_filter(|md| match *md.level() {
                tracing::Level::ERROR => EventFilter::Log,
                tracing::Level::WARN => EventFilter::Log,
                tracing::Level::INFO => EventFilter::Log,
                tracing::Level::DEBUG => EventFilter::Log,
                _ => EventFilter::Ignore,
            });
        subscriber.with(sentry_layer).init();
    } else {
        subscriber.init();
    }

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;

    runtime.block_on(async { start_server(config).await })?;

    Ok(())
}

async fn start_server(config: Config) -> anyhow::Result<()> {
    let host = config.host()?;

    tracing::info!("Checking Postgres connection...");
    let db_pool = PgPoolOptions::new()
        .max_connections(config.postgres_max_connections)
        .min_connections(config.postgres_min_connections.unwrap_or(1))
        .connect(&config.postgres_url)
        .await?;

    sqlx::query("SELECT 1")
        .execute(&db_pool)
        .await
        .map_err(|e| anyhow::anyhow!("Failed to connect to Postgres: {}", e))?;
    tracing::info!("Postgres connection established");

    db::migrations::run_migrations(&db_pool).await?;

    tracing::info!("Checking Redis connection...");
    let redis_client = RedisClient::with_pool_size(&config.redis_url, config.redis_pool_size)?;
    redis_client
        .check_connection()
        .await
        .map_err(|e| anyhow::anyhow!("Failed to connect to Redis: {}", e))?;
    tracing::info!("Redis connection established");
    let k1_cache = K1Store::new(redis_client.clone(), K1_TTL_SECONDS);
    let invoice_store = InvoiceStore::new(redis_client.clone());
    let maintenance_store = MaintenanceStore::new(redis_client.clone());
    let email_verification_store = EmailVerificationStore::new(redis_client);

    tracing::info!("Initializing email client...");
    let email_client =
        EmailClient::new(config.ses_from_address.clone(), config.email_dev_mode).await?;
    tracing::info!("Email client initialized");

    let app_state = Arc::new(AppStruct {
        config: Arc::new(config.clone()),
        lnurl_domain: config.lnurl_domain.clone(),
        db_pool: db_pool.clone(),
        k1_cache: k1_cache.clone(),
        invoice_store,
        email_verification_store,
        email_client,
        maintenance_store,
    });

    config.log_config();

    let backup_cron = config.backup_cron.clone();
    let heartbeat_cron = config.heartbeat_cron.clone();
    let deregister_cron = config.deregister_cron.clone();
    let fiat_rate_refresh_cron = config.fiat_rate_refresh_cron.clone();
    let mailbox_auth_cleanup_cron = config.mailbox_auth_cleanup_cron.clone();
    let cron_handle = cron_scheduler(
        app_state.clone(),
        backup_cron,
        heartbeat_cron,
        deregister_cron,
        fiat_rate_refresh_cron,
        mailbox_auth_cleanup_cron,
    )
    .await?;

    cron_handle.start().await?;

    let fiat_rate_startup_state = app_state.clone();
    tokio::spawn(async move {
        if let Err(e) = cron::refresh_fiat_rates(fiat_rate_startup_state).await {
            tracing::error!(job = "fiat_rates", error = %e, "startup refresh failed");
        }
    });

    let ark_client_app_state = app_state.clone();
    let ark_server_url = config.ark_server_url.clone();

    tokio::spawn(async move {
        if let Err(e) =
            ark_client::connect_to_ark_server(ark_client_app_state, ark_server_url).await
        {
            tracing::error!("Failed to connect to ark server: {}", e);
        }
    });

    let run_mailbox_worker = std::env::var("RUN_MAILBOX_WORKER")
        .map(|value| !matches!(value.as_str(), "0" | "false" | "FALSE" | "False"))
        .unwrap_or(true);

    if run_mailbox_worker {
        let mailbox_worker_app_state = app_state.clone();
        tokio::spawn(async move {
            let mailbox_worker_config = MailboxWorkerConfig::from_env();
            mailbox_worker_config.log();

            let worker = MailboxWorker::new(
                mailbox_worker_app_state,
                Arc::new(Beta8MailboxTransport),
                mailbox_worker_config,
            );

            if let Err(e) = worker.run().await {
                tracing::error!("Mailbox worker exited: {}", e);
            }
        });
    } else {
        tracing::info!("Mailbox worker disabled via RUN_MAILBOX_WORKER");
    }

    // Middleware that checks the signature and authenticates the user
    let auth_layer =
        middleware::from_fn_with_state(app_state.clone(), app_middleware::auth_middleware);

    // Middleware that only checks for user existence
    let user_exists_layer =
        middleware::from_fn_with_state(app_state.clone(), app_middleware::user_exists_middleware);

    // Create rate limiters
    let public_rate_limiter = rate_limit::create_public_rate_limiter();
    let auth_login_rate_limiter = rate_limit::create_public_rate_limiter();
    let auth_rate_limiter = rate_limit::create_auth_rate_limiter();
    let fiat_rate_limiter = rate_limit::create_fiat_rate_limiter();

    // Optional email setup routes need auth and a registered user.
    let email_verification_router = Router::new()
        .route("/email/send_verification", post(send_verification_email))
        .route("/email/verify", post(verify_email))
        .layer(user_exists_layer.clone());

    // Gated routes need auth and a registered user. Email is optional.
    let gated_router = Router::new()
        .route("/register_push_token", post(register_push_token))
        .route("/mailbox/authorize", post(authorize_mailbox))
        .route("/mailbox/revoke", post(revoke_mailbox_authorization))
        .route("/lnurlp/submit_invoice", post(submit_invoice))
        .route("/ln_address_suggestions", post(ln_address_suggestions))
        .route("/user_info", post(get_user_info))
        .route("/update_ln_address", post(update_ln_address))
        .route("/update_profile", post(update_profile))
        .route("/deregister", post(deregister))
        .route("/backup/upload_url", post(get_upload_url))
        .route("/backup/complete_upload", post(complete_upload))
        .route("/backup/list", post(list_backups))
        .route("/backup/download_url", post(get_download_url))
        .route("/backup/delete", post(delete_backup))
        .route("/backup/settings", post(update_backup_settings))
        .route("/report_job_status", post(report_job_status))
        .route("/heartbeat_response", post(heartbeat_response))
        .route("/report_last_login", post(report_last_login))
        .layer(user_exists_layer.clone());

    // Fiat routes need auth and a registered user, but use their own limiter so
    // wallet history price lookups do not consume the general authenticated API bucket.
    let fiat_router = Router::new()
        .route("/prices", post(fiat_prices))
        .route("/historical-price", post(historical_fiat_price))
        .layer(user_exists_layer)
        .layer(fiat_rate_limiter)
        .layer(auth_layer.clone());

    // Routes that need auth but user may not exist (like registration)
    // Apply auth rate limiter to these routes
    let bearer_router = Router::new()
        .route("/register", post(register))
        .merge(email_verification_router)
        .merge(gated_router)
        .layer(auth_rate_limiter)
        .layer(auth_layer);

    // Public routes with strict rate limiting on getk1
    let v0_router = Router::new()
        .route("/getk1", get(get_k1).layer(public_rate_limiter))
        .route(
            "/auth/login",
            post(auth_login).layer(auth_login_rate_limiter),
        )
        .route("/app_version", post(check_app_version))
        .merge(fiat_router)
        .merge(bearer_router);

    // Public route
    let lnurl_router = Router::new().route("/.well-known/lnurlp/{username}", get(lnurlp_request));

    let app = Router::new()
        .route("/", get(|| async { StatusCode::NO_CONTENT }))
        .route("/health", get(|| async { StatusCode::OK }))
        .nest("/v0", v0_router)
        .merge(lnurl_router)
        .with_state(app_state.clone())
        .layer(middleware::from_fn(trace_layer::trace_middleware))
        .layer(SentryHttpLayer::new().enable_transaction())
        .layer(NewSentryLayer::new_from_top());

    let addr = SocketAddr::from((host, config.port));
    tracing::debug!("server started listening on {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await?;

    // Important: Use into_make_service_with_connect_info to provide IP information for rate limiting
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await?;

    Ok(())
}
