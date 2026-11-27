//! The payer data a matched LNURL-pay payment's push carries (docs/lnurl_pay.md).
//!
//! It is sealed with the key the recipient's app registered with its push token, so Apple, Google
//! and UnifiedPush distributors only relay ciphertext. The app writes the notification text from
//! it: on iOS a notification service extension, which also runs after a force-quit.

use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{Aead, AeadCore, KeyInit, OsRng, Payload},
};
use anyhow::{Context, anyhow};
use base64::{Engine, engine::general_purpose::STANDARD};
use serde::{Deserialize, Serialize};

use crate::types::LnurlPayReceivePayerData;

pub(crate) const NOTIFICATION_KEY_LEN: usize = 32;
const NONCE_LEN: usize = 12;
const ENVELOPE_VERSION: u8 = 1;

/// What the app shows: the plaintext of `sealed`.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct LnurlPayReceiveNotification {
    /// What the recipient received, after barkd's fee.
    pub amount_sat: u64,
    pub payer_data: Option<LnurlPayReceivePayerData>,
    pub comment: Option<String>,
}

/// The `lnurl_pay_receive` object in a push's data: the record's ID, and the sealed content when
/// the app registered a key. `sealed` is base64 of `nonce || ciphertext || tag`, CryptoKit's
/// combined AES.GCM box, under AES-256-GCM with additional data binding it to the record ID.
pub fn push_field(
    record_id: &str,
    key: Option<&[u8; NOTIFICATION_KEY_LEN]>,
    content: &LnurlPayReceiveNotification,
) -> serde_json::Value {
    let sealed = key.and_then(|key| match seal(key, record_id, content) {
        Ok(sealed) => Some(sealed),
        Err(error) => {
            tracing::warn!(record_id, error = %error, "failed to seal LNURL-pay notification content");
            None
        }
    });
    let mut field = serde_json::json!({ "v": ENVELOPE_VERSION, "id": record_id });
    if let Some(sealed) = sealed {
        field["sealed"] = serde_json::Value::String(sealed);
    }
    field
}

pub fn parse_key(base64_key: &str) -> anyhow::Result<[u8; NOTIFICATION_KEY_LEN]> {
    STANDARD
        .decode(base64_key)
        .context("notification key must be base64")?
        .try_into()
        .map_err(|_| anyhow!("notification key must be {NOTIFICATION_KEY_LEN} bytes"))
}

fn seal(
    key: &[u8; NOTIFICATION_KEY_LEN],
    record_id: &str,
    content: &LnurlPayReceiveNotification,
) -> anyhow::Result<String> {
    let nonce: [u8; NONCE_LEN] = Aes256Gcm::generate_nonce(&mut OsRng).into();
    seal_with_nonce(key, record_id, content, &nonce)
}

fn seal_with_nonce(
    key: &[u8; NOTIFICATION_KEY_LEN],
    record_id: &str,
    content: &LnurlPayReceiveNotification,
    nonce: &[u8; NONCE_LEN],
) -> anyhow::Result<String> {
    let plaintext = serde_json::to_vec(content).context("failed to encode notification content")?;
    let ciphertext = Aes256Gcm::new(key.into())
        .encrypt(
            &Nonce::from(*nonce),
            Payload {
                msg: &plaintext,
                aad: additional_data(record_id).as_bytes(),
            },
        )
        .map_err(|_| anyhow!("failed to seal notification content"))?;
    let mut combined = nonce.to_vec();
    combined.extend(ciphertext);
    Ok(STANDARD.encode(combined))
}

fn additional_data(record_id: &str) -> String {
    format!("noah-lnurl-pay-receive-notification:v{ENVELOPE_VERSION}:{record_id}")
}

#[cfg(test)]
pub(crate) fn open(
    key: &[u8; NOTIFICATION_KEY_LEN],
    record_id: &str,
    sealed: &str,
) -> anyhow::Result<LnurlPayReceiveNotification> {
    let combined = STANDARD.decode(sealed)?;
    anyhow::ensure!(combined.len() > NONCE_LEN, "sealed content is too short");
    let (nonce, ciphertext) = combined.split_at(NONCE_LEN);
    let nonce: [u8; NONCE_LEN] = nonce.try_into()?;
    let plaintext = Aes256Gcm::new(key.into())
        .decrypt(
            &Nonce::from(nonce),
            Payload {
                msg: ciphertext,
                aad: additional_data(record_id).as_bytes(),
            },
        )
        .map_err(|_| anyhow!("sealed content did not open"))?;
    Ok(serde_json::from_slice(&plaintext)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    const FIXTURE: &str = "../apps/fixtures/lnurl-pay-receive-notification.json";

    #[derive(Serialize, Deserialize)]
    struct Fixture {
        description: String,
        key: String,
        cases: Vec<Case>,
    }

    #[derive(Serialize, Deserialize)]
    struct Case {
        name: String,
        id: String,
        sealed: String,
        /// `None` when the box must not open.
        expected: Option<LnurlPayReceiveNotification>,
    }

    fn content(
        amount_sat: u64,
        name: Option<&str>,
        identifier: Option<&str>,
        comment: Option<&str>,
    ) -> LnurlPayReceiveNotification {
        LnurlPayReceiveNotification {
            amount_sat,
            payer_data: (name.is_some() || identifier.is_some()).then(|| {
                LnurlPayReceivePayerData {
                    name: name.map(str::to_string),
                    identifier: identifier.map(str::to_string),
                }
            }),
            comment: comment.map(str::to_string),
        }
    }

    /// The cases both apps decrypt (apps/fixtures). `UPDATE_FIXTURES=1` rewrites the file.
    fn fixture() -> Fixture {
        let key = [0x5a; NOTIFICATION_KEY_LEN];
        let id = "0b7f3e0c-3c1a-4a59-9d55-5d0c2a1e7f01";
        let sealed = |content: &LnurlPayReceiveNotification, nonce: u8| {
            seal_with_nonce(&key, id, content, &[nonce; NONCE_LEN]).unwrap()
        };
        let full = content(
            9_880,
            Some("Alice Payer"),
            Some("alice@example.com"),
            Some("Thanks for the coffee ☕"),
        );
        let name_only = content(21, Some("Zoë 🚀"), None, None);
        let comment_only = content(1_000, None, None, Some("From the meetup"));
        let plain = content(330, None, None, None);
        let mut tampered = sealed(&full, 5);
        tampered.replace_range(20..21, if &tampered[20..21] == "A" { "B" } else { "A" });
        Fixture {
            description: "LNURL-pay receive notification content, sealed with AES-256-GCM under `key` (base64). `sealed` is base64 of nonce (12 bytes) || ciphertext || tag (16 bytes); the additional data is `noah-lnurl-pay-receive-notification:v1:<id>`. `expected` is the JSON inside, or null when the box must not open. Generated by server/src/lnurl_pay_notification.rs.".to_string(),
            key: STANDARD.encode(key),
            cases: vec![
                Case { name: "name, address and comment".to_string(), id: id.to_string(), sealed: sealed(&full, 1), expected: Some(full.clone()) },
                Case { name: "name only".to_string(), id: id.to_string(), sealed: sealed(&name_only, 2), expected: Some(name_only) },
                Case { name: "comment only".to_string(), id: id.to_string(), sealed: sealed(&comment_only, 3), expected: Some(comment_only) },
                Case { name: "no payer data or comment".to_string(), id: id.to_string(), sealed: sealed(&plain, 4), expected: Some(plain) },
                Case { name: "another record's ID".to_string(), id: "5d3c9a2e-7b41-4e0f-8c6d-1a2b3c4d5e6f".to_string(), sealed: sealed(&full, 1), expected: None },
                Case { name: "tampered ciphertext".to_string(), id: id.to_string(), sealed: tampered, expected: None },
            ],
        }
    }

    #[test]
    fn fixture_matches_what_the_server_seals() {
        let expected = serde_json::to_string_pretty(&fixture()).unwrap() + "\n";
        if std::env::var("UPDATE_FIXTURES").is_ok() {
            std::fs::write(FIXTURE, &expected).unwrap();
        }
        let stored = std::fs::read_to_string(FIXTURE)
            .unwrap()
            .replace("\r\n", "\n");
        assert_eq!(
            stored, expected,
            "{FIXTURE} is stale; run UPDATE_FIXTURES=1 cargo test -p server lnurl_pay_notification"
        );
    }

    #[test]
    fn sealed_content_opens_only_for_its_record() {
        let fixture = fixture();
        let key = parse_key(&fixture.key).unwrap();
        for case in fixture.cases {
            assert_eq!(
                open(&key, &case.id, &case.sealed).ok(),
                case.expected,
                "{}",
                case.name
            );
        }
    }

    #[test]
    fn push_field_seals_only_with_a_key() {
        let content = content(1_000, Some("Alice"), None, None);
        let key = [7; NOTIFICATION_KEY_LEN];

        let field = push_field("record", Some(&key), &content);
        assert_eq!(field["v"], 1);
        assert_eq!(field["id"], "record");
        let sealed = field["sealed"].as_str().unwrap();
        assert!(!sealed.contains("Alice"));
        assert_eq!(open(&key, "record", sealed).unwrap(), content);
        // A fresh nonce each time.
        assert_ne!(
            push_field("record", Some(&key), &content)["sealed"],
            field["sealed"]
        );

        assert_eq!(
            push_field("record", None, &content),
            serde_json::json!({"v": 1, "id": "record"})
        );
    }

    #[test]
    fn keys_are_32_base64_bytes() {
        assert_eq!(parse_key(&STANDARD.encode([1; 32])).unwrap(), [1; 32]);
        assert!(parse_key(&STANDARD.encode([1; 31])).is_err());
        assert!(parse_key("not base64!").is_err());
    }
}
