use std::time::Duration;
use std::time::SystemTime;

use axum::{
    Extension, Json,
    extract::{Path, Query, State},
    http::StatusCode,
};
use expo_push_notification_client::Priority;
use rand::Rng;
use uuid::Uuid;

use serde::{Deserialize, Serialize};
use tokio::time::sleep;
use validator::Validate;

use crate::{
    AppState,
    auth::mint_access_token,
    cache::email_verification_store::EmailVerificationStore,
    db::{
        device_repo::DeviceRepository, mailbox_authorization_repo::MailboxAuthorizationRepository,
        user_repo::UserRepository,
    },
    errors::ApiError,
    push::{PushNotificationData, has_expo_push_token, send_expo_push_notification},
    types::{
        AppVersionCheckPayload, AppVersionInfo, AuthEvent, AuthLoginPayload, AuthLoginResponse,
        AuthenticatedUser, EmailVerificationResponse, LightningInvoiceRequestNotification,
        NotificationData, RegisterPayload, RegisterResponse, SendEmailVerificationPayload,
        UserStatus, VerifyEmailPayload,
    },
    utils::{make_k1, verify_auth},
    wide_event::WideEventHandle,
};

/// Represents the response for a `k1` request, used in LNURL-auth.
#[derive(Serialize, Deserialize)]
pub struct GetK1 {
    /// A unique, single-use secret for the authentication process.
    pub k1: String,
    /// The LNURL-auth tag, which is always "login".
    pub tag: String,
}

const LNURLP_MIN_SENDABLE: u64 = 330000;
const LNURLP_MAX_SENDABLE: u64 = 100000000;
const COMMENT_ALLOWED_SIZE: u16 = 280;
const POLL_INTERVAL: Duration = Duration::from_millis(500);
const TIMEOUT: Duration = Duration::from_secs(30);
/// Generates and returns a new `k1` value for an LNURL-auth flow.
///
/// The `k1` value is a random 32-byte hex-encoded string that is stored in Redis with
/// a strict TTL so it can be used once for a login or registration attempt.
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

pub async fn auth_login(
    State(state): State<AppState>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<AuthLoginPayload>,
) -> anyhow::Result<Json<AuthLoginResponse>, ApiError> {
    let k1_consumed = state.k1_cache.take(&payload.k1).await.map_err(|e| {
        tracing::error!(error = %e, "Auth login failed: Unable to consume k1");
        ApiError::ServerErr("Failed to validate k1".to_string())
    })?;

    if !k1_consumed {
        return Err(ApiError::InvalidArgument("Invalid k1".to_string()));
    }

    let k1_parts: Vec<&str> = payload.k1.split('_').collect();
    if k1_parts.len() != 2 {
        return Err(ApiError::InvalidArgument("Invalid k1 format".to_string()));
    }

    let timestamp = k1_parts[1]
        .parse::<u64>()
        .map_err(|_| ApiError::InvalidArgument("Invalid timestamp in k1".to_string()))?;

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
#[derive(Serialize, Deserialize)]
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
    /// The maximum length of a comment that can be included with the payment.
    pub comment_allowed: u16,
}

/// Represents the second response in the LNURL-pay protocol.
///
/// This response contains the BOLT11 invoice that the wallet will use to pay.
#[derive(Serialize, Deserialize)]
pub struct LnurlpInvoiceResponse {
    /// The BOLT11 payment request (invoice).
    pub pr: String,
    /// A list of routes for the payment, typically empty.
    pub routes: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ark: Option<String>,
}

/// Defines the query parameters for an LNURL-pay request.
#[derive(Deserialize)]
pub struct LnurlpRequestQuery {
    /// The amount of the payment in millisatoshis.
    amount: Option<u64>,
    wallet: Option<String>,
}

/// Handles LNURL-pay requests.
///
/// This endpoint manages the two-step LNURL-pay flow. The first request (without an amount)
/// returns payment parameters. The second request (with an amount) triggers a push
/// notification to the user to generate an invoice, which is then returned to the payer.
pub async fn lnurlp_request(
    State(state): State<AppState>,
    Path(username): Path<String>,
    Query(query): Query<LnurlpRequestQuery>,
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

        let response = LnurlpDefaultResponse {
            callback: format!("https://{}/.well-known/lnurlp/{}", lnurl_domain, username),
            min_sendable: LNURLP_MIN_SENDABLE,
            max_sendable: LNURLP_MAX_SENDABLE,
            metadata,
            tag: "payRequest".to_string(),
            comment_allowed: COMMENT_ALLOWED_SIZE,
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

    if let Some(wallet) = &query.wallet
        && wallet == "noahwallet"
        && let Some(ark_address) = &user.ark_address
    {
        let response = LnurlpInvoiceResponse {
            pr: "".to_string(),
            routes: vec![],
            ark: Some(ark_address.clone()),
        };
        return Ok(Json(
            serde_json::to_value(response).map_err(|e| ApiError::SerializeErr(e.to_string()))?,
        ));
    }

    if !has_expo_push_token(&state, &pubkey).await? {
        tracing::warn!(
            pubkey = %pubkey,
            "Lightning LNURL invoice request rejected because user has no Expo push token"
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
        if let Err(e) = send_expo_push_notification(state_clone, data, Some(pubkey)).await {
            tracing::error!("Failed to send push notification: {}", e);
        }
    });

    tracing::debug!("Polling for invoice with a 30s timeout...");

    let start = std::time::Instant::now();

    let invoice = loop {
        match state.invoice_store.get(&transaction_id).await {
            Ok(Some(inv)) => {
                // Clean up after successful retrieval
                if let Err(e) = state.invoice_store.remove(&transaction_id).await {
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
                        "Invoice request timed out after 30s for transaction_id: {}",
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

    let response = LnurlpInvoiceResponse {
        pr: invoice,
        routes: vec![],
        ark: user.ark_address,
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
            display_name: user.display_name,
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
        display_name: None,
        is_email_verified: false,
        user_status: UserStatus::Active,
    }))
}

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

    if user.is_email_verified {
        return Ok(Json(EmailVerificationResponse {
            success: true,
            message: Some("Email already verified".to_string()),
        }));
    }

    let code = EmailVerificationStore::generate_code();

    state
        .email_verification_store
        .store(&auth_payload.key, &payload.email, &code)
        .await
        .map_err(|e| {
            tracing::error!("Failed to store verification code: {}", e);
            ApiError::ServerErr("Failed to store verification code".to_string())
        })?;

    state
        .email_client
        .send_verification_email(&payload.email, &code)
        .await
        .map_err(|e| {
            tracing::error!("Failed to send verification email: {}", e);
            ApiError::ServerErr("Failed to send verification email".to_string())
        })?;

    tracing::info!(
        "Verification email sent to {} for user {}",
        payload.email,
        auth_payload.key
    );

    Ok(Json(EmailVerificationResponse {
        success: true,
        message: Some("Verification code sent".to_string()),
    }))
}

/// Verifies the email verification code.
pub async fn verify_email(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<VerifyEmailPayload>,
) -> anyhow::Result<Json<EmailVerificationResponse>, ApiError> {
    let user_repo = UserRepository::new(&state.db_pool);

    let user = user_repo
        .find_by_pubkey(&auth_payload.key)
        .await?
        .ok_or_else(|| ApiError::UserNotFound)?;

    if user.is_email_verified {
        if let Some(Extension(event)) = &event {
            event.add_context("verification_result", "already_verified");
        }
        return Ok(Json(EmailVerificationResponse {
            success: true,
            message: Some("Email already verified".to_string()),
        }));
    }

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
