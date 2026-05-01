#![allow(dead_code)]

use std::{cmp, collections::HashSet, sync::Arc, time::Duration};

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
use uuid::Uuid;

use crate::{
    AppState,
    db::mailbox_authorization_repo::{ActiveMailboxAuthorization, MailboxAuthorizationRepository},
    errors::ApiError,
    push::{PushNotificationData, send_push_notification},
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
}

impl Default for MailboxWorkerConfig {
    fn default() -> Self {
        Self {
            concurrency_limit: 50,
            scan_interval: Duration::from_secs(15),
            batch_size: 100,
            base_retry_delay: Duration::from_secs(5),
            max_retry_delay: Duration::from_secs(300),
            claim_ttl: Duration::from_secs(30),
            claim_renew_interval: Duration::from_secs(5),
        }
    }
}

#[derive(Debug, Clone)]
pub struct MailboxSessionContext {
    pub worker_id: String,
    pub claim_ttl: Duration,
    pub claim_renew_interval: Duration,
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
        let mut active_pubkeys = HashSet::new();

        loop {
            while let Some(result) = join_set.try_join_next() {
                Self::handle_session_result(result, &mut active_pubkeys).await?;
            }

            let _ = self
                .schedule_runnable_sessions(&mut join_set, &mut active_pubkeys)
                .await?;

            tokio::select! {
                result = join_set.join_next(), if !join_set.is_empty() => {
                    if let Some(result) = result {
                        Self::handle_session_result(result, &mut active_pubkeys).await?;
                    }
                }
                _ = sleep(self.config.scan_interval) => {}
            }
        }
    }

    pub async fn run_once(&self) -> Result<usize> {
        let mut join_set = JoinSet::new();
        let mut active_pubkeys = HashSet::new();
        let scheduled = self
            .schedule_runnable_sessions(&mut join_set, &mut active_pubkeys)
            .await?;

        while let Some(result) = join_set.join_next().await {
            Self::handle_session_result(result, &mut active_pubkeys).await?;
        }

        Ok(scheduled)
    }

    async fn schedule_runnable_sessions(
        &self,
        join_set: &mut JoinSet<(String, Result<()>)>,
        active_pubkeys: &mut HashSet<String>,
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
            if active_pubkeys.contains(&mailbox.pubkey) {
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

            active_pubkeys.insert(pubkey.clone());
            join_set.spawn(async move {
                let _permit = permit;
                let result =
                    process_mailbox_session(app_state, transport, config, worker_id, mailbox).await;
                (pubkey, result)
            });
            scheduled += 1;
        }

        Ok(scheduled)
    }

    async fn handle_session_result(
        result: std::result::Result<(String, Result<()>), tokio::task::JoinError>,
        active_pubkeys: &mut HashSet<String>,
    ) -> Result<()> {
        let (pubkey, session_result) = result?;
        active_pubkeys.remove(&pubkey);
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
) -> Result<()>
where
    T: MailboxTransport + 'static,
{
    let repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    let is_connected = repo
        .mark_connected(&mailbox.pubkey, mailbox.auth_version, &worker_id)
        .await?;
    if !is_connected {
        return Ok(());
    }

    let session = MailboxSessionContext {
        worker_id: worker_id.clone(),
        claim_ttl: config.claim_ttl,
        claim_renew_interval: config.claim_renew_interval,
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

    repo.release_claim(&mailbox.pubkey, &worker_id).await?;

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

        let network = app_state.config.network()?;
        let mut client: MailboxServiceClient<_> = ServerConnection::builder()
            .address(&app_state.config.ark_server_url)
            .network(network)
            .connect()
            .await?
            .mailbox_client;

        let mut checkpoint = mailbox.last_checkpoint as u64;
        let suppress_catchup_notifications =
            should_suppress_catchup_notifications(mailbox.last_checkpoint);
        let mut claim_renewal = interval_at(
            Instant::now() + session.claim_renew_interval,
            session.claim_renew_interval,
        );

        loop {
            if !renew_mailbox_claim(&app_state, &mailbox, &session).await? {
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
            }
        }

        let stream_response = client
            .subscribe_mailbox(MailboxRequest {
                unblinded_id,
                authorization: Some(authorization),
                checkpoint,
            })
            .await;

        let mut stream = match stream_response {
            Ok(response) => response.into_inner(),
            Err(status) => return Ok(map_tonic_status(status)),
        };

        loop {
            tokio::select! {
                _ = claim_renewal.tick() => {
                    if !renew_mailbox_claim(&app_state, &mailbox, &session).await? {
                        return Ok(MailboxSessionOutcome::Completed);
                    }
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
    if !renew_mailbox_claim(app_state, mailbox, session)
        .await
        .map_err(ApiError::from)?
    {
        return Ok(false);
    }

    match &message.message {
        Some(Message::Arkoor(arkoor)) if send_notifications && !arkoor.vtxos.is_empty() => {
            for raw_vtxo in &arkoor.vtxos {
                let Some(notification) = build_receive_notification(raw_vtxo)? else {
                    continue;
                };

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

    let repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
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

fn build_receive_notification(raw_vtxo: &[u8]) -> Result<Option<PushNotificationData>, ApiError> {
    let mut cursor = std::io::Cursor::new(raw_vtxo);
    let vtxo: Vtxo<Full> = Vtxo::decode(&mut cursor)
        .map_err(|e| ApiError::Anyhow(anyhow::anyhow!("failed to decode mailbox vtxo: {}", e)))?;

    let amount_sats = vtxo.amount().to_sat();

    let body = match vtxo.policy_type() {
        VtxoPolicyKind::Pubkey => Some(format!("You received {} sats via Ark.", amount_sats)),
        VtxoPolicyKind::ServerHtlcRecv => {
            Some(format!("You received {} sats via Lightning.", amount_sats))
        }
        VtxoPolicyKind::ServerHtlcSend => None,
        _ => None,
    };

    Ok(body.map(|body| PushNotificationData {
        title: Some("Payment received".to_string()),
        body: Some(body),
        data: "{}".to_string(),
        priority: Priority::High,
        content_available: false,
    }))
}

async fn renew_mailbox_claim(
    app_state: &AppState,
    mailbox: &ActiveMailboxAuthorization,
    session: &MailboxSessionContext,
) -> Result<bool> {
    let now = Utc::now();
    let lease_expires_at = now + chrono::TimeDelta::from_std(session.claim_ttl)?;
    let repo = MailboxAuthorizationRepository::new(&app_state.db_pool);
    repo.renew_claim(
        &mailbox.pubkey,
        mailbox.auth_version,
        &session.worker_id,
        now,
        lease_expires_at,
    )
    .await
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
    fn build_receive_notification_for_lightning_receive_vtxo() {
        let raw = VTXO_VECTORS.round2_vtxo.serialize();

        let notification = build_receive_notification(&raw)
            .expect("lightning receive vtxo should decode")
            .expect("lightning receive vtxo should notify");

        assert_eq!(notification.title.as_deref(), Some("Payment received"));
        assert_eq!(
            notification.body.as_deref(),
            Some("You received 10000 sats via Lightning.")
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
        let mut active_pubkeys = HashSet::from([pubkey.clone()]);
        let result = Ok((pubkey.clone(), Err(anyhow!("session failed"))));

        let handled = MailboxWorker::<MailboxTransportUnavailable>::handle_session_result(
            result,
            &mut active_pubkeys,
        )
        .await;

        assert!(handled.is_ok(), "session errors should not stop the worker");
        assert!(
            !active_pubkeys.contains(&pubkey),
            "failed sessions should still be removed from the active set"
        );
    }
}
