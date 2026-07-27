use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, anyhow};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use chacha20poly1305::{
    XChaCha20Poly1305, XNonce,
    aead::{Aead, AeadCore, KeyInit, OsRng, Payload},
};
use deadpool_redis::redis::{AsyncCommands, Script, pipe};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use uuid::Uuid;

use crate::types::{LnurlPayReceiveMetadata, LnurlPayReceivePayerData};

use super::redis_client::RedisClient;

const REQUEST_PREFIX: &str = "lnurl-pay-receive-metadata:request:";
const BINDING_PREFIX: &str = "lnurl-pay-receive-metadata:binding:";
const TRANSACTION_PREFIX: &str = "lnurl-pay-receive-metadata:transaction:";
const READY_PREFIX: &str = "lnurl-pay-receive-metadata:ready:";
const READY_INDEX_PREFIX: &str = "lnurl-pay-receive-metadata:ready-index:";
const INVOICE_PREFIX: &str = "invoice:";

pub const REQUEST_TTL_SECONDS: u64 = 120;
pub const READY_TTL_SECONDS: u64 = 7 * 24 * 60 * 60;
const INVOICE_TTL_SECONDS: u64 = 60;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct StoredLnurlPayReceiveMetadata {
    id: String,
    transaction_id: String,
    recipient_pubkey: String,
    payment_hash: Option<String>,
    amount_msat: u64,
    payer_data: Option<LnurlPayReceivePayerData>,
    comment: Option<String>,
    invoice: Option<String>,
    created_at: u64,
}

#[derive(Debug, Serialize, Deserialize)]
struct EncryptedEnvelope {
    version: u8,
    nonce: String,
    ciphertext: String,
}

enum ReadyRecordDisposition {
    Deliver(StoredLnurlPayReceiveMetadata),
    RemoveReference,
    RemoveOwned(StoredLnurlPayReceiveMetadata),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BindInvoiceOutcome {
    Stored,
    Idempotent,
}

#[derive(Debug, thiserror::Error)]
pub enum BindInvoiceError {
    #[error("LNURL-pay receive metadata request expired")]
    Expired,
    #[error("LNURL-pay receive metadata request does not match invoice submitter")]
    RequestMismatch,
    #[error("Conflicting LNURL-pay invoice submission")]
    Conflict,
    #[error("LNURL-pay receive metadata store unavailable: {0}")]
    Infrastructure(#[source] anyhow::Error),
}

impl BindInvoiceError {
    fn infrastructure(error: impl Into<anyhow::Error>) -> Self {
        Self::Infrastructure(error.into())
    }

    pub fn is_retryable(&self) -> bool {
        matches!(self, Self::Infrastructure(_))
    }
}

#[derive(Clone)]
pub struct LnurlPayReceiveMetadataStore {
    client: RedisClient,
    cipher: XChaCha20Poly1305,
}

impl LnurlPayReceiveMetadataStore {
    pub fn new(client: RedisClient, encryption_key: &[u8; 32]) -> Self {
        Self {
            client,
            cipher: XChaCha20Poly1305::new(encryption_key.into()),
        }
    }

    pub async fn store_request(
        &self,
        transaction_id: &str,
        recipient_pubkey: &str,
        amount_msat: u64,
        payer_data: Option<LnurlPayReceivePayerData>,
        comment: Option<String>,
    ) -> anyhow::Result<()> {
        let key = request_key(transaction_id);
        let record = StoredLnurlPayReceiveMetadata {
            id: Uuid::new_v4().to_string(),
            transaction_id: transaction_id.to_string(),
            recipient_pubkey: recipient_pubkey.to_string(),
            payment_hash: None,
            amount_msat,
            payer_data,
            comment,
            invoice: None,
            created_at: unix_timestamp()?,
        };
        let encrypted = self.encrypt(&key, &record)?;
        let mut conn = self.client.get_connection().await?;
        let _: () = conn.set_ex(key, encrypted, REQUEST_TTL_SECONDS).await?;
        Ok(())
    }

    pub async fn bind_invoice(
        &self,
        transaction_id: &str,
        recipient_pubkey: &str,
        payment_hash: &str,
        amount_msat: u64,
        invoice: &str,
        binding_ttl_seconds: u64,
    ) -> Result<BindInvoiceOutcome, BindInvoiceError> {
        let request_key = request_key(transaction_id);
        let binding_key = binding_key(recipient_pubkey, payment_hash);
        let transaction_key = transaction_key(transaction_id);
        let invoice_key = invoice_key(transaction_id);
        let mut conn = self
            .client
            .get_connection()
            .await
            .map_err(BindInvoiceError::infrastructure)?;

        let request_ciphertext: Option<String> = conn
            .get(&request_key)
            .await
            .map_err(BindInvoiceError::infrastructure)?;
        let Some(request_ciphertext) = request_ciphertext else {
            return self
                .validate_idempotent_binding(
                    &mut conn,
                    &transaction_key,
                    recipient_pubkey,
                    payment_hash,
                    amount_msat,
                    invoice,
                )
                .await;
        };

        let mut record: StoredLnurlPayReceiveMetadata = self
            .decrypt(&request_key, &request_ciphertext)
            .map_err(BindInvoiceError::infrastructure)?;
        if record.recipient_pubkey != recipient_pubkey || record.amount_msat != amount_msat {
            return Err(BindInvoiceError::RequestMismatch);
        }

        record.payment_hash = Some(payment_hash.to_string());
        record.invoice = Some(invoice.to_string());
        let binding_ciphertext = self
            .encrypt(&binding_key, &record)
            .map_err(BindInvoiceError::infrastructure)?;
        let transaction_ciphertext = self
            .encrypt(&transaction_key, &record)
            .map_err(BindInvoiceError::infrastructure)?;

        let script = Script::new(
            r#"
            local request = redis.call('GET', KEYS[1])
            if not request then
                if redis.call('EXISTS', KEYS[3]) == 1 then return 2 end
                return 0
            end
            if request ~= ARGV[1] then return -1 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return -2 end
            redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[4])
            redis.call('SET', KEYS[3], ARGV[3], 'EX', ARGV[4])
            redis.call('SET', KEYS[4], ARGV[5], 'EX', ARGV[6])
            redis.call('DEL', KEYS[1])
            return 1
            "#,
        );
        let result: i64 = script
            .key(&request_key)
            .key(&binding_key)
            .key(&transaction_key)
            .key(&invoice_key)
            .arg(&request_ciphertext)
            .arg(&binding_ciphertext)
            .arg(&transaction_ciphertext)
            .arg(binding_ttl_seconds.max(1))
            .arg(invoice)
            .arg(INVOICE_TTL_SECONDS)
            .invoke_async(&mut conn)
            .await
            .map_err(BindInvoiceError::infrastructure)?;

        match result {
            1 => Ok(BindInvoiceOutcome::Stored),
            2 => {
                self.validate_idempotent_binding(
                    &mut conn,
                    &transaction_key,
                    recipient_pubkey,
                    payment_hash,
                    amount_msat,
                    invoice,
                )
                .await
            }
            0 => Err(BindInvoiceError::Expired),
            -1 | -2 => Err(BindInvoiceError::Conflict),
            _ => Err(BindInvoiceError::infrastructure(anyhow!(
                "Unexpected LNURL-pay receive metadata store response"
            ))),
        }
    }

    async fn validate_idempotent_binding(
        &self,
        conn: &mut deadpool_redis::Connection,
        transaction_key: &str,
        recipient_pubkey: &str,
        payment_hash: &str,
        amount_msat: u64,
        invoice: &str,
    ) -> Result<BindInvoiceOutcome, BindInvoiceError> {
        let existing: Option<String> = conn
            .get(transaction_key)
            .await
            .map_err(BindInvoiceError::infrastructure)?;
        let Some(existing) = existing else {
            return Err(BindInvoiceError::Expired);
        };
        let record: StoredLnurlPayReceiveMetadata = self
            .decrypt(transaction_key, &existing)
            .map_err(BindInvoiceError::infrastructure)?;
        if record.recipient_pubkey == recipient_pubkey
            && record.payment_hash.as_deref() == Some(payment_hash)
            && record.amount_msat == amount_msat
            && record.invoice.as_deref() == Some(invoice)
        {
            Ok(BindInvoiceOutcome::Idempotent)
        } else {
            Err(BindInvoiceError::Conflict)
        }
    }

    pub async fn mark_ready(
        &self,
        recipient_pubkey: &str,
        payment_hash: &str,
        amount_msat: u64,
    ) -> anyhow::Result<bool> {
        let binding_key = binding_key(recipient_pubkey, payment_hash);
        let mut conn = self.client.get_connection().await?;
        let binding_ciphertext: Option<String> = conn.get(&binding_key).await?;
        let Some(binding_ciphertext) = binding_ciphertext else {
            return Ok(false);
        };
        let record: StoredLnurlPayReceiveMetadata =
            match self.decrypt(&binding_key, &binding_ciphertext) {
                Ok(record) => record,
                Err(_) => {
                    tracing::warn!(
                        service = "lnurl_pay_receive_metadata_store",
                        "discarding unreadable LNURL-pay receive metadata binding"
                    );
                    let _: usize = conn.del(&binding_key).await?;
                    return Ok(false);
                }
            };
        if record.recipient_pubkey != recipient_pubkey
            || record.payment_hash.as_deref() != Some(payment_hash)
            || record.amount_msat != amount_msat
        {
            tracing::warn!(
                service = "lnurl_pay_receive_metadata_store",
                "discarding mismatched LNURL-pay receive metadata binding"
            );
            let _: usize = conn.del(&binding_key).await?;
            return Ok(false);
        }

        let ready_key = ready_key(&record.id);
        let ready_index_key = ready_index_key(recipient_pubkey);
        let transaction_key = transaction_key(&record.transaction_id);
        let ready_ciphertext = self.encrypt(&ready_key, &record)?;
        let script = Script::new(
            r#"
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
            redis.call('SADD', KEYS[3], ARGV[4])
            redis.call('EXPIRE', KEYS[3], ARGV[3])
            redis.call('DEL', KEYS[1])
            redis.call('EXPIRE', KEYS[4], ARGV[3])
            return 1
            "#,
        );
        let result: i64 = script
            .key(&binding_key)
            .key(&ready_key)
            .key(&ready_index_key)
            .key(&transaction_key)
            .arg(&binding_ciphertext)
            .arg(&ready_ciphertext)
            .arg(READY_TTL_SECONDS)
            .arg(&record.id)
            .invoke_async(&mut conn)
            .await?;
        Ok(result == 1)
    }

    pub async fn list_ready(
        &self,
        recipient_pubkey: &str,
        limit: usize,
    ) -> anyhow::Result<Vec<LnurlPayReceiveMetadata>> {
        let index_key = ready_index_key(recipient_pubkey);
        let mut conn = self.client.get_connection().await?;
        let mut ids: Vec<String> = conn.smembers(&index_key).await?;
        ids.sort_unstable();

        let mut items = Vec::new();
        let mut stale_ids = Vec::new();
        let mut owned_stale_records = Vec::new();
        for id in ids {
            if items.len() >= limit {
                break;
            }
            let key = ready_key(&id);
            let encrypted: Option<String> = conn.get(&key).await?;
            let Some(encrypted) = encrypted else {
                stale_ids.push(id);
                continue;
            };
            let record = match self.ready_record_disposition(
                &key,
                &encrypted,
                recipient_pubkey,
                &id,
            ) {
                ReadyRecordDisposition::Deliver(record) => record,
                ReadyRecordDisposition::RemoveReference => {
                    tracing::warn!(
                        service = "lnurl_pay_receive_metadata_store",
                        "removing unreadable or mismatched LNURL-pay receive metadata index entry"
                    );
                    stale_ids.push(id);
                    continue;
                }
                ReadyRecordDisposition::RemoveOwned(record) => {
                    tracing::warn!(
                        service = "lnurl_pay_receive_metadata_store",
                        "removing invalid LNURL-pay receive metadata record"
                    );
                    stale_ids.push(id);
                    owned_stale_records.push((key, record.transaction_id));
                    continue;
                }
            };
            let payment_hash = record
                .payment_hash
                .expect("ready record disposition requires a payment hash");
            items.push(LnurlPayReceiveMetadata {
                id: record.id,
                payment_hash,
                amount_sat: record.amount_msat / 1000,
                payer_data: record.payer_data,
                comment: record.comment,
            });
        }

        if !stale_ids.is_empty() {
            let mut cleanup = pipe();
            cleanup.atomic();
            for id in stale_ids {
                cleanup.srem(&index_key, id).ignore();
            }
            for (ready_key, transaction_id) in owned_stale_records {
                cleanup
                    .del(ready_key)
                    .ignore()
                    .del(transaction_key(&transaction_id))
                    .ignore();
            }
            cleanup.query_async::<()>(&mut conn).await?;
        }
        Ok(items)
    }

    pub async fn acknowledge(&self, recipient_pubkey: &str, ids: &[String]) -> anyhow::Result<()> {
        let index_key = ready_index_key(recipient_pubkey);
        let mut conn = self.client.get_connection().await?;
        for id in ids {
            let key = ready_key(id);
            let encrypted: Option<String> = conn.get(&key).await?;
            let Some(encrypted) = encrypted else {
                let _: usize = conn.srem(&index_key, id).await?;
                continue;
            };
            let record = match self.ready_record_disposition(&key, &encrypted, recipient_pubkey, id)
            {
                ReadyRecordDisposition::Deliver(record) => record,
                ReadyRecordDisposition::RemoveReference => {
                    tracing::warn!(
                        service = "lnurl_pay_receive_metadata_store",
                        "removing unreadable or mismatched LNURL-pay receive metadata index entry"
                    );
                    let _: usize = conn.srem(&index_key, id).await?;
                    continue;
                }
                ReadyRecordDisposition::RemoveOwned(record) => {
                    tracing::warn!(
                        service = "lnurl_pay_receive_metadata_store",
                        "removing invalid LNURL-pay receive metadata record"
                    );
                    let mut cleanup = pipe();
                    cleanup
                        .atomic()
                        .del(&key)
                        .ignore()
                        .srem(&index_key, id)
                        .ignore()
                        .del(transaction_key(&record.transaction_id))
                        .ignore();
                    cleanup.query_async::<()>(&mut conn).await?;
                    continue;
                }
            };
            let mut acknowledgement = pipe();
            acknowledgement
                .atomic()
                .del(&key)
                .ignore()
                .srem(&index_key, id)
                .ignore()
                .del(transaction_key(&record.transaction_id))
                .ignore();
            acknowledgement.query_async::<()>(&mut conn).await?;
        }
        Ok(())
    }

    fn ready_record_disposition(
        &self,
        redis_key: &str,
        encrypted: &str,
        recipient_pubkey: &str,
        indexed_id: &str,
    ) -> ReadyRecordDisposition {
        let Ok(record) = self.decrypt::<StoredLnurlPayReceiveMetadata>(redis_key, encrypted) else {
            return ReadyRecordDisposition::RemoveReference;
        };
        if record.recipient_pubkey != recipient_pubkey {
            return ReadyRecordDisposition::RemoveReference;
        }
        if record.id != indexed_id || record.payment_hash.is_none() {
            return ReadyRecordDisposition::RemoveOwned(record);
        }
        ReadyRecordDisposition::Deliver(record)
    }

    fn encrypt<T: Serialize>(&self, redis_key: &str, value: &T) -> anyhow::Result<String> {
        let plaintext =
            serde_json::to_vec(value).context("Failed to encode LNURL-pay receive metadata")?;
        let nonce = XChaCha20Poly1305::generate_nonce(&mut OsRng);
        let ciphertext = self
            .cipher
            .encrypt(
                &nonce,
                Payload {
                    msg: &plaintext,
                    aad: redis_key.as_bytes(),
                },
            )
            .map_err(|_| anyhow!("Failed to encrypt LNURL-pay receive metadata"))?;
        serde_json::to_string(&EncryptedEnvelope {
            version: 1,
            nonce: BASE64.encode(nonce),
            ciphertext: BASE64.encode(ciphertext),
        })
        .context("Failed to encode encrypted LNURL-pay receive metadata")
    }

    fn decrypt<T: DeserializeOwned>(&self, redis_key: &str, value: &str) -> anyhow::Result<T> {
        let envelope: EncryptedEnvelope =
            serde_json::from_str(value).context("Invalid encrypted LNURL-pay receive metadata")?;
        if envelope.version != 1 {
            return Err(anyhow!(
                "Unsupported encrypted LNURL-pay receive metadata version"
            ));
        }
        let nonce = BASE64
            .decode(envelope.nonce)
            .context("Invalid encrypted LNURL-pay receive metadata nonce")?;
        let nonce: [u8; 24] = nonce
            .try_into()
            .map_err(|_| anyhow!("Invalid encrypted LNURL-pay receive metadata nonce length"))?;
        let nonce = XNonce::from(nonce);
        let ciphertext = BASE64
            .decode(envelope.ciphertext)
            .context("Invalid encrypted LNURL-pay receive metadata payload")?;
        let plaintext = self
            .cipher
            .decrypt(
                &nonce,
                Payload {
                    msg: &ciphertext,
                    aad: redis_key.as_bytes(),
                },
            )
            .map_err(|_| anyhow!("Failed to decrypt LNURL-pay receive metadata"))?;
        serde_json::from_slice(&plaintext).context("Invalid decrypted LNURL-pay receive metadata")
    }
}

fn unix_timestamp() -> anyhow::Result<u64> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .context("System time is before the Unix epoch")?
        .as_secs())
}

fn request_key(transaction_id: &str) -> String {
    format!("{REQUEST_PREFIX}{transaction_id}")
}

fn binding_key(recipient_pubkey: &str, payment_hash: &str) -> String {
    format!("{BINDING_PREFIX}{recipient_pubkey}:{payment_hash}")
}

fn transaction_key(transaction_id: &str) -> String {
    format!("{TRANSACTION_PREFIX}{transaction_id}")
}

fn ready_key(id: &str) -> String {
    format!("{READY_PREFIX}{id}")
}

fn ready_index_key(recipient_pubkey: &str) -> String {
    format!("{READY_INDEX_PREFIX}{recipient_pubkey}")
}

fn invoice_key(transaction_id: &str) -> String {
    format!("{INVOICE_PREFIX}{transaction_id}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn redis_request_payload_is_encrypted() {
        let redis_url = std::env::var("TEST_REDIS_URL")
            .unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string());
        let client = RedisClient::new(&redis_url).unwrap();
        let store = LnurlPayReceiveMetadataStore::new(client, &[0x42; 32]);
        let transaction_id = Uuid::new_v4().to_string();
        let key = request_key(&transaction_id);
        let record = StoredLnurlPayReceiveMetadata {
            id: Uuid::new_v4().to_string(),
            transaction_id,
            recipient_pubkey: "recipient".to_string(),
            payment_hash: None,
            amount_msat: 42_000,
            payer_data: Some(LnurlPayReceivePayerData {
                name: Some("Sensitive Alice".to_string()),
                identifier: Some("alice@example.com".to_string()),
            }),
            comment: Some("Sensitive comment".to_string()),
            invoice: None,
            created_at: 1,
        };
        let raw = store.encrypt(&key, &record).unwrap();
        assert!(!raw.contains("Sensitive Alice"));
        assert!(!raw.contains("alice@example.com"));
        assert!(!raw.contains("Sensitive comment"));
        let decrypted: StoredLnurlPayReceiveMetadata = store.decrypt(&key, &raw).unwrap();
        assert_eq!(decrypted, record);
    }

    #[test]
    fn ready_record_disposition_quarantines_bad_entries() {
        let redis_url = std::env::var("TEST_REDIS_URL")
            .unwrap_or_else(|_| "redis://127.0.0.1:6379".to_string());
        let client = RedisClient::new(&redis_url).unwrap();
        let store = LnurlPayReceiveMetadataStore::new(client, &[0x42; 32]);
        let record = StoredLnurlPayReceiveMetadata {
            id: Uuid::new_v4().to_string(),
            transaction_id: Uuid::new_v4().to_string(),
            recipient_pubkey: "recipient".to_string(),
            payment_hash: Some("payment-hash".to_string()),
            amount_msat: 42_000,
            payer_data: None,
            comment: None,
            invoice: Some("invoice".to_string()),
            created_at: 1,
        };
        let key = ready_key(&record.id);
        let encrypted = store.encrypt(&key, &record).unwrap();

        match store.ready_record_disposition(&key, &encrypted, "recipient", &record.id) {
            ReadyRecordDisposition::Deliver(decoded) => assert_eq!(decoded, record),
            _ => panic!("valid ready record should be delivered"),
        }
        assert!(matches!(
            store.ready_record_disposition(&key, "not-an-envelope", "recipient", &record.id),
            ReadyRecordDisposition::RemoveReference
        ));

        let mut foreign = record.clone();
        foreign.recipient_pubkey = "another-recipient".to_string();
        let encrypted_foreign = store.encrypt(&key, &foreign).unwrap();
        assert!(matches!(
            store.ready_record_disposition(&key, &encrypted_foreign, "recipient", &record.id),
            ReadyRecordDisposition::RemoveReference
        ));

        let mut invalid_owned = record.clone();
        invalid_owned.payment_hash = None;
        let encrypted_invalid_owned = store.encrypt(&key, &invalid_owned).unwrap();
        assert!(matches!(
            store.ready_record_disposition(&key, &encrypted_invalid_owned, "recipient", &record.id),
            ReadyRecordDisposition::RemoveOwned(_)
        ));
    }
}
