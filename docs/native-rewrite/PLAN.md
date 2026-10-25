# Noah native rewrite — plan (v1, 2026-10-19)

RN behaviour inventory: [research/inventory.md](research/inventory.md). Working checklist: [TODO.md](TODO.md) (the milestone list below is the summary; TODO.md is authoritative).

## Non-negotiables (upgrade safety)

The native apps ship as an in-place update of the existing apps. On first launch they must find, without any user action:

| Asset | Where it lives today | Native app does |
|---|---|---|
| Seed (iOS) | Keychain generic password, service `com.noah.mnemonic.<variant>`, account `noah`, AfterFirstUnlock, default access group | Read the same item in place. Never rewrite/delete in early releases. |
| Seed (Android) | RN Keychain v10: DataStore `RN_KEYCHAIN` (`<svc>:u/:p/:c`), Keystore alias = service, AES-GCM *or* AES-CBC (dispatch on `:c`); fallback `noah_native_secrets` EncryptedSharedPreferences (only de-Googled users) | Read legacy → write to new Keystore-backed store → keep legacy. |
| Wallet DB | `Documents/noah-data-<variant>/db.sqlite` (iOS), `filesDir/noah-data-<variant>/db.sqlite` (Android); bark schema 42 in both old and new bark | Open same datadir with `createIfNotExists=false`, preflight that db.sqlite exists. |
| Settings/state (MMKV) | instance `noah-wallet-storage`, iOS `Documents/mmkv`, Android `filesDir/mmkv`, single-process, unencrypted, MMKV core 2.4.x; zustand JSON envelopes `{state, version}` | Open the same instance with native MMKV (Tencent MMKV ≥2.4.1, SPM / Maven). Keep keys and envelopes. |
| Expo push identity | iOS Keychain svc=bundleId acct `EXDeviceInstallationUUIDKey`; Android `noBackupFilesDir/expo_notifications_installation_uuid.txt` | Reuse UUID, call `exp.host/--/api/v2/push/getExpoPushToken` → same Expo token. |
| UnifiedPush | connector 3.1.2 SQLite `unifiedpush-connector` + `noah_unified_push` prefs, instances `noah-<variant>` | Same connector version line, same instance names. |
| Widgets | app-group UserDefaults `widgetBalance`; Android per-variant SharedPreferences | Keep widget code, replace only the writers. |
| Android components | launcher `com.noahwallet.MainActivity`; widget receivers `com.noahwallet.widgets.NoahWidget{Mainnet,Signet,Regtest}Provider` | Keep the exact class names (or an activity-alias), or launchers drop home-screen icons and placed widgets break. |
| Language | GT: iOS defaults `generaltranslation.locale`, Android `gt_store` | One-time read → native locale setting. |

Hard rules:
- Never auto-delete the seed. (RN deletes it when the datadir looks empty — `Navigators.tsx:543`. Do NOT port that; show "restore from saved phrase" instead.)
- Never auto-create a new wallet when a seed or DB exists.
- Fee units changed: old `fallback_fee_rate` sat/kvB → new `Config.fallbackFeeRate` sat/kwu (10000 → 2500). Exit fee rates sat/kvB → sat/vB. Onchain fee rates now sat/kwu (÷250 for sat/vB).
- Cancelling a bark await does not cancel the payment; resync before retry.

## Decisions (agreed 2026-10-19)

1. iOS minimum 18. Android minSdk 24.
2. No new shared-logic runtime: app logic is written twice (Swift + Kotlin). Parity is enforced with shared JSON fixtures in `apps/fixtures/` (inputs → expected outputs) consumed by XCTest and JUnit. No Swift-on-Android, no KMP, no extra Rust crate.
3. Missing bark-bolt-ffi APIs are implemented in a separate thread (handoff prompt: [ffi-handoff-prompt.md](ffi-handoff-prompt.md)). Native work codes against a thin `WalletService` protocol/interface so features can progress before the FFI release lands.
4. Every RN screen is migrated; iOS and Android ship together.
5. Raw APNs/FCM token registration: later (no test access yet). Keep Expo only for now.
6. Noah design system ("impeccable", the Noah way): later, no rush.
7. OS deep links (`bitcoin:`, `lightning:`, `lnurl`): nice to have, after parity.

## Architecture

- Repo: same monorepo. `apps/ios` (XcodeGen + SPM), `apps/android` (Gradle KTS + version catalog). `client/` keeps running until cutover.
- iOS: SwiftUI, iOS 18 min (proposed), Swift 6 mode, @Observable, NavigationStack per tab. Liquid Glass on 26 via a `GlassCompat` layer; material fallbacks on 18–25 and Reduce Transparency.
- Keyboard: one enforced SwiftUI pattern (scrollable forms, enum @FocusState, keyboard toolbar, safeAreaInset CTAs, sheets expand before focus, custom keypad never with system keyboard). IQKeyboardManager only if the pattern fails on device.
- Android: Compose, Material 3 1.4 stable (+ expressive styling, no beta components), Navigation 3, minSdk 24, OkHttp + kotlinx.serialization, CameraX + ZXing (no Play Services requirement).
- Wallet: bark-bolt-ffi (SPM XCFramework / AAR). One `WalletCoordinator` (Swift actor / Kotlin Mutex service) owns wallet lifetime and serializes all wallet ops for UI, push, background tasks, backups, App Intents.
- Shared logic: written twice, enforced by shared JSON fixtures (see decision 2).
- Server contract: Utoipa → `api/openapi.json` (+ normalization step) → swift-openapi-generator + Fabrikt (kotlinx). ts-rs stays for RN. CI drift gate. u64 → Int64/Long.
- Push: keep Expo. iOS silent push via app delegate (~30 s budget). Android own FirebaseMessagingService + WorkManager. UnifiedPush in pure Kotlin.
- Storage: MMKV (settings/state), Keychain/Keystore (secrets), bark DB (wallet), app-group stores (widgets).

## bark-bolt-ffi gaps (needed for parity)

P0 (blocks M1/M5):
1. Indexed key lookup/derivation + message sign/verify, preserving BOTH derivation paths (`m/350'/0'/i` loaded wallet, `m/350/i` offline mnemonic), Bitcoin signed-msg hash, ECDSA DER hex. Server login depends on it.
2. Consistent SQLite snapshot export + non-destructive snapshot validation (backups v2).
3. Whole-wallet committed-state change notifications (backup triggering).

P1:
4. Movement metadata updates + destination amounts in history (receive completion sums them).
5. Onchain ownership check (`isMine`) + true drain/send-all.
6. Pure VTXO decode; missing VTXO detail fields; optional dangerous drop.
7. Pending round results (funding txids, errors, terminal outcome).
8. All board VTXO IDs; exit transaction packages.
9. Mailbox authorization absolute-expiry overload.
10. ArkInfo/address fields the UI shows (mailbox pubkey etc.).

## Milestones (summary — see TODO.md)

### Phase 0 — Foundations (no user-facing UI)
- [ ] 0.1 Regtest upgrade: bitcoind 31+, captaind + bark `nightly-2026-10-19` (digest-pinned), delete `watchman_min_balance`, `[watchman]`, `vtxopool.issue_interval` from captaind.toml; verify `just setup-everything`; verify barkd 0.7.1 forwarding path end-to-end (bump only if broken).
- [ ] 0.2 Worktree `feat/native-rewrite`; scaffold `apps/ios` (3 schemes, same bundle IDs/entitlements/app groups/widget targets) and `apps/android` (3 flavors, same applicationIds, google-services); CI build jobs.
- [ ] 0.3 FFI gaps — owned by a separate thread (see ffi-handoff-prompt.md). Here: define `WalletService` abstraction; adopt new FFI releases as they land.
- [ ] 0.4 OpenAPI pipeline + generated Swift/Kotlin clients + contract fixtures + CI drift gate.
- [ ] 0.5 Upgrade-compat layer on both platforms: seed readers, MMKV open, datadir preflight/open, Expo install ID, GT locale. Automated upgrade test: install RN regtest build → create wallet, fund, settings, recurring rule → install native build over it → assert identity, balance, history, settings, Expo token.
- [ ] 0.6 WalletCoordinator + config mapping (fee units) + logging (redacting) + Sentry.
- [ ] 0.7 Server auth client (k1 → sign → JWT, refresh), registration, push registration.
- [ ] 0.8 Backup engine port, byte-compatible (v2: header 0x02 | 16B salt | 12B nonce | AES-256-GCM ct+tag; PBKDF2-SHA256 600k; manifest.json + db.sqlite zip). Cross-platform fixtures (iOS↔Android↔RN). Atomic writes on both. Update scripts/decrypt_backup.js for v2.
- [ ] 0.9 Translation tooling: GT catalogs → .xcstrings + strings.xml (en, es, ja, ko, sv).

### M1 — Shell, onboarding, home
Launch gating (onboarding/beta warning/push permission/battery/biometric lock), create/restore wallet, mnemonic display, email + lightning address, Home balance, banners, widgets writer, push handlers live (all 6 notification types), background tasks.

### M2 — Receive
Unified request (Ark/LN/onchain), amount/description sheets, QR/share/copy, receive success, QR hub.

### M3 — Send
Input parsing (BIP21/BIP321/bolt11/bolt12/LN address/Ark), method selection, MAX, fees, confirm, success, repeat payment, QR scanner.

### M4 — History
Transactions list/filters, detail, historical fiat, CSV export.

### M5 — Settings & recovery
Profile, currency/unit/language, backups UI, export DB, Esplora, logs, feedback/support, Noah story audio, debug, UnifiedPush + battery (Android).

### M6 — Onchain & exits
Boarding/auto-boarding, VTXOs/detail/refresh, unilateral exit flow + claims, Ark info.

### M7 — Recurring payments
Editor/list, schedule math (DST/month-end), execution safeguards, server sync.

### M8 — BTC Map
MapLibre native, offline snapshot, search, location, clustering, merchant details.

### Backlog (after parity)
- OS deep links for bitcoin:/lightning:/lnurl.
- Raw APNs/FCM token registration alongside Expo (server + client).
- Noah design system setup.
- iOS keychain accessibility policy review (ThisDeviceOnly).

### Cutover
Order agreed with the user on 2026-10-20:
1. Adaptive layouts: test landscape on the iPhone Duo simulator (iOS 27.1) and on Android tablets.
2. Design how translations are handled going forward (source of truth, workflow).
3. The design pass.
4. Signet test builds from CI: an APK, plus a Diawi link for iOS, matching today's RN signet flow.
5. In days to weeks: TestFlight + Play internal/closed tracks on the same listings, monitoring of heartbeats, push delivery and migration telemetry, then a staged rollout.

`client/` stays in the repo after the native apps ship, until we're confident in the rewrite. Deleting it, with the Nitro modules and ts-rs, is a later, separate decision.

### CI testing for the native UI (agreed 2026-10-20)
We keep Maestro as the cross-platform end-to-end layer and point it at the native apps. It reads the accessibility tree, so SwiftUI and Compose need no other tool.
- Every PR: native unit suites (`native-android.yml`, `native-ios.yml`), as today.
- Every PR: Maestro on the native Android regtest APK, on the existing Linux emulator runner with the Docker regtest stack. This replaces the RN APK in `noah-maestro-test-android.yml`. The RN flows in `client/.maestro` get ported to native, using stable test IDs (`accessibilityIdentifier` / `testTag`). There is one flow set per behaviour, with platform branches only where the UI differs.
- Nightly or on a label: the same flows on an iOS simulator (macOS runner), and the adaptive-layout flows (`client/tests/maestro/adaptive-*.yml`, ported) on a tablet emulator and the iPhone Duo.
- Small native UI test sets where Maestro can't reach:
  - XCUITest for system popovers (date/time pickers) and simulated Face ID;
  - Compose instrumentation for rotation and state restoration. It already compiles in CI; run it on the emulator job.
- Nightly or manual: the RN→native upgrade tests, which already use Maestro for RN onboarding.

## Working method
- RN regtest app running beside the native app for screen-by-screen reference.
- Small commits; after each meaningful commit a GPT-6.1 Sol (high/xhigh) review agent; fix findings before moving on.
- GPT-6 Astra background agents for parallel work (Android twin of each iOS screen, translations tooling, FFI additions).
- Sensitive areas (seed, backup crypto, datadir, signing, recurring sends, exits) get fixture tests + a dedicated review round.
