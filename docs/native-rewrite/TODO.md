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
- [ ] `apps/ios`: XcodeGen spec, SwiftPM deps, schemes `Noah` / `Noah-Signet` / `Noah-Regtest`, xcconfigs, bundle IDs `com.noahwallet.{mainnet,signet,regtest}`, entitlements (aps, app groups), URL schemes, Info.plist background modes
- [ ] iOS widget extension targets reused from `client/ios/SharedWidget` + per-variant extensions; App Intents reused from `client/ios/SharedAppIntents`
- [ ] `apps/android`: Gradle KTS, version catalog, flavors `mainnet`/`signet`/`regtest`, same applicationIds, signing config, `google-services.json` per flavor, minSdk 24
- [ ] Android widgets reused from `client/android/app/src/main/java/com/noahwallet/widgets`
- [ ] `apps/fixtures/` shared JSON fixtures + XCTest/JUnit loaders
- [ ] `justfile` recipes: `ios-native-*`, `android-native-*`, `native-test`
- [ ] CI: build + unit tests for both apps (all three variants compile)

### 0.3 Wallet SDK integration
- [ ] FFI gaps — implemented in a separate thread ([ffi-handoff-prompt.md](ffi-handoff-prompt.md)); track releases here
- [ ] `WalletService` protocol/interface over bark-bolt-ffi (Swift + Kotlin), with a fake for UI work
- [ ] Config mapping (Ark/Esplora/bitcoind per variant, Esplora override, **fee units: sat/kvB → sat/kwu**)
- [ ] `WalletCoordinator`: single owner of wallet lifetime; serializes load/close/replace and operations for UI, push, background tasks, backups, App Intents
- [ ] Open existing wallet: preflight `db.sqlite`, `createIfNotExists=false`, original network, `runDaemon` policy
- [ ] Notification loop (movement events) + receive subscriptions with cancellation

### 0.4 Server API contract
- [ ] Utoipa `ToSchema` on `server/src/types.rs` exports + path annotations on all handlers (bearer + registered-user security)
- [ ] Deterministic `openapi.json` export + normalization step (tagged `NotificationData`, nullable refs)
- [ ] swift-openapi-generator → `apps/ios` generated client; Fabrikt → `apps/android` generated models/client
- [ ] u64 → Int64/Long mapping + boundary fixtures; all 6 notification variants round-trip
- [ ] `just generate-api`; CI drift gate (TS + schema + native)

### 0.5 Upgrade compatibility (gate before any funded-wallet milestone)
- [ ] iOS seed reader: keychain service `com.noah.mnemonic.<variant>`, account `noah`
- [ ] Android seed reader: `RN_KEYCHAIN` DataStore (+ legacy SharedPreferences), cipher from `:c` (AES-GCM / AES-CBC); fallback `noah_native_secrets`; new Keystore-backed store; legacy kept
- [ ] BIP39 validation; conflict handling; never auto-delete seed; "restore from saved phrase" when DB missing
- [ ] Server auth token reader (`com.noah.auth-token.<variant>`) or re-login
- [ ] MMKV: open `noah-wallet-storage` (iOS `Documents/mmkv`, Android `filesDir/mmkv`); decode zustand envelopes for all 8 stores
- [ ] GT locale import (`generaltranslation.locale` / `gt_store`)
- [ ] Expo installation UUID reuse (iOS keychain `EXDeviceInstallationUUIDKey`, Android `noBackupFilesDir/expo_notifications_installation_uuid.txt`)
- [ ] UnifiedPush connector storage + instance names preserved
- [ ] Automated upgrade test (simulator + emulator): RN regtest build → create/fund wallet, set settings, recurring rule → install native over it → assert seed, identity, balance, history, settings, Expo token

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
- [ ] Byte-compatible v2 encrypt/decrypt (0x02 | salt16 | nonce12 | AES-256-GCM; PBKDF2-SHA256 600k), manifest.json + db.sqlite zip
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
