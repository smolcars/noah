# Native iOS M6 verification

M6 adds Board to Ark, the confirmation-only automatic boarding prompt, VTXO list/detail
and delegated refresh, and emergency-exit overview/review/deposit/detail screens.
Settings links open each surface. All native wallet work uses the counted,
cancellation-shielded `WalletSession.withOpenWallet` boundary.

## Safety and parity limits

Emergency exits follow RN's runtime semantics. RN's bark-cpp also runs Bark's
exit daemon, and RN maintenance includes onchain sync and exit progression.
Starting requires a review of the selected VTXOs and fee estimate, with explicit
consent to subsequent automatic background broadcasts/fee bumps paid from the
onchain balance. Manual **Progress Exits** requires its own broadcast/fee-bump
confirmation. Cancel remains available only before the final exit transaction is
broadcast; claims keep a destination and fee review. Estimates and screen reads
never invoke progression or broadcast. An optional manual-progression daemon
policy is P2 [FFI item 17](../../../docs/native-rewrite/ffi-handoff-prompt.md).

Other deliberate differences from RN:

- Boarding requires a separate amount/estimated Ark fee/estimated onchain fee
  confirmation. MAX now calls `boardAll`, matching RN's `useBoardAllArk` →
  `boardAllArk` → Nitro `boardAll` (`client/src/hooks/usePayments.ts:278`,
  `client/src/lib/paymentsApi.ts:166`). The review explains it can include spendable
  pending funds and the final amount can differ. Auto-Board still uses a fixed
  amount with an estimated 5,000-sat reserve, like RN. All boarding fees/received
  amounts are estimates: the final fee may be higher if more coins are needed.
  The receipt persists/displays the SDK's actual `onchainFeeSats` when available;
  a failed fee lookup cannot turn a returned submission into an unknown outcome.
  Missing actual fees are retried by exact persisted receipt txid on screen refresh,
  polling and relaunch, updating the saved receipt/message without resubmission.
  Auto-Board also retains its receipt until Done in Settings → Board to Ark.
  Prepared boarding (FFI 18) has the same priority as prepared sends (FFI 14).
- Confirmation must be younger than 30 seconds for every M6 mutation. The session
  rechecks wallet identity, selected VTXOs, destination, amounts, both fee legs and
  confirmed balance. A changed or stale quote requires another review.
- Every mutation records an atomic attempt inside the wallet directory before SDK
  submission. Attempts survive errors/relaunch and block only overlapping repeats
  of the same operation; uncertain board/refresh never blocks exits, claims or
  cancellations. Bark makes repeated active starts/already-canceled cancels harmless.
  Read-only **Check status** reads cached exact txids and per-item VTXO/exit
  states only. It never syncs: even BDK sync may rebroadcast already-authorized
  transactions. Bark’s daemon keeps the cache fresh. Inconclusive checks say
  “Noah checks again automatically as your wallet syncs.” Only conclusive evidence resolves
  an attempt. Missing IDs, locked refresh inputs and unknown errors remain uncertain.
  **Override repeat protection…** requires explicit destructive repeat-risk consent.
  The original attempt remains atomically saved with the override time, quote,
  IDs, status message, and the exact alert/confirmation text. Overrides do not
  block new submissions. Screen refresh/polling/relaunch reconciles all unresolved
  attempts from cached evidence, including overridden originals; a late original
  receipt remains visible separately from a new submission. Done hides that
  receipt while retaining its audit record. Saves retain the newest 100 overrides
  by override time (including acknowledged records), pruning only older overrides;
  active attempts are never pruned. Existing single/array journals decode without
  override metadata and migrate on their next atomic write.
  There is no automatic retry or amount-only reconciliation. Receipts stay until
  acknowledged; durable operation lookup for the pre-ID window remains FFI 19.
- Claims compare the built PSBT's fee to the reviewed estimate before broadcasting.
  A changed fee releases the attempt without broadcasting. Unknown native failures
  retain it. With FFI 0.4.0, only typed BDK `insufficientFunds`/`dust` errors from
  boarding clear the attempt: construction precedes payment recording/broadcast.
  Refresh/start/claim/cancel validation still returns `other` in this SDK; those
  errors remain uncertain. Local claim-fee changes and explicit cancellation
  refusals still clear safely. No diagnostic-prefix matching remains. Cancellation
  is rechecked by Bark, and a refused cancellation remains
  visible as an error rather than being shown as successful.
- No estimator-unavailable emergency fallback. No broadcasting `syncExits` call on
  screen appearance, pull-to-refresh, polling, or fee review. Status polling is
  read-only at 60 seconds. The background-push counter gates queries like RN's
  `isBackgroundJobRunning`; counted calls also preserve close/restore coordination.
- Native lists, sheets, selection menus and system numeric keyboard follow Settings'
  styling and system Liquid Glass navigation. No custom keyboard overlaps the system
  keyboard. Amounts follow the stored bitcoin unit. Fiat annotations and the RN
  wide-screen split pane are not ported here; technical details use native selectable
  text. The SDK's additional `VtxoSwept` terminal state is shown explicitly.

Terminal details read `getExitStatus(id)` directly, including history, rather than
searching the active overview. Discovered IDs are stored in a wallet-fingerprint
scoped file before start/claim/cancel and whenever active exits are read. Legacy
`bark.exit` movements seed additional input/exited IDs. Canceled/claimed rows thus
stay discoverable after close/reopen. RN's detail currently also depends on its
tracked overview; this preserves its intended terminal timeline despite the newer
Bark active-list semantics. FFI item 20 requests an all-exits listing for imports
whose terminal IDs/movements were never available to this app.

## Shared fixtures and tests

From the repository root:

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-exits.ts
```

The generator executes the RN sources with platform/network dependencies mocked.
It includes 82 fee/eligibility/review cases, 56 cancellation cases, 12 timeline cases,
16 overview availability cases, and 21 automatic boarding cases: **187 cases**.
Generation was repeated and produced identical hashes. `ExitFixtureTests` consumes
all five files; Android can consume them unchanged.

`OnchainSafetyTests` covers durable-before-call ordering, persistence failure,
restart uncertainty, acknowledgement, quote input correspondence, wallet readiness,
late estimates, background invalidation, expiry, start/progress authorization, suspension after review, MAX intent and
backward-compatible receipt/journal decoding with actual fee capture.

Use a dedicated iOS 27.0 simulator. Regenerate from `apps/ios`:

```sh
nix develop --no-pure-eval -c /opt/homebrew/bin/xcodegen generate
```

Then, from the repository root:

```sh
nix develop --no-pure-eval -c xcodebuild \
  -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$M6_SIMULATOR" \
  -derivedDataPath /tmp/noah-m6-implementation-dd \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGN_IDENTITY=- \
  -parallel-testing-enabled NO -collect-test-diagnostics never test
```

Keep simulator signing enabled: `CODE_SIGNING_ALLOWED=NO` removes Keychain
entitlements and caused 14 issues in existing Keychain/Expo/lifecycle tests on the
first run. With normal ad-hoc signing the full target passed.

Original full `NoahTests` run (2026-10-19, before parity correction): **252 tests total,
242 passed, 10 explicitly gated skips, 0 failures**, across 60 suites. Six tests
expand to 36 parameterized runs, so the device execution count is **272 passed,
10 skipped**. Result bundle: `/tmp/noah-m6-final-tests.xcresult`; build/test log:
`/tmp/noah-m6-final-tests.log`. The M6 tests comprise 14 enabled test functions
(including all 187 fixture cases) and one opt-in live function, skipped by default.

## Original live acceptance (before parity correction)

Dedicated simulator: `Noah-M6-Implementation`, iPhone 17 Pro, iOS 27.0 (`24A434`),
UDID `83ED7724-9CA4-4764-AFD0-EB1ADDBA2696`. No other simulator/device was used.
Docker services were inspected but never started, stopped or recreated.
After verification, the dedicated simulator was closed, shut down and deleted;
its absence was verified with `simctl list devices --json`.

`ExitLiveTests` is disabled by default. Explicit stages use
`TEST_RUNNER_NOAH_M6_LIVE_STAGE=create|board|refresh|estimate`, with
`-only-testing:NoahTests/ExitLiveTests`. Create refuses any existing wallet/seed;
board authorizes exactly 30,000 regtest sats; refresh uses one spendable VTXO;
estimate asserts unchanged exit entries and transaction count. The original run did not start or progress an exit. The follow-up stages below
extend this harness. Keep it on a disposable regtest simulator only.

- Created a fresh throwaway wallet using the production session, with automatic
  boarding disabled for the manual test. No seed was printed or exported.
- Funded its new onchain address with 100,000 sats via `just send-to … 0.001`.
  Funding txid `680b646cb203c3d7fe7d55adf6c24d23c151c3b762f3ba61c3bd948701dc8bc2`;
  the wrapper mined one block, and native Home showed 100,000 sats confirmed at
  block 189 after pull-to-refresh.

- Boarded 30,000 sats through Settings → Board to Ark, after a separate native
  confirmation showing 330 sats Ark fee, 208 sats estimated onchain fee, and 29,670
  sats net. Funding transaction
  `7fca5acf4b99f11ed90fa5929dcf3f382bd126e056e539e08ad78c33cefa34e5`.
  The durable receipt appeared with submission controls disabled until Done.
  Mined three blocks total to satisfy `required_board_confirmations = 3`.
  Native VTXOs showed one Active VTXO of 29,670 sats, expiry height 669,
  exit delta 144, depth 1, and the expected funding anchor.
- The onchain transaction's actual fee was **1,543 sats**, confirmed using the SDK's
  `onchain.transactions().onchainFeeSats`, versus the displayed **208-sat estimate**.
  This is concrete evidence for FFI item 18; a 5,000-sat *estimated* auto-board reserve
  is not a guaranteed actual reserve with this SDK.
- Refreshed that VTXO from its detail screen. Confirmation showed one VTXO,
  29,670 sats selected, 269 sats fee, and 29,401 sats after fee. SDK receipt:
  `Refresh submitted to delegated round 1.` No repeat submission was made.
  The first read-only estimate test correctly refused while the round inputs were
  locked. Core had round transaction
  `cb9c523a291f51c9451c5de429013b089cf78bdef3829b3916f492c18c7f567e` in its mempool;
  mined one block and repeated only the read-only estimate check.
- Confirmed refreshed value: **29,401 sats**. Exit estimate: **7,630 sats broadcast**,
  **1,295 sats claim**, **28,106 sats estimated proceeds**. Live assertions passed
  with zero exits before/after and unchanged wallet transaction count. The create
  and final estimate opt-in runs each passed one test. No exit registration or
  progression was invoked.
- Opened Emergency Exit through Settings and reviewed the entire wallet: the native
  sheet showed the same 29,401 / 7,630 / 1,295 / 28,106 sat values and a disabled
  Start button with the SDK restriction. The deposit sheet showed the 7,630-sat
  suggestion, a new Bitcoin address, QR code, copy and share controls.
- Enabled Auto-Board in Settings and returned Home. Its explicit confirmation
  showed 63,249 sats gross, 330 sats Ark fee, 208 sats estimated chain fee, 62,919
  sats net and a 5,000-sat estimated reserve. Chose **No, turn off**; no additional
  board was submitted. The service uses a concrete view container so its task also
  runs when there is no status message to display.

Full exit broadcast/claim/cancel lifecycle, physical hardware and external network
acceptance remain unverified. In particular, fixture coverage of cancellation is
not a live cancellation test. No exit was broadcast during this acceptance run.

## Parity correction verification (2026-10-19)

Dedicated simulator: **Noah M6 Parity 2364**, iPhone 17 Pro, iOS 27.0,
UDID `BA9EEBC6-5631-4B45-9870-0A2C2B35C99C`. Derived data is isolated at
`/tmp/noah-m6-parity-2364-dd`. No other simulator/device or Docker lifecycle was
changed. Regtest readiness was probed using `just bcli getblockchaininfo`.

`ExitLiveTests.regtestAcceptance()` now requires an on-device fingerprint marker
written only by its `create` stage before subsequent operations. The marker proves
this test owns the throwaway wallet; no seed is exported. Additional explicit
stages are `start` (one eligible VTXO ≤ 30,000 sats, refuses existing exits),
`observe` (daemon/status only), `progress` (fresh reviewed manual progression),
`claim` (fresh reviewed claim to a new address in this same wallet), and `max`
(reviewed `boardAll`, checks actual fee receipt). Mutation stages journal before
SDK calls and never clear an uncertain attempt. Each state observation is saved
as `Documents/m6-live-<stage>.json` in the simulator app container.

Use the full Swift Testing function ID, including trailing parentheses:

```sh
TEST_RUNNER_NOAH_M6_LIVE_STAGE=create xcodebuild \
  -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=BA9EEBC6-5631-4B45-9870-0A2C2B35C99C' \
  -derivedDataPath /tmp/noah-m6-parity-2364-dd \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGN_IDENTITY=- \
  -parallel-testing-enabled NO -collect-test-diagnostics never \
  '-only-testing:NoahTests/ExitLiveTests/regtestAcceptance()' test
```

The final full `NoahTests` target passed: **271 tests total, 260 passed,
11 explicitly gated skips, 0 failures**, across 63 suites. Seven parameterized
tests expand to 40 runs; device execution is **293 passed, 11 skipped**.
`xcresulttool get test-results summary` confirms both counts. Result bundle:
`/tmp/noah-m6-parity-2364-final.xcresult`; log:
`/tmp/noah-m6-parity-2364-final.log`. The initial full run also passed with the
same counts; the final run includes the last suspension UI gate changes.

Live evidence:

- Created a fresh native wallet, fingerprint `ca5e1e08`, and a separate Core wallet
  `noah-m6-parity-2364-funder`. Mined 101 blocks to mature that new funder's coinbase.
  All broadcasts used wallets created in this run; no existing Bark/Core wallet
  funded it. Sent **100,000 sats** from this funder to the native wallet:
  `768c399bdcc6358002537b80df048cfa86eadeb6501b52585f33f8c38fa8e5f3`,
  confirmed in block 295.
- The `create` and `board` opt-in test runs each passed exactly one test. Boarded
  **30,000 sats gross / 29,670 sats net** with **330 sats estimated Ark fee** and
  **208 sats estimated onchain fee**. The returned receipt explicitly displayed
  **Actual onchain fee: 1543 sats.** Mined three board confirmations. Native Home
  showed 98,127 sats total; Emergency Exit showed 68,457 sats confirmed onchain.
- Through Settings → Emergency Exit, selected the single 29,670-sat VTXO and
  reviewed **3,316 sats estimated broadcast fees**, **1,295 sats estimated claim
  fee**, **28,375 sats estimated proceeds**. The enabled native confirmation
  explained automatic background broadcasts/fee bumps and onchain fee payment.
  Confirmed **Start Emergency Exit** through the UI; its durable start receipt was
  shown and acknowledged. Saved receipt: `/tmp/noah-m6-parity-2364-ui-start.json`.
- Without any manual progress call, `observe` passed one test and recorded
  **Processing**, final parent `eb339065cf83c64883bb8df437ab10d5d5d3508a242aff5be6912a5ca1cff7d6`
  and CPFP child `c60fbd1c1d1bed0c5e1be2baad82c4482d59e4c1dbfde0dc7d627115637d244f`.
  Core independently showed both in its mempool. Cancellation was false after
  final broadcast. Saved observation: `/tmp/noah-m6-parity-2364-auto-broadcast.json`.
- Mined one block: parent/child confirmed in block **300**
  (`0d62b5418fb5ee30a073939b0a3758569d877d577270e2fcbd4a44cfd6218c9a`).
  `observe-confirmed` passed one test and recorded **AwaitingDelta**, claimable at
  **444** (144-block delay). Saved observation:
  `/tmp/noah-m6-parity-2364-timelock.json`.
- Mined 144 blocks to height 444. The explicit `progress` stage passed one test
  through the production reviewed/journaled action and recorded **Claimable**,
  `claimable_since = 444`, and cancellation false.
- The `claim` stage passed one test. It reviewed 29,670 sats and a **1,295-sat
  claim fee** to a fresh address in the same wallet, compared the built PSBT fee,
  and submitted `d79e09e34ccbc85bc89a1ca03a725fa6e8e274a0a6b49befc938bee061ed07d0`.
  Core independently showed it in the mempool: 518 weight units / 130 vbytes,
  exactly **0.00001295 BTC (1,295 sats)** fee. Receipt is persisted as
  `Documents/m6-live-claim-receipt.json`. Mined one confirming block, 445
  (`211cbeb313d77add44554b49056d22326f6f2334a0b7b9af5918aa9b9efa1e38`).
- `observe-claimed` passed one test after confirmation and returned an empty
  pending-exit list. Native Home showed **94,399 sats** total and a confirmed
  **28,375-sat** receipt at block 445. The SDK removes completed entries from this
  list; this run did not verify a retained terminal-detail screen.
- Suspended the wallet through Settings. **Show VTXOs**, **Emergency Exit**, and
  **Board to Ark** remained navigable, matching RN. The screens loaded no wallet
  data and disabled all actions, including deposit address generation and amount
  entry/MAX. VTXOs/Board showed the wallet-readiness message. Enabled Auto-Board
  while suspended and returned Home: only the suspended card appeared, with no
  auto-board prompt; pull-to-refresh preserved that state. The model regression
  test independently asserts zero reads/mutations when readiness is false.
- Resumed the wallet. Auto-Board then showed **89,191 sats estimated gross**,
  **330 sats estimated Ark fee**, **208 sats estimated onchain fee**,
  **88,861 sats estimated received**, and an **estimated 5,000-sat reserve**.
  The prompt said the final fee may be higher if more coins are needed. Selected
  **No, turn off**; no automatic board was submitted.
- Through Settings → Board to Ark → MAX → Review, the sheet showed **94,191 sats
  estimated gross**, **330 sats estimated Ark fee**, **208 sats estimated chain
  fee**, and **93,861 sats estimated received**. It explicitly explained that MAX
  boards all available funds, including spendable pending funds, and the amount
  may differ. Confirmed once through the UI. The persisted quote has
  `boardAll = true`; confirmed onchain balance became **0**. The receipt showed
  **Actual onchain fee: 1,691 sats** in both its message and formatted fee row,
  with txid `d49174d2933dc81524832059d74b5f0ce3a01b97f6aa52c821ca558c73d508e9`.
  Core independently reported **674 weight units / 169 vbytes**, **0.00001691 BTC
  (1,691 sats)** fee. Acknowledged the receipt and mined three confirmations.
  Saved receipt: `/tmp/noah-m6-parity-2364-ui-max.json`.

All **seven** explicit live test invocations (`create`, `board`, `observe`,
`observe-confirmed`, `progress`, `claim`, `observe-claimed`) passed exactly **one
selected Swift Testing test**, with zero skips/failures. Start and MAX were
submitted through the native UI; their durable receipts were independently saved.
Each live result bundle/log uses `/tmp/noah-m6-parity-2364-<stage>.xcresult` / `.log`.

Still unverified: successful live cancellation before final broadcast, a retained
terminal exit detail, physical hardware and external networks. The 56 RN-derived
cancellation fixtures and claim fee check remain intact. The live run used only
confirmed inputs for MAX; inclusion of pending funds follows the SDK's `boardAll`
contract and was not separately funded/exercised here. No process was killed
mid-submit; durable uncertainty/restart behavior is covered by unit tests. No
automatic app-level resubmission was introduced; the authorized Bark daemon
continues progressing tracked exits.

Cleanup: closed this simulator's Device panel and shut down **Noah M6 Parity 2364**.
Before deletion, `simctl list devices --json` was checked for its exact name and
UDID and **Shutdown** state. Deleted only that simulator and verified its absence.
The separately created Core funding wallet remains named
`noah-m6-parity-2364-funder`; no existing wallet was reset, unloaded or deleted.

## Recovery review fixes (2026-10-19)

Owned simulator **Noah M6 Recovery Sol ee0d**, iPhone 17 Pro, iOS 27.0,
UDID `386B936B-02F3-4480-B542-F4FF386CDB92`; derived data
`/tmp/noah-m6-recovery-ee0d-dd`. The uncertainty change passed **16 tests in
3 suites**, zero skips/failures (`OnchainRecoveryTests`, `OnchainJournalTests`,
`OnchainModelTests`), result `/tmp/noah-m6-recovery-ee0d-targeted1.xcresult`.
Tests exercise overlapping scopes, harmless repeat policy, exact outer error
contexts, unknown failures, conclusive/inconclusive checks, wallet identity,
explicit discard, legacy migration, relaunch and no model-driven resubmission.

Terminal-discovery follow-up passed **8 tests in 2 suites**, zero skips/failures
(`ExitHistoryTests`, `OnchainModelTests`), result
`/tmp/noah-m6-recovery-ee0d-targeted2.xcresult`. Tests cover removal from active
lists, wallet-scoped ID persistence after restart, direct detail/timeline loading
with an empty overview, and suspension clearing the detail.
Opt-in `cancel-cycle` and `cancel-reopen` stages keep the daemon disabled so a
throwaway exit can be canceled before automatic final broadcast; normal app
automatic progression remains unchanged.

Receipt-fee follow-up passed **3 tests in 2 suites**, zero skips/failures: two
`OnchainReceiptFeeTests` plus the opt-in `board-missing-fee` live stage, result
`/tmp/noah-m6-recovery-ee0d-fees-board.xcresult`. Fee tests cover an unavailable
lookup, exact txid/wallet matching, persistence after reopen, unchanged actual
fees and zero submission calls from model refresh.

### Live recovery review verification

All **seven live stages** (`create`, `board-missing-fee`, `reconcile-fee`,
`cancel-cycle`, `cancel-reopen`, `uncertain-board`, `check-uncertain-board`) passed
one selected Swift Testing function each, with zero skips/failures. The board
stage also ran the two fee unit tests above. Every live invocation used the full
`-only-testing:NoahTests/ExitLiveTests/regtestAcceptance()` ID, arm64,
`ONLY_ACTIVE_ARCH=YES`, ad-hoc signing, `-jobs 2`, isolated derived data and the
owned simulator listed above. Each result/log is under
`/tmp/noah-m6-recovery-ee0d-<stage>.xcresult` / `.log`, except the combined board
stage uses `fees-board`. JSON evidence and the rendered canceled detail screenshot
are copied to `/tmp/noah-m6-recovery-ee0d-evidence/` before simulator deletion.

- Created a throwaway native wallet, fingerprint `1f74458b`, with automatic
  boarding disabled. No seed was printed or exported. Funded it with **100,000
  sats** from Core's regtest `dev-wallet` using `just send-to`; funding txid
  `309a76a4405225323ac36e799a55961329c0c0674163ae1b38b170df761c1899`,
  confirmed in block **449** by the wrapper's one block. No Docker lifecycle
  command or unrelated wallet reset was used.
- Boarded **30,000 sats gross / 29,670 net**, with **330 sats estimated Ark fee**
  and **208 sats estimated chain fee**. Txid
  `c1811d23be12142ceac6617cb4cb7438906fb96c2835e489549eb19fa1397062`.
  The initial SDK actual fee was **1,543 sats**. To exercise an unavailable
  initial lookup, the harness removed only the receipt's fee/message, retaining
  the actual txid. A new test process reopened the wallet and called the production
  model's `load`: the saved receipt gained **1,543 sats** and the actual-fee message,
  with the same txid and unchanged wallet transaction set. No board was repeated.
- Mined exactly **three** board-confirmation blocks with `just generate 3`, reaching
  **452**. With the daemon disabled only for this explicit cancellation test,
  started then canceled VTXO
  `06b19efc51711569e274792053652eadff9c8914912459085675abae58043601:0`
  through freshly reviewed production session operations. Bark's active list was
  empty immediately afterward, while direct detail and the app overview retained
  **Canceled**, amount **29,670 sats**, and **Start → Canceled** at height 452.
- A separate `cancel-reopen` process reopened the wallet and repeated those detail,
  timeline, discoverability and empty-active-list assertions. Then the normal app
  was launched through the Device panel: Settings → Emergency Exit showed the
  canceled row, and its detail visibly showed **Started** and **Canceled · Current**.
  Screenshot: `/tmp/noah-m6-recovery-ee0d-evidence/canceled-detail.png`.
- Submitted a separate reviewed **30,000-sat** board once, txid
  `b80de545fc26f64c3f3f8904485fd6c1eedd36d122184ddc1eacf2fe795be0f3`.
  Simulated losing the final receipt write by leaving its persisted txid and
  removing its receipt. After reopening, the production model could review an
  emergency exit despite that unresolved board. **Check status** resolved the
  attempt from the exact txid; the receipt recorded **1,543 sats**, and the wallet
  transaction set was unchanged before/after checking. Core's `getmempoolentry`
  independently showed this tx: **616 weight units / 154 vbytes**, fee
  **0.00001543 BTC**, `unbroadcast=false`.

Limits: the receipt-loss tests simulate persisted crash outcomes; no process was
killed during a broadcast. A crash before the SDK returns any correlatable ID
cannot be conclusively reconciled by amount or by an empty transaction list.
Those attempts stay scoped/uncertain until conclusive state evidence or the
explicit destructive override; FFI 19 requests durable IDs. FFI 20 requests
all-exit discovery for terminal records without app-known IDs or retained exit
movements. The previous full claim lifecycle was not rerun here; no physical
hardware or external network was used. Normal automatic exit progression, all
review/freshness/cancellation rules and automatic-boarding thresholds are unchanged.

### Final full suite

The full `NoahTests` suite was run **once** after the implementation and live
checks: **294 tests total, 282 passed, 12 explicitly gated skips, 0 failures**,
across **69 suites**. Eleven parameterized tests expand to 50 runs, giving device
execution **321 passed, 12 skipped**. Both counts are confirmed by
`xcresulttool get test-results summary`. Result:
`/tmp/noah-m6-recovery-ee0d-full.xcresult`; log:
`/tmp/noah-m6-recovery-ee0d-full.log`; saved summary:
`/tmp/noah-m6-recovery-ee0d-evidence/full-summary.json`. `git diff --check` passes.

Cleanup: closed the owned simulator's Device panel and shut it down. Immediately
before deletion, `simctl list devices --json` was checked for the exact name
**Noah M6 Recovery Sol ee0d**, UDID `386B936B-02F3-4480-B542-F4FF386CDB92`, and
**Shutdown** state. Deleted only that UDID and verified its absence. No other
simulator, Android emulator, physical device or Docker lifecycle was changed.

## Round-2 read-only and override fixes (2026-10-19)

The earlier recovery verification's unchanged transaction set did **not** prove
Check status was read-only: pinned Bark BDK sync calls `rebroadcast_txs`, which
can resend eligible transactions the user already authorized. Check status now
uses only cached transactions, exact VTXO states and persisted exit status, via
`WalletSession.OnchainAttemptReads`. That seam exposes no sync/progression/
broadcast capability. Missing transaction IDs, locked inputs and insufficient
cached evidence stay unresolved. The UI explains: **Noah checks again
automatically as your wallet syncs.** The normal authorized daemon is unchanged.

The audit covered every M6 read path: manual/automatic boarding estimates,
refresh/exit/claim/cancel fee reviews, VTXO list/detail, exit overview/detail,
legacy exit-movement discovery, and exact-txid receipt-fee lookup. The SDK 0.3.1
core adapters and pinned Bark `fees.rs`, `exit/estimate.rs`, `exit/mod.rs` and
`onchain/bdk.rs` were inspected. These other reads do not invoke wallet/BDK sync,
exit progression or broadcast; chain-tip/status/fee queries are read-only. All
fund-moving calls remain inside the existing reviewed, journaled execute path.

Override confirmation now atomically updates the attempt rather than deleting
it. The retained quote/IDs, override time, visible status, alert title, warning
and confirmation text survive restart. Overridden attempts never block repeats,
including after their original receipt becomes known. Screen refreshes reconcile
them without resubmission, and late original receipts appear separately from a
new operation. Done records acknowledgement and hides the receipt while keeping
the audit. The newest 100 overrides are retained by timestamp; normal attempts
are never pruned. Both prior single-object and array journals migrate on write.

Dedicated simulator: **Noah M6 Round2 Sol 1a9053**, iPhone 17 Pro, iOS 27.0,
UDID `145E75FF-469C-49D6-BDA7-CE44CA3705BF`. Derived data:
`/tmp/noah-m6-round2-1a9053-dd`. XcodeGen was regenerated after adding
`OnchainOverrideTests.swift`. All runs use arm64, `ONLY_ACTIVE_ARCH=YES`, ad-hoc
signing, `-jobs 2`, disabled parallel testing, and only this owned simulator.
T3's Device service rejected the newly created UDID twice as unknown despite
`simctl` confirming it, so Xcode targeted its exact UDID directly.

Targeted verification:

- Cached reader / model: **12 tests in 2 suites**, zero skips/failures; result
  `/tmp/noah-m6-round2-1a9053-readonly.xcresult`.
- Recovery, journal, model, override, receipt fees and exit history: **25 tests
  in 6 suites**, zero skips/failures (**26 passed device executions**, including
  the two journal-schema arguments); result
  `/tmp/noah-m6-round2-1a9053-overrides.xcresult`.
- Acknowledgement follow-up: **4 tests in 1 suite**, zero skips/failures
  (**5 passed device executions**), including entering another review after Done
  without resurfacing an acknowledged override; result
  `/tmp/noah-m6-round2-1a9053-acknowledgement.xcresult`.

Tests exercise exact cached-read call traces, insufficient/mismatched evidence,
operation/item scopes, the closed pre-broadcast allowlist, persistence before
submission, single/array migration, override → new submission → late original
receipt, exact original fee lookup, restart, bounded pruning, and retained audit
acknowledgement. The model fake verifies one authorized new submission and no
additional submission during checks/refresh/relaunch. Live wallet broadcasts,
cancellation/claim lifecycle, external networks and physical hardware were not
rerun for these changes; no Docker command or unrelated device was used.

The full `NoahTests` suite was run **once**, after the final code changes:
**316 tests total, 303 passed, 13 explicitly gated skips, 0 failures**, across
**74 suites**. Parameterized device execution is **349 passed, 13 skipped**.
`xcresulttool get test-results summary` confirms both counts. Result:
`/tmp/noah-m6-round2-1a9053-full.xcresult`; log:
`/tmp/noah-m6-round2-1a9053-full.log`; summary:
`/tmp/noah-m6-round2-1a9053-full-summary.json`. All targeted result logs use the
same basename as their `.xcresult` with a `.log` suffix. `git diff --check` passes.

Cleanup: Xcode shut down the owned simulator after testing. Immediately before
deletion, `simctl list devices --json` was checked for its exact name
**Noah M6 Round2 Sol 1a9053**, UDID `145E75FF-469C-49D6-BDA7-CE44CA3705BF`, and
**Shutdown** state. Deleted only that UDID and verified its absence; before/after
inventories are `/tmp/noah-m6-round2-1a9053-before-delete.json` and
`/tmp/noah-m6-round2-1a9053-after-delete.json`. No other simulator, Android emulator,
physical device, or Docker lifecycle was changed.

## M6 claim destination fix (2026-10-20)

Based on `b46fc2e2`. Claims retain RN's ability to sweep directly to cold storage
or another valid Bitcoin address on this wallet's network. Emergency Exit
prefills a fresh Noah wallet address and offers **Use my Noah wallet address**.
Native onchain `isMine` checks ownership. External destinations show **This address
isn't in your Noah wallet. The claimed funds will be sent there.** beside the
field and in the explicit claim review. The persisted quote includes ownership;
execution rebuilds the quote and rechecks address, network and ownership before
submission. An ownership change requires another review. Existing quote journals
without the optional ownership flag remain readable.

New tests cover owned/external destinations, invalid addresses, payment-URI and
wrong-network rejection, regtest witness/legacy and signet address handling,
quote ownership correspondence/persistence, fresh prefill/restoration, late
ownership results and unavailable-wallet gates. No funded live broadcast or
external network/hardware test was repeated for this small fix.

Targeted verification passed **9 tests in 3 suites**, zero failures/skips:
`ExitClaimDestinationTests`, `ExitClaimDestinationModelTests`, `OnchainJournalTests`.
XcodeGen was regenerated in `apps/ios`. The full `NoahTests` target was run
**once** with signing enabled (`CODE_SIGN_IDENTITY=-`), `ARCHS=arm64`,
`ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, disabled parallel testing and isolated
DerivedData `/private/tmp/noah-m6-fix-8771-dd`.

**410 tests total: 393 passed, 17 explicitly gated skips, zero failures**, across
**91 suites**. Parameterized device execution: **520 passed, 17 skipped**.
`xcresulttool get test-results summary` confirms the counts. Evidence:
[targeted summary](evidence/m6-fix-targeted-summary.json),
[full summary](evidence/m6-fix-full-summary.json).

Owned simulator **Noah M6 Fix Sol 8771**, iPhone 17 Pro, iOS 27.0,
UDID `4A201247-BD68-457C-974E-51C243FABEB5`. T3's Device service rejected this
newly-created UDID twice as unknown, so Xcode targeted it directly after simctl
confirmed its identity. After testing, its exact name/type/UDID were checked,
it was shut down, **Shutdown** was rechecked, and only that UDID was deleted;
absence was verified. [Cleanup evidence](evidence/m6-fix-device-cleanup.json).
Task-owned DerivedData, result bundles and build/log scratch outside the worktree
were deleted after retaining these summaries. No other device or Docker command
was used.
