# Noah native Android

Kotlin/Compose `:app` with Navigation 3, onboarding, Home and Receive. Launch
opens through the process-wide `WalletSession.shared(context)` and resumes unfinished
onboarding at the recovery phrase. It never creates a wallet at launch or deletes
legacy secrets. Fresh creation derives key 0 before server registration.

Onboarding includes the beta warning, secure recovery phrase, emergency email and
Lightning identity. Restore UI remains a placeholder; the Android restore engine is implemented below.
Home uses the full wallet balance and RN-compatible unit/privacy settings. Receive
supports BIP-321 QR, amount/note editing, fiat, copy/share and Ark/Lightning completion.
Notification permission and the existing push layer run after onboarding.

## Build and install

Use JDK 17 and an Android SDK with platform 37 and build-tools 36.0.0 or newer.
Set `ANDROID_HOME` (or `sdk.dir` in the ignored `local.properties`). The repo's
`nix develop` shell provides JDK 17 and the SDK; no newer JDK is required.
The committed wrapper uses Gradle 9.6.1 with its distribution checksum pinned.

From `apps/android/`:

```sh
./gradlew :app:assembleRegtestDebug :app:assembleMainnetDebug :app:assembleSignetDebug :app:testRegtestDebugUnitTest

./gradlew :app:installRegtestDebug
./gradlew :app:installMainnetDebug
./gradlew :app:installSignetDebug
```

Or, from the repository root, wrap any command in Nix:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew :app:assembleRegtestDebug :app:testRegtestDebugUnitTest'
```

APKs are under `app/build/outputs/apk/<flavor>/debug/`. To explicitly replace an
installed debug app without clearing its data:

```sh
adb install -r app/build/outputs/apk/regtest/debug/app-regtest-debug.apk
adb shell am start -W -n com.noahwallet.regtest/com.noahwallet.MainActivity
```

Substitute `mainnet` or `signet` in both paths and package names for the other
variants. Test reports are in `app/build/reports/tests/testRegtestDebugUnitTest/`.
The JUnit resource source set includes `apps/fixtures/`; tests never depend on the
current working directory. See [the shared format](../fixtures/README.md).

## Upgrade identities and signing

- Namespace and launcher: `com.noahwallet`, `com.noahwallet.MainActivity`
  (exported, MAIN/LAUNCHER, `singleTask`).
- `version` flavors: `mainnet` → `com.noahwallet.mainnet` / Noah;
  `signet` → `com.noahwallet.signet` / Noah (Signet);
  `regtest` → `com.noahwallet.regtest` / Noah (Regtest).
- Version code **100** deliberately exceeds RN's **37**. Version name is **0.2.0**
  with `-signet` / `-regtest` suffixes. Release managers must increase the code
  beyond the latest published RN/native build before shipping and adjust the name.
- Debug signing references `../../client/android/app/debug.keystore` relative to
  the Android project root, alias `androiddebugkey`, store/key passwords `android`.
  It does not generate or commit another keystore.
- Release signing reads `MYAPP_RELEASE_STORE_FILE`, `MYAPP_RELEASE_STORE_PASSWORD`,
  `MYAPP_RELEASE_KEY_ALIAS`, `MYAPP_RELEASE_KEY_PASSWORD` from
  `apps/android/local.properties`. Use the same release key as RN. Relative store
  paths resolve from `apps/android/app/` (RN resolves from `client/android/app/`);
  prefer an absolute path when reusing existing configuration.
- As in RN, complete release properties plus an existing keystore select release
  signing. Otherwise `CI_DEBUG_SIGNING=true` allows debug signing for non-mainnet
  releases; without it they remain unsigned. Mainnet release tasks fail without
  valid release signing even when `CI_DEBUG_SIGNING=true`.

**Never uninstall, clear app data, or switch signing keys during upgrade tests.**
Android requires matching package ID and signing certificate to replace an app.
The old app's private files, Keystore entries and SharedPreferences remain in the
same sandbox. The compatibility layer reads the existing wallet DB path and MMKV settings,
and copies a readable legacy seed into a verified native secret store. The shell
alone is not a usable replacement wallet; payment UI remains future work.

Launcher artwork and widget Kotlin/layout/drawable/provider XML files are copied
from `client/android/app/src/main/`. Widget classes remain
`com.noahwallet.widgets.NoahWidget{Mainnet,Signet,Regtest}Provider`; flavor manifests
remove the other two receivers exactly as RN does. The `noah_widget_*_info` names,
`com.noahwallet.<variant>` preference files, balance/expiry keys and widget update
broadcast remain unchanged. Existing widgets can read their cached balances.
Flavor `google-services.json` files are copied unchanged. Notification channel IDs
`default` and `noah-push-default` are recorded in `NotificationChannels.kt`.

## Dependencies and SDK integrity

Versions are pinned in `gradle/libs.versions.toml`, checked against
[Google Maven](https://dl.google.com/dl/android/maven2/index.html) and
[Maven Central](https://repo.maven.apache.org/maven2/) on 2026-10-19:

- AGP 9.4.1, Kotlin/Compose compiler/serialization plugin 2.4.21.
  AGP's built-in Kotlin support is used (no legacy Kotlin Android plugin).
- Compose BOM 2026.09.00, stable Material 3 1.4.0, Navigation 3 1.2.0,
  Activity Compose 1.13.0, Lifecycle runtime/viewmodel-compose 2.11.0.
- kotlinx.serialization 1.11.0, coroutines 1.11.0, OkHttp 5.5.0.
- Tencent MMKV 2.4.3, preserving the RN MMKV 2.4.x core line.
- Firebase BOM 35.0.2 / Messaging, google-services plugin 4.5.0.
- UnifiedPush connector **3.1.2**, deliberately matching RN rather than upgrading
  its persisted registration database in this scaffold.
- JUnit 4.13.2; minimum Android API 24, compile/target API 37 (Android 17).

[AGP 9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
requires Gradle 9.6.0+ and JDK 17.

`io.github.niteshbalusu11:bark-bolt-ffi-android:0.2.0@aar` resolves directly from
[the v0.2.0 GitHub release](https://github.com/smolcars/bark-bolt-ffi/releases/tag/v0.2.0).
An exclusive Ivy repository filters to `io.github.niteshbalusu11` and uses
`https://github.com/smolcars/bark-bolt-ffi/releases/download/v[revision]/[module]-[revision].[ext]`.
No AAR is committed. Artifact-only Ivy metadata does not import the release POM,
so coroutines-core is declared explicitly.

`gradle/verification-metadata.xml` pins the AAR's SHA-256 from the release
[`SHA256SUMS`](https://github.com/smolcars/bark-bolt-ffi/releases/download/v0.2.0/SHA256SUMS):

```text
726bc3271c7057090064d191b6070b62ef6d01a1ffd632c3b68d34be59489932
```

Gradle's default strict verification rejects a mismatched AAR. Verification is
currently scoped to this release group; other Maven/plugin dependencies are
explicitly trusted, not checksum-audited. When upgrading the SDK, independently
compare the downloaded artifact with the new release's `SHA256SUMS` before
changing the catalog and verification entry. `BarkSdkLinkTest` references the
SDK's `Config` class and proves the AAR is on the compiled test classpath; it does
not execute Android JNI on the host JVM or create/log a mnemonic.

## Wallet layer

`WalletConfiguration` mirrors iOS and RN, with `10.0.2.2` for the Android emulator's
regtest host and explicit 10,000 sat/kvB → 2,500 sat/kwu conversion.
`WalletDatabase` opens SQLite read-only to reject missing/non-bark/wrong-network
files before the SDK can run migrations. `WalletSession.shared(context)` owns both
native handles; concurrent `ensureOpen` calls share one attempt. Canceling a UI
waiter does not cancel that attempt. Native calls are counted; close stops observers and drains those calls.
Kotlin explicitly closes both handles, including the onchain handle on open failure.

`WalletCreator` resumes existing data or saves a new seed before creating files.
Any nonempty datadir counts as existing data. Cleanup is limited to a failed fresh
attempt in an absent/empty datadir; a failed close or cleanup keeps the seed.
Launch never creates a wallet or deletes an existing seed; it settles interrupted restores before reading launch state.

`WalletBalance` uses bark's accounting and the shared balance fixture. The three
balance buckets missing from SDK 0.2.0 remain zero, matching iOS.
`BitcoinAmount` uses Android ICU; `BitcoinAmountDeviceTest` checks all 70 shared
cases on the device, including Spanish grouping and Swedish no-break spaces.
API 30+ uses `NumberFormatter`'s locale defaults; API 24–29 uses the legacy ICU
formatter with the Spanish four-digit grouping adjustment. The device fixture
check exercises both paths (an actual API 24–29 OS run remains outstanding).
`AppLog` redacts mnemonic-shaped text before logcat/file writes and preserves
RN's `cacheDir/noah_logs/noah.log` rotation (four old 512 KiB segments, 4,000 exported lines).

## Upgrade compatibility and secret storage

`services/storage/KeychainPassword.kt` reads RN secrets without migrating or
removing their original records. Precedence is DataStore `RN_KEYCHAIN`, legacy
SharedPreferences `RN_KEYCHAIN`, then (mnemonic only) `noah_native_secrets`.
Only complete absence permits fallback. Partial records, unsupported ciphers,
missing keys, malformed UTF-8 and decryption failures return `Unavailable`.
`Found.toString()` redacts its value; no provider exception text is retained.
The auth-token reader uses the same RN formats but not the mnemonic fallback.

`SeedStorage.read()` first reads the native store. On a successful legacy read it attempts a verified native copy. If that copy
fails it still returns the legacy seed and retries migration next time. Migration is serialized across instances. An unreadable native record blocks
fallback and overwrite. Legacy records are kept even after successful migration.

The native store uses alias `noah.native.seed.<variant>`, AES-256-GCM, no user
authentication, and a fresh provider-generated 12-byte nonce. Its version-1 file
is `[version:1][nonce:12][ciphertext][tag:16]`, atomically written to
`noBackupFilesDir/noah-native-seed-<variant>.bin`. Android excludes that directory
from cloud backup and device transfer. A read never generates a key. Explicit
writes refuse to replace an unreadable file/key. This is single-process storage;
future background services must remain in the app process or add process locking.

Launch states match `apps/fixtures/launch-state.json`. The existing wallet shell
requires both a readable nonempty seed and `filesDir/noah-data-<variant>/db.sqlite`.
The other states show the same placeholders as iOS and never create/delete data.
Secure-storage and wallet-open failures are retried when the activity resumes.
The shell is displayed only after the existing wallet opens successfully.

## Verified RN source facts

Paths below are relative to the repository root. Dependency sources were checked
both in the main checkout and after `bun install --frozen-lockfile` in this worktree.

- `client/src/constants.ts:17` and `:19`: services
  `com.noah.mnemonic.<variant>`, `com.noah.auth-token.<variant>`; account `noah`.
  `client/src/lib/crypto.ts:76` writes that account through `setGenericPassword`.
- `node_modules/react-native-keychain/android/src/main/java/com/oblador/keychain/DataStorePrefsStorage.kt:29`
  names the Preferences DataStore `RN_KEYCHAIN`; `:37` imports legacy preferences;
  `:73` writes Base64.DEFAULT username/password. `PrefsStorageBase.kt:32` defines
  `<service>:u`, `:p`, `:c`. AndroidX 1.1.1
  `PreferenceDataStoreFile.android.kt:37` supplies `files/datastore/<name>.preferences_pb`.
  Our reader deliberately omits the destructive SharedPreferences migration.
- In that same keychain package, `cipherStorage/CipherStorageBase.kt:54` selects
  UTF-8 and `:57` keeps a nonempty service as the alias.
  `CipherStorageKeystoreAesGcm.kt:43`, `:46`, `:215` specify AES-256/GCM/NoPadding,
  a 12-byte IV prefix and a 128-bit tag; `:156` only requires authentication for
  the authenticated cipher. `CipherStorageKeystoreAesCbc.kt:40`, `:43`, `:217`
  specify AES-256/CBC/PKCS7Padding and a 16-byte IV prefix. The reader dispatches
  on `KeystoreAESGCM_NoAuth` / `KeystoreAESCBC`, validates the decrypted username,
  and never calls RN's key-generation-on-read implementation. AndroidKeyStore requires the exact `PKCS7Padding` transformation. A device
  regression test caught the JVM-compatible `PKCS5Padding` name failing with a
  real CBC Keystore key; the JVM-only test adapter maps the equivalent padding name.
- `client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahTools.kt:336`:
  fallback file `noah_native_secrets`, `mnemonic_<variant>`, `MasterKeys.AES256_GCM_SPEC`,
  AES256_SIV preference keys and AES256_GCM values. AndroidX Security 1.1.0
  `MasterKeys.java:58` exposes the default alias through the spec; the alias is
  `_androidx_security_master_key_`. Both encrypted keysets and the existing master
  key are required before opening the fallback, preventing reader-side creation.
- `client/src/lib/mmkv.ts:5` and `:16` select `filesDir/mmkv`, instance
  `noah-wallet-storage`. `node_modules/react-native-fs-turbo/android/src/main/java/com/cmpayc/rnfsturbo/RNFSTurboModule.java:88`
  maps DocumentDirectoryPath to `context.getFilesDir()`.
  `node_modules/react-native-mmkv/cpp/HybridMMKV.cpp:22`, `:24`, `:249` confirm no
  encryption, the explicit root, and default single-process mode. Native keeps the
  `{state, version}` JSON envelopes and all seven optional wallet-state fields.
- `node_modules/expo-notifications/android/src/main/java/expo/modules/notifications/serverregistration/InstallationId.java:53`,
  `:62`, `:80`: primary `noBackupFilesDir/expo_notifications_installation_uuid.txt`,
  then preferences `host.exp.exponent.SharedPreferences` / `uuid`, then no-backup
  `expo_installation_uuid.txt`. Lines `95`, `114`, `125` define generation,
  first-line UUID parsing and persistence. The Kotlin port preserves this order,
  session caching and legacy-preference removal only after a successful write.
- `node_modules/gt-react-native/android/src/main/java/com/gtreactnative/GtReactNativeModuleImpl.kt:47`
  reads SharedPreferences `gt_store`;
  `node_modules/gt-i18n/dist/internal-cookies.d.cts:5` defines `generaltranslation.locale`.
- `client/src/constants.ts:43` defines the per-variant `noah-data-<variant>` path.
  `NoahToolsBackup.kt:317`, `:342`, `:368`, `:404` (beside NoahTools.kt above) define
  the v1/v2 envelope, AES-GCM with no AAD and PBKDF2-HMAC-SHA256, 600,000 rounds,
  32-byte key. Both versions use `[version][salt16][nonce12][ciphertext][tag16]`.

## Tests

The required three-variant build and JVM suite are shown above. JVM tests cover
both RN ciphers and preference locations, fallback/error precedence, missing
keys, write/read-back failures, corruption, migration serialization, shared launch
fixtures and the backup vectors in both directions. Existing shared fixtures are
unchanged; `backup-crypto-unicode.json` adds an independently generated UTF-8 vector.

Android's PBKDF2-SHA256 factory is guaranteed only from API 26, so API 24/25 use
RFC 8018's single-block PBKDF2 via platform HMAC-SHA256. Both derivation paths pass
the independent vectors; the API 24/25 fallback still needs an actual old-device
run. [Android algorithm availability](https://developer.android.com/reference/javax/crypto/SecretKeyFactory).

Instrumented tests exercise real Android Keystore GCM and CBC, atomic file
recovery, the EncryptedSharedPreferences fallback, Expo migration and Android's
backup crypto provider. They use only unique test aliases and preference keys.
`UpgradeCompatibilityTest` is skipped unless `noahUpgradeCheck=1`.

For the required end-to-end upgrade, boot a **fresh Google APIs emulator with
Play Services** (root-capable, not a locked Play Store image), then from the repo:

```sh
nix develop --command apps/android/UpgradeTests/run.sh emulator-5558
# Or reuse an already built RN regtest release APK:
nix develop --command apps/android/UpgradeTests/run.sh emulator-5558 /absolute/path/to/app-regtest-release.apk
```

Use a dedicated emulator you created. `emulator-5554` (BarkRegtest) is reserved and
rejected. For `FUNDED=1`, boot with `-grpc <unused-port> -grpc-use-token` and set
`EMULATOR_PROTO_DIR` to the directory containing that emulator's
`emulator_controller.proto` if it differs from `$ANDROID_HOME/emulator/lib` (for
example, the Nix build SDK and the host emulator SDK).
`clipboard.py` discovers the gRPC endpoint by serial, so it never targets another AVD.

```sh
FUNDED=1 nix develop --command apps/android/UpgradeTests/run.sh emulator-5558 /absolute/path/to/RN.apk
```

FUNDED runs the RN prepare flow (which resets the fresh test install), receives
100,000 sats and sends 5,000 back. The script force-stops RN, pulls its datadir with
any WAL, and requires fingerprint, movement count and spendable expectations from
SQLite before installing native with `adb install -r`. Instrumentation opens via
the shared session and compares all three. Missing expectations fail the check.
The funded RN state must contain at least two movements and 95,000 spendable sats.
Bark commands use `docker compose run --rm --no-deps` inside the harness: the current
`just bark` wrapper omits `--no-deps` and can recreate running dependency containers
when a worktree's Compose configuration differs.

The script refuses an existing Noah regtest install, builds RN release with
`CI_DEBUG_SIGNING=true` and `SENTRY_DISABLE_AUTO_UPLOAD=true` (embedded JS, no Metro or Sentry upload), checks both signing certificates,
creates the wallet using the RN Maestro subflows, captures only a hash of the Expo
ID, and replaces RN using `adb install -r`. There is no uninstall or clear-data
operation between the RN wallet creation and native replacement. The gated test asserts seed readability and verified migration, unchanged
legacy secret files, RN DB presence, initialized MMKV state, `existingWallet`, and
unchanged Expo ID when RN had one. Maestro artifacts are private and deleted on
exit because a failed onboarding flow can capture a recovery phrase.

### Wallet parity verification on 2026-10-19

- `assembleRegtestDebug`, `assembleMainnetDebug`, `assembleSignetDebug`,
  `testRegtestDebugUnitTest` and `assembleRegtestDebugAndroidTest` passed in Nix/JDK 17.
  The JVM suite ran **42 tests, zero failures**. Repository commit checks also passed
  RN lint, React Compiler checks, 148 RN unit tests and TypeScript checking.
- On the dedicated Android 15 Google APIs ARM64 `NoahNativeWalletParity` AVD
  (`emulator-5558`), instrumentation reported `OK (8 tests)`: **seven passed** and
  the upgrade-only test was skipped until the funded run. SQLite preflight left
  missing/empty/foreign/wrong-network databases untouched. All **70 amount cases**
  matched on both modern ICU and the legacy fallback path. The first run exposed
  Spanish `1234` grouping in the legacy formatter; the final run has no mismatches.
- RN `assembleRegtestRelease -PreactNativeArchitectures=arm64-v8a` passed with
  embedded JS, debug signing and Sentry upload disabled. RN and native signing
  certificate SHA-256:
  `fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`.
- **Funded RN → native upgrade passed (`OK (1 test)`)** after RN received 100,000
  sats and sent 5,000 back. Native matched the RN database's **fingerprint
  `4707393a`, two movements, and 95,000 spendable sats**. Verified seed migration,
  untouched legacy secret files, initialized MMKV and existing-wallet launch state.
  RN had no Expo ID; its existing-ID continuity was therefore not exercised by
  this run (separate instrumented Expo migration tests passed).
- The first funded harness invocation stopped after the payments because
  `adb exec-out cat` returned a missing-file diagnostic as successful output while
  reading optional Expo preferences. The reader now checks existence with
  `adb shell test -f`. The unchanged funded RN install was retained and the
  corrected harness's post-payment section resumed; neither payment was repeated.
- A preliminary `just bark balance` unexpectedly recreated bitcoind, Postgres and
  captaind through Compose dependency startup, retaining their volumes. Services
  returned healthy and the funded test passed. The harness now uses `--no-deps`;
  the shared `scripts/ark-dev.sh` wrapper needs the equivalent fix in its own task.

Unverified: physical devices and actual API 24–29 OS behavior (the formatter's
fallback path was exercised on API 35); an upgrade with onchain funds, recurring
rules or an existing RN Expo ID. See the native backups section below for interrupted-restore recovery verification.

## Server API and identity

`nix develop --command just generate-api-android` generates committed models and
clients from `api/openapi.json`. The script downloads **Fabrikt 28.1.1** (the latest
stable release checked on 2026-10-19), verifies its pinned SHA-256, and removes the
16 MiB tool when finished. [Fabrikt's client support](https://github.com/fabrikt-io/fabrikt/tree/28.1.1)
limits the OkHttp generator to Jackson; the Ktor target provides suspending calls
and kotlinx.serialization. We use Ktor 3.6.0 with its OkHttp engine and the app's
existing OkHttp dependency. Dependencies remain checksum-verified by Gradle.

Generation adaptations: `integer:uint32` maps to `Long` because Fabrikt otherwise
uses `Int` (the recurring-payment fixture includes 4294967295). The generated
notification interface's experimental serialization annotation becomes a local
`@OptIn`, avoiding propagation to callers; generated trailing whitespace is stripped.
Public method/path templates are
emitted directly from OpenAPI `security`. No endpoint security list is handwritten.
All six notification variants remain generated sealed-interface implementations.
As on iOS, normalized `u64` uses signed `Long`; values above `Long.MAX_VALUE` are
rejected. The server spec needs explicit bounds if that range becomes meaningful.

`AppServices.shared(context)` owns the shared wallet session, token provider,
public login client, authenticated client and registration service. Launch starts
registration after opening the existing wallet, then reports last login. Failed
setup retries on resume; server failure never prevents access to the wallet.
Regtest defaults to `http://10.0.2.2:3000`; tests may inject another base URL.
The login signs k1 using bark-bolt-ffi 0.2.0's offline mnemonic key at index zero.
Tokens refresh within 3 hours + 60 seconds of expiry; concurrent callers share a
login, and an auth 401 retries once. Redirects are disabled for these clients.

Tokens are read/written in RN's `RN_KEYCHAIN` DataStore row for
`com.noah.auth-token.<variant>`, account `noah`, using the existing Keystore alias
and GCM or CBC cipher. Only token rows can be cleared by the auth writer. Both
backup rule files already exclude RN_KEYCHAIN preferences and DataStore for cloud
backup and device transfer; JWTs never go into MMKV. Server state retains the RN
`server-storage` version-0 envelope and profile changes preserve other preferences.

The shared `apps/fixtures/device-info.json` ports RN's device-report rules plus the
iOS boundary/null cases; JUnit consumes it. iOS currently has equivalent inline
tests and has not been changed to consume the new fixture. `nostr-npub.json` is
reused. Email sending preserves the saved email until verification, reconciles
"already verified", and identity updates reject nsec locally. No screens or push
registration were added.

### Server verification (2026-10-19)

- JDK 17/Nix: regtest, mainnet and signet debug APKs and the instrumentation APK
  built successfully. **72 JVM tests passed**, including **30 server tests**.
- The server fixtures decode and round-trip all six notification types (7 cases),
  including unsigned-32 maximum and large satoshi amounts. Regeneration produces
  no diff. Unknown notification tags and signed-64 overflow are rejected.
- Dedicated Android 15 ARM64 `NoahNativeServerAgent`, `emulator-5560`:
  **3 instrumented tests passed**, including live login → registration →
  last-login against the existing server at `10.0.2.2:3000`, persisted token read,
  and recovery after a rejected token. Storage tests covered fresh GCM and existing
  RN CBC aliases, replacements and clearing; device metadata matched Expo sources.
  No other emulator or Docker service was modified. The test uses a throwaway
  identity, does not open/fund a wallet, and removes its local token/key afterward.
- Repository commit hooks passed lint, React Compiler checks, **148 RN tests**,
  TypeScript checking and Rust formatting. The initial worktree lacked installed
  Husky files; dependencies were installed and the first commit was amended through
  the installed hook before proceeding.

To rerun the gated live test, build and install the regtest app and test APK on an
emulator **you created**, then run (replace the serial with that emulator's):

```sh
adb -s emulator-5560 shell am instrument -w -e noahServerIT 1 \
  -e class com.noahwallet.services.ServerIntegrationTest,com.noahwallet.services.AuthTokenStorageTest \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

Do not use `BarkRegtest` / `emulator-5554`, another agent's emulator, or restart the
regtest stack. Optional `-e noahServerURL http://10.0.2.2:3000` overrides the test
endpoint. Without the opt-in flag, the live case is skipped. The live run did not
exercise email delivery, push, physical devices or Android API 24; email/identity
behavior is covered by unit tests. Like iOS, retry requires HTTP 401 plus one of
the three auth codes; a late 401 for an old token reuses an already refreshed token.


## Native push

`services/push` replaces Expo's Android service with `NoahFirebaseMessagingService`.
FCM `data.body` is decoded with the generated notification types, then queued in
WorkManager. High-priority messages use expedited work with a regular-work fallback;
normal-priority and UnifiedPush messages use regular work. Android < 12 gets the
WorkManager foreground notification. Duplicate pending/running payloads coalesce.
Wallet operations are not automatically retried after an ambiguous failure;
registration retries use WorkManager's exponential backoff and a network constraint.

The wallet phase requests `POST_NOTIFICATIONS` on Android 13+. Registration requires
RN `wallet-storage.isInitialized`, successful server registration and permission.
GMS devices exchange their current FCM token with Expo using the preserved installation
UUID, application ID, Firebase app and EAS project. `onNewToken` queues registration
with the latest token. No-GMS devices reuse connector **3.1.2**, its database/legacy
preferences, `noah-<variant>` and `noah_unified_push` endpoints. Endpoint changes
queue authenticated server registration. Saved distributors take precedence, then
ntfy, then the first available distributor. No distributor means waiting for one;
there is no Firebase fallback on a non-GMS device. Settings UI is still pending.

Both transports run maintenance (with correlated status), invoice creation/submission,
claim + local receive notification, heartbeat and correlated backup jobs. Recurring
wakes remain log-only until M7.
Widget updates preserve the six Long keys read by `NoahWidgetProvider`, including
`-999` for no VTXO expiry. Existing `default` and `noah-push-default` channels are
never recreated or reconfigured. Notification copy currently uses English and BIP177,
as the native locale/profile UI ports remain pending.

### Push verification (2026-10-19)

- All three debug variants build. **89 JVM tests pass**, including boundary string/
  object decoding from server fixtures, malformed/negative inputs, all six job routes,
  maintenance failure reporting, registration gates/retry, Expo wire shape and widget
  keys/balance accounting. Dependency verification now pins WorkManager and its
  storage dependencies as well as the retained UnifiedPush connector.
- Dedicated Android 15 Google APIs ARM64 AVD **NoahNativePushAgent**, `emulator-5562`:
  connector 3.1.2 reads seeded RN-shaped SQLite registrations and migrates a legacy
  registration without losing existing tokens; endpoint fallback/rotation, channel
  preservation and actual widget preference types pass instrumented tests.
- **Live FCM → Expo → register_push_token passed** against the existing host server
  at port **3100**. Repeating the Expo exchange with the same persisted installation
  UUID returned the same Expo token (compared privately). Notification permission was
  also requested and accepted in the actual wallet UI.
- Simulated RN `RemoteMessage` → native service → WorkManager maintenance also passed
  and reported `Success` to the live server.
- **Real Expo heartbeat and maintenance delivered after killing the background app
  process** (not force-stopping it). The native service queued both jobs, heartbeat
  became `responded`, maintenance reported `Success`, and widget preferences updated.
  A host driver sent RN-shaped payloads through Expo with the running server's
  credentials and inserted pending correlation rows for **only the disposable test
  identity**. The live server, containers and volumes were not restarted or recreated.
- The fresh test wallet used the server's supported address-less registration, like
  `ServerIntegrationTest`; a new SDK wallet has no address at index zero until one is
  generated. Production upgraded RN wallets already have that address. Onboarding
  must generate its address before the existing `ServerRegistration.peekAddress(0)`.

To run the gated test on a disposable emulator **you created**:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew -PnoahRegtestServerURL=http://10.0.2.2:3100 :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest'
adb -s YOUR_SERIAL install -r apps/android/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk
adb -s YOUR_SERIAL install -r apps/android/app/build/outputs/apk/androidTest/regtest/debug/app-regtest-debug-androidTest.apk
adb -s YOUR_SERIAL shell pm grant com.noahwallet.regtest android.permission.POST_NOTIFICATIONS
adb -s YOUR_SERIAL shell am instrument -w -e noahPushIT 1 -e class 'com.noahwallet.services.PushIntegrationTest#liveTokenRegistration' com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
adb -s YOUR_SERIAL shell am instrument -w -e class com.noahwallet.services.PushCompatibilityTest com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

The live test creates/resumes a throwaway wallet and exports only its public auth
identity to `no_backup/push-test-public-key` for targeting the test push row. It keeps
the app data for background delivery checks; remove the disposable AVD afterward.
`simulateRnMessageThroughFirebaseService` additionally accepts `-e noahPushK1 <k1>`
for a pending maintenance row owned by that test identity. Do not run UI automation
instrumentation concurrently with these tests.

Still unverified: an actual RN → native upgrade retaining a registered Expo token,
push before opening that update, physical devices/Doze/token rotation, API 24–30's
expedited foreground path, and delivery through a real UnifiedPush distributor.
Expo's SDK-free HTTP exchange is a compatibility endpoint rather than a guaranteed
public API; keep the existing Expo server credentials configured.

`lintRegtestDebug` remains blocked by the pre-existing camera permission lacking
an optional `android.hardware.camera` feature declaration
(`PermissionImpliesUnsupportedChromeOsHardware`). Push's deliberate RN-compatible
MAX channel importance is locally documented; all other push lint errors are clear.

## Native screen verification

JVM tests consume all cases in `receive-request.json` and `fiat-currency.json`.
Session tests cover waiting-observer shutdown, cancellation before observer startup,
and cancellation of an active native call. Observers belong to the session; shutdown releases their native wait and joins
them before draining counted calls and stopping the wallet. Submitted calls finish
under `NonCancellable` before their drain count is released.

The mnemonic screen sets `FLAG_SECURE`, marks clipboard content sensitive, clears
its own clipboard item on exit, and expires it after two minutes while visible.
No recovery phrase is stored in Compose saved state.

Verified on a dedicated Android 15 Google APIs emulator: fresh UI creation, process
restart during onboarding, server registration, Lightning identity save, post-onboarding
notification permission, and Home. Bark paid a 5,000-sat Ark address and a 6,000-sat
Lightning invoice from Receive; both reached success, and the SDK reported a final
10,888-sat wallet balance. The amount/note sheet remained visible above Gboard.
Email delivery/OTP entry and real push delivery were not repeated in this screen pass.

## Native encrypted backups and restore

`services/backup` ports the reviewed iOS design. `WalletSession` reservations cover
fresh creation and restore, counted native calls finish despite cancellation, and
close stops both observer kinds before draining calls and stopping the daemon.
An unfinished restore blocks ordinary open/create. Failed verification teardown
quarantines its handles and leaves the journal for recovery after a successful
close or process restart; files are never swapped beneath live native work.

V2 contains only a strict manifest and SQLite snapshot. Commons Compress 1.28.0
reads central-directory types (including symlinks); Noah checks CRC per entry and
counts inflated bytes independently of headers, with a 1 GiB aggregate cap.
Legacy v1 extraction accepts only this variant's subtree, with path and entry
limits. OkHttp streams downloads with the server-recorded v2 size as its cap,
then the restorer checks both size and SHA-256. Newly required Commons artifacts,
including build-plugin versions, have independently downloaded SHA-256 pins.

Snapshots and their seed are read inside one counted session call. Upload uses
initiate → S3 PUT (RN headers) → complete and skips unchanged snapshots. The
coordinator retains RN's `backup-storage` JSON shape and explicit nulls, generation
coverage, five-second debounce and jittered exponential retry. State changes,
foreground and push trigger it; push bypasses only the backup-enabled preference
and reports its correlated result. A cancelled flush waiter leaves the pass running.

Restore reserves the session, settles any old journal, fetches and validates,
installs, opens without a daemon, and saves the seed last. The sibling workspace
keeps journal/previous/staged/discard/work. Files use `fd.sync()` and directories
use `Os.fsync`; errors are surfaced. The journal stores an HMAC seed tag, never the
seed. Undo renames to discard before deleting; unreadable seed storage waits.
Failed seed writes are read back to decide whether to finish or undo. Android seed
read-back also synchronizes the file and directory before returning a value: an
unsynced replacement must not be mistaken for a durable commit. Cleanup retries
once, then returns `cleanupPending` for launch recovery. Launch recovers before
reading wallet state; scratch sweeps also cover the busy/no-journal path.

Backup/export/preflight scratch lives in cache and is tracked per operation;
restore workspaces are excluded from cloud backup and device transfer in both XML
rule files. The native seed remains in `noBackupFilesDir`. The optional restore
screen is still a placeholder; `AppServices.restorer()` and `seedRestored` are ready
for that UI to call. No restored phrase is persisted in UI state.

### Backup verification (2026-10-19)

- Nix/JDK 17: **216 JVM tests pass**; regtest debug APK and instrumentation APK build successfully.
  Tests cover crash boundaries, sync failures, reservations, cancellation, seed
  failures/read-back, legacy fallback, retained-backup fallback, manifest/path/CRC
  limits, shared crypto/archive fixtures, and coordinator timing with a manual clock.
- Dedicated Google APIs Android 15 ARM64 AVD `NoahBackupRestoreAgent`, port 5560:
  real upload to the existing S3-configured server on `10.0.2.2:3100`, unchanged
  snapshot skip, restore into a fresh datadir/seed store, reopen and matching
  fingerprint, movement count and balance. This is a fresh unfunded wallet (zero
  movements), matching the iOS integration-test scope. Its test backup is deleted.
- **11 distinct instrumented cases passed**: nine backup/storage/database/crypto cases,
  the Node snapshot preparation, and the Node restore.
- A separate real offline-wallet snapshot was zipped with Info-ZIP and encrypted
  by Node crypto, then downloaded, validated, committed and reopened by Android.
  The fixture uses a public BIP39 vector, never a user's seed. Real filesystem
  journal recovery and atomic seed persistence were also exercised on the emulator.
- Repository hooks run lint, React Compiler checks, 148 RN tests, TypeScript and
  Rust formatting. Android lint still has the pre-existing camera permission /
  optional hardware declaration error; no new backup lint errors were reported.

To repeat on **your own** emulator, build/install both APKs and run:

```sh
adb -s YOUR_SERIAL shell am instrument -w -e noahBackupIT 1 \
  -e class 'com.noahwallet.services.BackupIntegrationTest#liveUploadSkipAndRestore,com.noahwallet.services.BackupIntegrationTest#prepareNodeSnapshot,com.noahwallet.services.BackupIntegrationTest#realFilesystemCrashRecoveryAndSeedDurability' \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
node apps/android/scripts/serve-node-backup-fixture.mjs YOUR_SERIAL
# In a second terminal, while the fixture server runs on port 3161:
adb -s YOUR_SERIAL shell am instrument -w -e noahNodeBackupIT 1 \
  -e class 'com.noahwallet.services.BackupIntegrationTest#restoreNodeMadeSnapshot' \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

Stop the fixture server with Ctrl-C to remove its plaintext temp folder, then
remove your disposable AVD. These commands never restart the shared stack. Direct
iOS-device ↔ Android-device restore, physical devices, API 24–34 and hardware power
loss remain unverified. Unit tests inject each journal boundary and sync failure;
they do not emulate a filesystem losing writes after power removal.
