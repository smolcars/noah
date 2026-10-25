# Settings and wallet lifecycle checks

Run on a disposable Google APIs regtest emulator only. Never use the upgrade-test
phone or another agent's emulator. Build with JDK 17, pointing the app at a Noah
server with S3 configured:

```sh
cd apps/android
./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest \
  -PnoahRegtestServerURL=http://10.0.2.2:3100
```

`SettingsDeviceTest` (no opt-in) covers Back on every Settings page, the phrase
gate, the suspend and delete confirmations, the debug switch and the fold postures.
It never confirms a destructive action.

## Lifecycle acceptance on a funded wallet

The opt-in `SettingsLifecycleDeviceTest` uses the app's real wallet, session,
Keystore, MMKV, backup coordinator and server. The UI owns suspend/resume and
wallet deletion. Each stage must be selected explicitly with `-e noahSettingsIT`:

- `address`: `publicFundingAddress` writes only a public Ark address to
  `cache/settings-public-address.txt`. Fund it with Bark before continuing.
- `backup`: `fundedBackupsAndExport` requires a funded wallet; checks enable/disable,
  manual backup while disabled, server list/delete, and encrypted export/decryption.
- `suspended`: after the UI suspends the wallet and the app is force-stopped,
  `suspendedRejectsPushOpenAndBackupAfterRelaunch` checks the persisted flag,
  background open gate, shared-session gate and push backup refusal.
- `legacy`: `prepareLegacySeedCopiesBeforeUiDeletion` puts the same test-wallet
  seed in RN DataStore, old SharedPreferences and encrypted fallback preferences.
  It never prints the phrase. Use only immediately before disposable-wallet deletion.
- `deleted`: after typing `delete` in the UI and force-stopping the app,
  `deletedWalletCannotMigrateBackAfterRelaunch` verifies datadir/restore-workspace,
  native and all legacy seed readers, auth token, MMKV, widget data and onboarding.

For example, with the actual disposable serial substituted:

```sh
adb -s SERIAL shell am instrument -w \
  -e class com.noahwallet.services.SettingsLifecycleDeviceTest#suspendedRejectsPushOpenAndBackupAfterRelaunch \
  -e noahSettingsIT suspended \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

Run the stages in this order: `address`, fund the wallet (for example
`just bark send '<address>' '10000 sats'`) and wait for Home to show it, `backup`,
suspend in the UI and force-stop, `suspended`, resume in the UI, `legacy`, delete
in the UI and force-stop, `deleted`. Between stages, check by hand that seed viewing
and app lock ask for the device credential, that Export opens the share chooser,
and that Delete stays disabled until `delete` is typed.

## Privacy and deletion device checks

`WalletPrivacyDeviceTest` and `WalletDeletionDeviceTest` need a newly created
disposable **API 28** emulator with device PIN `1234`, and the opt-in
`-e noahPrivacyIT disposable-api28`. They cover credential and Home invalidation of
a pending Settings authentication, a direct relaunch during the credential handoff,
and deletion of retained exports with revocation of their exact read grants. They
use a disposable seed and need no Ark wallet or server.

```sh
adb -s SERIAL shell am instrument -w \
  -e class com.noahwallet.services.WalletPrivacyDeviceTest,com.noahwallet.services.WalletDeletionDeviceTest \
  -e noahPrivacyIT disposable-api28 \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

A pass is `OK` from the instrumentation runner. Recovery words are never read or
dumped. Physical fingerprint hardware, other API levels' credential fallback and
non-GMS push are not covered by these checks.
