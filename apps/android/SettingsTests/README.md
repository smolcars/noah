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


## Round-3 deletion review follow-up (2026-10-19)

Disposable iOS 27.0 simulator `Noah Deletion R3 Sol 7c062`
(`79139208-60C2-48E0-B902-5CE79AF313FD`), isolated DerivedData
`/tmp/noah-deletion-r3-7c062-ios`. Signing was enabled for Keychain tests.
No Android emulator, protected simulator, physical device or Docker service was used.

- iOS Settings deletion, interrupted-deletion recovery, wallet creation and restore
  share one mutation gate. Recovery reads the marker inside that gate and returns
  if Settings already removed it. A deterministic test pauses Settings at its
  committed marker, queues the real WalletCreator before push recovery, then
  verifies the replacement database and Keychain seed survive both queued and
  late recovery. The test also checks recovery has not entered background
  settlement while Settings still owns the gate.
- Both platforms write a random local instance ID into the wallet directory and
  its durable deletion marker. Recovery discards mismatched/invalid markers with
  a fixed, secret-free log message and leaves the current wallet untouched.
  One-byte legacy markers only auto-finish when no live datadir exists; a populated
  or partly removed legacy wallet requires a fresh explicit confirmation. The
  incomplete-wallet screens provide typed deletion, so this conservative handling
  cannot strand a seed behind a broken database.
- The live datadir is atomically renamed to `.noah-data-<variant>-deleting-data`
  and its parent synced before recursive removal. Relaunch fixtures cover the
  committed marker, rename, partial recursive removal (including the instance ID),
  files removed, seed removed and preferences removed. This keeps a partial
  cleanup from looking like a different wallet. All wallet and scratch files are
  removed and synced before seed deletion; creation/restore refuse a pending
  deletion marker.
- Deletion cancels the coordinator's in-flight backup pass, including its network
  upload, and allows at most 30 s for backup/export settlement before committing.
  A non-cooperative pass remains owned by its coordinator. Timeout fails with
  “Noah is still finishing a backup. Try again in a moment.” Database, seed, token
  and preferences remain intact; the original suspension state and export gate
  are restored for retry. Tests cover cancellation, an uncooperative iOS pass,
  export timeout/retry and Android background-settlement timeout. iOS uploads use
  a 60 s resource timeout as well as the 60 s inactivity timeout, matching
  Android's total call timeout.
- History and Settings exports on Android sync their own directories after
  checked unlink/revocation. Injected sync failures for each directory stop before
  the marker and keep the database/seed/preferences; retry syncs even an already
  empty directory. iOS retained encrypted/log shares already run through the
  checked scratch sweep, which syncs the scratch directory and its parent;
  History CSV has no retained app-owned file.

Full-suite commands (each full suite run once after targeted checks):

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
nix develop --command sh -c 'cd apps/ios && /opt/homebrew/bin/xcodegen generate && xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest -destination "platform=iOS Simulator,id=79139208-60C2-48E0-B902-5CE79AF313FD" -derivedDataPath /tmp/noah-deletion-r3-7c062-ios -resultBundlePath /tmp/noah-deletion-r3-7c062-full.xcresult -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO test'
```

Final results: **532 Android JVM tests**, 36 suites, **0 failures / 0 errors /
0 skips**; regtest APK assembly and instrumentation Kotlin compilation passed.
Full iOS `NoahTests`: **321 tests**, 73 suites, **308 passed / 13 opt-in skipped /
0 failed** (top-level xcresult summary; expanded parameterized runs: 353 passed,
13 skipped). Each full suite ran once. Targeted checks passed: 53 iOS tests in
five lifecycle/creator/restorer/scratch/coordinator suites, followed by the final
12 lifecycle tests after strengthening the creation seam; 29 Android tests across
lifecycle/coordinator/share suites, followed by the final 15 lifecycle tests.
Logs: `/tmp/noah-r3-android-full.log`, `/tmp/noah-r3-ios-full.log`; iOS result bundle:
`/tmp/noah-deletion-r3-7c062-full.xcresult`.

After testing, the simulator UUID and exact name were checked, it was shut down
through the Device panel, its `Shutdown` state was verified with `simctl`, and
only that UUID was deleted. No Android device was started or changed.

The initial iOS targeted build exposed a missing `try`; a subsequent targeted run
exposed a reservation test that expected an immediate error from a second restore
now queued by the shared gate. That run was stopped, and the test now uses a
separate injected gate to verify the underlying session reservation still rejects
competing mutations. A new incomplete-wallet retry fixture also required relaxing
its database-present assertion to directory-present. Corrected targeted checks
passed before the full runs. No full suite was rerun during iteration.

No funded-wallet acceptance or Android instrumentation was rerun for this round;
the requested instrumentation sources were compiled. The new incomplete-wallet
confirmation UI was compiled, with its deletion/retry behavior covered through
lifecycle tests, but not manually driven. Protected devices and infrastructure
were untouched.


## Round-4 deletion recovery follow-up (2026-10-19)

This supersedes round 3's UUID-only / one-byte legacy-marker recovery policy.
Both platforms now persist the local instance ID plus a random salt and
HMAC-SHA256 mnemonic tag in the deletion marker outside the removed datadir.
Recovery requires the current seed to match that tag even when the datadir has
already gone. A different or unreadable seed discards the stale marker, with a
fixed message containing no secrets, and preserves wallet/local state. A missing
seed permits the remaining non-seed cleanup for a valid marker. Legacy UUID-only,
one-byte and malformed markers cannot establish seed ownership and are discarded;
an incomplete wallet remains explicitly deletable with fresh confirmation.

Launch and push/WorkManager use the same serialized recovery path: restore settles
before deletion recovery and wallet opening. A committed deletion cannot coexist
with a restore journal because each mutation refuses the other's marker. Such a
marker is therefore stale even if its instance and seed match. Its removal is
synced before restore can remove its journal, preventing a crash immediately after
rollback from reauthorizing stale deletion on the next launch. A failed marker
unlink sync is retried before journal settlement, including when the marker is
already absent. Locked secure storage leaves the restore pending until retry;
no wallet data or seed is deleted by the stale marker.

Regression coverage on both platforms includes the 16 absent-datadir combinations
of bound/UUID-only/one-byte/malformed marker with seed A/B/none/unreadable; populated
and partial legacy datadirs; all prior destructive boundaries; matching-marker
plus installing/committing journals; rollback and completed restore; concurrent
UI/headless recovery; secure-storage unlock retry; and interruption immediately
after rollback removes the journal. The existing iOS shared gate and Send wallet
change hook, 30-second bounded drains and Android export-directory syncs remain.

Only disposable iOS 27.0 simulator `Noah Deletion R4 Sol 78ed`
(`E68A57C5-4ADC-4AAF-BC0E-B1B0CBC96313`) was used. Signing remained enabled.
DerivedData and all result bundles were isolated under `/private/tmp`.
No Android emulator, protected simulator, physical device or Docker was used.

Final full-suite commands (one run of each after targeted checks):

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
nix develop --command sh -c 'cd apps/ios && /opt/homebrew/bin/xcodegen generate && xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest -destination "platform=iOS Simulator,id=E68A57C5-4ADC-4AAF-BC0E-B1B0CBC96313" -derivedDataPath /private/tmp/noah-deletion-r4-78ed-ios -resultBundlePath /private/tmp/noah-deletion-r4-78ed-full.xcresult -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO test'
```

Final results: **557 Android JVM tests**, **40 suites**, **0 failures / 0 errors /
0 skips**; regtest APK assembly passed. Full iOS `NoahTests`: **351 tests**,
**79 suites**, **335 passed / 16 opt-in skipped / 0 failed**, from xcresult's
summary. Each full suite ran once. Targeted iteration passed Android's 15 lifecycle
tests, 35 lifecycle/restorer tests, then the final 18 lifecycle tests; iOS's 12
lifecycle tests (including the 16-case seed/marker matrix), 30 lifecycle/restorer
tests, then the final 15 lifecycle tests (including that matrix and three journal
phases). The initial targeted iOS compile caught a test helper whose return type
was widened accidentally; it was corrected before successful targeted runs.
Nix evaluation-cache contention delayed some starts; no full rerun was needed.

Logs: `/private/tmp/noah-r4-android-full.log`, `/private/tmp/noah-r4-ios-full.log`.
The iOS summary is retained in `/private/tmp/noah-r4-ios-full-summary.json`.
After testing, the simulator's exact UUID/name and `Shutdown` state were verified,
then only that simulator was deleted. Its DerivedData and all five targeted/full
`.xcresult` bundles were removed and their absence checked. No protected device
or infrastructure was changed.

No funded-wallet acceptance, Android instrumentation or physical-device biometric
checks were rerun in this round. The requested JVM/build and signed iOS suites
passed; restore UI, Send/Onchain/BTC Map, server and `client/` files were untouched.


## Round-5 deletion recovery and restore entry (2026-10-19)

Base: `feat/native-rewrite` at `337245c2`. This supersedes round 4's treatment of
unreadable secure storage as a mismatched seed. A valid bound deletion marker and
its staged files now survive unreadable storage and return a waiting state.
Unlock retry revalidates ownership; readable mismatches and legacy markers may be
discarded. A restore journal still settles before deletion, with stale-marker
unlink synced before journal removal. If its bound marker cannot yet be checked,
both remain pending until secure storage is readable.

Unmarked staging comes from an already committed deletion whose marker was
discarded. Both platforms check its removal and sync the parent directory before
new restore staging, creation or a deletion marker. The directory sync repeats
on retry even if unlink already succeeded. Orphan cleanup refuses pending markers;
the matching committed-delete path can finish older versions' stuck live-plus-stage
state. Android creation cleans staging while holding its session reservation;
iOS creation holds its existing lifecycle gate. No lock-order reversal was added.

Android startup no longer calls standalone deletion recovery first: combined
recovery, completion replay and wallet opening share one lifecycle-gate interval.
Held callers pass `walletMutationHeld = true`; UI and headless recovery use gate →
RestoreRecovery → session reservation. Production-order regression fixtures cover
matching-marker + restore journals, all restore journal phases, completion replay,
headless contention, locked/unlocked secure storage, partial staged deletion,
discarded-marker leftovers and cleanup sync failure/retry. Both platforms also run
the real encrypted-backup restore → delete pipeline after staged deletion/unlock,
including the older-version case where the marker was already discarded.

On iOS, restore words remain visible, with system VoiceOver allowed to announce
visible content. Keyboard extensions are blocked app-wide to prevent third-party
keystroke observation in these non-secure fields. Autocorrection, spell-checking,
inline predictions, smart quotes/dashes/insert-delete and content-type suggestions
are disabled; entry uses ASCII/no capitalization. Only a fitting suggestion row
is presented, with accessibility labels equal to the visible words. The risk
model is documented beside the delegate/field and shield. Tests verify the actual
UITextField traits, visible-entry policy and delegate extension-point decision.

Final targeted checks: **75 Android JVM tests**, four suites, **0 failures /
0 errors / 0 skips**; signed iOS **53 tests**, five suites, **53 passed / 0 skipped /
0 failed** (82 expanded parameterized passes). The initial iOS targeted build
mixed a newly edited test with a previously compiled app and failed to find the
new delegate method; the next build resolved that. Targeted runs also caught
pre-marker sync expectations in Android tests and an iOS sync against a not-yet
created directory; those were corrected before successful final targeted runs.
No full suite was used during iteration.

The only device used was newly created iOS 27.0 simulator
`Noah Deletion R5 Sol 18b34` (`58EA0F3B-BAFB-4ED5-9087-DC8DA91A15CF`, iPhone 17).
Device-panel attachment failed twice with “No device
58EA0F3B-BAFB-4ED5-9087-DC8DA91A15CF on host local. Call device_list for current
ids.” The UUID was verified through `simctl`, and signed Xcode tests used it
directly. No Android emulator, protected simulator, physical device or Docker was
started, stopped or changed.

Full-suite commands (one run each after final targeted checks):

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
nix develop --command sh -c 'cd apps/ios && /opt/homebrew/bin/xcodegen generate && xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest -destination "platform=iOS Simulator,id=58EA0F3B-BAFB-4ED5-9087-DC8DA91A15CF" -derivedDataPath /private/tmp/noah-deletion-r5-18b34-ios -resultBundlePath /private/tmp/noah-deletion-r5-18b34-full.xcresult -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=YES CODE_SIGNING_REQUIRED=YES -parallel-testing-enabled NO test'
```


Final results: **569 Android JVM tests**, **42 suites**, **0 failures / 0 errors /
0 skips**; regtest APK assembly passed. Full signed arm64 iOS `NoahTests`:
**374 tests**, **82 suites**, **358 passed / 16 opt-in skipped / 0 failed**
(xcresult summary; **470 expanded parameterized passes**, 16 skipped). Each full
suite ran exactly once after the final targeted checks. The APK build emitted
its existing native-library symbol-stripping warning; assembly succeeded.

Logs: `/private/tmp/noah-r5-android-full.log`,
`/private/tmp/noah-r5-ios-full.log`. Machine-readable summaries remain at
`/private/tmp/noah-r5-android-full-summary.json`,
`/private/tmp/noah-r5-ios-full-summary.json`; targeted iOS summary at
`/private/tmp/noah-r5-ios-target3-summary.json`.

After extracting results, the simulator's exact UUID, name and iPhone 17 type
were checked, it was shut down, its `Shutdown` state and name were rechecked,
and only that UUID was deleted. The simulator's absence was verified. Its
`/private/tmp/noah-deletion-r5-18b34-ios` DerivedData and all four owned
`target1`/`target2`/`target3`/`full` `.xcresult` bundles were deleted and their
absence checked.

All three round-5 High findings are addressed. No funded-wallet acceptance,
Android instrumentation, physical-device keyboard-extension or manual VoiceOver
session was rerun. Send, Onchain, BTC Map, server and `client/` implementation
was untouched; only wallet recovery, restore entry and their tests/docs changed.
