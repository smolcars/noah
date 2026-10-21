# Funded native backup acceptance

Run from the repository root on an Apple Silicon Mac with Xcode 27, XcodeGen,
the host Android SDK (`~/Library/Android/sdk`, API 35 Google APIs ARM64 image),
and an **already running, initialized** regtest stack:

```sh
nix develop --command python3 apps/BackupTests/run.py
```

The runner creates a uniquely named iPhone 17/iOS 27.0 simulator and AVD, using
console port **5618** with `-no-window -no-snapshot`. It refuses an occupied port.
It uses two Xcode jobs, Gradle `--max-workers=2 --no-daemon`, and private temporary
DerivedData/result bundles. On exit it checks device identity, shuts down,
confirms shutdown, deletes only its devices, and removes private build artifacts.
It never starts, stops, recreates or resets Docker services. Missing required
containers fail preflight. Bitcoin mining advances the shared regtest chain.
When LND has less than 80,000 sats of local channel balance, the runner pays a
fresh 80,000-sat LND invoice from CLN over the existing channel so it can be rerun.

`FundedCrossPlatformBackupTests` (Swift) and `FundedCrossPlatformBackupTest`
(Kotlin instrumentation) exercise production wallet creation, receive/claim,
sync, encrypted local export, restore transaction, SDK validation/reopen and Ark
send paths. Each source receives **10,000 confirmed onchain sats** through
`just bcli` and two Lightning payments (**31,000 + 7,000 sats**) through
`just lncli payinvoice --force --json` (the server uses CLN, so LND is the payer).
Receive fees are reflected in the recorded spendable balance.
The source closes before the other platform restores its unchanged encrypted
file. Every balance row, fingerprint, movement count and projected history row
(ID, amount, type, direction, movement status/kind/fee) must match after sync.
Before-sync state is recorded separately. Each restored wallet sends **1,000 Ark
sats** to a fresh Bark address through the app's send service; sender accounting,
new successful movement, unchanged onchain funds and receiver history are checked.

Only the backup listing/download server is a fixture; no SDK, VTXO, database,
crypto, archive or filesystem restore implementation is mocked. This is service
acceptance inside the regtest apps, not a tap-through onboarding/share-sheet test
or S3/authentication test. Test wallets use separate datadirs; iOS uses a unique
test Keychain item and Android a unique Android Keystore alias and real atomic
encrypted seed file. Device deletion removes all
remaining test data. No production, `client/` or `server/` code is changed.

Phrases and encrypted exports travel only over loopback (Android uses an adb
reverse tunnel), remain in host memory, and are never written to evidence or
printed. No screenshots are taken. Public JSON evidence defaults to
`/private/tmp/noah-funded-backup-evidence.json`; override with `--evidence PATH`.
The comparison checker has an assert-based negative self-check:

```sh
python3 apps/BackupTests/run.py --self-test
```

Do not rerun an individual spend after an uncertain outcome. Inspect its movement
and Bark receiver state first. A full runner invocation always creates new wallets.

## Real RN ↔ native S3 acceptance

`rn_native_interop.py` and `rn_native_android_interop.py` exercise the **installed
apps' Restore, Receive, Settings → Backup, and Send UI with Maestro**, including
RN's actual Nitro snapshot/archive/encryption/upload implementation. The server
must already be running with real S3 credentials at **127.0.0.1:3100**. The runners
never manage its process or the Docker stack. Port 3000 is not used.

Both Restore flows require an existing backup. Test setup therefore creates a
fresh, empty SDK wallet for each public BIP39 vector, snapshots it in format v2,
and uploads that empty bootstrap to the real server/S3. **Only the source app
restores the bootstrap.** After funding and Backup Now, the target app must
restore the newer **app-made** object. Its ID, timestamp, encrypted size and
SHA-256 are checked against the server list immediately before target restore;
the downloaded snapshot must match the funded source. The bootstrap cannot
satisfy these comparisons because all fourteen balances and both histories are
empty. Bootstrap setup is not an interoperability result.

The public vectors are reconstructed from the repository's English BIP39 list
and known test-vector entropy; phrases never appear in command arguments or
evidence. A/B are independent iOS directions; C/D are independent Android
directions. `InteropSetup.swift` is compiled against the pinned SDK's **macOS**
binary solely for setup, authenticated server inspection, and opening **copies**
of app databases for balance/history evidence. It does not create a funded
interop backup or install one into a destination app. No backup endpoint, upload,
download, crypto, archive, SDK open, or restore path is mocked.

Each source receives 20,000 Ark sats from Bark and 10,000 onchain sats from Core,
with exactly one mined block. Every fingerprint, raw movement row, all fourteen
SDK balance rows, and confirmed onchain history row must survive target restore.
The target then sends 5,000 Ark sats through the existing app send flow; exact
debit, zero fee, old history, unchanged onchain balances and Bark receipt are
checked. Backups are downloaded from an AWS S3 origin and inspected with the
unchanged `scripts/decrypt_backup.js` CLI, with the phrase supplied in memory.
The evidence records ZIP entry order, sizes, compression, flags, extra fields,
manifest and snapshot state. No encrypted or plaintext wallet file is committed.

Prepare RN dependencies with `bun install --frozen-lockfile`. Save the original
tracked `client/ios/Podfile.lock` outside the checkout, run `nix develop --command
just ios-prebuild`, and retain the generated lock matching `Pods/Manifest.lock`
until the RN iOS build finishes. Restore the original tracked lock afterwards.
Use private DerivedData paths for both builds:

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

The iOS runner creates four uniquely named **iPhone 17 / iOS 27.0** simulators,
checks identity before shutdown/deletion, and removes them and private runner
artifacts even on failure. Caller-supplied apps/DerivedData are retained; remove
your own build directory after testing. Default public evidence is
`/private/tmp/noah-rn-native-interop-evidence.json` (`--evidence` overrides it).

For Android, build the RN release with `CI_DEBUG_SIGNING=true`,
`SENTRY_DISABLE_AUTO_UPLOAD=true`, `EXPO_PUBLIC_REGTEST_URL=10.0.2.2` and
`EXPO_PUBLIC_REGTEST_SERVER_URL=http://10.0.2.2:3100`, using
`./gradlew --max-workers=2 --no-daemon :app:assembleRegtestRelease
-PreactNativeArchitectures=arm64-v8a` from `client/android/` inside Nix. An
unsigned regtest APK can instead be signed into the private build directory with
the existing public Android debug keystore. Build native with
`./gradlew --max-workers=2 --no-daemon
-PnoahRegtestServerURL=http://10.0.2.2:3100 :app:assembleRegtestDebug` from
`apps/android/`. Then, with the **host** Android SDK in `ANDROID_HOME`:

```sh
nix develop --command python3 apps/BackupTests/rn_native_android_interop.py \
  --rn-apk <private>/rn-regtest.apk \
  --native-apk apps/android/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk \
  --native-derived-data <private>/native-dd --port 5620
```

The Android runner creates/deletes a separate Pixel 7 AVD for each source and
target, using `system-images;android-35;google_apis;arm64-v8a`, an explicit even
port >=5592, token-protected loopback gRPC clipboard, `-no-window -no-snapshot`,
two cores and 2 GiB RAM. It verifies identity and shutdown before deletion. Root
is used only on these owned Google APIs emulators to copy the stopped app's
SQLite/WAL pair; seed, Keystore, auth token and MMKV files are not copied to the
host. Default public evidence is `/private/tmp/noah-rn-native-android-evidence.json`.

Keep Device-panel streaming closed during phrase input. Clipboard text is pasted
through the system Paste menu, not an automation text-entry command. RN's iOS
restore form groups its accessibility children, so its test uses the fixed
portrait device layout. A failed restore is stopped before subsequent UI
capture. Do not publish Maestro debug artifacts; the runners remove them.

Both runners refuse to overwrite existing server backups for their fixed public
vectors. Reproduction requires an independently reset, initialized test
environment; the runners will never reset it themselves. Do not repeat an
uncertain payment manually. To check the evidence comparisons without devices:

```sh
python3 apps/BackupTests/rn_native_interop.py --self-test
python3 apps/BackupTests/run.py --self-test
```
