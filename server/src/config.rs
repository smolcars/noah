use anyhow::{Context, Result};
use bitcoin::Network;
use std::net::Ipv4Addr;
use std::str::FromStr;

pub const ARK_USER_AGENT: &str = concat!("noah-server/", env!("CARGO_PKG_VERSION"));

/// Configuration for the Noah server
///
/// All config fields are set via environment variables:
/// - `HOST`, `PORT`, `PRIVATE_PORT`
/// - `POSTGRES_URL`, `REDIS_URL`
/// - `EXPO_ACCESS_TOKEN`, `ARK_SERVER_URL`
/// - `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`
#[derive(Debug, Clone)]
pub struct Config {
    pub host: String,
    pub port: u16,
    pub private_port: u16,
    pub lnurl_domain: String,
    pub postgres_url: String,
    pub postgres_max_connections: u32,
    pub postgres_min_connections: Option<u32>,
    pub expo_access_token: String,
    pub ark_server_url: String,
    pub server_network: String,
    pub sentry_url: Option<String>,
    pub backup_cron: String,
    pub maintenance_interval_rounds: u16,
    pub maintenance_notification_advance_secs: u64,
    pub heartbeat_cron: String,
    pub deregister_cron: String,
    pub fiat_rate_refresh_cron: String,
    pub mailbox_auth_cleanup_cron: String,
    pub fiat_rate_backfill_days: u64,
    pub coingecko_demo_api_key: Option<String>,
    pub notification_spacing_minutes: i64,
    pub s3_bucket_name: String,
    pub minimum_app_version: String,
    pub redis_url: String,
    pub redis_pool_size: usize,
    pub ntfy_auth_token: String,
    pub ses_from_address: String,
    pub email_dev_mode: bool,
    pub auth_jwt_secret: String,
    pub auth_jwt_ttl_hours: u64,
    pub zoho_client_id: Option<String>,
    pub zoho_client_secret: Option<String>,
    pub zoho_refresh_token: Option<String>,
    pub zoho_org_id: Option<String>,
    pub zoho_department_id: Option<String>,
    pub zoho_accounts_url: String,
    pub zoho_api_domain: String,
    pub zoho_agent_ticket_base_url: String,
    pub telegram_bot_token: Option<String>,
    pub telegram_support_chat_id: Option<String>,
    pub telegram_support_message_thread_id: Option<i64>,
}

impl Config {
    pub fn load() -> Result<Self> {
        dotenvy::dotenv().ok();

        let config = Self {
            host: std::env::var("HOST").unwrap_or_else(|_| "0.0.0.0".to_string()),
            port: std::env::var("PORT")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(3000),
            private_port: std::env::var("PRIVATE_PORT")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(3099),
            lnurl_domain: std::env::var("LNURL_DOMAIN").unwrap_or_else(|_| "localhost".to_string()),
            postgres_url: std::env::var("POSTGRES_URL").unwrap_or_default(),
            postgres_max_connections: std::env::var("POSTGRES_MAX_CONNECTIONS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(10),
            postgres_min_connections: std::env::var("POSTGRES_MIN_CONNECTIONS")
                .ok()
                .and_then(|v| v.parse().ok()),
            expo_access_token: std::env::var("EXPO_ACCESS_TOKEN").unwrap_or_default(),
            ark_server_url: std::env::var("ARK_SERVER_URL").unwrap_or_default(),
            server_network: std::env::var("SERVER_NETWORK")
                .unwrap_or_else(|_| "regtest".to_string()),
            sentry_url: std::env::var("SENTRY_URL").ok(),
            backup_cron: std::env::var("BACKUP_CRON")
                .unwrap_or_else(|_| "every 2 hours".to_string()),
            maintenance_interval_rounds: std::env::var("MAINTENANCE_INTERVAL_ROUNDS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(1),
            maintenance_notification_advance_secs: std::env::var(
                "MAINTENANCE_NOTIFICATION_ADVANCE_SECS",
            )
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(60),
            heartbeat_cron: std::env::var("HEARTBEAT_CRON")
                .unwrap_or_else(|_| "every 48 hours".to_string()),
            deregister_cron: std::env::var("DEREGISTER_CRON")
                .unwrap_or_else(|_| "every 12 hours".to_string()),
            fiat_rate_refresh_cron: std::env::var("FIAT_RATE_REFRESH_CRON")
                .unwrap_or_else(|_| "every 5 minutes".to_string()),
            mailbox_auth_cleanup_cron: std::env::var("MAILBOX_AUTH_CLEANUP_CRON")
                .unwrap_or_else(|_| "every 10 minutes".to_string()),
            fiat_rate_backfill_days: std::env::var("FIAT_RATE_BACKFILL_DAYS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(60),
            coingecko_demo_api_key: std::env::var("COINGECKO_DEMO_API_KEY").ok(),
            notification_spacing_minutes: std::env::var("NOTIFICATION_SPACING_MINUTES")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(45),
            s3_bucket_name: std::env::var("S3_BUCKET_NAME").unwrap_or_default(),
            minimum_app_version: std::env::var("MINIMUM_APP_VERSION")
                .unwrap_or_else(|_| "0.0.1".to_string()),
            redis_url: std::env::var("REDIS_URL")
                .unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string()),
            redis_pool_size: std::env::var("REDIS_POOL_SIZE")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(32),
            ntfy_auth_token: std::env::var("NTFY_AUTH_TOKEN").unwrap_or_default(),
            ses_from_address: std::env::var("SES_FROM_ADDRESS")
                .unwrap_or_else(|_| "noreply@noahwallet.io".to_string()),
            email_dev_mode: std::env::var("EMAIL_DEV_MODE")
                .map(|v| v == "true" || v == "1")
                .unwrap_or(false),
            auth_jwt_secret: std::env::var("AUTH_JWT_SECRET").unwrap_or_default(),
            auth_jwt_ttl_hours: std::env::var("AUTH_JWT_TTL_HOURS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(72),
            zoho_client_id: std::env::var("ZOHO_CLIENT_ID").ok(),
            zoho_client_secret: std::env::var("ZOHO_CLIENT_SECRET").ok(),
            zoho_refresh_token: std::env::var("ZOHO_REFRESH_TOKEN").ok(),
            zoho_org_id: std::env::var("ZOHO_ORG_ID").ok(),
            zoho_department_id: std::env::var("ZOHO_DEPARTMENT_ID").ok(),
            zoho_accounts_url: std::env::var("ZOHO_ACCOUNTS_URL")
                .unwrap_or_else(|_| "https://accounts.zoho.com".to_string()),
            zoho_api_domain: std::env::var("ZOHO_API_DOMAIN")
                .unwrap_or_else(|_| "https://desk.zoho.com".to_string()),
            zoho_agent_ticket_base_url: std::env::var("ZOHO_AGENT_TICKET_BASE_URL").unwrap_or_else(
                |_| "https://desk.zoho.com/agent/noahsupport/noah/tickets/details".to_string(),
            ),
            telegram_bot_token: std::env::var("TELEGRAM_BOT_TOKEN").ok(),
            telegram_support_chat_id: std::env::var("TELEGRAM_SUPPORT_CHAT_ID").ok(),
            telegram_support_message_thread_id: std::env::var("TELEGRAM_SUPPORT_MESSAGE_THREAD_ID")
                .ok()
                .and_then(|v| v.parse().ok()),
        };

        config.validate()?;

        Ok(config)
    }

    fn validate(&self) -> Result<()> {
        if self.postgres_url.is_empty() {
            anyhow::bail!("POSTGRES_URL is required");
        }
        if self.redis_url.is_empty() {
            anyhow::bail!("REDIS_URL is required");
        }
        if self.expo_access_token.is_empty() {
            anyhow::bail!("EXPO_ACCESS_TOKEN is required");
        }
        if self.ark_server_url.is_empty() {
            anyhow::bail!("ARK_SERVER_URL is required");
        }
        if self.s3_bucket_name.is_empty() {
            anyhow::bail!("S3_BUCKET_NAME is required");
        }
        if self.auth_jwt_secret.is_empty() {
            anyhow::bail!("AUTH_JWT_SECRET is required");
        }
        Ok(())
    }

    pub fn host(&self) -> Result<Ipv4Addr> {
        Ipv4Addr::from_str(&self.host).context(format!("Invalid host address: {}", self.host))
    }

    pub fn network(&self) -> Result<Network> {
        Network::from_str(&self.server_network)
            .context(format!("Invalid network: {}", self.server_network))
    }

    pub fn log_config(&self) {
        tracing::debug!("=== Server Configuration ===");
        tracing::debug!("Host: {}", self.host);
        tracing::debug!("Port: {}", self.port);
        tracing::debug!("Private Port: {}", self.private_port);
        tracing::debug!("LNURL Domain: {}", self.lnurl_domain);
        tracing::debug!("Postgres URL: [REDACTED]");
        tracing::debug!(
            "Postgres connection pool: max={}, min={}",
            self.postgres_max_connections,
            self.postgres_min_connections.unwrap_or(1)
        );
        tracing::debug!("Expo Access Token: [REDACTED]");
        tracing::debug!("Ark Server URL: {}", self.ark_server_url);
        tracing::debug!("Server Network: {}", self.server_network);
        tracing::debug!(
            "Sentry URL: {}",
            if self.sentry_url.is_some() {
                "[SET]"
            } else {
                "[NOT SET]"
            }
        );
        tracing::debug!(
            "Zoho Desk support: {}",
            if self.zoho_client_id.is_some()
                && self.zoho_client_secret.is_some()
                && self.zoho_refresh_token.is_some()
                && self.zoho_org_id.is_some()
                && self.zoho_department_id.is_some()
            {
                "[SET]"
            } else {
                "[NOT SET]"
            }
        );
        tracing::debug!(
            "Telegram support notifications: {}",
            if self.telegram_bot_token.is_some() && self.telegram_support_chat_id.is_some() {
                "[SET]"
            } else {
                "[NOT SET]"
            }
        );
        tracing::debug!("Backup Cron: {}", self.backup_cron);
        tracing::debug!("Heartbeat Cron: {}", self.heartbeat_cron);
        tracing::debug!("Deregister Cron: {}", self.deregister_cron);
        tracing::debug!("Fiat Rate Refresh Cron: {}", self.fiat_rate_refresh_cron);
        tracing::debug!(
            "Mailbox Auth Cleanup Cron: {}",
            self.mailbox_auth_cleanup_cron
        );
        tracing::debug!("Fiat Rate Backfill Days: {}", self.fiat_rate_backfill_days);
        tracing::debug!(
            "CoinGecko Demo API Key: {}",
            if self.coingecko_demo_api_key.is_some() {
                "[SET]"
            } else {
                "[NOT SET]"
            }
        );
        tracing::debug!(
            "Notification Spacing Minutes: {}",
            self.notification_spacing_minutes
        );
        tracing::debug!(
            "Maintenance Interval Rounds: {}",
            self.maintenance_interval_rounds
        );
        tracing::debug!(
            "Maintenance Notification Advance Secs: {}",
            self.maintenance_notification_advance_secs
        );
        tracing::debug!("S3 Bucket Name: [REDACTED]");
        tracing::debug!("Minimum App Version: {}", self.minimum_app_version);
        tracing::debug!("Redis URL: [REDACTED]");
        tracing::debug!("Redis Pool Size: {}", self.redis_pool_size);
        tracing::debug!("Ntfy Auth Token: [REDACTED]");
        tracing::debug!("SES From Address: {}", self.ses_from_address);
        tracing::debug!("JWT Auth Secret: [REDACTED]");
        tracing::debug!("JWT TTL Hours: {}", self.auth_jwt_ttl_hours);
        tracing::debug!("============================");
    }
}
