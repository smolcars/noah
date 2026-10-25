# Android Home, launch gates and QR parity

Base: `feat/native-rewrite` at `c7b5f3e6`. Implementation is confined to Android,
shared parity fixtures, and the Android checklist entries. No iOS, RN, server,
seed-storage, Send operation, recurring-payment, or onchain-operation logic changed.
`AutoBoardPrompt` gains only a UI completion callback; Send gains only destination
handoff wiring.

## RN behavior

- Launch: seed/recovery blocking states resolve before platform gates. The existing
  biometric dialog remains above every screen. Initialized physical devices block
  on resolved DENIED notification status, with request, recheck, and settings
  actions and foreground rechecking. Emulators skip this gate as RN does.
- Battery: after email on GMS, after UnifiedPush on non-GMS, and once for an
  initialized upgraded wallet if still optimized and never prompted. Requests the
  per-app exemption, falls back to the optimization list then app details, and
  auto-continues on foreground after exemption. Skip persists RN's
  `hasSeenBatteryOptimizationPrompt`.
- UnifiedPush: saved/connected distributor selection, installed distributor labels,
  endpoint display/copy, asynchronous registration, server registration, and
  onboarding Skip/Continue. Retains connector 3.1.2 storage and `noah-<variant>`.
  Deliberate improvement: after 30 seconds without an endpoint, show a retryable
  error; RN leaves its registering state stuck after stopping its timer.
- Home: total-balance fiat, the persisted currency and privacy symbol, existing
  shared fiat rounding/formatting, Settings/Map/QR shortcuts, and all 24 RN facts
  selected on mount and refresh. Backup progress/failure+retry/five-second success,
  automatic-board five-second success, and active pending rounds with 30-second
  polling, detailed status, copy, and explorer actions. Existing email banner and
  recent activity remain in place.
- Version: the existing service feeds RN's nonblocking Update Available copy,
  current/latest version labels and variant Play Store link. Failure permits use.
  RN does not force a blocking update screen.
- QR: literal Lightning address (not a unified Receive request), optional display
  name, 1.8-second copy feedback, missing-address Profile action, camera permission
  alert, close/background reset, and scan/paste into the existing Send importer.
  The existing Receive QR renderer and CameraX/ZXing scanner are reused.

## Fixtures and checks

- `apps/fixtures/platform-gates.json`: 64 RN push-before-battery combinations.
- `apps/fixtures/home-status.json`: 43 backup/round cases, 24 facts and 12 symbols;
  regenerate from RN source with `node apps/fixtures/tools/generate-home-status.mjs`.
- Existing `fiat-currency.json`: all 48 shared money-formatting cases.
- `PlatformGateRulesTest`: safety precedence, gate truth table, onboarding sequence.
- `HomeRulesTest`: backup visibility/retry, rounds, facts, hidden fiat, rounding,
  loading, boarding expiry, and store links.
- `ParityUIDeviceTest`: opt-in public Compose UI checks; the rendered QR is decoded
  from a screenshot. It also supports a private wallet setup without showing or
  exporting a phrase, and a live Receive-request → QR paste → Send route without
  submitting a payment.

The requested full command:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

Final results: **2,860 JUnit tests / 79 suites**, zero failures/errors/skips;
regtest debug APK built. The final parity instrumentation run has eight passes
and one opt-in setup skip; setup passes separately, and the two connector checks
pass in their fresh process: **11 distinct executed device checks, zero failures**.
Sixteen PNG screenshots were captured. The owned AVD's
identity was confirmed before shutdown; absence/closed console port was confirmed
before deletion, and both AVD files were confirmed removed afterwards.

## Device verification and limits

Only the owned `NoahAndroidParity5596` AVD on `emulator-5596` is used, launched
explicitly with `emulator -port 5596`. Regtest addresses use `10.0.2.2`, with the
existing server on port 3000, Ark on 3535, and Bitcoin RPC on 18443. No Docker
commands or other devices were used.

Only Google APIs system images were installed (API 28/35); there was no non-Google
image available. The non-GMS branch was verified with Play Services disabled on
this AVD. The test APK's inert distributor verifies discovery, selection,
registration initiation and timeout/retry; actual ntfy endpoint delivery is not
claimed. The preserved connector/endpoint migration tests pass in a separate
instrumentation process. Running them after the UI initialized the connector in
the same process fails because the connector caches its database; no production
migration code was changed to accommodate test ordering.

The battery screen and Android per-app exemption dialog were exercised live;
accepting the exemption returned to Home. One cold launch showed an ANR while
Gradle was active; its main thread was runnable in Compose layout, with substantial
CPU scheduling delay. The subsequent live and UI checks passed. The screenshot is
retained as `home-cold-start-anr.png`, rather than hiding this observation.

Notification-gate copy/actions use a debug-only preview because RN deliberately
skips notification gating on emulators, and the physical Pixel was off limits.
Update-required, completed boarding, and pending-round UI use explicit public
fixtures; no funded boarding/round or real physical push-delivery claim is made.
Visual design remains the existing native styling.

PNG evidence included live Home/privacy,
battery launch/system dialog, QR code/missing/scan/Send routing, notification
requirement, status banners, pending-round details, and UnifiedPush selection/retry.

## Round 1 review fixes (base `17e7b76f`)

Six commits address the Android Home/launch/gate/QR/version findings:

- Round progression observes background activity plus a completion revision. Busy
  work pauses queries; completion immediately retries even if a job starts and
  ends between polls, the first query failed, or the previous result was empty.
  Known active rounds keep their 30-second timer across transient errors and
  refresh/foreground; inactive Home has no round timer.
- Home checks the version independently of registration, with its own mutex and
  observable result. Failure remains cached and warning-only. A held version
  response cannot block registration or Emergency Email.
- Backup snapshots publish after persistence (and reset after wallet deletion).
  Boarding already publishes through the existing Compose completion state.
  Home observes both instead of decoding backup state every second. Success
  banners use one-shot five-second deadlines and refresh their clock on foreground,
  matching the iOS expiry semantics; historical outcomes do not replay.
- The hub QR exposes one accessible button, “Copy Lightning address”. The device
  check decodes the QR, verifies button semantics and copies the literal address.
- Activity startup awaits model/services construction on IO before attaching UI.
  Ordered recovery, seed/launch decisions, wallet opening, status queries and
  auth-token calls run on IO. Recovery eligibility is a snapshot, with existing
  action-time safety validation retained. Permission history uses `apply()` on IO;
  battery persistence/sync and UnifiedPush discovery/selection/register are awaited
  on IO before their results advance the UI. Biometric invalidation during startup
  is retained. Debug launches enable thread disk-read/write/network StrictMode
  with `penaltyLog()`; checks are never suppressed.
- Visible fiat waits for both balance and price. A loaded zero balance still
  renders zero; hidden fiat retains the persisted currency symbol without either.

Verification:

- The exact full command above ran once: **2,880 tests / 82 suites**, zero
  failures/errors/skips; regtest debug APK built. A final review then added a
  regression for a failed first query after refresh/foreground with known active
  rounds and moved Home query preflights to IO. The final focused command covered
  Home, backup coordinator, all server tests, launch and gate rules: **71 tests /
  10 suites**, zero failures/errors/skips, and rebuilt the APK. The full suite was
  not repeated after that narrow follow-up.
- **Four distinct device checks passed** across focused runs: interrupted recovery
  ahead of gates/Home, notification actions + battery Skip, QR accessibility/copy,
  and non-GMS distributor selection + timeout/retry. Private wallet preparation
  also passed (no phrase displayed and no payment submitted); initialized launch,
  battery Skip and Home were checked live. The snapshot helper's UiAutomation
  connection initially conflicted with instrumentation; stopping that helper on the
  owned AVD resolved the harness conflict. QR semantics were corrected against
  the device check before its passing run.
- Only `NoahAndroidFixR1_5598`, explicitly launched on port **5598**, was used.
  Non-GMS was exercised by disabling Play Services on that AVD. Its identity was
  confirmed with `adb -s emulator-5598 emu avd name`, then it was shut down; the
  missing device and closed console port were confirmed before deleting the AVD.
  Both its `.avd` directory and `.ini` were confirmed absent. Other emulators,
  the physical device, simulators and Docker were not operated.

StrictMode findings and remaining scope:

- The first fresh cold launch logged **32 disk-read violations** from service
  construction (scratch sweep, wallet/log/seed-store setup, HTTP client setup)
  and restore-journal checks. After moving those calls, fresh launch and the
  focused recovery/battery/UnifiedPush paths logged **zero violations**.
- An initialized-wallet sample initially logged 55 violations. Moving the shared
  wallet-open and token entrypoints to IO removed the direct seed/auth work.
  A subsequent initialized-wallet sample still logged **37 disk violations**
  through the existing Send observer and recurring executor: the Send reservation
  suspension check constructs SettingsStorage (`SendRuntime.kt:25` →
  `WalletSession.kt:474`); recurring readiness/binding reads wallet files and
  inspects/copies the database (`RecurringRuntime.kt:28`, `RecurringStore.kt:89`);
  recurring refresh writes widget preferences (`RecurringRuntime.kt:41` →
  `WidgetDataWriter.kt:35`). These require changes in the explicitly excluded
  Send/Recurring execution internals, so they remain open. This is **not** a clean
  initialized-launch StrictMode claim or a claim that the earlier ANR is resolved.
- Actual distributor endpoint delivery, physical notification gating, funded
  rounds/boarding and ANR performance profiling were not repeated. No iOS,
  Send/Recurring/Onchain internals, scan-lock behavior or EAN-13 support changed.

## Initialized-wallet main-thread I/O (base `37ab2125`)

Completed on **NoahMainThreadIO5590**, explicitly launched with `-port 5590`
(`emulator-5590`, Pixel 6 AVD, API 35 ARM64). The same initialized regtest wallet
was used before/after: funded once with **100,000 onchain sats**, then a **30,000-sat
boarding request** through the production session. `just bcli` mined one funding
block and the required three boarding blocks. The device harness verified at least
20,000 spendable Ark sats. No Send or recurring payment was submitted on the live
wallet. No other emulator, physical device, simulator or Docker lifecycle/config
was operated; stack access used only the explicitly authorized `just bcli` wrapper.

StrictMode **disk reads / disk writes / network**, before → after:

- Cold initialized launch: **41 / 7 / 0 → 0 / 0 / 0** (48 → 0 total).
- Foreground from the Recurring screen, including a 60-second timer tick:
  **66 / 6 / 0 → 0 / 0 / 0** (72 → 0 total).
- Opening Home from Send: **0 / 0 / 0 → 0 / 0 / 0**.
- Opening Send from Home: **2 / 0 / 0 → 0 / 0 / 0**.
- Opening Recurring from Settings: **30 / 3 / 0 → 0 / 0 / 0** (33 → 0 total).

Each sample clears the owned device's logcat, performs the named action, waits
15 seconds (75 for foreground), verifies the app PID is alive, then dumps logcat.
Only `StrictMode` lines whose PID and TID both equal that app PID are retained;
counts use `StrictMode policy violation` headers, never stack-line counts. Debug
thread disk-read/write/network detection and `penaltyLog()` remain enabled;
no policy exemptions or suppression were added. Baseline PID **5341**, changed
APK PID **6098**. Counts vary with StrictMode's stack deduplication and workload;
the earlier 37-violation sample above is historical, not this funded baseline.

Per-flow logs and numeric results,
complete IO call-site inventory with file and line.
Empty after-log files contain one newline; the results retain their live PID and
sampling window. The installed APK SHA-256 matched the assembled APK:
`f247dce337d81c315b0174308546fa8bf69c7ef6cb80135b6a69b97ea0ae2f7e`.

Safety and threading:

- Send: `WalletSession.kt:322` moves the suspension check and counted acquisition
  into the existing non-cancellable IO interval, retaining the monitor, send mutex
  and drain ordering. `SendController.kt:32` keeps journal reload blocked until its
  entire reservation returns, then publishes only for the current generation.
  `SendController.kt:192` awaits quarantine/audit; `:207` awaits journal removal.
  The synchronous submission lock and journal-before-payment sequence are unchanged.
  Pending read, stale-generation result, pending removal and pending failed audit
  tests retain locks and original evidence; no payment can start on unloaded state.
- Recurring: `RecurringExecutor.kt:29` awaits storage on IO, without a readiness or
  journal cache. Generation (`:72`), wallet/database binding (`:41`), complete
  journal recovery (`:50`) and all policy/mutation/sync reads and writes are awaited.
  Original store/journal locks remain inside IO blocks. The final synchronous guard
  still rereads current generation, identity, policy, quarantine/consumption and
  budget inside the session reservation before the SDK payment. Pending/failed
  read tests cover generation, binding, policy and journal; delayed occurrence,
  in-flight and consumption writes prove payment/acknowledgement order. Existing
  stop/replacement, rollback, quarantine, pause/cancel and fee tests still pass.
- Widgets: `WidgetDataWriter.kt:24` and `:35` await preferences clear/write on IO;
  commit still precedes broadcast. `NoahWidgetProvider.kt:57` retains the broadcast
  with `goAsync()` through serialized IO rendering, including the preference read
  at `:72`. Values, formatting, RemoteViews and click actions are unchanged.
  The device storage test holds both write/clear reads pending, proves Main remains
  responsive and no broadcast occurs early, and checks committed values.
- Foreground: `RecurringForegroundRunner.kt:23` awaits the initialized-wallet
  readiness read on IO; a stopped/backgrounded tick cannot execute afterward.
  Launch/seed/recovery precedence and seed internals are unchanged. Background
  seed-opening behavior retains its existing read-only eligibility decisions.

The exact required full command ran **once after all production/test changes**:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**2,891 tests / 84 suites passed**, zero failures/errors/skips; APK assembly passed
in the same **56-second** Gradle run. Full log,
JUnit totals. Targeted iteration:
596 Send/session cases; 2,050 payment cases; 2,053 payment/widget-value cases;
then all 15 pending-load/write/foreground cases, each with zero failures/errors/skips.
All Gradle invocations used `--max-workers=2`; Android-test APK assembly also passed.

Device checks: private seed-free setup **1 pass**, funded/boarded wallet verification
**1 pass**, and held widget IO + provider-key compatibility **2 passes / zero
failures/skips** (widget log). The first
funding harness timed out waiting for confirmation before any boarding marker or
outgoing payment existed. Funding was inspected, confirmed once, and setup resumed
on the same wallet; no funding transaction or board was repeated.

No live Send/recurring payment, induced power loss, ANR performance profiling,
physical phone or launcher-hosted widget layout test is claimed. Payment safety
faults and pending IO use the deterministic tests; widget persisted values are
checked on device. Nothing remains open in the requested main-thread-IO scope.

Cleanup: immediately before shutdown, `adb -s emulator-5590 emu avd name` returned
**NoahMainThreadIO5590**. The Device panel shut down that AVD; its serial was absent
and both ports **5590/5591** were closed before `avdmanager delete avd`. Its `.avd`
directory and `.ini` were then confirmed absent. Cleanup checks.

## Launch-threading review regressions (base `1d9aff5d`)

Four fixes address the review of `1d3cacb0`, `85c80d98`, `9d009d9e` and
`49d2e614`; MMKV opening/recovery is intentionally outside this change.

- Recurring settlement captures A's exact attempt/outcome/error before the first
  suspension, validates binding inside mutation, durably consumes A, preserves
  changed revisions and safety latches, and acknowledges only A. A later failed
  payment B cannot make A retry or have its own result dismissed. Ambiguous
  settlement also validates binding/revision before changing policy.
- Send clear, quarantine and audit retain a counted session reservation through
  completion. Generation and wallet identity are validated before mutation,
  including after suspending identity lookup. Journal reload/migration, inspection,
  pending/stopped markers, finish/reconcile and receipt-refresh writes were audited;
  stale identity/preparation/payment/refresh results cannot publish/write into a
  new generation. Lifecycle replacement must drain the old mutation first.
- `isOpen` reads volatile session state without entering the disk-owning monitor.
  Close, observer subscription/cleanup, direct create and restore reservation
  acquisition run on IO. Existing creator/lifecycle/recovery callers already use
  IO. Suspension validation remains authoritative at action time.
- Widgets derive all six displayed balance/expiry fields from one `prefs.all`
  snapshot. Commit/clear still await IO before broadcast; defaults and formatting
  are unchanged.

The reviewer's three temporary probes were turned into permanent desired-behavior
regressions. Each was run against the unfixed code and **failed**, then **passed**
after its fix: `RecurringSettlementTest` (paid A versus failed B),
`SendJournalOwnershipTest` (replacement overtaking delayed clear), and
`WalletSessionUiReadTest` (UI blocked behind suspension storage). Two additional
`WidgetSnapshotTest` cases failed with the old six-getter reader and pass with
concurrent commit/clear using the snapshot reader. The permanent suites also cover
settlement policy/wallet/latch changes, clear/override delayed at reservation and
identity boundaries, and stale preparation. Red logs and verification summary.

The final targeted run passed **2,106 tests / 38 suites**, zero failures/errors/
skips, and assembled both APKs. All Gradle invocations used `--max-workers=2`.
Two earlier overlapping Gradle invocations collided in the shared result directory;
the sequential rerun passed. This was a harness error, not a passing test run.

Live remeasurement used only **NoahThreadingRegression5598**, API 35 ARM64, launched
explicitly with **`-port 5598`**. The existing private setup test passed (**1 test**)
and created an initialized **unfunded** regtest wallet without displaying/exporting
a seed. The existing widget held-IO device test also passed (**1 test**); it checks
main-thread responsiveness and commit-before-broadcast for write and clear.
The installed APK matched the assembled APK's SHA-256 in the verification summary.

StrictMode disk reads / disk writes / network were **0 / 0 / 0 in every sample**:

- Cold initialized launch to Home, 15 seconds.
- Send from Home, 15 seconds.
- Home from Send, 15 seconds.
- Recurring from Settings, 15 seconds.
- Foreground from Recurring, 75 seconds, covering the 60-second timer tick.
- The first initialized launch to the one-time battery gate also measured zero.

Each sample clears only the owned device's logcat, performs the action, verifies
the app remains alive, and counts `StrictMode policy violation` headers only where
PID and TID both equal the live app PID. Debug disk-read/write/network detection
and `penaltyLog()` remain enabled with no exemptions. Per-flow counts and windows.
Zero-violation logs contain one newline. The first agent-device open hit a stale
session for a previously deleted AVD on 5598; after verifying the current AVD's
identity, only that stale automation session was released and measurement resumed.

Cleanup verified **NoahThreadingRegression5598** immediately before Device-panel
shutdown, then confirmed the serial absent, QEMU process exited and ports
**5598/5599 closed before deletion**. Both its `.avd` directory and `.ini` were
confirmed absent afterwards. Cleanup receipt.
No other device/emulator/simulator or Docker was operated. No iOS, server, client,
or MMKV opening/recovery code changed. No funded payment, induced process crash,
power loss or launcher-hosted widget rendering is claimed; money-path races and
snapshot consistency use the deterministic permanent tests.

The exact required final command ran **once**, after production/test changes and
owned-device verification/cleanup:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**2,899 tests / 88 suites passed**, zero failures/errors/skips; regtest APK assembly
passed in the same **1m 8s** run. Full log,
per-suite counts.
All four reported findings are fixed. Funded settlement and crash safety are
covered deterministically here; this run does not repeat the historical funded
StrictMode scenario or induce real power loss.
