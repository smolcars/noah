# Native Maestro tests

These flows target **only** `com.noahwallet.regtest`, installed from `apps/android` or `apps/ios`. They clear the wallet and iOS keychain. Use dedicated, disposable emulators/simulators, never a personal wallet or physical device. The RN tests and workflows under `client/` remain independent.

## Coverage

| Entry point | Checks |
| --- | --- |
| `wallet-onboarding-back.yml` | Beta back/decline, wallet creation, email → recovery back twice, finish without email, persisted wallet after restart |
| `receive.yml` | Ark/onchain details; Lightning appears after adding 500 sats and a note; edit/remove amount |
| `delete-wallet.yml` | Create a disposable wallet, type the delete confirmation, return to Welcome |
| `email-verification.yml` | Regtest code `000000`, unique Lightning username, saved email/identity and verified state in Profile |
| `manual-boarding-settings.yml` | Open manual boarding through Settings, read confirmed balance, enter a draft without submitting |
| `subflows/prepare-funded-send.yml` | Create a throwaway wallet and copy its Ark address for the host harness |
| `subflows/send-funded-ark.yml` | Send 5,000 sats to Bark and assert successful payment |
| `subflows/send-recipient-reset-on-back.yml` | Cancel review, start over, enter a new amount, ensure abandoned recipient is absent |
| `subflows/send-max-back-fixed-request.yml` | Enter MAX, cancel with Start over, paste a fixed BIP-321 request, review without submitting |
| `subflows/send-amountless-request.yml` | A zero-amount BIP-321 request opens the amount composer and can be reset |
| `subflows/exit-deposit-landscape.yml` | Reach/copy an exit deposit address in landscape without starting an exit |
| `adaptive/adaptive-layout-smoke.yml` | Fresh wallet, then all three adaptive checks below |
| `adaptive/adaptive-layout-checks.yml` | Send amount, receive amount/note and History survive portrait/landscape changes |
| `adaptive/adaptive-settings-checks.yml` | Currency/unit preferences, unsaved profile, backup, exit, VTXOs, boarding and recurring drafts remain reachable |
| `adaptive/adaptive-rotation-checks.yml` | Three rotated profile drafts return safely through Settings |
| `offline/welcome.yml` | Welcome, beta back/decline and restore inputs without creating a wallet or contacting the stack |

`config.yaml` discovers only the five independent top-level flows. Subflows depend on their caller; the runner invokes funded and adaptive flows explicitly. One shared flow set runs on both platforms. Branches cover actual native differences: Android battery optimization, keyboard dismissal, the iPadOS 27 numeric popover, and platform back/sheet controls.

Native onboarding intentionally prevents backing past the created recovery phrase. Native Receive uses text fields rather than the RN keypad/note sheet. Native Send uses Start over when abandoning a recipient/MAX draft. Adaptive form navigation scrolls within the primary column, since the wide iOS layout has independent columns and Android centers its form. Tests preserve these behaviors. RN-only upgrade/Expo-launch flows remain in `client/`; native RN→native upgrade tests have their own platform runbooks. Native receipts currently have no RN-style copy-destination button, so that RN subflow is not applicable. Exit deposit details are opened after rotating: Android currently dismisses that sheet on activity rotation; retaining it across rotation needs a separate behavior change.

## Local prerequisites

- Maestro 2.6.0 (tested locally at `~/.maestro/bin/maestro`), Java 17, Android SDK/ADB or Xcode and XcodeGen. CI uses the repository's pinned Nix `android-ci` shell, including Maestro.
- An already initialized Docker regtest stack reachable on localhost. Check it with `docker compose -f scripts/docker-compose.yml ps` and `nix develop --command just bark balance`.
- The runner **never starts, stops, recreates or mines the stack**. It uses `just bark send --wait ADDRESS '20000 sats'` once per funded run, generates Bark/Bitcoin destinations, and sends 5,000 sats back from the app. Keep sufficient Bark funds available. Do not rerun a funding step after an ambiguous failure without checking whether the transfer completed.
- Run one heavy native build at a time on a shared machine. Pass the explicit device ID to every command. Do not use other people's devices.

The script takes `PLATFORM ARTIFACT DEVICE SUITE`, where suite is `all` (five core flows plus funded setup, four send checks and exit deposit copy), `core`, `funded`, `adaptive`, or `offline`. It installs the artifact and verifies that the device is an emulator or a booted simulator. Set `MAESTRO_COMMAND` to select the local binary and `MAESTRO_DEBUG_OUTPUT` to an absolute output directory; otherwise artifacts go under the system temporary directory. Each invocation writes JUnit XML plus Maestro diagnostics. The runner does not clean up your device or build output.

### Android

Create your own AVD, using an unused even console port at least 5592. Never select an existing emulator implicitly. The default native regtest host is `10.0.2.2`; do not use a physical-device host override for this build.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --name NoahMaestroLocal --package 'system-images;android-35;google_apis;arm64-v8a' --device pixel_7
"$ANDROID_HOME/emulator/emulator" -avd NoahMaestroLocal -port 5592 \
  -no-snapshot -grpc 8592 -grpc-use-token

nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:assembleRegtestDebug'
MAESTRO_COMMAND="$HOME/.maestro/bin/maestro" MAESTRO_EMULATOR_GRPC_PORT=8592 \
  nix develop --command scripts/test-maestro-native.sh android \
  apps/android/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk emulator-5592 all
```

The funded tests use the emulator's authenticated gRPC clipboard API (`grpcurl`, `jq`, and the emulator's bundled `emulator_controller.proto`). Pass the gRPC port belonging to this emulator. No fixed shared clipboard/emulator port is assumed.

For adaptive coverage, create a separate `pixel_tablet` or `pixel_fold` AVD and run the same artifact with `adaptive`. Test the foldable's closed and open postures; the same flow set applies at each size. Screenshots are saved at the send, receive, history and settings drafts. Maestro rotation checks complement the existing Compose `AdaptiveLayoutDeviceTest` and `AdaptiveStateRestorationTest`, which CI runs after Maestro.

### iOS

Create a dedicated iOS 27.0 simulator, then build the regtest simulator app:

```sh
simulator_id=$(xcrun simctl create 'Noah Maestro Local' \
  com.apple.CoreSimulator.SimDeviceType.iPhone-17 com.apple.CoreSimulator.SimRuntime.iOS-27-0)
xcrun simctl boot "$simulator_id"
xcrun simctl bootstatus "$simulator_id" -b
(cd apps/ios && xcodegen generate && xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath /private/tmp/noah-maestro-dd \
  -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=YES build)
MAESTRO_COMMAND="$HOME/.maestro/bin/maestro" nix develop --command \
  scripts/test-maestro-native.sh ios \
  /private/tmp/noah-maestro-dd/Build/Products/Debug-Regtest-iphonesimulator/Noah.app "$simulator_id" all
```

Use `adaptive` on your own iPad simulator or a supported iPhone Duo runtime. The harness grants simulator pasteboard permission on iOS 27+ for funded flows. Like the RN harness, it writes public payment requests to the host Mac's clipboard too, so automatic pasteboard sync cannot replace the simulator's request. Give Maestro permission to control the simulator if requested by macOS.

Before deleting any device, verify its ID/name belongs to this run, shut it down, and confirm it is shut down. Then delete that simulator/AVD, your DerivedData, and your result bundles. Leave other devices and the shared Docker stack alone.

## CI

- `native-android.yml` uploads its native regtest debug APK, then calls reusable `native-maestro-android.yml`. PRs affecting `apps/**` (including Android, shared fixtures and these flows), the runner script or these workflows trigger it. It uses the existing Blacksmith Linux runner, KVM emulator and isolated Docker regtest stack. Core and funded flows run on a phone; existing adaptive Compose instrumentation runs on the emulator too.
- `native-maestro-ios.yml` runs at **06:17 UTC nightly**, on `workflow_dispatch`, or for a PR bearing **`ios-e2e`** (label, synchronize and reopen events). It creates dedicated iPhone 17 and iPhone Duo simulators using a compatible installed runtime and builds the native app the same way as `native-ios.yml`.
- macOS runners commonly have no Docker daemon. The iOS job probes Docker and starts an isolated CI stack only when available. Without it, **only the offline welcome flow runs**, with that limitation written to the job summary. It does not claim wallet, funded or adaptive success. Full iOS coverage requires a macOS runner with Docker support and reachable forwarded regtest ports, or local runs against an existing stack. A missing Duo runtime fails explicitly instead of silently substituting a phone.
- The nightly/label workflow also builds an Android APK and runs shared adaptive flows on `pixel_tablet` and `pixel_fold` Linux emulators, with Docker available. Fold posture changes beyond the profile's default posture require the explicit local posture runs above.
- JUnit reports, screenshots, emulator logs and failure diagnostics are uploaded as artifacts. CI stack lifecycle commands are confined to workflow steps on isolated CI runners. None of the RN workflows are changed.
- Schedules run from the default branch. Pushing `feat/native-rewrite` does not trigger these workflows; use local verification until the workflow is merged or explicitly dispatched from a branch where GitHub permits it.

Validation:

```sh
nix shell nixpkgs#actionlint nixpkgs#shellcheck --command sh -c \
  'actionlint .github/workflows/native-android.yml .github/workflows/native-maestro-android.yml .github/workflows/native-maestro-ios.yml && shellcheck scripts/test-maestro-native.sh'
```

`.github/actionlint.yaml` declares the repository's custom Blacksmith runner labels.

## Test ID locations

IDs match across platforms. iOS already exposed the receive, onboarding input, keypad, recipient, continue, confirm, profile and recurring IDs; those are reused. Android now exposes their equivalents. Activity and modal windows each need ID export at their own accessibility root.

| Source (line at implementation) | Added/exposed IDs |
| --- | --- |
| [apps/android/app/src/main/java/com/noahwallet/MainActivity.kt:114](../android/app/src/main/java/com/noahwallet/MainActivity.kt#L114) | Activity root exports tags |
| [apps/android/app/src/main/java/com/noahwallet/ui/WalletPrivacy.kt:43](../android/app/src/main/java/com/noahwallet/ui/WalletPrivacy.kt#L43) | Shared alert/sheet roots export tags |
| [apps/android/app/src/main/java/com/noahwallet/ui/NoahTheme.kt:50](../android/app/src/main/java/com/noahwallet/ui/NoahTheme.kt#L50) | Modifier support for button IDs |
| [apps/android/app/src/main/java/com/noahwallet/ui/HomeScreen.kt:74](../android/app/src/main/java/com/noahwallet/ui/HomeScreen.kt#L74) | `home-settings-button` |
| [apps/android/app/src/main/java/com/noahwallet/ui/Onboarding.kt:193](../android/app/src/main/java/com/noahwallet/ui/Onboarding.kt#L193) | `verification-code-input`, `email-address-input`, `lightning-username-input` |
| [apps/android/app/src/main/java/com/noahwallet/ui/ReceiveScreen.kt:151](../android/app/src/main/java/com/noahwallet/ui/ReceiveScreen.kt#L151) | `receive-qr-button`, `receive-copy-*`, `receive-amount-button/input/submit/remove`, `receive-description-input` |
| [apps/android/app/src/main/java/com/noahwallet/ui/SendScreen.kt:128](../android/app/src/main/java/com/noahwallet/ui/SendScreen.kt#L128) | `send-key-*`, `send-amount`, `send-recipient`, `send-continue`, `send-paste`, `send-max`, `send-start-over`, `send-currency-toggle`, `send-confirm`, `send-review-back` |
| [apps/android/app/src/main/java/com/noahwallet/ui/settings/SettingsScreen.kt:123](../android/app/src/main/java/com/noahwallet/ui/settings/SettingsScreen.kt#L123) | `settings-profile/currency/bitcoinUnit/vtxos/emergencyExit/recurring-payments/backup/board-ark`, `delete-wallet-input/confirm` |
| [apps/android/app/src/main/java/com/noahwallet/ui/settings/AccountScreens.kt:53](../android/app/src/main/java/com/noahwallet/ui/settings/AccountScreens.kt#L53) | `profile-name-input`, `preference-*` |
| [apps/android/app/src/main/java/com/noahwallet/onchain/BoardArkScreen.kt:27](../android/app/src/main/java/com/noahwallet/onchain/BoardArkScreen.kt#L27) | `board-amount`, `board-review` |
| [apps/android/app/src/main/java/com/noahwallet/recurring/RecurringPaymentEditorScreen.kt:65](../android/app/src/main/java/com/noahwallet/recurring/RecurringPaymentEditorScreen.kt#L65) | `recurring-label-input`, `recurring-recipient-input`, `recurring-amount-input`, `recurring-save-button` |
| [apps/android/app/src/main/java/com/noahwallet/recurring/RecurringPaymentsScreen.kt:45](../android/app/src/main/java/com/noahwallet/recurring/RecurringPaymentsScreen.kt#L45) | `recurring-new-button` |
| [apps/ios/Noah/Features/Send/SendView.swift:141](../ios/Noah/Features/Send/SendView.swift#L141) | `send-start-over`, `send-currency-toggle`, `send-max`, `send-paste`, `send-review-back` |
| [apps/ios/Noah/Features/Settings/SettingsView.swift:28](../ios/Noah/Features/Settings/SettingsView.swift#L28) | Matching settings rows and `delete-wallet-input/confirm` |
| [apps/ios/Noah/Features/Onchain/BoardArkView.swift:31](../ios/Noah/Features/Onchain/BoardArkView.swift#L31) | `board-review` |

## Local Maestro verification (2026-10-21)

Maestro 2.6.0 passed these entry points on dedicated devices. Counts combine completed runs after selector fixes; the core/funded suites were resumed during debugging rather than passing in one uninterrupted `all` invocation. Helper flows are included through their callers, not counted again.

| Suite | Android | iOS |
| --- | --- | --- |
| Five core wallet flows | 5/5 | 5/5 |
| Funded preparation, four send regressions and exit deposit copy | 6/6 | 6/6 |
| Offline welcome | 1/1 | 1/1 |
| Adaptive wrapper (all three adaptive component flows) | 2/2: tablet and foldable | 1/1: iPad |
| Total entry-point/device checks | **14/14** | **13/13** |

Android used dedicated API 35 Google APIs ARM64 AVDs on ports 5592 (phone), 5594 (tablet), and 5596 (Pixel Fold, default open posture). iOS used dedicated iPhone 17 and iPad Pro simulators on iOS 27.0. Both platforms received 20,000 regtest sats from Bark and successfully sent 5,000 sats back over Ark. Both copied exit addresses were validated by Bitcoin Core. No funded flow was skipped. Fold posture transitions and actual CI execution remain unverified locally.

The shared Android stack was left running; no local stack lifecycle commands were used. All owned devices were identified, shut down and deleted after testing. Owned DerivedData, result bundles and test/build processes were cleaned up; JUnit reports, summaries and logs remain under `/private/tmp/noah-maestro-evidence`.

## Native unit-suite verification (2026-10-21)

The requested Android command completed unit testing, APK assembly and instrumentation compilation: **3,634 passed / 1 failed / 3,635 total**. The existing `AppLogTest.redactionPerformance` scaling assertion failed during the full shared-machine run (2.68 ms → 391 ms). Its isolated rerun passed; no app/logging behavior was changed.

The full iOS `NoahTests` suite ran once after the last native source change with `xcodegen generate`, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, two build jobs, and DerivedData under `/private/tmp/noah-maestro-dd`: **810 passed / 27 skipped / 1 failed / 838 total**. The run used the dedicated iOS 27.0 iPad. The failure was `DeviceInfoTests.collectsAppOSAndHardware`, which expects `iOS` while an iPad reports `iPadOS`. All **7 DeviceInfoTests passed** in an isolated iPhone run using `test-without-building`. The 27 skipped tests require their opt-in live-test environments.
