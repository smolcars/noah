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
