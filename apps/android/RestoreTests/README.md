# Android restore checks

These cover Welcome → Restore Wallet (12 numbered words, local BIP39 suggestions,
whole-phrase paste, normalization and checksum validation), the restore engine's
steps and error messages, the durable completion receipt, the seed-only "Recovery
phrase found" launch, and the `FLAG_SECURE` shield across the restore screens.

## JVM tests

From the repository root:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

The classes in `RestoreUITest.kt` (`RecoveryPhraseInputTest`, `RestoreIssueTest`,
`RestoreCompletionTest`, `RestoreFlowTest`, `RestoreClipboardTest`) mirror iOS
`apps/ios/NoahTests/RestoreUITests.swift`: normalization,
paste lengths, suggestions, dictionary/count/checksum validation, every issue reason,
completion ordering, legacy and missing-ID outcomes, cleanup failures and failed
state writes. `RestoreTransactionTest`, `WalletRestorerTest` and `SecureWindowTest`
(the secure-window count handed over across recreation) cover the rest.
`apps/fixtures/restore-phrase.json` holds only public BIP39 vectors.

## Device tests

Use an emulator you created for this run, never a user's wallet or a shared
emulator. The opt-in classes create and wipe their own throwaway wallet. Build and
install, pointing the app at a Noah server with S3 configured:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 -PnoahRegtestServerURL=http://10.0.2.2:3100 :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest'
adb -s YOUR_SERIAL install -r apps/android/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk
adb -s YOUR_SERIAL install -r apps/android/app/build/outputs/apk/androidTest/regtest/debug/app-regtest-debug-androidTest.apk
adb -s YOUR_SERIAL shell am instrument -w \
  -e noahRestoreUIIT disposable-emulator \
  -e class com.noahwallet.services.RestoreUIDeviceTest \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

`RestoreUIDeviceTest` (opt-in `-e noahRestoreUIIT disposable-emulator`):

- `liveBackupWipePasteRestoreAndMissingBackup`: uploads a real encrypted backup,
  wipes local data, checks the missing-backup error for an unrelated phrase, then
  restores by paste and from the stored seed, and compares fingerprint, balances
  and movements. It removes its server backups and local wallet at the end. It needs
  a server that can store backups.
- `interruptedJournalAndWaitingSecretNeverOfferCreation`: an unreadable journal and
  a committing journal with an unreadable secret both hide Create Wallet.
- `seedOnlyLaunchCanConfirmDeletionAndStartOver`: the seed-only screen's confirmed
  Delete Wallet returns to Welcome.

For a funded restore, add `-e noahRestoreFunded 1`. Read the public Ark address
with `adb -s YOUR_SERIAL shell run-as com.noahwallet.regtest cat files/restore-funding-address.txt`,
pay it once with `just bark send '<address>' '12000 sats'`, and let the test
continue. It waits up to three minutes for the funds.

Other opt-in classes, run the same way with `-e class com.noahwallet.ui.<Class>`:

- `RestoreShieldDeviceTest` (`-e noahRestoreShield disposable-emulator`): samples
  `FLAG_SECURE` through Restoring, a recreation and the failure return, on both the
  stored-phrase and typed-phrase paths. Pass means zero unshielded samples. Its
  hosted cases run without the flag.
- `RecoveryPhraseShieldDeviceTest` (`-e noahPhraseShield disposable-emulator`):
  creates a throwaway wallet, reveals Settings' recovery phrase and rotates; the
  words must stay hidden.
- `StoredSeedShotsTest` (`-e noahStoredSeedShots <label>`): captures the suspended,
  idle, restoring and error states of "Recovery phrase found". Build with
  `-PnoahRegtestServerURL=http://10.0.2.2:3199`, a host port that accepts
  connections and never answers, so Restore holds and then fails. Copy a capture
  with `adb exec-out run-as com.noahwallet.regtest cat files/stored-seed-shots/<label>/idle.png > idle.png`.

No recovery phrase appears in shell arguments, runner output, screenshots or saved
accessibility trees: the tests inject words directly into the private fields.
`platformWordFieldsAreVisibleAndKeepTheKeyboardPrivate` and
`interruptedJournalAndWaitingSecretNeverOfferCreation` are known to fail for
reasons unrelated to restore (a single-line field transformation, and changed lock
copy).

## Process-death recovery checks

`apps/android/UpgradeTests/settings-restore-faults.sh` stages each restore-settings
boundary of `SettingsFailureTest#interruptedRestoreSettlesWhileSettingsFailAtEveryBoundary`,
force-stops the app there, and checks recovery in a fresh process. Install both
APKs first. The script requires `ANDROID_SERIAL=emulator-5610` and the name of the
AVD you created on that port:

```sh
ANDROID_SERIAL=emulator-5610 apps/android/UpgradeTests/settings-restore-faults.sh <your-avd-name>
```

It prints `<step>: process killed, fresh-process recovery passed` for each
checkpoint and stops at the first failure. These are storage fixtures and process
termination tests, not funded restores. Settings lifecycle device checks are in
[SettingsTests](../SettingsTests/README.md).
