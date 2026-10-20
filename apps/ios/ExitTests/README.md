# Native iOS M6 verification

M6 adds Board to Ark, the confirmation-only automatic boarding prompt, VTXO list/detail
and delegated refresh, and emergency-exit overview/review/deposit/detail screens.
Settings links open each surface. All native wallet work uses the counted,
cancellation-shielded `WalletSession.withOpenWallet` boundary.

## Safety and parity limits

**Starting/progressing exits is blocked on bark-bolt-ffi 0.3.0.** Its pinned Bark
commit `bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c` runs
`Daemon::run_exit_progress_process`, which automatically calls
`progress_exits_with_cpfp` with an onchain wallet. Noah's existing session runs that
daemon. Therefore “start tracking, then explicitly Progress to broadcast” cannot be
guaranteed. The screen still estimates fees; its start/progress confirmation buttons
are disabled, and the session rejects these actions before touching the SDK. See
[FFI handoff item 17](../../../docs/native-rewrite/ffi-handoff-prompt.md).
This restriction does not stop or change the existing daemon for wallets already
tracking exits; previously registered exits remain governed by Bark's daemon.

Other deliberate differences from RN:

- Boarding requires a separate amount/Ark fee/onchain fee confirmation. MAX fixes
  the gross amount calculated from confirmed funds instead of calling `boardAll`,
  which could spend additional pending funds. The 208-vbyte onchain fee is explicitly
  an estimate, as in RN; the actual fee/reserve can differ. Prepared boarding is
  requested in FFI item 18.
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
late estimates, background invalidation, expiry and the SDK-policy guard.

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

Final full `NoahTests` run (2026-10-19, final UI/receipt code): **252 tests total,
242 passed, 10 explicitly gated skips, 0 failures**, across 60 suites. Six tests
expand to 36 parameterized runs, so the device execution count is **272 passed,
10 skipped**. Result bundle: `/tmp/noah-m6-final-tests.xcresult`; build/test log:
`/tmp/noah-m6-final-tests.log`. The M6 tests comprise 14 enabled test functions
(including all 187 fixture cases) and one opt-in live function, skipped by default.

## Live acceptance

Dedicated simulator: `Noah-M6-Implementation`, iPhone 17 Pro, iOS 27.0 (`24A434`),
UDID `83ED7724-9CA4-4764-AFD0-EB1ADDBA2696`. No other simulator/device was used.
Docker services were inspected but never started, stopped or recreated.
After verification, the dedicated simulator was closed, shut down and deleted;
its absence was verified with `simctl list devices --json`.

`ExitLiveTests` is disabled by default. Explicit stages use
`TEST_RUNNER_NOAH_M6_LIVE_STAGE=create|board|refresh|estimate`, with
`-only-testing:NoahTests/ExitLiveTests`. Create refuses any existing wallet/seed;
board authorizes exactly 30,000 regtest sats; refresh uses one spendable VTXO;
estimate asserts unchanged exit entries and transaction count. No stage starts or
progresses an exit. Keep this test on a disposable regtest simulator only.

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
