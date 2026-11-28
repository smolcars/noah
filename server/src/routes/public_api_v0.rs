use std::collections::BTreeMap;
use std::time::Duration;
use std::time::SystemTime;

use axum::{
    Extension, Json,
    extract::{Path, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
};
use chrono::{TimeZone, Utc};
use expo_push_notification_client::Priority;
use lightning_invoice::Bolt11Invoice;
use rand::RngExt;
use uuid::Uuid;

use serde::{Deserialize, Serialize};
use tokio::time::sleep;
use validator::Validate;

use super::lnurl_pay_privacy::LnurlPaySensitiveQuery;
use crate::{
    AppState,
    auth::mint_access_token,
    cache::email_verification_store::EmailVerificationStore,
    db::{
        device_repo::DeviceRepository,
        fiat_rate_repo::FiatRateRepository,
        lnurl_pay_receive_metadata_repo::{CORRELATION_TTL, LnurlPayReceiveMetadataRepository},
        mailbox_authorization_repo::MailboxAuthorizationRepository,
        user_repo::UserRepository,
    },
    errors::ApiError,
    fiat_rates::{CoinGeckoFiatRateProvider, is_supported_currency},
    push::{PushNotificationData, has_push_token, send_push_notification},
    types::{
        AppVersionCheckPayload, AppVersionInfo, AuthEvent, AuthLoginPayload, AuthLoginResponse,
        AuthenticatedUser, EmailVerificationResponse, FiatPricesPayload, FiatPricesResponse,
        HistoricalFiatPricePayload, HistoricalFiatPriceResponse,
        LightningInvoiceRequestNotification, LnurlPayReceivePayerData, NotificationData,
        RegisterPayload, RegisterResponse, SendEmailVerificationPayload, UserStatus,
        VerifyEmailPayload, is_valid_ln_username,
    },
    utils::{make_k1, verify_auth},
    wide_event::WideEventHandle,
};

/// Represents the response for a `k1` request, used in LNURL-auth.
#[derive(Serialize, Deserialize, utoipa::ToSchema)]
pub struct GetK1 {
    /// A unique, single-use secret for the authentication process.
    pub k1: String,
    /// The LNURL-auth tag, which is always "login".
    pub tag: String,
}

const LNURLP_MIN_SENDABLE: u64 = 1000;
const LNURLP_MAX_SENDABLE: u64 = 1000000000;
const COMMENT_ALLOWED_SIZE: u16 = 280;
const MAX_PAYER_DATA_BYTES: usize = 2 * 1024;
const MAX_PAYER_NAME_CHARS: usize = 80;
const MAX_PAYER_IDENTIFIER_CHARS: usize = 320;
const POLL_INTERVAL: Duration = Duration::from_millis(500);
const TIMEOUT: Duration = Duration::from_secs(45);

#[derive(Deserialize, utoipa::ToSchema, utoipa::IntoParams)]
#[into_params(parameter_in = Query)]
pub struct Nip05RequestQuery {
    name: Option<String>,
}

#[derive(Debug, Deserialize, Serialize, utoipa::ToSchema)]
pub struct Nip05Response {
    pub names: BTreeMap<String, String>,
}

/// Resolves a hosted NIP-05 name to the linked Nostr public key.
#[utoipa::path(
    get, path = "/.well-known/nostr.json",
    description = "Public endpoint; no bearer token required.",
    params(Nip05RequestQuery),
    responses(
        (status = 200, description = "Success", body = Nip05Response),
        (status = 400, description = "Malformed query parameters", body = String, content_type = "text/plain"),
        (status = 429, description = "IP rate limit exceeded", body = String, content_type = "text/plain"),
        (status = 500, description = "Internal server error", body = crate::types::ApiErrorResponse),
    ),
)]
pub async fn nip05_request(
    State(state): State<AppState>,
    Query(query): Query<Nip05RequestQuery>,
) -> anyhow::Result<(HeaderMap, Json<Nip05Response>), ApiError> {
    let mut names = BTreeMap::new();

    if let Some(name) = query.name.filter(|name| is_valid_ln_username(name)) {
        let lightning_address = format!("{}@{}", name, state.lnurl_domain);
        let user_repo = UserRepository::new(&state.db_pool);

        if let Some(nostr_pubkey) = user_repo
            .find_active_nostr_pubkey_by_lightning_address(&lightning_address)
            .await?
        {
            names.insert(name, nostr_pubkey);
        }
    }

    let mut headers = HeaderMap::new();
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_ORIGIN,
        HeaderValue::from_static("*"),
    );
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("public, max-age=60"),
    );

    Ok((headers, Json(Nip05Response { names })))
}

/// Generates and returns a new `k1` value for an LNURL-auth flow.
///
/// The `k1` value is a random 32-byte hex-encoded string that is stored in Redis with
/// a strict TTL so it can be used once for a login or registration attempt.
#[utoipa::path(
    get, path = "/v0/getk1",
    description = "Public endpoint; no bearer token required.",
    responses(
        (status = 200, description = "Success", body = GetK1),
        (status = 429, description = "IP rate limit exceeded", body = String, content_type = "text/plain"),
        (status = 500, description = "Unable to create authentication challenge"),
    ),
)]
pub async fn get_k1(State(state): State<AppState>) -> anyhow::Result<Json<GetK1>, StatusCode> {
    let k1 = make_k1(&state.k1_cache).await.map_err(|e| {
        tracing::error!("Failed to create k1: {}", e);
        StatusCode::INTERNAL_SERVER_ERROR
    })?;

    Ok(Json(GetK1 {
        k1,
        tag: "login".to_string(),
    }))
}

#[utoipa::path(
    post, path = "/v0/prices",
    description = "Requires a bearer JWT and a registered user (user-exists middleware). Email verification is not required.",
    request_body = FiatPricesPayload,
    responses(
        (status = 200, description = "Success", body = FiatPricesResponse),
        (status = 400, description = "Malformed JSON", body = String, content_type = "text/plain"),
        (status = 413, description = "Request body too large", body = String, content_type = "text/plain"),
        (status = 415, description = "Expected application/json", body = String, content_type = "text/plain"),
        (status = 422, description = "JSON does not match the request payload", body = String, content_type = "text/plain"),
        (status = 401, description = "Missing, invalid or expired credentials; or user is not registered", body = crate::types::ApiErrorResponse),
        (status = 429, description = "IP rate limit exceeded", body = String, content_type = "text/plain"),
        (status = 500, description = "Internal server error", body = crate::types::ApiErrorResponse),
    ),
    security(("bearer" = [])),
)]
pub async fn fiat_prices(
    State(state): State<AppState>,
    Json(_payload): Json<FiatPricesPayload>,
) -> anyhow::Result<Json<FiatPricesResponse>, ApiError> {
    let repo = FiatRateRepository::new(&state.db_pool);
    let mut rates = repo.get_latest_rates().await?;

    if rates.is_empty() {
        tracing::warn!("No cached fiat rates found, fetching latest rates from provider");
        let provider = CoinGeckoFiatRateProvider::new(&state.config);
        let fetched = provider.fetch_latest_rates().await.map_err(|e| {
            tracing::error!(error = %e, "Failed to fetch latest fiat rates");
            ApiError::ServerErr("Failed to fetch fiat rates".to_string())
        })?;

        for rate in fetched {
            repo.upsert_rate(
                &rate.currency,
                rate.rate_date,
                rate.btc_price,
                rate.observed_at,
                &rate.source,
            )
            .await?;
        }

        rates = repo.get_latest_rates().await?;
    }

    let time = rates
        .iter()
        .map(|rate| rate.observed_at.timestamp())
        .max()
        .unwrap_or_else(|| Utc::now().timestamp());

    Ok(Json(FiatPricesResponse {
        time,
        rates: rates
            .into_iter()
            .map(|rate| (rate.currency, rate.btc_price))
            .collect(),
    }))
}

#[utoipa::path(
    post, path = "/v0/historical-price",
    description = "Requires a bearer JWT and a registered user (user-exists middleware). Email verification is not required.",
    request_body = HistoricalFiatPricePayload,
    responses(
        (status = 200, description = "Success", body = HistoricalFiatPriceResponse),
        (status = 400, description = "Invalid input (ApiError), or malformed JSON/query (plain text)", content((crate::types::ApiErrorResponse = "application/json"), (String = "text/plain"))),
        (status = 413, description = "Request body too large", body = String, content_type = "text/plain"),
        (status = 415, description = "Expected application/json", body = String, content_type = "text/plain"),
        (status = 422, description = "JSON does not match the request payload", body = String, content_type = "text/plain"),
        (status = 401, description = "Missing, invalid or expired credentials; or user is not registered", body = crate::types::ApiErrorResponse),
        (status = 429, description = "IP rate limit exceeded", body = String, content_type = "text/plain"),
        (status = 500, description = "Internal server error", body = crate::types::ApiErrorResponse),
    ),
    security(("bearer" = [])),
)]
pub async fn historical_fiat_price(
    State(state): State<AppState>,
    Json(payload): Json<HistoricalFiatPricePayload>,
) -> anyhow::Result<Json<HistoricalFiatPriceResponse>, ApiError> {
    let currency = payload.currency.to_ascii_uppercase();
    if !is_supported_currency(&currency) {
        return Err(ApiError::InvalidArgument(format!(
            "Unsupported fiat currency: {}",
            payload.currency
        )));
    }

    let rate_date = Utc
        .timestamp_opt(payload.timestamp, 0)
        .single()
        .ok_or_else(|| ApiError::InvalidArgument("Invalid timestamp".to_string()))?
        .date_naive();

    let repo = FiatRateRepository::new(&state.db_pool);
    let rate = match repo.get_rate(&currency, rate_date).await? {
        Some(rate) => rate,
        None => {
            tracing::debug!(
                currency = %currency,
                rate_date = %rate_date,
                "historical fiat rate cache miss"
            );
            let provider = CoinGeckoFiatRateProvider::new(&state.config);
            let fetched = provider
                .fetch_historical_rate(&currency, rate_date)
                .await
                .map_err(|e| {
                    tracing::error!(
                        currency = %currency,
                        rate_date = %rate_date,
                        error = %e,
                        "Failed to fetch historical fiat rate"
                    );
                    ApiError::ServerErr("Failed to fetch historical fiat rate".to_string())
                })?;

            repo.upsert_rate(
                &fetched.currency,
                fetched.rate_date,
                fetched.btc_price,
                fetched.observed_at,
                &fetched.source,
            )
            .await?;

            repo.get_rate(&currency, rate_date)
                .await?
                .ok_or_else(|| ApiError::ServerErr("Failed to store fiat rate".to_string()))?
        }
    };

    Ok(Json(HistoricalFiatPriceResponse {
        currency,
        time: payload.timestamp,
        rate: rate.btc_price,
    }))
}

#[utoipa::path(
    post, path = "/v0/auth/login",
    description = "Public endpoint; no bearer token required.",
    request_body = AuthLoginPayload,
    responses(
        (status = 200, description = "Success", body = AuthLoginResponse),
        (status = 400, description = "Invalid input (ApiError), or malformed JSON/query (plain text)", content((crate::types::ApiErrorResponse = "application/json"), (String = "text/plain"))),
        (status = 413, description = "Request body too large", body = String, content_type = "text/plain"),
        (status = 415, description = "Expected application/json", body = String, content_type = "text/plain"),
        (status = 422, description = "JSON does not match the request payload", body = String, content_type = "text/plain"),
        (status = 401, description = "Missing, invalid or expired credentials", body = crate::types::ApiErrorResponse),
        (status = 429, description = "IP rate limit exceeded", body = String, content_type = "text/plain"),
        (status = 500, description = "Internal server error", body = crate::types::ApiErrorResponse),
    ),
)]
pub async fn auth_login(
    State(state): State<AppState>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<AuthLoginPayload>,
) -> anyhow::Result<Json<AuthLoginResponse>, ApiError> {
    // Validate the issued challenge format before accessing Redis.
    let (challenge, timestamp) = payload
        .k1
        .split_once('_')
        .ok_or_else(|| ApiError::InvalidArgument("Invalid k1 format".to_string()))?;
    if challenge.len() != 64
        || !challenge
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
        || timestamp.is_empty()
        || !timestamp.bytes().all(|byte| byte.is_ascii_digit())
    {
        return Err(ApiError::InvalidArgument("Invalid k1 format".to_string()));
    }
    let timestamp = timestamp
        .parse::<u64>()
        .map_err(|_| ApiError::InvalidArgument("Invalid timestamp in k1".to_string()))?;

    let k1_consumed = state.k1_cache.take(&payload.k1).await.map_err(|e| {
        tracing::error!(error = %e, "Auth login failed: Unable to consume k1");
        ApiError::ServerErr("Failed to validate k1".to_string())
    })?;

    if !k1_consumed {
        return Err(ApiError::InvalidArgument("Invalid k1".to_string()));
    }

    let now = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap()
        .as_secs();

    if now.saturating_sub(timestamp) > 600 {
        return Err(ApiError::K1Expired);
    }

    let is_valid = verify_auth(payload.k1.clone(), payload.sig.clone(), payload.key.clone())
        .await
        .map_err(|_| ApiError::InvalidSignature)?;

    if !is_valid {
        return Err(ApiError::InvalidSignature);
    }

    let minted = mint_access_token(&state.config, &payload.key)
        .map_err(|_| ApiError::ServerErr("Failed to create access token".to_string()))?;

    if let Some(Extension(event)) = &event {
        event.set_user(&payload.key);
        event.add_context("expires_in_seconds", minted.expires_in_seconds);
    }

    Ok(Json(AuthLoginResponse {
        access_token: minted.token,
        token_type: "Bearer".to_string(),
        expires_at: minted.expires_at.to_rfc3339(),
        expires_in_seconds: minted.expires_in_seconds,
    }))
}

/// Represents the first response in the LNURL-pay protocol.
///
/// This response provides the necessary details for a wallet to make a payment,
/// such as the callback URL, sendable amounts, and metadata.
#[derive(Serialize, Deserialize, utoipa::ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LnurlpDefaultResponse {
    /// The URL where the wallet should send the second request.
    pub callback: String,
    /// The maximum amount that can be sent in a single payment, in millisatoshis.
    pub max_sendable: u64,
    /// The minimum amount that can be sent in a single payment, in millisatoshis.
    pub min_sendable: u64,
    /// A JSON string containing metadata about the payment.
    pub metadata: String,
    /// The LNURL-pay tag, which is always "payRequest".
    pub tag: String,
    /// The maximum length of a LUD-12 comment, in characters. Present only while the server can
    /// pass a comment on to the recipient.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub comment_allowed: Option<u16>,
    /// LUD-18 payer identity fields the callback accepts. Present only while the server can pass
    /// them on to the recipient.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub payer_data: Option<LnurlPayPayerDataSpec>,
    /// The user's Ark address when requested for a compatible Ark server.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ark: Option<String>,
    /// The rails the callback can pay through (lnurl/luds#303): always `lightning`, and `second`
    /// (type `bark`) while the user has an Ark address.
    pub payment_options: Vec<LnurlPayOption>,
}

/// One rail in `paymentOptions` (lnurl/luds#303).
#[derive(Serialize, Deserialize, utoipa::ToSchema)]
pub struct LnurlPayOption {
    /// The value a wallet sends to the callback as `paymentOption`.
    pub id: String,
    /// The payment method: `lightning` or `bark`.
    #[serde(rename = "type")]
    pub option_type: String,
    /// For `bark`, the Ark server's public key (compressed, lowercase hex), so a wallet can tell
    /// whether it can pay this option before calling back. Noah's extension, proposed on
    /// lnurl/luds#303.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub operator: Option<String>,
}

/// The `paymentOption` for a Lightning invoice, the default.
pub const PAYMENT_OPTION_LIGHTNING: &str = "lightning";
/// The `paymentOption` for an Ark payment to the user's address on Second's Ark server.
pub const PAYMENT_OPTION_SECOND: &str = "second";

/// The LUD-18 fields Noah accepts; neither is mandatory.
#[derive(Serialize, Deserialize, utoipa::ToSchema)]
pub struct LnurlPayPayerDataSpec {
    pub name: LnurlPayPayerDataFieldSpec,
    pub identifier: LnurlPayPayerDataFieldSpec,
}

#[derive(Serialize, Deserialize, utoipa::ToSchema)]
pub struct LnurlPayPayerDataFieldSpec {
    pub mandatory: bool,
}

/// Represents the second response in the LNURL-pay protocol.
///
/// This response contains the BOLT11 invoice that the wallet will use to pay.
#[derive(Serialize, Deserialize, utoipa::ToSchema)]
pub struct LnurlpInvoiceResponse {
    /// The BOLT11 payment request (invoice).
    pub pr: String,
    /// A list of routes for the payment, typically empty.
    pub routes: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ark: Option<String>,
    /// `lightning` when the wallet chose it as its `paymentOption` (lnurl/luds#303).
    #[serde(rename = "paymentOption", skip_serializing_if = "Option::is_none")]
    pub payment_option: Option<String>,
}

/// The callback response for a `paymentOption` paid without an invoice (lnurl/luds#303).
#[derive(Serialize, Deserialize, utoipa::ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LnurlpDestinationResponse {
    /// The `paymentOption` the wallet chose.
    pub payment_option: String,
    /// Where to pay: for `second`, the user's Ark address.
    pub payment_destination: String,
}

/// Schema for the query-dependent JSON returned by `lnurlp_request`.
#[derive(Serialize, Deserialize, utoipa::ToSchema)]
#[serde(untagged)]
#[cfg_attr(
    not(test),
    expect(dead_code, reason = "used only by the offline OpenAPI exporter")
)]
pub enum LnurlpResponse {
    Parameters(LnurlpDefaultResponse),
    Invoice(LnurlpInvoiceResponse),
    Destination(LnurlpDestinationResponse),
}

/// Defines the query parameters for an LNURL-pay request.
#[derive(Deserialize, utoipa::ToSchema, utoipa::IntoParams)]
#[into_params(parameter_in = Query)]
pub struct LnurlpRequestQuery {
    /// The amount of the payment in millisatoshis.
    amount: Option<u64>,
    /// The payer's Ark server pubkey, used to negotiate a compatible Ark address.
    ark: Option<String>,
    /// The `id` of the `paymentOptions` entry to pay through (lnurl/luds#303); `lightning` when
    /// absent.
    #[serde(rename = "paymentOption")]
    payment_option: Option<String>,
}

async fn negotiated_ark_address(
    state: &AppState,
    query: &LnurlpRequestQuery,
    ark_address: Option<&String>,
) -> Option<String> {
    let ark_address = ark_address?;

    if let Some(requested_server_pubkey) = query.ark.as_deref() {
        let configured_server_pubkey = state.ark_server_pubkey.read().await;
        let matches_configured_server = configured_server_pubkey
            .as_deref()
            .is_some_and(|pubkey| pubkey.eq_ignore_ascii_case(requested_server_pubkey));

        return matches_configured_server.then(|| ark_address.clone());
    }

    None
}

/// The rails discovery lists: Lightning always, and Ark while the user has an address, with the
/// Ark server's key as its `operator` once the server knows it.
async fn payment_options(state: &AppState, has_ark_address: bool) -> Vec<LnurlPayOption> {
    let mut options = vec![LnurlPayOption {
        id: PAYMENT_OPTION_LIGHTNING.to_string(),
        option_type: "lightning".to_string(),
        operator: None,
    }];
    if has_ark_address {
        options.push(LnurlPayOption {
            id: PAYMENT_OPTION_SECOND.to_string(),
            option_type: "bark".to_string(),
            operator: state
                .ark_server_pubkey
                .read()
                .await
                .as_deref()
                .map(str::to_lowercase),
        });
    }
    options
}

#[derive(Deserialize)]
struct LnurlPayPayerDataInput {
    name: Option<String>,
    identifier: Option<String>,
}

/// Payer data and comment from the callback, validated and normalized. An empty comment counts
/// as none: released Noah apps always send `comment=`.
fn validate_sensitive_lnurl_pay_query(
    sensitive: &LnurlPaySensitiveQuery,
) -> Result<(Option<LnurlPayReceivePayerData>, Option<String>), ApiError> {
    let payer_data = match sensitive.payer_data.as_deref() {
        Some(raw) => {
            if raw.len() > MAX_PAYER_DATA_BYTES {
                return Err(ApiError::InvalidArgument(
                    "LNURL-pay payerdata is too large".to_string(),
                ));
            }
            let input: LnurlPayPayerDataInput = serde_json::from_str(raw).map_err(|_| {
                ApiError::InvalidArgument("LNURL-pay payerdata must be valid JSON".to_string())
            })?;
            let name = input
                .name
                .map(|value| {
                    validate_payer_text("name", value.trim().to_string(), MAX_PAYER_NAME_CHARS)
                })
                .transpose()?;
            let identifier = input
                .identifier
                .map(|value| {
                    let value = validate_payer_text(
                        "identifier",
                        value.trim().to_lowercase(),
                        MAX_PAYER_IDENTIFIER_CHARS,
                    )?;
                    normalize_payer_identifier(value)
                })
                .transpose()?;
            (name.is_some() || identifier.is_some())
                .then_some(LnurlPayReceivePayerData { name, identifier })
        }
        None => None,
    };

    let comment = match sensitive.comment.as_deref() {
        None | Some("") => None,
        Some(value) => Some(validate_payer_text(
            "comment",
            value.to_string(),
            COMMENT_ALLOWED_SIZE as usize,
        )?),
    };
    Ok((payer_data, comment))
}

fn normalize_payer_identifier(value: String) -> Result<String, ApiError> {
    let invalid_identifier = || {
        ApiError::InvalidArgument(
            "LNURL-pay payer identifier must be a valid Lightning Address".to_string(),
        )
    };
    if !crate::types::is_valid_lightning_address(&value) {
        return Err(invalid_identifier());
    }

    let (username, domain) = value.split_once('@').ok_or_else(invalid_identifier)?;
    let url::Host::Domain(domain) = url::Host::parse(domain).map_err(|_| invalid_identifier())?
    else {
        return Err(invalid_identifier());
    };
    if !is_valid_dns_name(&domain) {
        return Err(invalid_identifier());
    }

    let normalized = format!("{username}@{domain}");
    if normalized.len() > MAX_PAYER_IDENTIFIER_CHARS {
        return Err(invalid_identifier());
    }
    Ok(normalized)
}

fn is_valid_dns_name(domain: &str) -> bool {
    domain.len() <= 253
        && domain.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label
                    .bytes()
                    .all(|character| character.is_ascii_alphanumeric() || character == b'-')
                && label
                    .as_bytes()
                    .first()
                    .is_some_and(u8::is_ascii_alphanumeric)
                && label
                    .as_bytes()
                    .last()
                    .is_some_and(u8::is_ascii_alphanumeric)
        })
}

fn validate_payer_text(
    field_name: &str,
    value: String,
    max_chars: usize,
) -> Result<String, ApiError> {
    let length = value.chars().count();
    if length == 0 || length > max_chars {
        return Err(ApiError::InvalidArgument(format!(
            "LNURL-pay payer {field_name} has an invalid length"
        )));
    }
    if value.chars().any(is_unsafe_payer_character) {
        return Err(ApiError::InvalidArgument(format!(
            "LNURL-pay payer {field_name} contains unsupported characters"
        )));
    }
    Ok(value)
}

/// Control characters, and the bidirectional controls that can make a name or comment display
/// as something it isn't.
fn is_unsafe_payer_character(character: char) -> bool {
    character.is_control()
        || matches!(
            character as u32,
            0x061c | 0x200e | 0x200f | 0x2028..=0x202e | 0x2066..=0x2069
        )
}

/// The repository to store this recipient's payer data in, when the server can match a payment to
/// it: barkd forwards the payment to the recipient's Ark address, and their mailbox authorization
/// covers the correlation window so the mailbox worker sees the delivery.
async fn receive_metadata_repo<'a>(
    state: &'a AppState,
    pubkey: &str,
    ark_address: Option<&str>,
) -> Result<Option<&'a LnurlPayReceiveMetadataRepository>, ApiError> {
    let Some(repo) = state.lnurl_pay_receive_metadata_repo.as_ref() else {
        return Ok(None);
    };
    if !state.config.lnurl_pay_receive_metadata_enabled
        || state.barkd_invoice_provider.is_none()
        || ark_address.is_none()
    {
        return Ok(None);
    }
    let covered_until = Utc::now() + CORRELATION_TTL;
    let mailbox_covered = MailboxAuthorizationRepository::new(&state.db_pool)
        .has_active_authorization(pubkey, covered_until.timestamp())
        .await?;
    Ok(mailbox_covered.then_some(repo))
}

async fn discard_reservation(repo: &LnurlPayReceiveMetadataRepository, id: Uuid) {
    if let Err(error) = repo.delete_reservation(id).await {
        // Cleanup also removes expired reservations, so a failed delete is bounded.
        tracing::warn!(reservation_id = %id, error = %error, "failed to discard an unused LNURL-pay receive reservation");
    }
}

/// Handles LNURL-pay requests.
///
/// This endpoint manages the two-step LNURL-pay flow. The first request (without an amount)
/// returns payment parameters. The second request (with an amount) returns the user's Ark address
/// for `paymentOption=second`, or a direct Ark response when negotiated with `ark=`. Otherwise it
/// asks barkd for a forwarding invoice when configured and falls back to waking the user's device.
#[utoipa::path(
    get, path = "/.well-known/lnurlp/{username}",
    description = "Public endpoint; no bearer token required. Without amount, returns payment parameters; with amount, returns an invoice (pr is empty for negotiated direct Ark payment), or the Ark address as paymentDestination for paymentOption=second.",
    params(("username" = String, Path, description = "Hosted Lightning username"), LnurlpRequestQuery),
    responses(
        (status = 200, description = "Success", body = LnurlpResponse),
        (status = 400, description = "Invalid input (ApiError), or malformed JSON/query (plain text)", content((crate::types::ApiErrorResponse = "application/json"), (String = "text/plain"))),
        (status = 429, description = "IP rate limit exceeded", body = String, content_type = "text/plain"),
        (status = 500, description = "Internal server error", body = crate::types::ApiErrorResponse),
    ),
)]
pub async fn lnurlp_request(
    State(state): State<AppState>,
    Path(username): Path<String>,
    Query(query): Query<LnurlpRequestQuery>,
    sensitive: Option<Extension<LnurlPaySensitiveQuery>>,
    event: Option<Extension<WideEventHandle>>,
) -> anyhow::Result<axum::response::Json<serde_json::Value>, ApiError> {
    let lnurl_domain = &state.lnurl_domain;
    let lightning_address = format!("{}@{}", username, lnurl_domain);

    if let Some(Extension(event)) = &event {
        event.add_context("ln_address", &lightning_address);
        if let Some(amount) = query.amount {
            event.add_context("amount_msats", amount);
        }
    }

    let user_repo = UserRepository::new(&state.db_pool);
    let user = user_repo
        .find_by_lightning_address(&lightning_address)
        .await?
        .ok_or_else(|| ApiError::InvalidArgument("User not found".to_string()))?;
    let pubkey = user.pubkey.clone();

    if user.status != UserStatus::Active {
        tracing::warn!(
            pubkey = %pubkey,
            user_status = %user.status,
            "Lightning LNURL request rejected because user is not active"
        );
        return Err(ApiError::InvalidArgument(
            "Lightning payments are not available for this user right now.".to_string(),
        ));
    }

    if query.amount.is_none() {
        let metadata = serde_json::json!([
            ["text/identifier", lightning_address],
            [
                "text/plain",
                format!("Paying satoshis to {}", lightning_address)
            ]
        ])
        .to_string();

        // Advertise payer data and comments only while the callback could pass them on.
        let receive_metadata_available = state.config.lnurl_pay_receive_metadata_advertised
            && match receive_metadata_repo(&state, &pubkey, user.ark_address.as_deref()).await? {
                Some(repo) => repo.has_capacity(&pubkey).await?,
                None => false,
            };
        let response = LnurlpDefaultResponse {
            callback: format!("https://{}/.well-known/lnurlp/{}", lnurl_domain, username),
            min_sendable: LNURLP_MIN_SENDABLE,
            max_sendable: LNURLP_MAX_SENDABLE,
            metadata,
            tag: "payRequest".to_string(),
            comment_allowed: receive_metadata_available.then_some(COMMENT_ALLOWED_SIZE),
            payer_data: receive_metadata_available.then_some(LnurlPayPayerDataSpec {
                name: LnurlPayPayerDataFieldSpec { mandatory: false },
                identifier: LnurlPayPayerDataFieldSpec { mandatory: false },
            }),
            ark: negotiated_ark_address(&state, &query, user.ark_address.as_ref()).await,
            payment_options: payment_options(&state, user.ark_address.is_some()).await,
        };
        return Ok(Json(
            serde_json::to_value(response).map_err(|e| ApiError::SerializeErr(e.to_string()))?,
        ));
    }

    let amount = query.amount.unwrap();

    if amount < LNURLP_MIN_SENDABLE {
        return Err(ApiError::InvalidArgument(format!(
            "Minimum invoice request is {} mSats",
            LNURLP_MIN_SENDABLE
        )));
    }

    if amount > LNURLP_MAX_SENDABLE {
        return Err(ApiError::InvalidArgument(format!(
            "Maximum invoice request is {} mSats",
            LNURLP_MAX_SENDABLE
        )));
    }

    // Payer data and a comment reach the recipient only through a barkd-forwarded payment the
    // mailbox worker can match. Refuse them anywhere else rather than drop them unseen.
    let sensitive = sensitive.map(|Extension(value)| value).unwrap_or_default();
    let metadata_submitted = sensitive.payer_data.is_some()
        || sensitive
            .comment
            .as_deref()
            .is_some_and(|comment| !comment.is_empty());
    let metadata_unavailable = || {
        ApiError::InvalidArgument(
            "Payer data and comments are not available for this recipient right now".to_string(),
        )
    };

    let unsupported_option = || ApiError::InvalidArgument("Unsupported paymentOption".to_string());
    match query.payment_option.as_deref() {
        None | Some(PAYMENT_OPTION_LIGHTNING) => {}
        Some(PAYMENT_OPTION_SECOND) => {
            let Some(ark_address) = user.ark_address.clone() else {
                return Err(unsupported_option());
            };
            if amount % 1000 != 0 {
                return Err(ApiError::InvalidArgument(
                    "Amount must be a whole number of satoshis".to_string(),
                ));
            }
            // Checked like any callback's, then dropped: nothing ties an Ark payment to this
            // request yet, and a note isn't worth failing the payment over.
            validate_sensitive_lnurl_pay_query(&sensitive)?;
            let response = LnurlpDestinationResponse {
                payment_option: PAYMENT_OPTION_SECOND.to_string(),
                payment_destination: ark_address,
            };
            return Ok(Json(
                serde_json::to_value(response)
                    .map_err(|e| ApiError::SerializeErr(e.to_string()))?,
            ));
        }
        Some(_) => return Err(unsupported_option()),
    }
    // The wallet's `paymentOption` sets the rail; the older `ark=` negotiation applies only
    // without one.
    let payment_option = query.payment_option.clone();
    let legacy_ark_address = match payment_option {
        Some(_) => None,
        None => negotiated_ark_address(&state, &query, user.ark_address.as_ref()).await,
    };

    if let Some(ark_address) = legacy_ark_address {
        if metadata_submitted {
            return Err(metadata_unavailable());
        }
        let response = LnurlpInvoiceResponse {
            pr: "".to_string(),
            routes: vec![],
            ark: Some(ark_address),
            payment_option: None,
        };
        return Ok(Json(
            serde_json::to_value(response).map_err(|e| ApiError::SerializeErr(e.to_string()))?,
        ));
    }

    let metadata_repo = receive_metadata_repo(&state, &pubkey, user.ark_address.as_deref()).await?;
    let (payer_data, comment) = match metadata_repo {
        Some(_) => validate_sensitive_lnurl_pay_query(&sensitive)?,
        None => (None, None),
    };
    if metadata_repo.is_none() && metadata_submitted {
        return Err(metadata_unavailable());
    }

    if let (Some(provider), Some(ark_address)) =
        (&state.barkd_invoice_provider, user.ark_address.as_deref())
    {
        if amount % 1000 != 0 {
            return Err(ApiError::InvalidArgument(
                "Invoice amount must be a whole number of satoshis".to_string(),
            ));
        }
        let amount_sat = amount / 1000;
        let description = format!("Paying satoshis to {lightning_address}");

        // Reserve the record before barkd issues the invoice, so a full quota never strands an
        // invoice. Every matched payment gets a record, with or without payer data, so the app can
        // show it as a Lightning address receive rather than a plain Ark one.
        let reservation = match metadata_repo {
            Some(repo) => match repo
                .reserve(&pubkey, amount, payer_data, comment, ark_address)
                .await?
            {
                Some(id) => Some((repo, id)),
                None if metadata_submitted => return Err(metadata_unavailable()),
                None => None,
            },
            None => None,
        };

        match provider
            .create_invoice_for_address(ark_address, amount_sat, Some(&description))
            .await
        {
            Ok(invoice) => {
                if let Some((repo, id)) = reservation {
                    // barkd_client already checked that it parses.
                    let finalized = match invoice.invoice.parse::<Bolt11Invoice>() {
                        Ok(parsed) => {
                            let expires_at = chrono::DateTime::<Utc>::from(
                                SystemTime::UNIX_EPOCH
                                    + parsed.duration_since_epoch()
                                    + parsed.expiry_time(),
                            );
                            repo.finalize(
                                id,
                                &pubkey,
                                &invoice.invoice,
                                &parsed.payment_hash().to_string(),
                                expires_at,
                            )
                            .await
                        }
                        Err(_) => Err(anyhow::anyhow!("barkd returned an invalid invoice")),
                    };
                    if let Err(error) = finalized {
                        discard_reservation(repo, id).await;
                        tracing::error!(error = %error, "Failed to store LNURL-pay receive metadata");
                        // Never hand out an invoice whose payer data can't be kept: the payer
                        // retries and barkd's unpaid invoice expires. Without payer data the
                        // payment just goes unlabeled.
                        if metadata_submitted {
                            return Err(ApiError::ServerErr(
                                "Failed to prepare payment request".to_string(),
                            ));
                        }
                    }
                }
                tracing::info!(
                    pubkey = %pubkey,
                    amount_msats = amount,
                    "Created forwarded Lightning invoice with barkd"
                );
                if let Some(Extension(event)) = &event {
                    event.add_context("invoice_source", "barkd");
                }
                let response = LnurlpInvoiceResponse {
                    pr: invoice.invoice,
                    routes: vec![],
                    ark: user.ark_address,
                    payment_option,
                };
                return Ok(Json(
                    serde_json::to_value(response)
                        .map_err(|e| ApiError::SerializeErr(e.to_string()))?,
                ));
            }
            Err(error) => {
                // The device invoice carries no payer data: that path can't match it to a
                // payment yet (docs/lnurl_pay.md).
                if let Some((repo, id)) = reservation {
                    discard_reservation(repo, id).await;
                }
                tracing::warn!(
                    pubkey = %pubkey,
                    amount_msats = amount,
                    error = %error,
                    "Failed to create forwarded Lightning invoice with barkd; falling back to device"
                );
                if let Some(Extension(event)) = &event {
                    event.add_context("barkd_fallback", true);
                }
            }
        }
    }

    if !has_push_token(&state, &pubkey).await? {
        tracing::warn!(
            pubkey = %pubkey,
            "Lightning LNURL invoice request rejected because user has no push token"
        );
        return Err(ApiError::InvalidArgument(
            "Lightning payments are not supported on this device right now.".to_string(),
        ));
    }

    let mailbox_repo = MailboxAuthorizationRepository::new(&state.db_pool);
    if !mailbox_repo
        .has_active_authorization(&pubkey, chrono::Utc::now().timestamp())
        .await?
    {
        tracing::warn!(
            pubkey = %pubkey,
            "Lightning LNURL invoice request rejected because user has no active mailbox authorization"
        );
        return Err(ApiError::InvalidArgument(
            "Lightning payments require mailbox notifications to be enabled.".to_string(),
        ));
    }

    // Generate a unique transaction ID for this payment request
    let transaction_id = Uuid::new_v4().to_string();

    if let Some(Extension(event)) = &event {
        event.add_context("transaction_id", &transaction_id);
        event.add_context("has_ark_address", user.ark_address.is_some());
    }

    let state_clone = state.clone();
    let transaction_id_clone = transaction_id.clone();
    tokio::spawn(async move {
        let data = PushNotificationData {
            title: None,
            body: None,
            data: serde_json::to_string(&NotificationData::LightningInvoiceRequest(
                LightningInvoiceRequestNotification {
                    transaction_id: transaction_id_clone,
                    amount,
                },
            ))
            .unwrap(),
            priority: Priority::High,
            content_available: true,
        };
        if let Err(e) = send_push_notification(state_clone, data, Some(pubkey)).await {
            tracing::error!("Failed to send push notification: {}", e);
        }
    });

    tracing::debug!("Polling for invoice with a 45s timeout...");

    let start = std::time::Instant::now();

    let invoice = loop {
        match state.invoice_store.get(&user.pubkey, &transaction_id).await {
            Ok(Some(inv)) => {
                // Clean up after successful retrieval
                if let Err(e) = state
                    .invoice_store
                    .remove(&user.pubkey, &transaction_id)
                    .await
                {
                    tracing::warn!(
                        "Failed to remove invoice for transaction_id {}: {}",
                        transaction_id,
                        e
                    );
                }

                break inv;
            }
            Ok(None) => {
                if start.elapsed() >= TIMEOUT {
                    tracing::error!(
                        "Invoice request timed out after 45s for transaction_id: {}",
                        transaction_id
                    );
                    return Err(ApiError::ServerErr("Request timed out".to_string()));
                }
                sleep(POLL_INTERVAL).await;
            }
            Err(e) => {
                tracing::error!("Failed to poll invoice from Redis: {}", e);
                return Err(ApiError::ServerErr(
                    "Failed to retrieve invoice".to_string(),
                ));
            }
        }
    };

    // The payee's device made this invoice, so check it before the payer sees it.
    // The apps truncate msat to whole sats.
    let network = state.config.network()?;
    let expected_msat = amount / 1000 * 1000;
    if !invoice.parse::<Bolt11Invoice>().is_ok_and(|parsed| {
        parsed.network() == network && parsed.amount_milli_satoshis() == Some(expected_msat)
    }) {
        tracing::warn!(
            pubkey = %user.pubkey,
            amount_msats = amount,
            "Device invoice rejected: not a valid invoice for the requested amount and network"
        );
        return Err(ApiError::ServerErr(
            "Failed to retrieve invoice".to_string(),
        ));
    }

    let response = LnurlpInvoiceResponse {
        pr: invoice,
        routes: vec![],
        ark: user.ark_address,
        payment_option,
    };
    Ok(Json(
        serde_json::to_value(response).map_err(|e| ApiError::SerializeErr(e.to_string()))?,
    ))
}

/// Handles user registration via LNURL-auth.
///
/// This endpoint receives a user's public key, a signature, and a `k1` value.
/// It verifies the signature against the `k1` value and, if valid, registers
/// the user in the database.
#[utoipa::path(
    post, path = "/v0/register",
    description = "Requires a bearer JWT. Registration is optional. Registers the authenticated public key or updates an existing registration.",
    request_body = RegisterPayload,
    responses(
        (status = 200, description = "Success", body = RegisterResponse),
        (status = 400, description = "Invalid input (ApiError), or malformed JSON/query (plain text)", content((crate::types::ApiErrorResponse = "application/json"), (String = "text/plain"))),
        (status = 413, description = "Request body too large", body = String, content_type = "text/plain"),
        (status = 415, description = "Expected application/json", body = String, content_type = "text/plain"),
        (status = 422, description = "JSON does not match the request payload", body = String, content_type = "text/plain"),
        (status = 401, description = "Missing, invalid or expired credentials", body = crate::types::ApiErrorResponse),
        (status = 429, description = "IP rate limit exceeded", body = String, content_type = "text/plain"),
        (status = 500, description = "Internal server error", body = crate::types::ApiErrorResponse),
    ),
    security(("bearer" = [])),
)]
pub async fn register(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<RegisterPayload>,
) -> anyhow::Result<Json<RegisterResponse>, ApiError> {
    if payload.ln_address.is_some()
        && payload.validate().is_err()
        && let Err(e) = payload.validate()
    {
        return Err(ApiError::InvalidArgument(e.to_string()));
    }

    let user_repo = UserRepository::new(&state.db_pool);

    if let Some(user) = user_repo.find_by_pubkey(&auth_payload.key).await? {
        if let Some(Extension(event)) = &event {
            event.add_context("is_new_user", false);
            event.set_ln_address(user.lightning_address.as_deref().unwrap_or(""));
        }

        if let Some(ark_address) = &payload.ark_address
            && let Err(e) = user_repo
                .update_ark_address(&auth_payload.key, ark_address)
                .await
        {
            if e.is::<crate::db::user_repo::DuplicateArkAddressError>() {
                // If address is taken, we can either return error or just ignore and keep old one.
                // Returning error is safer to let client know.
                return Err(ApiError::InvalidArgument(
                    "Ark address already taken".to_string(),
                ));
            }
            return Err(e.into());
        }

        if let Some(device_info) = payload.device_info {
            // For existing users, we'll just register the device in its own transaction
            let mut tx = state.db_pool.begin().await?;
            DeviceRepository::upsert(&mut tx, &auth_payload.key, &device_info).await?;
            tx.commit().await?;
        }

        return Ok(Json(RegisterResponse {
            status: "OK".to_string(),
            event: None,
            reason: Some("User already registered".to_string()),
            lightning_address: user.lightning_address,
            nostr_pubkey: user.nostr_pubkey,
            display_name: user.display_name,
            email: user.email,
            is_email_verified: user.is_email_verified,
            user_status: user.status,
        }));
    }

    let ln_address = payload.ln_address.unwrap_or_else(|| {
        let number = rand::rng().random_range(0..100);
        let random_word = random_word::get(random_word::Lang::En);
        format!("{}{}@{}", random_word, number, state.lnurl_domain)
    });

    if let Some(Extension(event)) = &event {
        event.add_context("is_new_user", true);
        event.set_ln_address(&ln_address);
        event.add_context("has_ark_address", payload.ark_address.is_some());
        event.add_context("has_device_info", payload.device_info.is_some());
    }

    if !crate::types::is_valid_lightning_address(&ln_address) {
        return Err(ApiError::InvalidArgument(
            "Invalid lightning address".to_string(),
        ));
    }

    // Create a new user in a transaction
    let mut tx = state.db_pool.begin().await?;
    let result = UserRepository::create(
        &mut tx,
        &auth_payload.key,
        &ln_address,
        payload.ark_address.as_deref(),
    )
    .await;

    if let Err(e) = result {
        if e.is::<crate::db::user_repo::LightningAddressTakenError>() {
            return Err(ApiError::InvalidArgument(
                "Lightning address already taken".to_string(),
            ));
        }
        if e.is::<crate::db::user_repo::DuplicateArkAddressError>() {
            return Err(ApiError::InvalidArgument(
                "Ark address already taken".to_string(),
            ));
        }
        return Err(e.into());
    }

    if let Some(device_info) = payload.device_info {
        DeviceRepository::upsert(&mut tx, &auth_payload.key, &device_info).await?;
    }

    tx.commit().await?;

    Ok(Json(RegisterResponse {
        status: "OK".to_string(),
        event: Some(AuthEvent::Registered),
        reason: None,
        lightning_address: Some(ln_address),
        nostr_pubkey: None,
        display_name: None,
        email: None,
        is_email_verified: false,
        user_status: UserStatus::Active,
    }))
}

#[utoipa::path(
    post, path = "/v0/app_version",
    description = "Public endpoint; no bearer token required.",
    request_body = AppVersionCheckPayload,
    responses(
        (status = 200, description = "Success", body = AppVersionInfo),
        (status = 400, description = "Invalid input (ApiError), or malformed JSON/query (plain text)", content((crate::types::ApiErrorResponse = "application/json"), (String = "text/plain"))),
        (status = 413, description = "Request body too large", body = String, content_type = "text/plain"),
        (status = 415, description = "Expected application/json", body = String, content_type = "text/plain"),
        (status = 422, description = "JSON does not match the request payload", body = String, content_type = "text/plain"),
        (status = 500, description = "Internal server error", body = crate::types::ApiErrorResponse),
    ),
)]
pub async fn check_app_version(
    State(state): State<AppState>,
    Json(payload): Json<AppVersionCheckPayload>,
) -> anyhow::Result<Json<AppVersionInfo>, ApiError> {
    let minimum_required = &state.config.minimum_app_version;
    let client_version = &payload.client_version;

    let minimum_parsed = semver::Version::parse(minimum_required).map_err(|e| {
        tracing::error!("Failed to parse minimum_app_version from config: {}", e);
        ApiError::ServerErr("Invalid server configuration".to_string())
    })?;

    let client_parsed = semver::Version::parse(client_version).map_err(|_| {
        ApiError::InvalidArgument(format!("Invalid client version format: {}", client_version))
    })?;

    let update_required = client_parsed < minimum_parsed;

    tracing::debug!(
        "Version check: client={}, minimum={}, update_required={}",
        client_version,
        minimum_required,
        update_required
    );

    Ok(Json(AppVersionInfo {
        minimum_required_version: minimum_required.clone(),
        update_required,
    }))
}

/// Sends an email verification code to the user's email address.
#[utoipa::path(
    post, path = "/v0/email/send_verification",
    description = "Requires a bearer JWT and a registered user (user-exists middleware). Email verification is not required.",
    request_body = SendEmailVerificationPayload,
    responses(
        (status = 200, description = "Success", body = EmailVerificationResponse),
        (status = 400, description = "Invalid input (ApiError), or malformed JSON/query (plain text)", content((crate::types::ApiErrorResponse = "application/json"), (String = "text/plain"))),
        (status = 413, description = "Request body too large", body = String, content_type = "text/plain"),
        (status = 415, description = "Expected application/json", body = String, content_type = "text/plain"),
        (status = 422, description = "JSON does not match the request payload", body = String, content_type = "text/plain"),
        (status = 401, description = "Missing, invalid or expired credentials; or user is not registered", body = crate::types::ApiErrorResponse),
        (status = 429, description = "IP rate limit exceeded", body = String, content_type = "text/plain"),
        (status = 500, description = "Internal server error", body = crate::types::ApiErrorResponse),
    ),
    security(("bearer" = [])),
)]
pub async fn send_verification_email(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<SendEmailVerificationPayload>,
) -> anyhow::Result<Json<EmailVerificationResponse>, ApiError> {
    if let Some(Extension(event)) = &event {
        let domain = payload.email.split('@').nth(1).unwrap_or("unknown");
        event.add_context("email_domain", domain);
    }
    if let Err(e) = payload.validate() {
        return Err(ApiError::InvalidArgument(e.to_string()));
    }

    let user_repo = UserRepository::new(&state.db_pool);
    let user = user_repo
        .find_by_pubkey(&auth_payload.key)
        .await?
        .ok_or_else(|| ApiError::UserNotFound)?;

    let requested_email = payload.email.trim();
    if user.is_email_verified
        && user
            .email
            .as_deref()
            .is_some_and(|email| email.eq_ignore_ascii_case(requested_email))
    {
        return Ok(Json(EmailVerificationResponse {
            success: true,
            message: Some("Email already verified".to_string()),
            email: user.email,
        }));
    }

    let code = EmailVerificationStore::generate_code();

    state
        .email_verification_store
        .store(&auth_payload.key, requested_email, &code)
        .await
        .map_err(|e| {
            tracing::error!("Failed to store verification code: {}", e);
            ApiError::ServerErr("Failed to store verification code".to_string())
        })?;

    state
        .email_client
        .send_verification_email(requested_email, &code)
        .await
        .map_err(|e| {
            tracing::error!("Failed to send verification email: {}", e);
            ApiError::ServerErr("Failed to send verification email".to_string())
        })?;

    tracing::info!(
        "Verification email sent to {} for user {}",
        requested_email,
        auth_payload.key
    );

    Ok(Json(EmailVerificationResponse {
        success: true,
        message: Some("Verification code sent".to_string()),
        email: None,
    }))
}

/// Verifies the email verification code.
#[utoipa::path(
    post, path = "/v0/email/verify",
    description = "Requires a bearer JWT and a registered user (user-exists middleware). Email verification is not required.",
    request_body = VerifyEmailPayload,
    responses(
        (status = 200, description = "Success", body = EmailVerificationResponse),
        (status = 400, description = "Invalid input (ApiError), or malformed JSON/query (plain text)", content((crate::types::ApiErrorResponse = "application/json"), (String = "text/plain"))),
        (status = 413, description = "Request body too large", body = String, content_type = "text/plain"),
        (status = 415, description = "Expected application/json", body = String, content_type = "text/plain"),
        (status = 422, description = "JSON does not match the request payload", body = String, content_type = "text/plain"),
        (status = 401, description = "Missing, invalid or expired credentials; or user is not registered", body = crate::types::ApiErrorResponse),
        (status = 429, description = "IP rate limit exceeded", body = String, content_type = "text/plain"),
        (status = 500, description = "Internal server error", body = crate::types::ApiErrorResponse),
    ),
    security(("bearer" = [])),
)]
pub async fn verify_email(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<VerifyEmailPayload>,
) -> anyhow::Result<Json<EmailVerificationResponse>, ApiError> {
    let user_repo = UserRepository::new(&state.db_pool);

    user_repo
        .find_by_pubkey(&auth_payload.key)
        .await?
        .ok_or_else(|| ApiError::UserNotFound)?;

    let email = state
        .email_verification_store
        .verify(
            &auth_payload.key,
            &payload.code,
            state.config.email_dev_mode,
        )
        .await
        .map_err(|e| {
            tracing::error!("Failed to verify code: {}", e);
            ApiError::ServerErr("Failed to verify code".to_string())
        })?;

    match email {
        Some(verified_email) => {
            if let Some(Extension(event)) = &event {
                event.add_context("verification_result", "success");
                let domain = verified_email.split('@').nth(1).unwrap_or("unknown");
                event.add_context("email_domain", domain);
            }
            user_repo
                .update_email(&auth_payload.key, &verified_email)
                .await?;
            user_repo.set_email_verified(&auth_payload.key).await?;

            tracing::info!(
                "Email {} verified for user {}",
                verified_email,
                auth_payload.key
            );

            Ok(Json(EmailVerificationResponse {
                success: true,
                message: Some("Email verified successfully".to_string()),
                email: Some(verified_email),
            }))
        }
        None => {
            if let Some(Extension(event)) = &event {
                event.add_context("verification_result", "invalid_code");
            }
            Err(ApiError::InvalidArgument(
                "Invalid or expired verification code".to_string(),
            ))
        }
    }
}

#[cfg(test)]
mod lnurl_pay_tests {
    use super::*;

    fn sensitive(payer_data: Option<&str>, comment: Option<&str>) -> LnurlPaySensitiveQuery {
        LnurlPaySensitiveQuery {
            payer_data: payer_data.map(str::to_string),
            comment: comment.map(str::to_string),
        }
    }

    #[test]
    fn accepts_direct_lud18_strings_and_normalizes_identity() {
        let (payer_data, comment) = validate_sensitive_lnurl_pay_query(&sensitive(
            Some(r#"{"name":" Alice ","identifier":" ALICE@Example.COM ","ignored":true}"#),
            Some("Hello"),
        ))
        .unwrap();
        let payer_data = payer_data.unwrap();
        assert_eq!(payer_data.name.as_deref(), Some("Alice"));
        assert_eq!(payer_data.identifier.as_deref(), Some("alice@example.com"));
        assert_eq!(comment.as_deref(), Some("Hello"));
    }

    #[test]
    fn an_empty_comment_counts_as_none() {
        assert_eq!(
            validate_sensitive_lnurl_pay_query(&sensitive(None, Some(""))).unwrap(),
            (None, None)
        );
    }

    #[test]
    fn rejects_nested_or_unsafe_payer_data() {
        assert!(
            validate_sensitive_lnurl_pay_query(&sensitive(
                Some(r#"{"name":{"value":"Alice"}}"#),
                None
            ))
            .is_err()
        );
        assert!(
            validate_sensitive_lnurl_pay_query(&sensitive(
                Some("{\"name\":\"Alice\\u202e\"}"),
                None
            ))
            .is_err()
        );
        for separator in ['\u{2028}', '\u{2029}'] {
            let comment = format!("Hello{separator}world");
            assert!(validate_sensitive_lnurl_pay_query(&sensitive(None, Some(&comment))).is_err());
        }
    }

    #[test]
    fn enforces_length_limits_in_characters() {
        let name = format!(r#"{{"name":"{}"}}"#, "😀".repeat(MAX_PAYER_NAME_CHARS));
        assert!(validate_sensitive_lnurl_pay_query(&sensitive(Some(&name), None)).is_ok());
        let name = format!(r#"{{"name":"{}"}}"#, "a".repeat(MAX_PAYER_NAME_CHARS + 1));
        assert!(validate_sensitive_lnurl_pay_query(&sensitive(Some(&name), None)).is_err());

        let comment = "😀".repeat(COMMENT_ALLOWED_SIZE as usize);
        assert!(validate_sensitive_lnurl_pay_query(&sensitive(None, Some(&comment))).is_ok());
        let comment = "a".repeat(COMMENT_ALLOWED_SIZE as usize + 1);
        assert!(validate_sensitive_lnurl_pay_query(&sensitive(None, Some(&comment))).is_err());
    }

    #[test]
    fn accepts_and_normalizes_valid_payer_identifier_domains() {
        for (identifier, expected) in [
            ("alice@localhost", "alice@localhost"),
            ("alice@Example.COM", "alice@example.com"),
            ("alice@bücher.example", "alice@xn--bcher-kva.example"),
        ] {
            assert_eq!(
                normalize_payer_identifier(identifier.to_lowercase()).unwrap(),
                expected
            );
        }
    }

    #[test]
    fn rejects_invalid_payer_identifier_domains() {
        let oversized_label = "a".repeat(64);
        for identifier in [
            "alice@example .com".to_string(),
            "alice@example.com:443".to_string(),
            "alice@-example.com".to_string(),
            "alice@example-.com".to_string(),
            "alice@example..com".to_string(),
            "alice@example_com".to_string(),
            "alice@127.0.0.1".to_string(),
            format!("alice@{oversized_label}.com"),
        ] {
            assert!(
                normalize_payer_identifier(identifier.clone()).is_err(),
                "{identifier} should be rejected"
            );
        }
    }
}
