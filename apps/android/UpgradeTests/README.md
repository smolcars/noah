# Android in-place upgrade checks

`run.sh` installs the native app over a real RN regtest release (same
`com.noahwallet.regtest` sandbox and signing certificate, never uninstalled or
cleared) on a fresh emulator. It then checks the wallet, settings, live RN MMKV
bytes, notices, server sync, Expo identity, widget and launcher entries.
[STORES.md](STORES.md) describes the RN stores and the settings upgrade policy the
checks expect. [DEVICE.md](DEVICE.md) is the physical-phone procedure.

## Storage checks without an RN install

JVM tests and the build, from the repository root:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Build `:app:assembleRegtestDebugAndroidTest`, install both APKs on a disposable
emulator you created, then run the real JNI storage assertions:

```sh
adb -s YOUR_SERIAL shell am instrument -w -r \
  -e class com.noahwallet.services.MMKVRecoveryTest,com.noahwallet.recurring.RecurringStorageDeviceTest,com.noahwallet.services.UpgradeCompatibilityTest \
  -e noahRecurringStorage disposable-emulator \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

`MMKVRecoveryTest` runs the immutable-snapshot importer against the committed RN
binary fixture (`apps/fixtures/rn-mmkv/`) and generated MMKV records, including
2,000 native writes with unchanged RN data/CRC bytes. It needs no funded wallet,
Docker or network. `UpgradeCompatibilityTest` is opt-in (`noahUpgradeCheck=1`)
and needs the expectations `run.sh` supplies, so here it skips.

## Full RN → native replacement

Prerequisites:

- A fresh, root-capable Google APIs emulator with Google Play services, on an
  explicit console port of 5590 or higher, booted with
  `-grpc <unused-port> -grpc-use-token` (`clipboard.py` uses the emulator's gRPC
  clipboard). `grpcurl` and Maestro on `PATH`. The runner refuses an emulator that
  already has the regtest app.
- `ANDROID_HOME` set (`apksigner` from build-tools 37.0.0, or set `APKSIGNER`).
- The regtest stack running: host ports 18443, 3535 and 3000 must answer.
- At least 12 GiB of free disk.
- Optionally a prebuilt RN regtest release APK. Without one, the runner runs
  `bun install --frozen-lockfile` and builds it with debug signing.

Run from the repository root, inside Nix:

```sh
FUNDED=1 INSTALL_WHILE_RUNNING=0 apps/android/UpgradeTests/run.sh emulator-5594 [RN-regtest-release.apk]
FUNDED=1 INSTALL_WHILE_RUNNING=1 apps/android/UpgradeTests/run.sh emulator-5594 [RN-regtest-release.apk]
```

Run both modes, each on a fresh emulator, for the full acceptance check.

- `FUNDED=1`: through RN's UI, receives 100,000 Ark sats from Bark and sends 5,000
  back, creates a recurring rule, changes settings and language (Swedish), then
  receives 30,000 onchain sats and mines one block. Without it, the runner only
  creates a wallet. Either way it places an RN widget and a pinned shortcut.
- `INSTALL_WHILE_RUNNING=1` (default): replaces RN while it is writing MMKV. `=0`
  replaces a stopped RN and also requires unchanged MMKV bytes across installation.
- `UPGRADE_EVIDENCE` (default `/private/tmp/noah-android-upgrade-evidence.json`):
  where the result JSON is copied.

RN skips push registration on emulators, so the runner provisions a real Expo token
through RN's own runtime (`RNPushBaseline`) and records only its hash. Maestro
artifacts stay in a private temporary directory that is removed on exit; they may
contain the recovery screen, so never upload them.

## Reading the results

The runner stops at the first failed check. On success it prints `Upgrade
compatibility assertions passed (wallet, settings, notices, MMKV, server sync, Expo
and launcher)` and the elapsed time. It requires:

- matching RN/native signing certificates;
- the same fingerprint, movement rows, balances and database inode;
- byte-identical live RN MMKV data/CRC files through native launch, and a matching
  immutable snapshot;
- `UpgradeCompatibilityTest` reporting `OK (1 test)`: settings match RN, the native
  recurring store is empty and its notice recorded, and the Expo ID and token hashes
  are unchanged;
- unchanged placed widget and launcher entries (`check-launcher.py`; its own tests
  are `python3 apps/android/UpgradeTests/test-launcher.py`);
- for `FUNDED=1`, a successful empty recurring sync in the server log for this
  wallet after replacement (`apps/ios/UpgradeTests/check-server-sync.py`).

The result JSON holds no seed, auth token or push token.

## Process-death restore checks

`settings-restore-faults.sh` kills the app at each restore-settings boundary and
checks recovery in a fresh process. See
[Restore tests](../RestoreTests/README.md#process-death-recovery-checks).
