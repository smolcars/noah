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
- [ ] bark-bolt-ffi Android libraries 16 KB-aligned (FFI handoff item 13; release blocker)
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
- [ ] v2 archive — Android
- [x] v1 legacy restore (variant folder only, path checks, entry/size caps) — iOS
- [x] Backup upload: FFI snapshot → seal → initiate → S3 PUT → complete, unchanged snapshot skipped, export file — iOS (live-tested against S3 with the dev `.env` server)
- [x] Restore: list (temporary token) → download (size + SHA-256 vs server record) → open → validate → journaled swap (`RestoreTransaction`) under an exclusive session hold → open check without daemon → seed save as commit point; v1 legacy fallback through the same transaction; launch settles an interrupted restore before reading launch state — iOS (live round trip + crash-boundary, race and Keychain-failure tests)
- [x] Unpack limits: streaming 1 GiB cap (headers can lie), CRC-32 verified per entry — iOS
- [ ] Restore — Android (same journal semantics)
- [x] Restore UI (RestoreWalletScreen); seed `backup-storage` after a restore (`BackupCoordinator.seedRestored`) — iOS
- [x] Backup coordinator: single in-flight, 5 s debounce, generation counter, backoff 15 s → 15 min with jitter, suspension gates; `backup-storage` with RN's actions; triggers from wallet state changes (session-owned), foreground and push — iOS
- [ ] Backup coordinator — Android
- [x] Independently built v2 + v1 backup files (Node crypto + Info-ZIP, `backup-archives.json`) open on iOS
- [ ] Same fixtures on Android; iOS ↔ Android round trip; `scripts/decrypt_backup.js` supports v2

### 0.8 Translations
- [ ] Tool: GT catalogs → `.xcstrings` + `strings.xml` for en/es/ja/ko/sv (placeholders, plurals, rich text flattened)
- [ ] Background notification strings honour the persisted locale

## M1 — Shell, onboarding, Home
- [ ] Launch gating: wallet check, onboarding vs main, push-permission gate, battery gate (Android), biometric lock overlay, beta warning
- [ ] Tab shell: Home, Receive, Send, History (Liquid Glass tab bar on iOS 26)
- [x] OnboardingScreen create/restore — iOS (language picker waits for 0.8 translations)
- [x] Create-or-resume logic (`WalletCreator`, RN `walletCreation` test cases ported) — iOS
- [x] `deriveStoreNextKeypair` + key-0 public key (`staticVtxoPubkey`) on creation, as RN does — iOS
- [x] BetaWarningScreen (accept before create, no double create) — iOS
- [x] MnemonicScreen in onboarding (numbered grid, local-only expiring copy) — iOS
- [ ] MnemonicScreen from Settings with biometric re-auth
- [x] RestoreWalletScreen (backup restore with staged progress) — iOS
- [x] EmailVerificationScreen (send/resend, 6-digit OTP, skip, reconcile verified) — iOS
- [x] LightningAddressScreen (username/domain, optional npub, skip) — iOS
- [ ] PushNotificationsRequiredScreen
- [ ] BatteryOptimizationScreen (Android)
- [ ] UnifiedPushScreen (Android, non-GMS)
- [x] HomeScreen first pass: balance (BIP-177/sats from `profile-storage`), hide toggle (`privacy-storage`), pull-to-sync, variant badge — iOS
- [ ] HomeScreen: fiat, recent 3 transactions, shortcuts, banners, facts
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
- [x] Live Ark, BOLT11, Ark-to-onchain, onchain MAX, forced FFI failure/relaunch verification — iOS; evidence and limits in `apps/ios/SendTests/README.md`
- [ ] External LNURL/BOLT12 provider acceptance and camera hardware verification — iOS

## M4 — History
- [ ] TransactionsScreen (filters, pull refresh, CSV export of filtered list)
- [ ] TransactionDetail (sheet / split pane, historical fiat, technical details, explorer, repeat) — fixtures from `transactionHistory`, `barkMovement`

## M5 — Settings & recovery
- [ ] SettingsScreen (biometrics, auto-board, mailbox, suspend/resume, delete wallet, re-register, version/debug unlock)
- [x] Suspend/resume, delete wallet (files before seed, under a reservation; refused while a restore is pending), re-register: model layer + suspended Home — iOS (Settings rows wire in with the Settings screen)
- [ ] ProfileScreen
- [ ] LanguageSettingsScreen, CurrencySettingsScreen, BitcoinUnitSettingsScreen
- [ ] EsploraSettingsScreen (coordinated wallet reload)
- [ ] BackupSettingsScreen
- [ ] ArkInfoScreen
- [ ] ExportDatabaseScreen (encrypted `.noahbackup`)
- [ ] LogScreen
- [ ] FeedbackScreen (screenshot ≤5 MiB, device info)
- [ ] NoahStoryScreen (bundled audio)
- [ ] DebugScreen

## M6 — Onchain & exits
- [ ] BoardArkScreen (+ auto-boarding service, 5,000-sat reserve)
- [ ] VTXOsScreen + VTXODetailScreen + refresh dialog
- [ ] UnilateralExitScreen (estimate never broadcasts, quote < 30 s, 330-sat threshold) + ExitDepositBottomSheet
- [ ] ExitVtxoDetailScreen — fixtures from `exitFeeEstimate`, `exitTimeline`, `exitOverviewCoordination`

## M7 — Recurring payments
- [ ] Schedule math (local calendar, DST, month-end) — fixtures from recurring tests
- [ ] Executor (latest due only, before-send markers, no ambiguous retry, re-check pause/cancel)
- [ ] RecurringPaymentsScreen + RecurringPaymentEditorScreen
- [ ] Server wake sync

## M8 — BTC Map
- [ ] MapLibre native map, themed styles, clustering
- [ ] Bundled snapshot + BTC Map v4 sync, GeoNames city search
- [ ] Merchant detail sheet / pane, location

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
