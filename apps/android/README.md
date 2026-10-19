# Noah native Android scaffold

Phase 0.2: a single Kotlin/Compose `:app` module, four Navigation 3 placeholder
destinations and the existing RemoteViews widgets. No wallet initialization,
migration, payment or push handling runs yet. Firebase messaging auto-init is
disabled. CameraX and ZXing will be added with scanning.

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
same sandbox. This scaffold does not open/write the wallet DB, secrets or MMKV.
Data migration and funded-wallet continuity tests belong to Phase 0.5; the shell
alone is not a usable replacement wallet.

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
