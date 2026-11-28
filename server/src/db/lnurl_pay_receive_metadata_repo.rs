//! Encrypted LUD-12/18 receive metadata for payments barkd forwards (see docs/lnurl_pay.md).
//!
//! A record is reserved before barkd is asked for an invoice, finalized with that invoice, and
//! made ready when the recipient's mailbox delivers the forwarded VTXOs. The app lists ready
//! records and acknowledges each once Bark's movement carries it, which deletes it.

use anyhow::{Context, anyhow};
use bitcoin::hashes::{Hash, HashEngine, sha256};
use chacha20poly1305::{
    XChaCha20Poly1305, XNonce,
    aead::{Aead, AeadCore, KeyInit, OsRng, Payload},
};
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use sqlx::{PgPool, Postgres, Row, Transaction};
use uuid::Uuid;

use crate::types::{LnurlPayReceiveMetadata, LnurlPayReceivePayerData, LnurlPayReceiveTarget};

const PAYLOAD_SCHEMA_VERSION: u8 = 1;
/// How long a reservation may wait for barkd's invoice.
const RESERVATION_TTL: Duration = Duration::minutes(2);
/// LNURL-pay invoices are paid right away; a payment after this arrives without metadata.
pub(crate) const CORRELATION_TTL: Duration = Duration::hours(1);
/// How long a ready record waits for an offline recipient. Never extended.
const ABSOLUTE_RETENTION: Duration = Duration::days(7);
/// Public callbacks would otherwise grow one recipient's barkd lookups without bound.
pub(crate) const MAX_ACTIVE_CORRELATIONS_PER_RECIPIENT: i64 = 128;
/// Serializes one recipient's quota checks; the second key of `hashtextextended`.
const QUOTA_LOCK_SEED: i64 = 1313816904;

/// The pending records an observation (`$1`) can match that its current scan hasn't checked.
/// Records created after the delivery can't be its payment. A macro so the SQL stays a literal.
macro_rules! unchecked_candidates_sql {
    () => {
        "FROM lnurl_pay_receive_metadata AS metadata
         INNER JOIN lnurl_pay_receive_arkoor_observations AS observation
            ON observation.id = $1
           AND observation.recipient_pubkey = metadata.recipient_pubkey
           AND observation.metadata_id IS NULL
         WHERE metadata.state = 'awaiting_payment'
           AND metadata.expires_at > now()
           AND metadata.correlation_expires_at > clock_timestamp()
           AND metadata.created_at <= observation.created_at
           AND (
               metadata.correlation_checked_at IS NULL
               OR metadata.correlation_checked_at < observation.correlation_scan_started_at
           )"
    };
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LiveCorrelation {
    pub metadata_id: Uuid,
    pub invoice: String,
    pub payment_hash: String,
    pub amount_msat: u64,
    pub ark_address: String,
}

#[derive(Debug)]
pub enum CorrelationCandidate {
    Decoded(LiveCorrelation),
    /// A payload this server version can't read, such as one a newer version wrote.
    Unreadable {
        metadata_id: Uuid,
    },
}

impl CorrelationCandidate {
    pub fn metadata_id(&self) -> Uuid {
        match self {
            Self::Decoded(correlation) => correlation.metadata_id,
            Self::Unreadable { metadata_id } => *metadata_id,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CorrelationPageState {
    pub has_more: bool,
    pub matched_metadata_id: Option<Uuid>,
    pub requires_retry: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArkoorObservation {
    pub id: Uuid,
    pub output_vtxo_ids: Vec<String>,
    pub ancestor_vtxo_ids: Vec<String>,
    pub delivered_amount_sat: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct StoredMetadataPayload {
    version: u8,
    amount_msat: u64,
    payer_data: Option<LnurlPayReceivePayerData>,
    comment: Option<String>,
    invoice: Option<String>,
    payment_hash: Option<String>,
    ark_address: Option<String>,
    target: Option<LnurlPayReceiveTarget>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct StoredArkoorObservation {
    version: u8,
    output_vtxo_ids: Vec<String>,
    ancestor_vtxo_ids: Vec<String>,
    delivered_amount_sat: u64,
}

#[derive(Clone)]
pub struct LnurlPayReceiveMetadataRepository {
    pool: PgPool,
    cipher: XChaCha20Poly1305,
    lookup_key: [u8; 32],
}

impl LnurlPayReceiveMetadataRepository {
    pub fn new(pool: &PgPool, encryption_key: &[u8; 32]) -> Self {
        Self {
            pool: pool.clone(),
            cipher: XChaCha20Poly1305::new(encryption_key.into()),
            lookup_key: *encryption_key,
        }
    }

    /// Reserves a correlation slot before barkd issues an invoice. `None` when the recipient
    /// already has the maximum number of pending correlations.
    pub async fn reserve(
        &self,
        recipient_pubkey: &str,
        amount_msat: u64,
        payer_data: Option<LnurlPayReceivePayerData>,
        comment: Option<String>,
        ark_address: &str,
    ) -> anyhow::Result<Option<Uuid>> {
        let id = Uuid::new_v4();
        let now = Utc::now();
        let payload = StoredMetadataPayload {
            version: PAYLOAD_SCHEMA_VERSION,
            amount_msat,
            payer_data,
            comment,
            invoice: None,
            payment_hash: None,
            ark_address: Some(ark_address.to_string()),
            target: None,
        };
        let (nonce, ciphertext) = self.encrypt(metadata_aad(id), &payload)?;
        let mut tx = self.pool.begin().await?;

        // Serialize this recipient's quota checks so concurrent callbacks can't exceed the cap.
        lock_recipient_quota(&mut tx, recipient_pubkey).await?;
        let pending_count = sqlx::query_scalar::<_, i64>(PENDING_COUNT_SQL)
            .bind(recipient_pubkey)
            .fetch_one(&mut *tx)
            .await?;
        if pending_count >= MAX_ACTIVE_CORRELATIONS_PER_RECIPIENT {
            return Ok(None);
        }

        sqlx::query(
            "INSERT INTO lnurl_pay_receive_metadata (
                id, recipient_pubkey, state, encrypted_payload, nonce,
                request_expires_at, correlation_expires_at, expires_at
             ) VALUES ($1, $2, 'awaiting_invoice', $3, $4, $5, $6, $7)",
        )
        .bind(id)
        .bind(recipient_pubkey)
        .bind(ciphertext)
        .bind(nonce)
        .bind(now + RESERVATION_TTL)
        .bind(now + CORRELATION_TTL)
        .bind(now + ABSOLUTE_RETENTION)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;

        Ok(Some(id))
    }

    /// Binds barkd's invoice to a reservation, which then waits for the payment.
    pub async fn finalize(
        &self,
        id: Uuid,
        recipient_pubkey: &str,
        invoice: &str,
        payment_hash: &str,
        invoice_expires_at: DateTime<Utc>,
    ) -> anyhow::Result<()> {
        let mut tx = self.pool.begin().await?;
        // Stay in the quota's critical section: otherwise a reservation could expire out of a
        // concurrent count just before this promotes it to a retained pending record.
        lock_recipient_quota(&mut tx, recipient_pubkey).await?;
        let row = sqlx::query(
            "SELECT recipient_pubkey, state, request_expires_at, expires_at,
                    encrypted_payload, nonce
             FROM lnurl_pay_receive_metadata
             WHERE id = $1
             FOR UPDATE",
        )
        .bind(id)
        .fetch_optional(&mut *tx)
        .await?
        .context("LNURL-pay receive reservation disappeared")?;
        if row.get::<String, _>("recipient_pubkey") != recipient_pubkey
            || row.get::<String, _>("state") != "awaiting_invoice"
            || row
                .get::<Option<DateTime<Utc>>, _>("request_expires_at")
                .is_none_or(|expires_at| expires_at <= Utc::now())
        {
            anyhow::bail!("LNURL-pay receive reservation is no longer active");
        }
        if invoice_expires_at > row.get::<DateTime<Utc>, _>("expires_at") {
            anyhow::bail!("barkd invoice outlives receive metadata retention");
        }

        let mut payload = self.decrypt_metadata_row(id, &row)?;
        if payload.invoice.is_some() || payload.payment_hash.is_some() {
            anyhow::bail!("LNURL-pay receive reservation was already finalized");
        }
        payload.invoice = Some(invoice.to_string());
        payload.payment_hash = Some(payment_hash.to_string());
        let (nonce, ciphertext) = self.encrypt(metadata_aad(id), &payload)?;
        sqlx::query(
            "UPDATE lnurl_pay_receive_metadata
             SET state = 'awaiting_payment', payment_hash_lookup = $2,
                 encrypted_payload = $3, nonce = $4, invoice_expires_at = $5,
                 request_expires_at = NULL, updated_at = now()
             WHERE id = $1",
        )
        .bind(id)
        .bind(self.payment_hash_lookup(payment_hash).to_vec())
        .bind(ciphertext)
        .bind(nonce)
        .bind(invoice_expires_at)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn delete_reservation(&self, id: Uuid) -> anyhow::Result<bool> {
        let result = sqlx::query(
            "DELETE FROM lnurl_pay_receive_metadata
             WHERE id = $1 AND state = 'awaiting_invoice'",
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(result.rows_affected() > 0)
    }

    pub async fn has_capacity(&self, recipient_pubkey: &str) -> anyhow::Result<bool> {
        let pending_count = sqlx::query_scalar::<_, i64>(PENDING_COUNT_SQL)
            .bind(recipient_pubkey)
            .fetch_one(&self.pool)
            .await?;
        Ok(pending_count < MAX_ACTIVE_CORRELATIONS_PER_RECIPIENT)
    }

    /// Stores one mailbox Arkoor delivery, or returns the one already stored for this checkpoint.
    pub async fn store_arkoor_observation(
        &self,
        recipient_pubkey: &str,
        mailbox_id: &str,
        mailbox_checkpoint: i64,
        output_vtxo_ids: &[String],
        ancestor_vtxo_ids: &[String],
        delivered_amount_sat: u64,
    ) -> anyhow::Result<ArkoorObservation> {
        let observation = StoredArkoorObservation {
            version: PAYLOAD_SCHEMA_VERSION,
            output_vtxo_ids: output_vtxo_ids.to_vec(),
            ancestor_vtxo_ids: ancestor_vtxo_ids.to_vec(),
            delivered_amount_sat,
        };
        let mut tx = self.pool.begin().await?;
        if let Some(row) = sqlx::query(
            "SELECT id, encrypted_payload, nonce
             FROM lnurl_pay_receive_arkoor_observations
             WHERE recipient_pubkey = $1 AND mailbox_id = $2 AND mailbox_checkpoint = $3
             FOR UPDATE",
        )
        .bind(recipient_pubkey)
        .bind(mailbox_id)
        .bind(mailbox_checkpoint)
        .fetch_optional(&mut *tx)
        .await?
        {
            let id: Uuid = row.get("id");
            let existing: StoredArkoorObservation = self.decrypt(
                observation_aad(id, recipient_pubkey, mailbox_id, mailbox_checkpoint),
                row.get::<Vec<u8>, _>("nonce").as_slice(),
                row.get::<Vec<u8>, _>("encrypted_payload").as_slice(),
            )?;
            if existing != observation {
                return Err(anyhow!(
                    "mailbox checkpoint is already bound to a different Ark observation"
                ));
            }
            return Ok(ArkoorObservation {
                id,
                output_vtxo_ids: existing.output_vtxo_ids,
                ancestor_vtxo_ids: existing.ancestor_vtxo_ids,
                delivered_amount_sat: existing.delivered_amount_sat,
            });
        }

        let id = Uuid::new_v4();
        let (nonce, ciphertext) = self.encrypt(
            observation_aad(id, recipient_pubkey, mailbox_id, mailbox_checkpoint),
            &observation,
        )?;
        sqlx::query(
            "INSERT INTO lnurl_pay_receive_arkoor_observations (
                id, recipient_pubkey, mailbox_id, mailbox_checkpoint, encrypted_payload, nonce,
                expires_at
             ) VALUES ($1, $2, $3, $4, $5, $6, $7)",
        )
        .bind(id)
        .bind(recipient_pubkey)
        .bind(mailbox_id)
        .bind(mailbox_checkpoint)
        .bind(ciphertext)
        .bind(nonce)
        .bind(Utc::now() + ABSOLUTE_RETENTION)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;

        Ok(ArkoorObservation {
            id,
            output_vtxo_ids: output_vtxo_ids.to_vec(),
            ancestor_vtxo_ids: ancestor_vtxo_ids.to_vec(),
            delivered_amount_sat,
        })
    }

    /// One page of the recipient's pending records this observation's scan hasn't checked yet,
    /// newest first.
    pub async fn list_unchecked_correlations(
        &self,
        observation_id: Uuid,
        limit: usize,
    ) -> anyhow::Result<Vec<CorrelationCandidate>> {
        let limit = i64::try_from(limit).context("correlation page limit is too large")?;
        let rows = sqlx::query(concat!(
            "SELECT metadata.id, metadata.encrypted_payload, metadata.nonce ",
            unchecked_candidates_sql!(),
            " ORDER BY metadata.created_at DESC, metadata.id DESC LIMIT $2"
        ))
        .bind(observation_id)
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;

        Ok(rows
            .into_iter()
            .map(|row| {
                let id: Uuid = row.get("id");
                let decoded = self.decrypt_metadata_row(id, &row).and_then(|payload| {
                    Ok(LiveCorrelation {
                        metadata_id: id,
                        invoice: payload.invoice.context("pending record has no invoice")?,
                        payment_hash: payload
                            .payment_hash
                            .context("pending record has no payment hash")?,
                        amount_msat: payload.amount_msat,
                        ark_address: payload
                            .ark_address
                            .context("pending record has no Ark address")?,
                    })
                });
                match decoded {
                    Ok(correlation) => CorrelationCandidate::Decoded(correlation),
                    Err(error) => {
                        tracing::warn!(
                            metadata_id = %id,
                            error = %error,
                            "LNURL-pay receive record could not be read by this server version"
                        );
                        CorrelationCandidate::Unreadable { metadata_id: id }
                    }
                }
            })
            .collect())
    }

    /// Records one scan page and reports whether this observation has more candidates. Pending,
    /// unreadable and failed-lookup records rotate with the page while the stored retry flag
    /// keeps the mailbox checkpoint from advancing after the pass.
    pub async fn finish_correlation_page(
        &self,
        observation_id: Uuid,
        checked_metadata_ids: &[Uuid],
        matched_metadata_id: Option<Uuid>,
        requires_retry: bool,
    ) -> anyhow::Result<CorrelationPageState> {
        let mut tx = self.pool.begin().await?;
        let observation = sqlx::query(
            "SELECT correlation_matched_metadata_id, correlation_requires_retry
             FROM lnurl_pay_receive_arkoor_observations
             WHERE id = $1 AND metadata_id IS NULL
             FOR UPDATE",
        )
        .bind(observation_id)
        .fetch_optional(&mut *tx)
        .await?
        .context("Ark observation disappeared during correlation")?;
        let existing_match: Option<Uuid> = observation.get("correlation_matched_metadata_id");
        let existing_match = match existing_match {
            Some(metadata_id) => self
                .is_active_correlation_in_tx(&mut tx, observation_id, metadata_id)
                .await?
                .then_some(metadata_id),
            None => None,
        };
        if existing_match.is_some()
            && matched_metadata_id.is_some()
            && existing_match != matched_metadata_id
        {
            anyhow::bail!("mailbox Ark observation matched several LNURL-pay receives");
        }
        let matched_metadata_id = match existing_match.or(matched_metadata_id) {
            Some(metadata_id) => self
                .is_active_correlation_in_tx(&mut tx, observation_id, metadata_id)
                .await?
                .then_some(metadata_id),
            None => None,
        };
        let requires_retry =
            observation.get::<bool, _>("correlation_requires_retry") || requires_retry;

        if !checked_metadata_ids.is_empty() {
            sqlx::query(
                "UPDATE lnurl_pay_receive_metadata AS metadata
                 SET correlation_checked_at = clock_timestamp()
                 FROM lnurl_pay_receive_arkoor_observations AS observation
                 WHERE observation.id = $1
                   AND observation.metadata_id IS NULL
                   AND metadata.id = ANY($2)
                   AND metadata.recipient_pubkey = observation.recipient_pubkey
                   AND metadata.created_at <= observation.created_at",
            )
            .bind(observation_id)
            .bind(checked_metadata_ids)
            .execute(&mut *tx)
            .await?;
        }

        sqlx::query(
            "UPDATE lnurl_pay_receive_arkoor_observations
             SET correlation_matched_metadata_id = $2,
                 correlation_requires_retry = $3
             WHERE id = $1 AND metadata_id IS NULL",
        )
        .bind(observation_id)
        .bind(matched_metadata_id)
        .bind(requires_retry)
        .execute(&mut *tx)
        .await?;

        let has_more = sqlx::query_scalar::<_, bool>(concat!(
            "SELECT EXISTS (SELECT 1 ",
            unchecked_candidates_sql!(),
            ")"
        ))
        .bind(observation_id)
        .fetch_one(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(CorrelationPageState {
            has_more,
            matched_metadata_id,
            requires_retry,
        })
    }

    async fn is_active_correlation_in_tx(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        observation_id: Uuid,
        metadata_id: Uuid,
    ) -> anyhow::Result<bool> {
        Ok(sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS (
                SELECT 1
                FROM lnurl_pay_receive_metadata AS metadata
                INNER JOIN lnurl_pay_receive_arkoor_observations AS observation
                   ON observation.id = $1
                  AND observation.recipient_pubkey = metadata.recipient_pubkey
                  AND observation.metadata_id IS NULL
                WHERE metadata.id = $2
                  AND metadata.state = 'awaiting_payment'
                  AND metadata.expires_at > clock_timestamp()
                  AND metadata.correlation_expires_at > clock_timestamp()
                  AND metadata.created_at <= observation.created_at
             )",
        )
        .bind(observation_id)
        .bind(metadata_id)
        .fetch_one(&mut **tx)
        .await?)
    }

    /// Starts this observation's scan over, for a retry after a pending or failed lookup.
    pub async fn reset_correlation_scan(&self, observation_id: Uuid) -> anyhow::Result<()> {
        sqlx::query(
            "UPDATE lnurl_pay_receive_arkoor_observations
             SET correlation_scan_started_at = clock_timestamp(),
                 correlation_matched_metadata_id = NULL,
                 correlation_requires_retry = FALSE
             WHERE id = $1 AND metadata_id IS NULL",
        )
        .bind(observation_id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    /// Makes a record ready for the delivered outputs and consumes the mailbox message in one
    /// transaction. Otherwise the app could list and acknowledge the record before the worker
    /// advances the checkpoint, and a replay would lose the observation. `None` when the record
    /// can no longer be matched (its correlation window closed); the checkpoint is then left alone.
    #[allow(clippy::too_many_arguments)]
    pub async fn mark_ready_and_update_checkpoint(
        &self,
        metadata_id: Uuid,
        observation_id: Uuid,
        output_vtxo_ids: &[String],
        delivered_amount_sat: u64,
        mailbox_checkpoint: i64,
        auth_version: i64,
        worker_id: &str,
    ) -> anyhow::Result<Option<Uuid>> {
        let mut tx = self.pool.begin().await?;
        let id = self
            .mark_ready_in_tx(
                &mut tx,
                metadata_id,
                observation_id,
                output_vtxo_ids,
                delivered_amount_sat,
            )
            .await?;
        let Some(id) = id else {
            return Ok(None);
        };

        let checkpoint_update = sqlx::query(
            "UPDATE mailbox_authorizations
             SET last_checkpoint = $1, updated_at = now()
             WHERE pubkey = (
                       SELECT recipient_pubkey
                       FROM lnurl_pay_receive_arkoor_observations
                       WHERE id = $2 AND metadata_id = $3 AND mailbox_checkpoint = $1
                   )
               AND auth_version = $4
               AND lease_owner = $5",
        )
        .bind(mailbox_checkpoint)
        .bind(observation_id)
        .bind(metadata_id)
        .bind(auth_version)
        .bind(worker_id)
        .execute(&mut *tx)
        .await?;
        if checkpoint_update.rows_affected() == 0 {
            anyhow::bail!("mailbox lease was lost before LNURL-pay receive metadata became ready");
        }
        tx.commit().await?;
        Ok(Some(id))
    }

    async fn mark_ready_in_tx(
        &self,
        tx: &mut Transaction<'_, Postgres>,
        metadata_id: Uuid,
        observation_id: Uuid,
        output_vtxo_ids: &[String],
        delivered_amount_sat: u64,
    ) -> anyhow::Result<Option<Uuid>> {
        if output_vtxo_ids.is_empty() {
            return Err(anyhow!("Ark output target must not be empty"));
        }
        let metadata_row = sqlx::query(
            "SELECT recipient_pubkey, state, encrypted_payload, nonce
             FROM lnurl_pay_receive_metadata
             WHERE id = $1
               AND expires_at > now()
               AND correlation_expires_at > clock_timestamp()
             FOR UPDATE",
        )
        .bind(metadata_id)
        .fetch_optional(&mut **tx)
        .await?;
        let Some(metadata_row) = metadata_row else {
            return Ok(None);
        };
        let recipient_pubkey: String = metadata_row.get("recipient_pubkey");
        if metadata_row.get::<String, _>("state") != "awaiting_payment" {
            return Ok(None);
        }
        let mut payload = self.decrypt_metadata_row(metadata_id, &metadata_row)?;
        let observation = sqlx::query(
            "SELECT recipient_pubkey, mailbox_id, mailbox_checkpoint, metadata_id,
                    encrypted_payload, nonce
             FROM lnurl_pay_receive_arkoor_observations
             WHERE id = $1 AND expires_at > now()
             FOR UPDATE",
        )
        .bind(observation_id)
        .fetch_optional(&mut **tx)
        .await?;
        let Some(observation) = observation else {
            return Ok(None);
        };
        if observation.get::<String, _>("recipient_pubkey") != recipient_pubkey {
            return Ok(None);
        }
        let existing_metadata_id: Option<Uuid> = observation.get("metadata_id");
        if existing_metadata_id.is_some_and(|id| id != metadata_id) {
            return Ok(None);
        }
        let stored_observation: StoredArkoorObservation = self.decrypt(
            observation_aad(
                observation_id,
                &recipient_pubkey,
                &observation.get::<String, _>("mailbox_id"),
                observation.get("mailbox_checkpoint"),
            ),
            observation.get::<Vec<u8>, _>("nonce").as_slice(),
            observation
                .get::<Vec<u8>, _>("encrypted_payload")
                .as_slice(),
        )?;
        if stored_observation.output_vtxo_ids != output_vtxo_ids
            || stored_observation.delivered_amount_sat != delivered_amount_sat
        {
            return Ok(None);
        }

        // The recipient received the forwarded amount, after barkd's fee.
        payload.amount_msat = delivered_amount_sat
            .checked_mul(1000)
            .context("delivered Ark amount is too large")?;
        payload.target = Some(LnurlPayReceiveTarget::ArkOutputVtxos {
            vtxo_ids: output_vtxo_ids.to_vec(),
        });
        let (nonce, ciphertext) = self.encrypt(metadata_aad(metadata_id), &payload)?;
        sqlx::query(
            "UPDATE lnurl_pay_receive_metadata
             SET state = 'ready', encrypted_payload = $2, nonce = $3, ready_at = now(),
                 updated_at = now()
             WHERE id = $1",
        )
        .bind(metadata_id)
        .bind(ciphertext)
        .bind(nonce)
        .execute(&mut **tx)
        .await?;
        sqlx::query(
            "UPDATE lnurl_pay_receive_arkoor_observations
             SET metadata_id = $2
             WHERE id = $1",
        )
        .bind(observation_id)
        .bind(metadata_id)
        .execute(&mut **tx)
        .await?;
        Ok(Some(metadata_id))
    }

    /// Drops an observation that matched no record. `false` when a record already holds it.
    pub async fn delete_arkoor_observation(&self, observation_id: Uuid) -> anyhow::Result<bool> {
        let result = sqlx::query(
            "DELETE FROM lnurl_pay_receive_arkoor_observations
             WHERE id = $1 AND metadata_id IS NULL",
        )
        .bind(observation_id)
        .execute(&self.pool)
        .await?;
        Ok(result.rows_affected() > 0)
    }

    pub async fn list_ready(
        &self,
        recipient_pubkey: &str,
        limit: usize,
    ) -> anyhow::Result<Vec<LnurlPayReceiveMetadata>> {
        let limit = i64::try_from(limit).context("metadata list limit is too large")?;
        let rows = sqlx::query(
            "SELECT id, encrypted_payload, nonce
             FROM lnurl_pay_receive_metadata
             WHERE recipient_pubkey = $1
               AND state = 'ready'
               AND expires_at > now()
             ORDER BY created_at, id
             LIMIT $2",
        )
        .bind(recipient_pubkey)
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;

        let mut items = Vec::with_capacity(rows.len());
        for row in &rows {
            let id: Uuid = row.get("id");
            let payload = match self.decrypt_metadata_row(id, row) {
                Ok(payload) => payload,
                Err(error) => {
                    // A newer server may have written a version this one can't read during a
                    // rolling deploy. Leave it for a reader that can, or for expiry.
                    tracing::warn!(record_id = %id, error = %error, "skipping unreadable LNURL-pay receive metadata");
                    continue;
                }
            };
            let Some(target) = payload.target else {
                tracing::warn!(record_id = %id, "skipping ready LNURL-pay receive metadata without a target");
                continue;
            };
            items.push(LnurlPayReceiveMetadata {
                id: id.to_string(),
                amount_sat: payload.amount_msat / 1000,
                target,
                payer_data: payload.payer_data,
                comment: payload.comment,
            });
        }
        Ok(items)
    }

    pub async fn acknowledge(&self, recipient_pubkey: &str, ids: &[Uuid]) -> anyhow::Result<u64> {
        if ids.is_empty() {
            return Ok(0);
        }
        let result = sqlx::query(
            "DELETE FROM lnurl_pay_receive_metadata
             WHERE recipient_pubkey = $1 AND id = ANY($2) AND state = 'ready'",
        )
        .bind(recipient_pubkey)
        .bind(ids)
        .execute(&self.pool)
        .await?;
        Ok(result.rows_affected())
    }

    pub async fn cleanup_expired(&self) -> anyhow::Result<u64> {
        let result = sqlx::query(
            "DELETE FROM lnurl_pay_receive_metadata
             WHERE expires_at <= now()
                OR (state <> 'ready' AND correlation_expires_at <= clock_timestamp())
                OR (state = 'awaiting_invoice' AND request_expires_at <= now())",
        )
        .execute(&self.pool)
        .await?;
        let observations = sqlx::query(
            "DELETE FROM lnurl_pay_receive_arkoor_observations WHERE expires_at <= now()",
        )
        .execute(&self.pool)
        .await?;
        Ok(result.rows_affected() + observations.rows_affected())
    }

    #[cfg(test)]
    pub async fn set_expiry_for_test(
        &self,
        id: Uuid,
        correlation_expires_at: DateTime<Utc>,
        expires_at: DateTime<Utc>,
    ) -> anyhow::Result<()> {
        sqlx::query(
            "UPDATE lnurl_pay_receive_metadata
             SET correlation_expires_at = $2, expires_at = $3
             WHERE id = $1",
        )
        .bind(id)
        .bind(correlation_expires_at)
        .bind(expires_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    #[cfg(test)]
    pub async fn contains_for_test(&self, id: Uuid) -> anyhow::Result<bool> {
        Ok(sqlx::query_scalar::<_, bool>(
            "SELECT EXISTS (SELECT 1 FROM lnurl_pay_receive_metadata WHERE id = $1)",
        )
        .bind(id)
        .fetch_one(&self.pool)
        .await?)
    }

    #[cfg(test)]
    pub async fn ids_for_test(
        &self,
        recipient_pubkey: &str,
    ) -> anyhow::Result<Vec<(Uuid, String)>> {
        Ok(sqlx::query_as::<_, (Uuid, String)>(
            "SELECT id, state FROM lnurl_pay_receive_metadata
             WHERE recipient_pubkey = $1
             ORDER BY created_at, id",
        )
        .bind(recipient_pubkey)
        .fetch_all(&self.pool)
        .await?)
    }

    /// A ready record for `recipient_pubkey`, as if a forwarded payment had matched it.
    #[cfg(test)]
    pub async fn insert_ready_for_test(&self, recipient_pubkey: &str) -> anyhow::Result<Uuid> {
        let id = Uuid::new_v4();
        let payload = StoredMetadataPayload {
            version: PAYLOAD_SCHEMA_VERSION,
            amount_msat: 1_000_000,
            payer_data: None,
            comment: Some("test comment".to_string()),
            invoice: None,
            payment_hash: None,
            ark_address: None,
            target: Some(LnurlPayReceiveTarget::ArkOutputVtxos {
                vtxo_ids: vec![format!("{}:0", "11".repeat(32))],
            }),
        };
        let (nonce, ciphertext) = self.encrypt(metadata_aad(id), &payload)?;
        sqlx::query(
            "INSERT INTO lnurl_pay_receive_metadata (
                id, recipient_pubkey, state, encrypted_payload, nonce, correlation_expires_at,
                expires_at, ready_at
             ) VALUES ($1, $2, 'ready', $3, $4, now() + interval '1 hour', now() + interval '7 days',
                       now())",
        )
        .bind(id)
        .bind(recipient_pubkey)
        .bind(ciphertext)
        .bind(nonce)
        .execute(&self.pool)
        .await?;
        Ok(id)
    }

    /// One record's payer data and comment, decrypted.
    pub async fn payer_data_and_comment(
        &self,
        id: Uuid,
    ) -> anyhow::Result<(Option<LnurlPayReceivePayerData>, Option<String>)> {
        let row = sqlx::query(
            "SELECT encrypted_payload, nonce FROM lnurl_pay_receive_metadata WHERE id = $1",
        )
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        let payload = self.decrypt_metadata_row(id, &row)?;
        Ok((payload.payer_data, payload.comment))
    }

    #[cfg(test)]
    pub async fn set_payload_version_for_test(&self, id: Uuid, version: u8) -> anyhow::Result<()> {
        let row = sqlx::query(
            "SELECT encrypted_payload, nonce FROM lnurl_pay_receive_metadata WHERE id = $1",
        )
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        let mut payload: StoredMetadataPayload = self.decrypt(
            metadata_aad(id),
            row.get::<Vec<u8>, _>("nonce").as_slice(),
            row.get::<Vec<u8>, _>("encrypted_payload").as_slice(),
        )?;
        payload.version = version;
        let (nonce, ciphertext) = self.encrypt(metadata_aad(id), &payload)?;
        sqlx::query(
            "UPDATE lnurl_pay_receive_metadata
             SET encrypted_payload = $2, nonce = $3
             WHERE id = $1",
        )
        .bind(id)
        .bind(ciphertext)
        .bind(nonce)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    fn payment_hash_lookup(&self, payment_hash: &str) -> [u8; 32] {
        let mut engine = sha256::Hash::engine();
        engine.input(&self.lookup_key);
        engine.input(payment_hash.as_bytes());
        sha256::Hash::from_engine(engine).to_byte_array()
    }

    fn encrypt<T: Serialize>(&self, aad: String, value: &T) -> anyhow::Result<(Vec<u8>, Vec<u8>)> {
        let plaintext = serde_json::to_vec(value).context("failed to encode metadata payload")?;
        let nonce = XChaCha20Poly1305::generate_nonce(&mut OsRng);
        let ciphertext = self
            .cipher
            .encrypt(
                &nonce,
                Payload {
                    msg: &plaintext,
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| anyhow!("failed to encrypt metadata payload"))?;
        Ok((nonce.to_vec(), ciphertext))
    }

    fn decrypt<T: DeserializeOwned>(
        &self,
        aad: String,
        nonce: &[u8],
        ciphertext: &[u8],
    ) -> anyhow::Result<T> {
        let nonce: [u8; 24] = nonce
            .try_into()
            .map_err(|_| anyhow!("invalid metadata nonce length"))?;
        let nonce = XNonce::from(nonce);
        let plaintext = self
            .cipher
            .decrypt(
                &nonce,
                Payload {
                    msg: ciphertext,
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| anyhow!("failed to decrypt metadata payload"))?;
        serde_json::from_slice(&plaintext).context("invalid decrypted metadata payload")
    }

    fn decrypt_metadata_row(
        &self,
        id: Uuid,
        row: &sqlx::postgres::PgRow,
    ) -> anyhow::Result<StoredMetadataPayload> {
        let payload: StoredMetadataPayload = self.decrypt(
            metadata_aad(id),
            row.get::<Vec<u8>, _>("nonce").as_slice(),
            row.get::<Vec<u8>, _>("encrypted_payload").as_slice(),
        )?;
        if payload.version != PAYLOAD_SCHEMA_VERSION {
            return Err(anyhow!("unsupported metadata payload schema version"));
        }
        Ok(payload)
    }
}

/// Reservations still waiting for barkd plus records waiting for their payment.
const PENDING_COUNT_SQL: &str = "SELECT count(*)
     FROM lnurl_pay_receive_metadata
     WHERE recipient_pubkey = $1
       AND (
           (state = 'awaiting_invoice' AND request_expires_at > now())
           OR state = 'awaiting_payment'
       )
       AND correlation_expires_at > clock_timestamp()";

async fn lock_recipient_quota(
    tx: &mut Transaction<'_, Postgres>,
    recipient_pubkey: &str,
) -> anyhow::Result<()> {
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, $2))")
        .bind(recipient_pubkey)
        .bind(QUOTA_LOCK_SEED)
        .execute(&mut **tx)
        .await?;
    Ok(())
}

fn metadata_aad(id: Uuid) -> String {
    format!("lnurl-pay-receive-metadata:v1:{id}")
}

fn observation_aad(
    id: Uuid,
    recipient_pubkey: &str,
    mailbox_id: &str,
    mailbox_checkpoint: i64,
) -> String {
    format!(
        "lnurl-pay-receive-arkoor-observation:v1:{id}:{recipient_pubkey}:{mailbox_id}:{mailbox_checkpoint}"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn encrypted_payload_has_a_version_and_hides_metadata() {
        let pool = PgPool::connect_lazy("postgres://localhost/noah").unwrap();
        let repo = LnurlPayReceiveMetadataRepository::new(&pool, &[0x42; 32]);
        let id = Uuid::new_v4();
        let payload = StoredMetadataPayload {
            version: PAYLOAD_SCHEMA_VERSION,
            amount_msat: 42_000,
            payer_data: Some(LnurlPayReceivePayerData {
                name: Some("Sensitive Alice".to_string()),
                identifier: Some("alice@example.com".to_string()),
            }),
            comment: Some("Sensitive comment".to_string()),
            invoice: Some("sensitive-invoice".to_string()),
            payment_hash: Some("sensitive-payment-hash".to_string()),
            ark_address: None,
            target: None,
        };
        let (nonce, ciphertext) = repo.encrypt(metadata_aad(id), &payload).unwrap();
        let raw = String::from_utf8_lossy(&ciphertext);
        assert!(!raw.contains("Sensitive Alice"));
        assert!(!raw.contains("Sensitive comment"));
        assert_eq!(
            repo.decrypt::<StoredMetadataPayload>(metadata_aad(id), &nonce, &ciphertext)
                .unwrap(),
            payload
        );
        // The payload is bound to its record: another ID can't decrypt it.
        assert!(
            repo.decrypt::<StoredMetadataPayload>(
                metadata_aad(Uuid::new_v4()),
                &nonce,
                &ciphertext
            )
            .is_err()
        );
    }
}
