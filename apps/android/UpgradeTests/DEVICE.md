# RN → native upgrade on a physical Android phone

Emulators can't prove real push delivery, killed-process wake-ups or widget
refresh on a launcher. This is the manual procedure for a phone with Google Play
services. It installs only `com.noahwallet.regtest` and `com.noahwallet.regtest.test`;
never install mainnet or signet builds, and never uninstall or clear the wallet
package between RN and native. The automated emulator version is
[`run.sh`](README.md).

## Setup

1. Check the regtest stack and Noah server are healthy without restarting them.
   Forward the stack to the phone: `adb -s <serial> reverse tcp:<port> tcp:<port>`
   for the Noah server, Ark (3535) and Bitcoin RPC (18443).
2. `bun install --frozen-lockfile`, then build the RN release inside Nix, from
   `client/android`, with `CI_DEBUG_SIGNING=true SENTRY_DISABLE_AUTO_UPLOAD=true
   EXPO_PUBLIC_REGTEST_URL=127.0.0.1 EXPO_PUBLIC_REGTEST_SERVER_URL=http://127.0.0.1:<port>
   ./gradlew assembleRegtestRelease -PreactNativeArchitectures=arm64-v8a --max-workers=2`.
3. In `apps/android`, build native and its tests, pointing them at the forwarded
   server:

   ```sh
   ./gradlew --max-workers=2 -PnoahRegtestHost=127.0.0.1 -PnoahRegtestServerURL=http://127.0.0.1:<port> \
     :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest
   ```

   Check that RN and native share a signing certificate with
   `apksigner verify --print-certs` before installing anything.

## Upgrade

1. Install RN and complete onboarding by hand. Allow notifications. Don't print or
   screenshot the recovery screen.
2. Fund it: `just bark send --wait <rn-ark-address> "100000 sats"`, then send
   5,000 sats back to a Bark address through RN's Send screen. Check RN's Home and
   History.
3. On a phone, RN registers its push token itself. Read this wallet's token from the
   server database privately and keep only its SHA-256. (`RNPushBaseline`, which
   `run.sh` uses, is emulator-only: it registers with `10.0.2.2:3000`.)
4. Force-stop RN and install native with `adb install -r`. Before launching it, use
   `run-as` (native is debuggable) to read what RN left, the same way `run.sh` does:
   the fingerprint from `bark_properties`, the movement count and IDs from
   `bark_movements` and spendable sats from `vtxo_view` in
   `files/noah-data-regtest/db.sqlite`, and the SHA-256 of the Expo installation
   UUID (`no_backup/expo_notifications_installation_uuid.txt` or its fallbacks).
5. Install native's instrumentation APK, then run `UpgradeCompatibilityTest` with
   `-e noahUpgradeCheck 1`, `expoIdSha256`, `expoTokenSha256` and the wallet
   expectation arguments that `run.sh` passes. A pass is `OK (1 test)`.
6. Launch native normally. Home must open the same wallet, with no new
   notification-permission prompt. The server's push-token row for this wallet
   must keep the same token hash, with a newer update time.

## Push and widget checks

- Background the app with HOME. For this wallet only, insert a pending heartbeat
  and a pending maintenance row on the server and send each through Expo with the
  server's credentials, a JSON-string `data` payload, `priority: high` and
  `_contentAvailable: true`. A pass is the server row becoming `responded` /
  `Success`. A completed WorkManager job is not evidence.
- Repeat after `adb shell am kill com.noahwallet.regtest` (never force-stop); the
  push must restart the process and the row must still be answered.
- Place the Noah (Regtest) widget, pay the wallet with `just bark send --wait`,
  then send a maintenance push. Without opening Noah, the launcher must show the new
  balance (`dumpsys appwidget` and the launcher's accessibility tree).
- Keep credentials, full tokens and the phrase out of logs and notes; record hashes
  and correlation-ID prefixes only.

## Limits

A screen-off check while charging is not a Doze test; deep Doze needs an unplugged,
idle device. Reboot, token rotation and long-term FCM behaviour are not covered.
Check 16 KB page alignment separately with `zipalign -c -P 16 <apk>`.
