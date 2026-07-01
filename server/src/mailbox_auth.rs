use ark::{ProtocolEncoding, mailbox::MailboxAuthorization};
use chrono::Utc;
use sqlx::PgPool;

use crate::{
    db::mailbox_authorization_repo::{
        MailboxAuthorizationBackfillCandidate, MailboxAuthorizationRepository,
    },
    errors::ApiError,
    types::AuthorizeMailboxPayload,
};

#[allow(dead_code)]
const MAILBOX_AUTH_EXPIRED_ERROR: &str = "mailbox authorization expired";
#[allow(dead_code)]
const MAILBOX_AUTH_INVALID_ERROR: &str = "mailbox authorization invalid";
#[allow(dead_code)]
const MAILBOX_AUTH_MISMATCH_ERROR: &str = "mailbox authorization does not match stored mailbox";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidatedMailboxAuthorization {
    pub mailbox_id: String,
    pub authorization_hex: String,
    pub authorization_expires_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
#[allow(dead_code)]
pub struct MailboxAuthorizationBackfillReport {
    pub checked: usize,
    pub valid: usize,
    pub normalized: usize,
    pub expired: usize,
    pub invalid: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[allow(dead_code)]
enum MailboxAuthorizationBackfillAction {
    Valid,
    Normalize {
        mailbox_id: String,
        authorization_expires_at: i64,
        authorization_hex: String,
    },
    Expired {
        mailbox_id: Option<String>,
        authorization_expires_at: Option<i64>,
        last_error: String,
    },
    Invalid {
        last_error: String,
    },
}

pub fn validate_authorize_mailbox_payload(
    payload: &AuthorizeMailboxPayload,
    now: i64,
    max_ttl_secs: i64,
) -> Result<ValidatedMailboxAuthorization, ApiError> {
    let authorization = decode_mailbox_authorization(&payload.encoded)?;
    if !authorization.verify() {
        return Err(ApiError::InvalidArgument(
            "Mailbox authorization signature is invalid".to_string(),
        ));
    }

    let mailbox_id = authorization.mailbox().serialize_hex();
    if !mailbox_id.eq_ignore_ascii_case(&payload.mailbox_id) {
        return Err(ApiError::InvalidArgument(
            "Mailbox authorization does not match mailbox ID".to_string(),
        ));
    }

    let embedded_expiry = authorization.expiry().timestamp();
    if embedded_expiry != payload.expiry {
        return Err(ApiError::InvalidArgument(
            "Mailbox authorization expiry does not match encoded authorization".to_string(),
        ));
    }

    if embedded_expiry <= now {
        return Err(ApiError::InvalidArgument(
            "Mailbox authorization expiry must be a future Unix timestamp".to_string(),
        ));
    }

    if embedded_expiry > now + max_ttl_secs {
        return Err(ApiError::InvalidArgument(
            "Mailbox authorization expiry exceeds maximum allowed TTL".to_string(),
        ));
    }

    Ok(ValidatedMailboxAuthorization {
        mailbox_id,
        authorization_hex: authorization.serialize_hex(),
        authorization_expires_at: embedded_expiry,
    })
}

#[allow(dead_code)]
pub async fn backfill_mailbox_authorizations(
    pool: &PgPool,
    dry_run: bool,
) -> anyhow::Result<MailboxAuthorizationBackfillReport> {
    let repo = MailboxAuthorizationRepository::new(pool);
    let candidates = repo.find_active_authorizations_for_backfill().await?;
    let now = Utc::now().timestamp();
    let mut report = MailboxAuthorizationBackfillReport {
        checked: candidates.len(),
        ..Default::default()
    };

    for candidate in candidates {
        match classify_backfill_candidate(&candidate, now) {
            MailboxAuthorizationBackfillAction::Valid => {
                report.valid += 1;
            }
            MailboxAuthorizationBackfillAction::Normalize {
                mailbox_id,
                authorization_expires_at,
                authorization_hex,
            } => {
                report.normalized += 1;
                if !dry_run {
                    repo.normalize_authorization(
                        &candidate.pubkey,
                        candidate.auth_version,
                        &mailbox_id,
                        authorization_expires_at,
                        &authorization_hex,
                    )
                    .await?;
                }
            }
            MailboxAuthorizationBackfillAction::Expired {
                mailbox_id,
                authorization_expires_at,
                last_error,
            } => {
                report.expired += 1;
                if !dry_run {
                    repo.mark_backfill_expired(
                        &candidate.pubkey,
                        candidate.auth_version,
                        mailbox_id.as_deref(),
                        authorization_expires_at,
                        &last_error,
                    )
                    .await?;
                }
            }
            MailboxAuthorizationBackfillAction::Invalid { last_error } => {
                report.invalid += 1;
                if !dry_run {
                    repo.mark_backfill_invalid(
                        &candidate.pubkey,
                        candidate.auth_version,
                        &last_error,
                    )
                    .await?;
                }
            }
        }
    }

    Ok(report)
}

fn decode_mailbox_authorization(encoded: &str) -> Result<MailboxAuthorization, ApiError> {
    MailboxAuthorization::deserialize_hex(encoded).map_err(|_| {
        ApiError::InvalidArgument("Mailbox authorization must be valid protocol hex".to_string())
    })
}

#[allow(dead_code)]
fn classify_backfill_candidate(
    candidate: &MailboxAuthorizationBackfillCandidate,
    now: i64,
) -> MailboxAuthorizationBackfillAction {
    let authorization = match MailboxAuthorization::deserialize_hex(&candidate.authorization_hex) {
        Ok(authorization) => authorization,
        Err(error) => {
            return MailboxAuthorizationBackfillAction::Invalid {
                last_error: format!("{MAILBOX_AUTH_INVALID_ERROR}: {error}"),
            };
        }
    };

    if !authorization.verify() {
        return MailboxAuthorizationBackfillAction::Invalid {
            last_error: "mailbox authorization signature is invalid".to_string(),
        };
    }

    let mailbox_id = authorization.mailbox().serialize_hex();
    let embedded_expiry = authorization.expiry().timestamp();
    if !mailbox_id.eq_ignore_ascii_case(&candidate.mailbox_id) {
        return MailboxAuthorizationBackfillAction::Invalid {
            last_error: MAILBOX_AUTH_MISMATCH_ERROR.to_string(),
        };
    }

    if embedded_expiry <= now {
        return MailboxAuthorizationBackfillAction::Expired {
            mailbox_id: Some(mailbox_id),
            authorization_expires_at: Some(embedded_expiry),
            last_error: MAILBOX_AUTH_EXPIRED_ERROR.to_string(),
        };
    }

    if candidate.mailbox_id != mailbox_id
        || candidate.authorization_expires_at != embedded_expiry
        || candidate.authorization_hex != authorization.serialize_hex()
    {
        return MailboxAuthorizationBackfillAction::Normalize {
            mailbox_id,
            authorization_expires_at: embedded_expiry,
            authorization_hex: authorization.serialize_hex(),
        };
    }

    MailboxAuthorizationBackfillAction::Valid
}
