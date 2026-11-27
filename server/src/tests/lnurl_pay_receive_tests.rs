//! LUD-12/18 receive metadata on barkd-forwarded payments: the LNURL-pay endpoint stores it, the
//! mailbox worker matches the forwarded delivery to it, and the app lists and acknowledges it.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use ark::{Address, ProtocolEncoding, Vtxo, VtxoId, test_util::VTXO_VECTORS, vtxo::Full};
use async_trait::async_trait;
use axum::Router;
use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use chrono::Utc;
use http_body_util::BodyExt;
use lightning_invoice::Bolt11Invoice;
use serde_json::{Value, json};
use server_rpc::protos::mailbox_server::{ArkoorMessage, MailboxMessage, mailbox_message::Message};
use tokio_util::sync::CancellationToken;
use tower::ServiceExt;
use uuid::Uuid;

use crate::{
    AppState,
    barkd_client::{
        ForwardedArkDestination, ForwardedReceiveHistory, ForwardedReceiveStatus,
        ForwardingInvoice, ForwardingInvoiceProvider,
    },
    db::{
        lnurl_pay_receive_metadata_repo::{
            LnurlPayReceiveMetadataRepository, MAX_ACTIVE_CORRELATIONS_PER_RECIPIENT,
        },
        mailbox_authorization_repo::MailboxAuthorizationRepository,
    },
    mailbox_worker::{MailboxSessionContext, process_mailbox_message},
    tests::common::{
        TestDbGuard, TestUser, create_test_user, setup_test_app_with_barkd, test_invoice,
    },
};

const AMOUNT_MSAT: u64 = 10_000_000;
const PAYER_DATA: &str = r#"{"name":"Alice Payer","identifier":"Alice@Example.com"}"#;
const COMMENT: &str = "Thanks for the coffee";

/// barkd: one forwarding invoice, and the receive movement it reports for that invoice.
struct ForwardingBarkd {
    invoice: Option<String>,
    history: Mutex<Option<ForwardedReceiveHistory>>,
    invoice_calls: Mutex<u32>,
}

impl ForwardingBarkd {
    fn new(invoice: Option<String>) -> Arc<Self> {
        Arc::new(Self {
            invoice,
            history: Mutex::new(None),
            invoice_calls: Mutex::new(0),
        })
    }

    fn report(&self, history: ForwardedReceiveHistory) {
        *self.history.lock().unwrap() = Some(history);
    }

    fn invoice_calls(&self) -> u32 {
        *self.invoice_calls.lock().unwrap()
    }
}

#[async_trait]
impl ForwardingInvoiceProvider for ForwardingBarkd {
    async fn check_readiness(&self) -> anyhow::Result<()> {
        Ok(())
    }

    async fn create_invoice_for_address(
        &self,
        _address: &str,
        _amount_sat: u64,
        _description: Option<&str>,
    ) -> anyhow::Result<ForwardingInvoice> {
        *self.invoice_calls.lock().unwrap() += 1;
        match &self.invoice {
            Some(invoice) => Ok(ForwardingInvoice {
                invoice: invoice.clone(),
            }),
            None => anyhow::bail!("test barkd failure"),
        }
    }

    async fn lookup_forwarded_receive(
        &self,
        _invoice: &str,
    ) -> anyhow::Result<Option<ForwardedReceiveHistory>> {
        Ok(self.history.lock().unwrap().clone())
    }
}

/// The VTXO barkd delivers: a test vector Arkoor VTXO.
fn delivered_vtxo() -> &'static Vtxo<Full> {
    &VTXO_VECTORS.arkoor2_vtxo
}

/// The recipient's Ark address, which the delivered VTXO pays.
fn recipient_address(vtxo: &Vtxo<Full>) -> Address {
    let blinded_id = ark::mailbox::BlindedMailboxIdentifier::from_pubkey(vtxo.user_pubkey());
    Address::builder()
        .testnet(true)
        .server_pubkey(vtxo.server_pubkey())
        .policy(vtxo.policy().clone())
        .delivery(ark::address::VtxoDelivery::ServerMailbox { blinded_id })
        .into_address()
        .unwrap()
}

struct Recipient {
    app: Router,
    state: AppState,
    user: TestUser,
    pubkey: String,
    _guard: TestDbGuard,
}

impl Recipient {
    /// A recipient with an Ark address on our server and a mailbox authorization covering the
    /// correlation window.
    async fn new(barkd: Arc<ForwardingBarkd>) -> Self {
        let (app, state, guard) = setup_test_app_with_barkd(Some(barkd)).await;
        let vtxo = delivered_vtxo();
        *state.ark_server_pubkey.write().await = Some(vtxo.server_pubkey().to_string());
        let user = TestUser::new();
        let address = recipient_address(vtxo).to_string();
        create_test_user(&state, &user, Some(&address)).await;
        let pubkey = user.pubkey().to_string();
        MailboxAuthorizationRepository::new(&state.db_pool)
            .upsert(
                &pubkey,
                "deadbeef",
                "cafebabe",
                Utc::now().timestamp() + 7200,
            )
            .await
            .unwrap();
        Self {
            app,
            state,
            user,
            pubkey,
            _guard: guard,
        }
    }

    fn repo(&self) -> &LnurlPayReceiveMetadataRepository {
        self.state.lnurl_pay_receive_metadata_repo.as_ref().unwrap()
    }

    async fn get(&self, uri: &str) -> (StatusCode, Value) {
        let response = self
            .app
            .clone()
            .oneshot(Request::get(uri).body(Body::empty()).unwrap())
            .await
            .unwrap();
        let status = response.status();
        let body = response.into_body().collect().await.unwrap().to_bytes();
        (status, serde_json::from_slice(&body).unwrap_or(Value::Null))
    }

    async fn post(&self, user: &TestUser, path: &str, body: Value) -> (StatusCode, Value) {
        let response = self
            .app
            .clone()
            .oneshot(
                Request::post(path)
                    .header(
                        http::header::AUTHORIZATION,
                        format!("Bearer {}", user.access_token(&self.state)),
                    )
                    .header(http::header::CONTENT_TYPE, "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        let status = response.status();
        let body = response.into_body().collect().await.unwrap().to_bytes();
        (status, serde_json::from_slice(&body).unwrap_or(Value::Null))
    }

    async fn callback(
        &self,
        payer_data: Option<&str>,
        comment: Option<&str>,
    ) -> (StatusCode, Value) {
        let mut query = form_urlencoded::Serializer::new(String::new());
        query.append_pair("amount", &AMOUNT_MSAT.to_string());
        if let Some(payer_data) = payer_data {
            query.append_pair("payerdata", payer_data);
        }
        if let Some(comment) = comment {
            query.append_pair("comment", comment);
        }
        self.get(&format!("/.well-known/lnurlp/test?{}", query.finish()))
            .await
    }

    /// Delivers the forwarded VTXO to the recipient's mailbox at `checkpoint`.
    async fn deliver(&self, checkpoint: u64) -> Result<bool, crate::errors::ApiError> {
        let repo = MailboxAuthorizationRepository::new(&self.state.db_pool);
        let session = MailboxSessionContext {
            worker_id: "lnurl-pay-receive-test".to_string(),
            stream_idle_reconnect: Duration::from_secs(10),
            cancellation_token: CancellationToken::new(),
        };
        // Each delivery claims the mailbox afresh, as a new session would.
        repo.release_all_claims(&session.worker_id).await.unwrap();
        let mailbox = repo
            .claim_runnable(
                Utc::now(),
                &session.worker_id,
                Utc::now() + chrono::Duration::minutes(1),
                1,
            )
            .await
            .unwrap()
            .remove(0);
        let message = MailboxMessage {
            checkpoint,
            message: Some(Message::Arkoor(ArkoorMessage {
                vtxos: vec![delivered_vtxo().serialize()],
            })),
        };
        process_mailbox_message(&self.state, &mailbox, &session, &message, true).await
    }

    async fn checkpoint(&self) -> i64 {
        MailboxAuthorizationRepository::new(&self.state.db_pool)
            .find_by_pubkey(&self.pubkey)
            .await
            .unwrap()
            .unwrap()
            .last_checkpoint
    }

    async fn records(&self) -> Vec<(Uuid, String)> {
        self.repo().ids_for_test(&self.pubkey).await.unwrap()
    }
}

/// What barkd reports once it forwarded `invoice`'s payment: claimed from the delivered VTXO's
/// direct parent, sent to the recipient for the delivered amount.
fn forwarded(invoice: &str, status: ForwardedReceiveStatus) -> ForwardedReceiveHistory {
    let vtxo = delivered_vtxo();
    ForwardedReceiveHistory {
        status,
        payment_hash: invoice
            .parse::<Bolt11Invoice>()
            .unwrap()
            .payment_hash()
            .to_string(),
        htlc_vtxos: vec![*vtxo.ancestor_ids().last().unwrap()],
        destination: (status == ForwardedReceiveStatus::Successful).then(|| {
            ForwardedArkDestination {
                address: recipient_address(vtxo).to_string(),
                amount_sat: vtxo.amount().to_sat(),
            }
        }),
    }
}

fn barkd_invoice() -> String {
    test_invoice(bitcoin::Network::Regtest, AMOUNT_MSAT, 7)
}

#[tracing_test::traced_test]
#[tokio::test]
async fn forwarded_payment_carries_payer_data_to_the_recipient() {
    let invoice = barkd_invoice();
    let barkd = ForwardingBarkd::new(Some(invoice.clone()));
    let recipient = Recipient::new(barkd.clone()).await;

    let (status, discovery) = recipient.get("/.well-known/lnurlp/test").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        discovery["payerData"],
        json!({"name": {"mandatory": false}, "identifier": {"mandatory": false}})
    );
    assert_eq!(discovery["commentAllowed"], 280);

    let (status, response) = recipient.callback(Some(PAYER_DATA), Some(COMMENT)).await;
    assert_eq!(status, StatusCode::OK, "{response}");
    assert_eq!(response["pr"], invoice);
    let [(record_id, state)] = recipient.records().await.try_into().unwrap();
    assert_eq!(state, "awaiting_payment");
    let (payer_data, comment) = recipient.repo().stored_for_test(record_id).await.unwrap();
    let payer_data = payer_data.unwrap();
    assert_eq!(payer_data.name.as_deref(), Some("Alice Payer"));
    assert_eq!(payer_data.identifier.as_deref(), Some("alice@example.com"));
    assert_eq!(comment.as_deref(), Some(COMMENT));
    // Neither tracing nor Sentry saw them.
    assert!(!logs_contain("Alice"));
    assert!(!logs_contain("coffee"));

    barkd.report(forwarded(&invoice, ForwardedReceiveStatus::Successful));
    assert!(recipient.deliver(7).await.unwrap());
    assert_eq!(recipient.checkpoint().await, 7);
    assert_eq!(
        recipient.records().await,
        vec![(record_id, "ready".to_string())]
    );

    let (status, list) = recipient
        .post(
            &recipient.user,
            "/v0/lnurlp/receive_metadata/list",
            json!({}),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{list}");
    let vtxo = delivered_vtxo();
    assert_eq!(
        list,
        json!({"items": [{
            "id": record_id.to_string(),
            "amount_sat": vtxo.amount().to_sat(),
            "target": {"type": "ark_output_vtxos", "vtxo_ids": [vtxo.id().to_string()]},
            "payer_data": {"name": "Alice Payer", "identifier": "alice@example.com"},
            "comment": COMMENT,
        }]})
    );

    // Another user can't acknowledge, and so delete, the recipient's record.
    let other = TestUser::new_with_key(&[0x5a; 32]);
    sqlx::query("INSERT INTO users (pubkey, lightning_address) VALUES ($1, 'other@localhost')")
        .bind(other.pubkey().to_string())
        .execute(&recipient.state.db_pool)
        .await
        .unwrap();
    let ack = json!({"ids": [record_id.to_string()]});
    let (status, _) = recipient
        .post(&other, "/v0/lnurlp/receive_metadata/ack", ack.clone())
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(recipient.records().await.len(), 1);
    let (status, other_list) = recipient
        .post(&other, "/v0/lnurlp/receive_metadata/list", json!({}))
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(other_list, json!({"items": []}));

    let (status, _) = recipient
        .post(&recipient.user, "/v0/lnurlp/receive_metadata/ack", ack)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert!(recipient.records().await.is_empty());
}

#[tokio::test]
async fn a_pending_barkd_movement_replays_the_delivery() {
    let invoice = barkd_invoice();
    let barkd = ForwardingBarkd::new(Some(invoice.clone()));
    let recipient = Recipient::new(barkd.clone()).await;
    assert_eq!(
        recipient.callback(Some(PAYER_DATA), None).await.0,
        StatusCode::OK
    );

    // barkd can post to the mailbox before it marks its movement successful.
    barkd.report(forwarded(&invoice, ForwardedReceiveStatus::Pending));
    assert!(recipient.deliver(3).await.is_err());
    assert_eq!(recipient.checkpoint().await, 0);
    assert_eq!(recipient.records().await[0].1, "awaiting_payment");

    barkd.report(forwarded(&invoice, ForwardedReceiveStatus::Successful));
    assert!(recipient.deliver(3).await.unwrap());
    assert_eq!(recipient.checkpoint().await, 3);
    assert_eq!(recipient.records().await[0].1, "ready");
}

#[tokio::test]
async fn an_unrelated_delivery_leaves_the_record_waiting() {
    let invoice = barkd_invoice();
    let barkd = ForwardingBarkd::new(Some(invoice.clone()));
    let recipient = Recipient::new(barkd.clone()).await;
    assert_eq!(
        recipient.callback(Some(PAYER_DATA), None).await.0,
        StatusCode::OK
    );

    // barkd claimed a different HTLC: this delivery isn't the record's payment.
    let mut history = forwarded(&invoice, ForwardedReceiveStatus::Successful);
    history.htlc_vtxos = vec![format!("{}:0", "ff".repeat(32)).parse::<VtxoId>().unwrap()];
    barkd.report(history);
    assert!(recipient.deliver(5).await.unwrap());
    assert_eq!(recipient.checkpoint().await, 5);
    assert_eq!(recipient.records().await[0].1, "awaiting_payment");

    // A delivery barkd sent elsewhere, or for another amount, doesn't match either.
    let mut history = forwarded(&invoice, ForwardedReceiveStatus::Successful);
    history.destination.as_mut().unwrap().amount_sat += 1;
    barkd.report(history);
    assert!(recipient.deliver(6).await.unwrap());
    assert_eq!(recipient.records().await[0].1, "awaiting_payment");
}

#[tokio::test]
async fn a_plain_payment_is_labeled_without_payer_data() {
    let invoice = barkd_invoice();
    let barkd = ForwardingBarkd::new(Some(invoice.clone()));
    let recipient = Recipient::new(barkd.clone()).await;

    // Released Noah apps always send an empty `comment=`.
    let (status, response) = recipient.callback(None, Some("")).await;
    assert_eq!(status, StatusCode::OK, "{response}");
    barkd.report(forwarded(&invoice, ForwardedReceiveStatus::Successful));
    assert!(recipient.deliver(1).await.unwrap());

    let (_, list) = recipient
        .post(
            &recipient.user,
            "/v0/lnurlp/receive_metadata/list",
            json!({}),
        )
        .await;
    assert_eq!(list["items"][0]["payer_data"], Value::Null);
    assert_eq!(list["items"][0]["comment"], Value::Null);
    assert_eq!(list["items"][0]["target"]["type"], "ark_output_vtxos");
}

#[tokio::test]
async fn payer_data_is_refused_where_it_cannot_reach_the_recipient() {
    let barkd = ForwardingBarkd::new(Some(barkd_invoice()));
    let recipient = Recipient::new(barkd.clone()).await;
    MailboxAuthorizationRepository::new(&recipient.state.db_pool)
        .revoke(&recipient.pubkey)
        .await
        .unwrap();

    // Without a mailbox authorization the worker can't see the delivery, so nothing is advertised
    // and nothing is accepted.
    let (_, discovery) = recipient.get("/.well-known/lnurlp/test").await;
    assert_eq!(discovery.get("payerData"), None);
    assert_eq!(discovery.get("commentAllowed"), None);
    let (status, _) = recipient.callback(Some(PAYER_DATA), None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let (status, _) = recipient.callback(None, Some(COMMENT)).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(barkd.invoice_calls(), 0);

    // An empty comment is no comment.
    let (status, response) = recipient.callback(None, Some("")).await;
    assert_eq!(status, StatusCode::OK, "{response}");
    assert!(recipient.records().await.is_empty());
}

#[tokio::test]
async fn invalid_payer_data_is_rejected_before_barkd() {
    let barkd = ForwardingBarkd::new(Some(barkd_invoice()));
    let recipient = Recipient::new(barkd.clone()).await;

    for payer_data in [
        "not json",
        r#"{"name":{"value":"Alice"}}"#,
        r#"{"identifier":"not an address"}"#,
    ] {
        let (status, _) = recipient.callback(Some(payer_data), None).await;
        assert_eq!(status, StatusCode::BAD_REQUEST, "{payer_data}");
    }
    let (status, _) = recipient.callback(None, Some(&"a".repeat(281))).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(barkd.invoice_calls(), 0);
    assert!(recipient.records().await.is_empty());
}

#[tokio::test]
async fn a_barkd_failure_discards_the_reservation() {
    let barkd = ForwardingBarkd::new(None);
    let recipient = Recipient::new(barkd.clone()).await;

    // The device fallback can't carry payer data; this recipient has no push token either.
    let (status, _) = recipient.callback(Some(PAYER_DATA), Some(COMMENT)).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(barkd.invoice_calls(), 1);
    assert!(recipient.records().await.is_empty());
}

#[tokio::test]
async fn a_full_quota_refuses_payer_data_but_not_the_payment() {
    let invoice = barkd_invoice();
    let barkd = ForwardingBarkd::new(Some(invoice.clone()));
    let recipient = Recipient::new(barkd.clone()).await;
    let address = recipient_address(delivered_vtxo()).to_string();
    for _ in 0..MAX_ACTIVE_CORRELATIONS_PER_RECIPIENT {
        recipient
            .repo()
            .reserve(&recipient.pubkey, AMOUNT_MSAT, None, None, &address)
            .await
            .unwrap()
            .unwrap();
    }

    let (_, discovery) = recipient.get("/.well-known/lnurlp/test").await;
    assert_eq!(discovery.get("payerData"), None);
    let (status, _) = recipient.callback(Some(PAYER_DATA), None).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(barkd.invoice_calls(), 0);

    let (status, response) = recipient.callback(None, None).await;
    assert_eq!(status, StatusCode::OK, "{response}");
    assert_eq!(response["pr"], invoice);
    assert_eq!(
        recipient.records().await.len() as i64,
        MAX_ACTIVE_CORRELATIONS_PER_RECIPIENT
    );
}

#[tokio::test]
async fn expired_records_are_cleaned_up() {
    let barkd = ForwardingBarkd::new(Some(barkd_invoice()));
    let recipient = Recipient::new(barkd).await;
    assert_eq!(
        recipient.callback(Some(PAYER_DATA), None).await.0,
        StatusCode::OK
    );
    let [(record_id, _)] = recipient.records().await.try_into().unwrap();

    recipient
        .repo()
        .set_expiry_for_test(
            record_id,
            Utc::now() - chrono::Duration::seconds(1),
            Utc::now() + chrono::Duration::days(1),
        )
        .await
        .unwrap();
    assert_eq!(recipient.repo().cleanup_expired().await.unwrap(), 1);
    assert!(!recipient.repo().contains_for_test(record_id).await.unwrap());
}

#[tokio::test]
async fn a_record_this_version_cannot_read_is_kept_for_one_that_can() {
    let invoice = barkd_invoice();
    let barkd = ForwardingBarkd::new(Some(invoice.clone()));
    let recipient = Recipient::new(barkd.clone()).await;
    assert_eq!(
        recipient.callback(Some(PAYER_DATA), None).await.0,
        StatusCode::OK
    );
    let [(record_id, _)] = recipient.records().await.try_into().unwrap();
    recipient
        .repo()
        .set_payload_version_for_test(record_id, 2)
        .await
        .unwrap();

    barkd.report(forwarded(&invoice, ForwardedReceiveStatus::Successful));
    assert!(recipient.deliver(2).await.is_err());
    assert_eq!(recipient.checkpoint().await, 0);
    assert!(recipient.repo().contains_for_test(record_id).await.unwrap());
}

#[tokio::test]
async fn the_direct_ark_route_refuses_payer_data() {
    let barkd = ForwardingBarkd::new(Some(barkd_invoice()));
    let recipient = Recipient::new(barkd.clone()).await;
    let server = delivered_vtxo().server_pubkey().to_string();
    let callback =
        |extra: &str| format!("/.well-known/lnurlp/test?amount={AMOUNT_MSAT}&ark={server}{extra}");

    // The Ark route never calls the callback that could carry them to the recipient.
    let payer_data = form_urlencoded::byte_serialize(PAYER_DATA.as_bytes()).collect::<String>();
    let (status, _) = recipient
        .get(&callback(&format!("&payerdata={payer_data}")))
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    let (status, response) = recipient.get(&callback("&comment=")).await;
    assert_eq!(status, StatusCode::OK, "{response}");
    assert_eq!(
        response["ark"],
        recipient_address(delivered_vtxo()).to_string()
    );
    assert_eq!(barkd.invoice_calls(), 0);
    assert!(recipient.records().await.is_empty());
}
