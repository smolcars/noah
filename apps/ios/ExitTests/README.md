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
  Prepared boarding (FFI 18) has the same priority as prepared sends (FFI 14).
- Confirmation must be younger than 30 seconds for every M6 mutation. The session
  rechecks wallet identity, selected VTXOs, destination, amounts, both fee legs and
  confirmed balance. A changed or stale quote requires another review.
- Every mutation records an atomic attempt inside the wallet directory before SDK
  submission. It survives errors/relaunch; unresolved attempts block further M6
  mutations. There is no automatic retry or amount-only reconciliation. A returned
  receipt stays until acknowledged. Durable operation lookup is requested in FFI 19.
- Claims compare the built PSBT's fee to the reviewed estimate before broadcasting.
  A changed fee releases the attempt without broadcasting. Unknown native failures
  retain it. Cancellation is rechecked by Bark, and a refused cancellation remains
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
