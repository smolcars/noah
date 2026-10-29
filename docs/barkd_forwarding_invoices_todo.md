# Barkd Forwarding Invoices TODO

Historical feature backlog. The unchecked items below are not an inventory of
what is deployed. Infrastructure now runs on Railway; use the
[signet](../.railway/README.md) and [mainnet](../.railway/mainnet/README.md)
operations notes for configuration, releases and wallet recovery.

## Goal

Let the Noah server create Lightning invoices for users without waking their devices. Noah will
read the user's Ark address from Postgres and ask a server-side Bark wallet to create a BOLT11
invoice whose claimed VTXO is forwarded directly to that address.

The forwarding wallet must never become the source of truth for Noah users. Postgres continues to
own user identity and Ark addresses; barkd owns only its wallet keys, SQLite state, and the durable
actions needed to claim and deliver Lightning receives.

Only Noah connects to Postgres. Barkd does not need Postgres credentials; it needs its volume plus
outbound access to the configured Ark server and chain source.

## Locked Decisions

- [x] Run barkd as a separate service, not inside the replicated Noah server process.
- [x] Run exactly one barkd instance per network with one attached persistent volume.
- [x] Keep the Noah server stateless and deploy it independently from Barkd.
- [x] Reach barkd over Railway's private network and require barkd bearer authentication.
- [x] Do not allocate public IPs or expose barkd's REST API or Swagger UI publicly.
- [x] Use barkd's `POST /api/v1/lightning/receives/invoice/for-address` endpoint.
- [x] Roll out on signet before creating the mainnet wallet.
- [x] Keep the push-notification invoice flow available as a feature-flagged fallback.
- [x] On rollback, stop creating new barkd invoices but leave barkd running so paid, parked actions
  can finish delivery.
- [x] Accept that the initial single-volume design has restart/deploy and volume-host downtime; it
  is durable but not highly available.

## References

- [Barkd overview](https://second.tech/docs/barkd/index)
- [Starting barkd](https://second.tech/docs/barkd/start)
- [Barkd authentication](https://second.tech/docs/barkd/authenticate)
- [Create an invoice for an Ark address](https://second.tech/docs/barkd/api-reference/lightning/create-a-bolt11-invoice-for-an-ark-address)
- [Get Lightning receive status](https://second.tech/docs/barkd/api-reference/lightning/get-receive-status)
- [Get Ark server info](https://second.tech/docs/barkd/api-reference/wallet/get-ark-server-info)
- [Bark backup requirements](https://second.tech/docs/backups)

This plan was last checked against barkd `0.6.1` and the linked documentation on 2026-08-17.
Pin the deployed version; do not build from a moving branch or use an unpinned `latest` image.

## Target Architecture

```text
Lightning payer
      |
      | LNURL callback
      v
Noah server
      |
      | 1. Read the user's Ark address from Postgres
      | 2. Authenticated HTTP over the private network
      v
barkd (one always-on instance in iad)
      |
      | SQLite database, mnemonic, auth token
      v
Persistent volume mounted at /data
      |
      | Paid receive is claimed directly to the user's address
      v
User's Ark mailbox
```

Use separate Railway environments and wallets for each network:

| Network | Barkd service | Noah caller | Datadir |
| --- | --- | --- | --- |
| Signet | `noah-barkd-signet` | `api` | `/data/barkd` |
| Mainnet | `noah-barkd-mainnet` | `api-mainnet` | `/data/barkd` |

## Phase 0: Resolve Protocol and Product Gates

These items block implementation or mainnet rollout.

### Lightning receive anti-DoS

- [x] The target Ark servers do not enforce `ln_receive_anti_dos_required`; no receive token or
  proof VTXO is required for this rollout.
- [x] Keep the Noah client aligned with barkd's current `for-address` request: `address`,
  `amount_sat`, and `description`.

### Amount and fee semantics

- [ ] Decide how Noah handles LNURL amounts that are not whole satoshis. LNURL supplies
  millisatoshis while barkd accepts an integer `amount_sat`.
- [ ] Recommended first behavior: require `amount_msat % 1000 == 0`, reject any fractional-sat
  request, and use a checked conversion to `u64` satoshis. Never silently round.
- [ ] Confirm that the BOLT11 amount exactly equals the LNURL callback amount.
- [ ] Confirm and document the amount the recipient receives after the Ark server's configured
  Lightning receive fee.
- [ ] Verify minimum and maximum amounts after fees, not only Noah's existing LNURL limits.

### Address compatibility

- [ ] Confirm stored user addresses contain a server-mailbox delivery mechanism.
- [ ] Confirm signet addresses are rejected by mainnet barkd and vice versa.
- [ ] Confirm addresses for a different Ark server are rejected.
- [ ] Continue taking the address only from the authenticated Noah user record; never accept a
  forwarding address directly from the public LNURL callback request.

## Infrastructure and wallet operations

The container image lives in [docker/barkd](../docker/barkd/Dockerfile). Railway
configuration, manual upgrades, volume backups and recovery are documented in
its operations notes above. The signet and mainnet wallets already exist; do not
initialize replacement wallets or restore an old copy alongside either active
wallet. Preserve the complete datadir and verify backup/format compatibility
before a Barkd upgrade.

## Phase 4: Add a Small Noah Barkd Client

### Configuration

- [ ] Add the following server configuration:
  - `BARKD_FORWARDED_INVOICES_ENABLED`, default `false`
  - `BARKD_URL`, required only when enabled
  - `BARKD_AUTH_TOKEN`, required only when enabled and always redacted
  - `BARKD_REQUEST_TIMEOUT_SECONDS`, with a conservative default
- [ ] Fail server startup when the feature is enabled but URL or token is missing.
- [ ] Validate that the production URL is HTTP on the approved network-specific Railway hostname;
  traffic stays on the private service network.
- [ ] Never log the authorization header, mnemonic, preimage, or full response bodies from errors.

### Client module

- [ ] Add `server/src/barkd_client.rs` with a narrow internal interface such as:

```rust
#[async_trait]
trait ForwardingInvoiceProvider {
    async fn create_invoice_for_address(
        &self,
        address: &str,
        amount_sat: u64,
        description: Option<&str>,
    ) -> anyhow::Result<ForwardingInvoice>;

    async fn receive_status(&self, identifier: &str)
        -> anyhow::Result<ForwardingReceiveStatus>;
}
```

- [ ] Implement the interface with the existing `reqwest` dependency.
- [ ] Use `Authorization: Bearer <token>` on every request.
- [ ] Model barkd error bodies without including secrets in returned `ApiError` messages.
- [ ] Set connect and total-request timeouts.
- [ ] Do not add automatic retries around invoice-creation POSTs. The endpoint has no idempotency
  key, so a timed-out request may already have created an invoice.
- [ ] Allow status GETs to retry with bounded backoff.
- [ ] Store the client in `AppStruct` as an optional dependency controlled by the feature flag.
- [ ] Add a startup/degraded-readiness probe using authenticated `GET /api/v1/wallet/ark-info`, but
  do not make temporary barkd unavailability remove every Noah API instance from service.

### Tests for the client

- [ ] Test the request path, JSON body, bearer header, and response parsing against a local stub.
- [ ] Test `400`, `401`, `404`, `500`, malformed JSON, connect failure, and timeout behavior.
- [ ] Test that errors and tracing fields do not contain the bearer token.
- [ ] Test that invoice creation is attempted once when the response times out.

## Phase 5: Integrate the LNURL Route

The current fallback branch begins in `server/src/routes/public_api_v0.rs` after direct Ark
negotiation fails.

- [ ] Preserve the current direct-Ark negotiation path unchanged.
- [ ] After direct negotiation fails, use barkd when all of the following are true:
  - The feature flag is enabled.
  - The active user has a stored Ark address.
  - The LNURL amount converts exactly to whole satoshis.
- [ ] Snapshot the user's current Ark address into the forwarding request; a later profile update
  must not change the destination of an already-issued invoice.
- [ ] Build a non-sensitive invoice description consistent with the existing LNURL metadata.
- [ ] Call `create_invoice_for_address` and return the resulting BOLT11 invoice in `pr`.
- [ ] Keep `routes` empty and decide whether the existing `ark` response field should contain the
  user's address on this fallback response.
- [ ] Preserve the existing device push flow when barkd is disabled, the user has no Ark address,
  or the configured fallback policy allows it after a barkd error.
- [ ] Do not require an Expo push token merely to take the barkd path.
- [ ] Decide separately whether Noah's mailbox authorization remains a product-policy requirement.
  Barkd validates mailbox delivery in the Ark address, but Noah's authorization may still be needed
  for Noah's mailbox worker to notify the device about the delivered VTXO.
- [ ] Define the failure policy explicitly:
  - Recommended: make one barkd attempt with no POST retry.
  - If no invoice is returned, use the old push path when the user is eligible.
  - Otherwise return a normal LNURL error without exposing barkd internals.
- [ ] Rate-limit the barkd path at least as strictly as the existing LNURL callback.
- [ ] Add structured events for outcome, latency, amount, and error class. Do not log tokens,
  mnemonics, preimages, or raw signed Ark data.

### Durable audit and reconciliation

- [ ] Add a Postgres migration for a small forwarding-invoice audit table.
- [ ] Store at least:
  - Noah request ID
  - user pubkey or stable user ID
  - Lightning address
  - Ark address snapshot
  - amount in millisatoshis and satoshis
  - BOLT11 invoice or parsed payment hash
  - latest barkd state
  - creation, last-check, preimage-revealed, delivery, and settlement timestamps
- [ ] Keep Bark SQLite authoritative for Bark action recovery; the Postgres row is for Noah audit,
  reconciliation, and alerts, not for reconstructing Bark actions.
- [ ] Add a bounded reconciliation worker that calls
  `GET /api/v1/lightning/receives/{identifier}` for non-terminal rows.
- [ ] Use the existing Postgres worker-claim pattern so multiple Noah instances do not poll the same
  row concurrently.
- [ ] Map and persist barkd states: `awaiting-payment`, `htlcs-ready`, `preimage-revealed`,
  `delivering`, and `settled`.
- [ ] Parse and store the payment hash, but deliberately ignore and never persist or log the
  `payment_preimage` returned by the status API.
- [ ] Alert on paid receives stuck in `preimage-revealed` or `delivering` beyond an agreed threshold.
- [ ] Define retention for unpaid/expired invoices and settled audit rows.

## Phase 6: Test End to End on Signet

### Functional tests

- [ ] Create a signet invoice through Noah for a test user's stored Ark address.
- [ ] Verify the invoice amount and payment hash.
- [ ] Pay it from an independent wallet.
- [ ] Observe the receive progress to `settled` through barkd's status endpoint.
- [ ] Sync the destination Noah wallet and verify it discovers and can spend the forwarded VTXO.
- [ ] Confirm the forwarding barkd wallet cannot spend the delivered user output.
- [ ] Confirm the forwarding wallet does not accumulate the user's received balance.
- [ ] Repeat with concurrent payments to different users and to the same user.

### Failure and recovery tests

- [ ] Stop barkd before invoice creation and verify the configured push/error fallback.
- [ ] Restart barkd with an unpaid invoice and confirm it remains queryable.
- [ ] Interrupt barkd after payment while the action is parked or delivering; confirm restart resumes
  the action and reaches `settled`.
- [ ] Temporarily make the destination mailbox unavailable, then restore it and verify delivery
  resumes.
- [ ] Exercise an invalid address, wrong-network address, wrong-server address, and an address
  without supported mailbox delivery.
- [ ] Exercise a non-whole-satoshi LNURL amount.
- [ ] Exercise barkd `401`, timeout, and malformed-response paths.
- [ ] Restore the wallet onto a new volume and verify a pending delivery can resume.
- [ ] Verify a Noah deploy or restart does not interrupt barkd.
- [ ] Verify a barkd rolling deploy has expected downtime but preserves the datadir.

### Load and abuse tests

- [ ] Establish a safe per-user/IP rate limit for invoice creation.
- [ ] Load-test expected concurrent invoice creation and status polling on the proposed VM size.
- [ ] Measure SQLite growth from unpaid invoices and determine cleanup/retention behavior.
- [ ] Confirm repeated client retries cannot redirect an invoice away from the address stored in
  Postgres.

## Phase 7: Observability and Operations

- [ ] Add metrics or structured events for:
  - invoice creation attempts, successes, and failures
  - barkd request latency and timeouts
  - current receives by lifecycle state
  - age of the oldest `preimage-revealed` and `delivering` receive
  - last successful authenticated Ark server check
- [ ] Verify automatic snapshots regularly and before/after barkd upgrades.
- [ ] Alert when barkd is unreachable, unauthorized, disconnected from the Ark server, or has a
  stuck paid receive.
- [ ] Alert when the wallet volume is near capacity.
- [ ] Document bearer-token rotation:
  - Rotate with `barkd --datadir /data/barkd secret refresh` in a controlled session.
  - Update the corresponding Noah Railway variable.
  - Restart/roll Noah and verify authentication.
- [ ] Document volume-host failure and restoration from continuous backup.
- [ ] Document barkd upgrades, database backup, verification, and rollback constraints.
- [ ] Add a dashboard/runbook link to every alert.

## Phase 8: Rollout

- [ ] Merge the infrastructure and client code with
  `BARKD_FORWARDED_INVOICES_ENABLED=false` everywhere.
- [ ] Deploy and initialize `noah-barkd-signet`.
- [ ] Complete the signet functional, crash-recovery, and snapshot-restore tests.
- [ ] Enable signet for internal accounts, then a small cohort, then all signet users.
- [ ] Observe at least one full invoice lifecycle and one barkd restart before mainnet approval.
- [ ] Create the mainnet app, volume, wallet, token, and snapshot policy independently.
- [ ] Verify the mainnet fingerprint and store the mainnet mnemonic using the recovery procedure.
- [ ] Recheck mainnet Ark server fees, limits, and mailbox key.
- [ ] Enable mainnet for internal accounts and low payment limits first.
- [ ] Expand gradually while monitoring stuck states, fallback rate, and recipient discovery.

### Rollback

- [ ] Set `BARKD_FORWARDED_INVOICES_ENABLED=false` to send new requests through the existing flow.
- [ ] Do not stop or delete barkd during an application rollback.
- [ ] Continue reconciling every invoice already returned to a payer until it settles or reaches a
  documented terminal recovery state.
- [ ] Preserve the wallet volume, mnemonic, bearer token, and backups even if the feature remains
  disabled.

## Definition of Done

- [ ] Barkd is reachable from Noah over the private service network and unreachable publicly.
- [ ] Exactly one barkd instance owns exactly one attached volume per network.
- [ ] Noah remains independently deployable without a volume.
- [ ] The bearer token and mnemonic never appear in source control or logs.
- [ ] A stored user Ark address can receive a paid Lightning invoice without waking the device.
- [ ] The destination wallet discovers and spends the forwarded VTXO after syncing.
- [ ] Barkd cannot redirect or spend an output created for the stored destination address.
- [ ] A barkd restart resumes a paid parked delivery.
- [ ] Wallet backup creation and restore have been tested successfully.
- [ ] Stuck paid receives produce actionable alerts.
- [ ] Disabling the feature restores the old invoice flow without abandoning existing barkd
  actions.

## Future Option: Postgres-Backed Bark Worker

Do not include this in the initial rollout. If the single-volume operational model becomes a real
constraint, evaluate a dedicated service built with `bark-wallet`, a Postgres `StorageAdaptor`, and
a distributed `LockManager`/leader lease. It must remain separate from the replicated Noah HTTP
process and pass Bark's persistence differential tests plus Noah crash/failover tests before it can
replace barkd.
