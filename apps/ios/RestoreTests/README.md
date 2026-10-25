# Native restore checks

Use a disposable simulator and an already-running regtest server with S3 configured (the examples use port 3100). Do not restart shared containers. These checks fund a throwaway wallet; never run the opt-in checkpoints against a wallet you need.

Start with a fresh simulator or reset its Keychain before creating the reference wallet. Reusing a simulator after manually deleting only its seed can leave a server token for a previous test wallet. Do not reset the Keychain between the reference backup and the stored-seed restore.

## Unit and integration suites

Build and run with your own DerivedData and simulator:

```sh
xcodegen generate --spec apps/ios/project.yml
TEST_RUNNER_NOAH_REGTEST_SERVER_URL=http://127.0.0.1:3100 \
TEST_RUNNER_NOAH_BACKUP_IT=1 xcodebuild -project apps/ios/Noah.xcodeproj \
  -scheme Noah-Regtest -destination "platform=iOS Simulator,id=$NOAH_TEST_UDID" \
  -derivedDataPath apps/ios/build/DerivedData -parallel-testing-enabled NO test
```

`RecoveryPhraseInputTests`, `RestoreCompletionTests`, and `RestoreIssueTests` cover normalization, whole-phrase paste, excess/missing words, the bundled suggestions, BIP39 checksum validation, completion ordering, legacy outcomes, cleanup failures and safe error copy. `RestoreSettingsRecoveryTests` covers restore settlement when settings can't be written. `TEST_RUNNER_NOAH_BACKUP_IT=1` enables the S3 backup round trip.

## Funded restore round trip (opt-in checkpoints)

`RestoreLiveTests` holds the opt-in checkpoints for this procedure. It runs only with `NOAH_RESTORE_LIVE_CHECK=1`, refuses non-regtest variants and adds no app controls.

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

Each reinstall needs the server override on launch; `ServerEndpoint` does not persist it. A pass means each `verify` report matches the original byte-for-byte.

## Process-death recovery checks

`apps/ios/UpgradeTests/settings-restore-faults.sh` stages each restore-settings boundary, terminates the test host there, and checks recovery in a fresh process while preference writes are still denied. It drives `RestoreSettingsRecoveryTests/processDeathRestoreWithFailedSettings()`, which runs only while the script's control file exists.

Run `build-for-testing` first, because the script uses the generated `.xctestrun`:

```sh
xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$NOAH_TEST_UDID" \
  -derivedDataPath <your-derived-data> -parallel-testing-enabled NO build-for-testing
apps/ios/UpgradeTests/settings-restore-faults.sh "$NOAH_TEST_UDID" <your-derived-data>
```

It prints `<step>: process terminated, fresh-process recovery passed` for each checkpoint and stops at the first failure. These use isolated seed and database fixtures, not a funded restore. When filtering individual tests, keep the trailing `()` (or the Swift Testing parameter signature).
