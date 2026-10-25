# Native Noah apps

The native apps in [ios](ios/README.md) and [android](android/README.md) replace
the React Native wallet in `client/` through an in-place update.

## Signet and mainnet test builds

**Native Signet and Mainnet use the same bundle/application IDs
(`com.noahwallet.signet`, `com.noahwallet.mainnet`) and signing identity as
RN. Installing one upgrades the tester's RN app in place, keeping its wallet
data. This is intentional for upgrade testing. A mainnet build upgrades a
real-funds wallet: keep its recovery phrase and a verified backup first.**

After changes to the respective platform, shared fixtures, or distribution
workflow land on `master`, GitHub Actions distributes:

- [Native Android Telegram](../.github/workflows/native-telegram-android.yml):
  signet and mainnet release APKs (arm64) in the existing Telegram chat/topic,
  labelled **Native** with the flavor and commit SHA. Download and install an
  APK over the matching RN app. The workflow run also has a
  `noah-native-android-<sha>` artifact with both APKs.
- [Native iOS Telegram](../.github/workflows/native-telegram-ios.yml): signet
  and mainnet ad-hoc IPAs uploaded to Diawi, with their download links posted
  to the same Telegram topic, labelled **Native** with the flavor and commit
  SHA. Open a link on a registered test device. The run also has
  `noah-native-signet-ios-<sha>` and `noah-native-mainnet-ios-<sha>` IPA
  artifacts.

Maintainers can also use **Run workflow** (`workflow_dispatch`) on either
workflow. Feature-branch pushes do not distribute builds. RN workflows remain
unchanged and continue distributing their builds separately.

## CI credentials and signing

All credentials below are existing repository Actions secrets, referenced only
through `secrets.*`. No signing keys are checked in.

| Workflow | Secrets | Purpose |
| --- | --- | --- |
| Android | `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Same release keystore/alias as RN Signet and Mainnet, for in-place installation |
| Android | `TELEGRAM_API_ID`, `TELEGRAM_API_HASH` | Same local Telegram Bot API server as RN, supporting large APK uploads |
| Both | `TELEGRAM_TOKEN`, `BLIXT_CHAT_ID`, `BLIXT_TOPIC_ID` | Existing Telegram bot, chat and topic |
| Both | `SENTRY_AUTH_TOKEN` | Existing native release hooks upload R8 mappings / archive dSYMs to `dunder-rn` / `react-native` |
| iOS | `DIAWI_TOKEN` | Existing Diawi upload account |

Android builds on a Blacksmith Linux runner. It uses its existing
`MYAPP_RELEASE_*` signing properties in a temporary
`apps/android/local.properties`, pointing at a decoded keystore under
`RUNNER_TEMP`. Both are deleted even on failure; Gradle signing is unchanged.

iOS uses the same `macOS` runner as `ios-device-build.yml`. That RN workflow
has **no certificate/profile/keychain secrets or import steps**: it relies on
the runner's installed signing identity, unlocked keychain and Xcode account
for team `AT26Z24V2Q`. The native workflow preserves that arrangement, so the
runner must also have an Apple Distribution identity and permission to obtain
ad-hoc profiles for `com.noahwallet.signet`,
`com.noahwallet.signet.NoahWidgetSignet`, `com.noahwallet.mainnet` and
`com.noahwallet.mainnet.NoahWidgetMainnet`, including testers' device UDIDs.
For each flavor it runs XcodeGen, archives `Noah-Signet` with
**`Release-Signet`** or `Noah` with **`Release-Mainnet`**, then exports with
`method=release-testing` (Xcode's current name for ad-hoc distribution).
Plain `Release` is not a native configuration and breaks package resolution.
Temporary archives and DerivedData live under `/private/tmp/noah-<flavor>-ci-*`
and are deleted after upload, including on failure.

## Roll back to React Native

Keep the wallet's recovery phrase and a verified backup before upgrade testing.
Download a known-good **RN Signet** APK or Diawi build from the existing
Telegram topic or RN workflow artifacts and reinstall it over Native Signet.
Do not uninstall first when preserving the app's wallet data.

The native Android version code is currently `100`, above RN's `37`; Android
normally blocks installing the older RN APK in place. Use a freshly built,
same-key RN Signet APK with a version code at least as high as the installed
native build. `adb install -r -d <rn-signet.apk>` only permits downgrade where
the device/build policy allows it; release devices may refuse. If an uninstall
is unavoidable, it deletes wallet data: recover from the saved phrase/backup.
On iOS, open the known-good RN Diawi link on the registered device and install
over the native app; its signing certificate/profile must still be valid.
Reinstalling RN does not undo any wallet database changes: verify that the RN
build supports the wallet schema before reopening it.
