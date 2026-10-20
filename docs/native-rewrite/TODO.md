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
- [ ] CI: build + unit tests for both apps (all three variants compile)

### 0.3 Wallet SDK integration
- [x] FFI gaps P0+P1 shipped in bark-bolt-ffi v0.2.0
- [x] FFI items 11 + 12 in v0.3.0: `OnchainWallet.openExisting` (read-only identity check before writes) and all nine balance buckets + Bark's pending/total — iOS pinned to 0.3.0 (Noah's own preflight kept)
- [x] Android on bark-bolt-ffi 0.3.0 (`openExisting` for existing wallets, full balance; wrappers already closed on teardown)
- [ ] Android session lifecycle parity with iOS (reservations, calls shielded from cancellation, observers), with the Android restore port
- [x] Funded RN→native upgrade re-run on 0.3.0 — iOS (fingerprint, 2 movements, 95,000 spendable; opened via `openExisting`)
- [x] Funded RN→native upgrade on 0.3.0 — Android, real Pixel 6 (Android 17): fingerprint, 2 movements, 95,000 spendable; Expo UUID and token unchanged (see `apps/android/UpgradeTests/DEVICE.md`)
- [x] Pixel 6: background-job callbacks, killed-process delivery and widget refresh — real Expo heartbeat `responded` / maintenance `Success` in both states; placed widget refreshed 95,000 → 96,234 sats after a push (2026-10-19 follow-up in `apps/android/UpgradeTests/DEVICE.md`)
- [x] bark-bolt-ffi Android libraries 16 KB-aligned (FFI handoff item 13) — both platforms on 0.3.1; arm64-v8a, armeabi-v7a and x86_64 `libbark_bolt_ffi.so` LOAD segments all `Align 0x4000` (checked with llvm-readelf on the published AAR)
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
- [ ] CI drift gate for generated native code

### 0.5 Upgrade compatibility (gate before any funded-wallet milestone)
- [x] iOS seed reader: keychain service `com.noah.mnemonic.<variant>`, account `noah` (read + in-place save, RN attributes)
- [x] Android seed reader: `RN_KEYCHAIN` DataStore (+ legacy SharedPreferences), cipher from `:c` (AES-GCM / AES-CBC); fallback `noah_native_secrets`; new Keystore-backed store; legacy kept
- [x] Android RN→native upgrade test run on an emulator
- [x] Launch decision (`apps/fixtures/launch-state.json`): never auto-delete seed; seed-without-DB and DB-without-seed states — iOS
- [x] Launch decision — Android
- [ ] BIP39 validation of the stored seed; conflict handling
- [x] Server auth token reader (`com.noah.auth-token.<variant>`) — iOS
- [x] Server auth token reader/writer (RN_KEYCHAIN, existing GCM/CBC alias; excluded from backup) — Android
- [x] MMKV: open `noah-wallet-storage` + zustand envelope codec, `wallet-storage` decoded — iOS
- [x] MMKV — Android
- [ ] Remaining 7 store shapes (decode as their screens land)
- [x] Verify against a real RN-written MMKV file (in the upgrade test) — iOS
- [x] GT locale reader (`generaltranslation.locale`) — iOS
- [x] GT locale reader (`gt_store`) — Android
- [x] Expo installation UUID reuse — iOS (keychain `EXDeviceInstallationUUIDKey`)
- [x] Expo installation UUID reuse — Android (`noBackupFilesDir/expo_notifications_installation_uuid.txt`)
- [x] UnifiedPush connector storage + instance names preserved — Android (3.1.2 SQLite + legacy prefs fixture on device)
- [x] Upgrade test iOS (`apps/ios/UpgradeTests/run.sh`): RN regtest creates wallet via Maestro → native installed over it → seed, DB, MMKV readable, launch = existingWallet, RN DB opens with new bark (same file, not recreated)
- [x] Funded upgrade test iOS (`FUNDED=1`): RN receives 100k + sends 5k → native sees same fingerprint, 2 movements, 95,000 spendable
- [ ] Extend funded upgrade test: onchain funds, recurring rule, settings values, Expo token
- [x] Upgrade test Android (emulator), funded: same fingerprint, 2 movements, 95,000 spendable (RN install had no Expo ID, so ID continuity is still unverified)
- [ ] Upgrade test also checks launcher shortcut + placed widget survive (Android) and widget kind/bundle ID (iOS)

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
- [ ] Restore UI — Android
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
- [ ] Restore onchain MAX only with prepared sends / coin control (FFI 14); expose broadcast/chain status for offboards (FFI 15); replace weak history correlation with durable operation IDs (FFI 16)
- [ ] External LNURL/BOLT12 provider acceptance and camera hardware verification — iOS

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

## M6 — Onchain & exits
- [x] BoardArkScreen + confirmation-only auto-boarding service, persisted toggle/one-shot prompt, estimated 5,000-sat reserve — iOS; RN `boardAll` MAX, estimated fees/amounts, actual onchain fee on receipt (prepared boarding awaits FFI 18, same priority as 14)
- [x] VTXOsScreen + VTXODetailScreen + delegated refresh review dialog — iOS
- [x] Emergency-exit read-only overview/estimates, quote < 30 s, 330-sat eligibility, deposit sheet, claim/cancel reviews — iOS
- [x] ExitVtxoDetailScreen + terminal canceled timeline; 187 RN-derived fee/cancellation/timeline/coordination/boarding fixtures — iOS
- [x] Starting/progressing exits — iOS RN parity: explicit start review explains automatic background broadcasts/fee bumps and onchain fees; manual Progress Exits review, cancel until final broadcast, claim reviews; optional manual-daemon policy is P2 FFI 17
- [x] Suspended wallet gates — iOS Settings M6 rows remain navigable like RN; M6 screen actions/queries, Home refresh, boarding minimum/address queries and Auto-Board perform no wallet operations
- [ ] Durable operation reconciliation after an ambiguous M6 submission — FFI 19; iOS preserves the attempt and blocks resubmission
- [x] Live exit start → automatic daemon broadcast/CPFP → 144-block timelock → reviewed manual progress → claim/confirmation — iOS; manual boarding/MAX actual fees and suspended UI verified (see `apps/ios/ExitTests/README.md`)
- [ ] Live exit cancellation before final broadcast — iOS (fixture coverage passes; daemon progressed the acceptance exit before cancellation)
- [ ] BoardArkScreen, auto-boarding, VTXOs/details/refresh, all exit screens — Android

## M7 — Recurring payments
- [ ] Schedule math (local calendar, DST, month-end) — fixtures from recurring tests
- [ ] Executor (latest due only, before-send markers, no ambiguous retry, re-check pause/cancel)
- [ ] RecurringPaymentsScreen + RecurringPaymentEditorScreen
- [ ] Server wake sync

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
- [ ] TestFlight + Play internal track on existing listings; upgrade test on real devices (iOS, GMS Android, non-GMS Android)
- [ ] Monitor heartbeats, push delivery, migration telemetry; staged rollout
- [ ] Retire `client/`, Nitro modules, ts-rs

## Backlog (after parity)
- [ ] OS deep links (`bitcoin:`, `lightning:`, `lnurl`)
- [ ] Raw APNs/FCM token registration alongside Expo
- [ ] Noah design system setup
- [ ] iOS keychain accessibility policy review
