//! Swift and Kotlin bindings for Satora swaps: pay a Lightning invoice, receive USDC on
//! Arbitrum. Mainnet only. The flow follows the `satora-spike` CLI that proved it.

use std::fmt;
use std::future::Future;
use std::path::Path;
use std::sync::{Arc, LazyLock};
use std::time::Duration;

use lendaswap_sdk::aa::{RemoteAaConfig, UserOpConfig};
use lendaswap_sdk::evm::{Address, EvmClaimOptions, RpcWallet, U256};
use lendaswap_sdk::signer::Signer;
use lendaswap_sdk::types::*;
use lendaswap_sdk::{Client, StoredSwap};

const API: &str = "https://api.satora.io";
const ARBITRUM: u64 = 42161;
const ARBITRUM_RPC: &str = "https://arb1.arbitrum.io/rpc";
const USDC: &str = "0xaf88d065e77c8cc2239327c5edb3a432268e5831";
/// Satora's Arbitrum HTLCErc20 (v7) and HTLCCoordinator (v5), as published in
/// github.com/satoraHQ/lendaswap-contracts. A redeploy needs an app update.
const HTLC: &str = "0x6C38bF44A327bf36BfbeFcedc514c0FB3Fec6748";
const COORDINATOR: &str = "0xb79118233552d4b7eD5c8D2d1E2BbA7e8eecb71f";
/// The claim floor and DEX slippage the SDK's own auto-claimer uses for this route.
const SLIPPAGE_BPS: u16 = 100;

static RUNTIME: LazyLock<Result<tokio::runtime::Runtime, String>> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("satora-ffi")
        .build()
        .map_err(|e| e.to_string())
});

/// Run SDK work on the crate's Tokio runtime; a panic there comes back as an error.
async fn run<T: Send + 'static>(
    work: impl Future<Output = Result<T, SatoraError>> + Send + 'static,
) -> Result<T, SatoraError> {
    let rt = RUNTIME
        .as_ref()
        .map_err(|e| SatoraError::other(e.clone()))?;
    rt.spawn(work)
        .await
        .map_err(|e| SatoraError::other(format!("task failed: {e}")))?
}

#[boltffi::data]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SatoraErrorCode {
    Other,
    /// Transport or decoding failure; retry later.
    Network,
    /// Satora's server refused the request.
    Api,
    Storage,
    InvalidInput,
    Timeout,
    /// No Lightning to EVM swap with that id in local storage.
    NotFound,
    /// A claim may already have left this device without a recorded hash. Do not retry
    /// blindly: check the chain before releasing it.
    ClaimInFlight,
    /// Satora named an HTLC or coordinator other than the pinned ones, e.g. after a
    /// redeploy. Do not pay or claim; an app update is needed.
    UnknownContract,
}

#[boltffi::error]
#[derive(Clone, Debug)]
pub struct SatoraError {
    pub message: String,
    #[boltffi::default(SatoraErrorCode::Other)]
    pub code: SatoraErrorCode,
}

impl SatoraError {
    fn new(code: SatoraErrorCode, message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            code,
        }
    }
    fn other(message: impl fmt::Display) -> Self {
        Self::new(SatoraErrorCode::Other, message.to_string())
    }
    fn invalid(message: impl fmt::Display) -> Self {
        Self::new(SatoraErrorCode::InvalidInput, message.to_string())
    }
}

impl fmt::Display for SatoraError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}
impl std::error::Error for SatoraError {}

impl From<lendaswap_sdk::Error> for SatoraError {
    fn from(e: lendaswap_sdk::Error) -> Self {
        use SatoraErrorCode as C;
        use lendaswap_sdk::Error as E;
        let code = match &e {
            E::Transport(_) | E::Decode(_) => C::Network,
            E::Api { .. } => C::Api,
            E::Storage(_) => C::Storage,
            E::InvalidBaseUrl(_) | E::InvalidSigner(_) | E::InvalidSwap(_) => C::InvalidInput,
            E::Timeout(_) => C::Timeout,
            _ => C::Other,
        };
        Self::new(code, e.to_string())
    }
}

/// A price for paying `sats` over Lightning. USDC amounts are base units (6 decimals).
#[boltffi::data]
#[derive(Clone, Debug)]
pub struct SatoraQuote {
    pub protocol_fee_sats: u64,
    pub protocol_fee_rate: f64,
    pub network_fee_sats: u64,
    pub gasless_claim_fee_sats: u64,
    /// USDC per BTC.
    pub exchange_rate: String,
    pub target_usdc: String,
    /// What should arrive, after fees.
    pub net_target_usdc: String,
    pub min_sats: u64,
    pub max_sats: u64,
}

/// A Lightning to USDC-on-Arbitrum swap as stored on this device.
/// USDC amounts are base units (6 decimals); times are Unix seconds.
#[boltffi::data]
#[derive(Clone, Debug)]
pub struct SatoraSwap {
    pub id: String,
    /// Satora's wire status, e.g. `pending`, `serverfunded`, `serverredeemed`.
    pub status: String,
    /// No further change is expected.
    pub terminal: bool,
    pub bolt11_invoice: String,
    pub source_sats: String,
    pub target_usdc: String,
    pub fee_sats: i64,
    pub destination: Option<String>,
    /// RFC 3339.
    pub created_at: String,
    pub invoice_expires_at: Option<i64>,
    /// The swap must be claimed before this.
    pub claim_deadline: i64,
    pub server_fund_tx: Option<String>,
    pub claim_tx: Option<String>,
    pub claim_user_op_hash: Option<String>,
    /// A claim was reserved on this device.
    pub claim_started: bool,
}

impl SatoraSwap {
    fn from_stored(s: StoredSwap) -> Option<Self> {
        let GetSwapResponse::LightningToEvm(r) = s.response else {
            return None;
        };
        let m = s.metadata;
        Some(Self {
            terminal: terminal(&r.status),
            status: r.status.as_wire_str().to_string(),
            claim_tx: m.evm_claim_txid.or(r.evm_claim_txid),
            claim_user_op_hash: m.evm_claim_userop_hash,
            claim_started: m.evm_claim_started == Some(true),
            id: r.id,
            bolt11_invoice: r.bolt11_invoice,
            source_sats: r.source_amount,
            target_usdc: r.target_amount,
            fee_sats: r.fee_sats,
            destination: r.target_evm_address,
            created_at: r.created_at,
            invoice_expires_at: r.invoice_expires_at,
            claim_deadline: r.evm_refund_locktime,
            server_fund_tx: r.evm_fund_txid,
        })
    }
}

fn terminal(s: &SwapStatus) -> bool {
    use SwapStatus::*;
    matches!(
        s,
        ServerRedeemed
            | Expired
            | ClientRefunded
            | ClientFundedServerRefunded
            | ClientRefundedServerRefunded
            | ClientInvalidFunded
            | ClientFundedTooLate
            | ServerWontFund
            | ClientRedeemedAndClientRefunded
    )
}

pub struct SatoraClient {
    client: Arc<Client>,
}

#[boltffi::export]
impl SatoraClient {
    /// Open the Satora client in `data_dir`, which must exist. The SDK keeps its
    /// mnemonic in `data_dir/wallet.sqlite` (plaintext, owner-only) and swap records in
    /// `data_dir/swaps.sqlite`. Without `mnemonic`, the stored one is used or a new one
    /// is generated and stored. A supplied `mnemonic` is stored when none is, and
    /// refused when a different one is.
    pub fn open(data_dir: String, mnemonic: Option<String>) -> Result<Self, SatoraError> {
        let dir = Path::new(&data_dir);
        let mut builder = Client::builder()
            .base_url(API)
            .wallet_storage_path(dir.join("wallet.sqlite"))
            .storage_path(dir.join("swaps.sqlite"));
        if let Some(m) = mnemonic {
            builder = builder.mnemonic(m);
        }
        Ok(Self {
            client: Arc::new(builder.build()?),
        })
    }

    /// Price paying `sats` over Lightning for USDC on Arbitrum. Read-only.
    pub async fn quote(&self, sats: u64) -> Result<SatoraQuote, SatoraError> {
        let client = self.client.clone();
        run(async move {
            let q = client
                .get_quote(QuoteRequest::new(
                    Chain::lightning(),
                    TokenId::Btc,
                    Chain::arbitrum(),
                    TokenId::UsdcArbitrum,
                    QuoteAmount::Source(sats.into()),
                ))
                .await?;
            Ok(SatoraQuote {
                protocol_fee_sats: q.protocol_fee,
                protocol_fee_rate: q.protocol_fee_rate,
                network_fee_sats: q.network_fee,
                gasless_claim_fee_sats: q.gasless_network_fee,
                exchange_rate: q.exchange_rate,
                target_usdc: q.target_amount,
                net_target_usdc: q.net_target_amount,
                min_sats: q.min_amount,
                max_sats: q.max_amount,
            })
        })
        .await
    }

    /// Create a swap paying USDC on Arbitrum to `destination`. Pay the returned
    /// `bolt11_invoice`, then claim once the status is `serverfunded`.
    pub async fn create_swap(
        &self,
        sats: u64,
        destination: String,
    ) -> Result<SatoraSwap, SatoraError> {
        destination
            .parse::<Address>()
            .map_err(|e| SatoraError::invalid(format!("destination address: {e}")))?;
        let client = self.client.clone();
        run(async move {
            let swap = client
                .create_swap_request(
                    SwapCreation::LightningToEvm {
                        target: EvmAsset {
                            chain_id: ARBITRUM,
                            token: TokenId::UsdcArbitrum,
                        },
                        amount: QuoteAmount::Source(sats.into()),
                        target_address: destination,
                    },
                    SwapOptions {
                        gasless: Some(true),
                        ..Default::default()
                    },
                )
                .await?;
            issued(&client, &swap.id)
        })
        .await
    }

    /// The swap as last stored on this device, without a network call.
    pub fn swap(&self, id: String) -> Result<SatoraSwap, SatoraError> {
        stored(&self.client, &id)
    }

    /// Fetch the swap's current state from Satora and store it.
    pub async fn refresh_swap(&self, id: String) -> Result<SatoraSwap, SatoraError> {
        let client = self.client.clone();
        run(async move {
            client.refresh_swap(&id).await?;
            stored(&client, &id)
        })
        .await
    }

    /// The 12-word phrase the SDK generated for this client, to show the user. It signs
    /// USDC claims only. Never log it.
    pub fn mnemonic(&self) -> Result<String, SatoraError> {
        self.client
            .mnemonic()
            .map(str::to_string)
            .ok_or_else(|| SatoraError::other("client has no mnemonic"))
    }

    /// Every Lightning to EVM swap stored on this device.
    pub fn list_swaps(&self) -> Result<Vec<SatoraSwap>, SatoraError> {
        Ok(self
            .client
            .list_all_swaps()?
            .into_iter()
            .filter_map(SatoraSwap::from_stored)
            .collect())
    }

    /// Claim the swap's USDC with a sponsored EIP-7702 UserOperation, or resume a claim
    /// already sent. Returns the claim transaction hash, or `None` when the claim was
    /// submitted but has no transaction yet: call again later to resume it.
    pub async fn claim(&self, id: String) -> Result<Option<String>, SatoraError> {
        let client = self.client.clone();
        run(async move { claim(&client, &id).await }).await
    }
}

fn stored(client: &Client, id: &str) -> Result<SatoraSwap, SatoraError> {
    client
        .get_stored_swap(id)?
        .and_then(SatoraSwap::from_stored)
        .ok_or_else(|| {
            SatoraError::new(
                SatoraErrorCode::NotFound,
                format!("no Lightning to EVM swap {id}"),
            )
        })
}

async fn claim(client: &Client, id: &str) -> Result<Option<String>, SatoraError> {
    let GetSwapResponse::LightningToEvm(r) = client.refresh_swap(id).await? else {
        return Err(SatoraError::invalid("not a Lightning to EVM swap"));
    };
    check_contracts(&r)?;
    let s = client.get_stored_swap(id)?.ok_or_else(|| {
        SatoraError::new(SatoraErrorCode::NotFound, "swap is not in local storage")
    })?;
    let m = &s.metadata;
    if let Some(tx) = &m.evm_claim_txid {
        return Ok(Some(tx.clone()));
    }
    let rpc: url::Url = ARBITRUM_RPC.parse().map_err(SatoraError::other)?;
    let wallet = wallet(client, s.key_index, &r.client_evm_address, &rpc).await?;
    if m.evm_claim_userop_hash.is_some() {
        let aa = user_op_config(client, &rpc).await?;
        let tx = client.resume_evm_claim_userop(id, &wallet, &aa).await?;
        return Ok(tx.map(|h| h.to_string()));
    }
    if m.evm_claim_started == Some(true) {
        return Err(SatoraError::new(
            SatoraErrorCode::ClaimInFlight,
            "a claim may already have been sent (evm_claim_started is set without a hash); \
             check the chain before releasing it",
        ));
    }
    // The payout address recorded at creation, which the server must also hold.
    let to = m
        .target_address
        .clone()
        .ok_or_else(|| SatoraError::other("no destination recorded at creation"))?;
    if !r
        .target_evm_address
        .as_deref()
        .is_some_and(|s| s.eq_ignore_ascii_case(&to))
    {
        return Err(SatoraError::other(
            "server's payout address differs from the one recorded at creation",
        ));
    }
    if r.target_token.token_id != TokenId::UsdcArbitrum {
        return Err(SatoraError::other("target token is not Arbitrum USDC"));
    }
    let floor = U256::from_str_radix(&r.target_amount, 10).map_err(SatoraError::other)?
        * U256::from(10_000 - SLIPPAGE_BPS)
        / U256::from(10_000);
    let addr = |s: &str| s.parse::<Address>().map_err(SatoraError::other);
    let options = EvmClaimOptions {
        htlc: addr(&r.evm_htlc_address)?,
        coordinator: addr(&r.evm_coordinator_address)?,
        destination: addr(&to)?,
        sweep_token: addr(USDC)?,
        min_amount_out: floor,
        minimum_remaining_seconds: 300,
        timeout: Duration::from_secs(120),
    };
    // tBTC -> USDC route; output goes to the coordinator, which sweeps it to `to`.
    let dex = client
        .get_dex_calldata_fund(DexCalldataRequest {
            from: DexToken::Evm {
                chain_id: ARBITRUM,
                address: r.wbtc_address.clone(),
            },
            to: DexToken::Evm {
                chain_id: ARBITRUM,
                address: USDC.into(),
            },
            amount: DexAmount::ExactIn(r.evm_expected_sats.clone()),
            sender: r.evm_coordinator_address.clone(),
            slippage_bps: SLIPPAGE_BPS,
            bridge_recipient: None,
            swap_id: None,
        })
        .await?;
    let DexCallsPayload::Evm { calls } = dex.payload;
    let aa = user_op_config(client, &rpc).await?;
    let receipt = client
        .claim_evm_userop(id, &wallet, options, calls, &aa)
        .await?;
    Ok(receipt.transaction_hash.map(|h| h.to_string()))
}

/// The new swap, refused before its invoice is handed out if it names unknown contracts,
/// so nothing is paid that `claim` would refuse.
fn issued(client: &Client, id: &str) -> Result<SatoraSwap, SatoraError> {
    if let Some(GetSwapResponse::LightningToEvm(r)) =
        client.get_stored_swap(id)?.map(|s| s.response)
    {
        check_contracts(&r)?;
    }
    stored(client, id)
}

fn check_contracts(r: &LightningToEvmSwapResponse) -> Result<(), SatoraError> {
    if r.evm_htlc_address.eq_ignore_ascii_case(HTLC)
        && r.evm_coordinator_address.eq_ignore_ascii_case(COORDINATOR)
    {
        return Ok(());
    }
    Err(SatoraError::new(
        SatoraErrorCode::UnknownContract,
        format!(
            "Satora named HTLC {} and coordinator {}, not the pinned contracts",
            r.evm_htlc_address, r.evm_coordinator_address
        ),
    ))
}

/// Bundler and paymaster from Satora's `/aa/config`. The SDK's `RemoteAaConfig` drops the
/// paymaster `policy_id` the server sends, so read the raw JSON for it.
async fn user_op_config(client: &Client, rpc: &url::Url) -> Result<UserOpConfig, SatoraError> {
    let raw = client.send(AaConfigQuery { chain: "42161" }).await?;
    let remote: RemoteAaConfig = serde_json::from_value(raw.clone()).map_err(SatoraError::other)?;
    if remote.entry_point != lendaswap_sdk::aa::userop::ENTRY_POINT_V07
        || remote.delegation_target != lendaswap_sdk::aa::kernel::KERNEL_DELEGATION_TARGET
    {
        return Err(SatoraError::other(
            "Satora named an EntryPoint or Kernel other than the pinned ones",
        ));
    }
    let policy = raw["paymaster"]["policy_id"]
        .as_str()
        .ok_or_else(|| SatoraError::other("Satora's /aa/config has no paymaster policy_id"))?;
    Ok(UserOpConfig {
        entry_point: remote.entry_point,
        delegation_target: remote.delegation_target,
        aa: remote.into_config(rpc.clone(), serde_json::json!({ "policyId": policy }), None),
    })
}

#[derive(serde::Serialize)]
struct AaConfigQuery {
    chain: &'static str,
}
impl lendaswap_sdk::Endpoint for AaConfigQuery {
    type Response = serde_json::Value;
    const METHOD: http::Method = http::Method::GET;
    const PATH: &'static str = "aa/config";
    const PAYLOAD: lendaswap_sdk::PayloadKind = lendaswap_sdk::PayloadKind::Query;
}

/// The swap's per-swap EVM key (it signs the claim) over an Arbitrum RPC.
async fn wallet(
    client: &Client,
    index: u32,
    owner: &str,
    rpc: &url::Url,
) -> Result<RpcWallet, SatoraError> {
    let mnemonic = client
        .mnemonic()
        .ok_or_else(|| SatoraError::other("client has no mnemonic"))?;
    let key = Signer::from_mnemonic(mnemonic)?.evm_key_for_address(index, owner)?;
    Ok(RpcWallet::connect(&key, ARBITRUM, rpc.clone()).await?)
}

#[cfg(test)]
mod tests {
    use super::*;

    const MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    const OTHER: &str =
        "legal winner thank year wave sausage worth useful legal winner thank yellow";

    fn dir() -> (tempfile::TempDir, String) {
        let d = tempfile::tempdir().unwrap();
        let s = d.path().to_str().unwrap().to_string();
        (d, s)
    }

    #[test]
    fn open_generates_and_keeps_mnemonic() {
        let (_d, dir) = dir();
        let first = SatoraClient::open(dir.clone(), None).unwrap();
        let m = first.client.mnemonic().unwrap().to_string();
        drop(first);
        let again = SatoraClient::open(dir, None).unwrap();
        assert_eq!(again.client.mnemonic(), Some(m.as_str()));
        assert!(again.list_swaps().unwrap().is_empty());
    }

    #[test]
    fn open_refuses_a_different_mnemonic() {
        let (_d, dir) = dir();
        SatoraClient::open(dir.clone(), Some(MNEMONIC.into())).unwrap();
        SatoraClient::open(dir.clone(), Some(MNEMONIC.into())).unwrap();
        let err = SatoraClient::open(dir, Some(OTHER.into())).err().unwrap();
        assert!(!err.message.contains("legal"), "error leaks the mnemonic");
    }

    #[test]
    fn unknown_swap_is_not_found() {
        let (_d, dir) = dir();
        let c = SatoraClient::open(dir, Some(MNEMONIC.into())).unwrap();
        assert_eq!(
            c.swap("nope".into()).unwrap_err().code,
            SatoraErrorCode::NotFound
        );
    }

    #[test]
    fn create_swap_rejects_bad_address_offline() {
        let (_d, dir) = dir();
        let c = SatoraClient::open(dir, Some(MNEMONIC.into())).unwrap();
        let err = RUNTIME
            .as_ref()
            .unwrap()
            .block_on(c.create_swap(10_000, "0xnot-an-address".into()))
            .unwrap_err();
        assert_eq!(err.code, SatoraErrorCode::InvalidInput);
    }

    fn response(htlc: &str, coordinator: &str) -> GetSwapResponse {
        let token = |id: &str, chain: &str| serde_json::json!({"token_id": id, "symbol": "X", "chain": chain, "name": "X", "decimals": 6});
        GetSwapResponse::LightningToEvm(
            serde_json::from_value(serde_json::json!({
                "bolt11_invoice": "lnbc1invoice", "chain": "42161",
                "client_evm_address": "0x0000000000000000000000000000000000000001",
                "created_at": "2026-11-20T00:00:00Z", "evm_chain_id": 42161,
                "evm_coordinator_address": coordinator, "evm_expected_sats": "1000",
                "evm_htlc_address": htlc, "evm_htlc_version": 7, "evm_refund_locktime": 0,
                "fee_sats": 1, "hash_lock": "00", "id": "swap-1", "network": "bitcoin",
                "server_evm_address": "0x0000000000000000000000000000000000000002",
                "source_amount": "10000", "source_token": token("btc", "Lightning"),
                "status": "pending", "target_amount": "5000000",
                "target_token": token(USDC, "42161"),
                "wbtc_address": "0x6c84a8f1c29108F47a79964b5Fe888D4f4D0dE40"
            }))
            .unwrap(),
        )
    }

    /// A client whose swap storage holds one swap naming `htlc` and `coordinator`.
    fn client_with(htlc: &str, coordinator: &str) -> Client {
        let storage = Arc::new(lendaswap_sdk::InMemorySwapStorage::new());
        let record = StoredSwap::new(0, response(htlc, coordinator), Default::default()).unwrap();
        lendaswap_sdk::storage::SwapStorage::store_swap(&*storage, record).unwrap();
        Client::builder()
            .mnemonic(MNEMONIC)
            .storage(storage)
            .build()
            .unwrap()
    }

    #[test]
    fn pinned_contracts_hand_out_the_invoice() {
        let c = client_with(&HTLC.to_lowercase(), COORDINATOR);
        assert_eq!(issued(&c, "swap-1").unwrap().bolt11_invoice, "lnbc1invoice");
    }

    #[test]
    fn other_contracts_are_refused_before_the_invoice() {
        let other = "0x18123A31261a76c74C82ED2F8346002CaA78Ab39";
        for (htlc, coordinator) in [(other, COORDINATOR), (HTLC, other)] {
            let err = issued(&client_with(htlc, coordinator), "swap-1").unwrap_err();
            assert_eq!(err.code, SatoraErrorCode::UnknownContract);
            assert!(!err.message.contains("lnbc"));
            let GetSwapResponse::LightningToEvm(r) = response(htlc, coordinator) else {
                unreachable!()
            };
            assert_eq!(
                check_contracts(&r).unwrap_err().code,
                SatoraErrorCode::UnknownContract
            );
        }
    }

    #[test]
    fn mnemonic_is_the_stored_one() {
        let (_d, dir) = dir();
        let c = SatoraClient::open(dir, Some(MNEMONIC.into())).unwrap();
        assert_eq!(c.mnemonic().unwrap(), MNEMONIC);
    }

    #[test]
    #[ignore = "network: asks api.satora.io for a quote (read-only)"]
    fn quote_mainnet() {
        let (_d, dir) = dir();
        let c = SatoraClient::open(dir, None).unwrap();
        let q = RUNTIME.as_ref().unwrap().block_on(c.quote(10_000)).unwrap();
        assert!(q.min_sats > 0 && q.max_sats >= q.min_sats);
        assert!(q.net_target_usdc.parse::<u64>().unwrap() > 0);
        println!("{q:?}");
    }
}
