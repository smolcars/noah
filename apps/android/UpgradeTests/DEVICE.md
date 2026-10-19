# Pixel 6 RN → native upgrade, 2026-10-19

**Partial pass; live push acceptance is blocked by the existing server on port
3100 becoming unavailable.** Wallet and Expo-token continuity passed on the real
phone. This run does not establish successful background callbacks, killed-process
delivery, or a changed widget cache.

## Environment and boundaries

- Physical Pixel 6, Android 17/API 37, USB serial `26011FDF600649`, Google Play
  services. Actual memory page size: 4096 bytes.
- Repository starting commit: `62b8b5e5`; bark-bolt-ffi Android **0.3.0**.
- Only `com.noahwallet.regtest` and `com.noahwallet.regtest.test` were installed.
  No emulator was driven and no mainnet/signet APK was installed.
- Existing containers and the server were never started, stopped, or recreated.
- USB reverse forwarding: 3100 (Noah), 3535 (Ark), 18443 (Bitcoin RPC).
- RN: release, version code 37, `0.1.14-regtest`, target API 36.
- Native: debug, version code 100, `0.2.0-regtest`, target API 37.
- Both APKs and the instrumentation APK used signing-certificate SHA-256
  `fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`.
- RN APK SHA-256: `896c86569bda13faf9c4ad5913643755fb8ffcb1b6ccdc8659557d9024a63579`.
- Native APK SHA-256: `0535a7483e5bf7a836950758c32fb2f72589cad81a2f53537df754597413f21a`.

## Procedure

1. Read the Android README, emulator upgrade script, Expo push research, and
   rewrite TODO. Verify server health and existing container state read-only.
2. Run `bun install --frozen-lockfile`. Build RN through the documented Gradle
   release task, inside `nix develop`, with
   `EXPO_PUBLIC_REGTEST_URL=127.0.0.1`,
   `EXPO_PUBLIC_REGTEST_SERVER_URL=http://127.0.0.1:3100`,
   `CI_DEBUG_SIGNING=true`, `SENTRY_DISABLE_AUTO_UPLOAD=true`, and
   `-PreactNativeArchitectures=arm64-v8a`. Limit build parallelism to two workers.
3. Build native `:app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest`
   with `-PnoahRegtestHost=127.0.0.1` and
   `-PnoahRegtestServerURL=http://127.0.0.1:3100`. Check certificates with
   `apksigner verify --print-certs` before installation.
4. Install RN. Use adb UI dumps and input to follow the existing Maestro
   onboarding steps. The recovery screen is never printed or screenshotted.
   Skip email, battery-optimization exemption, and Lightning username; accept
   the Android notification permission prompt.
5. Query the running server's database using its existing environment file,
   loading credentials only in memory. Identify the new Pixel row, retain only
   a token hash in evidence, and use that wallet's registered Ark address for
   funding. There were no Pixel 6 token rows before this run.
6. Send 100,000 sats using `just bark send --wait`, which uses `--no-deps`.
   Generate a Bark destination and send 5,000 sats back through RN's Send UI.
   Verify RN Home and History before replacing the app.
7. Because the physical phone is not rooted, use a temporary same-certificate
   `com.noahwallet.regtest.test` instrumentation APK to capture the RN baseline.
   Its platform-only Instrumentation reads SQLite with `OPEN_READONLY`, queries
   `bark_properties`, `bark_movements`, and spendable `vtxo_view` rows, and hashes
   the Expo UUID and RN keychain file. It does not decrypt/export secrets or
   modify wallet state. This avoids modifying the RN release APK or source.
8. Install native with `adb -s 26011FDF600649 install -r`. Read the preserved
   files through `run-as` before native launch and compare with the RN baseline.
   Replace only the temporary `.test` harness with the native instrumentation
   APK: its version code 0 is lower than the temporary harness's 1, requiring
   removal of the `.test` package. The wallet package is never removed/cleared.
9. Run `UpgradeCompatibilityTest` with `noahUpgradeCheck=1`, the RN fingerprint,
   movement count, spendable amount, and Expo UUID hash. Launch native normally.
10. Compare the server token hash and update timestamp. Background the app with
    HOME. Insert one pending heartbeat and one pending maintenance correlation
    row for this test wallet only. Send each through Expo using the existing
    server credentials, a JSON-string `data` payload, `priority: high`, and
    `_contentAvailable: true`. Inspect callback rows, native logs, WorkManager,
    widget preferences, and server health.

## Results

- **RN onboarding and push registration: PASS.** Notification permission granted;
  wallet pubkey prefix `029e0e0eb503`. Server token row created
  `2026-10-19T21:51:43.416Z`.
- **Funded RN state: PASS.** Home showed 95,000 sats. History showed successful
  +100,000 and −5,000 Ark movements. Read-only RN instrumentation captured
  fingerprint `19f2afe7`, **2 movements**, **95,000 spendable sats**.
- **In-place native upgrade: PASS.** The same values remained immediately after
  installation and after opening. `UpgradeCompatibilityTest`: **OK (1 test)**,
  0.37 s. It verified readable RN seed, verified native seed copy, unchanged
  legacy secret files, initialized MMKV, `existingWallet`, and the SDK summary.
  Existing onchain storage opens through SDK 0.3.0 `openExisting`.
- **Native Home: PASS.** The current scaffold's Home/Coming soon/Build: regtest
  shell appeared. It is not yet a complete payment UI.
- **Expo continuity: PASS.** UUID SHA-256 stayed
  `52cbac07ba6b623f8b0002b2e1bbf311631a8e0125a60ec846b968cddd2e9ae7`.
  RN and native server token SHA-256 were both
  `358f8aafc192e841a3f2083598f568e2083c6e609a66a54af925bdf5b83488ac`.
  The existing row's update timestamp advanced to `2026-10-19T21:55:02.140Z`;
  native logs confirmed FCM token obtained, Expo token obtained, and registration.
  Notification permission remained granted without another permission request.
- **Background transport/handler execution: PASS; callbacks: BLOCKED.** Expo
  accepted both sends (HTTP 200, ticket status `ok`). Native queued heartbeat at
  21:55:58.494 UTC and maintenance at 21:56:10.011 UTC. Their WorkManager rows
  completed in state 2 with one attempt and expedited scheduling enabled.
  Logs nevertheless reported `Failed to answer heartbeat` and
  `Failed to report maintenance status`. After 90 seconds the server rows remained
  `pending` / `Pending`. A completed worker is not evidence of an accepted callback.
  Correlation prefixes: heartbeat `b74f7a59-aee`, maintenance `0e3da61258c6`.
- **Widget: preservation observed; refresh not independently proven.** Cached
  total/offchain balance remained 95,000, onchain/pending 0, expiry threshold 24,
  closest expiry 453. The existing RN `lastUpdated` value stayed unchanged.
  No widget was placed, and no changed cache value was demonstrated.
- **Killed-process delivery: NOT RUN.** Stopped at the server availability block
  rather than creating additional requests with impossible callbacks.

## Real-device findings and remaining work

- Port 3100 answered health checks at setup and accepted native registration,
  then stopped listening before the background callbacks. Repeated health probes
  returned connection refused/HTTP status 000, and no listener was present.
  Ports 3535 and 18443 remained reachable. The cause of the server's disappearance
  was not determined; this run did not restart it. Restore it externally, then
  repeat background and process-killed pushes with fresh correlation rows.
- Android displayed an **Android App Compatibility** warning: the debug app is
  not 16 KB compatible; `libbark_bolt_ffi.so` has an unaligned LOAD segment.
  The dialog also listed `libmmkv.so`, `libandroidx.graphics.path.so`, and
  `libdatastore_shared_counter.so` with “Unknown error”. Dismissing OK allowed
  launch. Investigate the SDK AAR/packaging before release; this 4 KB-page phone
  run does not validate operation on a 16 KB-page device.
- Play Protect offered to upload the native test APK for a security check;
  declined the upload without changing a global setting.
- Phone was USB-powered, screen on, device-idle state ACTIVE, app standby bucket
  10. Battery exemption was skipped. Doze, screen-off delivery, reboot, token
  rotation, and long-term FCM deprioritization remain untested. Both observed
  messages took the native high-priority/expedited path.
- Native JVM suite: **89/89 passed** with default configuration. With the phone
  host override, 88/89 passed: `configsConvertFeeUnitsAndUseAndroidHost` hard-codes
  `10.0.2.2`, so its expected host differs from the intentional phone override.
- No application-source changes were made. Native regtest is left installed;
  temporary test APKs, private scratch data, and worktree build/dependency outputs
  are removed at handoff. The three USB reverse mappings are retained for the
  installed build.
