# Android in-place upgrade checks

The settings policy was replaced on **2026-10-20**. Seed + wallet database opens
Home with defaults when RN settings are absent or unreadable; only an explicitly
false initialized flag resumes onboarding. Seed corruption/conflicts still fail
closed. Read [STORES.md](STORES.md) for the native format, snapshot-only importer,
Home notices, deletion, background admission and recurring reset contract.

Run the JVM/build check from the repository root inside Nix:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Build `:app:assembleRegtestDebugAndroidTest`, install both APKs on an owned,
disposable emulator, then run the real JNI storage and upgrade assertions:

```sh
ANDROID_SERIAL=emulator-5596 adb -s emulator-5596 shell am instrument -w -r \
  -e class com.noahwallet.services.MMKVRecoveryTest,com.noahwallet.recurring.RecurringStorageDeviceTest,com.noahwallet.services.UpgradeCompatibilityTest \
  -e noahRecurringStorage disposable-emulator \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

The prepared RN upgrade assertion is opt-in (`noahUpgradeCheck=1`) and requires
the fingerprint/movement/Expo expectations supplied by `run.sh`. Without that
sandbox it visibly skips. Its funded assertions now require an empty native
recurring store and a persisted recurring notice. `MMKVRecoveryTest` independently
runs the actual immutable-snapshot importer against the committed RN binary
fixture and generated MMKV records, including 2,000 native writes with unchanged
RN data/CRC bytes. It needs no funded wallet, Docker or network.

`run.sh` performs the full RN → native install without uninstalling the wallet.
It defaults to replacement while RN is writing (`INSTALL_WHILE_RUNNING=1`);
`=0` retains the stopped baseline. Both modes remain acceptance checks. Prior
physical-device results and current isolated-device evidence are in
[DEVICE.md](DEVICE.md); they do not imply live funded/push acceptance for this pass.
