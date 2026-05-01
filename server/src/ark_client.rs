use crate::{
    AppState,
    notification_coordinator::{NotificationCoordinator, NotificationRequest},
    types::NotificationRequestData,
};

use bitcoin::hex::DisplayHex;
use expo_push_notification_client::Priority;
use server_rpc::{ServerConnection, protos::Empty};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const POLL_INTERVAL: Duration = Duration::from_secs(30);

pub async fn connect_to_ark_server(
    app_state: AppState,
    ark_server_url: String,
) -> anyhow::Result<()> {
    const INITIAL_RETRY_DELAY: Duration = Duration::from_secs(2);
    const MAX_RETRY_DELAY: Duration = Duration::from_secs(30);

    let mut retry_delay = INITIAL_RETRY_DELAY;

    loop {
        match establish_connection_and_process(&app_state, &ark_server_url).await {
            Ok(_) => {
                tracing::warn!(
                    service = "ark_client",
                    event = "connection_ended",
                    "reconnecting"
                );
                retry_delay = INITIAL_RETRY_DELAY;
            }
            Err(e) => {
                tracing::warn!(
                    service = "ark_client",
                    event = "connection_failed",
                    error = %format!("{e:#}"),
                    "failed to connect"
                );
            }
        }

        tracing::info!(
            service = "ark_client",
            event = "retry_scheduled",
            delay_secs = retry_delay.as_secs(),
            "retrying"
        );
        tokio::time::sleep(retry_delay).await;

        // Exponential backoff with max limit
        retry_delay = std::cmp::min(retry_delay * 2, MAX_RETRY_DELAY);
    }
}

async fn establish_connection_and_process(
    app_state: &AppState,
    ark_server_url: &str,
) -> anyhow::Result<()> {
    let network = app_state.config.network()?;
    let connection = ServerConnection::builder()
        .address(ark_server_url)
        .network(network)
        .connect()
        .await
        .map_err(|e| anyhow::anyhow!("Failed to connect: {e:#}"))?;
    let mut client = connection.client;

    tracing::info!(
        service = "ark_client",
        event = "connected",
        "connected to ark server"
    );

    let info = client.get_ark_info(Empty {}).await?.into_inner();

    tracing::info!(
        service = "ark_client",
        event = "ark_info",
        server_pubkey = %info.server_pubkey.to_lower_hex_string(),
        "received ark server info"
    );

    let maintenance_interval_rounds = app_state.config.maintenance_interval_rounds;

    tracing::info!(
        service = "ark_client",
        event = "polling_started",
        poll_interval_secs = POLL_INTERVAL.as_secs(),
        maintenance_interval_rounds = maintenance_interval_rounds,
        "polling for next round time"
    );

    loop {
        tokio::time::sleep(POLL_INTERVAL).await;

        let response = client.next_round_time(Empty {}).await?;
        let next_round_ts = response.into_inner().timestamp;

        let last_ts = app_state
            .maintenance_store
            .get_last_round_timestamp()
            .await?;
        let counter = app_state.maintenance_store.get_round_counter().await?;
        let advance_secs = app_state.config.maintenance_notification_advance_secs;
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);

        let action = evaluate_maintenance(
            next_round_ts,
            last_ts,
            counter,
            maintenance_interval_rounds,
            advance_secs,
            now,
        );

        match action {
            MaintenanceAction::NoChange => continue,
            MaintenanceAction::RoundDetected => {
                app_state
                    .maintenance_store
                    .set_last_round_timestamp(next_round_ts)
                    .await?;
                let counter = app_state
                    .maintenance_store
                    .increment_round_counter()
                    .await?;
                tracing::info!(
                    service = "ark_client",
                    event = "round_detected",
                    next_round_ts = next_round_ts,
                    counter = counter,
                    "new round detected"
                );
            }
            MaintenanceAction::TooClose => {
                app_state
                    .maintenance_store
                    .set_last_round_timestamp(next_round_ts)
                    .await?;
                app_state
                    .maintenance_store
                    .increment_round_counter()
                    .await?;
                tracing::info!(
                    service = "ark_client",
                    event = "maintenance_skipped",
                    next_round_ts = next_round_ts,
                    advance_secs = advance_secs,
                    "next round too close, skipping to next one"
                );
            }
            MaintenanceAction::Send => {
                app_state
                    .maintenance_store
                    .set_last_round_timestamp(next_round_ts)
                    .await?;
                tracing::info!(
                    service = "ark_client",
                    event = "maintenance_triggered",
                    next_round_ts = next_round_ts,
                    secs_until_round = next_round_ts.saturating_sub(now),
                    "sending maintenance notification"
                );

                let app_state_clone = app_state.clone();
                tokio::spawn(async move {
                    let _ = maintenance(app_state_clone).await;
                });

                app_state.maintenance_store.reset_round_counter().await?;
            }
        }
    }
}

#[derive(Debug, PartialEq)]
enum MaintenanceAction {
    /// Round timestamp unchanged, nothing to do
    NoChange,
    /// New round detected but counter hasn't reached the threshold yet
    RoundDetected,
    /// Counter reached threshold but the round is too close to notify in time
    TooClose,
    /// Counter reached threshold and there's enough advance time — send notification
    Send,
}

fn evaluate_maintenance(
    next_round_ts: u64,
    last_round_ts: Option<u64>,
    counter: u16,
    maintenance_interval_rounds: u16,
    advance_secs: u64,
    now: u64,
) -> MaintenanceAction {
    if last_round_ts == Some(next_round_ts) {
        return MaintenanceAction::NoChange;
    }

    // counter should reflect the value *after* incrementing for this new round
    let counter = counter + 1;

    if counter < maintenance_interval_rounds {
        return MaintenanceAction::RoundDetected;
    }

    if now + advance_secs > next_round_ts {
        return MaintenanceAction::TooClose;
    }

    MaintenanceAction::Send
}

pub async fn maintenance(app_state: AppState) -> anyhow::Result<()> {
    let coordinator = NotificationCoordinator::new(app_state);

    let request = NotificationRequest {
        priority: Priority::High,
        data: NotificationRequestData::Maintenance,
        target_pubkey: None, // Broadcast to all users
    };

    if let Err(e) = coordinator.send_notification(request).await {
        tracing::error!(service = "ark_client", job = "maintenance", error = %e, "notification failed");
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const INTERVAL: u16 = 10;
    const ADVANCE: u64 = 30;

    #[test]
    fn no_change_when_timestamp_unchanged() {
        let action = evaluate_maintenance(
            1000,       // next_round_ts
            Some(1000), // last_round_ts (same)
            5,          // counter
            INTERVAL,
            ADVANCE,
            900, // now
        );
        assert_eq!(action, MaintenanceAction::NoChange);
    }

    #[test]
    fn round_detected_but_counter_below_threshold() {
        let action = evaluate_maintenance(
            1000,      // next_round_ts
            Some(900), // last_round_ts (different)
            3,         // counter (3+1=4, below 10)
            INTERVAL,
            ADVANCE,
            800, // now
        );
        assert_eq!(action, MaintenanceAction::RoundDetected);
    }

    #[test]
    fn first_poll_with_no_prior_state() {
        let action = evaluate_maintenance(
            1000, // next_round_ts
            None, // last_round_ts (first time)
            0,    // counter
            INTERVAL, ADVANCE, 800, // now
        );
        assert_eq!(action, MaintenanceAction::RoundDetected);
    }

    #[test]
    fn too_close_when_round_within_advance_window() {
        // now=970, advance=30, next_round=990 → 970+30=1000 > 990
        let action = evaluate_maintenance(
            990,       // next_round_ts
            Some(900), // last_round_ts (different)
            9,         // counter (9+1=10, meets threshold)
            INTERVAL,
            ADVANCE,
            970, // now
        );
        assert_eq!(action, MaintenanceAction::TooClose);
    }

    #[test]
    fn too_close_when_round_exactly_at_advance_boundary() {
        // now=960, advance=30, next_round=990 → 960+30=990, not > 990
        // This is exactly at the boundary — should be Send, not TooClose
        let action = evaluate_maintenance(990, Some(900), 9, INTERVAL, ADVANCE, 960);
        assert_eq!(action, MaintenanceAction::Send);
    }

    #[test]
    fn send_when_enough_advance_time() {
        // now=900, advance=30, next_round=1000 → 900+30=930 < 1000
        let action = evaluate_maintenance(
            1000,
            Some(900),
            9, // counter (9+1=10, meets threshold)
            INTERVAL,
            ADVANCE,
            900,
        );
        assert_eq!(action, MaintenanceAction::Send);
    }

    #[test]
    fn too_close_when_round_already_passed() {
        // now=1010, advance=30, next_round=1000 → 1010+30=1040 > 1000
        let action = evaluate_maintenance(1000, Some(900), 9, INTERVAL, ADVANCE, 1010);
        assert_eq!(action, MaintenanceAction::TooClose);
    }

    #[test]
    fn counter_exactly_at_threshold_with_enough_time() {
        // counter=9, threshold=10 → 9+1=10 >= 10
        let action = evaluate_maintenance(2000, Some(1000), 9, INTERVAL, ADVANCE, 1500);
        assert_eq!(action, MaintenanceAction::Send);
    }

    #[test]
    fn counter_above_threshold_still_sends() {
        // counter=15, threshold=10 → 15+1=16 >= 10 (skipped reset somehow)
        let action = evaluate_maintenance(2000, Some(1000), 15, INTERVAL, ADVANCE, 1500);
        assert_eq!(action, MaintenanceAction::Send);
    }
}
