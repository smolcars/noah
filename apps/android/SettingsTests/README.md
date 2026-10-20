# Settings and wallet lifecycle acceptance

Run on a disposable Google APIs regtest emulator only. Never use the upgrade-test
phone or another agent's emulator. Build with JDK 17:

```sh
cd apps/android
./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest \
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

## Round-1 review follow-up (2026-10-19)

Disposable AVD `NoahReviewApi28B2ed`, Android 9 / API 28, serial `emulator-5584`,
with a test-only screen-lock PIN. No funded wallet or existing device was used.

- 385 JVM tests passed (31 suites, zero failures/errors/skips). The requested
  regtest assemble and instrumentation Kotlin compilation also passed.
- `WalletPrivacyDeviceTest` passed both opt-in cases: a fresh PIN succeeds across
  AndroidX's credential-activity stop; Home locks Noah; Home during credential
  confirmation invalidates even a subsequent successful PIN; a fresh retry works.
  The Activity's secure flag was asserted. The onboarding component's recovery
  phrase and enabled Continue action recover after Home and foreground return.
  The phrase harness uses a disposable seed and LaunchGate's stop invalidation,
  without creating a wallet or contacting Ark. Recovery words are never dumped.
- The test's opt-in is `-e noahPrivacyIT disposable-api28`; run each method via
  `am instrument -w -e class com.noahwallet.services.WalletPrivacyDeviceTest#METHOD`
  on a disposable API 28 emulator with PIN `1234` only.
- Mailbox tests cover new-wallet defaults, the exact iOS 60-day renewal boundary,
  89-day authorization payload, failed-server retries, explicit disable and
  re-registration. Checked scratch deletion covers undeletable plaintext and
  preserves seed/token/settings; Android deletion also checks preflight copies.
- JVM share tests recreate the owner, preserve encrypted/log exports through the
  24-hour retention boundary, invoke the revocation callback for the exact file
  on expiry/failure, and refuse SQLite staging files. Successful shares survive chooser return and rotation;
  plaintext staging stays operation-scoped. External-recipient rotation was not
  exercised manually.
- Warmed-cache 3,000-row history regressions consume wallet events before
  enrichment completes on both platforms. Observation restart and delete/new-wallet
  history invalidation also have JVM coverage.
- The full iOS `NoahTests` target passed: 257 total, 247 passed, 10 opt-in live tests
  skipped, zero failures (58 suites). XcodeGen ran first; Xcode 27.1 used
  `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, isolated DerivedData at
  `/tmp/noah-review-b2ed-ios`, and disposable simulator `Noah Review b2ed Settings`
  (`4BB72A56-FAAE-4301-84D4-EAD237B47DDC`, iOS 27.0).

Both disposable review devices were shut down and deleted after verification;
the AVD name was mapped from its serial before shutdown.

## Scope and remaining device coverage

Language selection persists RN's `gt_store` locale; translated native catalogs
remain the shared rewrite backlog, matching iOS. Auto-board is a preference until
M6. Esplora editing and restore UI remain their existing TODOs. The optional
battery screen opens system battery settings; the onboarding battery gate is not
implemented here.

Device-credential authentication was exercised; physical fingerprint hardware,
API 24–27/29 credential fallback, non-GMS push, and adaptive layouts were not tested.
Feedback payload/image limits are tested locally; no support ticket was sent.
Destructive developer actions were not executed. The bundled story is copied from
`client/assets/noahs-ark-story.m4a` under the repository's MIT license.

## Round-2 review follow-up (2026-10-19)

Disposable API 28 AVD `NoahSettingsR2dd034`, serial `emulator-5588`, with test PIN
`1234`; disposable iOS 27.0 simulator `Noah Settings Round2 dd034`
(`ABFFD749-A1E0-4EBC-90C8-46CF5BA7C77A`). No protected emulator, simulator,
physical device, or Docker service was changed.

- Credential handoff locks content immediately while preserving the request's
  generation. A normal credential result is accepted before resume; an unexpected
  resume, exported `singleTask` `onNewIntent`, Home/Recents, or screen-off invalidates
  the request. The direct-relaunch regression first unlocks Noah, starts Settings
  authentication, runs `am start -W -n com.noahwallet.regtest/com.noahwallet.MainActivity`
  while the PIN screen is up, then checks the visible lock, rejected Settings result,
  changed generation and a fresh successful PIN unlock. No recovery words are read.
- All four opt-in API 28 device cases passed in one instrumentation run (32.345 s):
  existing credential/Home invalidation and onboarding reload; direct relaunch;
  and retained-export deletion. The last case grants Settings log and History CSV
  URIs to DocumentsUI, calls the real `AppServices.lifecycle.delete()`, then checks
  both files are absent and `checkUriPermission` returns `PERMISSION_DENIED` for
  each exact URI and recipient UID. It uses a disposable seed and closed-session
  file fixture, without opening an Ark wallet or requiring a server.
- Both platforms gate new exports, await in-flight backup uploads/state writes and
  exports, and complete checked scratch cleanup before removing the wallet database.
  Cleanup failure keeps the database, seed, token and preferences; the model reopens
  an originally active wallet and shows: “Noah couldn't remove temporary wallet files.
  Restart Noah and try again.” Retained iOS encrypted/log shares are swept with
  scratch; iOS History CSV uses in-memory data and has no app-owned retained file.
- A separate durable `.noah-data-<variant>-deleting` marker beside the datadir is
  synced before database removal and removed after local state is cleared. UI and
  headless launch recovery finish marked deletion before inspecting/opening wallet
  data. Tests recreate the lifecycle at the marker, files-removed, seed-removed and
  local-state-removed boundaries, and verify a second recovery is a no-op. An error
  before the marker restores the original suspension preference; an error afterwards
  keeps the marker for retry. New scratch operations cannot start during the drain.

Reproduce all four device cases on a newly created disposable API 28 emulator:

```sh
adb -s SERIAL shell am instrument -w \
  -e class com.noahwallet.services.WalletPrivacyDeviceTest,com.noahwallet.services.WalletDeletionDeviceTest \
  -e noahPrivacyIT disposable-api28 \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

Final full-suite commands (each suite run once after the targeted checks):

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
nix develop --command sh -c 'cd apps/ios && /opt/homebrew/bin/xcodegen generate && xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest -destination "platform=iOS Simulator,id=ABFFD749-A1E0-4EBC-90C8-46CF5BA7C77A" -derivedDataPath /tmp/noah-settings-r2-dd034-ios -resultBundlePath /tmp/noah-settings-r2-dd034-full.xcresult -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO test'
```

Final results: **526 Android JVM tests**, 36 suites, zero failures/errors/skips;
regtest APK assembly and instrumentation Kotlin compilation passed. Full iOS
`NoahTests`: **293 tests**, 66 suites, **281 passed / 12 opt-in skipped / 0 failed**
(top-level xcresult summary; expanded parameterized runs: 320 passed, 12 skipped).
Targeted iteration checks passed: 20 Android Settings/lifecycle/share tests,
19 iOS lifecycle/coordinator tests, and 4 iOS scratch tests. Both full suites ran
once; shared Nix evaluation-cache contention delayed startup but no full rerun
was needed. Results: `/tmp/noah-r2-android-full.log` and
`/tmp/noah-settings-r2-dd034-full.xcresult`.

The AVD was mapped with `adb -s emulator-5588 emu avd name` before shutdown,
then only `NoahSettingsR2dd034` was deleted. The iOS UUID/name was checked with
`xcrun simctl list devices`, then that simulator alone was shut down and deleted.
No protected devices or Docker were touched. Physical biometric hardware and
API 24–27/29 handoff behavior were not re-tested; the requested API 28 regression
and existing PIN/Home/onboarding cases passed.
