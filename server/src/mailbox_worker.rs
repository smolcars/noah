#![allow(dead_code)]

use std::{
    cmp,
    collections::{HashMap, HashSet},
    future::Future,
    sync::{
        Arc, Mutex, MutexGuard,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

use anyhow::Result;
use ark::{ProtocolEncoding, Vtxo, vtxo::Full, vtxo::policy::VtxoPolicyKind};
use async_trait::async_trait;
use bitcoin::Amount;
use chrono::Utc;
use expo_push_notification_client::Priority;
use futures_util::StreamExt;
use server_rpc::{
    ServerConnection,
    client::ArkServiceInterceptor,
    mailbox::MailboxServiceClient,
    protos::mailbox_server::{MailboxMessage, MailboxRequest, mailbox_message::Message},
    tonic::{
        Code, Status, service::interceptor::InterceptedService, transport::Channel as GrpcChannel,
    },
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
    config::ARK_USER_AGENT,
    db::{
        fiat_rate_repo::{FiatRate, FiatRateRepository},
        mailbox_authorization_repo::{ActiveMailboxAuthorization, MailboxAuthorizationRepository},
    },
    errors::ApiError,
    push::{PushNotificationData, send_push_notification},
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
    /// Mailbox streams that share one connection to the Ark server.
    pub max_streams_per_channel: usize,
    /// Connections to the Ark server the worker may hold open at once.
    pub max_channels: usize,
}

const DEFAULT_MAX_STREAMS_PER_CHANNEL: usize = 100;
/// How long shutdown waits for cancelled sessions before aborting them.
const SHUTDOWN_GRACE: Duration = Duration::from_secs(5);
/// How long a new connection to the Ark server may take, handshake and
/// ArkInfo included. bark-server-rpc sets no connect timeout, and sessions
/// queue behind a dial.
const ARK_DIAL_TIMEOUT: Duration = Duration::from_secs(30);

impl Default for MailboxWorkerConfig {
    fn default() -> Self {
        let concurrency_limit = 250;
        Self {
            concurrency_limit,
            scan_interval: Duration::from_secs(15),
            batch_size: 100,
            base_retry_delay: Duration::from_secs(5),
            max_retry_delay: Duration::from_secs(300),
            claim_ttl: Duration::from_secs(120),
            claim_renew_interval: Duration::from_secs(30),
            stream_idle_reconnect: Duration::from_secs(600),
            max_streams_per_channel: DEFAULT_MAX_STREAMS_PER_CHANNEL,
            max_channels: concurrency_limit.div_ceil(DEFAULT_MAX_STREAMS_PER_CHANNEL),
        }
    }
}

impl MailboxWorkerConfig {
    pub fn from_env() -> Self {
        let default = Self::default();
        let concurrency_limit = env_usize(
            "MAILBOX_WORKER_CONCURRENCY_LIMIT",
            default.concurrency_limit,
        );
        let max_streams_per_channel = env_usize(
            "MAILBOX_WORKER_MAX_STREAMS_PER_CHANNEL",
            default.max_streams_per_channel,
        )
        .max(1);
        // By default there is room for every session the worker may run.
        let max_channels = env_usize(
            "MAILBOX_WORKER_MAX_CHANNELS",
            concurrency_limit.div_ceil(max_streams_per_channel),
        )
        .max(1);
        Self {
            concurrency_limit,
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
            max_streams_per_channel,
            max_channels,
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
            max_streams_per_channel = self.max_streams_per_channel,
            max_channels = self.max_channels,
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

    /// Connections to the Ark server the transport holds open, for the gauges log.
    fn open_channels(&self) -> usize {
        0
    }
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

    /// Runs until `shutdown` is cancelled, then stops every session and
    /// releases this worker's leases.
    pub async fn run(&self, shutdown: CancellationToken) -> Result<()> {
        let mut join_set = JoinSet::new();
        let mut active_sessions = HashMap::new();
        let mut claim_renewal = interval_at(
            Instant::now() + self.config.claim_renew_interval,
            self.config.claim_renew_interval,
        );

        while !shutdown.is_cancelled() {
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
                tokio::select! {
                    _ = shutdown.cancelled() => {}
                    _ = sleep(self.config.base_retry_delay) => {}
                }
                continue;
            }

            tokio::select! {
                _ = shutdown.cancelled() => {}
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
                    self.log_gauges(active_sessions.len()).await;
                }
                _ = sleep(self.config.scan_interval) => {}
            }
        }

        self.shut_down(join_set, active_sessions).await
    }

    /// Cancels every session, gives them a moment to finish, then frees all
    /// leases this worker still holds so a replacement can claim them at once.
    async fn shut_down(
        &self,
        mut join_set: JoinSet<(String, Result<()>)>,
        mut active_sessions: HashMap<String, ActiveMailboxSession>,
    ) -> Result<()> {
        tracing::info!(
            service = "mailbox_worker",
            active_sessions = active_sessions.len(),
            "mailbox worker shutting down"
        );
        for session in active_sessions.values() {
            session.cancellation_token.cancel();
        }

        let drained = tokio::time::timeout(SHUTDOWN_GRACE, async {
            while let Some(result) = join_set.join_next().await {
                if let Err(error) = Self::handle_session_result(result, &mut active_sessions).await
                {
                    tracing::error!(
                        service = "mailbox_worker",
                        error = %error,
                        "mailbox session join failed"
                    );
                }
            }
        })
        .await;
        if drained.is_err() {
            tracing::warn!(
                service = "mailbox_worker",
                remaining_sessions = join_set.len(),
                grace_secs = SHUTDOWN_GRACE.as_secs(),
                "mailbox sessions did not stop in time; aborting them"
            );
            join_set.shutdown().await;
        }

        let released = MailboxAuthorizationRepository::new(&self.app_state.db_pool)
            .release_all_claims(&self.worker_id)
            .await?;
        tracing::info!(
            service = "mailbox_worker",
            released_leases = released,
            "mailbox worker stopped"
        );
        Ok(())
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

    async fn log_gauges(&self, active_sessions: usize) {
        let repo = MailboxAuthorizationRepository::new(&self.app_state.db_pool);
        match repo
            .count_claimable_without_push_target(Utc::now().timestamp())
            .await
        {
            Ok(claimable_without_push_target) => tracing::info!(
                service = "mailbox_worker",
                active_sessions,
                open_channels = self.transport.open_channels(),
                claimable_without_push_target,
                "mailbox worker gauges"
            ),
            Err(error) => tracing::warn!(
                service = "mailbox_worker",
                error = %error,
                "failed to count mailboxes without a push target"
            ),
        }
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
    if !repo
        .claim_is_active(
            &mailbox.pubkey,
            mailbox.auth_version,
            &worker_id,
            Utc::now(),
        )
        .await?
    {
        return Ok(());
    }

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

type MailboxClient = MailboxServiceClient<InterceptedService<GrpcChannel, ArkServiceInterceptor>>;

/// Shares a few HTTP/2 connections to the Ark server across mailbox sessions.
///
/// A session takes a slot on the first channel with room and gives it back
/// when its lease drops. A new channel is dialled only when every open one is
/// full. Channels stay in the pool for good: when a connection dies, tonic
/// dials it again on the channel's next request, so a session that fails
/// retries on the same channel after the scheduler's backoff.
struct ChannelPool<C> {
    max_streams_per_channel: usize,
    max_channels: usize,
    dial_timeout: Duration,
    channels: Mutex<Vec<Arc<PooledChannel<C>>>>,
    /// Held while dialling, so sessions that start together share one dial.
    /// Remembers when the last dial failed.
    dial: tokio::sync::Mutex<Option<Instant>>,
}

struct PooledChannel<C> {
    client: C,
    streams: AtomicUsize,
}

/// One session's slot on a pooled channel. Dropping it frees the slot.
struct ChannelLease<C> {
    channel: Arc<PooledChannel<C>>,
}

impl<C> ChannelLease<C> {
    fn client(&self) -> &C {
        &self.channel.client
    }
}

impl<C> Drop for ChannelLease<C> {
    fn drop(&mut self) {
        self.channel.streams.fetch_sub(1, Ordering::Relaxed);
    }
}

impl<C> ChannelPool<C> {
    fn new(max_streams_per_channel: usize, max_channels: usize, dial_timeout: Duration) -> Self {
        Self {
            max_streams_per_channel,
            max_channels,
            dial_timeout,
            channels: Mutex::new(Vec::new()),
            dial: tokio::sync::Mutex::new(None),
        }
    }

    fn open(&self) -> MutexGuard<'_, Vec<Arc<PooledChannel<C>>>> {
        self.channels.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn open_channels(&self) -> usize {
        self.open().len()
    }

    fn try_borrow(&self) -> Option<ChannelLease<C>> {
        let channels = self.open();
        let channel = channels.iter().find(|channel| {
            channel.streams.load(Ordering::Relaxed) < self.max_streams_per_channel
        })?;
        channel.streams.fetch_add(1, Ordering::Relaxed);
        Some(ChannelLease {
            channel: channel.clone(),
        })
    }

    async fn borrow<F, Fut>(&self, dial: F) -> Result<ChannelLease<C>>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = Result<C>>,
    {
        if let Some(lease) = self.try_borrow() {
            return Ok(lease);
        }

        let waiting_since = Instant::now();
        let mut last_dial_failure = self.dial.lock().await;
        // Another session may have dialled while this one waited for the lock.
        if let Some(lease) = self.try_borrow() {
            return Ok(lease);
        }
        // Fail with the dial this session waited on instead of queueing another.
        if last_dial_failure.is_some_and(|failed_at| failed_at >= waiting_since) {
            anyhow::bail!("ark server dial failed while this session waited for it");
        }
        let open_channels = self.open_channels();
        if open_channels >= self.max_channels {
            tracing::warn!(
                service = "mailbox_worker",
                max_channels = self.max_channels,
                max_streams_per_channel = self.max_streams_per_channel,
                "every mailbox channel to ark server is full"
            );
            anyhow::bail!("all {} ark server channels are full", self.max_channels);
        }

        let dialled = tokio::time::timeout(self.dial_timeout, dial())
            .await
            .unwrap_or_else(|_| {
                Err(anyhow::anyhow!(
                    "ark server dial timed out after {:?}",
                    self.dial_timeout
                ))
            });
        let client = match dialled {
            Ok(client) => client,
            Err(error) => {
                *last_dial_failure = Some(Instant::now());
                return Err(error);
            }
        };
        let channel = Arc::new(PooledChannel {
            client,
            streams: AtomicUsize::new(1),
        });
        self.open().push(channel.clone());
        tracing::info!(
            service = "mailbox_worker",
            open_channels = open_channels + 1,
            "opened mailbox channel to ark server"
        );
        Ok(ChannelLease { channel })
    }
}

async fn dial_ark_server(app_state: &AppState) -> Result<MailboxClient> {
    let network = app_state.config.network()?;
    let connection = ServerConnection::builder()
        .address(&app_state.config.ark_server_url)
        .network(network)
        .user_agent(ARK_USER_AGENT)
        .connect()
        .await?;
    Ok(connection.mailbox_client)
}

pub struct Beta8MailboxTransport {
    channels: ChannelPool<MailboxClient>,
}

impl Beta8MailboxTransport {
    pub fn new(config: &MailboxWorkerConfig) -> Self {
        Self {
            channels: ChannelPool::new(
                config.max_streams_per_channel,
                config.max_channels,
                ARK_DIAL_TIMEOUT,
            ),
        }
    }
}

#[async_trait]
impl MailboxTransport for Beta8MailboxTransport {
    fn open_channels(&self) -> usize {
        self.channels.open_channels()
    }

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

        let mailbox_id = match decode_hex_bytes("mailbox_id", &mailbox.mailbox_id) {
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
            // The idle reconnect below comes back here, so it borrows from the
            // pool again instead of dialling. The previous lease was dropped at
            // the end of the last pass.
            let channel = tokio::select! {
                _ = session.cancellation_token.cancelled() => {
                    return Ok(MailboxSessionOutcome::Completed);
                }
                channel = self.channels.borrow(|| dial_ark_server(&app_state)) => channel?,
            };
            let mut client = channel.client().clone();

            let mut catchup_count = 0u64;
            loop {
                if session.cancellation_token.is_cancelled() {
                    return Ok(MailboxSessionOutcome::Completed);
                }

                let read_response = client
                    .read_mailbox(MailboxRequest {
                        mailbox_id: mailbox_id.clone(),
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
                    mailbox_id: mailbox_id.clone(),
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

pub(crate) async fn process_mailbox_message(
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
            let usd_rate = load_notification_usd_rate(app_state).await;
            let notification =
                build_lightning_claim_notification(payment_hash, amount_sat, usd_rate.as_ref())?;

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

            send_push_notification(
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

            // captaind stores each post (all VTXOs of one payment) under one
            // checkpoint, so one arkoor message is one payment and gets one push.
            let (amount, vtxo_ids) = incoming_ark_payment(&arkoor.vtxos)?;
            let usd_rate = load_notification_usd_rate(app_state).await;
            if let Some(notification) =
                build_receive_notification(amount, &vtxo_ids, usd_rate.as_ref())
            {
                tracing::info!(
                    service = "mailbox_worker",
                    pubkey = %mailbox.pubkey,
                    checkpoint = message.checkpoint,
                    vtxo_count = arkoor.vtxos.len(),
                    amount_sat = amount.to_sat(),
                    notification_kind = "visible_receive",
                    silent = false,
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

async fn load_notification_usd_rate(app_state: &AppState) -> Option<FiatRate> {
    match FiatRateRepository::new(&app_state.db_pool)
        .get_latest_rates()
        .await
    {
        Ok(rates) => rates.into_iter().find(|rate| rate.currency == "USD"),
        Err(error) => {
            tracing::warn!(%error, "Failed to load USD rate for receive notification");
            None
        }
    }
}

fn notification_usd_amount(amount_sat: u64, usd_rate: Option<&FiatRate>) -> Option<String> {
    let rate = usd_rate?;
    let age = Utc::now().signed_duration_since(rate.observed_at);
    // Allow missed five-minute refreshes without showing an outdated dollar estimate.
    if age < chrono::TimeDelta::zero()
        || age > chrono::TimeDelta::minutes(15)
        || !rate.btc_price.is_finite()
        || rate.btc_price <= 0.0
    {
        return None;
    }

    let amount = amount_sat as f64 / 100_000_000.0 * rate.btc_price;
    amount.is_finite().then(|| format!("{amount:.2}"))
}

fn build_lightning_claim_notification(
    payment_hash: String,
    amount_sat: u64,
    usd_rate: Option<&FiatRate>,
) -> Result<PushNotificationData, ApiError> {
    let data = serde_json::to_string(&NotificationData::LightningClaimRequest(
        LightningClaimRequestNotification {
            payment_hash,
            amount_sat,
            amount_usd: notification_usd_amount(amount_sat, usd_rate),
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

/// One arkoor message's payment (`Pubkey`) VTXOs: their total and IDs. Lightning
/// HTLC VTXOs are skipped: incoming Lightning payments have their own mailbox event.
fn incoming_ark_payment(raw_vtxos: &[Vec<u8>]) -> Result<(Amount, Vec<String>), ApiError> {
    raw_vtxos
        .iter()
        .try_fold((Amount::ZERO, Vec::new()), |(total, mut ids), raw_vtxo| {
            let mut cursor = std::io::Cursor::new(raw_vtxo);
            let vtxo: Vtxo<Full> = Vtxo::decode(&mut cursor).map_err(|e| {
                ApiError::Anyhow(anyhow::anyhow!("failed to decode mailbox vtxo: {}", e))
            })?;

            match vtxo.policy_type() {
                VtxoPolicyKind::Pubkey => {
                    let total = total.checked_add(vtxo.amount()).ok_or_else(|| {
                        ApiError::Anyhow(anyhow::anyhow!("mailbox vtxo amounts overflow"))
                    })?;
                    ids.push(vtxo.id().to_string());
                    Ok((total, ids))
                }
                _ => Ok((total, ids)),
            }
        })
}

/// The data names the received VTXOs so a tap on the notification can open the
/// payment they arrived in. Apps that don't know the type ignore it.
fn build_receive_notification(
    amount: Amount,
    vtxo_ids: &[String],
    usd_rate: Option<&FiatRate>,
) -> Option<PushNotificationData> {
    if amount == Amount::ZERO {
        return None;
    }

    let amount_sats = amount.to_sat();
    let amount = match notification_usd_amount(amount_sats, usd_rate) {
        Some(usd) => format!("{amount_sats} sats (≈ US${usd})"),
        None => format!("{amount_sats} sats"),
    };

    Some(PushNotificationData {
        title: Some("Payment received".to_string()),
        body: Some(format!("You received {amount} via Ark.")),
        data: serde_json::json!({
            "notification_type": "ark_payment_received",
            "vtxo_ids": vtxo_ids,
        })
        .to_string(),
        priority: Priority::High,
        content_available: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::anyhow;
    use ark::test_util::VTXO_VECTORS;

    fn usd_rate(btc_price: f64) -> FiatRate {
        FiatRate {
            currency: "USD".to_string(),
            rate_date: Utc::now().date_naive(),
            btc_price,
            observed_at: Utc::now(),
            source: "test".to_string(),
        }
    }

    #[test]
    fn notification_usd_amount_converts_sats_and_rounds_to_cents() {
        let rate = usd_rate(123_456.78);
        assert_eq!(
            notification_usd_amount(8_000, Some(&rate)).as_deref(),
            Some("9.88")
        );
        assert_eq!(
            notification_usd_amount(0, Some(&rate)).as_deref(),
            Some("0.00")
        );
    }

    #[test]
    fn notification_usd_amount_omits_missing_stale_and_invalid_rates() {
        assert_eq!(notification_usd_amount(8_000, None), None);
        let mut rate = usd_rate(100_000.0);
        rate.observed_at -= chrono::TimeDelta::minutes(16);
        assert_eq!(notification_usd_amount(8_000, Some(&rate)), None);
        rate.observed_at = Utc::now() + chrono::TimeDelta::minutes(1);
        assert_eq!(notification_usd_amount(8_000, Some(&rate)), None);

        for price in [0.0, -1.0, f64::NAN, f64::INFINITY] {
            assert_eq!(notification_usd_amount(8_000, Some(&usd_rate(price))), None);
        }
    }

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

    fn receive_notification(
        vtxos: &[&Vtxo<Full>],
        usd_rate: Option<&FiatRate>,
    ) -> Option<PushNotificationData> {
        let raw: Vec<Vec<u8>> = vtxos.iter().map(|vtxo| vtxo.serialize()).collect();
        let (amount, vtxo_ids) = incoming_ark_payment(&raw).expect("test vtxos should decode");
        build_receive_notification(amount, &vtxo_ids, usd_rate)
    }

    #[test]
    fn build_receive_notification_for_pubkey_vtxo() {
        let notification = receive_notification(&[&VTXO_VECTORS.arkoor2_vtxo], None)
            .expect("pubkey vtxo should notify");

        assert_eq!(notification.title.as_deref(), Some("Payment received"));
        assert_eq!(
            notification.body.as_deref(),
            Some("You received 8000 sats via Ark.")
        );
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&notification.data).unwrap(),
            serde_json::json!({
                "notification_type": "ark_payment_received",
                "vtxo_ids": [VTXO_VECTORS.arkoor2_vtxo.id().to_string()],
            })
        );
        assert_eq!(notification.priority, Priority::High);
        assert!(!notification.content_available);
    }

    #[test]
    fn receive_notification_reaches_unified_push_with_its_title_and_body() {
        let notification =
            receive_notification(&[&VTXO_VECTORS.arkoor2_vtxo], Some(&usd_rate(100_000.0)))
                .expect("pubkey vtxo should notify");

        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&crate::push::unified_push_body(
                &notification
            ))
            .unwrap(),
            serde_json::json!({
                "notification_type": "visible_notification",
                "title": "Payment received",
                "body": "You received 8000 sats (≈ US$8.00) via Ark.",
                "data": notification.data,
            })
        );
    }

    #[test]
    fn build_receive_notification_includes_usd_when_rate_is_fresh() {
        let notification =
            receive_notification(&[&VTXO_VECTORS.arkoor2_vtxo], Some(&usd_rate(100_000.0)))
                .expect("pubkey vtxo should notify");

        assert_eq!(
            notification.body.as_deref(),
            Some("You received 8000 sats (≈ US$8.00) via Ark.")
        );
    }

    #[test]
    fn build_receive_notification_sums_a_multi_vtxo_payment() {
        let vtxos = [
            &VTXO_VECTORS.arkoor2_vtxo,
            &VTXO_VECTORS.round1_vtxo,
            &VTXO_VECTORS.arkoor3_vtxo,
        ];

        let notification = receive_notification(&vtxos, None).expect("pubkey vtxos should notify");
        assert_eq!(notification.title.as_deref(), Some("Payment received"));
        assert_eq!(
            notification.body.as_deref(),
            Some("You received 28000 sats via Ark.")
        );

        let notification = receive_notification(&vtxos, Some(&usd_rate(100_000.0)))
            .expect("pubkey vtxos should notify");
        assert_eq!(
            notification.body.as_deref(),
            Some("You received 28000 sats (≈ US$28.00) via Ark.")
        );
    }

    #[test]
    fn build_receive_notification_counts_only_pubkey_vtxos() {
        let notification = receive_notification(
            &[
                &VTXO_VECTORS.round2_vtxo,
                &VTXO_VECTORS.arkoor2_vtxo,
                &VTXO_VECTORS.arkoor_htlc_out_vtxo,
            ],
            None,
        )
        .expect("the pubkey vtxo should notify");

        assert_eq!(
            notification.body.as_deref(),
            Some("You received 8000 sats via Ark.")
        );
        let data: serde_json::Value = serde_json::from_str(&notification.data).unwrap();
        assert_eq!(
            data["vtxo_ids"],
            serde_json::json!([VTXO_VECTORS.arkoor2_vtxo.id().to_string()])
        );
    }

    #[test]
    fn build_receive_notification_skips_htlc_only_and_empty_messages() {
        let htlcs = [
            &VTXO_VECTORS.round2_vtxo,
            &VTXO_VECTORS.arkoor_htlc_out_vtxo,
        ];
        assert!(receive_notification(&htlcs, None).is_none());
        assert!(receive_notification(&[], None).is_none());
    }

    #[test]
    fn incoming_ark_payment_fails_when_any_vtxo_fails_to_decode() {
        let raw = vec![VTXO_VECTORS.arkoor2_vtxo.serialize(), vec![0xff; 4]];
        assert!(incoming_ark_payment(&raw).is_err());
    }

    #[test]
    fn build_lightning_claim_notification_for_incoming_lightning_payment() {
        let payment_hash = "00".repeat(32);
        let amount_sat = 42_000;

        let notification = build_lightning_claim_notification(
            payment_hash.clone(),
            amount_sat,
            Some(&usd_rate(100_000.0)),
        )
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
                amount_usd,
            }) if hash == payment_hash && amount == amount_sat && amount_usd.as_deref() == Some("42.00")
        ));
    }

    #[test]
    fn lightning_claim_notification_supports_missing_usd_amount() {
        let notification = build_lightning_claim_notification("00".repeat(32), 42_000, None)
            .expect("lightning claim notification should build without USD");
        let payload: serde_json::Value = serde_json::from_str(&notification.data).unwrap();
        assert!(payload.get("amount_usd").is_none());

        let legacy: NotificationData = serde_json::from_value(payload).unwrap();
        assert!(matches!(
            legacy,
            NotificationData::LightningClaimRequest(LightningClaimRequestNotification {
                amount_usd: None,
                ..
            })
        ));
    }

    #[test]
    fn build_receive_notification_skips_lightning_receive_vtxo() {
        let notification = receive_notification(&[&VTXO_VECTORS.round2_vtxo], None);

        assert!(
            notification.is_none(),
            "lightning receive should use its mailbox event"
        );
    }

    #[test]
    fn build_receive_notification_skips_lightning_send_vtxo() {
        let notification = receive_notification(&[&VTXO_VECTORS.arkoor_htlc_out_vtxo], None);

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

    #[tokio::test]
    async fn channel_pool_frees_slots_and_caps_channels() {
        let pool = ChannelPool::new(1, 1, ARK_DIAL_TIMEOUT);
        let dials = AtomicUsize::new(0);
        let dial = || async {
            dials.fetch_add(1, Ordering::SeqCst);
            Ok(())
        };

        let lease = pool.borrow(dial).await.expect("first borrow dials");
        assert!(pool.borrow(dial).await.is_err(), "the only channel is full");
        drop(lease);
        let _lease = pool.borrow(dial).await.expect("the slot was freed");
        assert_eq!(dials.load(Ordering::SeqCst), 1);
        assert_eq!(pool.open_channels(), 1);
    }

    #[tokio::test]
    async fn channel_pool_waiters_share_a_failed_dial() {
        let pool = ChannelPool::<()>::new(100, 10, ARK_DIAL_TIMEOUT);
        let dials = AtomicUsize::new(0);
        let dial = || async {
            dials.fetch_add(1, Ordering::SeqCst);
            sleep(Duration::from_millis(50)).await;
            Err(anyhow!("connection refused"))
        };

        let (first, second) = tokio::join!(pool.borrow(dial), pool.borrow(dial));
        assert!(first.is_err() && second.is_err());
        assert_eq!(dials.load(Ordering::SeqCst), 1);

        // A session that starts after the failure dials again.
        assert!(pool.borrow(dial).await.is_err());
        assert_eq!(dials.load(Ordering::SeqCst), 2);
        assert_eq!(pool.open_channels(), 0);
    }

    #[tokio::test]
    async fn channel_pool_gives_up_on_a_stalled_dial() {
        let dial_timeout = Duration::from_millis(50);
        let pool = ChannelPool::<()>::new(100, 10, dial_timeout);
        let dials = AtomicUsize::new(0);
        let dial = || async {
            dials.fetch_add(1, Ordering::SeqCst);
            std::future::pending().await
        };

        // The waiter queued behind the stalled dial fails with it instead of
        // dialling again.
        let started = Instant::now();
        let (first, second) = tokio::join!(pool.borrow(dial), pool.borrow(dial));
        assert!(started.elapsed() >= dial_timeout);
        let error = first.err().expect("the stalled dial times out");
        assert!(error.to_string().contains("timed out"), "{error}");
        assert!(second.is_err());
        assert_eq!(dials.load(Ordering::SeqCst), 1);
        assert_eq!(pool.open_channels(), 0);
    }
}
