use std::{
    sync::{LazyLock, Mutex},
    time::{Duration, Instant},
};

use base64::{Engine, engine::general_purpose::STANDARD};
use reqwest::multipart::{Form, Part};
use serde::{Deserialize, Serialize};
use validator::ValidateEmail;

use crate::{
    config::Config,
    errors::ApiError,
    types::{
        AuthenticatedUser, DeviceInfo, SubmitSupportTicketPayload, SubmitSupportTicketResponse,
        SupportTicketAttachment,
    },
};

const MAX_SUBJECT_LEN: usize = 150;
const MAX_BODY_LEN: usize = 60_000;
const MAX_NAME_LEN: usize = 150;
const MAX_ATTACHMENT_BYTES: usize = 5 * 1024 * 1024;
const MAX_ATTACHMENT_BASE64_LEN: usize = MAX_ATTACHMENT_BYTES.div_ceil(3) * 4 + 16;
const ACCESS_TOKEN_EXPIRY_BUFFER: Duration = Duration::from_secs(120);

static TOKEN_CACHE: LazyLock<Mutex<Option<CachedZohoAccessToken>>> =
    LazyLock::new(|| Mutex::new(None));

#[derive(Clone)]
struct ZohoDeskConfig {
    client_id: String,
    client_secret: String,
    refresh_token: String,
    org_id: String,
    department_id: String,
    accounts_url: String,
    api_domain: String,
}

struct CachedZohoAccessToken {
    cache_key: String,
    access_token: String,
    expires_at: Instant,
}

struct ValidatedSupportTicket {
    subject: String,
    body: String,
    name: String,
    email: Option<String>,
    attachment: Option<ValidatedAttachment>,
    device_info: Option<DeviceInfo>,
}

struct ValidatedAttachment {
    filename: String,
    content_type: String,
    bytes: Vec<u8>,
}

#[derive(Deserialize)]
struct ZohoTokenResponse {
    access_token: String,
    expires_in: Option<u64>,
}

#[derive(Deserialize)]
struct ZohoUploadResponse {
    id: String,
}

#[derive(Serialize)]
struct ZohoTicketContact {
    #[serde(rename = "lastName")]
    last_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    email: Option<String>,
}

#[derive(Serialize)]
struct ZohoCreateTicketRequest {
    subject: String,
    #[serde(rename = "departmentId")]
    department_id: String,
    description: String,
    channel: String,
    status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    email: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    contact: Option<ZohoTicketContact>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    uploads: Vec<String>,
}

#[derive(Deserialize)]
struct ZohoCreateTicketResponse {
    id: String,
    #[serde(rename = "ticketNumber")]
    ticket_number: Option<String>,
}

pub async fn submit_support_ticket(
    config: &Config,
    auth_user: &AuthenticatedUser,
    payload: SubmitSupportTicketPayload,
) -> Result<SubmitSupportTicketResponse, ApiError> {
    let zoho_config = ZohoDeskConfig::from_config(config)?;
    let mut payload = validate_support_ticket_payload(payload)?;
    let http_client = reqwest::Client::new();
    let access_token = get_access_token(&http_client, &zoho_config).await?;

    let upload_ids = match payload.attachment.take() {
        Some(attachment) => {
            vec![upload_attachment(&http_client, &zoho_config, &access_token, attachment).await?]
        }
        None => vec![],
    };

    let response = create_ticket(
        &http_client,
        &zoho_config,
        &access_token,
        auth_user,
        payload,
        upload_ids,
    )
    .await?;

    if let Err(e) = crate::telegram::send_support_ticket_notification(
        &http_client,
        config,
        &response.id,
        response.ticket_number.as_deref(),
    )
    .await
    {
        tracing::warn!(error = %e, "Failed to send support ticket Telegram notification");
    }

    Ok(SubmitSupportTicketResponse {
        ticket_id: response.id,
        ticket_number: response.ticket_number,
    })
}

fn validate_support_ticket_payload(
    payload: SubmitSupportTicketPayload,
) -> Result<ValidatedSupportTicket, ApiError> {
    let subject = payload.subject.trim().to_string();
    if subject.is_empty() {
        return Err(ApiError::InvalidArgument("Subject is required".to_string()));
    }
    if subject.len() > MAX_SUBJECT_LEN {
        return Err(ApiError::InvalidArgument("Subject is too long".to_string()));
    }

    let body = payload.body.trim().to_string();
    if body.is_empty() {
        return Err(ApiError::InvalidArgument("Body is required".to_string()));
    }
    if body.len() > MAX_BODY_LEN {
        return Err(ApiError::InvalidArgument("Body is too long".to_string()));
    }

    let name = payload.name.trim().to_string();
    if name.is_empty() {
        return Err(ApiError::InvalidArgument("Name is required".to_string()));
    }
    if name.len() > MAX_NAME_LEN {
        return Err(ApiError::InvalidArgument("Name is too long".to_string()));
    }

    let email = payload.email.and_then(|value| {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_string())
        }
    });
    if email.as_ref().is_some_and(|value| !value.validate_email()) {
        return Err(ApiError::InvalidArgument("Invalid email".to_string()));
    }

    let attachment = payload.attachment.map(validate_attachment).transpose()?;

    Ok(ValidatedSupportTicket {
        subject,
        body,
        name,
        email,
        attachment,
        device_info: payload.device_info,
    })
}

fn validate_attachment(
    attachment: SupportTicketAttachment,
) -> Result<ValidatedAttachment, ApiError> {
    let content_type = attachment.content_type.trim().to_ascii_lowercase();
    if !matches!(content_type.as_str(), "image/jpeg" | "image/png") {
        return Err(ApiError::InvalidArgument(
            "Screenshot must be a JPEG or PNG image".to_string(),
        ));
    }

    if attachment.base64_data.len() > MAX_ATTACHMENT_BASE64_LEN {
        return Err(ApiError::InvalidArgument(
            "Screenshot must be 5 MB or smaller".to_string(),
        ));
    }

    let bytes = STANDARD
        .decode(attachment.base64_data.as_bytes())
        .map_err(|_| ApiError::InvalidArgument("Screenshot data is invalid".to_string()))?;

    if bytes.len() > MAX_ATTACHMENT_BYTES {
        return Err(ApiError::InvalidArgument(
            "Screenshot must be 5 MB or smaller".to_string(),
        ));
    }

    let filename = sanitize_filename(&attachment.filename, &content_type);

    Ok(ValidatedAttachment {
        filename,
        content_type,
        bytes,
    })
}

fn sanitize_filename(filename: &str, content_type: &str) -> String {
    let sanitized = filename
        .trim()
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' => '-',
            _ => c,
        })
        .collect::<String>();

    if !sanitized.is_empty() {
        return sanitized;
    }

    match content_type {
        "image/png" => "screenshot.png".to_string(),
        _ => "screenshot.jpg".to_string(),
    }
}

impl ZohoDeskConfig {
    fn from_config(config: &Config) -> Result<Self, ApiError> {
        let Some(client_id) = config.zoho_client_id.as_ref().filter(|v| !v.is_empty()) else {
            return Err(support_not_configured());
        };
        let Some(client_secret) = config.zoho_client_secret.as_ref().filter(|v| !v.is_empty())
        else {
            return Err(support_not_configured());
        };
        let Some(refresh_token) = config.zoho_refresh_token.as_ref().filter(|v| !v.is_empty())
        else {
            return Err(support_not_configured());
        };
        let Some(org_id) = config.zoho_org_id.as_ref().filter(|v| !v.is_empty()) else {
            return Err(support_not_configured());
        };
        let Some(department_id) = config.zoho_department_id.as_ref().filter(|v| !v.is_empty())
        else {
            return Err(support_not_configured());
        };

        Ok(Self {
            client_id: client_id.clone(),
            client_secret: client_secret.clone(),
            refresh_token: refresh_token.clone(),
            org_id: org_id.clone(),
            department_id: department_id.clone(),
            accounts_url: trim_trailing_slash(&config.zoho_accounts_url),
            api_domain: trim_trailing_slash(&config.zoho_api_domain),
        })
    }

    fn cache_key(&self) -> String {
        format!("{}:{}", self.client_id, self.refresh_token)
    }
}

fn support_not_configured() -> ApiError {
    ApiError::ServerErr("Support ticket submission is not configured".to_string())
}

fn trim_trailing_slash(value: &str) -> String {
    value.trim().trim_end_matches('/').to_string()
}

async fn get_access_token(
    http_client: &reqwest::Client,
    config: &ZohoDeskConfig,
) -> Result<String, ApiError> {
    let cache_key = config.cache_key();
    if let Some(token) = read_cached_access_token(&cache_key) {
        return Ok(token);
    }

    let response = http_client
        .post(format!("{}/oauth/v2/token", config.accounts_url))
        .form(&[
            ("refresh_token", config.refresh_token.as_str()),
            ("client_id", config.client_id.as_str()),
            ("client_secret", config.client_secret.as_str()),
            ("grant_type", "refresh_token"),
        ])
        .send()
        .await
        .map_err(|e| {
            tracing::error!(error = %e, "Failed to refresh Zoho access token");
            ApiError::ServerErr("Failed to submit support ticket".to_string())
        })?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        tracing::error!(%status, body = %body, "Zoho token refresh failed");
        return Err(ApiError::ServerErr(
            "Failed to submit support ticket".to_string(),
        ));
    }

    let token_response = response.json::<ZohoTokenResponse>().await.map_err(|e| {
        tracing::error!(error = %e, "Failed to parse Zoho token response");
        ApiError::ServerErr("Failed to submit support ticket".to_string())
    })?;

    let expires_in = token_response.expires_in.unwrap_or(3600);
    let expires_at =
        Instant::now() + Duration::from_secs(expires_in).saturating_sub(ACCESS_TOKEN_EXPIRY_BUFFER);

    write_cached_access_token(cache_key, token_response.access_token.clone(), expires_at);

    Ok(token_response.access_token)
}

fn read_cached_access_token(cache_key: &str) -> Option<String> {
    let cache = TOKEN_CACHE.lock().ok()?;
    let cached = cache.as_ref()?;
    if cached.cache_key == cache_key && cached.expires_at > Instant::now() {
        Some(cached.access_token.clone())
    } else {
        None
    }
}

fn write_cached_access_token(cache_key: String, access_token: String, expires_at: Instant) {
    if let Ok(mut cache) = TOKEN_CACHE.lock() {
        *cache = Some(CachedZohoAccessToken {
            cache_key,
            access_token,
            expires_at,
        });
    }
}

async fn upload_attachment(
    http_client: &reqwest::Client,
    config: &ZohoDeskConfig,
    access_token: &str,
    attachment: ValidatedAttachment,
) -> Result<String, ApiError> {
    let part = Part::bytes(attachment.bytes)
        .file_name(attachment.filename)
        .mime_str(&attachment.content_type)
        .map_err(|e| {
            tracing::error!(error = %e, "Failed to build Zoho upload part");
            ApiError::ServerErr("Failed to submit support ticket".to_string())
        })?;

    let form = Form::new().part("file", part);
    let response = http_client
        .post(format!("{}/api/v1/uploads", config.api_domain))
        .header("orgId", &config.org_id)
        .header("Authorization", format!("Zoho-oauthtoken {access_token}"))
        .multipart(form)
        .send()
        .await
        .map_err(|e| {
            tracing::error!(error = %e, "Failed to upload support ticket attachment to Zoho");
            ApiError::ServerErr("Failed to submit support ticket".to_string())
        })?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        tracing::error!(%status, body = %body, "Zoho attachment upload failed");
        return Err(ApiError::ServerErr(
            "Failed to submit support ticket".to_string(),
        ));
    }

    let upload_response = response.json::<ZohoUploadResponse>().await.map_err(|e| {
        tracing::error!(error = %e, "Failed to parse Zoho upload response");
        ApiError::ServerErr("Failed to submit support ticket".to_string())
    })?;

    Ok(upload_response.id)
}

async fn create_ticket(
    http_client: &reqwest::Client,
    config: &ZohoDeskConfig,
    access_token: &str,
    auth_user: &AuthenticatedUser,
    payload: ValidatedSupportTicket,
    upload_ids: Vec<String>,
) -> Result<ZohoCreateTicketResponse, ApiError> {
    let email = payload.email.clone();
    let contact = Some(ZohoTicketContact {
        last_name: payload.name.clone(),
        email: email.clone(),
    });

    let request = ZohoCreateTicketRequest {
        subject: payload.subject.clone(),
        department_id: config.department_id.clone(),
        description: build_ticket_description(&payload, auth_user),
        channel: "App".to_string(),
        status: "Open".to_string(),
        email,
        contact,
        uploads: upload_ids,
    };

    let response = http_client
        .post(format!("{}/api/v1/tickets", config.api_domain))
        .header("orgId", &config.org_id)
        .header("Authorization", format!("Zoho-oauthtoken {access_token}"))
        .json(&request)
        .send()
        .await
        .map_err(|e| {
            tracing::error!(error = %e, "Failed to create Zoho support ticket");
            ApiError::ServerErr("Failed to submit support ticket".to_string())
        })?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        tracing::error!(%status, body = %body, "Zoho ticket creation failed");
        return Err(ApiError::ServerErr(
            "Failed to submit support ticket".to_string(),
        ));
    }

    response
        .json::<ZohoCreateTicketResponse>()
        .await
        .map_err(|e| {
            tracing::error!(error = %e, "Failed to parse Zoho ticket response");
            ApiError::ServerErr("Failed to submit support ticket".to_string())
        })
}

fn build_ticket_description(
    payload: &ValidatedSupportTicket,
    auth_user: &AuthenticatedUser,
) -> String {
    let mut description = "<h3>Feedback</h3><p>".to_string();
    description.push_str(&escape_html(&payload.body).replace('\n', "<br />"));
    description.push_str("</p><hr /><h3>Context</h3><dl>");
    append_description_row(&mut description, "Source", "Noah mobile app");
    append_description_row(&mut description, "User key", &auth_user.key);

    append_description_row(&mut description, "Name", &payload.name);
    if let Some(email) = &payload.email {
        append_description_row(&mut description, "Email", email);
    }
    if let Some(device_info) = &payload.device_info {
        append_device_info(&mut description, device_info);
    }

    description.push_str("</dl>");
    description
}

fn append_device_info(description: &mut String, device_info: &DeviceInfo) {
    append_optional_description_row(
        description,
        "Device manufacturer",
        device_info.device_manufacturer.as_deref(),
    );
    append_optional_description_row(
        description,
        "Device model",
        device_info.device_model.as_deref(),
    );
    append_optional_description_row(description, "OS", device_info.os_name.as_deref());
    append_optional_description_row(description, "OS version", device_info.os_version.as_deref());
    append_optional_description_row(
        description,
        "App version",
        device_info.app_version.as_deref(),
    );
}

fn append_optional_description_row(description: &mut String, label: &str, value: Option<&str>) {
    if let Some(value) = value {
        append_description_row(description, label, value);
    }
}

fn append_description_row(description: &mut String, label: &str, value: &str) {
    description.push_str("<dt><strong>");
    description.push_str(&escape_html(label));
    description.push_str("</strong></dt><dd>");
    description.push_str(&escape_html(value));
    description.push_str("</dd>");
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

    fn payload_with_body(body: &str) -> SubmitSupportTicketPayload {
        SubmitSupportTicketPayload {
            subject: "Problem sending payment".to_string(),
            body: body.to_string(),
            name: "Nitesh".to_string(),
            email: None,
            attachment: None,
            device_info: None,
        }
    }

    #[test]
    fn validate_support_ticket_rejects_blank_subject() {
        let mut payload = payload_with_body("help");
        payload.subject = "   ".to_string();

        let result = validate_support_ticket_payload(payload);

        assert!(matches!(result, Err(ApiError::InvalidArgument(_))));
    }

    #[test]
    fn validate_support_ticket_rejects_long_subject() {
        let mut payload = payload_with_body("help");
        payload.subject = "a".repeat(MAX_SUBJECT_LEN + 1);

        let result = validate_support_ticket_payload(payload);

        assert!(matches!(result, Err(ApiError::InvalidArgument(_))));
    }

    #[test]
    fn validate_support_ticket_rejects_blank_body() {
        let result = validate_support_ticket_payload(payload_with_body("   "));

        assert!(matches!(result, Err(ApiError::InvalidArgument(_))));
    }

    #[test]
    fn validate_support_ticket_rejects_invalid_email() {
        let mut payload = payload_with_body("help");
        payload.email = Some("not-an-email".to_string());

        let result = validate_support_ticket_payload(payload);

        assert!(matches!(result, Err(ApiError::InvalidArgument(_))));
    }

    #[test]
    fn validate_support_ticket_rejects_blank_name() {
        let mut payload = payload_with_body("help");
        payload.name = "   ".to_string();

        let result = validate_support_ticket_payload(payload);

        assert!(matches!(result, Err(ApiError::InvalidArgument(_))));
    }

    #[test]
    fn validate_attachment_rejects_unsupported_content_type() {
        let result = validate_attachment(SupportTicketAttachment {
            filename: "screenshot.gif".to_string(),
            content_type: "image/gif".to_string(),
            base64_data: STANDARD.encode([1, 2, 3]),
        });

        assert!(matches!(result, Err(ApiError::InvalidArgument(_))));
    }

    #[test]
    fn validate_attachment_rejects_oversized_decoded_file() {
        let result = validate_attachment(SupportTicketAttachment {
            filename: "screenshot.png".to_string(),
            content_type: "image/png".to_string(),
            base64_data: STANDARD.encode(vec![0_u8; MAX_ATTACHMENT_BYTES + 1]),
        });

        assert!(matches!(result, Err(ApiError::InvalidArgument(_))));
    }

    #[test]
    fn validate_attachment_accepts_png_under_limit() {
        let result = validate_attachment(SupportTicketAttachment {
            filename: "screenshot.png".to_string(),
            content_type: "image/png".to_string(),
            base64_data: STANDARD.encode([1, 2, 3]),
        });

        assert!(result.is_ok());
    }

    #[test]
    fn build_ticket_description_formats_and_escapes_html() {
        let payload = ValidatedSupportTicket {
            subject: "Problem <sending> payment".to_string(),
            body: "line one\n<script>alert(1)</script>".to_string(),
            name: "Alice & Bob".to_string(),
            email: Some("alice@example.com".to_string()),
            attachment: None,
            device_info: Some(DeviceInfo {
                device_manufacturer: Some("Apple".to_string()),
                device_model: Some("Simulator".to_string()),
                os_name: Some("iOS".to_string()),
                os_version: Some("26.5".to_string()),
                app_version: Some("0.1.3".to_string()),
            }),
        };
        let auth_user = AuthenticatedUser {
            key: "pubkey".to_string(),
        };

        let description = build_ticket_description(&payload, &auth_user);

        assert!(description.contains("<h3>Feedback</h3>"));
        assert!(description.contains("line one<br />&lt;script&gt;alert(1)&lt;/script&gt;"));
        assert!(description.contains("Alice &amp; Bob"));
        assert!(description.contains("<strong>App version</strong>"));
    }
}
