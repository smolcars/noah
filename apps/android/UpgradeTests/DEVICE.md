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
- 2026-10-19 follow-up, bark-bolt-ffi 0.3.1 (FFI item 13): the published AAR's
  `libbark_bolt_ffi.so` (arm64-v8a, armeabi-v7a, x86_64) has every LOAD segment at
  `Align 0x4000`; the built regtest APK passes `zipalign -c -P 16` with all ten
  native libraries at `0x4000`. Reinstalled over the existing regtest app
  (`install -r`, data kept); Android reports `pageSizeCompat=0`. The phone was on
  its lock screen, so the dialog itself wasn't observed.
- Observed afterwards: unlocked the Pixel 6 over adb (swipe-only keyguard) and
  cold-launched the regtest app. It opened straight to Home (96,234 sats, recent
  activity) with no Android App Compatibility / "not 16 KB compatible" dialog,
  which the 0.3.0 build had shown on every launch.
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

## Follow-up: physical Pixel push acceptance, 2026-10-19

**PASS: background callbacks, killed-process delivery, and a visible widget balance
refresh.** This follow-up resolves the server-availability block above. The earlier
run's results remain historical; its pending rows were not reused or rewritten.

### Setup and procedure

- Starting commit `4e64dd52`; reused the installed native regtest APK and upgraded
  wallet on physical Pixel 6 `26011FDF600649`. No APK build, install, data reset,
  instrumentation, emulator action, or application-source change was needed.
- The externally restored server answered `/health` with HTTP 200 on port 3100.
  Existing USB reverse mappings for 3100, 3535 and 18443 remained intact. The server
  and Docker stack were never started, stopped or recreated during this follow-up.
- Loaded the existing server environment file's database and Expo credentials only
  into host-process memory. Selected the single Pixel 6 row with public-key prefix
  `029e0e0eb503` and required the exact previously recorded token SHA-256
  `358f8aafc192e841a3f2083598f568e2083c6e609a66a54af925bdf5b83488ac` before each send.
  No credentials or full push tokens were printed or stored in test evidence.
- Inserted fresh pending correlation rows for this test identity only, then sent
  real Expo pushes using JSON-string `data`, `priority: high` and
  `_contentAvailable: true`. Every send returned HTTP 200 / ticket status `ok`.
  Polled the exact identity/correlation row until `responded` or `Success`; server
  health was checked throughout. Timings below are database row creation-to-callback
  intervals, including the Expo request, rather than pure FCM transport latency.
- Backgrounded with HOME. For each killed-process case independently, issued
  `am kill com.noahwallet.regtest`, verified no app PID and `stopped=false`, then
  sent the push. Never used force-stop. No foreground launch occurred during these
  push/widget checks.

### Accepted callbacks

All timestamps below are UTC; maintenance rows had no error message.

- **Background heartbeat: PASS.** Correlation prefix `786db610-7ae`;
  `pending` → `responded`, 22:30:51.983 → 22:30:52.599, **0.615 s**.
- **Background maintenance: PASS.** Correlation prefix `830bef50657c`;
  `Pending` → `Success`, 22:31:00.508 → 22:31:01.139, **0.631 s**.
- **Killed-process heartbeat: PASS.** Correlation prefix `15ad71e1-a14`;
  `pending` → `responded`, 22:31:11.658 → 22:31:14.169, **2.512 s**.
  The push restarted the absent process as PID 16434.
- **Killed-process maintenance: PASS.** Correlation prefix `0f370fba1cab`;
  `Pending` → `Success`, 22:31:25.602 → 22:31:28.339, **2.737 s**.
  After killing PID 16434, this push restarted the process as PID 16567.
- Native app logs independently recorded `FCM push queued`, `Heartbeat response
  accepted` / `Maintenance status reported`, and `Push job finished`. Acceptance
  rests on the server row transitions, not merely worker completion.

### Widget refresh

- **PASS: real home-screen rendering.** Used adb/uiautomator to open the launcher's
  Widgets picker, search Noah, and add **Noah (Regtest)**. No UI helper APK was
  installed. `dumpsys appwidget` identified widget **5**, owned by
  `com.noahwallet.regtest/com.noahwallet.widgets.NoahWidgetRegtestProvider`.
  The initial home-screen accessibility tree displayed `₿ 95,000` / `95.0K`.
- Sent **1,234 sats** once with `just bark send --wait` to this wallet's registered
  regtest Ark address (address SHA-256 prefix `273016dfffd9ded1`). The current wrapper
  uses `docker compose run --rm --no-deps`; no dependency service was restarted.
  Bark returned exit code 0. Before the next push the widget cache still held 95,000.
- Sent a maintenance push, correlation prefix `a0b9ecf9b390`: server row
  `Pending` → `Success`, 22:33:05.116 → 22:33:10.732, **5.616 s**, no error.
  Both `totalBalance` and `offchainBalance` changed **95,000 → 96,234**; onchain
  and pending stayed 0, expiry threshold 24, closest expiry 450. The legacy
  `lastUpdated` field stayed unchanged; it is not the refresh acceptance criterion.
- Without opening Noah, the launcher's accessibility tree displayed **`₿ 96,234`**
  and **`96.2K`**. Widget 5's host `RemoteViews` instance changed, independently
  proving that the installed provider re-rendered rather than only writing cache.
  A read-only SQLite check confirmed fingerprint **`19f2afe7`**, **3 movements**,
  and **96,234 spendable sats**.

### Screen-off check and handoff

- **Screen-off high-priority heartbeat: PASS.** Used `KEYCODE_SLEEP` and waited
  **185.8 seconds**, verified by Android's `mLastSleepTime` elapsed value, before
  sending correlation prefix `ad73c4b1-eb9`. Expo returned HTTP 200 / ticket `ok`;
  the server row became `responded`, 22:36:38.569 → 22:36:39.239 UTC, **0.670 s**.
  The screen remained off while the callback completed.
- This is **not a true Doze test**: `mScreenOn=false`, `mCharging=true`, device-idle
  `mState=ACTIVE` / `mLightState=ACTIVE`. PowerManager's `mWakefulness=Dozing` describes
  display sleep and does not establish device-idle Doze. No global battery/idle
  setting was changed. Unplugged deep Doze, reboot, token rotation, long-term FCM
  behavior, and the previously identified 16 KB native-library compatibility issue
  remain outside this follow-up's acceptance.
- No native code bug was found and no fix commit was needed. Only this evidence
  document and the corresponding rewrite TODO checkbox changed; `git diff --check`
  passed. No build outputs, dependencies, screenshots, or helper APKs were created.
  Temporary host scripts/data and the device UI dump were removed. The installed
  regtest app, funded wallet, home-screen widget and reverse mappings are retained.

# Android migration safety upgrade, 2026-10-20

**PASS: funded RN → native replacement, including native's first foreground
launch, seed/DB rejection checks, settings/rule continuity, real Expo identity and
launcher continuity.** The procedure was resumed in stages while correcting UI
selectors; the final edited `run.sh` has not had a fresh uninterrupted rerun.

## Environment and isolation

- Base: `c6283d16` (`feat/native-rewrite`); Android bark-bolt-ffi **0.5.0**.
- New owned AVD **NoahMigration5594**, Google APIs Android 15/API 35, ARM64,
  virtual Pixel 6 hardware, explicit serial **emulator-5594**. Opened in T3's Device
  panel. Neither reserved emulator nor the physical Pixel 6 was driven.
- Emulator command used `-port 5594 -grpc 8594 -grpc-use-token -no-snapshot`.
  During DNS diagnosis it was restarted, preserving userdata, with
  `-dns-server 8.8.8.8`. Stack access remained through **10.0.2.2**.
- Built the current RN regtest release locally with embedded JS,
  `CI_DEBUG_SIGNING=true`, `SENTRY_DISABLE_AUTO_UPLOAD=true`, ARM64 and two Gradle
  workers. No RN implementation, iOS or server source was changed.
- RN APK SHA-256: `cac1c175ab9430c2eefcc4577fcd2101ae007d39033bc80ea920c6bf8c2a20a3`.
- Initial passing native APK SHA-256: `da005157bc1aed0db6370589a66083c1c0501ae06c3f2f9555ec4327dd892e7f`.
- Final native APK (verbatim RN backup-password preservation) SHA-256:
  `c41023b5399aa223d2b8572a218f8000320d37e58d46089dca1bd0c7b944c366`.
  Reinstalled with `install -r`; all funded device assertions passed again.
- Matching signing certificate SHA-256:
  `fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`.
- RN version code 37 → native code 100, same `com.noahwallet.regtest` sandbox.
  No uninstall or clear-data occurred after wallet creation. All replacements used
  `install -r`; a diagnostic RN rollback used `install -r -d` before any native
  SDK wallet open, then the final native replacement used `install -r` again.

## RN baseline and observed native result

- Funded Bark from Core with **200,000 sats**, boarded **180,000 sats** (179,670
  after its fee), and mined the required three boarding confirmations. RN received
  **100,000 Ark sats**, then sent **5,000** back through its UI. Core sent RN
  **30,000 onchain sats**, confirmed with one block. Existing Docker services were
  never restarted, recreated or reset; CLI containers used `--no-deps`.
- Same fingerprint **725da6da**, Bark movement IDs **1,2**, **95,000 Ark spendable**,
  and **30,000 confirmed onchain** sats after replacement. Native history contains
  the same 100,000 receive, 5,000 send, and confirmed onchain receive (block 636).
- Maestro created a rule **Android upgrade safety**, recipient
  **upgrade@example.com**, type **lnurl**, **1,234 sats**, monthly, first date
  **2099-01-01**, no end limit; then paused it. Native decoded the same full JSON
  row, not just selected fields. It retained zero runs, zero paid occurrences,
  and no in-flight payment. Assertions repeated after native UI initialized its
  recurring wallet binding: no new movement or balance change, row unchanged.
- Non-default RN settings preserved: **sats**, **EUR**, **hidden balance**, auto-board
  **disabled**, display name **Android upgrade**, email prompt **dismissed**.
  Mailbox-enabled remained **true**, matching the actual RN baseline.
- The original broad `rightOf` switch selectors were no-ops. The assertions caught
  this before any native SDK open. The RN DB still had the exact byte hash
  `3831faf6118e57d5624a60f38920361cf2fb0c02e02368a51b0744f94c25a183`, and no native
  seed file existed. RN was reinstalled in place; its actual **No, turn off**
  auto-board prompt set the preference, and its email prompt was dismissed.
  The committed flow now uses that explicit action. The funded wallet was never
  recreated and no funding/send operation was repeated.
- Before valid opening, the test tried a **different valid BIP39 seed** and an
  **invalid seed**. Both failed, all datadir file hashes stayed identical, and no
  native seed copy appeared. The valid seed then opened the original DB and was
  copied into Keystore storage; all legacy seed source files remained byte-identical.
- RN MMKV file bytes were identical immediately across final APK replacement.
  Typed settings and complete rule data passed before and after first native UI
  launch. A final safety review retained valid seed credential strings byte-for-byte:
  RN uses the exact string as a backup password. Validation normalizes only for
  BIP39 checks/SDK parsing; even formatting-only source differences block selection. Native's ordinary cold launch was also visually checked: hidden balance
  and the three expected history entries, with no recovery/onboarding gate.

## Expo and launcher proof

RN's JS `Device.isDevice` guard deliberately skips emulator registration. The
opt-in platform-only `RNPushBaseline` instrumentation used RN's own InstallationId
and Firebase runtime, opened RN in the foreground, obtained a **real** Expo token,
and registered it with Noah using RN's existing auth record. It never accessed or
exported the seed. Early background attempts failed with UnknownHostException;
foreground registration succeeded. This proves identity/token continuity, not
RN's automatic emulator registration path.

Native exchanged and registered its own FCM/Expo token and matched both hashes:

- Installation UUID SHA-256:
  `5510c8659b9dd2f05cc2005aec75b2368db6fcbe751da772a28c18c5a2a4ad87`.
- Expo token SHA-256:
  `4935792d8d1aa0ab271e3c496bd0e401e29e4beae58cf8fe5e64e0b7b421f547`.
- Widget provider metadata SHA-256:
  `e133e4c35196a5b179b6a2332a6a76e99b6e0ede627f30abeaebf016be9d4f7d`.

Placed widget **2** retained provider
`com.noahwallet.regtest/com.noahwallet.widgets.NoahWidgetRegtestProvider` and
Pixel Launcher host **1024**, package `com.google.android.apps.nexuslauncher`.
Resolved layout name, dimensions, resize/category and update-period metadata
matched. Before replacement its visible cache showed 125,000 total / 30.0K onchain
/ 95.0K offchain. RN publishes no dynamic shortcuts; the test pinned
**noah-upgrade-check** to `com.noahwallet.MainActivity`, and native retained the same
ID and component. A Maestro transport disconnect after interactive inspection was
handled by confirming launcher actions through the owned device's agent-device UI.

## Checks and cleanup

- Native instrumentation: **OK (1 test)**, initially 4.053 seconds; all assertions
  passed again on the final APK in 6.172 seconds. Both runs included funded,
  onchain, recurring, settings, token, widget and shortcut expectations present.
- Targeted Android iteration: **49 tests / 6 suites**, then **50 / 6** after adding
  verbatim-password validation coverage, zero failures. A second round-trip test
  covers raw credential migration and refusal to replace it with canonical text.
- Required final command (repeated after the final backup-password hardening):
  `nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'`:
  **2,838 tests / 75 suites**, zero failures/skips; APK assembly passed.
- Launcher gate self-check: **3 Python tests** passed (including changed/missing
  widget identity and missing shortcut rejection). Shell syntax and diff whitespace
  checks passed. Commit hooks passed client lint, compiler lint (202 files, zero
  diagnostics), **148 RN tests**, and typecheck.
- Shared fixtures: **23 launch cases**, **9 RN-generated store cases**, plus the
  unchanged recurring-storage/range suites. The Android-specific store fixture is
  `apps/fixtures/rn-stores-android-shapes.json`; its generator and complete store
  inventory are linked from [STORES.md](STORES.md).
- Cleanup confirmation is recorded below after shutdown. Private logs/screenshots,
  wallet snapshots and temporary continuation scripts are removed; no mnemonic,
  private key, signature or raw push/auth token is retained in this evidence.

Cleanup completed: immediately before shutdown, `adb -s emulator-5594 emu avd name`
returned **NoahMigration5594 / OK**. T3 `device_close(shutdown=true)` powered it off;
adb serial absence and QEMU process absence were checked before
`avdmanager delete avd -n NoahMigration5594` reported deletion. The AVD registry
and directory were verified absent. Owned temporary artifacts and scripts were
removed after recording these results.

## Second clean-start verification on emulator-5604

Created a second owned **NoahMigrationFinal5604** Google APIs/API 35 ARM64 AVD,
explicit `-port 5604 -grpc 8604 -grpc-use-token -dns-server 8.8.8.8`. Reused the
locally built matching RN release and final native APK. The current shared Bark
wallet had 99,270 spendable sats, so 30,000 existing confirmed onchain sats were
boarded (29,670 net), with exactly three required confirmations. No extra Core
funding or Docker service restart was needed.

RN creation, 100k/5k Ark flows, confirmed 30k onchain receive, paused recurring
rule and all six non-default settings ran unattended from the updated script.
The widget drawer expanded its preview below the viewport: an additional
`scrollUntilVisible` was added and the complete corrected widget-placement flow
passed through Maestro. The shortcut was also placed through Maestro; its home
page text was off-screen, so that viewport assertion was removed in favour of
the existing persisted ID/component checks. The remaining upgrade ran successfully
on this same funded RN wallet, without reinstalling RN, clearing state, or
repeating payments. A fresh uninterrupted entry-point run **after these final two
launcher selector edits** remains unperformed; each corrected flow and the full
native assertions passed.

Final result: **OK (1 test), 4.351 seconds**, fingerprint **795dbcf2**, movements
**1,2**, 95,000 Ark / 30,000 confirmed onchain sats, exact paused rule and settings,
unchanged real Expo identity, widget **2** / Pixel Launcher host **1024**, and
**noah-upgrade-check → com.noahwallet.MainActivity**. Foreign/invalid seed
rejections performed no DB/seed writes; first native foreground launch executed
no payment.

- Installation UUID SHA-256:
  `a1aee31d3f95f931e4e450a56d4c4651f3b89992f3c0742f6c662581b9203874`.
- Expo token SHA-256:
  `39c9e244a44117f327d4585e97fd7e99f6fa3ef01cb53076bcc49bde5cf53714`.
- Provider metadata SHA-256 remained
  `e133e4c35196a5b179b6a2332a6a76e99b6e0ede627f30abeaebf016be9d4f7d`.

Before cleanup, `adb -s emulator-5604 emu avd name` returned
**NoahMigrationFinal5604 / OK**. It was shut down through T3's Device panel; serial
and QEMU-process absence were checked before deleting this AVD. Both owned AVDs
and their private artifacts were removed. Reserved emulators and the physical
Pixel 6 were left untouched.
