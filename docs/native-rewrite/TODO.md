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
- [ ] FFI gaps — implemented in a separate thread ([ffi-handoff-prompt.md](ffi-handoff-prompt.md)); track releases here
- [ ] `WalletService` protocol/interface over bark-bolt-ffi (Swift + Kotlin), with a fake for UI work
- [x] Config mapping per variant with **fee units: sat/kvB → sat/kwu** — iOS
- [ ] Esplora override from `esplora-storage`; Android config mapping
- [ ] `WalletCoordinator`: single owner of wallet lifetime; serializes load/close/replace and operations for UI, push, background tasks, backups, App Intents
- [x] Open existing wallet: preflight `db.sqlite`, `createIfNotExists=false`, original network — iOS (`WalletSession`)
- [ ] `runDaemon` policy + full `WalletCoordinator` (ownership for push/background/backups)
- [ ] Notification loop (movement events) + receive subscriptions with cancellation

### 0.4 Server API contract
- [x] Utoipa `ToSchema` on `server/src/types.rs` exports + path annotations on all handlers (bearer + registered-user security)
- [x] Deterministic `openapi.json` export + normalization step (tagged `NotificationData`, nullable refs)
- [x] swift-openapi-generator 1.14.2 → `apps/ios/Noah/API/Generated` (committed, `just generate-api-ios`)
- [ ] Fabrikt → `apps/android` generated models/client
- [x] All notification variants decode + round-trip from server-generated fixtures — iOS
- [ ] Same for Android
- [x] `just generate-api`; server tests fail on a stale `api/openapi.json` or `apps/fixtures/server-notification-data.json`
- [ ] CI drift gate for generated native code

### 0.5 Upgrade compatibility (gate before any funded-wallet milestone)
- [x] iOS seed reader: keychain service `com.noah.mnemonic.<variant>`, account `noah` (read + in-place save, RN attributes)
- [ ] Android seed reader: `RN_KEYCHAIN` DataStore (+ legacy SharedPreferences), cipher from `:c` (AES-GCM / AES-CBC); fallback `noah_native_secrets`; new Keystore-backed store; legacy kept
- [x] Launch decision (`apps/fixtures/launch-state.json`): never auto-delete seed; seed-without-DB and DB-without-seed states — iOS
- [ ] Launch decision — Android
- [ ] BIP39 validation of the stored seed; conflict handling
- [x] Server auth token reader (`com.noah.auth-token.<variant>`) — iOS
- [x] MMKV: open `noah-wallet-storage` + zustand envelope codec, `wallet-storage` decoded — iOS
- [ ] MMKV — Android
- [ ] Remaining 7 store shapes (decode as their screens land)
- [x] Verify against a real RN-written MMKV file (in the upgrade test) — iOS
- [x] GT locale reader (`generaltranslation.locale`) — iOS
- [ ] GT locale reader (`gt_store`) — Android
- [x] Expo installation UUID reuse — iOS (keychain `EXDeviceInstallationUUIDKey`)
- [ ] Expo installation UUID reuse — Android (`noBackupFilesDir/expo_notifications_installation_uuid.txt`)
- [ ] UnifiedPush connector storage + instance names preserved
- [x] Upgrade test iOS (`apps/ios/UpgradeTests/run.sh`): RN regtest creates wallet via Maestro → native installed over it → seed, DB, MMKV readable, launch = existingWallet, RN DB opens with new bark (same file, not recreated)
- [x] Funded upgrade test iOS (`FUNDED=1`): RN receives 100k + sends 5k → native sees same fingerprint, 2 movements, 95,000 spendable
- [ ] Extend funded upgrade test: onchain funds, recurring rule, settings values, Expo token
- [ ] Upgrade test Android (emulator)
- [ ] Upgrade test also checks launcher shortcut + placed widget survive (Android) and widget kind/bundle ID (iOS)

### 0.6 Platform services
- [ ] Redacting logger (file-backed, rotating, exportable) — iOS OSLog + file, Android Log + file
- [ ] Sentry (cocoa / android), disabled in debug + regtest like RN
- [ ] HTTP clients (URLSession / OkHttp) incl. upload/download
- [ ] Server auth: `/v0/getk1` → sign (offline key `m/350/0`) → `/v0/auth/login` → JWT; refresh near expiry; retry once
- [ ] Server registration + last-login + device info
- [ ] Push: iOS APNs + Expo token; Android FirebaseMessagingService + Expo token; all 6 `NotificationData` types; job status + heartbeat reporting
- [ ] UnifiedPush (Android, non-GMS) in pure Kotlin
- [ ] Background tasks: BGTaskScheduler / WorkManager (maintenance, recurring)
- [ ] Widget data writers (same keys)

### 0.7 Backups
- [x] Envelope crypto v1/v2 (salt16 | nonce12 | AES-256-GCM; PBKDF2-SHA256 600k) with Node-generated vectors (`apps/fixtures/backup-crypto.json`) — iOS
- [ ] Envelope crypto — Android (same vectors)
- [ ] v2 archive: manifest.json + db.sqlite zip
- [ ] v1 legacy restore
- [ ] Snapshot via FFI; install/rollback sequence; atomic writes; temp cleanup
- [ ] Backup coordinator: single in-flight, 5 s debounce, generation counter, backoff 15 s → 15 min with jitter, suspension gates
- [ ] Cross-platform fixtures (iOS ↔ Android ↔ RN-created backups); `scripts/decrypt_backup.js` supports v2

### 0.8 Translations
- [ ] Tool: GT catalogs → `.xcstrings` + `strings.xml` for en/es/ja/ko/sv (placeholders, plurals, rich text flattened)
- [ ] Background notification strings honour the persisted locale

## M1 — Shell, onboarding, Home
- [ ] Launch gating: wallet check, onboarding vs main, push-permission gate, battery gate (Android), biometric lock overlay, beta warning
- [ ] Tab shell: Home, Receive, Send, History (Liquid Glass tab bar on iOS 26)
- [ ] OnboardingScreen (language picker, create/restore)
- [ ] BetaWarningScreen (accept before create, no double create)
- [ ] MnemonicScreen (biometric re-auth outside onboarding, copy, never log)
- [ ] RestoreWalletScreen (backup restore with staged progress)
- [ ] EmailVerificationScreen (send/resend, 6-digit OTP, skip, reconcile verified)
- [ ] LightningAddressScreen (username/domain, optional npub, skip)
- [ ] PushNotificationsRequiredScreen
- [ ] BatteryOptimizationScreen (Android)
- [ ] UnifiedPushScreen (Android, non-GMS)
- [ ] HomeScreen (balances, fiat, hide balance, recent 3, shortcuts, banners, facts)
- [ ] Banners: backup status, auto-boarding, pending round, email verification, update warning
- [ ] App version check

## M2 — Receive
- [ ] ReceiveScreen (BIP-321 unified QR, amount/description sheet, copy sheet, share, completion subscriptions)
- [ ] ReceiveSuccessScreen + animation
- [ ] QRHubScreen (my lightning-address code / scan)

## M3 — Send
- [ ] Input parsing (BIP21/BIP321, bolt11, bolt12, lightning address, Ark address, onchain) — fixtures from `client/tests/unit/{sendUtils,sendFlow,onchainSend,lightningInvoice,fiatCurrency}`
- [ ] SendScreen (amount-first + recipient-first, MAX, method/source choice, fee review, notes, suggestions)
- [ ] AmountKeypad (custom keypad, never with system keyboard)
- [ ] QR scanner (AVFoundation / CameraX + ZXing)
- [ ] SendConfirmation + SendSuccess sheets
- [ ] Repeat payment metadata

## M4 — History
- [ ] TransactionsScreen (filters, pull refresh, CSV export of filtered list)
- [ ] TransactionDetail (sheet / split pane, historical fiat, technical details, explorer, repeat) — fixtures from `transactionHistory`, `barkMovement`

## M5 — Settings & recovery
- [ ] SettingsScreen (biometrics, auto-board, mailbox, suspend/resume, delete wallet, re-register, version/debug unlock)
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
