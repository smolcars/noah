# Native Maestro tests (Android)

These flows target **only** `com.noahwallet.regtest`, installed from `apps/android`, on one phone profile. They clear the app's data. Use a dedicated, disposable emulator, never a personal wallet or a physical device. The RN flows under `client/` are separate. iOS has no Maestro flows here; its onboarding checks live in `apps/ios/OnboardingTests`.

## Flows

Every PR that touches the native Android paths (see `.github/workflows/native-android.yml`) runs both groups in one CI job, on one emulator and regtest stack: smoke first, then funded. The tag decides the group. CI installs the R8-minified regtest release, debug-signed (`CI_DEBUG_SIGNING=true ./gradlew :app:assembleRegtestRelease`), so a missing keep rule fails the PR instead of crashing release builds. Each group creates **one wallet** and reuses it, so its flows run in order and can't be split across shards.

| Flow | Tag | Covers | Time |
| --- | --- | --- | --- |
| `smoke/onboard-and-receive.yml` | `smoke` | Create → Beta → skip Name and Email → Notifications → Android's battery step → Done → Home; Receive, Ways to pay you, copy the Ark address | 37 s |
| `smoke/amountless-request.yml` | `smoke` | A `bitcoin:` link without an amount opens Send on the keypad with the recipient set | 3 s |
| `smoke/delete-wallet.yml` | `smoke` | Settings → Delete wallet, typed confirmation, back to Welcome | 16 s |
| `smoke/restore-validation.yml` | `smoke` | Restore: twelve valid words with a bad checksum are rejected | 16 s |
| `subflows/fund-wallet.yml` (setup, run by the script) | — | Create the wallet as above; Receive → Add amount 10,000 → copy the payment request | 51 s |
| `funded/ark-send.yml` | `funded` | A `bitcoin:?ark=…&amount=` link opens the review at ₿5,000 via Ark; hold to send; success; the payment in Activity | 14–18 s |

Times are from the local runs below. A group's wall time adds the APK install and Maestro start-up: **smoke about 80 s, funded about 83 s**.

`config.yaml` orders the smoke chain (`flowsOrder`). Maestro refuses an order naming flows that a tag filter removes, so the funded group runs the `funded/` folder on its own. Subflows are never selected by tag.

The funded group is three steps in `scripts/test-maestro-native.sh`: `fund-wallet.yml`; the script reads the request from the emulator clipboard and pays its `lightning=` invoice from LND (`just lncli payinvoice`, no blocks mined); then `funded/` runs with the request's Ark address as `ARK`. The send goes to the wallet's own Ark address, which is valid on any stack. Fixed and amountless requests use BIP-173's regtest vector `bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080`, never paid.

## Speed

- **One wallet per group** and onboarding once; no `clearState` after the first flow.
- **Deep links** (`openLink: bitcoin:…`) start Send at the state a flow needs.
- **No sleeps.** Only element waits, at most 30 s (a Lightning or Ark round trip).
- **No animations.** The script sets the emulator's animation scales to 0, which also gives the app's reduced-motion path. It turns Wi-Fi off too: the emulator's Wi-Fi reconnected mid-send once locally, which left the payment's outcome uncertain; mobile data reaches the stack the same way.
- A tap costs about 2.5 s here (Maestro settles before and after it), so flows avoid taps rather than wait less. Capping `waitToSettleTimeoutMs` made the runs slower.

## Running locally

Prerequisites: the Android SDK and the repository's `android-ci` Nix shell, which pins Maestro 2.11.0 (the version CI downloads) and provides `grpcurl`, `jq` and `just` (`MAESTRO_COMMAND` picks another Maestro). CI doesn't use Nix; it installs the same tools directly. The funded group also needs a regtest stack whose LND has a channel to CLN. The script never starts, stops or mines the stack.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --name noah-maestro-phone --package 'system-images;android-35;google_apis;arm64-v8a' --device pixel_7
"$ANDROID_HOME/emulator/emulator" -avd noah-maestro-phone -port 5592 \
  -no-snapshot -no-window -grpc 8592 -grpc-use-token &
adb -s emulator-5592 emu avd name   # confirm it's yours

nix develop --command sh -c 'cd apps/android && ./gradlew :app:assembleRegtestDebug'
apk=apps/android/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk
nix develop .#android-ci --command scripts/test-maestro-native.sh android "$apk" emulator-5592 smoke
MAESTRO_EMULATOR_GRPC_PORT=8592 nix develop .#android-ci --command \
  scripts/test-maestro-native.sh android "$apk" emulator-5592 funded
```

The suite is `smoke`, `funded` or `all` (both, one after the other). Results (JUnit and Maestro debug output) go to `MAESTRO_DEBUG_OUTPUT`, or the system temporary directory.

For a second stack on other ports, build with its endpoints, as the `noah-bark08` stack needs (from the emulator the host is `10.0.2.2`): `-PnoahRegtestServerURL=http://10.0.2.2:13000 -PnoahRegtestArkURL=http://10.0.2.2:13535 -PnoahRegtestBitcoindURL=http://10.0.2.2:28443`, and point the script at that stack's LND with `MAESTRO_LNCLI="docker exec noah-bark08-lnd-1 lncli --network=regtest"`.

captaind pays Lightning receives from a pool of ten 10,000-sat VTXOs and refills it only once a block confirms its issue. A fresh CI stack funds one wallet per run. Many local funded runs without a block end in "vtxo pool is empty" in logcat and LND reporting the payment FAILED; mine one block.

When you're done, shut the emulator down (`adb -s emulator-5592 emu kill`), delete the AVD (`avdmanager delete avd --name noah-maestro-phone`) and your output. Leave other devices and the stack alone.

## Test IDs

Flows select by ID except the tab bar (Home, Receive, Send, Activity), which has none. The IDs match iOS wherever iOS has the same control. Added for these flows:

| Android source | IDs |
| --- | --- |
| `ui/onboarding/WelcomeStep.kt` | `onboarding-create`, `onboarding-restore`, `onboarding-beta-confirm` |
| `ui/onboarding/NameStep.kt` | `onboarding-name-skip`, `onboarding-name-continue`, `lightning-username-input` |
| `ui/onboarding/EmailStep.kt` | `onboarding-email-skip`, `onboarding-email-send`, `email-address-input`, `verification-code-input` (shared with Settings → Email) |
| `ui/onboarding/NotificationsStep.kt` | `notifications-enable`, or `notifications-open-settings` once denied (shared with the launch gate) |
| `ui/onboarding/DoneStep.kt` | `onboarding-open-wallet` |
| `ui/launch/PlatformScreens.kt` | `battery-skip` (Android only) |
| `ui/RecoveryWordField.kt` | `restore-word-1…24`: the cells are platform `EditText`s, so the ID is set on their accessibility node; Compose's `testTag` doesn't reach UI Automator |
| `ui/send/SendContent.kt` | `send-success` on a sent result's title, `send-title` otherwise |

## Local verification (2026-10-25)

Android API 35 Google APIs arm64 emulator (`pixel_7`, port 5592), regtest debug build against the `noah-bark08` stack, every group run through the script. Runs 1–2 used Maestro 2.6.0; runs 3–4 used 2.11.0 from `nix develop .#android-ci`, CI's version (run 3's smoke job includes installing its driver). Every run passed. The machine was shared with other heavy builds (load average 4–22), so times vary by a few seconds.

| Flow | Run 1 | Run 2 | Run 3 | Run 4 |
| --- | --- | --- | --- | --- |
| `onboard-and-receive` | 38 s | 37 s | 39 s | 38 s |
| `amountless-request` | 3 s | 3 s | 3 s | 3 s |
| `delete-wallet` | 16 s | 16 s | 17 s | 16 s |
| `restore-validation` | 15 s | 16 s | 16 s | 16 s |
| **smoke job (wall)** | **79 s** | **80 s** | **83 s** | **81 s** |
| `fund-wallet` (setup) | 51 s | 51 s | 53 s | 53 s |
| `funded/ark-send` | 18 s | 14 s | 13 s | 14 s |
| **funded job (wall)** | **84 s** | **82 s** | **80 s** | **82 s** |

The review reached by a deep link sometimes already had its quote (Hold to send) and sometimes showed Next first; `ark-send` handles both, and both happened in these runs. `subflows/send-ark-from-clipboard.yml`, used by the RN↔native BackupTests, passed once on Android; the BackupTests themselves weren't run.
