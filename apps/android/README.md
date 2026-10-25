# Noah native Android

Regtest Lightning-address previews default to `localhost.com`. Override the domain with
`-PnoahRegtestLnurlDomain=wallet.test` when building against a custom LNURL host.
This does not change mainnet/signet domains or the server URL.

Kotlin/Compose `:app` with Navigation 3, onboarding, Home and Receive. Launch
opens through the process-wide `WalletSession.shared(context)` and resumes unfinished
onboarding at the recovery phrase. It never creates a wallet at launch or deletes
legacy secrets. Fresh creation derives key 0 before server registration.

Onboarding includes the beta warning, secure recovery phrase, emergency email and
Lightning identity. Restore accepts a private 12-word recovery phrase or uses the
securely stored phrase after wallet data loss; see [restore verification](RestoreTests/README.md).
Home uses the full wallet balance and RN-compatible unit/privacy settings. Send and
Receive follow [the redesign](../../docs/native-rewrite/design/send-receive.md) on top of
the unchanged `SendModel`/`SendController` and receive request: hold to send, a motion
stage that advances only as the controller reaches each milestone, and a Receive card that
defaults to the Lightning address, with every format, amount requests and arrivals.
Notification permission and the existing push layer run after onboarding.

Send and Receive have an opt-in fixture gallery (no wallet is used):
`adb shell am instrument -w -e class com.noahwallet.ui.SendReceiveGalleryTest -e noahSRShots <label> com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner`,
then pull `files/sr-shots/<label>` with `run-as`. `SendHoldTest`, `SendReceiveFoldTest` and
`ReceiveQRTest` run unconditionally.

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
removing their original records. Auth tokens use DataStore `RN_KEYCHAIN`, then
legacy SharedPreferences `RN_KEYCHAIN`. Mnemonics compare **all** present sources:
DataStore, legacy preferences, `noah_native_secrets`, and the native Keystore store.
Only complete absence permits another source. Partial records, unsupported ciphers,
missing keys, malformed UTF-8 and decryption failures return `Unavailable`.
`Found.toString()` redacts its value; no provider exception text is retained.
The auth-token reader uses the same RN formats but not the mnemonic fallback.

`SeedStorage.read()` is read-only. It validates English BIP39 word list/checksum
(12/24 words, NFKD/case/whitespace normalization for validation) and rejects invalid, unreadable or
conflicting sources. Native migration waits until the SDK's read-only DB
fingerprint check succeeds. It retains the exact original credential string for
RN backup-password compatibility; differing source strings fail closed even if
they normalize to equivalent BIP39 words. A failed copy retains the readable legacy seed for
retry; legacy records are never removed by migration. The blocking recovery screen
has a secret-free support message and offers no wallet replacement action.
See [the full RN store inventory](UpgradeTests/STORES.md) for MMKV contracts.

The native store uses alias `noah.native.seed.<variant>`, AES-256-GCM, no user
authentication, and a fresh provider-generated 12-byte nonce. Its version-1 file
is `[version:1][nonce:12][ciphertext][tag:16]`, atomically written to
`noBackupFilesDir/noah-native-seed-<variant>.bin`. Android excludes that directory
from cloud backup and device transfer. A read never generates a key. Explicit
writes refuse to replace an unreadable file/key. This is single-process storage;
future background services must remain in the app process or add process locking.

Launch states match `apps/fixtures/launch-state.json`. The existing wallet shell
requires both a readable nonempty seed and `filesDir/noah-data-<variant>/db.sqlite`.
Seed-only launch offers restore from the securely stored phrase. Missing-seed and
unavailable-storage states preserve data and offer recovery guidance.
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

For the required end-to-end upgrade, create your own **fresh Google APIs emulator
with Play Services**, on an explicit port **>=5590**. Both `5554` and `5556` are
reserved. Use a root-capable image, not a locked Play Store image. Boot with
`-port 5594 -grpc 8594 -grpc-use-token` (pick unused ports). For the funded flow,
set `EMULATOR_PROTO_DIR` to the host SDK's `emulator/lib` if the Nix SDK differs.
`clipboard.py` discovers only that serial's authenticated gRPC endpoint.

```sh
nix develop --command apps/android/UpgradeTests/run.sh emulator-5594
# Or reuse an RN regtest release APK built from the current client:
FUNDED=1 nix develop --command apps/android/UpgradeTests/run.sh emulator-5594 /absolute/path/to/RN.apk
```

The script refuses an existing Noah installation, builds with two Gradle workers,
checks matching certificates, and creates RN's wallet through Maestro. FUNDED
receives 100,000 Ark sats, sends 5,000 back, creates/pauses a 1,234-sat monthly rule,
sets sats/EUR/hidden balance/name/email-prompt preferences, receives 30,000 confirmed
onchain sats and declines RN's auto-board prompt using **No, turn off**. It places
RN's widget and pins a controlled shortcut to the existing MainActivity.

RN's `Device.isDevice` gate skips emulator push registration. The separate test-only
runner (`-PnoahRNUpgradeBaseline=true`) uses the unchanged RN APK's Expo
InstallationId and FCM runtime, exchanges a real Expo token in RN's foreground,
and registers it with Noah using the existing RN auth token. It never reads the
seed or exports credentials; results contain only hashes. The normal test APK is
rebuilt separately. A missing real token/UUID baseline fails the upgrade gate.

RN is force-stopped before the full datadir/WAL and MMKV hash are captured. Native
replaces it with `adb install -r`, with no uninstall or clear-data after wallet
creation. Instrumentation rejects invalid/foreign seeds without changing DB files,
then verifies the fingerprint, movement IDs and amounts, Ark/onchain balances,
settings, every recurring-rule field, Expo UUID/token, shortcut component and widget
provider metadata. It launches native UI and waits for recurring initialization,
then confirms the paused rule and balances remain unchanged. Widget bound IDs,
provider and launcher host are also compared across APK replacement.

The fresh-install prepare flow clears only the new empty test installation.
Maestro artifacts are private and removed on exit because an onboarding failure
can capture a recovery phrase. Never restart/reset the shared Docker stack for
this test. Existing `just bark` now uses `--no-deps`; the harness also uses it
explicitly. Fund Bark via Bitcoin Core and board if needed, using quiet CLI logs.
See [how to run it](UpgradeTests/README.md#full-rn--native-replacement)
and the launcher parser self-check: `python3 apps/android/UpgradeTests/test-launcher.py`.

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
wakes call the shared recurring executor with its existing submission budget.
Widget updates preserve the six Long keys read by `NoahWidgetProvider`, including
`-999` for no VTXO expiry. Existing `default` and `noah-push-default` channels are
never recreated or reconfigured. Notification copy currently uses English and BIP177,
as the native locale/profile UI ports remain pending.

### Periodic background work (2026-10-20)

`PeriodicWork` adds two unique WorkManager requests, both with connected-network
constraints and no charging, idle, exact-alarm or expedited requirement:

- `noah-wallet-refresh`: 60 minutes, initially delayed 60 minutes; sync, execute
  due recurring payments, refresh the widget's summary/expiry and publish it.
- `noah-wallet-maintenance`: 6 hours, initially delayed 6 hours; the same stages,
  plus delegated maintenance after sync and enabled-only backup after recurring.

`KEEP` preserves pending/running IDs and next dates on launch. WorkManager's own
persistent database and boot/package-replacement receivers restore scheduling;
there is no additional boot receiver. Eligible onboarding/startup reconciles before
server registration, so a registration outage cannot prevent local scheduling.
Committed wallet deletion cancels both requests before deleting the seed; another
completed onboarding enqueues fresh requests. Suspended/ineligible wakes do no work.
Android may defer either interval under Doze, battery optimization or constraints.
The existing battery screen and persisted prompt/skip state are unchanged; these
workers neither request nor require an exemption.

Admission uses the existing `AppServices.ensureWalletOpen` headless path: healthy
storage, Home launch decision (readable agreeing valid seed and existing wallet DB),
`isInitialized != false`, `isOnboarded()`, no suspension, restore/deletion journal or
automation-storage failure. Headless work never saves/migrates seeds or performs
restore/deletion recovery. The lifecycle gate still precedes RestoreRecovery and
session reservations; nested opening passes `walletMutationHeld = true`.

Before opening the wallet, `RecurringRuntime.hasDuePayments` reads the existing
protected store: valid active policies with `nextRunAt <= now` and elapsed retry
backoff. Policy-ledger rollback/corruption and durable authority stops overlay those
policies as needing attention. With nothing due, the recurring stage is skipped;
it adds no wallet open, sync or network request. Refresh's independent sync/widget
stages still run. This intentionally replaces RN's once-per-24-hour Android fallback
with more timely execution when a wake push is missed. The server's
`recurring_payment_due` push remains primary. Advisory reminders are unchanged.
The only execution path is `RecurringRuntime.execute(budget)` → existing executor
and Send reservations: final binding/policy/authority checks, durable occurrence
consumption and ambiguous-outcome protection remain authoritative and unchanged.

Push and periodic jobs share a queue, while all wallet calls retain the existing
counted/exclusive session reservations. A periodic run carries the same monotonic
25-second `RecurringBudget.push()` as push work. Every independent stage checks
cancellation, WorkManager stop and budget; widget summary, expiry and publication
have separate checks. CoroutineWorker's final `onStopped` cancels its coroutine;
a cancellation hook revokes the budget immediately, including during shielded
recurring/backup settlement. Foreground entry also revokes synchronously, refuses
new periodic runs and waits for existing runs outside the lifecycle gate before
startup/resume wallet work. Native calls already admitted and detached recurring/
backup work settle before the queue/activity flag is released. Workers never
return `retry()` after an ambiguous wallet result; a later interval starts fresh
admission through the same durable executor, rather than replaying a submission.

Periodic verification on base `aea99fe4`, owned API 35 Google APIs ARM64 AVD
`NoahPeriodic_aea99_5604`, explicitly launched with `-port 5604 -no-window -no-snapshot`:

- **16 new focused JVM tests passed**, including WorkManager TestDriver scheduling,
  stable IDs/dates, network constraints, cancellation during a shielded stage,
  real wallet-lifecycle deletion/re-onboarding, all admission refusals, each
  stop/budget stage boundary, foreground settlement, protected-policy due checks,
  and the existing executor's one-payment/ambiguous-outcome behavior.
- **One instrumentation test passed** on `emulator-5604`: both production worker
  kinds returned success for an empty wallet without creating a wallet/seed;
  TestDriver then ran both persisted periodic requests and verified dedup/cancel.
  This proves WorkManager/headless refusal, not a funded native maintenance run.
- Final requested command passed with **3,072 tests / 113 suites / zero failures,
  errors or skips**, APK assembly and instrumentation Kotlin compilation:
  `nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'`.
  The first full run had seven stale source-wiring assertion failures after the
  shared admission extraction; those assertions were updated, all 13 wiring
  tests passed narrowly, and the final command was rerun successfully. No runtime
  or device-test behavior changed in that correction.
- Logs/results: `/private/tmp/noah-periodic-{focused,full-initial,wiring,final,device-test}.log`
  and `/private/tmp/noah-periodic-results.json`. The exact AVD name was confirmed,
  shutdown requested, device absence and closed ports 5604/5605 verified, then
  that AVD deleted and its `.ini`/`.avd` absence verified. Single-use final Gradle
  processes stopped; shared devices and builds were untouched.

No live funded wallet, server wake delivery, real 60-minute/6-hour wait, reboot,
package replacement or Doze measurement was attempted. Scheduling persistence uses
WorkManager's platform implementation; the regression uses its official test driver.
No iOS, server, RN, settings-storage internals, executor, ledger or authority code
changed. Client/server hooks were skipped for these native-only commits.

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

## Activity

Activity follows [its redesign](../../docs/native-rewrite/design/activity.md): Home's rows over the shared
`HistoryMapping` (failed and refunded sends, fee-paying renewals, the recurring join, invoice and offer
descriptions), a Pending group and day groups, search, the filter in ⋯, a row → detail shared-element push with
a predictive back preview, the export sheet, and book/tabletop splits at the fold.
`ActivityTransitionTest` (phones) and `ActivityFoldTest` (synthetic folds) run unconditionally. Opt-in:
- fixture gallery: `-e class com.noahwallet.ui.ActivityGalleryTest -e noahActivityShots <label>` (optionally
  `-e noahActivityOnly list-busy,detail-failed`), then pull `files/activity-shots/<label>` with `run-as`;
- the debug preview on a real clock: `adb shell am start -n com.noahwallet.regtest/com.noahwallet.ui.ParityPreviewActivity
  --es screen activity --es state busy`;
- live stages on a disposable wallet against a second Bark 0.8 stack (build with the regtest overrides below):
  `-e class com.noahwallet.ui.ActivityLiveTest -e noahActivityLive <create|fund|pay|fail|nothing-left|onchain|address|chain-send|ack>
  -e owner activity-disposable` with `-e invoice`, `-e recipient` or `-e amount` as each stage documents.

On regtest, bark finishes an Ark-funded on-chain send once it's in the mempool (`offboard_required_confirmations`
is 0 off mainnet), so "Confirmed in block N · 1 of 2" only shows on mainnet; on-chain wallet payments show the
block row until their block.

## Settings

Settings follows [its redesign](../../docs/native-rewrite/design/settings.md) on today's models and services. On
Home's stack it is one entry (`SettingsDestination.Settings`) holding its own page stack; deep links (Home's Renew,
backup and suspended nudges, Activity's VTXO, schedule and exit links, recurring notifications) go through
`SettingsRoutes.open`, which puts the page on top of its parents. The list leads and the page trails on wide windows
and at a book fold (tabletop: list above, page below). The recovery phrase is shown only after authentication (or the
"No screen lock" sheet), lives in memory (`RevealedPhrase`) and clears when Noah leaves the screen. The emergency
exit has its own redesign (see Emergency exit below).
`SettingsDeviceTest` (gates, Back and predictive back on every page, synthetic folds, recreation) runs unconditionally.
Opt-in gallery on an owned, disposable regtest AVD with its throwaway wallet open:
`-e class com.noahwallet.ui.SettingsGalleryTest -e noahSettingsShots <label>` (optionally `-e noahSettingsOnly root,delete`),
then pull `files/settings-shots/<label>` with `run-as`. Its recovery phrase is the public sample, never the wallet's.

## Emergency exit

The exit follows [its redesign](../../docs/native-rewrite/design/exit.md) on today's exit calls, journal and rules. Its
pages (`ExitKey`: warning, Choose VTXOs, start, one exit, claim, the list) sit above Settings → Emergency exit and
fill a wide window or fold themselves (explanation and picture lead, details and actions trail; tabletop: picture
above). `ExitStages` maps each bark `ExitState` and transaction status to the page's phase, and reads fees rising
from bark's history. bark keeps a state's tip while nothing changes, so `ExitSeen` keeps (across restarts) the wallet
tip at which Noah first saw each transaction in its status, per episode (where that status began in bark's
append-only history, in this run: bark restarts a canceled exit with empty history, and starting or canceling forgets
the exit's evidence), and blocks stuck in the mempool count from there. "Needs
on-chain funds" is inferred (`ExitFunding`): a transaction in `AwaitingCpfpBroadcast` while the confirmed balance is
below its package's fee at the rate bark's own estimate reports, once bark has tried (a completed Check the chain, or
one configured daemon sync interval plus 15 s within one daemon run, restarted on return from the background), within
the same parking episode. The stale warning needs evidence that nothing
progresses exits: the last Check the chain failed, or the wallet is open without bark's daemon. "Fees more than it's
worth" uses each VTXO's standalone estimate until bark-bolt-ffi's estimate has per-transaction data; "All" waits until
every VTXO has one (Try again asks for the rest). A fee child's fee
is what left the wallet (BDK can't price its anchor input).
Claims default to another wallet; a claim to this phone records its outputs in auto-board's handled ledger and its
claimed set (one durable write) before it's broadcast. Auto-board then weighs and sizes only the confirmed balance
less those coins while they're unspent, and passes them to `prepareBoard` as excluded outpoints (bark-bolt-ffi 0.7.0),
so it prepares once, by amount, and their value stays on-chain. A review whose inputs still include one is never
offered. Manual Move to Ark excludes nothing. A submitted claim shows as claiming until bark's next progress marks it. Each broadcast claim's actual fee, exits and destination are kept apart from the
journal (`ExitClaimRecords`), so a finished exit still shows what it received after its attempt is acknowledged.
`ExitDeviceTest` (hold submits once, Claim off until a valid reviewed address, Cancel only while allowed, synthetic
book and tabletop folds, recreation) and `ExitSubmitDeviceTest` (start and claim reach a fake bark once through the
real pages, session and on-disk journal, across a posture change and process death) run unconditionally. Opt-in fixture gallery (no wallet):
`-e class com.noahwallet.onchain.ExitGalleryTest -e noahExitShots <label>` (optionally `-e noahExitScenes warn,list`),
then pull `files/exit-shots/<label>` with `run-as`. `ExitLiveTest` adds owned-wallet stages for the live check:
`board-all` (no confirmed on-chain funds left), `inspect` (each exit's packages, fee children and waiting package fee)
and `ledger` (auto-board's handled ledger and what it would offer).

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
Sealed files publish from a complete temporary file without replacing anything:
`createNewFile` claims the destination name exclusively (O_CREAT|O_EXCL), then
`Os.rename` swaps the complete file in over that empty placeholder. Android's app
sandbox denies hard links and `Os.rename` alone would overwrite, so this keeps
iOS's exclusive publication semantics in plain Kotlin (no native code).
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
rule files. The native seed remains in `noBackupFilesDir`. The restore screen calls
`AppServices.restorer()`, then seeds backup state with
`seedRestored`, invalidates server authentication and opens the restored wallet.
Failures after the engine commits require restart and cannot start another restore.
Phrase entry uses `FLAG_SECURE`, local BIP39 suggestions/checksum validation and
whole-phrase paste; input is cleared on submit, background and exit and never saved
in instance state. Installed/suspended wallets and pending restore/deletion markers
are blocked from this onboarding flow.

### Backup verification (2026-10-19)

- Nix/JDK 17: **217 JVM tests pass**; regtest debug APK and instrumentation APK build successfully.
  Tests cover crash boundaries, sync failures, reservations, cancellation, seed
  failures/read-back, legacy fallback, retained-backup fallback, manifest/path/CRC
  limits, shared crypto/archive fixtures, and coordinator timing with a manual clock.
- Dedicated Google APIs Android 15 ARM64 AVD `NoahBackupRestoreAgent`, port 5560:
  real upload to the existing S3-configured server on `10.0.2.2:3100`, unchanged
  snapshot skip, restore into a fresh datadir/seed store, reopen and matching
  fingerprint, movement count and balance. This is a fresh unfunded wallet (zero
  movements), matching the iOS integration-test scope. Its test backup is deleted.
- **12 distinct instrumented cases passed**: ten backup/storage/database/crypto/publication cases,
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
  -e class 'com.noahwallet.services.BackupIntegrationTest#liveUploadSkipAndRestore,com.noahwallet.services.BackupIntegrationTest#prepareNodeSnapshot,com.noahwallet.services.BackupIntegrationTest#realFilesystemCrashRecoveryAndSeedDurability,com.noahwallet.services.BackupIntegrationTest#atomicArchivePublicationRefusesExistingTargets' \
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

## Android logging and telemetry

`NoahApplication` installs `BarkLog` before starting telemetry, including headless
process startup. Like native iOS and RN's nitro-ark logger, Bark uses Debug for
all variants; Trace maps to verbose, Debug/debug, Info/info, Warn/warn and
Error/error. The SDK retains the callback. Callback bodies only map the level and
admit raw target/message strings to `LogFile`'s existing ordered, bounded writer;
no Bark reentry, redaction or filesystem work runs on the Rust/JNI caller.
Registration failures log a fixed message without the provider's error contents.

The writer holds at most 256 pending records and redacts targets/messages before
Logcat and rotating cache files. Overflow produces ordered `[dropped N log records]`
markers. Export reads the same redacted files. Logging and telemetry share the
unchanged 147-case `apps/fixtures/log-redaction.json` contract. Telemetry additionally
omits oversized or uncertain messages, checks well-formed UTF-16, and detects
mnemonic fragments across approved strings and dictionary keys. Individual and
aggregate inspection failures drop the event; keys and values are aggregated
separately and bounded to 16 KiB, with a nesting limit of 32.

### Sentry configuration and event coverage

Sentry Android core and NDK are pinned together at **8.57.0**, with the NDK's
transitive native runtime pinned by its metadata at **0.16.6**. All four Sentry
artifacts have independently downloaded Maven Central SHA-256 pins. Replay is
not a dependency. Auto-init, performance and native preload providers are removed
from the manifest; native scope sync remains disabled.
`AppTelemetry` starts only in mainnet/signet release builds with a nonblank DSN.
The production public DSN matches RN/iOS and lives in `Config/sentry.properties`,
compiled into `BuildConfig.SENTRY_DSN`; `NOAH_SENTRY_DSN` in the environment, then
ignored `local.properties`, can override it. Upload credentials never enter app
configuration. Debug, every regtest build and blank-DSN builds do not initialize
Sentry. Release is `<applicationId>@<versionName>+<versionCode>`, dist is the version
code, environment is production, error sampling is 1 and tracing is disabled.
`sendDefaultPii=false`; SDK diagnostics use a no-op logger and scope persistence
is disabled.

The event hook constructs a fresh allowlisted model, matching iOS. Its conservative
aggregate detector can discard otherwise useful diagnostic events:


- Event ID, timestamp, severity, platform, release, dist and environment.
- SDK name/version, integration names, package name/version. Unknown SDK/package
  fields and global package-set mutation are excluded.
- Exception type/module/thread ID, a redacted value, mechanism type/handled/synthetic.
- Thread ID/name/current/crashed/main and stack traces. Frames retain only
  function/module/filename/line; no variables, source context, absolute-path fields or registers.
- App identifier/version/build/start time/build type/in-foreground.
- Device family/model/model ID/architectures, battery level/charging/simulator,
  memory/free/usable memory, storage/free storage, boot time, processor count/CPU
  description, screen dimensions/density/DPI, orientation and low-memory flag.
- OS name/version/build/kernel version/rooted. The Java SDK has no typed equivalents
  for iOS app-memory and device thermal-state fields, so those are omitted.
- Only the `app.variant` tag, with enum values mainnet/signet/regtest.
- Redacted formatted messages and breadcrumb messages. Breadcrumbs retain only
  timestamp/category/severity/message; their data/type/unknown fields are removed.
- Android's generated ProGuard mapping UUID is the sole debug metadata exception:
  a fresh `proguard` image containing only this build's UUID is constructed from
  SDK options loaded from the generated asset. Incoming debug images are dropped. Cached errors from another release omit the
  current build's mapping UUID rather than risk an incorrect mapping association.

Extras, arbitrary contexts, user/request data, transaction/fingerprint, logger,
server name, modules, arbitrary debug images, message templates/parameters,
mechanism payloads and new/unknown SDK fields have no admission path. Every
remaining serialized string and key passes the shared redactor and final guard.
Attachments are cleared from all hint slots; their size allowance is zero.
Screenshots, hierarchy, tombstone capture, app-start/performance tracing,
profiling, replay and feedback UI are disabled. Live/historical **ANR errors,
JVM crashes/handled errors and NDK signal crashes** pass through the same event
hook; ANR raw thread dumps and profiling attachments are disabled. Separate
log/metric channels are disabled and have explicit drop callbacks.
Transaction/replay/feedback callbacks also drop.

A final transport filter covers cached envelopes and SDK APIs that bypass
`beforeSend`, including deprecated user feedback. It admits only rescrubbed error
events and minimal **Release Health sessions**. All other envelope types (including
attachments, both feedback paths, spans/transactions, profiles/profile chunks,
replay, logs, metrics, check-ins, client reports and unknown types) are dropped.
Envelope headers are rebuilt without arbitrary fields or trace context.
Items are filtered independently: a malformed session or event cannot discard
valid errors/crashes in the same envelope. Deliberate drops complete submission
and applicable crash-flush hints as consumed, with retries disabled, so cached
rejections do not wait for the SDK's 15-second timeout or block later outbox items.
Cached directory replay uses resettable per-item waits and retains the outbox file while an accepted upload is unresolved.
Final-guard rejections complete the same submission/crash-flush hints before returning from `beforeSend`.
Automatic session tracking stays **ON**, per the 2026-10-20 product decision.
Sessions accept strictly validated SDK 32-hex IDs and canonical UUID strings for
random session/install IDs, and keep init/start/timestamp/status,
sequence/error count/duration and release/environment; IP, user-agent and arbitrary
attributes are removed. Their remaining strings also pass the final guard.

The Android NDK signal handler writes JSON envelopes to the SDK outbox; Java's
[`OutboxSender`](https://github.com/getsentry/sentry-java/blob/8.57.0/sentry/src/main/java/io/sentry/OutboxSender.java)
replays native events through `captureEvent()`, `beforeSend` and the transport gate.
SIGSEGV/abort in `libbark_bolt_ffi.so` therefore uses the same allowlist above:
exception/signal type, module/thread ID, redacted value and mechanism
type/handled/synthetic; thread ID/name/current/crashed/main; and frames containing
only function/module/filename/line. Thread names must pass the final string guard
or the entire event is rejected. Registers, variables, memory payloads, arbitrary
contexts, raw tombstones and minidump attachments have no admission path.
Native symbolication is a follow-up: instruction/image addresses and incoming
native debug images remain dropped. The generated Java ProGuard mapping exception
above is unchanged.

### R8 mappings and CI

Release builds enable R8 with Android's default optimized rules and four narrow
`dontwarn` entries for absent optional Zstd/XZ classes. Backup extraction already
accepts only stored/deflate ZIP entries; no unsupported codec is enabled. Bark's AAR already
keeps its JNI callback classes. A generated `sentry-debug-meta.properties` asset
uses a UUID derived from the **actual mapping bytes**, so incremental builds cannot
associate a changed mapping with stale metadata. Both assembly and bundles depend
on the upload task for mainnet/signet release; regtest never uploads.

Two keep rules restore the no-arg constructors WorkManager 2.9 and its Room 2.5 call
reflectively (`WorkDatabase_Impl`, the input mergers). R8 full mode strips them, which
crashed the signet release at launch. CI's Maestro jobs run the minified regtest release.

Local builds generate metadata without invoking the upload CLI. CI (`CI=true` or
`CI=1`) requires `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT` and `sentry-cli`
on PATH (or `SENTRY_CLI_EXECUTABLE`). Eligible CI releases fail before upload when
these are missing, and fail on CLI errors. Only the mapping file and generated
UUID are passed to `upload-proguard`; no source bundle or app data is uploaded.
CLI diagnostics and credentials are kept out of build output. See the
[Sentry mapping documentation](https://docs.sentry.io/platforms/android/enhance-errors/proguard/).

Mainnet release still refuses a debug signing fallback. For local build verification
without release credentials, pass `-PnoahUnsignedRelease=true` to produce an unsigned
artifact; this explicitly clears the release signing configuration.

### Verification (2026-10-20, base 8b70bb34)

Final focused JVM logging/telemetry/startup coverage: **43 passed / zero failures**, including
four Bark callback tests, 21 telemetry tests, 17 logger tests and one startup test. Tests cover 2,000 concurrent
callbacks, a blocked writer with 1,000 callbacks under a 100 ms budget, overflow
ordering, all 147 shared vectors at SDK and telemetry boundaries, all six build
gates, allowlisted fields, final key/value/aggregate guards, attachment slots,
SDK channel drops and Release Health sessions. Python mapping-upload tests:
**seven passed / zero failures**, without credentials or network access. Application
startup plus the existing WorkManager integration checks: **five passed / zero
failures**, including a merged-manifest check for both removed Sentry providers.
An earlier focused run exceeded the unchanged 100 ms escaped-input redactor budget
(135.9 ms) and had a logger-wrapper assertion failure. The assertion now matches
the SDK's diagnostic wrapper; the subsequent 42-test run passed the same timing
limit without a threshold change.

The full JVM suite ran once: **3,098 passed / zero failures or skips**, **116
suites**, 71.658 seconds of test execution. The exact requested final Nix command
ran once and stopped before tasks at the existing mainnet signing guard because
this worktree has no release credentials. The unsigned fallback ran the full
suite and built regtest debug, then R8 reported the four optional codec classes.
After the narrow rules above, unsigned mainnet release and release vital lint
passed. The last focused run covers the later cached-release UUID guard; the full
suite was not repeated for that metadata-only change. The final focused build
command passed **43 tests** and assembled both regtest debug and unsigned mainnet
release with `--max-workers=2`; release vital lint also passed. Final APK inspection
verified the generated UUID against the mapping bytes and the embedded asset,
confirmed absence of Sentry native libraries and APK signatures, and checked
Application/provider declarations in both merged manifests. All build commands
completed and their single-use Gradle processes exited; shared idle daemons were
left untouched.

Run focused tests with `./gradlew --max-workers=2 :app:testRegtestDebugUnitTest
--tests com.noahwallet.services.BarkLogTest --tests
com.noahwallet.services.AppTelemetryTest --tests
com.noahwallet.services.TelemetryTransportTest --tests
com.noahwallet.services.PlatformStartupTest` from `apps/android`; run upload checks
with `python3 apps/android/PlatformTests/test_sentry_mapping.py -v` from the root.

### Crash reporting verification (2026-10-20, base 9f760f88)

The permanent review-probe regressions reproduced **seven session/capture
failures**, the **disabled-NDK assertion**, and **three pending-hint failures**
before their respective fixes. Native payload scrubbing and Rust error-chain
redaction already passed; those probes are now permanent too. Focused coverage
passes **40 tests**: 22 telemetry, 12 transport, five Bark logger and one startup.
Transport tests use SDK-generated sessions, real `SentryClient` captures for
handled errors/crashes/ANRs, next-launch `EnvelopeSender` cache replay, and native
SIGSEGV/SIGABRT outbox replay with attachments before and after the event. Native
fixtures verify the exact frame/mechanism allowlist and rejection of secret-bearing
thread names. Hint regressions have two-second bounds against the SDK's 15-second
waits; the rejected-cache batch additionally requires completion within 500 ms.

The requested final command ran once through Nix with `--max-workers=2`:
`:app:testRegtestDebugUnitTest :app:assembleRegtestDebug`. All **3,209 tests / 119
suites passed**, with **zero failures, errors or skips**, and regtest debug assembly
passed. Unsigned mainnet release assembly (`-PnoahUnsignedRelease=true`, two
workers) and release vital lint passed; CI mapping upload was disabled for this
local verification. APK, R8 mapping and seed inspection confirmed unchanged
Sentry/Bark JNI class names and packaged `libbark_bolt_ffi.so`, `libsentry-android.so`
and `libsentry.so` for both configured ABIs (`arm64-v8a`, `x86_64`). The release
APK is unsigned and its merged manifest contains none of the three Sentry startup
providers. Evidence is under `/tmp/noah-android-telemetry-fix/`; build processes
completed without stopping shared daemons.

No live Sentry ingestion, credentialed mapping upload or physical/device crash/ANR
was attempted. These require the team's CI credentials or an owned test device.
No emulator, simulator, physical device or Docker was used for this change.
Android-only commits bypassed the client/server Husky checks.

### Direct native backup compatibility (2026-10-20)

Base `5a19f992`. `CrossPlatformBackupTest` exports through Kotlin `BackupService`
and restores the unaltered Swift service export in
`apps/fixtures/native-backups.json`. An in-process loopback HTTP fixture exercises
production Ktor/OkHttp metadata/download paths; SDK snapshot validation,
`WalletRestorer`, filesystem transaction/seed commit and the production
SDK opener all run. Each test owns a fresh datadir and memory seed item, leaving
the app's real wallet untouched.

Both directions match fingerprint **53bdf6b1**, **0 movements** (entire history
empty) and **0 sats in every one of the 14 balance rows**. The committed files
are native-produced v2 envelopes around real synthetic regtest SQLite snapshots,
using the public BIP39 `abandon` × 11 + `about` vector. No funding, S3 upload,
container changes or real credentials were needed. This verifies direct native
file compatibility; it does not repeat prior funded restore acceptance.

V2 contains exactly `manifest.json` + `db.sqlite`; it excludes mnemonic/seed,
auth tokens, MMKV preferences, recurring schedules, logs, widgets and database
sidecars. Both manifests have `formatVersion=2`, `createdAt` (UTC ISO milliseconds),
`snapshotSha256`, `network`, `walletFingerprint`, required nullable
`serverPubkey`/`mailboxPubkey`, and `barkSchemaVersion` (42). Restore validates the
hash, network, fingerprint, schema and optional identities with the SDK before
installation. V1 retains its base64 legacy subtree extraction, including sidecars.
No app/build version is stored: newer app versions using supported format/schema
remain compatible. Future list/envelope/manifest versions (3) and an actual
SQLite migration 9999 are refused before installation, preserving the prior DB
bytes and seed with no journal/open wallet. The existing restorer can still fall
back to an older compatible retained backup after rejecting a candidate.

Restore now classifies newer envelope, manifest and SDK schema versions separately and asks the user to update Noah; wrong-phrase and damaged-backup copy is unchanged. `newerBackupsExplainUpdateRequired` runs normally alongside the pre-install safety tests (no opt-in flag), with classification also covered by JVM tests.

Run only on an emulator you created:

```sh
adb -s YOUR_SERIAL shell am instrument -w \
  -e class 'com.noahwallet.services.CrossPlatformBackupTest#restoresIOSServiceExport,com.noahwallet.services.CrossPlatformBackupTest#refusesNewerBackupsWithoutPartialRestore' \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

No external fixture server or live Noah server is required. The SDK reads local
regtest configuration but no sync/daemon/funding operation runs. See the shared
fixture README for the opt-in native exporter.

Verification: **14 targeted JVM tests** passed; native export generation and both direct-restore/safety instrumentation methods passed (**3 distinct native tests**, including four safety cases). The opt-in copy reproduction failed as expected. An initial restore run lacked the test documents directory; a subsequent process was killed for a startup ANR before tests began. After correcting only test setup, the final two-method run passed in **34.608 seconds**.

The required final command ran once after the last Android code change:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

Result: **3,462 JVM tests / 122 suites / 0 failures / 0 errors / 0 skips**, regtest debug APK assembled, **BUILD SUCCESSFUL in 3m 5s**. All targeted Gradle calls also used `--max-workers=2`. The owned Android 15 Google APIs ARM64 AVD **NoahBackupCross5612** was explicitly mapped to **emulator-5612**, identity-checked, shut down and deleted. Temporary exports/logs were removed and no owned build process remains. No other emulator, physical device or Docker lifecycle was controlled.

### Funded native compatibility (2026-10-20)

Base `0b3b545d`: real Swift `BackupService` → Android `WalletRestorer` and
Kotlin `BackupService` → iOS `WalletRestorer` **passed** with separate funded
wallets (`ce392bd0` and `0bbe2205`). Each retained **37,724 Ark sats**, **10,000
confirmed onchain sats**, movement IDs **1/2** (31,000/7,000 sats; fees 162/114),
and its onchain transaction before and after sync. Each restored wallet then
sent **1,000 Ark sats**, fee **0**, producing successful movement **3** and a
verified Bark receive; remaining balances were **36,724 Ark / 10,000 onchain**.
All 14 balance rows and projected history records matched. Android used a real
atomic encrypted seed file and a unique Android Keystore alias. The generated
Ktor clients' absolute metadata paths are routed to the local producer fixture;
download, crypto, archive/SDK validation, restore, reopen and sends are real.

Rerun from the repository root: `nix develop --command python3 apps/BackupTests/run.py`.
The runner owns and removes both devices (Android port 5618), DerivedData and
results, uses Gradle `--max-workers=2 --no-daemon`, and never operates container
lifecycle. See the [runbook](../BackupTests/README.md) and
movement IDs, transactions, results and limits.
No backup/restore defect was found; production code is unchanged.
