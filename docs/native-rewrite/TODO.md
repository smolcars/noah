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
- [ ] iOS App Intents ported from `client/ios/SharedAppIntents` onto `WalletService` (needs 0.3)
- [ ] iOS `processing` background mode + `BGTaskSchedulerPermittedIdentifiers` (with 0.6 background tasks)
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
- [ ] Android session lifecycle parity with iOS (reservations, calls shielded from cancellation, observers), with the Android restore port
- [x] Funded RN→native upgrade re-run on 0.3.0 — iOS (fingerprint, 2 movements, 95,000 spendable; opened via `openExisting`)
- [x] Funded RN→native upgrade on 0.3.0 — Android, real Pixel 6 (Android 17): fingerprint, 2 movements, 95,000 spendable; Expo UUID and token unchanged (see `apps/android/UpgradeTests/DEVICE.md`)
- [x] Pixel 6: background-job callbacks, killed-process delivery and widget refresh — real Expo heartbeat `responded` / maintenance `Success` in both states; placed widget refreshed 95,000 → 96,234 sats after a push (2026-10-19 follow-up in `apps/android/UpgradeTests/DEVICE.md`)
- [x] bark-bolt-ffi Android libraries 16 KB-aligned (FFI handoff item 13) — fixed in 0.3.1; both platforms now on 0.5.0 and Android reverified there; arm64-v8a, armeabi-v7a and x86_64 `libbark_bolt_ffi.so` LOAD segments all `Align 0x4000` (checked with llvm-readelf on the published AAR)
- [ ] `WalletService` protocol/interface over bark-bolt-ffi (Swift + Kotlin), with a fake for UI work
- [x] Config mapping per variant with **fee units: sat/kvB → sat/kwu** — iOS
- [x] Config mapping — Android (sat/kwu; regtest via `10.0.2.2`)
- [ ] Esplora override from `esplora-storage`
- [ ] `WalletCoordinator`: single owner of wallet lifetime; serializes load/close/replace and operations for UI, push, background tasks, backups, App Intents
- [x] Open existing wallet: preflight `db.sqlite`, `createIfNotExists=false`, original network — iOS (`WalletSession`)
- [x] One `WalletSession.shared` per process (bark locks the datadir); `ensureOpen` joins an open in progress — iOS
- [x] Shared session, preflight open, safe create-or-resume, bark balance accounting — Android (overlapping closes rejected, native handles disposed explicitly)
- [ ] `runDaemon` policy + coordination rules for push/background/backups on the shared session
- [ ] Notification loop (movement events) + receive subscriptions with cancellation

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
- [x] iOS seed reader: keychain service `com.noah.mnemonic.<variant>`, account `noah` (read + in-place save, RN attributes)
- [x] Android seed reader: `RN_KEYCHAIN` DataStore (+ legacy SharedPreferences), cipher from `:c` (AES-GCM / AES-CBC); fallback `noah_native_secrets`; new Keystore-backed store; legacy kept
- [x] Android RN→native upgrade test run on an emulator
- [x] Launch decision (`apps/fixtures/launch-state.json`): never auto-delete seed; seed-without-DB and DB-without-seed states — iOS
- [x] Launch decision — Android
- [ ] BIP39 validation of the stored seed; conflict handling
  - [x] iOS: validate 12/24-word BIP39 before create/open/restore; block corrupt/ambiguous seeds and fingerprint conflicts without overwriting data; fixed support guidance, shared safety fixture and real SDK no-write tests
- [x] Server auth token reader (`com.noah.auth-token.<variant>`) — iOS
- [x] Server auth token reader/writer (RN_KEYCHAIN, existing GCM/CBC alias; excluded from backup) — Android
- [x] MMKV: open `noah-wallet-storage` + zustand envelope codec, `wallet-storage` decoded — iOS
- [x] MMKV — Android
- [ ] Remaining RN store shapes (decode as their screens land)
  - [x] iOS: inventory all 8 stores; validate consumed envelopes/versions, preserve unknown JSON tokens, apply transaction v0 migration; RN-generated fixtures; Esplora decoder remains inactive until its native screen lands — `apps/ios/UpgradeTests/README.md`
- [x] Verify against a real RN-written MMKV file (in the upgrade test) — iOS
- [x] GT locale reader (`generaltranslation.locale`) — iOS
- [x] GT locale reader (`gt_store`) — Android
- [x] Expo installation UUID reuse — iOS (keychain `EXDeviceInstallationUUIDKey`)
- [x] Expo installation UUID reuse — Android (`noBackupFilesDir/expo_notifications_installation_uuid.txt`)
- [x] UnifiedPush connector storage + instance names preserved — Android (3.1.2 SQLite + legacy prefs fixture on device)
- [x] Upgrade test iOS (`apps/ios/UpgradeTests/run.sh`): RN regtest creates wallet via Maestro → native installed over it → seed, DB, MMKV readable, launch = existingWallet, RN DB opens with new bark (same file, not recreated)
- [x] Funded upgrade test iOS (`FUNDED=1`): RN receives 100k + sends 5k → native sees same fingerprint, 2 movements, 95,000 spendable
- [ ] Extend funded upgrade test: onchain funds, recurring rule, settings values, Expo token
  - [x] iOS: confirmed 10k onchain + 95k Ark, identical movements/fingerprint/recurring policy, native recurring UI with zero payments, non-default settings and locale — `apps/ios/UpgradeTests/README.md`
  - [ ] iOS: actual RN-registered Expo UUID/token continuity requires a physical iPhone; simulator hardware guards prevent RN registration (storage-contract tests and opt-in continuity test exist)
- [x] Upgrade test Android (emulator), funded: same fingerprint, 2 movements, 95,000 spendable (RN install had no Expo ID, so ID continuity is still unverified)
- [ ] Upgrade test also checks launcher shortcut + placed widget survive (Android) and widget kind/bundle ID (iOS)
  - [x] iOS: all three widget identities/app groups match RN; built regtest extension checked; two actual RN-placed SpringBoard widget instance IDs survive native installation

### 0.6 Platform services
- [x] Redacting logger (file-backed, rotating, exportable) — iOS (`AppLog`, same `Caches/noah_logs` layout as RN)
- [x] Redacting logger — Android
- [ ] Route bark-bolt-ffi `BarkLogger` callbacks into the app log
- [ ] Sentry (cocoa / android), disabled in debug + regtest like RN
- [ ] HTTP clients (URLSession / OkHttp) incl. upload/download
- [x] Server auth: `/v0/getk1` → sign (offline key `m/350/0`) → `/v0/auth/login` → JWT; refresh near expiry; retry once — iOS (live-tested on regtest)
- [x] Server auth — Android (offline index-0 signing, shared login, RN token storage, retry once; live-tested on regtest)
- [x] Server registration + last-login + device info (shared token provider/client) — iOS
- [x] Server registration + last-login + device info, app-version check, email and Lightning identity services — Android
- [x] Push iOS: permission, APNs token → Expo token (RN installation ID, provisioning aps-environment), `register_push_token`, silent-push handler with 25 s budget, jobs for maintenance / invoice / claim / backup / heartbeat with job-status reports, widget refresh
- [x] Push iOS: register the push token after server registration once onboarding is done (RN `usePushNotifications` gating)
- [ ] Push iOS: recurring_payment_due (M7); verify real delivery on a device (simctl push does not run the background handler)
- [x] FirebaseMessagingService + Expo token, onboarding/permission/server gates, expedited push work, maintenance/invoice/claim + status/heartbeat — Android (real Expo heartbeat + maintenance delivered; backup/recurring log-only pending their ports)
- [x] UnifiedPush (Android, non-GMS) in pure Kotlin — Android (shared jobs, preserved endpoint/connector storage; live distributor test pending)
- [ ] Background tasks: BGTaskScheduler / WorkManager (maintenance, recurring)
- [x] Widget data writer — iOS (`widgetBalance`, same keys, expiry from VTXOs and onchain tip)
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
- [x] One launch recovery at a time for UI start and headless jobs, settled on every wallet open (also clears pre-journal plaintext, even while suspended); exports sealed only into app-owned swept folders — iOS + Android
- [x] Backup upload and unchanged-snapshot skip; live S3 round trip at port 3100 — Android
- [x] Shared Node/Info-ZIP v1/v2 archives and a Node-made real wallet snapshot restored/reopened — Android
- [x] Independently built v2 + v1 backup files (Node crypto + Info-ZIP, `backup-archives.json`) open on iOS
- [ ] Direct iOS ↔ Android round trip; `scripts/decrypt_backup.js` supports v2 (shared fixtures and Node → Android verified)

### 0.8 Translations
- [ ] Tool: GT catalogs → `.xcstrings` + `strings.xml` for en/es/ja/ko/sv (placeholders, plurals, rich text flattened)
- [ ] Background notification strings honour the persisted locale

## M1 — Shell, onboarding, Home
- [ ] Launch gating: wallet check, onboarding vs main, push-permission gate, battery gate (Android), biometric lock overlay, beta warning
- [x] Biometric lock overlay on launch and foreground (device-passcode fallback) — iOS
- [x] Biometric app lock on launch and foreground, device-credential fallback; Settings seed re-auth and FLAG_SECURE — Android
- [ ] Tab shell: Home, Receive, Send, History (Liquid Glass tab bar on iOS 26)
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
- [ ] PushNotificationsRequiredScreen
- [ ] BatteryOptimizationScreen (Android)
- [ ] UnifiedPushScreen (Android, non-GMS)
- [x] HomeScreen first pass: balance (BIP-177/sats from `profile-storage`), hide toggle (`privacy-storage`), pull-to-sync, variant badge — iOS
- [ ] HomeScreen: fiat, recent 3 transactions, shortcuts, banners, facts
- [x] HomeScreen: recent 3 transactions, View all, transaction detail — iOS
- [ ] Banners: backup status, auto-boarding, pending round, email verification, update warning
- [ ] App version check

- [x] Launch gating (including unfinished onboarding), Navigation 3 tabs, create/beta/secure mnemonic, emergency email and Lightning identity — Android
- [x] Derive key 0 before fresh-wallet registration and persist `staticVtxoPubkey` — Android
- [x] Home first pass: full balance, persisted unit/privacy, pull-to-sync, variant and email banner; Inter/light/dark theme — Android
- [x] Notification permission and existing push registration after onboarding — Android

## M2 — Receive
- [x] ReceiveScreen (BIP-321 unified QR, amount/description sheet, copy sheet, share, completion subscriptions) — iOS
- [x] ReceiveSuccessScreen + animation — iOS
- [ ] QRHubScreen (my lightning-address code / scan)

- [x] ReceiveScreen: BIP-321 QR with Noah logo, amount/note/fiat sheet, copy/share, Ark and Lightning completion — Android
- [x] ReceiveSuccessScreen with animation, settled status and refreshed balance — Android
- [x] Shared receive-request (9 cases) and fiat-currency (48 cases) fixtures in JUnit; session observer cancellation/close regressions — Android

## M3 — Send
- [x] Android Send round-1 review fixes: iOS rounds 7–8 audited destructive override for unresolved receipts; durable original/legacy evidence and corrupt journal/audit quarantine; retained, coalesced, bounded session-owned discard retries and failed-close ownership; last-known receipts after refresh errors; version-2 prepared input outpoints and confirmed competing-spend detection. Bounded Android/iOS Send amount parsing shares 90 adversarial vectors. Full suites once: Android 1,113 passed / 0 failed or skipped plus regtest APK; iOS 378 total / 362 passed / 16 opt-in skipped / 0 failed. Dedicated simulator/build cleanup complete. See `apps/android/SendTests/README.md`.
- [x] Input parsing (BIP21/BIP321, bolt11, bolt12, lightning address/LNURL, Ark address, onchain) — iOS; RN-generated `send-utils`, `send-flow`, `onchain-send`, `lightning-invoice` fixtures; shared `fiat-currency` reused
- [x] SendScreen (amount-first + recipient-first, MAX, method/source choice, fee review, notes, suggestions) — iOS
- [x] AmountKeypad (custom keypad, never with system keyboard) — iOS
- [x] QR scanner (AVFoundation; RN camera permission wording preserved) — iOS
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
- [ ] Explicit offboard broadcast outcomes/prepared offboards (remaining FFI 15); replace weak Ark/offboard history correlation with durable operation IDs (FFI 16)
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
- [ ] EsploraSettingsScreen (coordinated wallet reload)
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
- [ ] Physical recurring-wake delivery, timed local reminder delivery, external LNURL/BOLT12 provider live acceptance and funded RN-recurring upgrade — iOS
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
- [ ] Adaptive layouts (iPad / landscape / foldables) parity
- [ ] Parity audit against RN, screen by screen
  - Send: while a refresh round awaits confirmation, Send shows "0 sats available in Ark" without saying why (both apps; Home already shows pending-in-round funds)
  - History: refresh movements (`bark.round:refresh`) fall through to the "Onchain" label, the same as RN; consider a Refresh label in the design pass
  - Send/Board (FFI 0.5.0 verification, 2026-10-20) — align, both safe today:
    - Receipt refresh: Android refreshes visible Send receipts every 10 s and after submission; iOS only on load or on request.
    - Network-status copy: iOS adds explanations and repeat-payment warnings; Android uses short labels.
    - Lookup errors: iOS saves Send lookup errors with the receipt, while Android shows a transient message. M6 does the opposite.
    - Boarding typed-error copy differs: insufficient funds, minimum amount, reserved inputs, failed broadcast and `notAttempted`.
    - Boarding receipts: Android shows net Ark amount and total leaving next to the fees; iOS shows only the fee rows.
    - Incomplete probe cleanup: iOS stops before re-preparing; Android re-prepares and relies on SDK reservations to reject it.
- [ ] TestFlight + Play internal track on existing listings; upgrade test on real devices (iOS, GMS Android, non-GMS Android)
- [ ] Monitor heartbeats, push delivery, migration telemetry; staged rollout
- [ ] Retire `client/`, Nitro modules, ts-rs

## Backlog (after parity)
- [ ] OS deep links (`bitcoin:`, `lightning:`, `lnurl`)
- [ ] Raw APNs/FCM token registration alongside Expo
- [ ] Noah design system setup
- [ ] iOS keychain accessibility policy review
