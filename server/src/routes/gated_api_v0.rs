use crate::db::backup_repo::BackupRepository;
use crate::db::heartbeat_repo::HeartbeatRepository;
use crate::db::job_status_repo::JobStatusRepository;
use crate::db::mailbox_authorization_repo::MailboxAuthorizationRepository;
use crate::db::push_token_repo::PushTokenRepository;
use crate::db::user_repo::UserRepository;
use crate::wide_event::WideEventHandle;
// use crate::push::{PushNotificationData, send_push_notification};
use crate::s3_client::S3BackupClient;
use crate::types::{
    AuthorizeMailboxPayload, BackupInfo, BackupObjectDownloadResponse, BackupObjectInfo,
    BackupSettingsPayload, CompleteBackupUploadPayload, CompleteUploadPayload,
    DefaultSuccessPayload, DeleteBackupObjectPayload, DeleteBackupPayload, DownloadUrlResponse,
    GetBackupObjectDownloadPayload, GetDownloadUrlPayload, HeartbeatResponsePayload,
    InitiateBackupUploadPayload, InitiateBackupUploadResponse, LightningAddressSuggestionsPayload,
    LightningAddressSuggestionsResponse, ReportJobStatusPayload, ReportStatus,
    SubmitInvoicePayload, SubmitSupportTicketPayload, SubmitSupportTicketResponse,
    UpdateProfilePayload, UserInfoResponse, UserStatus,
};
use crate::{
    AppState,
    errors::ApiError,
    mailbox_auth::validate_authorize_mailbox_payload,
    types::{
        AuthenticatedUser, GetUploadUrlPayload, RegisterPushToken, UpdateLnAddressPayload,
        UploadUrlResponse,
    },
};
use axum::{Extension, Json, extract::State};
use base64::Engine;
use chrono::Utc;
use uuid::Uuid;
use validator::Validate;

const MAX_MAILBOX_AUTH_TTL_SECS: i64 = 90 * 24 * 60 * 60;
const LN_SUGGESTIONS_MIN_USERNAME_LEN: usize = 2;
const LN_SUGGESTIONS_MAX_QUERY_LEN: usize = 64;
const LN_SUGGESTIONS_LIMIT: i64 = 8;
const BACKUP_OBJECT_FORMAT_VERSION: i32 = 2;
const MAX_BACKUP_OBJECT_SIZE_BYTES: u64 = 256 * 1024 * 1024;
const NON_LN_SUGGESTION_PREFIXES: [&str; 9] = [
    "bc1", "tb1", "bcrt1", "lnbc", "lntb", "lnbcrt", "ark", "tark", "lno",
];

fn normalize_suggestions_query(query: &str) -> String {
    query
        .trim()
        .to_lowercase()
        .trim_start_matches("lightning:")
        .to_string()
}

fn parse_partial_lightning_address(query: &str) -> Option<(String, Option<String>)> {
    if query.is_empty() {
        return None;
    }

    if query.chars().filter(|c| *c == '@').count() > 1 {
        return None;
    }

    if let Some((username, domain_prefix)) = query.split_once('@') {
        if username.is_empty() {
            return None;
        }

        return Some((username.to_string(), Some(domain_prefix.to_string())));
    }

    Some((query.to_string(), None))
}

fn is_valid_partial_username(username: &str) -> bool {
    username
        .chars()
        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-' || c == '.')
}

fn is_valid_partial_domain(domain: &str) -> bool {
    domain
        .chars()
        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '.')
}

fn has_blocked_suggestion_prefix(username: &str) -> bool {
    NON_LN_SUGGESTION_PREFIXES
        .iter()
        .any(|prefix| username.starts_with(prefix))
}

/// Registers a push notification token for a user.
///
/// This endpoint associates a push token with a user's public key, allowing
/// the server to send push notifications to the user's device.
pub async fn register_push_token(
    State(app_state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<RegisterPushToken>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("has_push_token", true);
    }

    let push_token_repo = PushTokenRepository::new(&app_state.db_pool);
    push_token_repo
        .upsert(&auth_payload.key, &payload.push_token)
        .await?;

    let user_repo = UserRepository::new(&app_state.db_pool);
    user_repo
        .set_status(&auth_payload.key, UserStatus::Active)
        .await?;

    // TODO: Implement logic to send notification only once.
    // let app_state_clone = app_state.clone();
    // let pubkey = auth_payload.key.clone();
    // tokio::spawn(async move {
    //     let notification_data = PushNotificationData {
    //         title: Some("Welcome to Noah!".to_string()),
    //         body: Some("You're all set! You'll now receive notifications for payment requests and important updates.".to_string()),
    //         data: "{}".to_string(),
    //         priority: "normal".to_string(),
    //         content_available: false,
    //     };

    //     if let Err(e) =
    //         send_push_notification(app_state_clone, notification_data, Some(pubkey)).await
    //     {
    //         tracing::warn!("Failed to send welcome push notification: {}", e);
    //     }
    // });

    Ok(Json(DefaultSuccessPayload { success: true }))
}

/// Creates a Zoho Desk support ticket from in-app user feedback.
pub async fn submit_support_ticket(
    State(app_state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<SubmitSupportTicketPayload>,
) -> anyhow::Result<Json<SubmitSupportTicketResponse>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("support_ticket_submission", true);
        event.add_context("has_attachment", payload.attachment.is_some());
    }

    let response =
        crate::zoho::submit_support_ticket(&app_state.config, &auth_payload, payload).await?;

    Ok(Json(response))
}

/// Stores or refreshes mailbox authorization for a user.
pub async fn authorize_mailbox(
    State(app_state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<AuthorizeMailboxPayload>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    payload
        .validate()
        .map_err(|e| ApiError::InvalidArgument(e.to_string()))?;

    let validated = validate_authorize_mailbox_payload(
        &payload,
        Utc::now().timestamp(),
        MAX_MAILBOX_AUTH_TTL_SECS,
    )?;

    if let Some(Extension(event)) = event {
        event.add_context("has_mailbox_authorization", true);
        event.add_context(
            "mailbox_authorization_expiry",
            validated.authorization_expires_at,
        );
    }

    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    mailbox_repo
        .upsert(
            &auth_payload.key,
            &validated.mailbox_id,
            &validated.authorization_hex,
            validated.authorization_expires_at,
        )
        .await?;

    let user_repo = UserRepository::new(&app_state.db_pool);
    user_repo
        .set_status(&auth_payload.key, UserStatus::Active)
        .await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

/// Revokes mailbox authorization for a user.
pub async fn revoke_mailbox_authorization(
    State(app_state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("mailbox_authorization_revoked", true);
    }

    let mailbox_repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    mailbox_repo.revoke(&auth_payload.key).await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

/// Receives and processes a BOLT11 invoice from a user's device.
///
/// After a user generates an invoice in response to a push notification,
/// this endpoint receives it and forwards it to the waiting payer.
pub async fn submit_invoice(
    State(state): State<AppState>,
    Extension(_auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<SubmitInvoicePayload>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("transaction_id", &payload.transaction_id);
    }

    state
        .invoice_store
        .store(&payload.transaction_id, &payload.invoice)
        .await
        .map_err(|e| {
            tracing::error!("Failed to store invoice in Redis: {}", e);
            ApiError::ServerErr("Failed to store invoice".to_string())
        })?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

/// Returns autocomplete suggestions for a partial lightning address query.
pub async fn ln_address_suggestions(
    State(state): State<AppState>,
    Extension(_auth_payload): Extension<AuthenticatedUser>,
    Json(payload): Json<LightningAddressSuggestionsPayload>,
) -> anyhow::Result<Json<LightningAddressSuggestionsResponse>, ApiError> {
    let normalized_query = normalize_suggestions_query(&payload.query);

    if normalized_query.len() > LN_SUGGESTIONS_MAX_QUERY_LEN {
        return Err(ApiError::InvalidArgument("Query too long".to_string()));
    }

    let Some((username, domain_prefix)) = parse_partial_lightning_address(&normalized_query) else {
        return Ok(Json(LightningAddressSuggestionsResponse {
            suggestions: vec![],
        }));
    };

    if username.len() < LN_SUGGESTIONS_MIN_USERNAME_LEN || !is_valid_partial_username(&username) {
        return Ok(Json(LightningAddressSuggestionsResponse {
            suggestions: vec![],
        }));
    }

    if domain_prefix.is_none() && has_blocked_suggestion_prefix(&username) {
        return Ok(Json(LightningAddressSuggestionsResponse {
            suggestions: vec![],
        }));
    }

    if let Some(domain_prefix) = domain_prefix {
        if !is_valid_partial_domain(&domain_prefix) {
            return Ok(Json(LightningAddressSuggestionsResponse {
                suggestions: vec![],
            }));
        }

        let normalized_domain = state.lnurl_domain.to_lowercase();
        if !normalized_domain.starts_with(&domain_prefix) {
            return Ok(Json(LightningAddressSuggestionsResponse {
                suggestions: vec![],
            }));
        }
    }

    let user_repo = UserRepository::new(&state.db_pool);
    let suggestions = user_repo
        .search_lightning_address_suggestions(&username, &state.lnurl_domain, LN_SUGGESTIONS_LIMIT)
        .await?;

    Ok(Json(LightningAddressSuggestionsResponse { suggestions }))
}

/// Retrieves the user's information.
///
/// This endpoint returns the user's lightning address.
pub async fn get_user_info(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
) -> anyhow::Result<Json<UserInfoResponse>, ApiError> {
    let user_repo = UserRepository::new(&state.db_pool);

    let user = user_repo
        .find_by_pubkey(&auth_payload.key)
        .await?
        .ok_or(ApiError::NotFound("User not found".to_string()))?;

    let lightning_address = user.lightning_address.ok_or(ApiError::NotFound(
        "User does not have a lightning address".to_string(),
    ))?;

    Ok(Json(UserInfoResponse {
        lightning_address,
        display_name: user.display_name,
        email: user.email,
        user_status: user.status,
    }))
}

/// Updates a user's lightning address.
///
/// This endpoint allows a user to update their lightning address.
pub async fn update_ln_address(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    Json(payload): Json<UpdateLnAddressPayload>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if let Err(e) = payload.validate() {
        return Err(ApiError::InvalidArgument(e.to_string()));
    }

    let user_repo = UserRepository::new(&state.db_pool);

    let result = user_repo
        .update_lightning_address(&auth_payload.key, &payload.ln_address)
        .await;

    if let Err(e) = result {
        if e.is::<crate::db::user_repo::LightningAddressTakenError>() {
            return Err(ApiError::InvalidArgument(
                "Lightning address already taken".to_string(),
            ));
        }
        return Err(e.into());
    }

    Ok(Json(DefaultSuccessPayload { success: true }))
}

/// Updates a user's profile fields.
pub async fn update_profile(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    Json(payload): Json<UpdateProfilePayload>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if let Err(e) = payload.validate() {
        return Err(ApiError::InvalidArgument(e.to_string()));
    }

    let user_repo = UserRepository::new(&state.db_pool);
    user_repo
        .update_display_name(&auth_payload.key, payload.display_name.as_deref())
        .await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

pub async fn get_upload_url(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<GetUploadUrlPayload>,
) -> Result<Json<UploadUrlResponse>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("backup_version", payload.backup_version);
    }

    let s3_client = S3BackupClient::new(state.config.s3_bucket_name.clone()).await?;
    let s3_key = format!(
        "{}/backup_v{}.db",
        auth_payload.key.clone(),
        payload.backup_version
    );
    let upload_url = s3_client.generate_upload_url(&s3_key).await?;

    Ok(Json(UploadUrlResponse { upload_url, s3_key }))
}

pub async fn initiate_backup_object_upload(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    Json(payload): Json<InitiateBackupUploadPayload>,
) -> Result<Json<InitiateBackupUploadResponse>, ApiError> {
    if payload.format_version != BACKUP_OBJECT_FORMAT_VERSION {
        return Err(ApiError::InvalidArgument(format!(
            "Unsupported backup format version: {}",
            payload.format_version
        )));
    }
    if payload.encrypted_size == 0 {
        return Err(ApiError::InvalidArgument(
            "Backup size must be greater than zero".to_string(),
        ));
    }
    if payload.encrypted_size > MAX_BACKUP_OBJECT_SIZE_BYTES {
        return Err(ApiError::InvalidArgument(format!(
            "Backup size must not exceed {} bytes",
            MAX_BACKUP_OBJECT_SIZE_BYTES
        )));
    }

    let encrypted_sha256 = payload.encrypted_sha256.to_ascii_lowercase();
    let checksum_bytes = hex::decode(&encrypted_sha256).map_err(|_| {
        ApiError::InvalidArgument("Backup SHA-256 must be 64 hexadecimal characters".to_string())
    })?;
    if checksum_bytes.len() != 32 || encrypted_sha256.len() != 64 {
        return Err(ApiError::InvalidArgument(
            "Backup SHA-256 must be 64 hexadecimal characters".to_string(),
        ));
    }
    let checksum_sha256 = base64::engine::general_purpose::STANDARD.encode(checksum_bytes);

    let requested_backup_id = Uuid::new_v4();
    let requested_object_key = format!(
        "{}/backups/{}.noahbackup",
        auth_payload.key, requested_backup_id
    );
    let pending_object = BackupRepository::new(&state.db_pool)
        .create_pending_object(
            requested_backup_id,
            &auth_payload.key,
            &requested_object_key,
            payload.format_version,
            payload.encrypted_size,
            &encrypted_sha256,
        )
        .await?;

    if pending_object.format_version != payload.format_version
        || pending_object.encrypted_size != payload.encrypted_size
        || pending_object.encrypted_sha256 != encrypted_sha256
    {
        return Err(ApiError::Conflict(
            "A different backup upload is already pending. Please retry after it expires."
                .to_string(),
        ));
    }

    let s3_client = S3BackupClient::new(state.config.s3_bucket_name.clone()).await?;
    let upload_url = s3_client
        .generate_checksummed_upload_url(
            &pending_object.object_key,
            &checksum_sha256,
            payload.encrypted_size,
        )
        .await?;

    Ok(Json(InitiateBackupUploadResponse {
        backup_id: pending_object.backup_id.to_string(),
        upload_url,
        checksum_sha256,
    }))
}

pub async fn complete_backup_object_upload(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    Json(payload): Json<CompleteBackupUploadPayload>,
) -> Result<Json<DefaultSuccessPayload>, ApiError> {
    let backup_id = parse_backup_id(&payload.backup_id)?;
    let repo = BackupRepository::new(&state.db_pool);
    let object = repo
        .find_object(&auth_payload.key, backup_id)
        .await?
        .ok_or_else(|| ApiError::NotFound("Backup upload not found".to_string()))?;

    if object.completed_at.is_some() {
        return Ok(Json(DefaultSuccessPayload { success: true }));
    }

    let expected_checksum = base64::engine::general_purpose::STANDARD
        .encode(hex::decode(&object.encrypted_sha256).map_err(anyhow::Error::from)?);
    let s3_client = S3BackupClient::new(state.config.s3_bucket_name.clone()).await?;
    let uploaded = s3_client.head_object(&object.object_key).await?;
    if uploaded.size != object.encrypted_size {
        return Err(ApiError::InvalidArgument(
            "Uploaded backup size does not match the initiated upload".to_string(),
        ));
    }
    if uploaded.checksum_sha256.as_deref() != Some(expected_checksum.as_str()) {
        return Err(ApiError::InvalidArgument(
            "Uploaded backup checksum does not match the initiated upload".to_string(),
        ));
    }

    if !repo.complete_object(&auth_payload.key, backup_id).await? {
        return Err(ApiError::NotFound("Backup upload not found".to_string()));
    }

    for expired in repo
        .completed_objects_beyond_retention(&auth_payload.key)
        .await?
    {
        match s3_client.delete_object(&expired.object_key).await {
            Ok(()) => {
                repo.delete_object(&auth_payload.key, expired.backup_id)
                    .await?;
            }
            Err(error) => {
                tracing::warn!(
                    backup_id = %expired.backup_id,
                    error = %error,
                    "failed to prune retained backup object"
                );
            }
        }
    }

    Ok(Json(DefaultSuccessPayload { success: true }))
}

pub async fn list_backup_objects(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
) -> Result<Json<Vec<BackupObjectInfo>>, ApiError> {
    Ok(Json(
        BackupRepository::new(&state.db_pool)
            .list_completed_objects(&auth_payload.key)
            .await?,
    ))
}

pub async fn get_backup_object_download_url(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    Json(payload): Json<GetBackupObjectDownloadPayload>,
) -> Result<Json<BackupObjectDownloadResponse>, ApiError> {
    let backup_id = payload
        .backup_id
        .as_deref()
        .map(parse_backup_id)
        .transpose()?;
    let object = BackupRepository::new(&state.db_pool)
        .find_completed_object(&auth_payload.key, backup_id)
        .await?
        .ok_or_else(|| ApiError::NotFound("Backup not found".to_string()))?;
    let completed_at = object
        .completed_at
        .ok_or_else(|| ApiError::NotFound("Backup not found".to_string()))?;
    let download_url = S3BackupClient::new(state.config.s3_bucket_name.clone())
        .await?
        .generate_download_url(&object.object_key)
        .await?;

    Ok(Json(BackupObjectDownloadResponse {
        backup: BackupObjectInfo {
            backup_id: object.backup_id.to_string(),
            format_version: object.format_version,
            created_at: completed_at.to_rfc3339(),
            encrypted_size: object.encrypted_size,
            encrypted_sha256: object.encrypted_sha256,
        },
        download_url,
    }))
}

pub async fn delete_backup_object(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    Json(payload): Json<DeleteBackupObjectPayload>,
) -> Result<Json<DefaultSuccessPayload>, ApiError> {
    let backup_id = parse_backup_id(&payload.backup_id)?;
    let repo = BackupRepository::new(&state.db_pool);
    let object = repo
        .find_completed_object(&auth_payload.key, Some(backup_id))
        .await?
        .ok_or_else(|| ApiError::NotFound("Backup not found".to_string()))?;
    S3BackupClient::new(state.config.s3_bucket_name.clone())
        .await?
        .delete_object(&object.object_key)
        .await?;
    repo.delete_object(&auth_payload.key, backup_id).await?;
    Ok(Json(DefaultSuccessPayload { success: true }))
}

fn parse_backup_id(value: &str) -> Result<Uuid, ApiError> {
    Uuid::parse_str(value).map_err(|_| ApiError::InvalidArgument("Invalid backup ID".to_string()))
}

pub async fn complete_upload(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<CompleteUploadPayload>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("backup_version", payload.backup_version);
        event.add_context("backup_size_bytes", payload.backup_size);
    }

    let backup_repo = BackupRepository::new(&state.db_pool);
    backup_repo
        .upsert_metadata(
            &auth_payload.key,
            &payload.s3_key,
            payload.backup_size,
            payload.backup_version,
        )
        .await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

pub async fn list_backups(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
) -> Result<Json<Vec<BackupInfo>>, ApiError> {
    let backup_repo = BackupRepository::new(&state.db_pool);
    let backups = backup_repo.list(&auth_payload.key).await?;
    Ok(Json(backups))
}

pub async fn get_download_url(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<GetDownloadUrlPayload>,
) -> Result<Json<DownloadUrlResponse>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("backup_version", payload.backup_version);
    }

    let backup_repo = BackupRepository::new(&state.db_pool);

    let (s3_key, backup_size) = if let Some(version) = payload.backup_version {
        backup_repo
            .find_by_version(&auth_payload.key, version)
            .await?
            .ok_or(ApiError::NotFound("Backup not found".to_string()))?
    } else {
        backup_repo
            .find_latest(&auth_payload.key)
            .await?
            .ok_or(ApiError::NotFound("Backup not found".to_string()))?
    };

    let s3_client = S3BackupClient::new(state.config.s3_bucket_name.clone()).await?;
    let download_url = s3_client.generate_download_url(&s3_key).await?;

    Ok(Json(DownloadUrlResponse {
        download_url,
        backup_size,
    }))
}

pub async fn delete_backup(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<DeleteBackupPayload>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("backup_version", payload.backup_version);
    }

    let backup_repo = BackupRepository::new(&state.db_pool);

    let s3_key = backup_repo
        .find_s3_key_by_version(&auth_payload.key, payload.backup_version)
        .await?
        .ok_or(ApiError::NotFound("Backup not found".to_string()))?;

    let s3_client = S3BackupClient::new(state.config.s3_bucket_name.clone()).await?;
    s3_client.delete_object(&s3_key).await?;

    backup_repo
        .delete_by_version(&auth_payload.key, payload.backup_version)
        .await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

pub async fn report_job_status(
    State(app_state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<ReportJobStatusPayload>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if !matches!(
        payload.status,
        ReportStatus::Success | ReportStatus::Failure
    ) {
        return Err(ApiError::InvalidArgument(
            "report_job_status only accepts success or failure; pending/timeout are server-managed"
                .to_string(),
        ));
    }

    if let Some(Extension(event)) = event {
        event.add_context("report_type", format!("{:?}", payload.report_type));
        event.add_context("job_status", format!("{:?}", payload.status));
        event.add_context("has_error", payload.error_message.is_some());
        event.add_context("notification_k1", &payload.notification_k1);
    }

    let mut tx = app_state.db_pool.begin().await?;

    let updated = JobStatusRepository::update_by_k1(
        &mut tx,
        &auth_payload.key,
        &payload.notification_k1,
        &payload.report_type,
        &payload.status,
        payload.error_message,
    )
    .await?;

    if !updated {
        return Err(ApiError::NotFound(
            "Pending job status report not found for this k1".to_string(),
        ));
    }

    tx.commit().await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

pub async fn update_backup_settings(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    Json(payload): Json<BackupSettingsPayload>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    let backup_repo = BackupRepository::new(&state.db_pool);
    backup_repo
        .upsert_settings(&auth_payload.key, payload.backup_enabled)
        .await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

pub async fn deregister(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("action", "deregister");
    }

    let pubkey = auth_payload.key;

    // Use a transaction to ensure all or nothing is deleted
    let mut tx = state.db_pool.begin().await?;

    UserRepository::set_status_tx(&mut tx, &pubkey, UserStatus::Deregistered).await?;
    PushTokenRepository::delete_by_pubkey(&mut tx, &pubkey).await?;
    MailboxAuthorizationRepository::delete_by_pubkey(&mut tx, &pubkey).await?;
    HeartbeatRepository::delete_by_pubkey_tx(&mut tx, &pubkey).await?;

    tx.commit().await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

pub async fn heartbeat_response(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
    event: Option<Extension<WideEventHandle>>,
    Json(payload): Json<HeartbeatResponsePayload>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    if let Some(Extension(event)) = event {
        event.add_context("notification_id", &payload.notification_id);
    }

    let heartbeat_repo = HeartbeatRepository::new(&state.db_pool);

    let updated = heartbeat_repo
        .mark_as_responded(&payload.notification_id, &auth_payload.key)
        .await?;

    if !updated {
        return Err(ApiError::NotFound(
            "Heartbeat notification not found or already responded".to_string(),
        ));
    }

    let user_repo = UserRepository::new(&state.db_pool);
    user_repo
        .set_status(&auth_payload.key, UserStatus::Active)
        .await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}

pub async fn report_last_login(
    State(state): State<AppState>,
    Extension(auth_payload): Extension<AuthenticatedUser>,
) -> anyhow::Result<Json<DefaultSuccessPayload>, ApiError> {
    let user_repo = UserRepository::new(&state.db_pool);
    user_repo.update_last_login(&auth_payload.key).await?;

    Ok(Json(DefaultSuccessPayload { success: true }))
}
