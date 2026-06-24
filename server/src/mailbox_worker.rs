#![allow(dead_code)]

use std::{
    cmp,
    collections::{HashMap, HashSet},
    sync::Arc,
    time::Duration,
};

use anyhow::Result;
use ark::{ProtocolEncoding, Vtxo, vtxo::Full, vtxo::policy::VtxoPolicyKind};
use async_trait::async_trait;
use chrono::Utc;
use expo_push_notification_client::Priority;
use futures_util::StreamExt;
use server_rpc::{
    ServerConnection,
    mailbox::MailboxServiceClient,
    protos::mailbox_server::{MailboxMessage, MailboxRequest, mailbox_message::Message},
    tonic::{Code, Status},
};
use tokio::{
    sync::Semaphore,
    task::JoinSet,
    time::{Instant, interval_at, sleep},
};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::{
    AppState,
    db::mailbox_authorization_repo::{ActiveMailboxAuthorization, MailboxAuthorizationRepository},
    errors::ApiError,
    push::{PushNotificationData, send_expo_push_notification, send_push_notification},
    types::{LightningClaimRequestNotification, NotificationData},
};

#[derive(Debug, Clone)]
pub struct MailboxWorkerConfig {
    pub concurrency_limit: usize,
    pub scan_interval: Duration,
    pub batch_size: i64,
    pub base_retry_delay: Duration,
    pub max_retry_delay: Duration,
    pub claim_ttl: Duration,
    pub claim_renew_interval: Duration,
    pub stream_idle_reconnect: Duration,
}

impl Default for MailboxWorkerConfig {
    fn default() -> Self {
        Self {
            concurrency_limit: 250,
            scan_interval: Duration::from_secs(15),
            batch_size: 100,
            base_retry_delay: Duration::from_secs(5),
            max_retry_delay: Duration::from_secs(300),
            claim_ttl: Duration::from_secs(120),
            claim_renew_interval: Duration::from_secs(30),
            stream_idle_reconnect: Duration::from_secs(600),
        }
    }
}

impl MailboxWorkerConfig {
    pub fn from_env() -> Self {
        let default = Self::default();
        Self {
            concurrency_limit: env_usize(
                "MAILBOX_WORKER_CONCURRENCY_LIMIT",
                default.concurrency_limit,
            ),
            scan_interval: env_duration_secs(
                "MAILBOX_WORKER_SCAN_INTERVAL_SECS",
                default.scan_interval,
            ),
            batch_size: env_i64("MAILBOX_WORKER_BATCH_SIZE", default.batch_size),
            base_retry_delay: env_duration_secs(
                "MAILBOX_WORKER_BASE_RETRY_DELAY_SECS",
                default.base_retry_delay,
            ),
            max_retry_delay: env_duration_secs(
                "MAILBOX_WORKER_MAX_RETRY_DELAY_SECS",
                default.max_retry_delay,
            ),
            claim_ttl: env_duration_secs("MAILBOX_WORKER_CLAIM_TTL_SECS", default.claim_ttl),
            claim_renew_interval: env_duration_secs(
                "MAILBOX_WORKER_CLAIM_RENEW_INTERVAL_SECS",
                default.claim_renew_interval,
            ),
            stream_idle_reconnect: env_duration_secs(
                "MAILBOX_WORKER_STREAM_IDLE_RECONNECT_SECS",
                default.stream_idle_reconnect,
            ),
        }
        .with_safe_claim_renew_interval()
    }

    fn with_safe_claim_renew_interval(mut self) -> Self {
        let max_renew_interval_secs = self.claim_ttl.as_secs().saturating_div(2).max(1);
        if self.claim_renew_interval.as_secs() > max_renew_interval_secs {
            tracing::warn!(
                service = "mailbox_worker",
                configured_claim_renew_interval_secs = self.claim_renew_interval.as_secs(),
                claim_ttl_secs = self.claim_ttl.as_secs(),
                adjusted_claim_renew_interval_secs = max_renew_interval_secs,
                "mailbox claim renew interval exceeds safe threshold; adjusting"
            );
            self.claim_renew_interval = Duration::from_secs(max_renew_interval_secs);
        }
        self
    }

    pub fn log(&self) {
        tracing::info!(
            service = "mailbox_worker",
            concurrency_limit = self.concurrency_limit,
            scan_interval_secs = self.scan_interval.as_secs(),
            batch_size = self.batch_size,
            base_retry_delay_secs = self.base_retry_delay.as_secs(),
            max_retry_delay_secs = self.max_retry_delay.as_secs(),
            claim_ttl_secs = self.claim_ttl.as_secs(),
            claim_renew_interval_secs = self.claim_renew_interval.as_secs(),
            stream_idle_reconnect_secs = self.stream_idle_reconnect.as_secs(),
            "mailbox worker config loaded"
        );
    }
}

fn env_usize(key: &str, default: usize) -> usize {
    std::env::var(key)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(default)
}

fn env_i64(key: &str, default: i64) -> i64 {
    std::env::var(key)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(default)
}

fn env_duration_secs(key: &str, default: Duration) -> Duration {
    std::env::var(key)
        .ok()
        .and_then(|value| value.parse().ok())
        .map(Duration::from_secs)
        .unwrap_or(default)
}

#[derive(Debug, Clone)]
pub struct MailboxSessionContext {
    pub worker_id: String,
    pub stream_idle_reconnect: Duration,
    pub cancellation_token: CancellationToken,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MailboxSessionOutcome {
    Completed,
    Retryable { reason: String },
    InvalidAuth { reason: String },
    Expired { reason: String },
}

#[async_trait]
pub trait MailboxTransport: Send + Sync {
    async fn run_session(
        &self,
        app_state: AppState,
        mailbox: ActiveMailboxAuthorization,
        session: MailboxSessionContext,
    ) -> Result<MailboxSessionOutcome>;
}

pub struct MailboxWorker<T> {
    app_state: AppState,
    transport: Arc<T>,
    config: MailboxWorkerConfig,
    semaphore: Arc<Semaphore>,
    worker_id: String,
}

#[derive(Debug, Clone)]
struct ActiveMailboxSession {
    auth_version: i64,
    cancellation_token: CancellationToken,
}

impl<T> MailboxWorker<T>
where
    T: MailboxTransport + 'static,
{
    pub fn new(app_state: AppState, transport: Arc<T>, config: MailboxWorkerConfig) -> Self {
        let semaphore = Arc::new(Semaphore::new(config.concurrency_limit));
        Self {
            app_state,
            transport,
            config,
            semaphore,
            worker_id: Uuid::new_v4().to_string(),
        }
    }

    pub async fn run(&self) -> Result<()> {
        let mut join_set = JoinSet::new();
        let mut active_sessions = HashMap::new();
        let mut claim_renewal = interval_at(
            Instant::now() + self.config.claim_renew_interval,
            self.config.claim_renew_interval,
        );

        loop {
            while let Some(result) = join_set.try_join_next() {
                if let Err(error) = Self::handle_session_result(result, &mut active_sessions).await
                {
                    tracing::error!(
                        service = "mailbox_worker",
                        error = %error,
                        "mailbox session join failed"
                    );
                }
            }

            if let Err(error) = self
                .schedule_runnable_sessions(&mut join_set, &mut active_sessions)
                .await
            {
                tracing::error!(
                    service = "mailbox_worker",
                    error = %error,
                    retry_delay_secs = self.config.base_retry_delay.as_secs(),
                    "mailbox scheduler failed; retrying"
                );
                sleep(self.config.base_retry_delay).await;
                continue;
            }

            tokio::select! {
                result = join_set.join_next(), if !join_set.is_empty() => {
                    if let Some(result) = result
                        && let Err(error) = Self::handle_session_result(result, &mut active_sessions).await
                    {
                        tracing::error!(
                            service = "mailbox_worker",
                            error = %error,
                            "mailbox session join failed"
                        );
                    }
                }
                _ = claim_renewal.tick(), if !active_sessions.is_empty() => {
                    if let Err(error) = self.renew_active_sessions(&mut active_sessions).await {
                        tracing::error!(
                            service = "mailbox_worker",
                            error = %error,
                            active_sessions = active_sessions.len(),
                            "mailbox bulk lease renewal failed"
                        );
                    }
                }
                _ = sleep(self.config.scan_interval) => {}
            }
        }
    }

    pub async fn run_once(&self) -> Result<usize> {
        let mut join_set = JoinSet::new();
        let mut active_sessions = HashMap::new();
        let scheduled = self
            .schedule_runnable_sessions(&mut join_set, &mut active_sessions)
            .await?;

        while let Some(result) = join_set.join_next().await {
            Self::handle_session_result(result, &mut active_sessions).await?;
        }

        Ok(scheduled)
    }

    async fn schedule_runnable_sessions(
        &self,
        join_set: &mut JoinSet<(String, Result<()>)>,
        active_sessions: &mut HashMap<String, ActiveMailboxSession>,
    ) -> Result<usize> {
        let available_slots = self.semaphore.available_permits();
        if available_slots == 0 {
            return Ok(0);
        }

        let repo = MailboxAuthorizationRepository::new(&self.app_state.db_pool);
        let fetch_limit = cmp::min(self.config.batch_size, available_slots as i64);
        let now = Utc::now();
        let lease_expires_at = now + chrono::TimeDelta::from_std(self.config.claim_ttl)?;
        let runnable = repo
            .claim_runnable(now, &self.worker_id, lease_expires_at, fetch_limit)
            .await?;

        let mut scheduled = 0usize;

        for mailbox in runnable {
            if active_sessions.contains_key(&mailbox.pubkey) {
                continue;
            }

            let Ok(permit) = self.semaphore.clone().try_acquire_owned() else {
                break;
            };

            let pubkey = mailbox.pubkey.clone();
            let app_state = self.app_state.clone();
            let transport = self.transport.clone();
            let config = self.config.clone();
            let worker_id = self.worker_id.clone();
            let cancellation_token = CancellationToken::new();

            active_sessions.insert(
                pubkey.clone(),
                ActiveMailboxSession {
                    auth_version: mailbox.auth_version,
                    cancellation_token: cancellation_token.clone(),
                },
            );
            join_set.spawn(async move {
                let _permit = permit;
                let result = process_mailbox_session(
                    app_state,
                    transport,
                    config,
                    worker_id,
                    mailbox,
                    cancellation_token,
                )
                .await;
                (pubkey, result)
            });
            scheduled += 1;
        }

        Ok(scheduled)
    }

    async fn renew_active_sessions(
        &self,
        active_sessions: &mut HashMap<String, ActiveMailboxSession>,
    ) -> Result<()> {
        let active_claims = active_sessions
            .iter()
            .map(|(pubkey, session)| (pubkey.clone(), session.auth_version))
            .collect::<Vec<_>>();

        let repo = MailboxAuthorizationRepository::new(&self.app_state.db_pool);
        let now = Utc::now();
        let renew_window = chrono::TimeDelta::from_std(self.config.claim_ttl / 2)?;
        let renew_after = now + renew_window;
        let lease_expires_at = now + chrono::TimeDelta::from_std(self.config.claim_ttl)?;
        let claims = repo
            .bulk_renew_claims(
                &active_claims,
                &self.worker_id,
                now,
                renew_after,
                lease_expires_at,
            )
            .await?;

        let valid_pubkeys = claims
            .iter()
            .map(|claim| claim.pubkey.clone())
            .collect::<HashSet<_>>();
        let renewed_count = claims.iter().filter(|claim| claim.renewed).count();
        let lost_pubkeys = active_sessions
            .keys()
            .filter(|pubkey| !valid_pubkeys.contains(*pubkey))
            .cloned()
            .collect::<Vec<_>>();

        for pubkey in &lost_pubkeys {
            if let Some(session) = active_sessions.get(pubkey) {
                session.cancellation_token.cancel();
            }
        }

        if renewed_count > 0 || !lost_pubkeys.is_empty() {
            tracing::debug!(
                service = "mailbox_worker",
                active_sessions = active_sessions.len(),
                renewed_count,
                lost_ownership_count = lost_pubkeys.len(),
                "mailbox bulk lease renewal complete"
            );
        }

        Ok(())
    }

    async fn handle_session_result(
        result: std::result::Result<(String, Result<()>), tokio::task::JoinError>,
        active_sessions: &mut HashMap<String, ActiveMailboxSession>,
    ) -> Result<()> {
        let (pubkey, session_result) = result?;
        active_sessions.remove(&pubkey);
        if let Err(error) = session_result {
            tracing::error!(
                service = "mailbox_worker",
                pubkey = %pubkey,
                error = %error,
                "mailbox session failed"
            );
        }

        Ok(())
    }
}

async fn process_mailbox_session<T>(
    app_state: AppState,
    transport: Arc<T>,
    config: MailboxWorkerConfig,
    worker_id: String,
    mailbox: ActiveMailboxAuthorization,
    cancellation_token: CancellationToken,
) -> Result<()>
where
    T: MailboxTransport + 'static,
{
    let repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    let session = MailboxSessionContext {
        worker_id: worker_id.clone(),
        stream_idle_reconnect: config.stream_idle_reconnect,
        cancellation_token,
    };

    let outcome = match transport
        .run_session(app_state.clone(), mailbox.clone(), session)
        .await
    {
        Ok(outcome) => outcome,
        Err(error) => MailboxSessionOutcome::Retryable {
            reason: error.to_string(),
        },
    };

    match outcome {
        MailboxSessionOutcome::Completed => {
            repo.clear_error(&mailbox.pubkey, mailbox.auth_version, &worker_id)
                .await?;
        }
        MailboxSessionOutcome::Retryable { reason } => {
            let retry_at =
                Utc::now() + compute_retry_delay(&repo, &mailbox.pubkey, &config).await?;
            repo.mark_retry(
                &mailbox.pubkey,
                retry_at,
                &reason,
                mailbox.auth_version,
                &worker_id,
            )
            .await?;
        }
        MailboxSessionOutcome::InvalidAuth { reason } => {
            repo.mark_invalid(&mailbox.pubkey, &reason, mailbox.auth_version, &worker_id)
                .await?;
        }
        MailboxSessionOutcome::Expired { reason } => {
            repo.mark_expired(&mailbox.pubkey, &reason, mailbox.auth_version, &worker_id)
                .await?;
        }
    }

    repo.release_claim(&mailbox.pubkey, mailbox.auth_version, &worker_id)
        .await?;

    Ok(())
}

async fn compute_retry_delay(
    repo: &MailboxAuthorizationRepository<'_>,
    pubkey: &str,
    config: &MailboxWorkerConfig,
) -> Result<chrono::TimeDelta> {
    let retries = repo.current_failure_count(pubkey).await?.saturating_add(1);

    let shift = retries.saturating_sub(1).min(16) as u32;
    let base_secs = config.base_retry_delay.as_secs();
    let max_secs = config.max_retry_delay.as_secs();
    let delay_secs = cmp::min(base_secs.saturating_mul(1u64 << shift), max_secs);

    Ok(chrono::TimeDelta::seconds(delay_secs as i64))
}

pub struct MailboxTransportUnavailable;

#[async_trait]
impl MailboxTransport for MailboxTransportUnavailable {
    async fn run_session(
        &self,
        _app_state: AppState,
        _mailbox: ActiveMailboxAuthorization,
        _session: MailboxSessionContext,
    ) -> Result<MailboxSessionOutcome> {
        Ok(MailboxSessionOutcome::Retryable {
            reason: "mailbox transport not implemented".to_string(),
        })
    }
}

pub struct Beta8MailboxTransport;

#[async_trait]
impl MailboxTransport for Beta8MailboxTransport {
    async fn run_session(
        &self,
        app_state: AppState,
        mailbox: ActiveMailboxAuthorization,
        session: MailboxSessionContext,
    ) -> Result<MailboxSessionOutcome> {
        let now = Utc::now().timestamp();
        if mailbox.authorization_expires_at <= now {
            return Ok(MailboxSessionOutcome::Expired {
                reason: "mailbox authorization has expired".to_string(),
            });
        }

        let unblinded_id = match decode_hex_bytes("mailbox_id", &mailbox.mailbox_id) {
            Ok(value) => value,
            Err(reason) => return Ok(MailboxSessionOutcome::InvalidAuth { reason }),
        };
        let authorization = match decode_hex_bytes("authorization", &mailbox.authorization_hex) {
            Ok(value) => value,
            Err(reason) => return Ok(MailboxSessionOutcome::InvalidAuth { reason }),
        };

        let mut checkpoint = mailbox.last_checkpoint as u64;
        let mut suppress_catchup_notifications =
            should_suppress_catchup_notifications(mailbox.last_checkpoint);

        loop {
            let network = app_state.config.network()?;
            let mut client: MailboxServiceClient<_> = ServerConnection::builder()
                .address(&app_state.config.ark_server_url)
                .network(network)
                .connect()
                .await?
                .mailbox_client;

            let mut catchup_count = 0u64;
            loop {
                if session.cancellation_token.is_cancelled() {
                    return Ok(MailboxSessionOutcome::Completed);
                }

                let read_response = client
                    .read_mailbox(MailboxRequest {
                        unblinded_id: unblinded_id.clone(),
                        authorization: Some(authorization.clone()),
                        checkpoint,
                    })
                    .await;

                let read_response = match read_response {
                    Ok(response) => response,
                    Err(status) => return Ok(map_tonic_status(status)),
                };

                let messages = read_response.into_inner().messages;
                if messages.is_empty() {
                    break;
                }

                for message in messages {
                    if !process_mailbox_message(
                        &app_state,
                        &mailbox,
                        &session,
                        &message,
                        !suppress_catchup_notifications,
                    )
                    .await?
                    {
                        return Ok(MailboxSessionOutcome::Completed);
                    }
                    checkpoint = message.checkpoint;
                    catchup_count += 1;
                }
            }

            if catchup_count > 0 {
                tracing::info!(
                    service = "mailbox_worker",
                    pubkey = %mailbox.pubkey,
                    catchup_count,
                    checkpoint,
                    notifications_suppressed = suppress_catchup_notifications,
                    "mailbox catch-up complete"
                );
            }

            suppress_catchup_notifications = false;

            let stream_response = client
                .subscribe_mailbox(MailboxRequest {
                    unblinded_id: unblinded_id.clone(),
                    authorization: Some(authorization.clone()),
                    checkpoint,
                })
                .await;

            let mut stream = match stream_response {
                Ok(response) => response.into_inner(),
                Err(status) => return Ok(map_tonic_status(status)),
            };

            tracing::trace!(
                service = "mailbox_worker",
                pubkey = %mailbox.pubkey,
                checkpoint,
                idle_reconnect_secs = session.stream_idle_reconnect.as_secs(),
                "mailbox subscription started"
            );

            let idle_reconnect = sleep(session.stream_idle_reconnect);
            tokio::pin!(idle_reconnect);

            loop {
                tokio::select! {
                    _ = session.cancellation_token.cancelled() => {
                        return Ok(MailboxSessionOutcome::Completed);
                    },
                    _ = &mut idle_reconnect => {
                        tracing::trace!(
                            service = "mailbox_worker",
                            pubkey = %mailbox.pubkey,
                            checkpoint,
                            idle_reconnect_secs = session.stream_idle_reconnect.as_secs(),
                            "mailbox stream idle; reconnecting and checking catch-up"
                        );
                        break;
                    }
                    next = stream.next() => {
                        let Some(next) = next else {
                            return Ok(MailboxSessionOutcome::Retryable {
                                reason: "mailbox stream ended".to_string(),
                            });
                        };

                        let message = match next {
                            Ok(message) => message,
                            Err(status) => return Ok(map_tonic_status(status)),
                        };

                        if !process_mailbox_message(&app_state, &mailbox, &session, &message, true).await? {
                            return Ok(MailboxSessionOutcome::Completed);
                        }
                        checkpoint = message.checkpoint;
                        idle_reconnect.as_mut().reset(Instant::now() + session.stream_idle_reconnect);
                    }
                }
            }
        }
    }
}

fn decode_hex_bytes(field: &str, value: &str) -> std::result::Result<Vec<u8>, String> {
    hex::decode(value).map_err(|e| format!("invalid {} hex: {}", field, e))
}

fn map_tonic_status(status: Status) -> MailboxSessionOutcome {
    match status.code() {
        Code::Unauthenticated | Code::PermissionDenied => MailboxSessionOutcome::InvalidAuth {
            reason: status.message().to_string(),
        },
        Code::InvalidArgument => {
            let message = status.message().to_ascii_lowercase();
            if message.contains("expire") {
                MailboxSessionOutcome::Expired {
                    reason: status.message().to_string(),
                }
            } else {
                MailboxSessionOutcome::InvalidAuth {
                    reason: status.message().to_string(),
                }
            }
        }
        Code::FailedPrecondition => MailboxSessionOutcome::Expired {
            reason: status.message().to_string(),
        },
        _ => MailboxSessionOutcome::Retryable {
            reason: status.to_string(),
        },
    }
}

async fn process_mailbox_message(
    app_state: &AppState,
    mailbox: &ActiveMailboxAuthorization,
    session: &MailboxSessionContext,
    message: &MailboxMessage,
    send_notifications: bool,
) -> Result<bool, ApiError> {
    if session.cancellation_token.is_cancelled() {
        return Ok(false);
    }

    let repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    if !repo
        .claim_is_active(
            &mailbox.pubkey,
            mailbox.auth_version,
            &session.worker_id,
            Utc::now(),
        )
        .await
        .map_err(ApiError::from)?
    {
        return Ok(false);
    }

    match &message.message {
        Some(Message::IncomingLightningPayment(lightning_payment)) if send_notifications => {
            let payment_hash = hex::encode(&lightning_payment.payment_hash);
            let amount_sat = lightning_payment.amount_msat / 1000;
            let notification = build_lightning_claim_notification(payment_hash, amount_sat)?;

            tracing::info!(
                service = "mailbox_worker",
                pubkey = %mailbox.pubkey,
                checkpoint = message.checkpoint,
                amount_sat,
                notification_kind = "lightning_claim_request",
                silent = true,
                content_available = true,
                "mailbox incoming lightning payment received; sending claim push notification"
            );

            send_expo_push_notification(
                app_state.clone(),
                notification,
                Some(mailbox.pubkey.to_string()),
            )
            .await?;
        }
        Some(Message::Arkoor(arkoor)) if send_notifications && !arkoor.vtxos.is_empty() => {
            tracing::info!(
                service = "mailbox_worker",
                pubkey = %mailbox.pubkey,
                checkpoint = message.checkpoint,
                vtxo_count = arkoor.vtxos.len(),
                "mailbox arkoor message received"
            );

            for raw_vtxo in &arkoor.vtxos {
                let Some(notification) = build_receive_notification(raw_vtxo)? else {
                    continue;
                };
                let notification_kind = if notification.content_available
                    && notification.title.is_none()
                    && notification.body.is_none()
                {
                    "lightning_claim_request"
                } else {
                    "visible_receive"
                };

                tracing::info!(
                    service = "mailbox_worker",
                    pubkey = %mailbox.pubkey,
                    checkpoint = message.checkpoint,
                    notification_kind,
                    silent = notification.title.is_none() && notification.body.is_none(),
                    content_available = notification.content_available,
                    "sending mailbox push notification"
                );

                send_push_notification(
                    app_state.clone(),
                    notification,
                    Some(mailbox.pubkey.to_string()),
                )
                .await?;
            }
        }
        _ => {}
    }

    repo.update_checkpoint(
        &mailbox.pubkey,
        message.checkpoint as i64,
        mailbox.auth_version,
        &session.worker_id,
    )
    .await
    .map_err(ApiError::from)
}

fn should_suppress_catchup_notifications(last_checkpoint: i64) -> bool {
    last_checkpoint == 0
}

fn build_lightning_claim_notification(
    payment_hash: String,
    amount_sat: u64,
) -> Result<PushNotificationData, ApiError> {
    let data = serde_json::to_string(&NotificationData::LightningClaimRequest(
        LightningClaimRequestNotification {
            payment_hash,
            amount_sat,
        },
    ))
    .map_err(|e| ApiError::SerializeErr(e.to_string()))?;

    Ok(PushNotificationData {
        title: None,
        body: None,
        data,
        priority: Priority::High,
        content_available: true,
    })
}

fn build_receive_notification(raw_vtxo: &[u8]) -> Result<Option<PushNotificationData>, ApiError> {
    let mut cursor = std::io::Cursor::new(raw_vtxo);
    let vtxo: Vtxo<Full> = Vtxo::decode(&mut cursor)
        .map_err(|e| ApiError::Anyhow(anyhow::anyhow!("failed to decode mailbox vtxo: {}", e)))?;

    let amount_sats = vtxo.amount().to_sat();

    match vtxo.policy_type() {
        VtxoPolicyKind::Pubkey => Ok(Some(PushNotificationData {
            title: Some("Payment received".to_string()),
            body: Some(format!("You received {} sats via Ark.", amount_sats)),
            data: "{}".to_string(),
            priority: Priority::High,
            content_available: false,
        })),
        VtxoPolicyKind::ServerHtlcRecv | VtxoPolicyKind::ServerHtlcSend => Ok(None),
        _ => Ok(None),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::anyhow;
    use ark::test_util::VTXO_VECTORS;

    #[test]
    fn retry_delay_grows_and_caps() {
        let config = MailboxWorkerConfig {
            base_retry_delay: Duration::from_secs(5),
            max_retry_delay: Duration::from_secs(300),
            ..Default::default()
        };

        let delay_for = |failures: i32| {
            let retries = failures.saturating_add(1) as u32;
            let shift = retries.saturating_sub(1).min(16);
            let secs = cmp::min(
                config
                    .base_retry_delay
                    .as_secs()
                    .saturating_mul(1u64 << shift),
                config.max_retry_delay.as_secs(),
            );
            chrono::TimeDelta::seconds(secs as i64)
        };

        assert_eq!(delay_for(0), chrono::TimeDelta::seconds(5));
        assert_eq!(delay_for(1), chrono::TimeDelta::seconds(10));
        assert_eq!(delay_for(2), chrono::TimeDelta::seconds(20));
        assert_eq!(delay_for(10), chrono::TimeDelta::seconds(300));
    }

    #[test]
    fn invalid_argument_with_expired_message_maps_to_expired() {
        let status = Status::new(Code::InvalidArgument, "authorization expired");
        assert_eq!(
            map_tonic_status(status),
            MailboxSessionOutcome::Expired {
                reason: "authorization expired".to_string(),
            }
        );
    }

    #[test]
    fn invalid_argument_without_expired_message_maps_to_invalid_auth() {
        let status = Status::new(Code::InvalidArgument, "mailbox authorization mismatch");
        assert_eq!(
            map_tonic_status(status),
            MailboxSessionOutcome::InvalidAuth {
                reason: "mailbox authorization mismatch".to_string(),
            }
        );
    }

    #[test]
    fn suppresses_notifications_during_initial_sync_only() {
        assert!(should_suppress_catchup_notifications(0));
        assert!(!should_suppress_catchup_notifications(1));
        assert!(!should_suppress_catchup_notifications(42));
    }

    #[test]
    fn claim_renew_interval_is_clamped_below_ttl() {
        let config = MailboxWorkerConfig {
            claim_ttl: Duration::from_secs(120),
            claim_renew_interval: Duration::from_secs(90),
            ..Default::default()
        }
        .with_safe_claim_renew_interval();

        assert_eq!(config.claim_renew_interval, Duration::from_secs(60));
    }

    #[test]
    fn build_receive_notification_for_pubkey_vtxo() {
        let raw = VTXO_VECTORS.arkoor2_vtxo.serialize();

        let notification = build_receive_notification(&raw)
            .expect("pubkey vtxo should decode")
            .expect("pubkey vtxo should notify");

        assert_eq!(notification.title.as_deref(), Some("Payment received"));
        assert_eq!(
            notification.body.as_deref(),
            Some("You received 8000 sats via Ark.")
        );
    }

    #[test]
    fn build_lightning_claim_notification_for_incoming_lightning_payment() {
        let payment_hash = "00".repeat(32);
        let amount_sat = 42_000;

        let notification = build_lightning_claim_notification(payment_hash.clone(), amount_sat)
            .expect("lightning claim notification should build");

        assert_eq!(notification.title, None);
        assert_eq!(notification.body, None);
        assert_eq!(notification.priority, Priority::High);
        assert!(notification.content_available);

        let data: NotificationData =
            serde_json::from_str(&notification.data).expect("notification data should decode");
        assert!(matches!(
            data,
            NotificationData::LightningClaimRequest(LightningClaimRequestNotification {
                payment_hash: hash,
                amount_sat: amount,
            }) if hash == payment_hash && amount == amount_sat
        ));
    }

    #[test]
    fn build_receive_notification_skips_lightning_receive_vtxo() {
        let raw = VTXO_VECTORS.round2_vtxo.serialize();

        let notification =
            build_receive_notification(&raw).expect("lightning receive vtxo should decode");

        assert!(
            notification.is_none(),
            "lightning receive should use its mailbox event"
        );
    }

    #[test]
    fn build_receive_notification_skips_lightning_send_vtxo() {
        let raw = VTXO_VECTORS.arkoor_htlc_out_vtxo.serialize();

        let notification =
            build_receive_notification(&raw).expect("lightning send vtxo should decode");

        assert!(notification.is_none(), "lightning send should not notify");
    }

    #[tokio::test]
    async fn handle_session_result_does_not_stop_worker_on_session_error() {
        let pubkey = "test-pubkey".to_string();
        let mut active_sessions = HashMap::from([(
            pubkey.clone(),
            ActiveMailboxSession {
                auth_version: 1,
                cancellation_token: CancellationToken::new(),
            },
        )]);
        let result = Ok((pubkey.clone(), Err(anyhow!("session failed"))));

        let handled = MailboxWorker::<MailboxTransportUnavailable>::handle_session_result(
            result,
            &mut active_sessions,
        )
        .await;

        assert!(handled.is_ok(), "session errors should not stop the worker");
        assert!(
            !active_sessions.contains_key(&pubkey),
            "failed sessions should still be removed from the active set"
        );
    }
}
