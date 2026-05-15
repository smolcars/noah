use expo_push_notification_client::{Expo, ExpoClientOptions, ExpoPushMessage, Priority};
use futures_util::{StreamExt, stream};
use reqwest::Client;
use serde::Serialize;

use crate::{
    AppState, db::push_token_repo::PushTokenRepository, errors::ApiError,
    types::NotificationRequestData, utils::make_k1,
};

/// Determines if a push token is an Expo push token.
/// All other tokens (e.g., UnifiedPush HTTP endpoints) are treated as non-Expo.
fn is_expo_token(token: &str) -> bool {
    ((token.starts_with("ExponentPushToken[") || token.starts_with("ExpoPushToken["))
        && token.ends_with(']'))
        || regex::Regex::new(r"^[a-z\d]{8}-[a-z\d]{4}-[a-z\d]{4}-[a-z\d]{4}-[a-z\d]{12}$")
            .expect("regex is valid")
            .is_match(token)
}

fn notification_type_for_log(data: &str) -> String {
    serde_json::from_str::<serde_json::Value>(data)
        .ok()
        .and_then(|value| {
            value
                .get("notification_type")
                .and_then(|notification_type| notification_type.as_str())
                .map(str::to_string)
        })
        .unwrap_or_else(|| "unknown".to_string())
}

#[derive(Serialize, Clone, Debug)]
pub struct PushNotificationData {
    pub title: Option<String>,
    pub body: Option<String>,
    pub data: String,
    pub priority: Priority,
    // This is iOS only which makes the app wake up to do things
    pub content_available: bool,
}

#[derive(Debug, Clone)]
pub struct PushDispatchReceipt {
    pub pubkey: String,
    pub notification_k1: String,
}

#[derive(Debug, Clone)]
struct PushTarget {
    pubkey: String,
    push_token: String,
}

pub async fn send_push_notification(
    app_state: AppState,
    data: PushNotificationData,
    pubkey: Option<String>,
) -> anyhow::Result<(), ApiError> {
    send_push_notification_internal(app_state, data, pubkey, true).await
}

pub async fn send_expo_push_notification(
    app_state: AppState,
    data: PushNotificationData,
    pubkey: Option<String>,
) -> anyhow::Result<(), ApiError> {
    send_push_notification_internal(app_state, data, pubkey, false).await
}

pub async fn has_expo_push_token(app_state: &AppState, pubkey: &str) -> Result<bool, ApiError> {
    let push_token_repo = PushTokenRepository::new(&app_state.db_pool);
    let Some(push_token) = push_token_repo.find_by_pubkey(pubkey).await? else {
        return Ok(false);
    };

    Ok(is_expo_token(&push_token))
}

pub async fn send_push_notification_with_unique_k1(
    app_state: AppState,
    base_notification_data: NotificationRequestData,
    pubkey: Option<String>,
) -> anyhow::Result<Vec<PushDispatchReceipt>, ApiError> {
    // For notifications that need unique k1 per device, we don't use the batching approach
    // Instead, we send individual notifications with unique k1 values
    let expo = Expo::new(ExpoClientOptions {
        access_token: Some(app_state.config.expo_access_token.clone()),
    });
    let http_client = Client::new();

    let push_token_repo = PushTokenRepository::new(&app_state.db_pool);

    let push_targets = if let Some(pubkey) = pubkey {
        match push_token_repo.find_by_pubkey(&pubkey).await? {
            Some(push_token) => vec![PushTarget { pubkey, push_token }],
            None => vec![],
        }
    } else {
        push_token_repo
            .find_all_with_pubkeys()
            .await?
            .into_iter()
            .map(|(pubkey, push_token)| PushTarget { pubkey, push_token })
            .collect()
    };

    if push_targets.is_empty() {
        return Ok(vec![]);
    }

    // Send individual notifications with unique k1 for each device
    let receipts = stream::iter(push_targets)
        .filter_map(|target| {
            let expo_clone = expo.clone();
            let app_state_clone = app_state.clone();
            let base_data_clone = base_notification_data.clone();
            let http_client_clone = http_client.clone();
            let ntfy_auth = app_state.config.ntfy_auth_token.clone();
            async move {
                // Create notification data with unique k1 if needed
                let notification_k1 = if base_data_clone.needs_unique_k1() {
                    match make_k1(&app_state_clone.k1_cache).await {
                        Ok(unique_k1) => Some(unique_k1),
                        Err(e) => {
                            tracing::error!(
                                "Failed to create unique k1 for push notification: {}",
                                e
                            );
                            return None;
                        }
                    }
                } else {
                    None
                };

                let notification_data = match base_data_clone
                    .into_notification_data(notification_k1.clone())
                {
                    Ok(notification_data) => notification_data,
                    Err(e) => {
                        tracing::error!("Failed to build notification payload: {}", e);
                        return None;
                    }
                };

                let data_string = match serde_json::to_string(&notification_data) {
                    Ok(s) => s,
                    Err(e) => {
                        tracing::error!("Failed to serialize notification data: {}", e);
                        return None;
                    }
                };

                let send_result = if is_expo_token(&target.push_token) {
                    let push_data = PushNotificationData {
                        title: None,
                        body: None,
                        data: data_string,
                        priority: Priority::High,
                        content_available: true,
                    };

                    let message = match ExpoPushMessage::builder(vec![target.push_token.clone()])
                        .data(&push_data.data)
                        .and_then(|b| {
                            b.priority(push_data.priority)
                                .content_available(push_data.content_available)
                                .mutable_content(false)
                                .build()
                        }) {
                        Ok(msg) => msg,
                        Err(e) => {
                            tracing::error!("Failed to build push notification message: {}", e);
                            return None;
                        }
                    };

                    expo_clone
                        .send_push_notifications(message)
                        .await
                        .map(|_| ())
                        .map_err(|e| e.to_string())
                } else {
                    send_unified_notification(
                        &http_client_clone,
                        &target.push_token,
                        &data_string,
                        &ntfy_auth,
                    )
                    .await
                    .map_err(|e| e.to_string())
                };

                if let Err(e) = send_result {
                    tracing::error!(pubkey = %target.pubkey, "Failed to send push notification: {}", e);
                    return None;
                }

                Some(PushDispatchReceipt {
                    pubkey: target.pubkey,
                    notification_k1: notification_k1.unwrap_or_default(),
                })
            }
        })
        .collect::<Vec<_>>()
        .await;

    tracing::debug!(
        "send_push_notification_with_unique_k1: Sent {} notifications with unique k1s {:?}",
        receipts.len(),
        base_notification_data
    );
    Ok(receipts)
}

async fn send_push_notification_internal(
    app_state: AppState,
    data: PushNotificationData,
    pubkey: Option<String>,
    allow_unified_push: bool,
) -> anyhow::Result<(), ApiError> {
    let expo = Expo::new(ExpoClientOptions {
        access_token: Some(app_state.config.expo_access_token.clone()),
    });
    let http_client = Client::new();

    let push_token_repo = PushTokenRepository::new(&app_state.db_pool);

    let push_tokens = if let Some(pubkey) = pubkey {
        // A single token might not be found, which is not an error, so we handle the Option.
        match push_token_repo.find_by_pubkey(&pubkey).await? {
            Some(token) => vec![token],
            None => vec![],
        }
    } else {
        push_token_repo.find_all().await?
    };
    let notification_type = notification_type_for_log(&data.data);

    if push_tokens.is_empty() {
        tracing::warn!(
            notification_type,
            "send_push_notification: no push tokens found for notification"
        );
        return Ok(());
    }

    tracing::info!(
        notification_type,
        "send_push_notification: Sending to {} tokens",
        push_tokens.len()
    );

    let (expo_tokens, unified_tokens): (Vec<_>, Vec<_>) =
        push_tokens.into_iter().partition(|t| is_expo_token(t));

    if !allow_unified_push {
        if !unified_tokens.is_empty() {
            tracing::info!(
                notification_type,
                skipped_unified_push_tokens = unified_tokens.len(),
                "send_push_notification: skipping UnifiedPush tokens for Expo-only notification"
            );
        }

        if expo_tokens.is_empty() {
            tracing::warn!(
                notification_type,
                "send_push_notification: no Expo push tokens found for Expo-only notification"
            );
            return Ok(());
        }
    }

    if !expo_tokens.is_empty() {
        let chunks = expo_tokens
            .chunks(100)
            .map(|c| c.to_vec())
            .collect::<Vec<_>>();

        stream::iter(chunks)
            .for_each_concurrent(None, |chunk| {
                let expo_clone = expo.clone();
                let data_clone = data.clone();
                async move {
                    let mut builder = ExpoPushMessage::builder(chunk);
                    if let Some(title) = &data_clone.title {
                        builder = builder.title(title.clone());
                    }
                    if let Some(body) = &data_clone.body {
                        builder = builder.body(body.clone());
                    }
                    let message = match builder.data(&data_clone.data).and_then(|b| {
                        b.priority(data_clone.priority)
                            .content_available(data_clone.content_available)
                            .mutable_content(false)
                            .build()
                    }) {
                        Ok(msg) => msg,
                        Err(e) => {
                            tracing::error!("Failed to build push notification message: {}", e);
                            return;
                        }
                    };

                    if let Err(e) = expo_clone.send_push_notifications(message).await {
                        tracing::error!("Failed to send push notification chunk: {}", e);
                    }
                }
            })
            .await;
    }

    if allow_unified_push && !unified_tokens.is_empty() {
        let ntfy_auth = app_state.config.ntfy_auth_token.clone();
        let data_clone = data.clone();
        stream::iter(unified_tokens)
            .for_each_concurrent(None, |endpoint| {
                let http_client_clone = http_client.clone();
                let ntfy_auth = ntfy_auth.clone();
                let payload = data_clone.clone();
                async move {
                    if let Err(e) = send_unified_notification(
                        &http_client_clone,
                        &endpoint,
                        &payload.data,
                        &ntfy_auth,
                    )
                    .await
                    {
                        tracing::error!("Failed to send unified push notification: {}", e);
                    }
                }
            })
            .await;
    }

    tracing::info!(
        notification_type,
        "send_push_notification: Sent push notification"
    );

    Ok(())
}

async fn send_unified_notification(
    client: &Client,
    endpoint: &str,
    payload: &str,
    auth_token: &str,
) -> Result<(), ApiError> {
    let mut request = client.post(endpoint).body(payload.to_string());
    request = request.bearer_auth(auth_token);

    let response = request
        .send()
        .await
        .map_err(|_| ApiError::ServerErr("Failed to send push notification".to_string()))?;

    if !response.status().is_success() {
        let status = response.status();
        let text = response.text().await.unwrap_or_default();
        tracing::error!("UnifiedPush endpoint returned {}: {}", status, text);
    }

    Ok(())
}
