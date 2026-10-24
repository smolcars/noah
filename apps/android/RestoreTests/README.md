# Android restore UI verification

Based on `feat/native-rewrite` at `e0fcab52` (bark-bolt-ffi 0.4.0), 2026-10-19.
The initial implementation evidence below is retained separately from the review pass.

Welcome → Restore Wallet accepts 12 numbered words, local BIP39 suggestions,
whole-phrase paste in any field, compatibility/case/whitespace normalization and
checksum validation. Incorrect paste lengths preserve existing entries and explain
what to change. `FLAG_SECURE` protects this screen; no input is saved in instance
state. Leaving, backgrounding and submitting discard the entered words and recreate the
whole native field subtree, dropping its undo history. Platform password EditTexts
mask node text and text-change events according to Android password policy, keep
word-position labels and editing actions, disable saved state/autofill/content
capture, and request no suggestions or personalized IME learning. Prefix-derived
suggestion buttons are excluded from accessibility. Accepted whole-phrase paste
is removed from the clipboard only while its content matches; background and exit
repeat that conditional cleanup. This deliberately improves on RN restore.

Engine steps map to fetching, validating, installing the wallet and verifying
recovery-phrase storage. Fixed messages cover every engine reason, missing backup,
wrong network, damaged download, authentication and connectivity problems. A
post-commit error blocks both retry and Back and explicitly says the backup was
restored and Noah must restart. A metadata-only, fsynced completion receipt is written before installation via an
optional engine callback (commit/rollback rules are unchanged). It survives engine
journal cleanup and replays after launch recovery; rollback or no installation
discards it. Completion saves onboarding flags, seeds
`backup-storage`, settles pending cleanup, clears the server token, opens the
wallet and starts server services, then removes the receipt. The lifecycle mutation
gate covers restore eligibility through completion, before session reservation. Successful deletion resets the
in-memory restore flow for a subsequent wallet.

Seed-only launch offers the stored-phrase recovery screen without displaying the
phrase, an explicit Resume Recovery action while suspended, and a typed confirmed
Delete Wallet fallback. Restore/deletion actions disable during restore and pending
recovery. The enabled Restore control uses the same eligibility guard as the model. Restore and deletion markers block onboarding regardless of database/seed
presence; recovery runs before launch inspection. Waiting for an unreadable secret
keeps the journal and wallet files and asks the user to unlock. Existing data,
suspended wallets and active sessions cannot enter onboarding restore. Neither RN
nor iOS Backup & Restore Settings offers restore over an installed wallet, so the
existing Android backup toggle/manual upload/list/delete/export controls remain.

## Initial implementation verification

The Android JVM cases mirror the reviewed iOS `RestoreUITests.swift`: normalization,
paste lengths, suggestions, dictionary/count/checksum validation, all issue reasons,
completion ordering, legacy and missing-ID outcomes, unresolved/thrown cleanup and
failed state writes. Additional cases cover duplicate submission, cancellation,
post-commit retry blocking, reset after deletion and all launch-marker combinations.
`apps/fixtures/restore-phrase.json` contains only public BIP39 vectors; Android reads
it from the existing shared-fixture loader. iOS now also clears its input on
background/submit/exit, recreates fields, disables UIKit undo retention and clears
matching pasted content; native field and isolated-pasteboard tests cover those fixes.

Targeted checks: **22 JVM tests passed** across four suites; no failures or skips.

Final full command (run once after targeted/device iteration):

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Initial full result: **548 JVM tests**, **40 suites**, **0 failures / 0 errors / 0 skips**.
Regtest debug APK assembly and instrumentation Kotlin compilation passed.
`BUILD SUCCESSFUL in 1m 29s`; full suite ran once. Existing deprecation warnings
remain; no new dependencies or verification-metadata changes were needed.

## Initial live device evidence

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

## Initial differences and limits

Android masks entered words using platform password fields; iOS keeps its existing
visible local fields with undo disabled. RN currently has a freeform
multiline field and no local word suggestions. Android uses its existing
IME-resizing scroll form, platform password fields (masked text and platform
password accessibility behavior), `FLAG_SECURE` and an indeterminate stage bar.
Its BIP39 checksum uses the bundled English list and JDK SHA-256 so the same logic
runs in JVM tests without JNI; the live test also checks SDK-generated phrases.

The initial live wallet was unfunded: balance **0 sats**, **0 movements**, with exact balance
bucket and wallet fingerprint comparison. The review run below adds funded restore and iOS privacy coverage. Direct
iOS ↔ Android restore, physical devices, API 24–34/36–37, actual hardware
storage loss and a live post-commit filesystem-cleanup failure remain unverified. The engine's existing
crash/storage tests and the new completion state tests cover the latter behavior
with injected failures. No Docker service, protected emulator, physical Pixel or
iOS simulator was controlled for this task.

## Review regression coverage

- Platform node text, hint/word position, editing actions, actual password text-change
  events with system speak-passwords disabled, and native EditorInfo flags.
- Background field identity replacement followed by hardware Ctrl+Z; matching
  clipboard removal after accepted paste and preservation of unrelated replacement.
- Controlled deletion drain → queued restore → held completion gate interleaving.
- Durable receipt replay at engine-return, flags, backup seeding, cleanup, token,
  wallet-open and receipt-removal boundaries; rollback/no-install/waiting-secret
  and unreadable-record cases; callback failure before installation.
- Seed-only suspended recovery, disabled Restore until Resume Recovery, confirmed
  deletion back to Welcome, and existing interrupted/waiting-secret launch cases.
- Real Ktor offline backup-list and login conversions retain a safe connectivity
  category without retaining transport exceptions or their private details.

For the funded opt-in case, add `-e noahRestoreFunded 1` to the instrumentation
command. On the owned emulator only, read the public Ark address from
`run-as com.noahwallet.regtest cat files/restore-funding-address.txt`, pay it once
through `just bark send '<address>' '12000 sats'`, then let the test continue.
It waits up to three minutes for funds, checks a nonzero balance and movements,
backs up to port 3100, wipes, restores, and compares every balance bucket and the
full mapped movement list for typed-phrase and stored-seed recovery. No phrase is
exported to a file, shell argument, screenshot or accessibility-tree artifact.

## Review verification — 2026-10-19

Base: `e0fcab52`; Android 15 ARM64 AVD **NoahRestoreReview0dc6**, explicitly
mapped to **emulator-5594**; iPhone 17 / iOS 27.0 simulator
**Noah Restore Review 0dc6** (`C0D892B5-FB05-467B-902B-D31C0067A6CB`).

- Requested Android full command, run once after targeted/device iteration:
  **563 JVM tests / 42 suites / 0 failures / 0 errors / 0 skips**.
  Regtest debug APK assembly and Android instrumentation compilation passed;
  **BUILD SUCCESSFUL in 1m 2s**. Targeted checks while iterating included
  **54 JVM tests / 9 suites / 0 failures** and engine receipt-hook regressions.
- Full iOS NoahTests, run once with code signing enabled and DerivedData under
  `/private/tmp`: **350 total / 334 passed / 16 opt-in skipped / 0 failed**,
  **80 suites** (396 expanded passes in xcresult). Targeted privacy/phrase run:
  **6 tests / 2 suites / 0 failures**. The owned simulator and DerivedData,
  including xcresults, were verified, shut down/deleted and removed afterward.
- Final Android device class: **4 tests passed / 0 failures**, **42.353 seconds**.
  It covers actual native password node text and text-change events with speak
  passwords disabled, safe labels/edit actions, IME flags, background field
  replacement plus Ctrl+Z, paste/clipboard behavior, missing-backup errors,
  suspended seed-only resume, confirmed deletion and interrupted launch gates.
- Funded Android run: **12,000 regtest sats / 1 movement** delivered once from
  the existing Bark wallet via `just bark send '<public address>' '12000 sats'`.
  No mining or Docker lifecycle changes were needed. The port-3100 encrypted
  backup was uploaded, local wallet wiped, and both typed-phrase and stored-seed
  restores passed fingerprint, every balance bucket and full mapped movement
  comparisons. Backup-storage seeding, cleared engine journal and server restart
  also passed. All throwaway server snapshots and local wallet state were removed.

The funded four-case iteration passed its three non-accessibility cases but failed
an event-observation assertion. An earlier privacy iteration also timed out waiting
for a programmatic edit event. The final test explicitly subscribes to all event
types, waits for field attachment and uses real key input; it requires an actual
password text-change event and checks its text/beforeText. Its isolated rerun passed
(**1 test, 2.617 seconds**), then all four final cases passed together. Failed
iterations are not counted as passing runs. The final all-case rerun used an
unfunded wallet; the funded case above was already verified.

No recovery phrase was logged, written to saved-instance state, exported to disk,
or captured in a screenshot/accessibility-tree artifact. No protected emulator,
physical Pixel, shared iOS simulator or Docker service lifecycle was controlled.
The owned Android emulator was identity-checked, shut down and deleted; temporary
command/emulator logs and all owned build output outside the worktree were removed.

Remaining limits: real TalkBack gestures/audio and the system speak-passwords opt-in
were not exercised; the tests inspect the actual platform node/event boundary with
that setting disabled. API 24–28 clipboard fallback, physical devices, live process
kill at every completion boundary, direct cross-platform restore and real storage
faults remain unverified. Completion crash/failure boundaries are deterministic
JVM tests with a real metadata receipt file and injected failures.


## Round-5 recovery and iOS restore-entry follow-up (2026-10-19)

Base: `feat/native-rewrite` at `337245c2`. Android UI startup now uses
`AppServices.recoverRestore(walletMutationHeld = true)` inside one lifecycle
mutation gate through recovery, completion replay and opening. It no longer calls
deletion recovery first. Both held and headless paths run the same combined
recovery, with gate → RestoreRecovery → session reservation ordering. Round-4
fixtures now use that production order and verify that headless recovery waits
until startup completion releases the gate.

Both platforms distinguish unreadable secure storage from a seed mismatch. A
valid bound marker and its staging data stay pending until unlock; startup shows
“Waiting for secure storage”. Readable mismatches and legacy markers can be
discarded. Coexisting restore journals still invalidate stale markers before
journal settlement, but unreadable bound markers wait for secure storage first.
Checked unmarked staging cleanup and parent-directory sync precede restore
staging, creation and a new deletion marker; a failed sync is retried even after
unlink succeeded. Orphan cleanup refuses any pending marker. The committed-delete
path also handles an older app's marker over both a live and staged directory.

Tests cover every rename/removal boundary while locked, same/different seed after
unlock, old discarded markers, cleanup sync failure/retry, and the actual encrypted
backup restore → deletion pipeline on both platforms. The latter tests cover both
retained pending markers and an older app that already discarded its marker.

On iOS, typed words remain visible and available to system VoiceOver. AppDelegate
rejects keyboard extensions app-wide because they can observe non-secure entry;
there are no third-party iOS accessibility services in this risk model. Fields
explicitly disable autocorrection, spelling, inline prediction, quotes, dashes,
smart insert/delete, content-type suggestions and capitalization, and use an ASCII
keyboard. Suggestions render only the row that fits, with each accessibility label
equal to its visible word; no combined phrase or offscreen suggestion row is
exposed. Existing undo/clipboard/screen-capture protections remain. Delegate and
actual UITextField trait tests assert this deliberate visible-entry policy.

Full-suite verification and disposable-simulator cleanup are recorded in
`apps/android/SettingsTests/README.md`, round 5. No funded-wallet acceptance,
Android instrumentation, physical-device keyboard-extension or VoiceOver session
was rerun for this round.

## Restore recovery review round 3

Base: local `feat/native-rewrite` HEAD `8c878b0e`, 2026-10-20.

Committed restore settlement now writes a durable STOPPED automation state before
its matching initialized marker. It can replace marker-only, state-only, corrupt,
mismatched and previously stopped authority files, including an in-process failed
read latch, without touching the committed seed or database. Normal authority
bootstrap also writes state first. Ordinary recurring reads and resume still
reject incomplete/corrupt authority; only committed restore settlement can repair
it, and completion never automatically releases the stop.

Explicit Resume retries the committed settings marker and the existing completion
receipt under the lifecycle mutation gate before checking either pending marker.
Receipt replay still restores backup metadata and invalidates the auth token.
Failed persistence retains suspension and the automation stop. The typed settings
error reaches the Settings action handler instead of its generic fallback.

Regression evidence:

- Original authority behavior: **20 targeted JVM tests / 2 failed**. The new
  partial-authority recovery and initialization-order tests failed. The original
  resume guard also failed the new resume/receipt regression (with its callback
  signature added for compilation, but its original body retained).
- Fixed targeted suites: **47 JVM tests / 3 suites / zero failures or skips**.
  Partial-authority recovery covers five states; resume covers success, settings
  write failure, receipt metadata failure and a retained receipt.
- **25 instrumented tests passed**, zero failures/skips: SettingsFailureTest,
  MMKVRecoveryTest and AndroidStorageTest (**18**), plus the real-filesystem
  restore/seed durability and atomic publication cases and WalletDatabaseDeviceTest
  (**7**). The new resume case exercises actual unwritable settings directories,
  recovered writes, suspension, retained receipts and restored backup metadata.
- **27/27 process-death checkpoints recovered in fresh processes**: the nine
  existing restore boundaries; marker-only/state-only/corrupt authorities; all six
  file-sync/rename-directory-sync fences of authority bootstrap and stop; and all
  nine settlement sync fences (settings marker, STOPPED state, authority marker,
  restore marker removal, cleanup and journal removal). Preference writes remain
  denied during recovery. The tests check the committed database, settled journal,
  initialized Home admission flags, valid STOPPED authority, denied headless work,
  and retry after permissions recover. Automation remains stopped after settings
  completion. One recovery runner exited without reporting a result at
  `initializing4`; the unchanged disk checkpoint passed on retry. No checkpoint
  assertion failed.
- Final required command completed after the last code change:

  ```sh
  nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
  ```

  **3,451 JVM tests / 121 suites / zero failures, errors or skips**; APK assembly
  and instrumentation compilation passed, **BUILD SUCCESSFUL in 1m 22s**. One
  earlier full invocation was canceled before completion to forward the new
  user-facing error through the UI handler; it is not counted as a completed run.

All device tests used the owned Android 15 Google APIs ARM64 AVD
`NoahRestoreRecoverySol5610`, explicitly confirmed as `emulator-5610`, launched
with `-port 5610 -no-window -no-snapshot`. These are isolated storage/restore
fixtures and process termination tests, not funded-wallet UI or hardware power-loss
tests. No physical device, shared emulator or Docker service was controlled.

Cleanup: the AVD name was confirmed again before shutdown; `adb devices` confirmed
`emulator-5610` was absent, and both the exact `.avd` directory and `.ini`
registration were verified absent after deleting only `NoahRestoreRecoverySol5610`.

## Stored-phrase screen restyle (2026-10-24)

"Recovery phrase found" is now an onboarding step (`storedSeed` in `Onboarding.kt`, as iOS's
`OnboardingFlow.storedSeed`). `FormLayout` lays it out: the headline and short line, the error just above a black
Restore Wallet pill, then a red Delete Wallet. Book posture splits at the hinge, and tabletop puts the headline
above the fold. Restore Wallet plays the Restoring scene and holds "Ready" before Home. A failed restore returns to
this screen with its error. The old step messages ("Validating backup…" and the others) are gone, as on iOS. The
suspended branch, blocked Back, `RestoreShield`, `restore-error` and `restore-progress` (now Restoring's phase
text) all stay.

Opt-in captures of the suspended, idle, restoring and error states run on a disposable regtest emulator. Point the
server at a host port that accepts connections and never answers, so Restore holds and then fails:

```sh
./gradlew -PnoahRegtestServerURL=http://10.0.2.2:3199 assembleRegtestDebug assembleRegtestDebugAndroidTest
adb shell am instrument -w -e class com.noahwallet.ui.StoredSeedShotsTest -e noahStoredSeedShots <label> \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.noahwallet.regtest cat files/stored-seed-shots/<label>/idle.png > idle.png
```

The stored phrase is the public BIP-39 test vector, and the screen never shows it. Captures were taken before and
after on the owned Android 15 AVDs `noah-phrase-phone` (emulator-5592) and `noah-phrase-fold` (emulator-5594), in
light and dark. The fold was half-open: rotation 0 for book, rotation 1 for tabletop. The phone was also captured
with animator scale 0.

Results:
- `OnboardingFoldTest`, which now includes this screen, passed 3/3.
- `RestoreUIDeviceTest.seedOnlyLaunchCanConfirmDeletionAndStartOver` passed.
- The live successful stored-phrase restore (`liveBackupWipePasteRestoreAndMissingBackup`) wasn't rerun to the
  end. Neither local server could store a backup, so it stops at "upload backup".
- Two failures are unrelated to this screen and predate it:
  - `platformWordFieldsAreVisibleAndKeepTheKeyboardPrivate` expects no transformation method, but the field sets
    `isSingleLine`.
  - `interruptedJournalAndWaitingSecretNeverOfferCreation` waits for "Unlock your device", but that screen reads
    "Wallet recovery required".
- Full JVM suite: 4,215 tests in 197 suites, 0 failures; the instrumentation APK builds.
