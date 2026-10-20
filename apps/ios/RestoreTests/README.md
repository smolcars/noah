# Native restore checks

Use a disposable simulator and an already-running regtest server with S3 configured. Do not restart shared containers. These checks fund a throwaway wallet; never run the opt-in checkpoints against a wallet you need.

Start with a fresh simulator or reset its Keychain before creating the reference wallet. Reusing a simulator after manually deleting only its seed can leave a server token for a previous test wallet. Do not reset the Keychain between the reference backup and the stored-seed restore.

Build and run the unit suites with your own DerivedData and simulator:

```sh
xcodegen generate --spec apps/ios/project.yml
TEST_RUNNER_NOAH_REGTEST_SERVER_URL=http://127.0.0.1:3100 \
TEST_RUNNER_NOAH_BACKUP_IT=1 xcodebuild -project apps/ios/Noah.xcodeproj \
  -scheme Noah-Regtest -destination "platform=iOS Simulator,id=$NOAH_TEST_UDID" \
  -derivedDataPath apps/ios/build/DerivedData -parallel-testing-enabled NO test
```

`RecoveryPhraseInputTests`, `RestoreCompletionTests`, and `RestoreIssueTests` cover normalization, whole-phrase paste, excess/missing words, the bundled suggestions, BIP39 checksum validation, completion ordering, legacy outcomes, cleanup failures and safe error copy. The existing engine tests remain unchanged.

For a funded UI round trip:

1. Launch with `SIMCTL_CHILD_NOAH_REGTEST_SERVER_URL=http://127.0.0.1:3100 xcrun simctl launch "$NOAH_TEST_UDID" com.noahwallet.regtest`. Create a wallet through onboarding, receive an Ark payment using `just bark send '<address>' '12000 sats'`, and confirm it settled in the app.
2. Run the `backup` checkpoint below. It enables server backups, flushes the production coordinator and checks the uploaded backup ID using a fresh phrase-authenticated restore client. It writes `Documents/restore-check.json` containing only the fingerprint, spendable balance, movement IDs, statuses and amounts.
3. **Before uninstalling**, copy that report and the test-only `Documents/restore-test-phrase.txt` into private temporary files. The phrase file is mode 0600; keep its destination mode 0600 too. Verify its word count without printing its contents. This explicit export avoids relying on the simulator clipboard surviving a test-host exit. It is only produced by the opt-in test, never by the app.
4. Uninstall and reinstall without clearing the Keychain. Launch with the server override again. Tap Restore Wallet on “Recovery phrase found.” Confirm Home, then run the `verify` checkpoint and compare its report byte-for-byte with the original.
5. With the private phrase copy verified, run `prepare-phrase`. This closes the session and deletes **only** the regtest mnemonic item, conditional on its unchanged value. Uninstall and reinstall again. Welcome → Restore Wallet must now show the numbered fields. Enter the phrase, restore and run `verify` again. Compare the same original report.
6. Delete the private phrase copy, `apps/ios/build`, and the disposable simulator. Do not capture screenshots or accessibility trees containing the phrase.

Checkpoint command (choose `backup`, `verify`, or `prepare-phrase`):

```sh
TEST_RUNNER_NOAH_REGTEST_SERVER_URL=http://127.0.0.1:3100 \
TEST_RUNNER_NOAH_RESTORE_LIVE_CHECK=1 \
TEST_RUNNER_NOAH_RESTORE_ACTION=backup \
xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$NOAH_TEST_UDID" \
  -derivedDataPath apps/ios/build/DerivedData -parallel-testing-enabled NO \
  -only-testing:NoahTests/RestoreLiveTests test-without-building
```

The checkpoint suite is skipped by default and refuses non-regtest variants. It adds no app controls. Each reinstall needs the server override on launch; `ServerEndpoint` does not persist it.

## Verified 2026-10-19

- Noah Restore Agent, iPhone 17 / iOS 27.0, Xcode 27.1; server `http://127.0.0.1:3100` with S3.
- Full suite: 169 tests in 40 suites passed, including the S3 round trip and unchanged restore-engine tests. After the keyboard adjustment, all 11 focused phrase/completion/error tests passed again.
- Created and funded through the app; 12,000 regtest sats received over Ark. A fresh phrase-authenticated client confirmed the uploaded backup was accessible.
- Both uninstall/reinstall paths passed: stored seed without typing, then the same wallet after deleting its seed and entering all 12 words individually. Reports matched byte-for-byte: fingerprint `45257914`, spendable 12,000 sats, movement ID 1, status `successful`, effective balance +12,000. The final checkpoint also confirmed both wallet-storage flags and no pending restore journal.
- Keyboard inspection confirmed visible local word suggestions; no-backup copy was exercised live. Whole-phrase normalization/paste and unsafe retry states are covered by unit tests.
- Physical devices and iOS 18 runtime behavior were not exercised. The temporary phrase, build artifacts and simulator were removed after verification.

## Restore recovery review round 3

Base: local `feat/native-rewrite` HEAD `8c878b0e`, 2026-10-20.

Restore settlement now atomically persists and syncs a STOPPED authority state,
then its matching initialized marker, before removing the restore journal. It
repairs missing, partial, corrupt and mismatched authority files only for a
committed restore, and releases a prior in-process authority failure latch only
after both files are durable. Normal bootstrap writes state before its marker.
Ordinary recurring reads and explicit resume still fail closed on authority
damage; settings completion never automatically restarts automation.

Explicit Resume retries committed settings completion before its pending guard.
If storage still rejects the write, suspension and the automation stop remain;
Settings now forwards the specific “settings can't be saved yet” message through
its action handler. Recovered storage permits that same Resume action to succeed
without foreground activation or restart.

Regression evidence:

- Both new authority tests failed against the original implementation: four of
  the five partial-authority cases could not settle, and the initialization-order
  test observed a marker before a state file. The original resume implementation
  failed both storage-recovered and storage-still-failing cases (the latter also
  showed the wrong error message).
- Targeted restore/lifecycle/authority suites passed: **49 declared tests / 48
  passed / 1 opt-in process-death test skipped**; **114 expanded passing cases**.
  The authority matrix includes marker-only, state-only, corrupt, mismatched and
  already-stopped files. Existing missing/corrupt authority rejection tests remain
  unchanged and passed.
- The process-death runner passed **27/27 checkpoints**: nine restore boundaries;
  three partial-authority states; six actual sync fences of authority bootstrap
  and stop; nine actual settlement sync fences, including the settings marker,
  STOPPED authority pair, restore marker, previous/scratch cleanup and journal
  removal. It terminates the test host while blocked at the real write fence and
  recovers in a fresh process with preference writes still denied. Recovery checks
  the retained committed database, settled journal, initialized Home admission
  flags, valid STOPPED authority, denied headless work and settings persistence
  after permissions recover. Automation remains stopped after completion.

All iOS runs use the owned **Noah Restore Recovery Sol 5610**, iPhone 17 / iOS 27.0,
UDID `E2A730DB-F0A0-4CA2-A668-BAFFA98F98C9`, Xcode 27.1. Builds use XcodeGen,
signing enabled, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, no parallel testing and the
owned `/private/tmp/noah-restore-sol-dd` DerivedData. Individual test filters retain
the trailing `()` (or Swift Testing parameter signature). `build-for-testing`
prepares the `.xctestrun` required by `UpgradeTests/settings-restore-faults.sh`.
These are isolated seed/database fixtures and actual host termination tests;
funded UI restores, physical devices and hardware power loss were not exercised.
No protected simulator or Docker service was controlled.

Final full `NoahTests` ran once after the last code change, with no method
exclusions: **691 declared tests / 142 suites / 668 passed / 23 opt-in skipped /
zero failures**, including **1,408 expanded passing cases**. Test execution took
**186.744 seconds**; **TEST SUCCEEDED**. The process-death helper is among the
opt-in skips in this run and was separately enabled for all 27 successful killed-host
recoveries. Live backup/server/funded-payment, physical-device and prepared RN
upgrade checkpoints retain their existing opt-in conditions.

```sh
nix develop --command sh -c 'cd apps/ios && /opt/homebrew/bin/xcodegen generate && xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest -destination "platform=iOS Simulator,id=E2A730DB-F0A0-4CA2-A668-BAFFA98F98C9" -derivedDataPath /private/tmp/noah-restore-sol-dd -resultBundlePath /private/tmp/noah-restore-sol-final.xcresult -jobs 2 -parallel-testing-enabled NO -collect-test-diagnostics never ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -only-testing:NoahTests test'
```

Cleanup completed: the exact simulator name/UDID and Shutdown state were confirmed
before deletion, then its UDID was confirmed absent. Removed the owned DerivedData,
all result bundles (including the final explicit bundle), temporary helper scripts,
summary and logs. No owned build/test process remains active; shared daemons were
left alone. Android's owned emulator cleanup is recorded in its restore notes.
