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

## Round-3 payment safety (2026-10-19)

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

## Round-2 payment safety (2026-10-19)

- Onchain-wallet MAX is disabled in the UI, quote/review and submission paths.
  The only app call to BDK drain was removed. Offchain MAX still submits precisely
  the reviewed VTXO IDs.
- Empty canonical BDK history never proves non-submission. No matches, mismatched
  amounts, multiple exact matches, old MAX attempts without receipts, malformed
  evidence and missing snapshots remain locked. A single new outgoing transaction
  paying exactly the reviewed amount can establish Submitted; only confirmation
  establishes Confirmed.
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
  transaction paying exactly the reviewed amount establishes submission only
  when it is the sole exact candidate. Missing BDK evidence stays uncertain:
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
uncertain; previously returned txids remain submitted. Recipient assignments
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
