# Android restore UI verification

Based on `feat/native-rewrite` at `391ada96` (bark-bolt-ffi 0.4.0), 2026-10-19.

Welcome → Restore Wallet accepts 12 numbered words, local BIP39 suggestions,
whole-phrase paste in any field, compatibility/case/whitespace normalization and
checksum validation. Incorrect paste lengths preserve existing entries and explain
what to change. `FLAG_SECURE` protects this screen; no input is saved in instance
state. Leaving, backgrounding and submitting discard the entered words.

Engine steps map to fetching, validating, installing the wallet and verifying
recovery-phrase storage. Fixed messages cover every engine reason, missing backup,
wrong network, damaged download, authentication and connectivity problems. A
post-commit error blocks both retry and Back and explicitly says the backup was
restored and Noah must restart. Completion saves onboarding flags, seeds
`backup-storage`, settles pending cleanup, clears the server token, opens the
wallet and starts server services in that order. Successful deletion resets the
in-memory restore flow for a subsequent wallet.

Seed-only launch offers the stored-phrase recovery screen without displaying the
phrase. Restore and deletion markers block onboarding regardless of database/seed
presence; recovery runs before launch inspection. Waiting for an unreadable secret
keeps the journal and wallet files and asks the user to unlock. Existing data,
suspended wallets and active sessions cannot enter onboarding restore. Neither RN
nor iOS Backup & Restore Settings offers restore over an installed wallet, so the
existing Android backup toggle/manual upload/list/delete/export controls remain.

## Automated verification

The Android JVM cases mirror the reviewed iOS `RestoreUITests.swift`: normalization,
paste lengths, suggestions, dictionary/count/checksum validation, all issue reasons,
completion ordering, legacy and missing-ID outcomes, unresolved/thrown cleanup and
failed state writes. Additional cases cover duplicate submission, cancellation,
post-commit retry blocking, reset after deletion and all launch-marker combinations.
`apps/fixtures/restore-phrase.json` contains only public BIP39 vectors; Android reads
it from the existing shared-fixture loader. iOS's equivalent tests remain unchanged.

Targeted checks: **22 JVM tests passed** across four suites; no failures or skips.

Final full command (run once after targeted/device iteration):

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Final result: **548 JVM tests**, **40 suites**, **0 failures / 0 errors / 0 skips**.
Regtest debug APK assembly and instrumentation Kotlin compilation passed.
`BUILD SUCCESSFUL in 1m 29s`; full suite ran once. Existing deprecation warnings
remain; no new dependencies or verification-metadata changes were needed.

## Live device evidence

Created Android 15 Google APIs ARM64 AVD **NoahRestoreUI1c474**, explicitly mapped
with `adb -s emulator-5592 emu avd name` to **emulator-5592**. Built the opt-in
instrumentation APK with `-PnoahRegtestServerURL=http://10.0.2.2:3100` and used the
existing S3-configured local server. Final instrumentation run: **2 tests passed**,
zero failures, **33.436 seconds**.

- `liveBackupWipePasteRestoreAndMissingBackup`: create a throwaway wallet; upload
  a real encrypted backup; drain backup work and wipe local database, seed, token
  and settings while keeping the server backup; enter an unrelated valid phrase
  through the private field and see the actionable missing-backup error with no
  local seed/database created; return to Welcome; paste the backed-up phrase;
  background/resume and verify all input cleared; re-enter and press Restore;
  verify fingerprint, every balance bucket and movement count match; confirm
  backup-storage hash, cleared restore journal and restarted server registration.
  Then remove only the local database while retaining the secure phrase, relaunch
  into “Recovery phrase found”, submit without exposing the phrase, and compare
  the restored wallet again. All throwaway server snapshots are removed and the
  local wallet is deleted via the existing lifecycle API at the end.
- `interruptedJournalAndWaitingSecretNeverOfferCreation`: an unreadable journal
  opens “Restore didn't finish”; a valid committing journal plus an unreadable
  secret opens “Unlock your device”. Both hide Create Wallet; the waiting case
  retains its journal and database. Its simulated files are removed afterward.

Both runs use the real launch gate and UI controls. Earlier iteration failures
were accessibility lookup races between frames; explicit waits fixed the test.
The initial failed iteration lacked independent cleanup credentials after its
local wipe and may have left one encrypted unfunded test snapshot on the server;
later iterations retain a temporary authenticated client for cleanup.
The first successful single-case round trip took 21.169 seconds. Failed iterations
were not counted as passing tests. No secret or screen content was written to test
output; only public success flags and zero-balance/movement counts were reported.

After verification, the owned AVD was mapped again, shut down through the device
host, and only `NoahRestoreUI1c474` was deleted. No build output directory was
created outside this worktree; temporary command/emulator logs were removed.

## Reproduce on a disposable emulator

Never run the opt-in device class on a user's wallet or a shared emulator. It
creates and wipes its own throwaway app wallet, and removes its server backups
and local wallet at the end. Use an emulator you created for this run.

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 -PnoahRegtestServerURL=http://10.0.2.2:3100 :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest'
adb -s YOUR_SERIAL install -r apps/android/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk
adb -s YOUR_SERIAL install -r apps/android/app/build/outputs/apk/androidTest/regtest/debug/app-regtest-debug-androidTest.apk
adb -s YOUR_SERIAL shell am instrument -w \
  -e noahRestoreUIIT disposable-emulator \
  -e class com.noahwallet.services.RestoreUIDeviceTest \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

No recovery phrase appears in shell arguments, runner output, screenshots or
saved accessibility trees. The test injects the phrase directly into a private
field with the platform accessibility text action, then presses the real Restore
button. It waits for Compose accessibility updates instead of racing recomposition.

## Differences and limits

The input matches reviewed native iOS behavior. RN currently has a freeform
multiline field and no local word suggestions. Android uses its existing
IME-resizing scroll form, a password keyboard (with visible entered words and
private accessibility semantics), `FLAG_SECURE` and an indeterminate stage bar.
Its BIP39 checksum uses the bundled English list and JDK SHA-256 so the same logic
runs in JVM tests without JNI; the live test also checks SDK-generated phrases.

The live wallet is unfunded: balance **0 sats**, **0 movements**, with exact balance
bucket and wallet fingerprint comparison. Funded restore, direct iOS ↔ Android
restore, physical devices, API 24–34/36–37, actual hardware storage loss and a live
post-commit filesystem-cleanup failure remain unverified. The engine's existing
crash/storage tests and the new completion state tests cover the latter behavior
with injected failures. No Docker service, protected emulator, physical Pixel or
iOS simulator was controlled for this task.
