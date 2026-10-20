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

The requested full command is recorded in `evidence/gradle-final.log`:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

Final results: **2,860 JUnit tests / 79 suites**, zero failures/errors/skips;
regtest debug APK built. The final parity instrumentation run has eight passes
and one opt-in setup skip; setup passes separately, and the two connector checks
pass in their fresh process: **11 distinct executed device checks, zero failures**.
Sixteen PNG screenshots are indexed in `evidence/results.json`. The owned AVD's
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

See `evidence/results.json` for final counts and `evidence/device-cleanup.txt` for
AVD identity, shutdown and deletion. PNG evidence includes live Home/privacy,
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
