use std::sync::Arc;

use anyhow::Result;
use server::{
    build_app_state,
    config::Config,
    mailbox_worker::{Beta8MailboxTransport, MailboxWorker, MailboxWorkerConfig},
    utils::shutdown_signal,
};
use tokio_util::sync::CancellationToken;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[tokio::main]
async fn main() -> Result<()> {
    let config = Config::load()?;

    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "server=debug,tower_http=debug".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    let app_state = build_app_state(config).await?;
    let mailbox_worker_config = MailboxWorkerConfig::from_env();
    mailbox_worker_config.log();
    let transport = Arc::new(Beta8MailboxTransport::new(&mailbox_worker_config));
    let worker = MailboxWorker::new(app_state, transport, mailbox_worker_config);

    let shutdown = CancellationToken::new();
    let signal_shutdown = shutdown.clone();
    tokio::spawn(async move {
        shutdown_signal().await;
        signal_shutdown.cancel();
    });

    worker.run(shutdown).await
}
