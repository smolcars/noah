# Android Home, launch gates and QR parity

## Coverage

JVM tests (run with the unit-test task):

- `PlatformGateRulesTest`: seed/recovery precedence over platform gates, the
  notification/battery gate truth table and the onboarding sequence, from
  `apps/fixtures/platform-gates.json`.
- `HomeRulesTest`: backup visibility/retry, rounds, facts, hidden fiat, rounding,
  loading, boarding expiry and store links, from `apps/fixtures/home-status.json`.
- Launch-threading regressions: `RecurringSettlementTest`, `SendJournalOwnershipTest`,
  `WalletSessionUiReadTest` and `WidgetSnapshotTest`.

Instrumentation tests (disposable emulator only; `ParityUIDeviceTest` and
`InitializedWalletIOTest` are opt-in):

- `ParityUIDeviceTest`: public Compose UI checks for launch recovery, notification
  actions and battery Skip, QR decoding/copy, the missing-address Profile route,
  scanner paste/close, backup/update copy and pending-round details.
- `PushCompatibilityTest`: UnifiedPush connector/endpoint migration.
- `InitializedWalletIOTest`: funds and boards an initialized wallet once, for StrictMode
  sampling below.
- `WidgetIOTest`: widget writes and clears stay off the main thread and commit before
  broadcast.

## Running

JVM tests and APK, from the repository root:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

Regenerate `home-status.json` from RN source with
`node apps/fixtures/tools/generate-home-status.mjs`.

For device checks, use only a disposable emulator you own, launched on an explicit
port. Build both APKs (`:app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest`),
install them with `adb -s SERIAL install -r`, then run:

```sh
adb -s SERIAL shell am instrument -w -e noahParityUI disposable-emulator \
  -e class com.noahwallet.ui.ParityUIDeviceTest \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

Each `ParityUIDeviceTest` case also requires `noahParityUI=disposable-emulator`.
Extra opt-ins select the remaining cases:

| Argument | Case |
| --- | --- |
| `-e noahParityCreateWallet 1` | `prepareLiveWalletWithoutDisplayingSeed`: creates an initialized regtest wallet without showing a phrase |
| `-e noahParityLive 1` | `liveQrHubPastesReceiveRequestIntoSend`: Receive request → QR paste → Send, no payment |
| `-e noahParityNonGMS 1` | `nonGmsDistributorSelectionAndTimeoutCanRetry`: disable Play Services on the AVD first |
| `-e noahAutoBoardSetup 1` | `enableAutoBoardAndExportPublicAddress` |

`InitializedWalletIOTest` needs `-e noahInitializedIO disposable-emulator` and funds or
boards the owned wallet exactly once. Run `PushCompatibilityTest` in its own
instrumentation process (`-e class com.noahwallet.services.PushCompatibilityTest`):
after the UI has initialized the connector in the same process, the connector's cached
database makes it fail.

The app reaches the regtest stack at `10.0.2.2` (server 3000, Ark 3535, Bitcoin RPC
18443). Emulators skip the notification gate as RN does, so its copy and actions use
the debug-only `ParityPreviewActivity`.

## Reading results

Screenshots from `ParityUIDeviceTest` are written to the app's external files
directory, `files/parity/<name>.png`. Cases without their opt-in arguments report as
skipped.

To sample main-thread I/O, use a debug build (StrictMode disk read/write/network
detection with `penaltyLog()` is enabled): clear the owned device's logcat, perform
one action (cold launch, Send from Home, Home from Send, Recurring from Settings, or
foreground from Recurring for 75 seconds to cover the 60-second timer), confirm the
app PID is still alive, then dump logcat. Count only `StrictMode policy violation`
headers whose PID and TID both equal the app PID; the expected count is zero.

Real distributor endpoint delivery, physical notification gating and funded rounds
are not covered by these checks.
