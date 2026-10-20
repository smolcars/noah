# Settings and wallet lifecycle acceptance

Run on a disposable Google APIs regtest emulator only. Never use the upgrade-test
phone or another agent's emulator. Build with JDK 17:

```sh
cd apps/android
./gradlew :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest \
  -PnoahRegtestServerURL=http://10.0.2.2:3100
```

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

## 2026-10-19 acceptance

Google APIs Android 15, dedicated AVD `NoahSettingsM5Astra`, serial `emulator-5582`.
Server on host port 3100 with the existing S3 configuration; infrastructure was
not restarted. Bark funded the new wallet with 10,000 sats over Ark. Home showed
10,000 sats and one payment after sync.

- 236 JVM tests passed; regtest APK and instrumentation APK built.
  A resource-contention timeout in the existing restore suite passed on the full
  rerun with `--max-workers=1` (zero failures or skipped tests).
- Live backup integration passed: enable, manual upload, list, delete (including
  invalidation of the remembered snapshot), disabled-auto/manual upload, export
  and decryption. UI Backup Now, List Backups and confirmed Delete Backup passed.
- Re-register through Settings succeeded; FCM/Expo push registration was observed
  again in the redacted application log.
- Seed viewing required the device PIN. App lock required authentication after
  backgrounding and cold launch; enabling and disabling each required authentication.
- UI suspension persisted across relaunch. Home had only the suspended card;
  all three background/session/backup refusal checks passed. Resume returned the
  10,000-sat wallet.
- Export opened Android's share chooser with the encrypted `.noahbackup`; canceling
  the chooser removed the scratch file immediately on return to Noah.
- Delete remained disabled until `delete` was entered. Confirming returned to
  onboarding. After force-stop, the deletion instrumentation passed: wallet and
  restore directories absent, native and all seeded RN legacy readers empty,
  auth token absent, MMKV cleared, widget preferences empty, and onboarding selected.
  All five opt-in device stages passed.

## Scope and remaining device coverage

Language selection persists RN's `gt_store` locale; translated native catalogs
remain the shared rewrite backlog, matching iOS. Auto-board is a preference until
M6. Esplora editing and restore UI remain their existing TODOs. The optional
battery screen opens system battery settings; the onboarding battery gate is not
implemented here.

Device-credential authentication was exercised; physical fingerprint hardware,
API 24–29 credential fallback, non-GMS push, and adaptive layouts were not tested.
Feedback payload/image limits are tested locally; no support ticket was sent.
Destructive developer actions were not executed. The bundled story is copied from
`client/assets/noahs-ark-story.m4a` under the repository's MIT license.
