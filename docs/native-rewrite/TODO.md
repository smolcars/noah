# Native rewrite — to-do

Check items off in the commit that completes them. Every screen item means: iOS (SwiftUI) **and** Android (Compose), matching the RN screen's behaviour listed in [research/inventory.md](research/inventory.md), with the keyboard/glass rules from [PLAN.md](PLAN.md). Pure logic gets a shared JSON fixture in `apps/fixtures/` run by XCTest and JUnit.

## Phase 0 — Foundations

### 0.1 Regtest stack
- [x] Bitcoin Core 31+ (digest-pinned) in `scripts/docker-compose.yml`
- [x] captaind + bark CLI `nightly-2026-10-19`, digest-pinned
- [x] `scripts/captaind.toml`: remove `watchman_min_balance`, `[watchman]`, `vtxopool.issue_interval`
- [x] Other image refs in `justfile` / `scripts/` updated
- [x] `just setup-everything` works (run on existing volumes; recreates the bark CLI wallet)
- [x] barkd 0.7.1 forwarding against captaind 2026-10-19: invoice-for-address → LND pays → VTXO delivered (no bump needed)
- [ ] barkd restart while a forward is pending (not yet tested)

### 0.2 Project scaffolding
- [x] `apps/ios`: XcodeGen spec, SwiftPM deps, schemes `Noah` / `Noah-Signet` / `Noah-Regtest`, xcconfigs, bundle IDs `com.noahwallet.{mainnet,signet,regtest}`, entitlements (aps, app groups), URL schemes, Info.plist background modes
- [x] iOS widget extension reused from `client/ios/SharedWidget` (one target, per-variant bundle ID + kind from xcconfig)
- [x] iOS App Intents ported from `client/ios/SharedAppIntents` onto the shared `WalletSession`: all nine identifiers, parameters and Siri phrases preserved; receive/balance only, read-only seed admission
- [x] iOS `processing`/`fetch` background modes + per-bundle `BGTaskSchedulerPermittedIdentifiers` in XcodeGen (0.6 background tasks)
- [x] `apps/android`: Gradle KTS, version catalog, flavors `mainnet`/`signet`/`regtest`, same applicationIds, signing config, `google-services.json` per flavor, minSdk 24
- [x] Android component names unchanged: launcher `com.noahwallet.MainActivity`, widget receivers `com.noahwallet.widgets.NoahWidget{Mainnet,Signet,Regtest}Provider` (+ same `@xml/noah_widget_*_info`)
- [x] Android widgets reused from `client/android/app/src/main/java/com/noahwallet/widgets`
- [x] `apps/fixtures/` shared JSON fixtures + XCTest/JUnit loaders
- [x] `justfile` recipes: `ios-native-*`
- [x] `justfile` recipes: `android-native-*`
- [x] CI: build + unit tests for both apps (all three variants compile)

### 0.3 Wallet SDK integration
- [x] FFI gaps P0+P1 shipped in bark-bolt-ffi v0.2.0
- [x] iOS pins bark-bolt-ffi 0.5.0 at `68b88c73ccaaa8e5fe607fbcc1e7d783a9ef4609`; SPM manifest and resolved XCFramework SHA-256 verified against release SHA256SUMS. Prepared boarding (18), read-only transaction status (15), persisted all-exit listings (20) adopted; prepared offboards and durable operation keys (19) remain open
- [x] FFI items 11 + 12 in v0.3.0: `OnchainWallet.openExisting` (read-only identity check before writes) and all nine balance buckets + Bark's pending/total — adopted on iOS (now pinned to 0.5.0; Noah's own preflight kept)
- [x] Android on bark-bolt-ffi 0.5.0 (tag `68b88c73`): verified AAR/POM SHA-256, `openExisting`, full balance, prepared sends/boarding, read-only chain status and persisted live/terminal exits; wrappers and preparations drained on teardown
- [x] Android session lifecycle parity with iOS (reservations, calls shielded from cancellation, observers), with the Android restore port — `WalletSession.kt:141,215,349,411,465`, `WalletRestorer.kt:42`; `WalletSessionTest` cancellation/reservation/observer regressions + `WalletRestorerTest` / `RestoreTransactionTest` (restore reservations `e5f08b66`, observer fixes `b02ba8fc`, admission follow-up `6667d748`)
- [x] Funded RN→native upgrade re-run on 0.3.0 — iOS (fingerprint, 2 movements, 95,000 spendable; opened via `openExisting`)
- [x] Funded RN→native upgrade on 0.3.0 — Android, real Pixel 6 (Android 17): fingerprint, 2 movements, 95,000 spendable; Expo UUID and token unchanged (see `apps/android/UpgradeTests/DEVICE.md`)
- [x] Pixel 6: background-job callbacks, killed-process delivery and widget refresh — real Expo heartbeat `responded` / maintenance `Success` in both states; placed widget refreshed 95,000 → 96,234 sats after a push (2026-10-19 follow-up in `apps/android/UpgradeTests/DEVICE.md`)
- [x] bark-bolt-ffi Android libraries 16 KB-aligned (FFI handoff item 13) — fixed in 0.3.1; both platforms now on 0.5.0 and Android reverified there; arm64-v8a, armeabi-v7a and x86_64 `libbark_bolt_ffi.so` LOAD segments all `Align 0x4000` (checked with llvm-readelf on the published AAR)
- [x] `WalletService` protocol/interface with a fake for UI work — superseded by `WalletSession` plus feature-specific dependency seams: iOS `ReceiveModel.swift:7` / `HistoryModel.swift:7`; Android `WalletSession.Handles` (`WalletSession.kt:46`) / `SendWallet` (`SendController.kt:243`); injected fakes in `ReceiveModelTests` / `SendSafetyTest` (`cfe15cf1`, `91095f90`)
- [x] Config mapping per variant with **fee units: sat/kvB → sat/kwu** — iOS
- [x] Config mapping — Android (sat/kwu; regtest via `10.0.2.2`)
- [ ] Activate the decoded `esplora-storage` override on both platforms after endpoint/network validation and coordinated reload; both SDK openers still use `WalletConfiguration.base`. Codecs exist (`RNStoreCodec.swift:28,83`, `RNStoreCodec.kt:16`); consumer/editor remains M5
- [x] `WalletCoordinator` — superseded by process-wide `WalletSession` + `WalletLifecycle` mutation gates: iOS actor/shared owner (`WalletSession.swift:14,71`), Android owner/reservations/drained close (`WalletSession.kt:22,215,465`); UI/push/backup/restore and iOS App Intents share it; `WalletSessionTests`, `WalletSessionTest`, `WalletLifecycleTests` / `WalletLifecycleTest` (`3d530294`, `e5f08b66`, `5e000c52`)
  - [x] iOS: `WalletSession` holds headless admission across push/task/intent awaits and backup/native settlement; foreground entry waits; restore/deletion reservations exclude jobs
- [x] Open existing wallet: preflight `db.sqlite`, `createIfNotExists=false`, original network — iOS (`WalletSession`)
- [x] One `WalletSession.shared` per process (bark locks the datadir); `ensureOpen` joins an open in progress — iOS
- [x] Shared session, preflight open, safe create-or-resume, bark balance accounting — Android (overlapping closes rejected, native handles disposed explicitly)
- [x] `runDaemon` policy + coordination rules for push/background/backups on the shared session — iOS headless opens disable the daemon and settle/close; Android retains the daemon-on shared session (`AppServices.kt:170,176`), with lifecycle admission, counted shielded calls and backup settlement (`WalletSession.kt:349,465`, `AppServices.kt:140`); `HeadlessWalletTests`, `StoredWalletOpenerTest`, `WalletSessionTest`, `BackupCoordinatorTest` (`d40f6368`, `ec9f1876`, `2416b2ef`)
  - [x] iOS: headless opens are existing-only, daemon off, closed after settlement; an already-open foreground session is retained. No headless restore/deletion recovery or seed writes; suspended/incompatible/locked/missing/conflicting state refuses admission
- [x] Notification loop (movement events) + receive subscriptions with cancellation — `WalletSession.swift:499,537`, `WalletSession.kt:89,411`; `WalletSessionTests.repeatedPendingObserversStopOnCloseAndConsumerCancellation`, `WalletObserverStopTests.stopRetriesRegistrationGapWithoutCancellingReader`, `WalletSessionTest.cancelReceiveObserverReleasesHolderButLeavesWalletUsable` (`cfe15cf1`, `91095f90`, `b02ba8fc`, `4d9f1ca5`)

### 0.4 Server API contract
- [x] Utoipa `ToSchema` on `server/src/types.rs` exports + path annotations on all handlers (bearer + registered-user security)
- [x] Deterministic `openapi.json` export + normalization step (tagged `NotificationData`, nullable refs)
- [x] swift-openapi-generator 1.14.2 → `apps/ios/Noah/API/Generated` (committed, `just generate-api-ios`)
- [x] Fabrikt 28.1.1 → `apps/android` generated Kotlinx models + Ktor/OkHttp client — Android
- [x] All notification variants decode + round-trip from server-generated fixtures — iOS
- [x] All six notification variants decode + round-trip from server fixtures — Android
- [x] `just generate-api`; server tests fail on a stale `api/openapi.json` or `apps/fixtures/server-notification-data.json`
- [x] CI drift gate for generated native code

#### Native CI

The native workflows run on `pull_request` with path filters and `workflow_dispatch` only; none run on branch pushes or require secrets. `apps/**` includes the shared `apps/fixtures/**` inputs.

- [native-android.yml](../../.github/workflows/native-android.yml): `build-and-test` on `blacksmith-8vcpu-ubuntu-2404`, using the existing `android-ci` Nix shell, Nix/Gradle/SDK caches, all three debug assemblies and `:app:testRegtestDebugUnitTest`. Gradle uses `--max-workers=2 --dependency-verification=strict`; JUnit XML is uploaded even on failure.
- [native-ios.yml](../../.github/workflows/native-ios.yml): `build-and-test` on `blacksmith-6vcpu-macos-latest`, XcodeGen, cached Swift packages, unsigned simulator builds of `Noah`, `Noah-Signet`, and `Noah-Regtest`, then `NoahTests` on a dedicated iPhone 17 simulator. Tests use ad hoc signing for simulator keychain entitlements, without signing credentials. Live tests opt in through environment variables. The `.xcresult` bundle and JSON summary are uploaded even on failure; the owned simulator and temporary DerivedData are deleted afterwards.
- [native-generated.yml](../../.github/workflows/native-generated.yml): `server-contract` on `blacksmith-4vcpu-ubuntu-2404` mirrors Server CI's Rust/dependency setup and runs the offline `UPDATE_OPENAPI=1 cargo test -p server --bin server openapi::export`. It checks `api/openapi.json` and notification fixtures, without database/cache services. After that passes, `native-clients` on `blacksmith-6vcpu-macos-latest` runs `nix develop .#android-ci --command just generate-api-ios generate-api-android` and rejects tracked diffs and untracked generated files. Filters also cover `api/**`, `server/**`, Cargo inputs, generator recipes and Nix inputs.

Native clients are generated from Rust/Utoipa's OpenAPI document, not the RN TypeScript file. The existing recipes pin Apple's Swift OpenAPI generator to 1.14.2 and Fabrikt to 28.1.1 with a JAR checksum. RN's `client/src/types/serverTypes.ts` remains a separate ts-rs export from `server/src/types.rs`.

Local verification (2026-10-20): actionlint 1.7.7 with ShellCheck passed for all three workflows; the exact Gradle command assembled all three flavors and passed 2,842 tests. Xcode 27.1 built all three schemes unsigned, then passed 481 tests with 19 opt-in skips using ad hoc simulator signing. Both offline server exports passed and both native clients regenerated without drift. The dedicated simulator was checked, shut down and deleted, and `/private/tmp` DerivedData was removed. Hosted runner setup, caches and artifact uploads still need their first CI run; no branch was pushed or workflow dispatched.

### 0.5 Upgrade compatibility (gate before any funded-wallet milestone)
- [x] Both recurring round-3 correctness: all 20 RN factory fields mandatory (including nullable keys), shared missing-field classification/lossless-row regressions; settlement retains underlying policy across temporary suspension; clock/execution admission and protected binding reads avoid pre-onboarding authority failure on both platforms. Existing rollback, consumption, generation and storage-health gates retained; Android final 3,001 tests / 104 suites + APK; iOS full once 665 total / 642 passed / 23 opt-in skipped / 0 failed, 132 suites (959 expanded passes); owned simulator/DerivedData/six result bundles removed; both RecurringTests notes
- [x] Both recurring round-2 upgrade safety: wallet-local durable lifecycle stop/suspension authority survives acknowledged MMKV rollback; per-occurrence settlement application claims and pre-marker consumed-receipt migration prevent duplicate history/notifications; proven consumed receipts release Send despite policy rollback/write latches; explicit edit/resume reapproval retains journal consumption. Android reminder protection, stage cancellation/budget checks, and injectable ledger sync on both platforms; final evidence in RecurringTests notes
- [x] Android standalone restore-preparation stop in `AppServices.stopRecurringAutomation` — `f4ba6620` writes the durable automation authority stop before the settings generation write (`AppServices.kt:107`); lifecycle suspension/deletion paths remain covered.
- [x] iOS seed reader: keychain service `com.noah.mnemonic.<variant>`, account `noah` (read + in-place save, RN attributes)
- [x] Android seed reader: `RN_KEYCHAIN` DataStore (+ legacy SharedPreferences), cipher from `:c` (AES-GCM / AES-CBC); fallback `noah_native_secrets`; new Keystore-backed store; legacy kept
- [x] Android RN→native upgrade test run on an emulator
- [x] Launch decision (`apps/fixtures/launch-state.json`): never auto-delete seed; seed-without-DB and DB-without-seed states — iOS
- [x] Launch decision — Android
- [x] BIP39 validation of the stored seed; conflict handling
  - [x] iOS: validate 12/24-word BIP39 before create/open/restore; block corrupt/ambiguous seeds and fingerprint conflicts without overwriting data; fixed support guidance, shared safety fixture and real SDK no-write tests
  - [x] Review round 1: one normalization path for wallet/auth SDK input, original RN backup passwords retained; explicit read-only Android background opens and foreground-only idempotent migration; iOS transient Keychain retry; shared raw-source launch diagnostics (including empty seeds) and Unicode vectors — `apps/fixtures/README.md`
  - [x] Android: 12/24-word/checksum validation, all-source agreement, read-only seed reads, SDK fingerprint rejection before migration, blocking support state; negative device checks keep DB/source bytes unchanged — `apps/android/UpgradeTests/DEVICE.md`.
- [x] Server auth token reader (`com.noah.auth-token.<variant>`) — iOS
- [x] Server auth token reader/writer (RN_KEYCHAIN, existing GCM/CBC alias; excluded from backup) — Android
- [x] Settings product policy (2026-10-20): native durable JSON files replace MMKV on both platforms. One-time best-effort import from an immutable snapshot, integrity checked before scratch-only mapping; strict invalid-key drops; original valid RN strings retained. No live MMKV opens or native writes; fresh installs create no MMKV.
- [x] Settings I/O failure fallback (both platforms): reads/foreground launch and Home notice consumption succeed from process-wide defaults when the native file is unreadable and its directory unwritable; next write/open retries persistence. Shared per-file fallback prevents stale settings objects overwriting recovered state. Durable writes/health checks still fail closed for recurring execution and automation authority.
- [x] Native onboarding ordering (both platforms): fsynced `isInitialized=false` precedes seed/database creation inside the existing gate/reservation and precedes backup/phrase restore installation. Marker failure creates no wallet; public-key lookup follows creation. Interruption checkpoints and restore retry/order regressions cover the missing-flag window.
- [x] Settings loss notice names biometric lock off, balances visible, and currency/display defaults, with no Esplora reference. Consumption is nonblocking and once per process if writes fail; a later successful write makes it durable across launches.
- [x] Settings review final verification (`10c0b996` base): Android 3,011 JVM / 106 suites + APK and instrumentation compilation; final owned-emulator regressions 12/12 passed. Broader device run 16/17 passed (unchanged invalid-mnemonic fixture). iOS full once: 667 total / 645 passed / 22 opt-in skips / zero failures, 135 suites (963 expanded passes). Owned devices, DerivedData and six result bundles removed; evidence and interruption-test limits in `apps/android/UpgradeTests/STORES.md`.
- [x] Settings never decide wallet existence: seed + DB means Home; only readable explicit `isInitialized=false` resumes onboarding. Missing/cleared/damaged RN settings and damaged native files use defaults. Seed-safety rules unchanged; background Home admission is read-only for seeds and respects suspension/restore/deletion.
- [x] RN recurring schedules never import. Native records the one-time Home notice and empty pending sync; existing recurring server path sends `schedules:[]`, verified to clear server wake entries. Native policy ledger, automation authority, executor and journal unchanged.
- [x] One-time nonblocking Home notices persist in native settings for lost settings and RN recurring schedules. Removed quarantine, Continue/model rebuilding, unreadable-settings blocked/support states, restore-once/retirement, MMKV handlers and lazy reload guards. Wallet deletion removes RN files/snapshot while retaining the native import marker through re-onboarding.
- [x] Shared launch/import fixtures and native tests: eight MMKV admission cases, 196 raw RN import cases, interrupted writes before/after rename, background/concurrent import, damaged native defaults, one-time notices, native deletion/re-onboarding and 2,000 native writes with byte-identical live RN files. Final Android: 2,978 JVM / 103 suites, APK + instrumentation compilation; owned-emulator JNI: 12 passed / 1 prepared-upgrade skip. Final iOS: 652 total / 630 passed / 22 opt-in skips / 0 failures. Owned devices, DerivedData and results removed; details and acceptance limits in UpgradeTests docs.
- [x] Upgrade runners cover install while RN is running/writing (`INSTALL_WHILE_RUNNING=1`, default); stopped baseline mode retained (`=0`). Run both for acceptance; live results are recorded in each platform's upgrade docs.
- [x] Remaining RN store shapes — all eight envelopes have codecs, unknown-field retention and transaction-v0 migration; both `RNStoreTests` / `RNStoreMigrationTest.unifiedRNFixturesValidateMigrateAndPreserveOriginalBytes` consume 196 RN-generated cases (`RNStoreCodec.swift:14`, `RNStoreCodec.kt:10`; `f29a23a4`, `b84ce5dd`, `17e7b76f`). Esplora activation remains separately tracked in 0.3/M5
  - [x] Review round 1: exact raw version tokens; recoverable profile fallback without stored-value repair; one deterministic RN Zustand generator/196-case raw fixture consumed by both native suites — `apps/fixtures/README.md`
  - [x] iOS: inventory all 8 stores; validate consumed envelopes/versions, preserve unknown JSON tokens, apply transaction v0 migration; RN-generated fixtures; Esplora decoder remains inactive until its native screen lands — `apps/ios/UpgradeTests/README.md`
  - [x] Android: all 8 RN envelopes inventoried/validated; unknown fields retained on writes, transaction v0 migration matched; Esplora has a decoder but no native consumer — `apps/android/UpgradeTests/STORES.md`.
- [x] Verify against a real RN-written MMKV file (in the upgrade test) — iOS
- [x] GT locale reader (`generaltranslation.locale`) — iOS
- [x] GT locale reader (`gt_store`) — Android
- [x] Expo installation UUID reuse — iOS (keychain `EXDeviceInstallationUUIDKey`)
- [x] Expo installation UUID reuse — Android (`noBackupFilesDir/expo_notifications_installation_uuid.txt`)
- [x] UnifiedPush connector storage + instance names preserved — Android (3.1.2 SQLite + legacy prefs fixture on device)
- [x] Upgrade test iOS (`apps/ios/UpgradeTests/run.sh`): RN regtest creates wallet via Maestro → native installed over it → seed, DB, MMKV readable, launch = existingWallet, RN DB opens with new bark (same file, not recreated)
- [x] Funded upgrade test iOS (`FUNDED=1`): RN receives 100k + sends 5k → native sees same fingerprint, 2 movements, 95,000 spendable
- [ ] Repeat the final Android extended funded upgrade script uninterrupted from its entry point; onchain funds, recurring rule, settings and real Expo-token assertions already exist and passed in resumed procedures (`UpgradeCompatibilityTest.kt:40`, `apps/android/UpgradeTests/DEVICE.md:363`). iOS extended upgrade and physical Expo continuity passed (`UpgradeCompatibilityTests.swift:52,83,111,132`; `5aa40aa6`, `da24df4d`); retain the evidence below
  - [x] iOS: confirmed 10k onchain + 95k Ark, identical movements/fingerprint/recurring policy, native recurring UI with zero payments, non-default settings and locale — `apps/ios/UpgradeTests/README.md`
  - [x] iOS: Expo token continuity verified on a physical iPhone 17 (iOS 27.2): native re-registered RN's exact token after an in-place install; installation ID reused (`apps/ios/UpgradeTests/README.md`)
  - [x] Both: RN Settings → Delete Wallet unlinks MMKV while open, so a wallet re-created in that RN session never persists stores. Reproduced on a real iPhone with the UI driver (`apps/ios/DeviceDriver`). RN fix merged: smolcars/noah#371 (keep the folder, `clearAll()`). iPhone, 2026-10-20: old RN → empty store → native resumes onboarding; fixed RN → stores persist, RN relaunch and native both land on Home
  - [x] Both: product decision (2026-10-20): readable stores with wallet-storage already missing on first native open resume foreground onboarding using the existing seed+DB; unreadable storage and later loss of admitted keys block. Recover strategy, immutable pre-open backup, durable restoration retirement and headless initialization gates are covered in both native suites
  - [x] Both: MMKV review round 1 (2026-10-20) — automatic RN-backup restore retired after the first open, every Android headless open requires `isInitialized`, present-but-undecodable values block, migration backups excluded from system backup and deleted with the wallet
  - [x] Both: MMKV rollback (CRC recovery to an older confirmed state) can resurrect a paused/cancelled recurring schedule — fixed with wallet-scoped, fsynced write-ahead policy ledgers, revision/status/digest checks on every recurring read, durable bootstrap-once markers and cancellation tombstones; permanent unpaid pause/cancel/edit probes fail before / pass after; Android 2,927 tests + APK, iOS 617 total / 594 passed / 23 opt-in skipped / 0 failed; `apps/android/RecurringTests/README.md`, `apps/ios/RecurringTests/README.md`
  - [x] Android: device assertions passed with 95k Ark + 30k confirmed onchain, identical paused RN rule/settings, real RN-runtime Expo UUID/token, and first native foreground launch without payment. Script/flows were corrected during a resumed live procedure; a fresh uninterrupted final-script run remains to be repeated.
- [x] Upgrade test Android (emulator), funded: same fingerprint, 2 movements, 95,000 spendable (Expo UUID/token continuity verified in the extended run above)
- [x] Upgrade test also checks launcher shortcut + placed widget survive (Android) and widget kind/bundle ID (iOS)
  - [x] iOS: all three widget identities/app groups match RN; built regtest extension checked; two actual RN-placed SpringBoard widget instance IDs survive native installation
  - [x] Android device pass: placed widget ID 2/provider/host/metadata and controlled pinned MainActivity shortcut survived `install -r` — `apps/android/UpgradeTests/DEVICE.md`.

### 0.6 Platform services
- [x] Redacting logger (file-backed, rotating, exportable) — iOS (`AppLog`, linear token scans; bounded raw-record admission and background redaction before all sinks/overflow markers; prefixed/escaped JSON credentials, redaction-only Unicode mnemonic skeleton, URL userinfo and address privacy; 147 shared vectors; nonblocking lifecycle drains with bounded UIKit background execution)
- [x] Redacting logger — Android (linear token scans; bounded async raw-record queue, background redaction before Logcat/files, ordered overflow and awaitable flush; same decoded JSON/Unicode/address contract; 147 shared vectors)
- [x] Route bark-bolt-ffi `BarkLogger` callbacks into the app log — iOS (ordered bounded async admission, no disk waits in Rust callbacks, RN Debug verbosity)
- [ ] Route bark-bolt-ffi `BarkLogger` callbacks into the app log — Android
- [x] Sentry (cocoa), disabled in debug + regtest like RN — iOS (production DSN; fixed event/context/tag allowlist, PII off, no attachments, redacted messages and fail-closed key/value guard; archive dSYMs require CI credentials)
- [ ] Sentry (android), disabled in debug + regtest like RN
- [x] HTTP clients (URLSession / OkHttp) incl. upload/download — iOS `ServerClient.swift:19`, `BackupService.swift:106`, `BoundedDownload.swift:16`; Android `ServerClient.kt:48`, `BackupService.kt:33`, `BoundedDownload.kt:14`; `ServerAuthTests` / `ServerAuthTest`, `BoundedDownloadTests`, `BackupArchiveTest.boundedDownloadChecksActualBytesWithoutTrustingHeaders`, both live backup integration suites (`5ef912c5`, `ec9f1876`, `60ebc1b7`)
- [x] Server auth: `/v0/getk1` → sign (offline key `m/350/0`) → `/v0/auth/login` → JWT; refresh near expiry; retry once — iOS (live-tested on regtest)
- [x] Server auth — Android (offline index-0 signing, shared login, RN token storage, retry once; live-tested on regtest)
- [x] Server registration + last-login + device info (shared token provider/client) — iOS
- [x] App-version check — iOS (existing server client, process-cached fail-open Home warning; M1)
- [x] Server registration + last-login + device info, app-version check, email and Lightning identity services — Android
- [x] Push iOS: permission, APNs token → Expo token (RN installation ID, provisioning aps-environment), `register_push_token`, silent-push handler with 25 s budget, jobs for maintenance / invoice / claim / backup / heartbeat with job-status reports, widget refresh; expiry checks between stages, at wallet-open admission and immediately before native opening; bounded completion log drain
- [x] iOS SDK observer teardown: stop native notification/state-change waits and await uncancelled readers, retrying the wait-registration gap; repeated close/consumer-cancellation regressions and [upstream draft](upstream/bark-bolt-ffi-notification-teardown.md), including Android polling audit — `apps/ios/PlatformTests/README.md`
- [x] Push iOS: register the push token after server registration once onboarding is done (RN `usePushNotifications` gating)
- [ ] Verify physical iOS `recurring_payment_due` background delivery; M7 handling is implemented (`PushJobs.swift:82`, `RecurringLive.swift:31`; `RecurringLiveTests` / `RecurringBackgroundRegressionTests`, `23e08ffd`). Simulator/direct-handler evidence does not prove remote background delivery
- [x] FirebaseMessagingService + Expo token, onboarding/permission/server gates, expedited push work, maintenance/invoice/claim + status/heartbeat — Android (real Expo heartbeat + maintenance delivered; initial backup/recurring log-only stubs superseded by real coordinator/executor calls in `PushRuntime.kt:25,27`, covered by `PushJobsTest.backupReportsSuccessAndRecurringOpensThenExecutes`; caller/budget admission rechecked before native work and after cleanup/reservation waits; every independent push stage including reports/widgets is gated)
- [x] UnifiedPush (Android, non-GMS) in pure Kotlin — Android (shared jobs, preserved endpoint/connector storage; live distributor test pending)
- [ ] Add Android periodic WorkManager maintenance/recurring execution; existing `PushWork.kt:14,33` handles delivered pushes/registration and `RecurringNotifications.kt:31,58` schedules advisory reminders only. iOS BGTaskScheduler implementation is complete below (`BackgroundScheduler.swift:27,88`; `BackgroundTaskTests`, `f095e8be`)
  - [x] iOS: 15-minute opportunistic app refresh (sync/recurring/widget), 6-hour network processing (also maintenance/enabled backup), shared push deadline/submission budget, expiration/foreground revocation, stage/open-admission checks, bounded completion log drains and session settlement; `apps/ios/PlatformTests/README.md`
- [x] Widget data writer — iOS (`widgetBalance`, six rendered balance/expiry keys, expiry from VTXOs and onchain tip; `WidgetDataWriter.swift:7`; RN's unused `lastUpdated` metadata is not emitted)
- [x] Widget data writer — Android (provider Long keys, expiry sentinel, explicit update broadcast; refreshed by real push)

### 0.7 Backups
- [x] Envelope crypto v1/v2 (salt16 | nonce12 | AES-256-GCM; PBKDF2-SHA256 600k) with Node-generated vectors (`apps/fixtures/backup-crypto.json`) — iOS
- [x] Envelope crypto — Android (same vectors + unicode vectors)
- [x] v2 archive: manifest.json + db.sqlite zip (strict manifest, only those two entries extracted, no-overwrite writes) — iOS
- [x] v2 archive (strict manifest, CRC checks, streaming cap) — Android
- [x] v1 legacy restore (variant folder only, path checks, entry/size caps) — iOS
- [x] Backup upload: FFI snapshot → seal → initiate → S3 PUT → complete, unchanged snapshot skipped, export file — iOS (live-tested against S3 with the dev `.env` server)
- [x] Restore: list (temporary token) → download (size + SHA-256 vs server record) → open → validate → journaled swap (`RestoreTransaction`) under an exclusive session hold → open check without daemon → seed save as commit point; v1 legacy fallback through the same transaction; launch settles an interrupted restore before reading launch state — iOS (live round trip + crash-boundary, race and Keychain-failure tests)
- [x] Unpack limits: streaming 1 GiB cap (headers can lie), CRC-32 verified per entry — iOS
- [x] Restore — Android (same journal semantics; durable seed read-back, launch recovery, failure/race tests)
- [x] Restore UI (RestoreWalletScreen); seed `backup-storage` after a restore (`BackupCoordinator.seedRestored`) — iOS
- [x] Restore UI — Android (private 12-word input/paste/suggestions, staged progress, fixed issue mapping, commit-safe completion and `backup-storage` seeding; saved-phrase and pending-journal launch gates)
- [x] Restore review — Android: platform password accessibility/IME policy, generation-based undo disposal, conditional pasted-clipboard clearing, shared lifecycle gate, durable completion replay, confirmed deletion fallback, explicit suspended recovery and typed connectivity errors; iOS undo/background/clipboard fixes too (RN clipboard gap deliberately improved)
- [x] Restore review verification: Android 563 JVM tests / 42 suites, APK + instrumentation compilation; four Android 15 device cases and funded 12,000-sat / one-movement typed/stored-seed restores; iOS 350 total / 334 passed / 16 opt-in skipped / 0 failures, 80 suites; each full suite once, owned devices/artifacts removed — `apps/android/RestoreTests/README.md`
- [x] Backup coordinator: single in-flight, 5 s debounce, generation counter, backoff 15 s → 15 min with jitter, suspension gates; `backup-storage` with RN's actions; triggers from wallet state changes (session-owned), foreground and push — iOS
- [x] Backup coordinator — Android (generation counter, manual-clock tests, foreground/state-change/push triggers)
- [x] v1 legacy extraction, bounded streaming download, startup scratch sweep and Auto Backup/device-transfer exclusions — Android
- [x] One launch recovery at a time (also clears pre-journal plaintext while suspended); exports sealed only into app-owned swept folders — iOS + Android. iOS recovery is foreground-only; headless jobs defer restore/deletion markers without changing seeds. Android retains recovery on headless opens.
- [x] Backup upload and unchanged-snapshot skip; live S3 round trip at port 3100 — Android
- [x] Shared Node/Info-ZIP v1/v2 archives and a Node-made real wallet snapshot restored/reopened — Android
- [x] Independently built v2 + v1 backup files (Node crypto + Info-ZIP, `backup-archives.json`) open on iOS
- [ ] Verify a direct iOS ↔ Android backup round trip and add v2 support to `scripts/decrypt_backup.js` (line 29 still accepts only v1). Both native v1/v2 codecs, shared fixtures and Node → Android are verified; those do not establish a direct cross-platform round trip (`apps/android/README.md:575`, `60ebc1b7`)

### 0.8 Translations
- [ ] Tool: GT catalogs → `.xcstrings` + `strings.xml` for en/es/ja/ko/sv (placeholders, plurals, rich text flattened)
- [ ] Background notification strings honour the persisted locale

## M1 — Shell, onboarding, Home
- [x] Launch gating: wallet check, onboarding vs main, push-permission gate, battery gate (Android), biometric lock overlay, beta warning — `RootView.swift:13`, `LaunchGateView.kt:124`, `PlatformGateRules.kt:10`; `LaunchStateTests` / `LaunchAndSettingsTest`, `PushPermissionTests`, `PlatformGateRulesTest.matchesRNTruthTable`, `SettingsFeaturesTest.launchBackgroundAndStaleAuthentication` (`879e0160`, `90bd4789`; existing platform-specific evidence below)
- [x] Launch gating: wallet/onboarding, denied-push gate and beta warning — iOS (seed-safety and secure-storage states remain ahead of push)
- [x] Biometric lock overlay on launch and foreground (device-passcode fallback) — iOS
- [x] Biometric app lock on launch and foreground, device-credential fallback; Settings seed re-auth and FLAG_SECURE — Android
- [x] Tab shell: Home, Receive, Send, History (system Liquid Glass tab bar on iOS 26) — `RootView.swift:97,213`, Android Navigation 3 `NoahApp.kt:33,67,91`; native screen/route tests and existing ParityTests live Home/Receive → QR → Send evidence (`91c03a26`, `b0de918c`, `ad310b64`). iOS repeat-payment prefill remains in the cutover parity audit
- [x] OnboardingScreen create/restore — iOS (language picker waits for 0.8 translations)
- [x] Onboarding create/restore — Android (restore from Welcome or a securely stored phrase; never offer creation while restore/deletion recovery is pending)
- [x] Create-or-resume logic (`WalletCreator`, RN `walletCreation` test cases ported) — iOS
- [x] `deriveStoreNextKeypair` + key-0 public key (`staticVtxoPubkey`) on creation, as RN does — iOS
- [x] BetaWarningScreen (accept before create, no double create) — iOS
- [x] MnemonicScreen in onboarding (numbered grid, local-only expiring copy) — iOS
- [x] MnemonicScreen from Settings with biometric re-auth — iOS
- [x] RestoreWalletScreen (backup restore with staged progress) — iOS
- [x] EmailVerificationScreen (send/resend, 6-digit OTP, skip, reconcile verified) — iOS
- [x] LightningAddressScreen (username/domain, optional npub, skip) — iOS
- [x] PushNotificationsRequiredScreen — iOS (physical device + initialized + denied only; provisional/ephemeral remain undetermined like Expo; foreground/recheck/settings resolution; existing seed/recovery gates and biometric overlay retain precedence)
- [x] PushNotificationsRequiredScreen — Android (`PlatformScreens.kt:22`, `LaunchGateView.kt:127`; `PlatformGateRulesTest.matchesRNTruthTable`, `ParityUIDeviceTest.notificationActionsAndBatterySkip`; `879e0160`, `90bd4789`)
- [x] BatteryOptimizationScreen (Android) — persisted Skip, foreground exemption recheck and OS-settings fallbacks (`PlatformScreens.kt:38`, `PlatformAccess.kt:30`; `PlatformGateRulesTest.emailOnboardingRouting`, `ParityUIDeviceTest.notificationActionsAndBatterySkip`; `879e0160`)
- [x] UnifiedPushScreen (Android, non-GMS) — distributor/endpoint/copy/register/Skip/Continue parity with timeout retry (`PlatformScreens.kt:80`; `ParityUIDeviceTest.nonGmsDistributorSelectionAndTimeoutCanRetry`, `879e0160`). Real distributor transport acceptance remains open under cutover
- [x] HomeScreen first pass: balance (BIP-177/sats from `profile-storage`), hide toggle (`privacy-storage`), pull-to-sync, variant badge — iOS
- [x] HomeScreen: fiat (shared currency fixtures, 5-minute quotes), settings/map shortcuts, RN random facts on mount/refresh — iOS (QR shortcut completed with M2)
- [x] HomeScreen: fiat, recent 3 transactions, shortcuts, banners, facts — Android (`HomeScreen.kt:29,70,88,110`; `HomeRulesTest.factsMatchRN` / `fiatUsesSharedRoundingAndLoadingState`, `ParityUIDeviceTest.liveQrHubPastesReceiveRequestIntoSend`, history fixture/device coverage; `547b3256`, `1e633c51`). Remaining broader Home parity gaps are listed under cutover
- [x] HomeScreen: recent 3 transactions, View all, transaction detail — iOS
- [x] Banners: backup status/retry + 5-second success, automatic boarding success + 5-second expiry, pending rounds + detail/copy/explorer + 30-second active-only polling, persisted email dismissal/verification, update warning — iOS
  - iOS deliberately observes successful automatic boarding receipts from the existing journal (observational hook only; boarding behaviour unchanged); historical receipts do not replay. Backup/boarding snapshots follow coordinator/journal events, success banners use one-shot expiry timers, and idle Home has no presentation polling; background jobs pause round progression; completion revisions and wallet-change events resume queries even for jobs completed between UI updates. Existing native boarding review/receipt messages remain.
- [x] Banners: backup status, auto-boarding, pending round, email verification, update warning — Android (`HomeBanners.kt:25`, `HomeScreen.kt:97`; `HomeRulesTest`, `HomeBannerExpiryTest.backupAndBoardingExpireOnceAtTheirFiveSecondDeadlines`, `HomeRoundObserverTest.jobEntirelyBetweenPollsRequeriesImmediatelyAndPausesTheTimer`, `ParityUIDeviceTest.backupRetryAndUpdateWarningCopy`; `547b3256`, `90bd4789`)
- [x] App version check — iOS (`/v0/app_version`, current version once per process, no retry, failure allows use, Home warning + variant TestFlight link; no blocking update screen, as in RN)
- [x] App version check — Android (`ServerRegistration.kt:32`, `HomeBanners.kt:28,88`; `ServerRegistrationTest.stalledVersionCheckDoesNotBlockRegistrationOrEmailAndRunsOnce` / `independentVersionFailureIsCachedAndOnlyRecordsSuccessfulReports`; `1d055c27`, `90bd4789`)
- [x] Shared RN Home predicates/facts/symbols (`home-status.json`) and platform push gates (`platform-gates.json`), retaining unknown-round and iOS authorization cases — iOS
- [x] iOS parity verification: 33 targeted passes; full NoahTests once, 537 total / 515 passed / 22 opt-in skipped / 0 failed (113 suites). Dedicated regtest simulator, 10 screenshots, no payments submitted; simulator/DerivedData/result bundles removed. Deliberate session-lifetime/scanner/presentation differences and device-only acceptance gaps: `apps/ios/ParityTests/README.md`.

- [x] iOS M1 review round 1: completion/wallet events resume idle round queries; observable backup/journal status, one-shot 5 s expiry and 30 s active-round timers; shared Home/platform push fixtures match. 50 targeted passes; full NoahTests once, 553 total / 531 passed / 22 opt-in skipped / 0 failed (115 suites). Owned simulator/DerivedData/7 result bundles removed; `apps/ios/ParityTests/README.md`.

- [x] Launch gating (including unfinished onboarding), Navigation 3 tabs, create/beta/secure mnemonic, emergency email and Lightning identity — Android
- [x] Derive key 0 before fresh-wallet registration and persist `staticVtxoPubkey` — Android
- [x] Home first pass: full balance, persisted unit/privacy, pull-to-sync, variant and email banner; Inter/light/dark theme — Android
- [x] Home fiat and hidden-currency symbols (48 shared fiat cases), RN facts picked on mount/refresh, existing Settings/Map shortcuts — Android
- [x] Home backup progress/failure+retry/5 s success, automatic-board 5 s success from the existing UI completion callback, active pending rounds with 30 s polling and detail sheet — Android (boarding/Send operations unchanged; UI completion and destination wiring only)
- [x] App-version UI: RN’s nonblocking Update Available banner, current/latest versions and variant Play Store link; service failure continues without a banner — Android
- [x] Shared RN Home predicates/facts/symbols (`home-status.json`, RN-source generator); launch push-before-battery truth table (`platform-gates.json`) — Android
- [x] Notification permission and existing push registration after onboarding — Android
- [x] PushNotificationsRequiredScreen: physical-device DENIED gate, request/recheck/settings, foreground recheck; seed recovery takes precedence and biometric overlay remains above all gates — Android
- [x] BatteryOptimizationScreen: after email (or UnifiedPush), one-time initialized-wallet fallback, per-app exemption/list/app-settings fallback, foreground auto-continue and persisted Skip — Android
- [x] UnifiedPushScreen: non-GMS onboarding and Settings, saved/connected distributor selection, endpoint polling/copy, backend registration, Skip/Continue — Android (connector storage and `noah-<variant>` preserved; deliberate improvement: 30 s timeout permits retry instead of leaving RN’s spinner stuck)

- [x] Android M1 round-1 scoped fixes: background-job completion resumes Home rounds (including jobs between polls and refresh/foreground query failures); independent cached version check; observable backup/boarding status with one-shot 5 s expiry; accessible QR copy button; awaited IO startup/recovery/gates/token calls and debug StrictMode; fiat waits for balance + price. Full JVM/APK command once: 2,880 tests / 82 suites / zero failures; final follow-up: 71 focused tests / 10 suites + rebuilt APK; four distinct device checks and private setup passed; owned port-5598 AVD identity/shutdown/deletion verified — `apps/android/ParityTests/README.md`.
- [x] Android initialized-launch StrictMode follow-up — Send reservations, recurring readiness/binding/database inspection and widget IO moved off main (`WalletSession.kt:349`, `RecurringExecutor.kt:30`, `WidgetDataWriter.kt:24,35`; `1d3cacb0`, `85c80d98`, `9d009d9e`, `ac770323`). `WalletSessionUiReadTest.uiSessionStateReadDoesNotBlockBehindIoSuspensionRead`, delayed journal/settlement/widget regressions and archived five-flow zero-violation measurements supersede the historical 37-violation sample; no ANR performance-profiling claim. Details in `apps/android/ParityTests/README.md:175,270`

## M2 — Receive
- [x] ReceiveScreen (BIP-321 unified QR, amount/description sheet, copy sheet, share, completion subscriptions) — iOS
- [x] ReceiveSuccessScreen + animation — iOS
- [x] QRHubScreen (my Lightning-address code / scan) — iOS (address-only QR, name/copy, Profile fallback, existing scanner + paste, existing Send recipient import; denied/canceled/lost-focus reset to My code; live payments are preserved)
- [x] QRHubScreen (my lightning-address code / scan) — Android (`QRHubScreen.kt:25`, `NoahApp.kt:97`; `ParityUIDeviceTest` QR decoding/copy/Profile/close/paste cases and `liveQrHubPastesReceiveRequestIntoSend`; `ad310b64`, `6e3efa7c`; fuller evidence below)

- [x] ReceiveScreen: BIP-321 QR with Noah logo, amount/note/fiat sheet, copy/share, Ark and Lightning completion — Android
- [x] ReceiveSuccessScreen with animation, settled status and refreshed balance — Android
- [x] Shared receive-request (9 cases) and fiat-currency (48 cases) fixtures in JUnit; session observer cancellation/close regressions — Android
- [x] QRHubScreen: literal Lightning-address QR/name/copy (1.8 s feedback), missing-address Profile action, scan/paste via existing CameraX/ZXing and Send importer, camera-permission denial and close/tab lifecycle reset — Android
- [x] QR hub Compose device tests: decode rendered QR, copy exact address, missing-address Profile route, scanner close/paste; public-copy previews for launch/status evidence — Android
- [x] Android M1/M2 parity verification: 2,860 JVM tests / 79 suites, regtest APK, 9 new device checks plus 2 isolated connector checks; 16 screenshots; owned port-5596 AVD shut down and deleted — `apps/android/ParityTests/README.md` (non-GMS verified with Play Services disabled; no non-Google image installed, real distributor delivery pending)

## M3 — Send
- [x] Android Send round-1 review fixes: iOS rounds 7–8 audited destructive override for unresolved receipts; durable original/legacy evidence and corrupt journal/audit quarantine; retained, coalesced, bounded session-owned discard retries and failed-close ownership; last-known receipts after refresh errors; version-2 prepared input outpoints and confirmed competing-spend detection. Bounded Android/iOS Send amount parsing shares 90 adversarial vectors. Full suites once: Android 1,113 passed / 0 failed or skipped plus regtest APK; iOS 378 total / 362 passed / 16 opt-in skipped / 0 failed. Dedicated simulator/build cleanup complete. See `apps/android/SendTests/README.md`.
- [x] Input parsing (BIP21/BIP321, bolt11, bolt12, lightning address/LNURL, Ark address, onchain) — iOS; RN-generated `send-utils`, `send-flow`, `onchain-send`, `lightning-invoice` fixtures; shared `fiat-currency` reused
- [x] SendScreen (amount-first + recipient-first, MAX, method/source choice, fee review, notes, suggestions) — iOS
- [x] AmountKeypad (custom keypad, never with system keyboard) — iOS
- [x] QR scanner (AVFoundation; RN camera permission wording preserved) — iOS
  - QR hub reuses the existing native QR scanner (QR only; RN also recognizes EAN-13). Paste uses iOS PasteButton privacy consent. Only SendView model injection and shell routing changed; Send/payment internals remain unchanged.
- [x] SendConfirmation + SendSuccess sheets — iOS
- [x] Repeat payment metadata (`updateHistoryMetadata`, RN envelope) — iOS
- [x] Submission lock, persisted attempt, cancellation-shielded wallet calls, sync/history reconciliation without automatic retry — iOS
- [x] Live Ark, BOLT11, Ark-to-onchain, historical onchain MAX (now disabled), forced FFI failure/relaunch verification — iOS; evidence and limits in `apps/ios/SendTests/README.md`
- [x] Send safety: recipient-bound routes, reviewed MAX funds/VTXOs, rail-specific durable reconciliation, explicit uncertain override, submitted/confirmed status — iOS; regression and live evidence in `apps/ios/SendTests/README.md`
- [x] Disable onchain-wallet MAX at UI/review/submission boundaries; explain entering an amount; offchain MAX retains reviewed VTXO IDs — iOS
- [x] Round-2 Send safety: missing BDK history stays locked, exact-amount tx matching, pre-recording error allowlist, real BOLT11 amounts/fees and already-paid state, receipt revalidation after reorgs, honest offboard tracking copy — iOS
- [x] Round-3 Lightning checkpoint guard — read-only SDK state lookup before pay; existing sends never reach pay, real checkpoint amounts/fees remain locked across restart — iOS
- [x] Round-3 entered-amount BDK sends retained; accessible fee/total estimate and possible higher multi-input fee copy; actual fee on receipts when known; FFI 14 fee ceiling marked user priority — iOS
- [x] Round-3 BDK identity: only persisted submit-returned txids establish receipts; snapshot differences are possible matches and remain uncertain/locked with destructive override — iOS
- [x] Round-3 journal safety v3: v1/early-v2 receipts reopen uncertain, recorded txids retained only as evidence, migration writes fail closed; new receipt provenance is persisted — iOS
- [x] Round-3 verification: full NoahTests 264 total / 253 passed / 11 opt-in skipped / 0 failed; live amountless BOLT11 reuse without a second send and entered-amount BDK estimate/actual-fee UI — iOS; `apps/ios/SendTests/README.md`
- [x] Round-4 LNURL resolve-only → exact-amount/network/expiry validation → BOLT11 checkpoint guard; offers refuse any pending checkpoint before pay, remaining locked across restart — iOS
- [x] Round-4 Ark→onchain MAX keeps exact reviewed VTXOs/total, accessible recipient and fee estimates, and txid-bound actual output/fee receipts (pending until readable); prepared-offboard amount/fee binding added to FFI 14 — iOS
- [x] Round-4 direct Ark success persists SDK-returned-success provenance; close-before-Done receipts survive reload for Ark, BOLT11, LNURL, offers, onchain and offboard without weakening history-candidate rules — iOS
- [x] Round-4 verification: full NoahTests 291 total / 278 passed / 13 opt-in skipped / 0 failed (323 passed individual runs); live 1,000-sat Ark receipt survives kill/relaunch before Done; Ark→onchain MAX actual output/fee and exact reviewed VTXOs independently verified — iOS; `apps/ios/SendTests/README.md`
- [x] Round-5 Send wallet identity: fingerprint + network bound to review/journal; successful delete/create/restore invalidates in-memory receipt state; foreign journals discarded, same-wallet locks retained, identity-free legacy journals quarantined until explicit destructive override — iOS
- [x] Round-5 pending BOLT11/resolved-LNURL adoption: persist canonical checkpoint identity and reviewed fee/total before check-only advancement; exclusive session hold, no fresh-payment fallback after disappearance, real terminal receipts, held funds bypass fresh spendability checks — iOS
- [x] Round-5 regression coverage: real delete → different-seed restore, legacy/foreign journals, cancellation/session exclusion, signed-invoice/amount/fee/total/revocation checks, missing evidence and checkpoint disappearance with zero payment calls — iOS; verification and live limits in `apps/ios/SendTests/README.md`
- [x] Round-5 verification: full NoahTests 318 total / 303 passed / 15 opt-in skipped / 0 failed (364 passed individual runs); real same-process delete/different-seed restore regression passed; live held-checkpoint creation passed but adoption was not demonstrated because the checkpoint failed/revoked before review — iOS; `apps/ios/SendTests/README.md`
- [x] Restore onchain-wallet MAX with prepared `.drainAll`, confirmed-only inputs and exact fee/amount review (FFI 14) — iOS
- [x] Read-only offboard chain status (FFI 15) — iOS and Android on 0.5.0: not yet seen / mempool / confirmed height; errors keep the last status, absence never unlocks.
- [x] Android 0.5.0 round-1 Send receipt ordering: persist exact returned txid/proof before optional network status; interrupted lookup/restart and failed-write regressions. Offboard status bypasses BDK sync/graph, while BDK send reorg/conflict/retry rules remain intact
- [ ] Adopt explicit offboard broadcast outcomes/prepared offboards (remaining FFI 15) and durable Ark/offboard operation IDs (FFI 16) on both platforms; 0.5.0 read-only txid status is done, but submission still uses `sendOnchain` / `offboardVtxos` and pre-receipt history stays candidate evidence (`WalletSession+Send.swift:181,210`, `SdkSendWallet.kt:136`, both `SendReconciliation` implementations)
- [x] Adopt bark-bolt-ffi 0.4.0 — iOS: signed onchain fee/amount/vsize/input-count review, two-minute expiry and lifecycle discard, confirmed-only MAX, exact txid journaled before `broadcastPrepared`, explicit broadcast outcomes, typed call-specific Send/M6 errors; round-5 Lightning checkpoint identity/amount/fee guards remain valid. Verification: `apps/ios/SendTests/README.md`
- [x] Round-6 Send crash recovery — iOS: unconfirmed prepared/broadcasting journals retain the broadcast-retry lock until confirmation; failed reconciliation sync surfaces an error and cannot upgrade cached evidence; crash-before-receipt and failed-receipt-write restart regressions. Verification: `apps/ios/SendTests/README.md`
- [x] Round-6 Send review lifetime — iOS: discard/exit/background/recipient changes invalidate in-flight reviews with a generation token; late preparations immediately discard their reserved inputs, and stale errors cannot alter the new flow. Suspended-preparation regressions: `apps/ios/SendTests/README.md`
- [x] Round-6 verification — iOS: targeted Send 61 tests / 103 individual runs passed; full NoahTests once, 351 total / 335 passed / 16 opt-in skipped / 0 failed (403 passed individual runs), 79 suites; isolated simulator and build/result artifacts cleaned. `apps/ios/SendTests/README.md`
- [x] Adopt bark-bolt-ffi 0.4.0 prepared sends and typed errors — Android Send: exact signed amount/fee/vsize/input review, confirmed-only MAX, lifecycle/expiry discard, pre-broadcast txid journal, explicit outcomes and crash-recovery broadcast lock
- [x] Input parsing and RN-generated `send-utils`, `send-flow`, `onchain-send`, `lightning-invoice` fixtures; shared `fiat-currency` reused — Android
- [x] Compose SendScreen: amount-first/recipient-first, custom amount keypad, MAX, method/source choice, fee review, notes, suggestions, confirmation/success sheets — Android
- [x] CameraX + existing ZXing QR/EAN-13 scanner, RN camera permission wording — Android (physical-camera acceptance remains unverified)
- [x] Versioned, wallet-bound durable Send controller: synchronous lock, atomic journal, counted cancellation-shielded session reservation, separate rail evidence, uncertain destructive override and reorg/status checks — Android
- [x] BOLT11/LNURL checkpoint inspection and journaled check-only adoption; canonical invoice/hash/amount/fee/total guards, offer checkpoint block, actual already-paid amounts, in-app validated LNURL resolution — Android
- [x] Repeat-payment metadata and History callback → recipient prefill with full revalidation — Android
- [x] Live emulator Ark/BOLT11, exact entered-amount onchain send, confirmed-only MAX, Back/reprepare and kill/relaunch before Done — Android; full verification 993 JUnit cases passed / 0 failed / 0 skipped, APK and instrumentation Kotlin compile passed. Evidence and limits: `apps/android/SendTests/README.md`
- [ ] External LNURL/BOLT12 provider acceptance and camera hardware verification — iOS
- [x] Real Pixel 6 Send — Android: BOLT11 2,000 + 1,000 sats paid to CLN, with fees matching captaind's Lightning schedule; exact prepared onchain send matches Bitcoin Core (142 vB, 143 sats) and confirms in-app; Lightning result sheet now reads "Payment sent" (iOS parity). `apps/android/SendTests/README.md`

## M4 — History
- [x] TransactionsScreen (filters, pull refresh, CSV export of filtered list) — iOS
- [x] TransactionDetail (sheet / split pane, historical fiat, technical details, explorer, repeat callback) — iOS; RN-generated `transactionHistory` / `barkMovement` fixtures; Send hookup remains `TODO(M3)`

- [x] TransactionsScreen: unified Ark/onchain history, filters, pull refresh, filtered CSV via FileProvider/share sheet — Android
- [x] TransactionDetail: phone sheet / wide split pane, historical fiat, technical details, variant explorer and repeat callback (Send hookup `TODO(M3)`) — Android
- [x] Home recent 3 transactions, View all and detail; 107 shared history fixture cases and live three-rail verification (`apps/android/HistoryTests/README.md`) — Android

- [x] History review: fractional/offset dates, transitive deterministic ordering under partial block-time enrichment, and CSV formula neutralization — both platforms; shared `history-regressions` fixtures

- [x] Optional History enrichment runs in a cancellable, single-worker job, publishes incrementally, and leaves wallet observation / Refresh / Export responsive — both platforms

- [x] History CSV lifetime: Android startup sweep, failed-share deletion and scheduled 24-hour expiry; iOS uses in-memory `DataRepresentation` without app-owned CSV files

- [x] History review A4: cached enrichment merges/sorts once per 32-row batch, yields between batches, and flushes completed rows before network misses; warmed 3,000-row cache tests consume wallet events during enrichment on both platforms

## M5 — Settings & recovery
- [x] SettingsScreen (biometrics, auto-board preference, mailbox, version/debug unlock) — iOS
- [x] Suspend/resume, delete wallet (files before seed, under a reservation; refused while a restore is pending), re-register: model layer + suspended Home — iOS
- [x] Settings danger-zone rows wired to suspend/resume, delete wallet (type "delete" to confirm, as RN), re-register — iOS
- [x] ProfileScreen — iOS
- [x] LanguageSettingsScreen, CurrencySettingsScreen, BitcoinUnitSettingsScreen — iOS (language persists; translated copy awaits 0.9 catalogs)
- [ ] Implement EsploraSettingsScreen on both platforms: edit/reset, URL normalization, genesis/network test, persist override and coordinated wallet reload matching RN `EsploraSettingsScreen.tsx`; native Ark Info still shows a disabled "Coming soon" row (`ArkInfoView.swift:36`, `AccountScreens.kt:131`)
- [x] BackupSettingsScreen — iOS
- [x] ArkInfoScreen — iOS
- [x] ExportDatabaseScreen (encrypted `.noahbackup`) — iOS
- [x] LogScreen — iOS
- [x] FeedbackScreen (screenshot ≤5 MiB, device info) — iOS
- [x] NoahStoryScreen (bundled audio) — iOS
- [x] DebugScreen — iOS

- [x] Settings, Profile, Language/Currency/Bitcoin Unit with RN-compatible persistence — Android
- [x] Suspend/resume, files-before-seed deletion under reservation, pending-restore refusal, typed confirmation, re-register and suspended Home — Android
- [x] Backup settings, manual backup while disabled, server list/delete and remembered-upload invalidation — Android
- [x] Ark info, encrypted database export/FileProvider cleanup, logs, bounded screenshot feedback, bundled story audio and version-tap Debug — Android
- [x] Battery optimization settings screen — Android (onboarding gate remains M1)
- [x] M5 regtest build, lifecycle/storage regression tests and isolated funded-emulator acceptance — Android (`apps/android/SettingsTests/README.md`)

- [x] M5 review A2: Android mailbox authorization refresh at startup/resume and after re-registration, preserving opt-out and iOS's 60-day renewal / 89-day expiry; registration regression tests

- [x] M5 review A3: checked scratch cleanup (including Android preflight copies) and directory durability before deleting secrets on both platforms; undeletable plaintext preserves seed/token/settings

- [x] M5 review A4: Android history observation keys wallet availability; suspension/deletion clear cached rows and invalidate old enrichment before a replacement wallet

- [x] M5 review A5: onboarding recovery phrase reloads on foreground return with generation and foreground guards, after clearing on background

- [x] M5 review A1/A6: identify AndroidX's API 24–28 credential activity hop, preserve its generation across stop, invalidate on real background/system-dialog dismissal or screen-off; secure the authenticated Activity before rendering/backgrounding (RN source searched; no Android secure-screen hook found)

- [x] M5 review A7: model-owned encrypted/log exports survive configuration changes and asynchronous receivers, with scheduled/startup 24-hour expiry and exact URI revocation; plaintext staging is discarded immediately

- [x] M5 review verification: 385 Android JVM tests; regtest APK + instrumentation compilation; two API 28 device privacy/onboarding cases; full iOS NoahTests (247 passed, 10 opt-in skipped) — evidence in `apps/android/SettingsTests/README.md`

- [x] M5 round-2 A1: hide/lock Android credential handoffs without changing their valid generation; invalidate unexpected resume and exported singleTask relaunch; API 28 direct `am start` regression plus JVM state-machine tests

- [x] M5 round-2 A2/A3 — iOS: await backup passes/exports, check scratch and retained-share cleanup before database removal, preserve/reopen the wallet on cleanup failure, and finish durable marked deletion at UI/headless launch; ordering/relaunch/gate regressions

- [x] M5 round-2 A2/A3 — Android: drain backup/export operations; check scratch, preflight copies, Settings and History exports (with exact URI revocation) before database removal; preserve/reopen on cleanup failure; durable deletion marker recovered before UI/headless wallet opens; ordering/relaunch and real-grant device regressions

- [x] M5 round-2 verification: 526 Android JVM tests (36 suites, no failures/skips), regtest APK and instrumentation compilation; four API 28 device privacy/deletion-grant cases; full iOS NoahTests 293 total / 281 passed / 12 opt-in skipped / 0 failed (66 suites), both full suites run once; disposable devices mapped/shut down/deleted — `apps/android/SettingsTests/README.md`

- [x] M5 round-3 deletion identity — iOS: one shared gate for Settings deletion, creation, restore and UI/headless recovery; marker rechecked after ownership, mismatched markers discarded without touching the current wallet; deterministic committed-delete → queued creation/recovery regression

- [x] M5 round-3 deletion identity — both platforms: durable random local instance IDs, atomic rename before recursive removal, matching-marker recovery across partial removal; legacy one-byte markers only auto-finish without a live datadir, with typed explicit deletion available for incomplete wallets

- [x] M5 round-3 bounded deletion drain — both platforms: cancel in-flight backup uploads, at most 30 s waiting for backup/export settlement before commit, actionable retry error with database/seed intact; iOS upload total resource timeout 60 s, matching Android

- [x] M5 round-3 export durability — Android: checked History/Settings export removal followed by directory fsync before deletion commit; sync-failure/retry tests preserve database/seed/preferences. iOS retained shares already use the checked, synced scratch sweep; CSV remains in memory

- [x] M5 round-3 verification: 532 Android JVM tests (36 suites, zero failures/errors/skips), regtest APK and instrumentation Kotlin compilation; iOS NoahTests 321 total / 308 passed / 13 opt-in skipped / 0 failed (73 suites). Each full suite ran once; own simulator verified, shut down and deleted — `apps/android/SettingsTests/README.md`

- [x] M5 round-4 deletion ownership — both platforms: durable salted seed HMAC and local instance ID in the external marker; absent datadir never bypasses seed ownership; mismatched/unreadable seed and legacy markers discarded without changing the wallet; seed A/B/none/unreadable matrix

- [x] M5 round-4 mixed restore/deletion recovery — both platforms: shared launch/push/WorkManager recovery settles restore before deletion; impossible marker+journal coexistence invalidates and syncs stale marker before journal removal, including unlink-sync retry; matching-marker rollback/completion, unlock retry, concurrent recovery and post-rollback interruption regressions

- [x] M5 round-4 verification: Android 557 JVM tests (40 suites, zero failures/errors/skips) and regtest APK assembly; signed arm64 iOS NoahTests 351 total / 335 passed / 16 opt-in skipped / 0 failed (79 suites), each full suite once. Verified own simulator identity/shutdown before deletion; removed DerivedData and all result bundles — `apps/android/SettingsTests/README.md`

- [x] M5 round-5 Android startup: one lifecycle-gate interval through combined restore/deletion recovery, completion replay and wallet opening; matching-marker + restore-journal regressions use production gate → RestoreRecovery → session order

- [x] M5 round-5 deletion staging — both platforms: unreadable secure storage retains valid bound markers/staging and shows “Waiting for secure storage”; readable stale/legacy markers discard safely; checked, synced unmarked staging cleanup before restore, creation and a new deletion marker, plus recovery of older committed markers over leftover staging. Supersedes round 4's unreadable-seed discard policy

- [x] M5 round-5 restore entry — iOS: visible words retained for RN usability; keyboard extensions blocked app-wide, all prediction/correction/smart-edit traits disabled, ASCII/no capitalization/no content type, fitting on-screen-only suggestion rows with visible-word accessibility labels; delegate and actual UITextField configuration tests

- [x] M5 round-5 verification: Android 569 JVM tests (42 suites, zero failures/errors/skips) plus regtest APK assembly; signed arm64 iOS NoahTests 374 total / 358 passed / 16 opt-in skipped / 0 failed (82 suites; 470 expanded passes), each full suite once after 75 Android / 53 iOS targeted tests. Owned simulator identity/shutdown checked before deletion; DerivedData and all result bundles removed — `apps/android/SettingsTests/README.md`


## M6 — Onchain & exits
- [x] iOS 0.5.0 review round 2: exact cached board txids reconcile despite optional status failures, with separately saved/displayed lookup errors and last status; Auto-Board rereads confirmed funds before claiming to preserve the exact reviewed reserve. Targeted recovery 32 tests / 38 executions and boarding 39 / 57, zero failures/skips; full NoahTests once 489 total / 470 passed / 19 gated skips / zero failures (684 expanded passes, 104 suites). Owned simulator, DerivedData and all five result bundles deleted. SendTests/ExitTests READMEs
- [x] iOS 0.5.0 review round 1: BDK eviction/confirmed-competing-spend downgrades survive chain-status errors with separately saved lookup errors and last chain status; restart and acknowledgement locks retained. Auto-Board discards an affordable fee probe before preparing the exact reserve review; typed errors/cancellation/cleanup covered. Targeted 76 tests / 133 executions, zero failures/skips; full NoahTests once 467 total / 448 passed / 19 gated skips / zero failures (611 expanded passes, 99 suites); owned simulator, DerivedData and six result bundles deleted. SendTests/ExitTests READMEs
- [x] iOS 0.5.0 verification: targeted 66 tests / 89 executions; full NoahTests once 452 total / 433 passed / 19 gated skips / 0 failures (577 expanded passes, 98 suites). Nine Core-funded live stages: exact amount/MAX/Auto-Board vs Core, offboard mempool → confirmed, board status and canceled exit after reopen. Owned simulator, DerivedData and all result bundles removed; `apps/ios/SendTests/README.md` and `apps/ios/ExitTests/README.md`
- [x] Claim-destination regression strengthening — both platforms: deferred/failing prefill and retry, empty-destination rejection, exact reviewed/SDK-drain/journal destination, and zero submissions on either ownership flip, ownership errors or invalid/wrong-network addresses; Android targeted 23/full 1,358 passed, iOS targeted 9/full 432 total (414 passed, 18 gated skips), both full suites once with zero failures; no bug found, owned iOS simulator/build artifacts deleted
- [x] Claim destination safety — both platforms preserve external/cold-storage sweeps; fresh Noah-address prefill/restore action, native ownership warning in field/review, address/network/ownership revalidation at execution and regression tests
- [x] Android VTXO monetary selections — one toggleable checkbox node per row, spoken shortened ID/amount/status/checked state, disabled/checked semantics tests
- [x] Android Auto-Board review — confirmed-only DrainAll sizing probe, discarded before one reduced fixed-gross preparation with 43-vB change allowance; exact signed reserve ≥5,000 sats and net threshold checked before review; error/cancellation/lifecycle/expiry discard regressions
- [x] M6 small-fix verification — Android full suite once: 1,334 passed / 0 failed/skipped + APK; final targeted 11 passed + APK. iOS full NoahTests once: 410 total / 393 passed / 17 gated skips / 0 failures (520 expanded passes); owned simulator and temporary build artifacts deleted. Details/evidence in both ExitTests READMEs
- [x] Prepared boarding (FFI 18, 0.5.0) — iOS: exact gross/net/Ark fee/onchain fee/total/vsize/input review, confirmed-only drain MAX, two-minute review lifetime and lifecycle/late-result discard; persisted confirmation-only Auto-Board with a discarded confirmed-only DrainAll fee probe, one reduced-gross preparation and exact ≥5,000-sat reserve; confirmation rereads confirmed funds to preserve the exact shown reserve, including unselected coins; M6 txid journaled before submit, explicit outcomes and same-ID persistence cleanup
- [x] VTXOsScreen + VTXODetailScreen + delegated refresh review dialog — iOS
- [x] Emergency-exit read-only overview/estimates, quote < 30 s, 330-sat eligibility, deposit sheet, claim/cancel reviews — iOS
- [x] Exit overview/history uses Bark `listAllExits` with exact amounts and terminal rows after reopen (FFI 20, 0.5.0); detail reads persisted state/history. Retired app-side exit ID/movement discovery; 187 RN-derived fixtures retained — iOS
- [x] Starting/progressing exits — iOS RN parity: explicit start review explains automatic background broadcasts/fee bumps and onchain fees; manual Progress Exits review, cancel until final broadcast, claim reviews; optional manual-daemon policy is P2 FFI 17
- [x] Suspended wallet gates — iOS Settings M6 rows remain navigable like RN; M6 screen actions/queries, Home refresh, boarding minimum/address queries and Auto-Board perform no wallet operations
- [x] Scoped durable M6 uncertainty — iOS: unrelated recovery remains available, exact pre-broadcast allowlist, cached + read-only chain-source Check status (no BDK sync/rebroadcast) and explicit destructive override with retained timestamp/consent audit (newest 100 overrides), cached late-original reconciliation and separate receipts; single/array migration and relaunch tests
- [x] M6 round-2 regression verification — iOS: targeted 12 + 25 + 4 tests; full NoahTests once: 316 total / 303 passed / 13 opt-in skips / 0 failures (349 expanded passes); owned iOS 27.0 simulator deleted; evidence in `apps/ios/ExitTests/README.md`
- [x] Board/exit-claim receipt chain status (FFI 15, 0.5.0) — iOS; exact prepared board txid crash recovery uses cached BDK transactions/pending boards/movement anchors independently of optional network status, never amount matching; lookup errors persist/display separately with the last known status
- [ ] Durable operation IDs for pre-ID ambiguous refresh/exit/claim outcomes (prepared boards now journal a funding txid first) — FFI 19; iOS never reconciles by amount or automatically resubmits
- [x] Live exit start → automatic daemon broadcast/CPFP → 144-block timelock → reviewed manual progress → claim/confirmation — iOS; manual boarding/MAX actual fees and suspended UI verified (see `apps/ios/ExitTests/README.md`)
- [x] Live exit cancellation before final broadcast — iOS throwaway wallet; canceled detail/timeline and overview retained after reopen; simulated lost board receipt leaves exits available and read-only Check status resolves exact txid (see `apps/ios/ExitTests/README.md`)
- [x] BoardArkScreen + persisted confirmation-only Auto-Board — Android 0.5.0: exact gross/net/Ark fee/chain fee/total/vsize/input review, confirmed-only Amount/DrainAll, two-minute pause/back/wallet-change expiry/discard and generation guards; txid saved before submit, failed delivery stays locked, notAttempted permits review, accepted-cache persistence repaired with the same ID
- [x] VTXO list/filter/selection/detail + reviewed delegated refresh; suspended/background gates — Android
- [x] Emergency-exit overview/estimates/deposit/detail timelines, fresh start/progress/claim/cancel reviews, automatic Bark daemon consent and scoped journals/retained overrides — Android. SDK `listAllExits(true, false)` owns overview/history and exact amounts, including terminal rows after reopen without Noah discovery; direct details remain SDK reads. Legacy index writes/store retired; corrupt legacy files cannot block start/claim/cancel and need no display migration. Board/claim receipts query read-only chain status; no amount reconciliation
- [x] Android 0.5.0 verification: 1,376 JVM tests / 63 suites, zero failures/errors/skips + APK; Android-test sources compile; 6 owned-emulator stage passes, real amount/MAX reviews match Core funding outputs/fees/vsize, exact Auto-Board reserve/decline, offboard mempool → block 629, persisted canceled rows after reopen without Noah index. Emulator 5592 and temporary SDK outputs deleted; evidence/limits in both Android SendTests/ExitTests READMEs
- [x] Android 0.5.0 review round 1: durable receipt before status; fundable DrainAll Auto-Board sizing + 43-vB change allowance; offboard status independent of BDK; retired exit index; iOS recurring approval copy. Final JVM/APK command once: 2,770 tests / 70 suites, zero failures/errors/skips, APK assembled (1m 4s); deterministic checks only, no live device
- [x] Android 0.5.0 review round 2: BDK eviction/confirmed-competing-spend downgrades persist despite optional status errors, with separate warnings, last chain status and acknowledgement/restart locks; exact-txid board crash recovery uses local pending/movement/BDK evidence independently of lookup failures, never amounts. Targeted Send 21 / Onchain 52 passed; full JVM/APK command once: 2,773 tests / 70 suites, zero failures/errors/skips, APK assembled (2m 25s); deterministic tests only, no devices or Docker — both Android SendTests/ExitTests READMEs

## M7 — Recurring payments
- [x] Both recurring round-3 correctness: all 20 RN factory fields mandatory (including nullable keys), shared missing-field classification/lossless-row regressions; settlement retains underlying policy across temporary suspension; clock/execution admission and protected binding reads avoid pre-onboarding authority failure on both platforms. Existing rollback, consumption, generation and storage-health gates retained; Android final 3,001 tests / 104 suites + APK; iOS full once 665 total / 642 passed / 23 opt-in skipped / 0 failed, 132 suites (959 expanded passes); owned simulator/DerivedData/six result bundles removed; both RecurringTests notes
- [x] Both round-2 money-path fixes: durable wallet lifecycle stop/suspension + final generation guard outside MMKV; idempotent consumed/application settlement including pre-marker receipts, acknowledgement independent of policy advancement/storage latch, explicit fresh-revision reapproval with retained consumption; Android protected reminders and cancellation/budget between independent stages; fast injected ledger fsync with dedicated real ordering tests. Android final: 2,968 tests / 99 suites / 0 failures/errors/skips plus regtest APK; iOS full once: 643 total / 620 passed / 23 opt-in skipped / 0 failed, 128 suites, 913 expanded passes; owned simulator removed; both RecurringTests notes
- [x] Finding-1 Android integration boundary: base `f4ba6620` wires `AppServices.stopRecurringAutomation` to the durable wallet authority before its standalone MMKV restore-preparation generation write; the older handoff patch in Android RecurringTests notes is historical. WalletLifecycle suspend/delete rollback regressions remain passing
- [x] Both M7 MMKV policy rollback: durable per-wallet-binding policy ledgers precede every MMKV commit; pause/resume/edit/delete, bootstrap-once/missing/corrupt evidence, final submission/manual review and wallet deletion/recreation regressions. Occurrence consumption and revision-bound settlement retained. Android full JVM/APK once: 2,927 tests / 90 suites / 0 failures/skips; iOS full NoahTests once: 617 total / 594 passed / 23 opt-in skipped / 0 failed, 123 suites (864 expanded passes); owned simulator/DerivedData/seven result bundles removed; both RecurringTests notes
- [x] M7 iOS RN numeric compatibility: decode rows independently; wide/unreadable policies remain visible, stopped and cancellable with verbatim original JSON; representable Int counts and fractional timestamps retain normal scheduling. Shared RN-written range fixture added, Android copy unchanged. Targeted 54 / 42 tests passed; full NoahTests once: 496 total / 477 passed / 19 opt-in skipped / 0 failed, 105 suites (698 expanded passes). Owned simulator/DerivedData/five result bundles removed; `apps/ios/RecurringTests/README.md`
- [x] M7 iOS round-4 review: reject/disable status checks during execution and never settle live submission as uncertain; permanent submission/status race fails before fix, passes with one consumed occurrence and active schedule; settled uncertain checks remain available. Targeted 49 tests / 81 expanded runs passed; full NoahTests once: 491 total / 472 passed / 19 opt-in skipped / 0 failed, 104 suites (686 expanded passes). Owned simulator/DerivedData/three result bundles removed; screen-open execution and policy/fee guards unchanged; `apps/ios/RecurringTests/README.md`
- [x] M7 iOS round-3 review: validate/quarantine malformed journal evidence; revoke in-progress foreground ticks on deactivation; failed policy/stop-write latches in final guard; legacy uncertainty blocks resume/edit/override independently of due planning; RN note routing/end-date parity, notification-response routing, 21-million-BTC approval cap with visible oversized RN policies, strict envelope versions. All four reviewer probes retained; targeted 43/43/62/40/64 tests; full NoahTests once: 477 total / 458 passed / 19 opt-in skipped / 0 failed, 104 suites (654 expanded passes). Owned simulator/DerivedData/eight result bundles removed; fee policy and Android unchanged; Android follow-ups/limits in `apps/ios/RecurringTests/README.md`
- [x] Schedule math (local calendar, DST, month-end) — iOS; 1,260 RN-generated shared calendar cases, 36 policy cases, RN storage fixture
- [x] RN MMKV persistence/migration — iOS; lossless version-0 envelope, fail-closed reads/writes, wallet fingerprint/network + local-instance binding
- [x] Executor — iOS; latest due only, fsynced before-send/consumed markers, existing Send quote/checkpoint/journal/reconciliation, exclusive cancellation-shielded session hold, final pause/cancel/edit/suspension/identity checks, uncertain outcomes and audited destructive override
- [x] RecurringPaymentsScreen + RecurringPaymentEditorScreen — iOS; Settings row, create/edit, preview, pause/resume/cancel, last-run and uncertain status
- [x] Foreground + `recurring_payment_due` through existing push jobs and `AppServices.ensureWalletOpen` — iOS; restore/deletion gates, asynchronous reminders/results
- [x] Server wake sync — iOS; generated authenticated client, serialized replacement payloads, durable pending retry flag
- [x] Live regtest + full NoahTests evidence — iOS; exactly one 1,000-sat Ark send across push/foreground/repeat/pause/cancel; 405 total / 388 passed / 17 opt-in skipped / 0 failed, 88 suites; `apps/ios/RecurringTests/README.md`
- [x] M7 iOS round-1 safety: persistent teardown stop generation; cross-revision consumed timestamp watermarks + migration; round-1 max(1% of amount, 200 sats) fee policy with needs-approval/manual Send review (blanket Lightning gating and cap superseded by the ArkInfo policy below); final current-clock due/backoff guard; time-zone/idle wake refresh; independent push deadline + submission budget
- [x] M7 iOS round-1 verification: 59 focused tests / 108 runs; full suite once, 421 total / 403 passed / 18 opt-in skipped / 0 failed, 90 suites; live BOLT12 20,000 sats skipped automatically then paid once on explicit approval (75-sat quote below cap; above-cap tested deterministically); `apps/ios/RecurringTests/README.md`
- [x] Unattended fee cap from `ArkInfo.feeSchedule.lightningSend` (decided with the user 2026-10-20), replacing the round-1 max(1%, 200) cap and blanket Lightning approval: auto-pay only when the quote and the schedule's worst-case fee F(a) are both ≤ min(2·F(a), max(200, 2% of a)), otherwise needs approval + notify; published fallback when ArkInfo is unavailable; `apps/fixtures/recurring-fee-cap.json` — iOS: captured quote/schedule rechecked at submission; 24 shared cases + UInt64 saturation + executor regressions, 34 focused tests / 56 runs passed; full suite once: 433 total / 415 passed / 18 opt-in skipped / 0 failed (554 passed individual runs). Pinned bark fee path verified; accepted schedule-change race documented. Live re-run passed after Ark funding from the Pixel: 20,000-sat BOLT12 quoted 155 vs cap 400 (worst case 235), paid automatically exactly once; `apps/ios/RecurringTests/README.md`
- [x] M7 iOS round-2 safety: portable journal v2 retains consumed indices per wallet instance/schedule/policy revision alongside cross-revision timestamp watermarks; conservative durable v1/dictionary migration blocks unknown revision history; exact New York→UTC rollback, spring/fall DST and cross-revision regressions
- [x] M7 iOS round-2 attended approval retains Send success/receipt until Done with replay protection; scene-scoped 60-second foreground runner reuses the executor, coalesces push/screen triggers and cancels on scene deactivation
- [x] M7 iOS round-2 verification: focused runs 44/35/41 tests (67/58/71 individual runs); full NoahTests once: 447 total / 429 passed / 18 opt-in skipped / 0 failed, 95 suites (576 expanded passes); owned simulator/DerivedData/six result bundles removed; ArkInfo fee policy unchanged; `apps/ios/RecurringTests/README.md`
- [ ] Physical recurring-wake delivery, timed local reminder delivery and external LNURL/BOLT12 provider live acceptance — iOS (Android physical recurring transport/timed reminders/external-provider acceptance also remain unverified in `apps/android/RecurringTests/README.md:854`). Funded RN-recurring upgrade is already verified by `UpgradeCompatibilityTests.recurringPolicyIsIdenticalAndHasNotRun` and `apps/ios/UpgradeTests/README.md:197` (`5aa40aa6`); the earlier RecurringTests limitation is historical
- [x] Schedule math/executor, RN MMKV + wallet-instance binding, screens, foreground/push + independent deadline, notifications and serialized server wake sync — Android; round-3 uses Long occurrence counts, keeps unrepresentable RN rows visible/stopped/lossless, notifies the first active preflight failure and confirmed settlement after cancellation, rejects string envelope versions and legacy-uncertain resume/edit up front, and defaults Custom to 14 days; RN actual validator/writer verified 1 through Number.MAX_VALUE with Android-only fixture; targeted 1,435/1,439/1,442 passed, final JVM/APK command once: 2,830 passed / 73 suites / 0 failed/skipped; parity follow-up aligns new/edited end dates with RN 23:59:00.000 while preserving stored timestamps; oversized RN policies remain visible as needs attention without blocking healthy schedules; validate cross-record journal consumption before reads/writes, preserve malformed originals plus durable quarantine, and stop policies visibly across relaunch (including negative-index/zone-rollback regression); unchanged fixture-defined captured Lightning fee cap (all 24 cases), persistent stop generation; round-1 portable journal v2 retains consumed indices/timestamps and blocks incomplete legacy history, attended receipts stay until Done, STARTED lifecycle checks every minute through the shared executor; targeted 1,432 passed / 11 suites, full JVM/APK check once: 2,817 passed / 72 suites / 0 failed/skipped; earlier four passing instrumentation tests and exactly one 1,000-sat Ark + one automatic 1,000-sat BOLT12 send (79-sat fee, 166-sat cap); parity JVM-only limits and earlier setup/cleanup evidence in `apps/android/RecurringTests/README.md`
- [x] Android unattended recurring fee approval copy matches iOS's separate quoted-fee, published-schedule and zero-fee Ark reasons; policy unchanged, exact-copy + shared fixture/executor checks: 1,357 passed

## M8 — BTC Map
- [x] MapLibre native map, themed styles, clustering — iOS, Android
- [x] Bundled snapshot + BTC Map v4 sync, GeoNames city search — iOS, Android (RN-generated shared fixtures, persisted viewport; Android packages shared assets directly)
- [x] Merchant detail sheet / pane, location — iOS, Android (Android requests coarse/fine only on user action; verification in `apps/android/MapTests/README.md`)

- [x] BTC Map resource limits — iOS: streamed 32 MiB maximum, 100k records, 16 KiB strings, no immediate oversize retry and five-minute cooldown (measured payloads in `apps/ios/MapTests/README.md`)
- [x] Android M8: mirror transfer/record/string limits and oversize cooldown/cache preservation (bounded streaming/cache reads, merged-cache validation; JUnit regressions)

- [x] BTC Map review — iOS: permission requested only by Locate, accessible Places in view opens merchant details, viewport/focus city re-ranking, tested web/phone sanitization (`btc-map-review` shared vectors)
- [x] Android M8: location requests remain explicit Locate only; lazy Places in view/detail navigation from dateline-aware camera bounds; re-rank cities on viewport/focus changes; shared `btc-map-review.json` URL/phone/bounds cases (including digitless phone regressions); narrow URI-only intents retain user choice of handler

## Cutover
- [ ] Finish adaptive layouts (iPad / landscape / foldables) parity outside the existing History/BTC Map split panes (`TransactionsView.swift:15`, `TransactionDetail.kt:28`, both BTC Map screens; `2fa4e026`, `062ac351`). Home/Receive/Send/QR/settings still need RN adaptive-flow comparison and wide-window acceptance
- [ ] Complete the RN screen-by-screen behaviour audit beyond the recorded M1/M2, History, Send/M6 and recurring reviews; resolve the gaps below and verify the remaining flows on both platforms
  - [x] Home: pending balance, expandable Ark/onchain details, balance masking, and a background-job strip on both platforms. All SDK Ark buckets remain counted; onchain immature funds are shown separately. See [behaviour-parity verification](behavior-parity-2026-10-20.md)
  - [ ] Home onchain detail sub-buckets: FFI 0.5.0 exposes only `confirmedSats`, `pendingSats` (trusted + untrusted), and `totalSats`. Both apps show combined "Pending"; exact RN "Trusted Pending" / "Untrusted Pending" rows require an FFI API extension
  - [x] iOS Home/History repeat handoff consumes once when editable, clears the old route, restores amount/currency/comment, and returns to normal recipient validation and confirmation
  - [x] Send and Home explain the amount pending in a round and that it becomes available after the round confirms; available balances update together
  - History: refresh movements (`bark.round:refresh`) fall through to the "Onchain" label, the same as RN; consider a Refresh label in the design pass
  - Send/Board (FFI 0.5.0 verification, 2026-10-20):
    - [x] Receipt refresh: Android's immediate/10-second visible Send receipt refresh on both platforms; iOS observation cancels when the tab, scene, receipt, or wallet is unavailable
    - [x] Network-status copy: iOS explanations and repeat-payment warnings on both platforms; asserted against `apps/fixtures/payment-status-copy.json`
    - [x] Lookup errors: recoverable in-memory messages remain beside the receipt with a status retry, clear on success, and are excluded from persisted Send/Board evidence; exact txids and previous evidence/locks survive failures
    - [x] Boarding typed errors: one wording table for insufficient confirmed funds, minimum Ark amount, reserved inputs, unsynced wallet, too many inputs, failed broadcast, and `notAttempted`; shared fixture above
    - [x] Boarding receipts: Android's net Ark amount and total leaving next to fees on both platforms; total uses an actual onchain fee when known, otherwise the reviewed fee
    - [x] Incomplete probe cleanup: iOS's stop-before-reprepare rule on both platforms; Android checks its retained preparation handle after cleanup, independent of SDK rejection
- [ ] Publish native builds to the existing TestFlight + Play internal listings and verify release-candidate upgrades on physical iOS, GMS Android and non-GMS Android. Debug RN→native identity/seed/token upgrades already passed on iPhone 17 and Pixel 6 (`apps/ios/UpgradeTests/README.md:138`, `apps/android/UpgradeTests/DEVICE.md:70`); non-GMS distributor/device acceptance and native store-track releases are not evidenced
- [ ] Monitor heartbeats, push delivery, migration telemetry; staged rollout
- [ ] Retire `client/`, Nitro modules, ts-rs

## Backlog (after parity)
- [ ] OS deep links (`bitcoin:`, `lightning:`, `lnurl`)
- [ ] Raw APNs/FCM token registration alongside Expo
- [ ] Complete the later Noah design-system pass (`PLAN.md:34`); initial RN light/dark colors, Inter typography and shared controls already exist (`DesignSystem/Theme.swift:6`, `DesignSystem/Buttons.swift:5`, `NoahTheme.kt:24`; `91c03a26`, `b0de918c`)
- [ ] iOS keychain accessibility policy review — current new-item policy is `kSecAttrAccessibleAfterFirstUnlock`, while in-place updates preserve RN attributes (`KeychainPassword.swift:74,86`); compatibility coverage is not a completed policy review
