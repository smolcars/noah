# Native backup acceptance

Two opt-in harnesses: funded native ↔ native backups, and real RN ↔ native backups
through S3. Both need an **already running, initialized** regtest stack and never
start, stop, recreate or reset Docker services. Bitcoin mining advances the shared
regtest chain.

## Funded native ↔ native backups

`FundedCrossPlatformBackupTests` (Swift) and `FundedCrossPlatformBackupTest` (Kotlin
instrumentation) exercise production wallet creation, receive/claim, sync, encrypted
local export, restore transaction, SDK validation/reopen and Ark send. Each source
receives 10,000 confirmed on-chain sats through `just bcli` and two Lightning payments
through `just lncli payinvoice --force --json` (the server uses CLN, so LND pays). The
other platform restores the unchanged encrypted file; every balance row, fingerprint,
movement count and projected history row must match after sync. Each restored wallet
then sends 1,000 Ark sats to a fresh Bark address through the app's send service.
Only the backup listing/download server is a fixture; no SDK, crypto, archive or
restore code is mocked.

Prerequisites: an Apple Silicon Mac with Xcode, XcodeGen and the host Android SDK
(`~/Library/Android/sdk`, API 35 Google APIs ARM64 image). Run from the repository root:

```sh
nix develop --command python3 apps/BackupTests/run.py
```

The runner creates its own uniquely named iPhone 17 simulator and AVD (console port
5618, refusing an occupied port), uses private DerivedData, and on exit checks device
identity, shuts down and deletes only its devices and build artifacts. Missing required
containers fail preflight. When LND has less than 80,000 sats of local channel balance,
it pays a fresh LND invoice from CLN over the existing channel so it can be rerun.

Phrases and encrypted exports travel only over loopback (Android uses an adb reverse
tunnel), stay in host memory, and are never written to evidence or printed. Public
JSON evidence goes to `/private/tmp/noah-funded-backup-evidence.json` (override with
`--evidence PATH`). Check the comparison logic without devices:

```sh
python3 apps/BackupTests/run.py --self-test
```

Do not rerun an individual spend after an uncertain outcome; inspect its movement and
the Bark receiver first. A full runner invocation always creates new wallets.

## Real RN ↔ native S3 acceptance

`rn_native_interop.py` (iOS) and `rn_native_android_interop.py` (Android) drive the
installed apps' Restore, Receive, Settings → Backup and Send UI with Maestro, including
RN's actual snapshot/archive/encryption/upload code. The server must already run with
real S3 credentials at **127.0.0.1:3100**; port 3000 is not used. Each source receives
20,000 Ark sats from Bark and 10,000 on-chain sats from Core; the target restores the
newer app-made backup, every fingerprint, movement, balance and on-chain history row
must survive, and the target then sends 5,000 Ark sats. Backups are inspected with
`scripts/decrypt_backup.js`, with the phrase supplied in memory.

Setup first uploads an empty bootstrap backup for each public BIP39 vector, because
both Restore flows need an existing backup; only the source app restores it, so it is
never an interop result. `InteropSetup.swift` is compiled against the pinned SDK's
macOS binary for setup, authenticated server inspection and reading **copies** of app
databases. Phrases never appear in command arguments or evidence.

### iOS

Prepare RN dependencies with `bun install --frozen-lockfile`. Save the tracked
`client/ios/Podfile.lock` outside the checkout, run `nix develop --command just
ios-prebuild`, keep the generated lock until the RN build finishes, then restore the
original. Use private DerivedData paths:

```sh
# From client/, inside Nix; Release embeds JS and needs no Metro server.
SENTRY_DISABLE_AUTO_UPLOAD=true EXPO_PUBLIC_REGTEST_URL=127.0.0.1 \
EXPO_PUBLIC_REGTEST_SERVER_URL=http://localhost:3100 \
xcodebuild build -workspace ios/noah.xcworkspace -scheme Noah-Regtest \
  -configuration Release -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath <private>/rn-dd \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -jobs 2

# From apps/ios/, inside Nix (xcodegen must be on PATH).
xcodegen generate
xcodebuild build -project Noah.xcodeproj -scheme Noah-Regtest \
  -configuration Debug-Regtest -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath <private>/native-dd -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  NOAH_REGTEST_SERVER_URL=http://localhost:3100

# From the repo root. The native DD supplies the exact pinned SDK artifacts.
nix develop --command python3 apps/BackupTests/rn_native_interop.py \
  --rn-app <private>/rn-dd/Build/Products/Release-iphonesimulator/Noah-Regtest.app \
  --native-app <private>/native-dd/Build/Products/Debug-Regtest-iphonesimulator/Noah.app \
  --native-derived-data <private>/native-dd
```

The runner creates four uniquely named iPhone 17 simulators and removes them and its
private artifacts even on failure. Caller-supplied apps and DerivedData are kept;
remove your own build directory afterwards. Public evidence defaults to
`/private/tmp/noah-rn-native-interop-evidence.json` (`--evidence` overrides it).

### Android

Build the RN release from `client/android/` inside Nix with `CI_DEBUG_SIGNING=true`,
`SENTRY_DISABLE_AUTO_UPLOAD=true`, `EXPO_PUBLIC_REGTEST_URL=10.0.2.2` and
`EXPO_PUBLIC_REGTEST_SERVER_URL=http://10.0.2.2:3100`, using
`./gradlew --max-workers=2 --no-daemon :app:assembleRegtestRelease
-PreactNativeArchitectures=arm64-v8a`. An unsigned regtest APK can instead be signed
into the private build directory with the public Android debug keystore. Build native
from `apps/android/` with `./gradlew --max-workers=2 --no-daemon
-PnoahRegtestServerURL=http://10.0.2.2:3100 :app:assembleRegtestDebug`. Then, with the
**host** Android SDK in `ANDROID_HOME`:

```sh
nix develop --command python3 apps/BackupTests/rn_native_android_interop.py \
  --rn-apk <private>/rn-regtest.apk \
  --native-apk apps/android/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk \
  --native-derived-data <private>/native-dd --port 5620
```

The runner creates and deletes a separate Pixel 7 AVD for each source and target
(`system-images;android-35;google_apis;arm64-v8a`, an explicit even port ≥5592,
`-no-window -no-snapshot`). Root is used only on these owned emulators to copy the
stopped app's SQLite/WAL pair; seed, Keystore and auth token files are never copied.
Public evidence defaults to `/private/tmp/noah-rn-native-android-evidence.json`.

### Safety

Keep Device-panel streaming closed during phrase input; clipboard text is pasted
through the system Paste menu. Do not publish Maestro debug artifacts; the runners
remove them. Both runners refuse to overwrite existing server backups for their fixed
public vectors, so reproducing needs an independently reset, initialized test
environment; they never reset it themselves. Do not repeat an uncertain payment
manually. To check the evidence comparisons without devices:

```sh
python3 apps/BackupTests/rn_native_interop.py --self-test
python3 apps/BackupTests/run.py --self-test
```
