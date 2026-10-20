# Pixel 6 RN → native upgrade, 2026-10-19

## Settings upgrade policy — 2026-10-20

This supersedes the historical MMKV recovery notes below. Seed + DB opens Home;
only explicit readable `isInitialized=false` resumes onboarding. Settings are
best-effort, imported once from an integrity-verified immutable snapshot through
scratch MMKV. All native writes use the durable JSON store; live RN files stay
unchanged. Damaged native files use defaults and the settings notice, without
reimporting RN. Background opens never write seeds and require Home admission.

RN recurring schedules never import. Home shows the one-time recurring notice;
the existing server-sync path sends `{"schedules":[]}` after admission and retains
pending state on failure. Upgrade assertions now inspect the native store and
expect empty recurring state plus the notice. Native-created schedules retain
the existing ledger, authority and journal. Deletion removes RN files/snapshot
and leaves an empty native import marker through re-onboarding.

See [the complete storage policy](STORES.md#settings-upgrade-policy--2026-10-20)
for file format, sync ordering, notices, server verification and corruption rules.


Current funded acceptance uses `run.sh` from its entry point with both
`INSTALL_WHILE_RUNNING=0` and `=1` on a newly created, root-capable Google APIs
AVD. The runner checks the first Home's recurring reset notice, absence after a
process restart, an empty native recurring list, unchanged live RN data/CRC files
through native launch, the same DB inode, all movement rows, balances and saved
settings. Language is set to Swedish through RN before replacement; biometrics
must retain the RN flag. Esplora is compared if present (regtest uses Core).
A successful empty recurring sync must appear in the existing server log for
this wallet's public login key after replacement. The RN-runtime Expo harness
still obtains real FCM/Expo tokens because RN's JS emulator guard skips automatic
registration. No seed, auth token or push token enters the result document.

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
  unchanged recurring-storage/range suites. Round 1 superseded the separate store
  fixtures with `apps/fixtures/rn-store-compatibility.json` (196 raw cases shared
  by both apps); its generator and inventory are linked from [STORES.md](STORES.md).
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

## Historical MMKV interrupted-update safety (superseded 2026-10-20)

Native now registers CRC and length **OnErrorRecover** callbacks through MMKV's
initialization handler. Warning/error callbacks send fixed diagnostics through
the redacting `AppLog`; MMKV messages and stored values are not forwarded.
The first open is serialized across all `SettingsStorage` callers. Before
mapping `files/mmkv/noah-wallet-storage`, native durably copies it and its CRC
to immutable `noBackupFilesDir/mmkv.native-backup` (excluded from cloud backup
and device transfer). A synced manifest outside
MMKV records first-open completion and the original actual sizes. Copy/sync
failure leaves storage unavailable, suppresses writes and retries next process
launch. The backup is never replaced and is retained until wallet deletion.

If backup metadata records content but no RN keys survive, native closes MMKV,
restores the original pair once and reopens with recovery enabled. A durable
pending marker resumes a crashed restore before mapping either file. If RN data
is still absent, startup and headless wallet opens block with a selectable,
copy-safe `STORAGE_UNSUPPORTED` support state. Existing DB/seed with a missing
`wallet-storage` resume onboarding (user decision, 2026-10-20). RN's Delete Wallet
unlinks the MMKV folder while it's open, so a wallet re-created in that session
never persisted its stores; blocking would lock users out of a valid wallet. An
explicit false flag also resumes unfinished onboarding.
After the first successful open and validation, a separate
`noBackupFilesDir/mmkv.native-retired` file atomically records admitted RN key
names and is fsynced together with its parent directory before any native
mutation. Newly introduced RN keys join that record before being written.
Subsequent CRC/length recovery or loss of an admitted key blocks instead of
restoring or restarting onboarding. Snapshot restoration is then disabled;
the original pair remains for explicit/support recovery only. Wallet deletion
retires with no required keys, clears/syncs live settings and deletes both
published and staging snapshots after local wallet/seed deletion. The retirement
record survives deletion → re-onboarding; no deleted snapshot is recreated.
Recurring storage checks health before reading policies; the separate recurring
journal prevents replay when MMKV returns an older confirmed policy.

`run.sh` now defaults to `INSTALL_WHILE_RUNNING=1`. After a stopped baseline it
relaunches RN and repeatedly selects EUR through real RN controls, confirms an
MMKV byte change, then runs `adb install -r` while the writer remains active.
No force-stop occurs between the writer and replacement. The prior stopped
hash comparison remains available with `INSTALL_WHILE_RUNNING=0`; run **both**
for acceptance. In running mode a pre-install hash cannot equal the final file
by contract because RN is writing, so continuity is checked by the existing
native wallet/preferences/recurring assertions instead. Writer/flow artifacts
stay private and are removed on exit. Only use an owned emulator at port >=5590.

The shared, actual RN mmap fixture is in `../../fixtures/rn-mmkv/README.md`.
`MMKVRecoveryTest` runs real JNI corruption checks on device: former discard
behavior loses the same CRC-damaged bytes that recovery retains; length and
truncated-write recovery, exact backup bytes, one-time restore, failed backup,
unrecoverable storage and missing-wallet-state gates are exercised. JVM backup
tests cover fsync failures/restart retry, interrupted restoration, immutable
publication, last-confirmed sizes and deletion markers.

Verification for this change (base `37ab2125`, branch `t3/mmkv-upgrade-recovery`):

- Required full command ran once, inside Nix, with `--max-workers=2`:
  `:app:testRegtestDebugUnitTest :app:assembleRegtestDebug` — **2,890 tests /
  83 suites / zero failures, errors or skips**; APK assembled successfully.
- Targeted backup tests: **8 passed**. Targeted executor rollback test passed.
- Real Android 15/API 35 instrumentation on owned `NoahMMKV79e4`,
  **emulator-5590: 5 passed** (`MMKVRecoveryTest`), including the real native
  discard/recovery comparison. Instrumentation APK compiled successfully.
- Confirmed the emulator's AVD name, shut it down, verified serial/process
  absence and its exact AVD path, then deleted only that AVD and verified removal.
- A full running-RN Android replacement was not repeated: this checkout has no
  built RN APK or installed RN JavaScript dependencies. The actual RN-byte JNI
  checks passed; the running replacement procedure was exercised on iOS.
  No physical device, other agent's emulator, or Docker container was touched.

## Historical MMKV recovery review fixes (2026-10-20; base `172446b6`)

`SettingsStorage` distinguishes absent keys from undecodable stored values and
uses the MMKV byte getter with a strict UTF-8 decoder; JNI string replacement
characters cannot disguise damaged bytes. Both initial validation and every
native mutation check consumed stores and admitted-key presence.

Every `AppServices.ensureWalletOpen` requires `isInitialized == true` before
recovery and again in `StoredWalletOpener.ensureOpen`. Foreground onboarding can
reuse the existing seed+DB. Committed deletion recovery waits for foreground so
a headless path cannot delete a seed. Interrupted restore recovery remains
seed-read-only; native seed migration still occurs only after a verified
foreground SDK open.

Audited entry classes: `NoahFirebaseMessagingService`, `NoahUnifiedPushService`,
`PushWorker`, `PushRegistrationWorker`, `RecurringRuntime`,
`RecurringTimeReceiver`, `RecurringForegroundRunner`, `RecurringReminderWorker`,
`NoahWidgetProvider` and its mainnet/signet/regtest subclasses. FCM/UnifiedPush
queue `PushWorker`, whose wallet jobs use the shared opener. Registration has
its own onboarding gate. Recurring execution uses the shared opener; reminders
also require initialization. Widgets only render cached preferences. Cleanup
workers (`HistoryExportCleanup`, `SettingsShareCleanup`) do not open wallets.

`StoredWalletOpenerTest` exercises all five wallet push types with absent,
missing-flag and false initialization states, asserting no open and no seed
writes, then checks foreground reuse and initialized headless success.
`HeadlessEntryPointsTest` adds one source-wiring contract per entry class plus
a deletion-recovery ordering check; framework callbacks themselves are not run
in the plain JVM. `MMKVRecoveryTest` adds real-JNI suspension/key-loss/UTF-8,
post-retirement CRC, no-backup, deletion and nine shared launch-vector checks.

A negative-control run reinstating the old restore, headless and forgiving
UTF-8 behavior produced **11 failures / 30 tests**. Restoring the fixes passed
all 30; the final targeted backup/admission/UTF-8 set passed 32 checks (including
the added no-backup/deletion-order tests across the targeted runs).
No Android emulator, physical device or Docker was used. Real-JNI regression
probes are compiled only in this review, pending an authorized device run.

Required final command ran once inside Nix with `--max-workers=2`:
`:app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin`
— **2,933 tests / 91 suites / zero failures, errors or skips**; regtest APK
assembly and instrumentation Kotlin compilation both succeeded. All 12
`MMKVRecoveryTest` JNI methods compile; they were not executed on a device.
The nine shared storage vectors passed in the equivalent real-MMKV iOS tests.
Durable pause/cancel tombstones remain a separate task; no `RecurringExecutor`
or `RecurringStore` implementation was changed.

## Historical MMKV migration safety, review round 2 (2026-10-20; base `4f7228c5`)

Recovery rejection now survives process restarts. Before returning **recover**
from either MMKV error callback, native atomically writes and syncs a quarantine
file outside MMKV, then syncs its parent directory. The identical UTF-8 format on
both platforms is `1\nnoah-wallet-storage\n` (version, store name, trailing newline);
it contains no setting values or secrets. iOS uses
`Documents/mmkv.native-quarantine`; Android uses
`noBackupFilesDir/mmkv.native-quarantine`. Marker write/sync failure fails closed
for that launch; an unreadable or unknown marker also blocks.

The admission state machine is:

1. Before restoration retirement, inspect a disposable copy of the immutable
   pre-open snapshot. Inventory **all eight RN keys**, including recurring.
   Any missing snapshot key, undecodable consumed envelope, or newly quarantined
   recovery closes MMKV and restores the original pair once. Revalidate against
   the snapshot inventory before retiring restoration and clearing quarantine.
   The existing restore-pending journal still resumes before mapping files.
   If restoration/validation fails, remain blocked. An already restored pair may
   finish admission after a crash; another recovery/loss cannot trigger a second
   restoration. A snapshot that itself never had wallet-storage may still resume
   foreground onboarding using the existing seed and DB.
2. After retirement, never restore the RN snapshot. Recovery with readable,
   complete admitted stores becomes **SETTINGS_RECOVERED**, a selectable support
   message explaining that settings came from an older state and listing
   biometrics, wallet suspension, balance hiding and backup settings to review.
   Missing admitted keys or undecodable envelopes remain **STORAGE_UNSUPPORTED**
   and offer no bypass. Retrying/restarting cannot clear quarantine.
3. Only the foreground launch gate's explicit **Continue** action reopens and
   validates recovered settings, syncs retirement, then removes the marker and
   syncs its directory. Failed acknowledgement retains quarantine. No seed is
   read/written to acknowledge settings. Push, background tasks, workers, widgets
   and recurring work cannot open a quarantined wallet. Widgets only render
   existing caches; they do not open MMKV or a wallet. The independent recurring
   policy ledger and revision-bound settlement remain unchanged and still gate
   recovered schedules after acknowledgement.

Recurring envelope admission uses the existing recurring codec. Undecodable
bytes, malformed envelopes and missing admitted recurring keys block both
opening and later binding/mutation. Strict-parse unsupported rows remain visible
and stopped; they do not block the wallet and are never replaced by an empty
schedule envelope. Esplora remains inventoried but inactive as before.

Shared `../../fixtures/mmkv-launch-state.json` now has **15 vectors**, adding
partial first-open missing/undecodable wallet state, a snapshot with no wallet
state, persistent quarantine, retries and explicit acknowledgement. Immutable
snapshot bytes are never mapped directly: MMKV may rewrite only the disposable
inspection copy or live files.

Verification: the required final Nix command ran once with `--max-workers=2`:
`:app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin`
— **2,961 JVM tests / 94 suites / zero failures, errors or skips**; regtest APK
assembly and instrumentation Kotlin compilation succeeded. The recurring
admission negative control failed its unreadable-envelope test when the old
validation was reinstated; both tests passed with the fix. Four quarantine
filesystem tests exercise sync ordering, retries, failed marker writes,
acknowledgement failure and malformed markers. Snapshot inspection tests prove
that recovery edits cannot alter the immutable pair and scratch is removed.

All **17 MMKVRecoveryTest JNI methods compile**, including the new actual-MMKV
suspension/retry, acknowledgement, marker failure, recurring envelope/key loss,
partial snapshot loss and 15 shared vectors. They were **not run on Android**:
this task prohibits touching any Android emulator or physical device. Equivalent
real-MMKV iOS probes and all shared vectors passed. No Android device, protected
simulator, Docker, client or server implementation was changed; funded/live
upgrade and real FCM/UnifiedPush delivery were not repeated.

## Historical MMKV migration safety, review round 3 (2026-10-20; base `32e1b6ef`)

Recovery is gated **before mapping** the live data/CRC pair, restored pair and
snapshot inspection copy. The checker reads the MMKV 2.4.3 format directly;
`../../fixtures/rn-mmkv/README.md` records the pinned source/AAR, exact 112-byte
metadata layout and real RN MMKVCore 2.4.0 fixture values. Version 3/4 uses the
metadata size rather than the stale legacy header. The last-confirmed pair is
never accepted as proof that the current pair is healthy. Missing data is a
never-created store and opens normally. Bad CRC/length, missing/short metadata,
or an unsupported version must write and fsync the external marker and its
parent directory before any MMKV mapping. Marker failure leaves both files
unchanged and blocks this launch.

MMKV's callbacks remain a secondary signal, including after cache eviction.
They return recover only after durable quarantine. MMKV 2.4.3 offers only
recover and discard; **discard also rewrites the files**. There is no callback
option that both refuses recovery and preserves bytes. A secondary callback
whose marker cannot be made durable therefore terminates the process before
returning to MMKV (Swift `fatalError`; Android process kill plus `Runtime.halt`).
The next launch reaches the ordinary pre-open gate. This deliberately favors
preserving settings over continuing a launch with unacknowledged recovery.

The foreground Continue action durably acknowledges first, then reapplies
recovered protection before publishing wallet data. Every headless path still
requires healthy storage and cannot acknowledge the marker. Retirement,
first-launch single restoration, admitted-key validation, recurring codecs and
the independent recurring policy ledger retain their existing behavior.

Android Continue reloads the cached profile, currency/unit, privacy, suspension,
locale and backup state, replaces the biometric lock policy and reruns launch.
Server state is already read dynamically from SettingsStorage. Locked wallet
content is excluded from composition until authentication.
`RecurringStorageDeviceTest` now injects malformed bytes using MMKV with an
explicit storage root; its validated writer correctly refuses such writes.

Negative control: removing pre-open checks, allowing callback return after
marker failure and omitting the Continue reload failed **all 3 device probes**.
The marker probe detected rewritten bytes; the other probes detected silent
suspension rollback and cached disabled biometrics/visible balance.
The corrected final real-JNI run passed **22 tests**: **20 MMKVRecoveryTest +
2 RecurringStorageDeviceTest**, zero failures or skips. It includes CRC and
length fallback, cache eviction, marker-write failure preserving both files,
and the actual Continue method enforcing lock + hiding before launch.

Device command (only the task-owned emulator, no Docker or physical devices):

```sh
ANDROID_SERIAL=emulator-5592 adb shell am instrument -w -r \
  -e class com.noahwallet.services.MMKVRecoveryTest,com.noahwallet.recurring.RecurringStorageDeviceTest \
  -e noahRecurringStorage disposable-emulator \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

Final required Nix command ran once after the last code change:
`:app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin`,
with `--max-workers=2`: **2,965 JVM tests / 95 suites / zero failures, errors or
skips**; APK assembly and instrumentation compilation succeeded. The focused
integrity/quarantine JVM run passed 6 tests. iOS's final suite passed 633 of
656 tests (23 opt-in skipped, zero failures).

Cleanup: emulator **emulator-5592** reported the exact owned AVD name
**NoahMMKVRound3_10769**. It was shut down through T3, verified absent from adb,
then the exact named AVD was listed, deleted with avdmanager and verified absent.
The iOS simulator, owned DerivedData, all result bundles, logs and temporary
probes were also removed. No other emulator, protected simulator, physical
device or Docker was used. No funded/live RN upgrade, real push delivery or
physical biometric prompt was repeated. The late-callback process-termination
fallback is source-verified; pre-open durability failures are fault-injected.

## Native settings replacement verification — 2026-10-20

Base `b6b3538d`; branch `t3/simplify-settings-migration`. This implements the
2026-10-20 policy above, replacing the prior recovery/Continue behavior.

- Android final required Nix command ran after the final code change with
  `--max-workers=2`: **2,978 JVM tests / 103 suites / 0 failures, errors or skips**;
  regtest APK assembly and Android instrumentation Kotlin compilation passed.
- Owned Android 15/API 35 arm64 AVD **NoahSettings_fdb0**, console port **5596**:
  **13 instrumentation tests discovered: 12 passed, 1 opt-in skipped**, zero
  failures. These are 10 `MMKVRecoveryTest` snapshot/native-file tests, two
  `RecurringStorageDeviceTest` tests, and the skipped prepared RN
  `UpgradeCompatibilityTest` (no funded RN sandbox/expectations supplied).
- iOS full `NoahTests` ran once after the final code change and XcodeGen:
  **652 tests / 130 suites / 630 passed / 22 opt-in skipped / 0 failures**;
  XCResult records 923 expanded parameterized passes. Arm64/active architecture
  only, signing enabled, two build jobs, no parallel test runners, owned
  DerivedData/result bundle under `/private/tmp`.
- Targeted iOS runs verified all 196 raw RN import cases, shared launch policy,
  empty recurring sync after admission (with retry), native damage/defaults,
  interruption before/after rename for absent and existing generations,
  concurrent import, existing immutable snapshot precedence, fresh install,
  deletion/re-onboarding, notices and 2,000 native writes with unchanged RN bytes.
  Android's matching JVM/JNI coverage passed. Native-created recurring tests
  remain in the full suites; production executor, ledger and authority unchanged.
- The first Android device run exposed fixture assumptions (the real binary
  has no recurring schedules) and a test-only directory creation race. Explicit
  generated recurring records and precreated test directories fixed these;
  the complete final device run passed. The first iOS atomic-order assertion
  compared URL directory hints; comparing paths fixed it, with real fsync order
  unchanged. A Swift import typo was caught during compilation and corrected.

Cleanup: confirmed exact simulator **NoahSettings-fdb0** /
`842FE262-F486-4394-924E-A2896937075B`, shut it down, verified Shutdown, deleted
it and verified absence. Confirmed `emulator-5596` reported **NoahSettings_fdb0**,
stopped it, verified absence from adb, listed and deleted that exact AVD and
verified absence. Removed owned DerivedData, every result bundle and temporary
scripts/logs; stopped the lingering failed targeted Xcode invocation. No owned
build process remains. Shared/protected simulators, other AVDs, physical devices,
Docker, `client/`, `server/`, AppLog and AppTelemetry were untouched.

Limits: no funded RN replacement, stopped/running RN install acceptance, live
server sync, push delivery or physical biometric prompt was repeated. Server
clearing semantics were source-verified (including its existing empty-set test);
native empty-payload/admission/retry behavior was executed in tests. The updated
prepared upgrade assertions compile but remain opt-in for that acceptance run.


## Funded acceptance rerun — 2026-10-20, base 0d1d724e

Branch `t3/rerun-native-upgrade-tests`, initially clean at **0d1d724e**. Only
upgrade tests and evidence change; client/server production code is unchanged.
The RN regtest release was rebuilt from this worktree with embedded JavaScript,
inside Nix, `CI_DEBUG_SIGNING=true`, `SENTRY_DISABLE_AUTO_UPLOAD=true`,
`CMAKE_BUILD_PARALLEL_LEVEL=2`, `assembleRegtestRelease`,
`-PreactNativeArchitectures=arm64-v8a --max-workers=2 --no-parallel`, Kotlin
compilation in-process and a 3 GiB Gradle heap. Build passed in **9m39s**.
Native regtest and instrumentation APK builds also passed, always with two
Gradle workers. RN/native certificate SHA-256 matched:
`fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`.

- RN APK SHA-256:
  `fda9e9ce82dfe891e225712c21f9618e4a217a37254bb1750c30a5b6281c9c44`.
- Native APK SHA-256:
  `ab76cb9827ef2e536ff5e0153342937bf9470164f21d2cca64243749345f7097`.

The only AVD used is the newly created **NoahUpgrade1020_ac03**, Google APIs
Android 15 / API 35 / ARM64, console **5620**, gRPC **8620**. Initial emulator **37.2.12** failed as detailed
below; final acceptance uses private emulator **36.6.11**.
Its name is verified through `adb -s emulator-5620 emu avd name` before actions.
No reserved emulator, physical Pixel 6, physical iPhone, or other agent's AVD is
used. The current host's emulator binary and system image are in the host SDK;
Nix supplies build/platform tools. Set
`EMULATOR_PROTO_DIR="$HOME/Library/Android/sdk/emulator/lib"` for the runner's
authenticated per-emulator clipboard helper, because Nix's SDK lacks those
emulator protocol files. No discovery token is printed.

### Host/setup failures before final acceptance

These attempts are not counted as native-upgrade passes. The first attempt
stopped before funding because the emulator protocol directory was missing.
A subsequent Maestro launch failed while RN remained alive; a concurrent
agent-device snapshot helper recorded a UIAutomation connection exception.
A launch-only reproduction later passed; the exact launch failure cause was
not established. No manual UI inspection runs concurrently with final Maestro
flows.

Four emulator exits were reproduced before native installation, including a
no-payment 15-iteration Home/Send/keypad stress flow. Host logs report QEMU main
and CPU loopers unresponsive for roughly **15,000 ms**, then ADB reports
`device 'emulator-5620' not found` / `device offline`. The macOS crash report for
PID **26197** identifies **SIGSEGV** in
`android::crashreport::CrashReporter::GenerateDumpAndDie`, called from
`HangDetector::LooperWatcher::process`; this is an emulator hang termination,
not a wallet exception. Default graphics and SwiftShader both reproduced it;
removing the iOS build load and explicitly disabling Vulkan did not prevent the
streamed stress failure. Interrupted RN wallet **d5d72ecc** was read back as
**one movement / 100,000 spendable sats**, proving its send was not submitted.

The final test-only send flow types Bark's address and verifies the recipient
field, avoiding shared-host pasteboard interference discovered in the iOS run.
The final native assertions compare an independent import of the actual RN bytes,
including biometric/Esplora settings; language is set to Swedish through RN.
Home's recurring notice must appear once across a process restart, native
schedules must be empty, the server must accept an empty sync and have zero
wake rows, and every live RN data/CRC file must remain byte-identical. Final
file capture force-stops native after foreground assertions to keep DB/WAL copies
consistent. No seed or token is exported; Expo values in evidence are hashes.

The identical no-payment stress flow passed **all 15 loops** with the T3 Device
panel stream closed. A subsequent full funded run nevertheless reproduced the
same QEMU hang before native installation, so the stream is **not established as
the sole cause**. The owned device was opened through T3 before diagnosis; final
attempts leave the stream closed and use device-scoped Maestro assertions/logs.
The 37.2.12 command was `emulator -avd NoahUpgrade1020_ac03 -port 5620
-no-window -no-snapshot -grpc 8620 -grpc-use-token -dns-server 8.8.8.8
-gpu swiftshader -feature -Vulkan -cores 2 -memory 2048`.

Another entry-point attempt found no Send tab immediately after cold launch;
a later filtered UI hierarchy showed the normal Home/Send tabs and no blocking
dialog. RN's Android startup loads icons/wallet state before navigation becomes
available. The harness now waits for navigation and positive available balance.
Standalone client post-onboarding flows get the same readiness wait in private
copies, leaving `client/` unchanged. Own locale/onchain/writer flows also wait for
navigation; the live writer deadline allows 120 seconds for cold startup. A
direct-entry send probe then passed its recipient/review/success checks.

To isolate emulator 37.2.12, downloaded Google's archived **36.6.11** Apple
Silicon binary into the private test directory, without replacing the shared
SDK. The [official emulator archive](https://developer.android.com/studio/emulator_archive)
provided `emulator-darwin_aarch64-15507667.zip`; SHA-256 was checked against
`aebcd4dde29a4921d47e5e79b8c1ffece69a70f5b280d8dc7033ebaffa737072`
before extraction. It uses the same owned AVD and API 35 image, with no container
or production-source change.

The first 36.6.11 entry-point attempt reached confirmed funding without an
emulator exit but failed RN's `125,000.*` assertion before native installation.
A filtered UI dump showed balance still hidden after the completed Show tap;
read-only fingerprint **71b25b69** had exactly receive 100,000 / send 5,000 and
95,000 spendable, and UI activity showed +30,000 onchain confirmed at block 667.
A separate probe successfully showed **125,000 total / 30,000 confirmed**, then
restored hiding. The exact missed-tap cause was not established. The final harness waits for
animation after boarding-prompt dismissal, retries the Show tap with a required
Hide-label assertion, and requires the final hidden state. No wallet code changed.
The failed preparation is not a native acceptance pass; the full script was
restarted from its entry point with a new RN wallet.

The next fresh 36.6.11 attempt timed out waiting 60 seconds for RN's boarding
confirmation. After failure, a guarded hierarchy showed the real Board-to-Ark
dialog and both visible action buttons; activity already showed confirmed
30,000 onchain at block 672. Read-only fingerprint **21c7eb79** again retained
exactly two Ark movements / 95,000 spendable. This was delayed RN preparation,
not native upgrade behavior. The final bounded boarding wait is 180 seconds to
cover wallet sync plus fee-plan loading on the shared host. No native APK had
been installed in this failed attempt; the full entry point was restarted.

The next uninterrupted stopped entry-point invocation completed every RN/native
wallet, settings, notice, launcher and Expo assertion. Fingerprint **1697ba78**,
movements **1,2**, **95,000 Ark + 30,000 confirmed onchain**, settings
**sats/EUR/sv/hidden=true/biometrics=false/auto-boarding=false**, native recurring
count **0**. Instrumentation: **OK (1 test), 10.216 seconds**. The live MMKV
manifest remained `402e80c045e161c820f8f16ab6d3e865cc387f17b3d5fa025478fd22ecbe09a3`,
and the immutable snapshot pair matched. Expo ID/token SHA-256 remained
`7f8bc1235f58e1d6277f33bd6a75c6819365dae1cbc3ec24a085b841c03f63a3` /
`9597a29b4e2e52ef5e342d395dc424fe745578b93fe220702e1aa1c11372073c`.

That invocation still exited nonzero in the final test-only server helper:
Python 3.9 lacks `tomllib`, and native Ktor sends `User-Agent: ktor-client`
rather than OkHttp's default. Removed that unnecessary TOML parsing and require
Ktor's observed identity, distinct from RN's `okhttp/4.12.0`. Read-only verification
then accepted request **6234a043-10d8-4ed0-844d-81fddb798802**, HTTP 200,
empty schedules, public-key prefix **0375b01ba38b**, and found **0** wake rows.
RN's earlier active-rule requests returned 400; its later paused empty sync is
explicitly excluded by the native-identity check. This diagnostic is not counted
as a passing final script. Both modes are rerun from the entry point below.

### Stopped replacement — PASS, uninterrupted final entry-point run

`FUNDED=1 INSTALL_WHILE_RUNNING=0`, **504 seconds**, exit **0**. Fresh RN
wallet, with no resume or manual UI repair.
Native instrumentation: **OK (1 test), 9.549 seconds**. Native build **100**
replaced rebuilt RN **38**, same `com.noahwallet.regtest` and certificate.
Both use bark-bolt/Ark through their respective existing wrappers; native FFI
is **0.5.0**, RN Nitro Ark **0.0.144**.

- Wallet fingerprint **f687c2c3**, movements **1,2**: receive +100,000 and send
  −5,000, successful, identical IDs/status/subsystem/kind/intended/effective
  amounts and fees. Same DB inode. Native SDK fingerprint validation and
  foreign/invalid-seed rejection before writes pass; legacy secret files remain
  untouched. No upgrade-created movement or payment.
- **95,000 spendable/offchain + 30,000 confirmed/total onchain = 125,000 total
  sats**, pending **0**, identical before/after native launch.
- Settings **sats / EUR / Swedish (`sv`) / hidden balance true / biometrics
  false / auto-boarding false**, display name **Android upgrade**. Exact profile,
  privacy, transaction and Esplora records match an independent private RN import;
  mailbox/email-prompt flags and native import marker pass. Esplora is unset.
- Direct Home admission. The RN recurring rule is absent: **0 native schedules**.
  The recurring reset notice appears once, disappears after a process restart,
  and its native pending flag is consumed. No settings-loss notice appears.
- Every live RN MMKV file, including CRC, remains byte-identical across stopped
  installation and after all native assertions. Manifest SHA-256:
  `53a25fb4e2b1799548fb5272498ae1fecf4aa2189d7e63bc444fc133828f773e`.
  The immutable native snapshot's data/CRC pair matches the same baseline.
- Native `ktor-client` empty sync accepted, HTTP **200**, request
  **d00a7250-c459-4a02-8500-245012890f08**, login-key prefix **0237f7044ccb**.
  Read-only database query: **0** recurring wake rows. RN's HTTP identity is
  excluded, so its earlier empty paused-rule sync cannot satisfy this assertion.
- Real RN-runtime Expo installation ID/token preserved. SHA-256:
  `6539bc5d8b57df49c854effb2603814a60ffeecf9a48891d0519a00344d8de97` /
  `91999acc715d7c2379aba87716057ce55a0be3fc3c05a82abd3697d1081de88e`.
  Native FCM/Expo registration succeeds with the exact RN token. Notification
  permission, placed widget IDs/provider/host/metadata (**1 widget**) and pinned
  MainActivity shortcut survive `adb install -r`.

Final commands use the prebuilt RN APK and the private emulator's protocol files:

```sh
FUNDED=1 INSTALL_WHILE_RUNNING=0 \
  EMULATOR_PROTO_DIR=<private-tmp>/emulator36-sdk/emulator/lib \
  UPGRADE_EVIDENCE=<private-tmp>/android-stopped.json \
  apps/android/UpgradeTests/run.sh emulator-5620 <private-tmp>/rn-regtest.apk
```

The separate running/writing invocation uses `INSTALL_WHILE_RUNNING=1` and a
fresh RN installation on the same owned AVD. No uninstall occurs between RN and
native within either scenario.

### Running/writing replacement — PASS, uninterrupted final entry-point run

`FUNDED=1 INSTALL_WHILE_RUNNING=1`, **507 seconds**, exit **0**, fresh RN wallet
and the committed final runner from its entry point. The runner observed a real
RN data-file hash change, a completed EUR selection and a still-live Maestro
writer, then called `adb install -r` without first stopping RN. It stopped the
writer only after replacement and captured the final RN generation before the
first native launch. Instrumentation: **OK (1 test), 8.081 seconds**.

Fingerprint **f0bd46a5**, movements **1,2**, **95,000 spendable/offchain + 30,000
confirmed/total onchain = 125,000 total sats**, pending **0**. Every wallet,
DB-inode/full-movement/no-payment, settings, once-only notice, native-empty-rule,
real Expo registration and launcher assertion above passes in this mode too.
Native Home opens directly; the settings-loss notice is absent.

The live RN two-file manifest changed during RN writing from
`ec95deee965a83012149fb9420191943c89e5b8ebd26a2e5164548e55f0d9830`
to `96bb60144ca302c732f1a2526d332445a41c6b92bdc7f7e0948f9600eaf2cb63`.
The latter remained byte-identical after all native checks. The immutable
snapshot's data/CRC pair matches. Native empty sync HTTP **200**, request
**9527fb87-8300-49c7-80b1-8b84f4cc83f8**, login-key prefix **030e76563f75**;
read-only wake-row count **0**. Expo ID/token SHA-256 preserved:
`573c4a8a866e76bda0aeef1ab1fafb8cb75d4aa4378bf4e4b9b577668a6989b3` /
`000934024a4b146580d9ba21db360fe0183d7f9378ac476cd5e1f66ab92a6370`.
One placed widget and the pinned MainActivity shortcut survive.

### Verification, limits and cleanup

Both Android extended funded scripts passed uninterrupted from the entry point;
the earlier resumed-procedure limitation in TODO 0.5 is resolved. Both iOS modes
also passed freshly; see [the iOS report](../../ios/UpgradeTests/README.md#funded-acceptance-rerun--2026-10-20-base-0d1d724e).
No production upgrade defect was observed. The failures above are retained as
harness/environment evidence rather than counted as passes.

Repository hooks run normally: client lint, React Compiler lint, **148 unit tests
/ 0 failures**, TypeScript, and `cargo fmt --check` pass. Both runner shells pass
`bash -n`; MMKV verifier self-checks accept identical files and reject a changed
CRC or mismatched snapshot. A synthetic server-log check excludes RN/foreign-wallet
requests and accepts only the matching native request. No hook is bypassed.

Android's real RN-runtime FCM/Expo registration is exercised even though RN's JS
emulator guard skips automatic registration. iOS simulator APNs/real Expo
registration remains an explicit skip. Physical biometric authentication is not
exercised; the persisted false flag is checked on both platforms. A non-null
Esplora endpoint is not configured in this Core-backed regtest fixture; exact
unset-state preservation is checked. Push delivery/background callbacks and the
settings-corruption matrix are outside this funded rerun, covered separately by
the historical device/storage reports.

Cleanup: checked exact AVD **NoahUpgrade1020_ac03**, console **5620**, and owned
QEMU PID **40750** before `emu kill`. Verified the serial disappeared and the
PID exited before deleting the AVD; verified both its `.ini` and `.avd` directory
are absent. The owned iOS simulator was separately checked, shut down, deleted
and verified absent. Removed owned RN/native build directories, Pods, private
emulator/SDK/APKs, all iOS DerivedData and XCResult bundles. Temporary dependencies
and remaining private screenshots/logs/public evidence are removed after the
final documentation commit hooks. No owned build/test/writer process remains.
Shared devices/SDKs and service containers are unchanged; the existing server,
Postgres and Bitcoin Core retain start times preceding this task. Mining and
funding used the allowed wrappers; no stack lifecycle recipe ran. `client/`,
`server/`, `.env` files and production native code have no committed changes.
