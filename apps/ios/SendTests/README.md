# Native Send verification

Run shared fixture generation from the repository root:

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-send.ts
```

The generator executes the RN implementations. `send-flow.json` covers every
combination of stage confirmations, one/multiple rails, unavailable rails and
zero/one/two funding sources. `send-utils.json` includes the RN test inputs plus
BOLT11 amountless, zero, sub-satoshi, fractional-satoshi and network cases. Altered
invoice headers are decoder-only fixtures; their signatures are not valid and
these fixtures must never be submitted as payments. Existing `fiat-currency.json`
is consumed by `ReceiveFixtureTests` for the shared fiat implementation.

Use a dedicated simulator and DerivedData (never another developer's device):

```sh
cd apps/ios
xcodegen generate
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$SEND_SIMULATOR" \
  -derivedDataPath build/DerivedData test \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO
```

The round-2 full `NoahTests` xcresult reports **246 tests: 236 passed,
10 opt-in tests skipped, 0 failures** (56 suites) on a dedicated iPhone 17,
iOS 27.0 simulator, built with `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`. No target or
suite was excluded. Swift Testing's parameterized cases account for 269 passed
individual runs. The pre-fix regression run failed both missing-BDK-history and
wrong-destination-amount assertions; both pass after the fix.

## Round-5 wallet identity and checkpoint adoption (2026-10-19)

Reviews and attempts now carry the wallet fingerprint and network. A foreign
journal is removed before exposing its receipt or reconciling it; failed removal
blocks Send. A successful deletion, creation, restore or committed-restore
recovery invalidates the process-wide controller while replacement is reserved.
A generation guard rejects status/submission callbacks queued for the old wallet.
Same-wallet restarts preserve the journal and lock.

**Legacy rule:** journals without an identity are quarantined, never displayed as
success and never reconciled/adopted. The old format recorded neither an identity
nor a wallet-change epoch, so even an old file timestamp cannot prove that the
wallet has remained unchanged. We therefore never automatically assign an
identity-free journal to the current wallet. It remains locked with only the
explicit, twice-confirmed destructive history-check override; a failed removal
keeps the lock. Existing v1/v2/v3 outcome-provenance migrations still apply to
identity-bound attempts. Tests without live storage inject their own identities
or deliberately exercise the older migration logic in isolation.

## Round-4 payment safety (2026-10-19)

LNURL and Lightning addresses resolve the callback invoice in the app, persist it
and its hash before submission, validate exact amount/network/expiry, then use
the BOLT11 read-only checkpoint guard and payment API. Comments and callback
query parameters are preserved. Like pinned Bark/RN, metadata hash mismatches
warn without rejecting the invoice (LUD-06). The app never calls SDK payLnurl or
payLightningAddress. Offers cannot expose their invoice hash before SDK payment;
any pending Lightning checkpoint blocks the offer before pay, with explicit copy
and a durable uncertain lock. Regression spies assert zero payment calls for an
LNURL checkpoint collision and an unrelated pending checkpoint before an offer;
normal BOLT11/resolved LNURL and empty-checkpoint offer paths still call pay once.

Ark→onchain MAX stays enabled with exact reviewed VTXO IDs and total coins.
Review says “Recipient receives about …” and explains network fee changes;
the fee is estimated and the total is labeled “Total (reviewed coins)”. Receipts
read actual recipient outputs and fees only from an offboard movement whose
metadata txid equals the SDK-returned txid. Missing/malformed/ambiguous evidence
leaves the recipient amount and fee pending, never filled from the quote.
Refresh fills them when available. Older MAX receipts lacking actual-amount
provenance are normalized to pending while preserving their submitted txid/lock.
FFI item 14 now requests recipient amount/fee binding for prepared offboards.

Direct Ark submission now persists `submitReturnedArkSuccess` only after the
SDK's `sendArkoorPayment` returns success (0.3.0 returns Void). The loader accepts
that proof only for the exact reviewed Ark amount. History candidates still
cannot manufacture this provenance. A parameterized regression submits and
serializes a successful attempt without Done, reloads it, verifies the same
receipt remains locked, rejects a second submit, then clears only through Done
for Ark, BOLT11, LNURL, offers, onchain and offboard. Cross-rail and wrong-amount
Ark proofs are rejected; legacy migration remains conservative.

Full final `NoahTests`: **291 tests, 278 passed, 13 opt-in skipped, 0 failed**,
across **70 suites**; parameterized cases account for **323 passed individual
runs**. All six close-before-Done rail cases pass. Dedicated simulator:
`Noah Send Round 4 Fix 73185be8`, iPhone 17 / iOS 27.0, UUID
`943CC723-49C8-4648-8438-622F6B21BCCB`. XcodeGen was regenerated after adding
files; tests used Nix, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, no target exclusions,
and `/tmp/noah-send-r4-fix-73185be8-dd`. Result:
`/tmp/noah-send-r4-final-full.xcresult`. Targeted checkpoint tests also passed
(8 tests / 10 individual runs), as did offboard/existing safety tests
(29 tests / 54 individual runs).

Live harness: **1 passed, 0 skipped, 0 failed**,
`/tmp/noah-send-r4-live.xcresult`. A fresh disposable wallet received **20,000
Ark sats once**, sent **1,000 Ark sats once**, and persisted the unacknowledged
receipt with `proof: submitReturnedArkSuccess`. Bark receiver movement **35**
independently reports successful receipt of 1,000 sats. After the harness exited,
the real app reopened the receipt; it was then explicitly terminated while
showing “Payment sent / 1000 sats” before Done and relaunched. Send still showed
the same successful receipt and Done, with no destructive override. Sender
history contained exactly one Ark send and balance remained 19,000 sats.

After Done, the real composer selected Ark→onchain MAX. Accessibility snapshots
verified “Recipient receives about 16441 sats”, “Estimated fee, 2559 sats”,
“Total (reviewed coins), 19000 sats”, and the network-fee explanation. One Confirm
submitted txid prefix `466906ef2469`. The receipt showed **Submitted**, **16,441
sats**, and **Actual fee: 2,559 sats**, read from resulting movement **3** and
its transaction metadata. Core independently reports **16,441 sats received,
zero confirmations**. SQLite confirms the exact **four reviewed VTXO IDs** were
consumed and recipient + fee = reviewed 19,000 sats. Live quote and actual agreed;
the regression test exercises a changed fee/output. No block was mined and no
Docker service was restarted or reconfigured.

Evidence: `/tmp/noah-send-r4-ark-evidence.json`,
`/tmp/noah-send-r4-offboard-evidence.json`, `/tmp/noah-send-r4-core-evidence.json`;
AX snapshots and PNGs: `/tmp/noah-send-r4-ark-{before,after}-relaunch.{txt,png}`,
`/tmp/noah-send-r4-offboard-{review,receipt}.{txt,png}`. No seeds, wallet data,
invoices or runtime destinations are committed.

Repeat on a fresh dedicated simulator with `TEST_RUNNER_NOAH_SEND_ROUND4_LIVE=1`
and `TEST_RUNNER_NOAH_SEND_ARK_RECIPIENT` (a fresh Bark regtest address), selecting
`-only-testing:NoahTests/SendRoundFourLiveTests/arkReceiptBeforeDone()`.
Fund `Documents/send-round4-funding-address.txt` once with **20,000 sats** within
180 seconds. The harness hosts the real receipt and writes
`Documents/send-round4-ark-ready`; create `Documents/send-round4-finish` after
inspection to finish without acknowledging. Reopen the actual app, open Send,
kill/relaunch before Done and verify the receipt again. Then use the composer
for Ark→onchain MAX to a fresh Core address and compare receipt/movement/Core.

The dedicated simulator was shut down through the Device panel, its exact name,
UUID and Shutdown state checked, then deleted. No other device was changed.

Remaining verification limits: external LNURL/BOLT12 provider acceptance and
camera hardware were not exercised live. Their payment guards use injected
resolution/state and payment-call spies. FFI 14–16 remain SDK work; the app
continues to label estimates/pending values and never infers network acceptance.

## Round-3 payment safety (2026-10-19)

Full `NoahTests` target: **264 tests, 253 passed, 11 opt-in tests skipped,
0 failures**, across 61 suites, on the dedicated `Noah Send Round 3 Fix`
iPhone 17 / iOS 27.0 simulator. Parameterized cases account for **292 passed
individual runs**. XcodeGen was regenerated after adding the test files;
build/test used `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, no target exclusions,
and `/tmp/noah-send-r3-fix-derived`. Result: `/tmp/noah-send-r3-fix-full3.xcresult`.
The checkpoint regressions exercise both equal and mismatched checkpoint
amounts and assert **zero SDK payment calls**. Raw v1/v2 migration fixtures cover
wrong-amount BDK receipts, fabricated Lightning receipts/already-paid states,
missing provenance, and failed migration writes. Existing unreadable-journal,
missing-BDK-history, disabled MAX, pre-recording error and reorg tests pass.

- Journal safety v3 persists submit-returned-txid / actual-movement provenance.
  All older receipts (including early-v2 fabricated Lightning and wrong-amount
  BDK receipts) reopen uncertain; old txids survive as possible evidence only.
  Current-version receipts missing proof also reopen. Migration is atomically
  saved before exposing state; write errors and unreadable journals block Send.
  The destructive override is available for uncertain attempts without a proven
  receipt, including Lightning. It never runs from Done or ordinary Confirm.

- Only a txid returned directly from submission, with its provenance persisted,
  binds an onchain transaction to the attempt. Snapshot differences (including a
  rediscovered old same-address/amount transaction) display “This transaction may
  be your payment: <txid>” and remain uncertain/locked across refresh and restart.
  Only the explicit destructive history-check override permits a fresh attempt.

- Entered-amount onchain-wallet sends remain enabled by user decision. Review
  labels both fee and total as estimates and explicitly warns that more input
  coins may raise the final fee/total. Receipts show the actual BDK fee when
  available (including after refresh); otherwise they retain the estimate labels
  and warning. FFI item 14 now prioritizes prepared sends with a fee ceiling.

- Before BOLT11 submission, SDK 0.3.0 `lightningSendState(paymentHash:)` reads the
  paid fact/checkpoint without driving it. Existing checkpoints never call pay,
  including checkpoints inherited before any movement existed. Their actual
  amount and fee are persisted and displayed alongside any reviewed mismatch.
  Read-only status checks keep mismatched attempts uncertain even if the old
  checkpoint later settles. Pending/unknown movement evidence also blocks pay.
  Paid invoices use actual movement amounts/fees and never call pay again.
  See [SDK implementation](https://github.com/smolcars/bark-bolt-ffi/blob/v0.3.0/src/core/wallet.rs).

Live round-3 acceptance: **1 test passed, 0 skipped, 0 failures**, result
`/tmp/noah-send-r3-fix-live.xcresult`. A fresh disposable wallet received 10,000
Ark sats and 30,000 onchain sats, with exactly one block mined to confirm the
funding. It paid a fresh **amountless** CLN BOLT11 for 2,000 sats (83-sat fee,
hash prefix `4bde856bb481`), then reviewed and confirmed the same invoice for
1,000 sats. The result was **Invoice already paid**, showing the real 2,000 sats
and 83-sat fee, with an unchanged movement-ID set (exactly one Lightning send).
CLN independently reports `paid` and `amount_received_msat: 2000000`.

The test then hosted the production `SendConfirmation` / `SendOutcomeView` in
its app window. Device-panel accessibility snapshots verified **Estimated fee,
208 sats**, **Estimated total, 5208 sats**, and the complete warning that more
coins may raise the final fee and total. One UI confirm sent 5,000 sats from BDK.
The receipt showed **Submitted**, **5,000 sats**, **Actual fee: 143 sats**, and
txid prefix `ede68f662b8d`. Core independently reports a 5,000-sat receipt with
zero confirmations. No extra block was mined for the outgoing send. The durable
receipt has `proof: submitReturnedTxid`; Lightning has `proof: actualMovement`.
Evidence JSON and accessibility snapshots are in `/tmp/noah-send-r3-live-evidence.json`,
`/tmp/noah-send-r3-review-snapshot.txt`, and `/tmp/noah-send-r3-receipt-snapshot.txt`;
screenshots are `/tmp/noah-send-r3-review.png` and `/tmp/noah-send-r3-receipt.png`.

Repeat on a fresh dedicated simulator with `TEST_RUNNER_NOAH_SEND_ROUND3_LIVE=1`,
`TEST_RUNNER_NOAH_SEND_INVOICE` (a fresh **amountless** regtest BOLT11), and
`TEST_RUNNER_NOAH_SEND_ONCHAIN_RECIPIENT` (a fresh Core address). Select
`-only-testing:NoahTests/SendRoundThreeLiveTests/amountlessInvoiceReuseAndEnteredOnchainFee()`.
The test publishes both funding addresses in `Documents/send-round3-funding.json`;
fund each once within 180 seconds, mine one confirming block, inspect the review
and tap Confirm in the Device panel. After inspecting the receipt, create
`Documents/send-round3-finish` to finish the test. This exercises real quote,
journal, SDK submission, confirmation/receipt UI and reconciliation, with no
faked payment services. It does not drive the composer or manufacture a stuck
live checkpoint; mismatched checkpoint refusal uses SDK-value regression tests.

The dedicated simulator `859E8322-DA9F-418B-879E-A8D53315B016` was shut down,
its exact name and Shutdown state checked, and then deleted. No other device or
Docker service was changed. No wallet data, seeds, invoices or destinations are
committed. Fee ceilings, durable operation IDs and explicit broadcast outcomes
remain FFI items 14–16.

## Round-2 payment safety (2026-10-19)

- Onchain-wallet MAX is disabled in the UI, quote/review and submission paths.
  The only app call to BDK drain was removed. Offchain MAX still submits precisely
  the reviewed VTXO IDs.
- Empty canonical BDK history never proves non-submission. No matches, mismatched
  amounts, multiple exact matches, old MAX attempts without receipts, malformed
  evidence and missing snapshots remain locked. Round 3 supersedes the old
  single-new-transaction rule: a snapshot difference is only possible evidence.
- The pinned FFI exposes a message-only `BarkError`, not typed error variants.
  Only the exact outer address/network, fee-validation and transaction-building
  contexts from `OnchainWallet.send` prove pre-recording failure. Source links
  accompany the allowlist. Persistence, unknown and nested backend errors remain
  uncertain; history absence never substitutes for this proof.
- BOLT11 reconciliation and returned Lightning successes read actual movement
  destination amounts and fees. A successful movement in the pre-submit snapshot
  is **Invoice already paid**, with its real amount, not a new success. Pending
  hash recovery also checks amount; mismatches remain uncertain.
- Refresh revalidates confirmed onchain receipts too. A reorg can move them back
  to Submitted, or to an uncertain “can't find” outcome. The recorded txid survives
  refresh and restart; missing evidence cannot enable retry or delete that record.
  External offboards stay Submitted with explicit in-app tracking limitations.
  Mainnet/signet receipts link to the same explorers as History; regtest has no
  public explorer link.
- Round-1 BDK failures inferred from absence and BOLT11 receipts with unverified
  amounts reopen uncertain. All new journal fields remain optional.
- Send tests use deadline polling rather than fixed completion waits. Receipt
  write-failure injection no longer races the async payment. The receive-close
  test accepts cancellation while still asserting no completion and successful
  session reservation after close.

Live round-2 sanity check: **1 test passed, 0 skipped, 0 failures** on the
`Noah Send Round 2` simulator, through production quote, journal, controller and
SDK submission code. A fresh disposable wallet received 10,000 Ark sats once,
then sent 1,000 sats to Bark (receiver movement 32, successful, zero fee) and
paid a fresh CLN BOLT11 invoice for 2,000 sats (hash prefix `778e6e4c5862`). CLN
reported paid with 2,000,000 msat received. Each submission produced exactly one
new successful sender movement; the Lightning receipt fee matched its movement.
No blocks were mined and no services restarted. The simulator is deleted after
verification. No wallet data, seeds, invoices or runtime destinations are committed.

To repeat this opt-in test on a fresh dedicated simulator, pass
`TEST_RUNNER_NOAH_SEND_ROUND2_LIVE=1`, `TEST_RUNNER_NOAH_SEND_ARK_RECIPIENT`, and
`TEST_RUNNER_NOAH_SEND_INVOICE` (a fresh 2,000-sat regtest BOLT11) to xcodebuild,
with `-only-testing:NoahTests/SendRoundTwoLiveTests`. The test publishes its fresh
Ark address in the app's `Documents/send-round2-funding-address.txt`; fund it once
with `just bark send "<address>" "10000 sats"` within 120 seconds. It never retries
submissions, and requires an empty default regtest wallet directory. This test
uses real wallet operations but does not exercise the SwiftUI controls.

The historical acceptance runs below predate the disabling of onchain MAX.

## Payment-safety verification (2026-10-19)

Dedicated simulator: `Noah Send Fix Agent` (iPhone 17, iOS 27), with DerivedData
inside this worktree. No other simulator or port 3000/3100 service was changed;
Docker services were neither restarted nor recreated. Bark at `bfbe6b7c` calls
its unified payment command `send`; the older `send-to` name is unavailable.

- Funded a new simulator wallet with 100,000 Ark sats and 30,000 onchain sats;
  mined one block for the onchain deposit.
- Sent 5,000 sats from the onchain wallet. UI said **Submitted**, with txid prefix
  `37c9de4686f8`; Core saw 5,000 sats with zero confirmations. Actual fee: 143 sats.
- With 24,857 sats of unconfirmed change and zero confirmed sats, onchain MAX
  refused at source confirmation: **Wait for pending onchain funds to confirm.**
  No payment was submitted. After `just generate 1` and wallet refresh, MAX
  reviewed 24,649 sats plus an explicitly approximate 208-sat fee (24,857 total).
  It submitted 24,757 sats with an actual 100-sat fee, txid prefix `f1d176567e4e`.
  After another block, Refresh changed **Submitted** to **Payment confirmed**.
- Stale-route scenario: opt-in `SendLiveTests` resolved Lightning recipient A to
  Ark route A, then imported distinct Ark address B through the scanner's import
  path. Review and actual SDK submission paid only B, 1,000 sats, zero fee. Local
  sender history and Bark receiver movement 27 agree. Only the LNURL resolver
  was faked; payment, quote, controller journal and history were live. No camera
  hardware or external LNURL provider was involved.
- Paid a fresh CLN BOLT11 invoice for 2,000 sats; review fee 83 sats. UI showed
  **Payment sent**; CLN reported `paid`, 2,000,000 msat received.
- Offchain MAX reviewed 93,989 sats plus 2,928 sats fee, total 96,917 sats. It
  submitted txid prefix `b9579cef9e61`. Compared the persisted quote's five VTXO
  IDs with movement 4's input-VTXO records in SQLite: **exactly the same set**.
  Core received 93,989 sats and confirmed it after one block. UI conservatively
  stays **Submitted** because external offboards are absent from BDK's wallet
  history. Relaunch preserved that submitted txid and never exposed retry.
- Final outgoing Ark-backed history: one Ark send, one Lightning send, one
  offboard; the two BDK sends are separate. No duplicate payment.

The dedicated simulator and `apps/ios/build` are removed after verification.
No screenshots, wallet databases, runtime destinations, invoices, or seeds are
committed.

To repeat the live stale-route test, first create and fund a disposable regtest
simulator wallet and obtain two distinct fresh regtest Ark destinations. Pass
`TEST_RUNNER_NOAH_SEND_LIVE_STALE=1`, `TEST_RUNNER_NOAH_SEND_ROUTE_A`, and
`TEST_RUNNER_NOAH_SEND_ROUTE_B` to the xcodebuild command above, replacing the
skip flag with `-only-testing:NoahTests/SendLiveTests`. This test **spends 1,000
regtest sats**. Without the explicit gate it is disabled.

## Live regtest acceptance (2026-10-19)

Created a wallet through onboarding on the dedicated `Noah Send Agent` simulator.
Used its Receive tab to copy fresh receiving addresses. Existing Docker services
were not restarted or recreated. Loaded the existing Bitcoin Core `dev-wallet`
(it was present but unloaded). All CLI payments used the repository's `just`
wrappers, with Bark's `--no-deps` behavior.

- Funded 100,000 sats over Ark. Receive displayed success and Send showed 100,000
  spendable sats.
- Sent 1,000 sats over Ark, amount-first, with a double-tap on confirm. Exactly one
  successful outgoing movement; zero fee.
- Paid a CLN BOLT11 invoice for 2,000 sats, recipient-first. CLN reported `paid`;
  exactly one successful Lightning movement, 83-sat fee.
- Sent 5,000 sats onchain from Ark. Successful offboard movement, 2,560-sat fee.
  Mined one block with `just generate 1`; Bitcoin Core confirmed receipt of
  0.00005 BTC (txid prefix `d6d1420927d2`).
- Funded the app's onchain wallet with 30,000 sats and one confirmation. MAX showed
  both funding buckets separately (89,357 Ark / 30,000 onchain). Selected onchain;
  review estimated 208 sats in fees. The actual drain sent 29,900 sats with a
  100-sat fee, displayed the actual amount, and left no onchain funds. Mined one
  block; Bitcoin Core confirmed 0.000299 BTC (txid prefix `b14261cfa930`).
- Submitted malformed checksum-less offer `lno1q` for 1,000 sats, forcing a native
  throw after confirmation. No new movement or balance change. Send displayed
  “Payment may have been sent”, disabled Back/confirm, and only offered a status
  check. Repeated checks and a full app relaunch kept that state; no resubmission.
- Final Ark movement net: 89,357 sats. Three outgoing Ark-backed movements plus
  the separate BDK onchain drain; no duplicate payment.
- The custom amount keypad and system recipient keyboard were exercised in
  separate stages. Keyboard Done dismisses input before navigation. Review and
  success controls were checked through the accessibility tree.

The dedicated simulator and `apps/ios/build` are removed after verification.
No screenshots, wallet data or runtime payment destinations are committed.

## Submission safety and limits

`SendPaymentController.shared` locks synchronously on the first confirm tap. It
revalidates the quote/balance and snapshots history, then atomically records the
attempt before submission. A sheet's cancellation cannot cancel its owned task;
all SDK operations use counted `WalletSession.withOpenWallet` calls. A failed
metadata write never converts a successful payment into a retryable failure.

Reconciliation follows the rail and strength of identity:

- Onchain-wallet sends use only BDK transactions, never Ark movements. Both Ark
  movement IDs and BDK txids are snapshotted before submission. A new outgoing
  transaction paying exactly the reviewed amount is only a possible match,
  even if confirmed; it never establishes submission. Missing BDK evidence stays uncertain:
  canonical history can evict a transaction that was already broadcast.
  Malformed/unreadable evidence and old journals without a BDK snapshot stay locked. A persist error can leave the transaction in BDK
  memory, so reconciliation reads transactions even if sync fails.
- BOLT11 uses its pre-known hash plus the movement's actual amount. A successful
  pre-existing movement is already paid; a new exact-amount success uses actual
  amounts/fees. Failed/canceled with the reviewed amount permits a new payment;
  pending or mismatched amounts remain locked.
- Ark, offers, Lightning addresses/LNURL and offboards use weak correlation:
  source, subsystem/kind, destination, exact amount (except MAX), new movement ID
  and creation time. Candidates are shown within the uncertain screen. A weak
  success says it appears to have gone through; weak failure or missing history
  **never automatically unlocks**. The explicit “I've checked my history, start
  a new payment” action requires a second destructive confirmation warning that
  the first payment may still complete. Failed marker removal keeps Send locked.
  The native History tab is still a placeholder; candidate evidence is therefore
  displayed directly here. FFI item 16 replaces this weak matching/override limit.
- A returned txid is persisted as **Submitted**, never as proof of broadcast.
  Refresh can upgrade or downgrade confirmation, preserving the txid even when
  history disappears. An external offboard may remain Submitted until FFI item
  15 exposes chain-source status; copy explicitly explains this limitation.

The journal also persists the reviewed quote (including MAX VTXO IDs), method,
source, destination, amount, start time and BOLT11 hash. Pre-fix journals with weak terminal outcomes are reopened as
uncertain; unproven legacy txids remain evidence only. New submit-returned txids
retain their proof and remain tracked across restart. Recipient assignments
invalidate parsing, routes, comment, choices, quote and payment synchronously;
asynchronous results carry a revision guard, and a route records its originating
LNURL recipient before any substitution is allowed.

MAX safeguards until FFI item 14:

- Onchain MAX is unavailable. Separate balance/drain locks cannot prevent daemon
  sync from discovering additional funds between review and spending.
- Offchain MAX quotes `estimateOffboardFee(address:vtxoIds:)` for the exact
  spendable IDs, rechecks that set before submission, and calls `offboardVtxos`
  with precisely the reviewed IDs. Added, removed or replaced IDs require review.
- `feeRates()` is sat/kwu; integer sat/vB rounds up. Exact BDK construction fees
  remain blocked on prepared sends, so the 208-vbyte estimate for entered amounts
  stays explicitly approximate. Only a proven pre-recording SDK error unlocks
  automatically; missing history never does.

Regression tests use fake dependencies and SDK value objects for route changes,
late resolution, MAX balance/set changes, strong and weak identity, unrelated
history, corrupt transaction evidence, all rail recovery paths, submitted receipts,
confirmation, override confirmation/storage failure, and legacy journal safety.
Existing double-tap, cancellation, corrupt journal, repeat metadata, BOLT11 amount
and expiry, RN fixtures and fee-unit coverage remain intact.

Not exercised live: external LNURL/BOLT12 providers, camera hardware, real network
loss during a submitted payment, and a Rust persistence failure. Those outcome
branches use fault-injected tests. No prepared-transaction, broadcast-status, or
operation-ID FFI capability was invented; items 14–16 remain SDK work.
