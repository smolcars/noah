# Noah native Android

Kotlin/Compose `:app` with the upgrade compatibility layer, backup envelope
crypto, four Navigation 3 placeholder destinations and the existing widgets.
Launch checks the seed and existing database before showing the shell. It never
creates a wallet or deletes legacy secrets. Wallet operations, onboarding and
push registration remain future work; Firebase messaging auto-init is disabled.

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
alone is not a usable replacement wallet; funded-wallet operations are still
outside this implementation.

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

`io.github.niteshbalusu11:bark-bolt-ffi-android:0.1.0@aar` resolves directly from
[the v0.1.0 GitHub release](https://github.com/smolcars/bark-bolt-ffi/releases/tag/v0.1.0).
An exclusive Ivy repository filters to `io.github.niteshbalusu11` and uses
`https://github.com/smolcars/bark-bolt-ffi/releases/download/v[revision]/[module]-[revision].[ext]`.
No AAR is committed. Artifact-only Ivy metadata does not import the release POM,
so coroutines-core is declared explicitly.

`gradle/verification-metadata.xml` pins the AAR's SHA-256 from the release
[`SHA256SUMS`](https://github.com/smolcars/bark-bolt-ffi/releases/download/v0.1.0/SHA256SUMS):

```text
87ba0c4946ca417a66787451892de452127b3890b4ce1c6941d07652cc61179c
```

Gradle's default strict verification rejects a mismatched AAR. Verification is
currently scoped to this release group; other Maven/plugin dependencies are
explicitly trusted, not checksum-audited. When upgrading the SDK, independently
compare the downloaded artifact with the new release's `SHA256SUMS` before
changing the catalog and verification entry. `BarkSdkLinkTest` references the
SDK's `Config` class and proves the AAR is on the compiled test classpath; it does
not execute Android JNI on the host JVM or create/log a mnemonic.

## Upgrade compatibility and secret storage

`services/storage/KeychainPassword.kt` reads RN secrets without migrating or
removing their original records. Precedence is DataStore `RN_KEYCHAIN`, legacy
SharedPreferences `RN_KEYCHAIN`, then (mnemonic only) `noah_native_secrets`.
Only complete absence permits fallback. Partial records, unsupported ciphers,
missing keys, malformed UTF-8 and decryption failures return `Unavailable`.
`Found.toString()` redacts its value; no provider exception text is retained.
The auth-token reader uses the same RN formats but not the mnemonic fallback.

`SeedStorage.read()` first reads the native store. On its first successful legacy
read, it writes and reads back the seed, compares the values, and only then returns
it. Migration is serialized across instances. An unreadable native record blocks
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
Secure-storage failures are retried when the activity resumes.

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
nix develop --command apps/android/UpgradeTests/run.sh emulator-5556
# Or reuse an already built RN regtest release APK:
nix develop --command apps/android/UpgradeTests/run.sh emulator-5556 /absolute/path/to/app-regtest-release.apk
```

The script refuses an existing Noah regtest install, builds RN release with
`CI_DEBUG_SIGNING=true` and `SENTRY_DISABLE_AUTO_UPLOAD=true` (embedded JS, no Metro or Sentry upload), checks both signing certificates,
creates the wallet using the RN Maestro subflows, captures only a hash of the Expo
ID, and replaces RN using `adb install -r`. No uninstall or clear-data operation
occurs. The gated test asserts seed readability and verified migration, unchanged
legacy secret files, RN DB presence, initialized MMKV state, `existingWallet`, and
unchanged Expo ID when RN had one. Maestro artifacts are private and deleted on
exit because a failed onboarding flow can capture a recovery phrase.

### Verification on 2026-10-19

- All three debug variants, `testRegtestDebugUnitTest` (16 tests, zero failures),
  and regtest/signet instrumented test APKs built successfully in Nix/JDK 17.
- On the Android 15 Google APIs ARM64 `NoahNativeUpgrade` emulator, the signet
  instrumentation run returned `OK (6 tests)`: five enabled tests passed and the
  upgrade-only test was skipped. GCM/CBC were each tested through both storage
  locations. The fresh-install launch screen displayed “Welcome to Noah”.
- The RN regtest release APK built successfully with embedded JS, debug signing,
  Sentry auto-upload disabled, ARM64 only and two Gradle workers. RN and native
  APKs have the same SHA-256 signing certificate:
  `fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`.
- **The required RN-to-native upgrade has not passed yet.** Its script stopped
  at the stack preflight, before installing RN:

  ```text
  Signing certificates match: fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c
  Regtest prerequisite unavailable: host port 3535. Restore the existing stack before running the upgrade.
  ```

  OrbStack was stopped after host resource pressure. Restarting OrbStack and
  running `just up` preserved the existing volumes, but Bitcoin reported height
  106 while CLN recorded 255 and captaind panicked with “bitcoind chain went
  backward”. No stack reset, replacement wallet creation or mining was performed.
  Recover the existing working stack/override, then rerun the script on the fresh
  regtest sandbox (only the separate signet test app is currently installed).

Unverified: actual RN-written seed/MMKV/Expo continuity until the upgrade gate
passes; physical-device Keystore behavior; an API 24/25 device run; funded-wallet
balance/history continuity and opening the DB through the new wallet SDK. Those
wallet operations are not implemented by this compatibility layer.
