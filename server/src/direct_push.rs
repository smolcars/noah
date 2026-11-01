//! Sends pushes straight to APNs and FCM, for apps that register a raw device token.

use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, anyhow, bail};
use expo_push_notification_client::Priority;
use jsonwebtoken::{Algorithm, EncodingKey, Header, encode};
use reqwest::{Client, Response, StatusCode, header::RETRY_AFTER};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::sync::Mutex;

use crate::{
    config::Config, db::push_token_repo::PushTarget, push::PushNotificationData,
    types::PushProvider,
};

const APNS_PRODUCTION_URL: &str = "https://api.push.apple.com";
const APNS_SANDBOX_URL: &str = "https://api.sandbox.push.apple.com";
const FCM_URL: &str = "https://fcm.googleapis.com";
const FCM_SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
/// Expo's default, so a phone that was offline still catches up as it does today.
const MESSAGE_TTL: Duration = Duration::from_secs(28 * 24 * 3600);
/// Apple rejects provider tokens older than an hour and refreshes under 20 minutes apart.
const APNS_TOKEN_LIFETIME: Duration = Duration::from_secs(40 * 60);
const FCM_TOKEN_REQUESTED_LIFETIME: Duration = Duration::from_secs(3600);
const FCM_TOKEN_REFRESH_MARGIN: Duration = Duration::from_secs(5 * 60);
/// Delays before the second and third attempts. The Lightning-address flow only gives
/// the phone 45 s, so a send doesn't keep trying for long.
const RETRY_DELAYS: [Duration; 2] = [Duration::from_millis(500), Duration::from_secs(2)];
const MAX_RETRY_AFTER: Duration = Duration::from_secs(5);
/// Covers waiting for the auth token, requests and retry delays. Mailbox processing waits
/// on each send, and a wake-up later than this is unlikely to beat the 45 s invoice limit.
const SEND_DEADLINE: Duration = Duration::from_secs(20);

/// What one send attempt means for the next step.
#[derive(Debug, PartialEq)]
enum Attempt {
    Sent,
    /// The provider says this token no longer reaches the app.
    Unregistered,
    Retry {
        after: Option<Duration>,
        reason: String,
    },
    /// The auth token was refused; the next attempt must use a new one.
    RetryWithNewAuth {
        reason: String,
    },
    Failed {
        reason: String,
    },
}

struct CachedToken {
    value: String,
    refresh_at: Instant,
}

struct Apns {
    key: EncodingKey,
    key_id: String,
    team_id: String,
    topic: String,
    production_url: String,
    sandbox_url: String,
    provider_token: Mutex<Option<CachedToken>>,
}

#[derive(Deserialize)]
struct FcmServiceAccount {
    project_id: String,
    client_email: String,
    private_key: String,
    token_uri: String,
}

struct Fcm {
    key: EncodingKey,
    project_id: String,
    client_email: String,
    token_uri: String,
    url: String,
    access_token: Mutex<Option<CachedToken>>,
}

/// Holds the one long-lived HTTP client, because APNs expects connections to be reused.
pub struct DirectPush {
    client: Client,
    apns: Option<Apns>,
    fcm: Option<Fcm>,
    send_deadline: Duration,
}

impl DirectPush {
    /// Fails on credentials that are present but unusable, so a bad secret stops the
    /// deploy instead of silently losing pushes. Missing ones only fail at send time.
    pub fn from_config(config: &Config) -> Result<Self> {
        let client = Client::builder()
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(REQUEST_TIMEOUT)
            .build()
            .context("Failed to build the direct push HTTP client")?;

        let apns = match (
            &config.apns_key_p8,
            &config.apns_key_id,
            &config.apns_team_id,
            &config.apns_topic,
        ) {
            (None, None, None, None) => None,
            (Some(key), Some(key_id), Some(team_id), Some(topic)) => Some(Apns {
                key: EncodingKey::from_ec_pem(key.as_bytes())
                    .map_err(|_| anyhow!("APNS_KEY_P8 is not a valid .p8 key"))?,
                key_id: key_id.clone(),
                team_id: team_id.clone(),
                topic: topic.clone(),
                production_url: APNS_PRODUCTION_URL.to_string(),
                sandbox_url: APNS_SANDBOX_URL.to_string(),
                provider_token: Mutex::new(None),
            }),
            _ => {
                bail!("APNS_KEY_P8, APNS_KEY_ID, APNS_TEAM_ID and APNS_TOPIC must be set together")
            }
        };

        let fcm = match &config.fcm_service_account_json {
            None => None,
            Some(json) => {
                // serde_json's error can quote the input, which holds the private key.
                let account: FcmServiceAccount = serde_json::from_str(json).map_err(|_| {
                    anyhow!("FCM_SERVICE_ACCOUNT_JSON is not a service account key")
                })?;
                Some(Fcm {
                    key: EncodingKey::from_rsa_pem(account.private_key.as_bytes()).map_err(
                        |_| anyhow!("FCM_SERVICE_ACCOUNT_JSON holds an invalid private key"),
                    )?,
                    project_id: account.project_id,
                    client_email: account.client_email,
                    token_uri: account.token_uri,
                    url: FCM_URL.to_string(),
                    access_token: Mutex::new(None),
                })
            }
        };

        Ok(Self {
            client,
            apns,
            fcm,
            send_deadline: SEND_DEADLINE,
        })
    }

    #[cfg(test)]
    #[allow(dead_code)] // the library's test build has no direct push tests
    pub(crate) fn use_send_deadline(&mut self, deadline: Duration) {
        self.send_deadline = deadline;
    }

    #[cfg(test)]
    #[allow(dead_code)] // the library's test build has no direct push tests
    pub(crate) fn use_urls(&mut self, apns_url: &str, fcm_url: &str) {
        if let Some(apns) = &mut self.apns {
            apns.production_url = format!("{apns_url}/production");
            apns.sandbox_url = format!("{apns_url}/sandbox");
        }
        if let Some(fcm) = &mut self.fcm {
            fcm.url = fcm_url.to_string();
        }
    }

    pub fn apns_configured(&self) -> bool {
        self.apns.is_some()
    }

    pub fn fcm_configured(&self) -> bool {
        self.fcm.is_some()
    }

    /// Sends one message, retrying transient failures a bounded number of times. A token
    /// the provider says is gone is kept and counts as sent, as for Expo: the device misses
    /// its heartbeats and gets deregistered, and heartbeats only go to rows that still exist.
    ///
    /// Errors name no device token or credential, so callers can log them.
    pub async fn send(
        &self,
        target: &PushTarget,
        data: &PushNotificationData,
        sound: bool,
    ) -> Result<()> {
        tokio::time::timeout(self.send_deadline, self.deliver(target, data, sound))
            .await
            .map_err(|_| anyhow!("Direct push gave up at its deadline"))?
    }

    async fn deliver(
        &self,
        target: &PushTarget,
        data: &PushNotificationData,
        sound: bool,
    ) -> Result<()> {
        let mut delays = RETRY_DELAYS.iter();
        loop {
            let attempt = match target.provider {
                Some(PushProvider::Apns) => self.send_to_apns(target, data, sound).await,
                Some(PushProvider::Fcm) => self.send_to_fcm(target, data).await,
                None => bail!("The token has no direct push provider"),
            };
            let (after, reason) = match attempt {
                Attempt::Sent => return Ok(()),
                Attempt::Unregistered => {
                    tracing::warn!(
                        pubkey = %target.pubkey,
                        provider = ?target.provider,
                        "Push provider reports the device token is no longer registered"
                    );
                    return Ok(());
                }
                Attempt::Failed { reason } => bail!(reason),
                Attempt::Retry { after, reason } => (after, reason),
                Attempt::RetryWithNewAuth { reason } => {
                    self.forget_auth(target).await;
                    (None, reason)
                }
            };
            let Some(delay) = delays.next() else {
                bail!(reason);
            };
            tokio::time::sleep(after.map_or(*delay, |after| after.min(MAX_RETRY_AFTER))).await;
        }
    }

    async fn forget_auth(&self, target: &PushTarget) {
        match target.provider {
            Some(PushProvider::Apns) => {
                if let Some(apns) = &self.apns {
                    *apns.provider_token.lock().await = None;
                }
            }
            Some(PushProvider::Fcm) => {
                if let Some(fcm) = &self.fcm {
                    *fcm.access_token.lock().await = None;
                }
            }
            None => {}
        }
    }

    async fn send_to_apns(
        &self,
        target: &PushTarget,
        data: &PushNotificationData,
        sound: bool,
    ) -> Attempt {
        let Some(apns) = &self.apns else {
            return Attempt::Failed {
                reason: "APNs credentials are not configured".to_string(),
            };
        };
        let provider_token = match apns.token().await {
            Ok(token) => token,
            Err(error) => {
                return Attempt::Failed {
                    reason: error.to_string(),
                };
            }
        };
        let base = if target.apns_sandbox {
            &apns.sandbox_url
        } else {
            &apns.production_url
        };
        let (push_type, priority) = apns_headers(data);
        let response = self
            .client
            .post(format!("{base}/3/device/{}", target.push_token))
            .bearer_auth(provider_token)
            .header("apns-topic", &apns.topic)
            .header("apns-push-type", push_type)
            .header("apns-priority", priority)
            .header("apns-expiration", expires_at().to_string())
            .json(&apns_body(data, sound))
            .send()
            .await;
        let response = match response {
            Ok(response) => response,
            Err(error) => return transport_failure("APNs", error),
        };
        let status = response.status();
        let retry_after = retry_after(&response);
        let reason = response
            .json::<Value>()
            .await
            .ok()
            .and_then(|body| body["reason"].as_str().map(str::to_string));
        classify_apns(status, reason.as_deref(), retry_after)
    }

    async fn send_to_fcm(&self, target: &PushTarget, data: &PushNotificationData) -> Attempt {
        let Some(fcm) = &self.fcm else {
            return Attempt::Failed {
                reason: "FCM credentials are not configured".to_string(),
            };
        };
        let access_token = match fcm.token(&self.client).await {
            Ok(token) => token,
            // Google's token endpoint failing is as transient as FCM itself failing.
            Err(error) => {
                return Attempt::Retry {
                    after: None,
                    reason: error.to_string(),
                };
            }
        };
        let response = self
            .client
            .post(format!(
                "{}/v1/projects/{}/messages:send",
                fcm.url, fcm.project_id
            ))
            .bearer_auth(access_token)
            .json(&fcm_body(&target.push_token, data))
            .send()
            .await;
        let response = match response {
            Ok(response) => response,
            Err(error) => return transport_failure("FCM", error),
        };
        let status = response.status();
        let retry_after = retry_after(&response);
        let body = response.json::<Value>().await.unwrap_or(Value::Null);
        classify_fcm(status, &body, retry_after)
    }
}

impl Apns {
    async fn token(&self) -> Result<String> {
        let mut cached = self.provider_token.lock().await;
        if let Some(token) = &*cached
            && token.refresh_at > Instant::now()
        {
            return Ok(token.value.clone());
        }

        let mut header = Header::new(Algorithm::ES256);
        header.kid = Some(self.key_id.clone());
        let value = encode(
            &header,
            &json!({ "iss": self.team_id, "iat": unix_time() }),
            &self.key,
        )
        .map_err(|_| anyhow!("Failed to sign the APNs provider token"))?;
        *cached = Some(CachedToken {
            value: value.clone(),
            refresh_at: Instant::now() + APNS_TOKEN_LIFETIME,
        });
        Ok(value)
    }
}

impl Fcm {
    /// Holds the lock across the refresh so concurrent sends share one token request.
    async fn token(&self, client: &Client) -> Result<String> {
        let mut cached = self.access_token.lock().await;
        if let Some(token) = &*cached
            && token.refresh_at > Instant::now()
        {
            return Ok(token.value.clone());
        }

        #[derive(Deserialize)]
        struct TokenResponse {
            access_token: String,
            expires_in: u64,
        }

        let now = unix_time();
        let claims = json!({
            "iss": self.client_email,
            "scope": FCM_SCOPE,
            "aud": self.token_uri,
            "iat": now,
            "exp": now + FCM_TOKEN_REQUESTED_LIFETIME.as_secs(),
        });
        let assertion = encode(&Header::new(Algorithm::RS256), &claims, &self.key)
            .map_err(|_| anyhow!("Failed to sign the FCM token request"))?;
        let response = client
            .post(&self.token_uri)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
                ("assertion", assertion.as_str()),
            ])
            .send()
            .await
            .map_err(|error| anyhow!("FCM token request failed: {}", error.without_url()))?;
        let status = response.status();
        if !status.is_success() {
            bail!("FCM token request returned {status}");
        }
        let token: TokenResponse = response
            .json()
            .await
            .map_err(|_| anyhow!("FCM token response could not be read"))?;
        *cached = Some(CachedToken {
            value: token.access_token.clone(),
            refresh_at: Instant::now()
                + Duration::from_secs(token.expires_in).saturating_sub(FCM_TOKEN_REFRESH_MARGIN),
        });
        Ok(token.access_token)
    }
}

/// `without_url` because an APNs URL holds the device token.
fn transport_failure(provider: &str, error: reqwest::Error) -> Attempt {
    Attempt::Retry {
        after: None,
        reason: format!("{provider} request failed: {}", error.without_url()),
    }
}

fn retry_after(response: &Response) -> Option<Duration> {
    response
        .headers()
        .get(RETRY_AFTER)?
        .to_str()
        .ok()?
        .parse()
        .ok()
        .map(Duration::from_secs)
}

fn classify_apns(status: StatusCode, reason: Option<&str>, after: Option<Duration>) -> Attempt {
    let described = || format!("APNs returned {status}: {}", reason.unwrap_or("no reason"));
    match (status, reason) {
        (StatusCode::OK, _) => Attempt::Sent,
        (StatusCode::GONE, _) => Attempt::Unregistered,
        (StatusCode::FORBIDDEN, Some("ExpiredProviderToken")) => Attempt::RetryWithNewAuth {
            reason: described(),
        },
        (status, _) if status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error() => {
            Attempt::Retry {
                after,
                reason: described(),
            }
        }
        _ => Attempt::Failed {
            reason: described(),
        },
    }
}

fn classify_fcm(status: StatusCode, body: &Value, after: Option<Duration>) -> Attempt {
    let code = body["error"]["details"]
        .as_array()
        .into_iter()
        .flatten()
        .find_map(|detail| detail["errorCode"].as_str())
        .or_else(|| body["error"]["status"].as_str());
    let described = || format!("FCM returned {status}: {}", code.unwrap_or("no code"));
    match (status, code) {
        (StatusCode::OK, _) => Attempt::Sent,
        (StatusCode::NOT_FOUND, Some("UNREGISTERED")) => Attempt::Unregistered,
        (StatusCode::UNAUTHORIZED, _) => Attempt::RetryWithNewAuth {
            reason: described(),
        },
        (status, _) if status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error() => {
            Attempt::Retry {
                after,
                reason: described(),
            }
        }
        _ => Attempt::Failed {
            reason: described(),
        },
    }
}

fn is_visible(data: &PushNotificationData) -> bool {
    data.title.is_some() || data.body.is_some()
}

/// Apple documents silent pushes as type `background` at priority 5, whatever the
/// message's own priority.
fn apns_headers(data: &PushNotificationData) -> (&'static str, &'static str) {
    if !is_visible(data) {
        return ("background", "5");
    }
    let priority = if data.priority == Priority::Normal {
        "5"
    } else {
        "10"
    };
    ("alert", priority)
}

/// `body` is the key the apps already read Noah's payload from.
fn apns_body(data: &PushNotificationData, sound: bool) -> Value {
    let mut aps = serde_json::Map::new();
    if is_visible(data) {
        let mut alert = serde_json::Map::new();
        if let Some(title) = &data.title {
            alert.insert("title".to_string(), json!(title));
        }
        if let Some(body) = &data.body {
            alert.insert("body".to_string(), json!(body));
        }
        aps.insert("alert".to_string(), Value::Object(alert));
    }
    if data.content_available || !is_visible(data) {
        aps.insert("content-available".to_string(), json!(1));
    }
    if sound {
        aps.insert("sound".to_string(), json!("default"));
    }
    json!({ "aps": aps, "body": data.data })
}

/// Data-only, so the app's service runs in every state. `message` and `title` are the
/// keys it already shows a visible notification from.
fn fcm_body(token: &str, data: &PushNotificationData) -> Value {
    let mut fields = serde_json::Map::new();
    fields.insert("body".to_string(), json!(data.data));
    if let Some(title) = &data.title {
        fields.insert("title".to_string(), json!(title));
    }
    if let Some(body) = &data.body {
        fields.insert("message".to_string(), json!(body));
    }
    let priority = if data.priority == Priority::High {
        "HIGH"
    } else {
        "NORMAL"
    };
    json!({
        "message": {
            "token": token,
            "data": fields,
            "android": { "priority": priority, "ttl": format!("{}s", MESSAGE_TTL.as_secs()) },
        }
    })
}

fn unix_time() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_secs())
}

fn expires_at() -> u64 {
    unix_time() + MESSAGE_TTL.as_secs()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn silent(priority: Priority) -> PushNotificationData {
        PushNotificationData {
            title: None,
            body: None,
            data: r#"{"notification_type":"heartbeat"}"#.to_string(),
            priority,
            content_available: true,
        }
    }

    fn visible(priority: Priority) -> PushNotificationData {
        PushNotificationData {
            title: Some("Payment received".to_string()),
            body: Some("You received 1 sat".to_string()),
            data: "{}".to_string(),
            priority,
            content_available: false,
        }
    }

    #[test]
    fn silent_pushes_use_apples_background_settings_at_any_priority() {
        for priority in [Priority::High, Priority::Normal] {
            assert_eq!(apns_headers(&silent(priority)), ("background", "5"));
        }
    }

    #[test]
    fn visible_pushes_follow_the_message_priority() {
        assert_eq!(apns_headers(&visible(Priority::High)), ("alert", "10"));
        assert_eq!(apns_headers(&visible(Priority::Normal)), ("alert", "5"));
    }

    #[test]
    fn apns_body_carries_noahs_payload_where_the_app_reads_it() {
        assert_eq!(
            apns_body(&silent(Priority::High), false),
            json!({
                "aps": { "content-available": 1 },
                "body": r#"{"notification_type":"heartbeat"}"#,
            })
        );
        assert_eq!(
            apns_body(&visible(Priority::High), true),
            json!({
                "aps": {
                    "alert": { "title": "Payment received", "body": "You received 1 sat" },
                    "sound": "default",
                },
                "body": "{}",
            })
        );
    }

    #[test]
    fn fcm_body_uses_the_keys_the_app_reads() {
        assert_eq!(
            fcm_body("device", &visible(Priority::High)),
            json!({
                "message": {
                    "token": "device",
                    "data": {
                        "body": "{}",
                        "title": "Payment received",
                        "message": "You received 1 sat",
                    },
                    "android": { "priority": "HIGH", "ttl": "2419200s" },
                }
            })
        );
        assert_eq!(
            fcm_body("device", &silent(Priority::Normal))["message"]["android"]["priority"],
            "NORMAL"
        );
    }

    #[test]
    fn apns_responses_are_classified() {
        let retry_after = Some(Duration::from_secs(1));
        assert_eq!(classify_apns(StatusCode::OK, None, None), Attempt::Sent);
        assert_eq!(
            classify_apns(StatusCode::GONE, Some("Unregistered"), None),
            Attempt::Unregistered
        );
        assert!(matches!(
            classify_apns(StatusCode::FORBIDDEN, Some("ExpiredProviderToken"), None),
            Attempt::RetryWithNewAuth { .. }
        ));
        assert!(matches!(
            classify_apns(StatusCode::TOO_MANY_REQUESTS, Some("TooManyRequests"), retry_after),
            Attempt::Retry { after, .. } if after == retry_after
        ));
        assert!(matches!(
            classify_apns(StatusCode::SERVICE_UNAVAILABLE, None, None),
            Attempt::Retry { .. }
        ));
        for (status, reason) in [
            (StatusCode::BAD_REQUEST, "BadDeviceToken"),
            (StatusCode::BAD_REQUEST, "DeviceTokenNotForTopic"),
            (StatusCode::FORBIDDEN, "InvalidProviderToken"),
            (StatusCode::PAYLOAD_TOO_LARGE, "PayloadTooLarge"),
        ] {
            assert!(
                matches!(
                    classify_apns(status, Some(reason), None),
                    Attempt::Failed { reason: described } if described.contains(reason)
                ),
                "{reason}"
            );
        }
    }

    #[test]
    fn fcm_responses_are_classified() {
        let error = |status: &str, code: &str| json!({ "error": { "status": status, "details": [{ "errorCode": code }] } });
        assert_eq!(
            classify_fcm(StatusCode::OK, &json!({ "name": "sent" }), None),
            Attempt::Sent
        );
        assert_eq!(
            classify_fcm(
                StatusCode::NOT_FOUND,
                &error("NOT_FOUND", "UNREGISTERED"),
                None
            ),
            Attempt::Unregistered
        );
        // A missing project is also a 404, and says nothing about the device.
        assert!(matches!(
            classify_fcm(
                StatusCode::NOT_FOUND,
                &json!({ "error": { "status": "NOT_FOUND" } }),
                None
            ),
            Attempt::Failed { .. }
        ));
        assert!(matches!(
            classify_fcm(
                StatusCode::UNAUTHORIZED,
                &json!({ "error": { "status": "UNAUTHENTICATED" } }),
                None
            ),
            Attempt::RetryWithNewAuth { .. }
        ));
        for status in [
            StatusCode::TOO_MANY_REQUESTS,
            StatusCode::SERVICE_UNAVAILABLE,
        ] {
            assert!(matches!(
                classify_fcm(status, &Value::Null, None),
                Attempt::Retry { .. }
            ));
        }
        assert!(matches!(
            classify_fcm(
                StatusCode::BAD_REQUEST,
                &error("INVALID_ARGUMENT", "INVALID_ARGUMENT"),
                None
            ),
            Attempt::Failed { .. }
        ));
    }
}
