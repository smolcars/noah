use anyhow::{Context, Result};
use bitcoin::Network;
use std::net::{IpAddr, Ipv4Addr};
use std::str::FromStr;

pub const ARK_USER_AGENT: &str = concat!("noah-server/", env!("CARGO_PKG_VERSION"));

const BARKD_MAINNET_HOST: &str = "noah-barkd-mainnet.internal";
const BARKD_SIGNET_HOST: &str = "noah-barkd-signet.internal";
const BARKD_SIGNET_RAILWAY_HOST: &str = "noah-barkd-signet.railway.internal";
const BARKD_PORT: u16 = 3000;

fn validate_barkd_url(network: Network, url: &reqwest::Url) -> Result<()> {
    if url.scheme() != "http"
        || url.cannot_be_a_base()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
    {
        anyhow::bail!("BARKD_URL must be an HTTP origin without credentials, path, or query");
    }

    let host = url.host_str().context("BARKD_URL must include a host")?;
    match network {
        Network::Bitcoin => {
            if host != BARKD_MAINNET_HOST || url.port() != Some(BARKD_PORT) {
                anyhow::bail!("mainnet BARKD_URL must be http://{BARKD_MAINNET_HOST}:{BARKD_PORT}");
            }
        }
        Network::Signet => {
            if ![BARKD_SIGNET_HOST, BARKD_SIGNET_RAILWAY_HOST].contains(&host)
                || url.port() != Some(BARKD_PORT)
            {
                anyhow::bail!(
                    "signet BARKD_URL must be http://{BARKD_SIGNET_HOST}:{BARKD_PORT} or http://{BARKD_SIGNET_RAILWAY_HOST}:{BARKD_PORT}"
                );
            }
        }
        Network::Regtest => {
            let host_without_ipv6_brackets = host
                .strip_prefix('[')
                .and_then(|host| host.strip_suffix(']'))
                .unwrap_or(host);
            let is_loopback = host == "localhost"
                || host_without_ipv6_brackets
                    .parse::<IpAddr>()
                    .is_ok_and(|address| address.is_loopback());
            if !is_loopback {
                anyhow::bail!("regtest BARKD_URL must use localhost or a loopback IP address");
            }
        }
        _ => anyhow::bail!("forwarded barkd invoices are not supported on {network}"),
    }

    Ok(())
}

/// Configuration for the Noah server
///
/// All config fields are set via environment variables:
/// - `HOST`, `PORT`, `PRIVATE_PORT`
/// - `POSTGRES_URL`, `REDIS_URL`
/// - `EXPO_ACCESS_TOKEN`, `ARK_SERVER_URL`
/// - `BARKD_FORWARDED_INVOICES_ENABLED`, `BARKD_URL`, `BARKD_AUTH_TOKEN`
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
    pub ses_from_address: String,
    pub email_dev_mode: bool,
    pub auth_jwt_secret: String,
    pub auth_jwt_ttl_hours: u64,
    pub barkd_forwarded_invoices_enabled: bool,
    pub barkd_url: Option<String>,
    pub barkd_auth_token: Option<String>,
    pub barkd_request_timeout_seconds: u64,
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
            barkd_forwarded_invoices_enabled: std::env::var("BARKD_FORWARDED_INVOICES_ENABLED")
                .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "True"))
                .unwrap_or(false),
            barkd_url: std::env::var("BARKD_URL").ok(),
            barkd_auth_token: std::env::var("BARKD_AUTH_TOKEN").ok(),
            barkd_request_timeout_seconds: std::env::var("BARKD_REQUEST_TIMEOUT_SECONDS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(10),
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
        if self.barkd_forwarded_invoices_enabled {
            let barkd_url = self
                .barkd_url
                .as_deref()
                .filter(|value| !value.trim().is_empty())
                .context("BARKD_URL is required when forwarded invoices are enabled")?;
            let barkd_url =
                reqwest::Url::parse(barkd_url).context("BARKD_URL must be a valid URL")?;
            validate_barkd_url(self.network()?, &barkd_url)?;
            self.barkd_auth_token
                .as_deref()
                .filter(|value| !value.trim().is_empty())
                .context("BARKD_AUTH_TOKEN is required when forwarded invoices are enabled")?;
            if self.barkd_request_timeout_seconds == 0 {
                anyhow::bail!("BARKD_REQUEST_TIMEOUT_SECONDS must be greater than zero");
            }
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
        tracing::debug!("SES From Address: {}", self.ses_from_address);
        tracing::debug!("JWT Auth Secret: [REDACTED]");
        tracing::debug!("JWT TTL Hours: {}", self.auth_jwt_ttl_hours);
        tracing::debug!(
            "Barkd forwarded invoices: {}",
            self.barkd_forwarded_invoices_enabled
        );
        tracing::debug!(
            "Barkd URL: {}",
            if self.barkd_url.is_some() {
                "[SET]"
            } else {
                "[NOT SET]"
            }
        );
        tracing::debug!(
            "Barkd auth token: {}",
            if self.barkd_auth_token.is_some() {
                "[SET]"
            } else {
                "[NOT SET]"
            }
        );
        tracing::debug!(
            "Barkd request timeout seconds: {}",
            self.barkd_request_timeout_seconds
        );
        tracing::debug!("============================");
    }
}

#[cfg(test)]
mod tests {
    use bitcoin::Network;

    use super::validate_barkd_url;

    fn validate(network: Network, url: &str) -> bool {
        validate_barkd_url(network, &reqwest::Url::parse(url).unwrap()).is_ok()
    }

    #[test]
    fn production_barkd_urls_are_pinned_to_the_private_fly_apps() {
        assert!(validate(
            Network::Bitcoin,
            "http://noah-barkd-mainnet.internal:3000"
        ));
        assert!(validate(
            Network::Signet,
            "http://noah-barkd-signet.internal:3000/"
        ));

        for (network, url) in [
            (Network::Bitcoin, "https://noah-barkd-mainnet.internal:3000"),
            (Network::Bitcoin, "http://noah-barkd-mainnet.internal"),
            (Network::Bitcoin, "http://noah-barkd-signet.internal:3000"),
            (Network::Signet, "http://example.com:3000"),
            (
                Network::Signet,
                "http://noah-barkd-signet.internal:3000/api",
            ),
            (
                Network::Signet,
                "http://token@noah-barkd-signet.internal:3000",
            ),
        ] {
            assert!(!validate(network, url), "accepted unsafe barkd URL: {url}");
        }
    }

    #[test]
    fn signet_barkd_urls_are_pinned_to_the_private_railway_service() {
        assert!(validate(
            Network::Signet,
            "http://noah-barkd-signet.railway.internal:3000"
        ));
        assert!(validate(
            Network::Signet,
            "http://noah-barkd-signet.railway.internal:3000/"
        ));

        for (network, url) in [
            (
                Network::Bitcoin,
                "http://noah-barkd-signet.railway.internal:3000",
            ),
            (
                Network::Signet,
                "http://noah-barkd-mainnet.railway.internal:3000",
            ),
            (Network::Signet, "http://other.railway.internal:3000"),
            (
                Network::Signet,
                "http://noah-barkd-signet.railway.internal.example.com:3000",
            ),
            (
                Network::Signet,
                "https://noah-barkd-signet.railway.internal:3000",
            ),
            (Network::Signet, "http://noah-barkd-signet.railway.internal"),
            (
                Network::Signet,
                "http://noah-barkd-signet.railway.internal:3001",
            ),
            (
                Network::Signet,
                "http://noah-barkd-signet.railway.internal:3000/api",
            ),
            (
                Network::Signet,
                "http://noah-barkd-signet.railway.internal:3000/?key=value",
            ),
            (
                Network::Signet,
                "http://noah-barkd-signet.railway.internal:3000/#fragment",
            ),
            (
                Network::Signet,
                "http://token@noah-barkd-signet.railway.internal:3000",
            ),
            (
                Network::Signet,
                "http://:token@noah-barkd-signet.railway.internal:3000",
            ),
        ] {
            assert!(!validate(network, url), "accepted unsafe barkd URL: {url}");
        }
    }

    #[test]
    fn regtest_barkd_urls_are_limited_to_loopback_hosts() {
        assert!(validate(Network::Regtest, "http://localhost:3000"));
        assert!(validate(Network::Regtest, "http://127.0.0.1:3100"));
        assert!(validate(Network::Regtest, "http://[::1]:3200"));
        assert!(!validate(Network::Regtest, "http://192.168.1.10:3000"));
        assert!(!validate(Network::Regtest, "https://localhost:3000"));
        assert!(!validate(Network::Testnet, "http://localhost:3000"));
    }
}
