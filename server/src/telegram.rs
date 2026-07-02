use std::time::Duration;

use serde::Serialize;

use crate::config::Config;

const TELEGRAM_SEND_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Clone)]
struct TelegramConfig {
    bot_token: String,
    chat_id: String,
    message_thread_id: Option<i64>,
}

#[derive(Serialize)]
struct TelegramSendMessageRequest {
    chat_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    message_thread_id: Option<i64>,
    text: String,
    parse_mode: &'static str,
    disable_web_page_preview: bool,
}

pub async fn send_support_ticket_notification(
    http_client: &reqwest::Client,
    config: &Config,
    ticket_id: &str,
    ticket_number: Option<&str>,
) -> anyhow::Result<bool> {
    let Some(telegram_config) = TelegramConfig::from_config(config) else {
        return Ok(false);
    };

    let message =
        build_support_ticket_message(&config.zoho_agent_ticket_base_url, ticket_id, ticket_number);

    let request = TelegramSendMessageRequest {
        chat_id: telegram_config.chat_id,
        message_thread_id: telegram_config.message_thread_id,
        text: message,
        parse_mode: "HTML",
        disable_web_page_preview: true,
    };

    let response = http_client
        .post(format!(
            "https://api.telegram.org/bot{}/sendMessage",
            telegram_config.bot_token
        ))
        .json(&request)
        .timeout(TELEGRAM_SEND_TIMEOUT)
        .send()
        .await
        .map_err(|_| anyhow::anyhow!("Telegram sendMessage request failed"))?;

    if !response.status().is_success() {
        anyhow::bail!(
            "Telegram sendMessage failed with status {}",
            response.status()
        );
    }

    Ok(true)
}

impl TelegramConfig {
    fn from_config(config: &Config) -> Option<Self> {
        let bot_token = config.telegram_bot_token.as_ref()?.trim();
        let chat_id = config.telegram_support_chat_id.as_ref()?.trim();

        if bot_token.is_empty() || chat_id.is_empty() {
            return None;
        }

        Some(Self {
            bot_token: bot_token.to_string(),
            chat_id: chat_id.to_string(),
            message_thread_id: config.telegram_support_message_thread_id,
        })
    }
}

fn build_support_ticket_message(
    ticket_base_url: &str,
    ticket_id: &str,
    ticket_number: Option<&str>,
) -> String {
    let ticket_url = build_ticket_url(ticket_base_url, ticket_id);
    let ticket_label = ticket_number
        .filter(|value| !value.trim().is_empty())
        .map(|value| format!("#{}", escape_html(value.trim())))
        .unwrap_or_else(|| "Open in Zoho Desk".to_string());

    format!(
        "🎫 <b>New support ticket</b>\n\nTicket: <a href=\"{}\">{}</a>",
        escape_html(&ticket_url),
        ticket_label
    )
}

fn build_ticket_url(ticket_base_url: &str, ticket_id: &str) -> String {
    format!(
        "{}/{}",
        ticket_base_url.trim().trim_end_matches('/'),
        ticket_id.trim()
    )
}

fn escape_html(value: &str) -> String {
    value
        .chars()
        .map(|c| match c {
            '&' => "&amp;".to_string(),
            '<' => "&lt;".to_string(),
            '>' => "&gt;".to_string(),
            '"' => "&quot;".to_string(),
            '\'' => "&#39;".to_string(),
            _ => c.to_string(),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;

    #[test]
    fn build_support_ticket_message_includes_only_ticket_number_and_link() {
        let message = build_support_ticket_message(
            "https://desk.zoho.com/agent/noahsupport/noah/tickets/details",
            "1343900000000463036",
            Some("106"),
        );

        assert!(message.contains("New support ticket"));
        assert!(message.contains("#106"));
        assert!(message.contains(
            "https://desk.zoho.com/agent/noahsupport/noah/tickets/details/1343900000000463036"
        ));
        assert!(!message.contains("Name"));
        assert!(!message.contains("Email"));
        assert!(!message.contains("User key"));
        assert!(!message.contains("Device"));
    }

    #[test]
    fn build_support_ticket_message_escapes_ticket_number() {
        let message = build_support_ticket_message(
            "https://desk.zoho.com/agent/noahsupport/noah/tickets/details",
            "1343900000000463036",
            Some("<106&>"),
        );

        assert!(message.contains("#&lt;106&amp;&gt;"));
    }

    #[test]
    fn build_support_ticket_message_falls_back_without_ticket_number() {
        let message = build_support_ticket_message(
            "https://desk.zoho.com/agent/noahsupport/noah/tickets/details/",
            "1343900000000463036",
            None,
        );

        assert!(message.contains(">Open in Zoho Desk</a>"));
        assert!(message.contains("/tickets/details/1343900000000463036"));
    }

    #[test]
    fn telegram_config_is_none_without_required_env() {
        let config = test_config();

        assert!(TelegramConfig::from_config(&config).is_none());
    }

    fn test_config() -> Config {
        Config {
            host: "localhost".to_string(),
            port: 3000,
            private_port: 3001,
            lnurl_domain: "localhost".to_string(),
            postgres_url: "postgres://postgres:postgres@localhost:5432/noah_test".to_string(),
            postgres_max_connections: 5,
            postgres_min_connections: Some(1),
            expo_access_token: "test-token".to_string(),
            ark_server_url: "http://localhost:8081".to_string(),
            server_network: "test-network".to_string(),
            sentry_url: None,
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
            s3_bucket_name: "test-bucket".to_string(),
            minimum_app_version: "0.0.1".to_string(),
            redis_url: "redis://127.0.0.1:6379".to_string(),
            redis_pool_size: 32,
            ntfy_auth_token: "test-token".to_string(),
            ses_from_address: "test@noahwallet.com".to_string(),
            email_dev_mode: true,
            auth_jwt_secret: "test-jwt-secret".to_string(),
            auth_jwt_ttl_hours: 24,
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
}
