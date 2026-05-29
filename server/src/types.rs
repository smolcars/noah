use regex::Regex;
use serde::{Deserialize, Serialize};
use std::sync::OnceLock;
use ts_rs::TS;
use validator::{Validate, ValidationError};

fn ln_username_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^[a-z0-9_.-]+$").expect("valid ln username regex"))
}

fn is_valid_ln_username(username: &str) -> bool {
    ln_username_regex().is_match(username)
}

pub(crate) fn is_valid_lightning_address(value: &str) -> bool {
    let (username, domain) = match value.split_once('@') {
        Some(parts) => parts,
        None => return false,
    };

    if username.is_empty() || domain.is_empty() {
        return false;
    }

    if domain.contains('@') {
        return false;
    }

    is_valid_ln_username(username)
}

fn validate_lightning_address(value: &str) -> Result<(), ValidationError> {
    if is_valid_lightning_address(value) {
        Ok(())
    } else {
        Err(ValidationError::new("lightning_address"))
    }
}

#[derive(Serialize, Deserialize, Debug, Clone, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct AuthLoginPayload {
    pub key: String,
    pub sig: String,
    pub k1: String,
}

#[derive(Debug, Clone)]
pub struct AuthenticatedUser {
    pub key: String,
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct AuthLoginResponse {
    pub access_token: String,
    pub token_type: String,
    pub expires_at: String,
    #[ts(type = "number")]
    pub expires_in_seconds: u64,
}

/// Represents a structured API error response.
#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct ApiErrorResponse {
    /// Always "ERROR" for error responses.
    pub status: String,
    /// Stable machine-readable error code.
    pub code: String,
    /// User-facing error message.
    pub message: String,
    /// Safe error reason for compatibility.
    pub reason: String,
}

/// Represents events that can occur during LNURL-auth.
#[derive(Serialize, Deserialize, TS)]
#[serde(rename_all = "UPPERCASE")]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub enum AuthEvent {
    /// Indicates that a user has been successfully registered.
    Registered,
}

/// Represents the response for an user registration.
#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct RegisterResponse {
    /// The status of the request, either "OK" or "ERROR".
    pub status: String,
    /// An optional event indicating the outcome of the authentication.
    pub event: Option<AuthEvent>,
    /// An optional reason for an error, if one occurred.
    pub reason: Option<String>,
    /// The user's lightning address.
    pub lightning_address: Option<String>,
    /// The user's optional display name.
    pub display_name: Option<String>,
    /// Whether the user's email is verified.
    pub is_email_verified: bool,
    /// The user's current operational lifecycle status.
    pub user_status: UserStatus,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq, TS)]
#[serde(rename_all = "lowercase")]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub enum UserStatus {
    Active,
    Inactive,
    Deregistered,
}

impl UserStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            UserStatus::Active => "active",
            UserStatus::Inactive => "inactive",
            UserStatus::Deregistered => "deregistered",
        }
    }
}

impl std::fmt::Display for UserStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.as_str())
    }
}

impl std::str::FromStr for UserStatus {
    type Err = anyhow::Error;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "active" => Ok(UserStatus::Active),
            "inactive" => Ok(UserStatus::Inactive),
            "deregistered" => Ok(UserStatus::Deregistered),
            _ => Err(anyhow::anyhow!("Invalid user status: {}", value)),
        }
    }
}

/// Defines device information captured during registration.
#[derive(Serialize, Deserialize, TS, Debug)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct DeviceInfo {
    pub device_manufacturer: Option<String>,
    pub device_model: Option<String>,
    pub os_name: Option<String>,
    pub os_version: Option<String>,
    pub app_version: Option<String>,
}

/// Defines the payload for a user registration request.
#[derive(Serialize, Deserialize, TS, Validate)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct RegisterPayload {
    /// User chosen lightning address
    #[validate(custom(function = "validate_lightning_address"))]
    pub ln_address: Option<String>,
    /// Optional device information.
    pub device_info: Option<DeviceInfo>,
    /// Optional Ark address
    pub ark_address: Option<String>,
}

/// Defines the payload for registering a push notification token.
#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct RegisterPushToken {
    /// The Expo push token for the user's device.
    pub push_token: String,
}

/// Defines the payload for granting mailbox authorization to the server.
#[derive(Serialize, Deserialize, TS, Validate)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct AuthorizeMailboxPayload {
    /// Ark mailbox identifier scoped to the loaded wallet.
    #[validate(length(min = 1))]
    pub mailbox_id: String,
    /// Authorization expiry as a Unix timestamp in seconds.
    #[ts(type = "number")]
    pub expiry: i64,
    /// Hex-encoded mailbox authorization.
    #[validate(length(min = 1))]
    pub encoded: String,
}

/// Represents the response for a user's information.
#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct UserInfoResponse {
    /// The user's lightning address.
    pub lightning_address: String,
    /// The user's optional display name.
    pub display_name: Option<String>,
    /// The user's current operational lifecycle status.
    pub user_status: UserStatus,
}

/// Defines the payload for submitting a BOLT11 invoice.
#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct SubmitInvoicePayload {
    /// The BOLT11 invoice to be paid.
    pub invoice: String,
    /// The unique identifier for the payment transaction.
    pub transaction_id: String,
}

/// Defines the payload for updating a user's lightning address.
#[derive(Serialize, Deserialize, TS, Validate)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct UpdateLnAddressPayload {
    /// The new lightning address for the user.
    #[validate(custom(function = "validate_lightning_address"))]
    pub ln_address: String,
}

/// Defines the payload for updating a user's profile.
#[derive(Serialize, Deserialize, TS, Validate)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct UpdateProfilePayload {
    /// The user's optional display name. Null or empty clears the name.
    #[validate(length(max = 80))]
    pub display_name: Option<String>,
}

/// Defines the payload for querying lightning address suggestions.
#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct LightningAddressSuggestionsPayload {
    /// Partial lightning address typed in the send screen.
    pub query: String,
}

/// Represents autocomplete suggestions for lightning addresses.
#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct LightningAddressSuggestionsResponse {
    /// Ordered suggestion list.
    pub suggestions: Vec<String>,
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct GetUploadUrlPayload {
    pub backup_version: i32, // 1 or 2 (rolling)
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct UploadUrlResponse {
    pub upload_url: String, // Pre-signed S3 URL
    pub s3_key: String,     // S3 object key
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct CompleteUploadPayload {
    pub s3_key: String,
    pub backup_version: i32,
    #[ts(type = "number")]
    pub backup_size: u64,
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct BackupInfo {
    pub backup_version: i32,
    pub created_at: String,
    #[ts(type = "number")]
    pub backup_size: u64,
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct GetDownloadUrlPayload {
    pub backup_version: Option<i32>, // None = latest
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct DownloadUrlResponse {
    pub download_url: String, // Pre-signed S3 URL
    #[ts(type = "number")]
    pub backup_size: u64,
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct DeleteBackupPayload {
    pub backup_version: i32,
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct BackupSettingsPayload {
    pub backup_enabled: bool,
}

#[derive(Debug, Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
#[serde(rename_all = "camelCase")]
pub enum ReportType {
    Maintenance,
    Backup,
}

#[derive(Debug, Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
#[serde(rename_all = "camelCase")]
pub enum ReportStatus {
    Pending,
    Success,
    Failure,
    Timeout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeartbeatStatus {
    Pending,
    Responded,
    Timeout,
}

impl std::fmt::Display for HeartbeatStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HeartbeatStatus::Pending => write!(f, "pending"),
            HeartbeatStatus::Responded => write!(f, "responded"),
            HeartbeatStatus::Timeout => write!(f, "timeout"),
        }
    }
}

impl std::str::FromStr for HeartbeatStatus {
    type Err = anyhow::Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "pending" => Ok(HeartbeatStatus::Pending),
            "responded" => Ok(HeartbeatStatus::Responded),
            "timeout" => Ok(HeartbeatStatus::Timeout),
            _ => Err(anyhow::anyhow!("Invalid heartbeat status: {}", s)),
        }
    }
}

#[derive(Debug, Serialize, Deserialize, TS, Clone)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct MaintenanceNotification {
    pub notification_k1: String,
}

#[derive(Debug, Serialize, Deserialize, TS, Clone)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct LightningInvoiceRequestNotification {
    pub transaction_id: String,
    #[ts(type = "number")]
    pub amount: u64,
}

#[derive(Debug, Serialize, Deserialize, TS, Clone)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct LightningClaimRequestNotification {
    pub payment_hash: Option<String>,
    #[ts(type = "number | null")]
    pub amount_sat: Option<u64>,
}

#[derive(Debug, Serialize, Deserialize, TS, Clone)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct BackupTriggerNotification {
    pub notification_k1: String,
}

#[derive(Debug, Serialize, Deserialize, TS, Clone)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct HeartbeatNotification {
    pub notification_id: String,
}

#[derive(Debug, Clone)]
pub enum NotificationRequestData {
    Maintenance,
    BackupTrigger,
    Heartbeat(HeartbeatNotification),
}

impl NotificationRequestData {
    pub fn notification_type(&self) -> &'static str {
        match self {
            NotificationRequestData::Maintenance => "maintenance",
            NotificationRequestData::BackupTrigger => "backup_trigger",
            NotificationRequestData::Heartbeat(_) => "heartbeat",
        }
    }

    pub fn needs_unique_k1(&self) -> bool {
        matches!(
            self,
            NotificationRequestData::Maintenance | NotificationRequestData::BackupTrigger
        )
    }

    pub fn report_type(&self) -> Option<ReportType> {
        match self {
            NotificationRequestData::Maintenance => Some(ReportType::Maintenance),
            NotificationRequestData::BackupTrigger => Some(ReportType::Backup),
            NotificationRequestData::Heartbeat(_) => None,
        }
    }

    pub fn into_notification_data(
        self,
        notification_k1: Option<String>,
    ) -> anyhow::Result<NotificationData> {
        match self {
            NotificationRequestData::Maintenance => {
                let notification_k1 = notification_k1.ok_or_else(|| {
                    anyhow::anyhow!("maintenance notifications require notification_k1")
                })?;
                Ok(NotificationData::Maintenance(MaintenanceNotification {
                    notification_k1,
                }))
            }
            NotificationRequestData::BackupTrigger => {
                let notification_k1 = notification_k1.ok_or_else(|| {
                    anyhow::anyhow!("backup notifications require notification_k1")
                })?;
                Ok(NotificationData::BackupTrigger(BackupTriggerNotification {
                    notification_k1,
                }))
            }
            NotificationRequestData::Heartbeat(notification) => {
                Ok(NotificationData::Heartbeat(notification))
            }
        }
    }
}

// Enum wrapper for all notification types
#[derive(Debug, Serialize, Deserialize, TS, Clone)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
#[serde(tag = "notification_type", rename_all = "snake_case")]
pub enum NotificationData {
    Maintenance(MaintenanceNotification),
    LightningInvoiceRequest(LightningInvoiceRequestNotification),
    LightningClaimRequest(LightningClaimRequestNotification),
    BackupTrigger(BackupTriggerNotification),
    Heartbeat(HeartbeatNotification),
}

impl NotificationData {
    /// Returns the canonical notification type identifier as a string.
    ///
    /// This is the **single source of truth** for notification type strings.
    /// The same string is used for:
    /// - JSON serialization tag (`notification_type` field in client)
    /// - Logging, analytics, and background job coordination
    ///
    /// The strings match the serde `snake_case` variant names exactly.
    ///
    /// # Examples
    /// - `BackupTrigger` → `"backup_trigger"`
    /// - `LightningClaimRequest` → `"lightning_claim_request"`
    /// - `LightningInvoiceRequest` → `"lightning_invoice_request"`
    /// - `Maintenance` → `"maintenance"`
    pub fn notification_type(&self) -> &'static str {
        match self {
            NotificationData::Maintenance(_) => "maintenance",
            NotificationData::LightningInvoiceRequest(_) => "lightning_invoice_request",
            NotificationData::LightningClaimRequest(_) => "lightning_claim_request",
            NotificationData::BackupTrigger(_) => "backup_trigger",
            NotificationData::Heartbeat(_) => "heartbeat",
        }
    }

    /// Check if this notification needs a unique k1 per device
    pub fn needs_unique_k1(&self) -> bool {
        matches!(
            self,
            NotificationData::Maintenance(_) | NotificationData::BackupTrigger(_)
        )
    }

    /// Set the k1 value for notifications that require it
    pub fn set_k1(&mut self, k1: String) {
        match self {
            NotificationData::Maintenance(n) => n.notification_k1 = k1,
            NotificationData::BackupTrigger(n) => n.notification_k1 = k1,
            NotificationData::Heartbeat(_)
            | NotificationData::LightningInvoiceRequest(_)
            | NotificationData::LightningClaimRequest(_) => {}
        }
    }
}

#[derive(Debug, Deserialize, Validate, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct HeartbeatResponsePayload {
    pub notification_id: String,
}

#[derive(Debug, Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct ReportJobStatusPayload {
    pub notification_k1: String,
    pub report_type: ReportType,
    pub status: ReportStatus,
    pub error_message: Option<String>,
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct DefaultSuccessPayload {
    pub success: bool,
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct AppVersionCheckPayload {
    pub client_version: String,
}

#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct AppVersionInfo {
    pub minimum_required_version: String,
    pub update_required: bool,
}

/// Defines the payload for requesting an email verification code.
#[derive(Serialize, Deserialize, TS, Validate)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct SendEmailVerificationPayload {
    #[validate(email)]
    pub email: String,
}

/// Defines the payload for verifying an email with a code.
#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct VerifyEmailPayload {
    pub code: String,
}

/// Represents the response for email verification requests.
#[derive(Serialize, Deserialize, TS)]
#[ts(export, export_to = "../../client/src/types/serverTypes.ts")]
pub struct EmailVerificationResponse {
    pub success: bool,
    pub message: Option<String>,
}
