# Settings and account screens parity audit, 2026-10-20

Read-only source audit (Sol) of the RN screens not covered by earlier reviews, against both native apps at `454da8c6`. No builds or devices were used. Fix batches:

- Lifecycle/backup batch (items 1, 2, 6, 8, 17, 22 and the 60-second sync question): in progress.
- Account/preferences batch (items 3, 7, 9–14, 21, 23): queued.
- Info/tools batch (items 4, 5, 15, 16, 18–20): queued.

Audited clean HEAD `454da8c6` on `t3/audit-native-screen-parity`. Findings below are from source inspection; no files changed and no builds or device commands ran. S/M/L describe estimated fix size.

**Must fix before release**

1. **Deleting the latest backup does not schedule its replacement.** RN schedules another backup when automatic backups are enabled; both native coordinators only clear the remembered ID/hash and mark pending, leaving replacement dependent on another trigger. **S**
   RN: `client/src/hooks/useBackupManager.ts:83`. iOS: `apps/ios/Noah/Services/Backup/BackupCoordinator.swift:278`. Android: `apps/android/app/src/main/java/com/noahwallet/services/backup/BackupCoordinator.kt:72`.

2. **Recovery-phrase access requires OS authentication even when wallet authentication is disabled.** RN bypasses authentication in that case; native always requests it, with no alternative for a device unable to authenticate. Retain the added protection while providing an accessible credential-setup/recovery path. **M**
   RN: `client/src/screens/MnemonicScreen.tsx:47`, `client/src/hooks/useBiometrics.ts:33`. iOS: `apps/ios/Noah/Features/Onboarding/OnboardingFlow.swift:196`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/Onboarding.kt:92`.

3. **The pre-wallet language picker is missing on both platforms.** Users must complete creation or restoration before reaching native language settings. **S**
   RN: `client/src/screens/OnboardingScreen.tsx:23`. iOS: missing from `apps/ios/Noah/Features/Onboarding/OnboardingFlow.swift:41`. Android: missing from `apps/android/app/src/main/java/com/noahwallet/ui/Onboarding.kt:33`.

4. **Feedback loses screenshot editing; Android also loses the selected-image preview.** RN permits cropping before attaching and displays the selected image. Native users cannot crop private surrounding content within this flow; Android shows only the attachment size. **M**
   RN: `client/src/screens/FeedbackScreen.tsx:83`. iOS: `apps/ios/Noah/Features/Settings/FeedbackView.swift:31`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/settings/OtherScreens.kt:36`, `apps/android/app/src/main/java/com/noahwallet/ui/settings/OtherScreens.kt:56`.

5. **Ark Info displays incorrect price-service endpoints.** Both native apps show mempool.space URLs, while RN shows the variant-specific Noah `/v0/prices` and `/v0/historical-price` endpoints actually used by the app. **S**
   RN: `client/src/screens/ArkInfoScreen.tsx:260`, `client/src/constants.ts:88`. iOS: `apps/ios/Noah/Features/Settings/ArkInfoView.swift:43`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/settings/AccountScreens.kt:136`.

**Should fix**

6. **Background locking discards screen-local state instead of preserving it under an overlay.** Both native roots remove wallet content while locked; Android also removes it while resolving foreground permission checks. This can discard Settings/Feedback drafts and navigation state. **M**
   RN: `client/src/components/BiometricGate.tsx:149`. iOS: `apps/ios/Noah/Features/Shell/RootView.swift:24`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/LaunchGateView.kt:69`, `apps/android/app/src/main/java/com/noahwallet/ui/LaunchGateView.kt:123`. These lifecycle consequences were not runtime-reproduced.

7. **Android hides the tabs throughout Settings.** RN keeps the main tabs available while browsing Settings; native Android requires backing out first. **S**
   RN: `client/src/Navigators.tsx:396`. iOS: matching tab shell, `apps/ios/Noah/Features/Shell/RootView.swift:99`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/NoahApp.kt:73`.

8. **Reset Registration can report completion without completing all RN service-registration steps.** iOS omits immediate mailbox renewal and swallows push-registration failures; Android ignores a nonregistered push outcome. RN propagates push/mailbox failures. **S**
   RN: `client/src/lib/server.ts:182`. iOS: `apps/ios/Noah/App/AppModel.swift:363`, `apps/ios/Noah/App/AppModel.swift:485`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/AppModel.kt:183`.

9. **Android resubmits an unchanged Lightning identity.** RN and iOS exit without a server mutation; Android can fail an otherwise unnecessary Save because of connectivity or server errors. **S**
   RN: `client/src/screens/LightningAddressScreen.tsx:62`. iOS: matching, `apps/ios/Noah/Features/Onboarding/LightningAddressView.swift:134`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/Onboarding.kt:195`.

10. **Android replaces actionable identity/email server errors with generic failures.** RN displays the server’s message; native Android loses explanations such as username conflicts or verification/send restrictions. Preserve safe structured server messages. **S**
    RN: `client/src/hooks/useUpdateLightningIdentity.ts:59`, `client/src/screens/EmailVerificationScreen.tsx:175`. iOS: matching message extraction, `apps/ios/Noah/Features/Onboarding/EmergencyEmailView.swift:188`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/AppModel.kt:271`.

11. **Email replacement loses its existing-address reassurance.** Both natives use generic optional-email copy instead of explaining that the current verified address stays active until the replacement is verified; Android additionally offers “Continue without email” from Settings. The underlying preservation of the old address matches. **S**
    RN: `client/src/screens/EmailVerificationScreen.tsx:229`. iOS: `apps/ios/Noah/Features/Onboarding/EmergencyEmailView.swift:68`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/Onboarding.kt:168`.

12. **Profile loses the 80-character input limit and verified-email copy action.** Both native name fields accept longer input, and the emergency email is plain text rather than RN’s copyable row. **S**
    RN: `client/src/screens/ProfileScreen.tsx:223`, `client/src/screens/ProfileScreen.tsx:308`. iOS: `apps/ios/Noah/Features/Settings/ProfileView.swift:69`, `apps/ios/Noah/Features/Settings/ProfileView.swift:79`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/settings/AccountScreens.kt:51`, `apps/android/app/src/main/java/com/noahwallet/ui/settings/AccountScreens.kt:60`.

13. **Android preference selection stays on the picker; its unit picker also omits amount examples.** RN returns immediately after currency/unit/language selection. Android currency names additionally use the device locale rather than the selected app locale. **S**
    RN: `client/src/screens/CurrencySettingsScreen.tsx:34`, `client/src/screens/BitcoinUnitSettingsScreen.tsx:26`, `client/src/screens/LanguageSettingsScreen.tsx:25`. iOS: matching selection/return, `apps/ios/Noah/Features/Settings/ProfileView.swift:43`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/settings/AccountScreens.kt:83`.

14. **Settings loses current-value summaries and Android changes row order.** iOS omits the selected language/currency/unit summaries; Android reduces them and moves Board before Emergency Exit, plus UnifiedPush below the toggles. **S**
    RN: `client/src/screens/SettingsScreen.tsx:288`, `client/src/screens/SettingsScreen.tsx:318`. iOS: `apps/ios/Noah/Features/Settings/SettingsView.swift:25`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/settings/SettingsScreen.kt:112`.

15. **Ark Info omits the reported network and block-height API field.** Both natives retain the other inspected server parameters, keys and wallet endpoints. **S**
    RN: `client/src/screens/ArkInfoScreen.tsx:258`, `client/src/screens/ArkInfoScreen.tsx:315`. iOS: missing from `apps/ios/Noah/Features/Settings/ArkInfoView.swift:13`. Android: missing from `apps/android/app/src/main/java/com/noahwallet/ui/settings/AccountScreens.kt:109`.

16. **Logs lose latest-record positioning and refresh timestamps.** Both natives start at the oldest displayed record; neither shows RN’s last-update time. Android’s initial read also lacks a catch/loading/error path; iOS treats unreadable log files as absent. **S**
    RN: `client/src/screens/LogScreen.tsx:42`. iOS: `apps/ios/Noah/Features/Settings/FileShare.swift:58`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/settings/BackupScreens.kt:127`.

17. **A successful manual backup can be presented as failed when the subsequent list refresh fails.** RN ignores that secondary failure; both native flows include it in the backup action’s failure handling. **S**
    RN: `client/src/hooks/useBackupManager.ts:54`. iOS: `apps/ios/Noah/Features/Settings/BackupSettingsView.swift:31`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/settings/BackupScreens.kt:54`.

18. **Export Database loses completion feedback and timestamped filenames.** Both natives export a fixed variant filename and provide no RN-equivalent successful-share acknowledgement. Encryption and share preparation are present. **S**
    RN: `client/src/hooks/useExportDatabase.ts:21`, `client/src/hooks/useExportDatabase.ts:51`. iOS: `apps/ios/Noah/Features/Settings/FileShare.swift:40`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/settings/BackupScreens.kt:119`.

19. **Debug results lose useful structure and failure detail.** RN returns JSON for Ark/decode/import and copyable errors; native uses object descriptions/generic failures, and iOS discards the import result entirely. All eleven actions exist. **M**
    RN: `client/src/screens/DebugScreen.tsx:150`, `client/src/screens/DebugScreen.tsx:248`. iOS: `apps/ios/Noah/Features/Settings/DebugView.swift:60`. Android: `apps/android/app/src/main/java/com/noahwallet/services/wallet/WalletSession.kt:579`, `apps/android/app/src/main/java/com/noahwallet/ui/settings/OtherScreens.kt:123`.

20. **Story playback stops earlier.** RN stops on unmount; iOS stops on disappearance, including leaving its tab, and Android explicitly pauses on `ON_STOP`. Play/pause/stop/seek and bundled audio otherwise exist. **S**
    RN: `client/src/screens/NoahStoryScreen.tsx:163`. iOS: `apps/ios/Noah/Features/Settings/NoahStoryView.swift:50`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/settings/OtherScreens.kt:92`.

21. **Android onboarding Back differs from RN’s stack.** Email/Lightning Back is consumed, UnifiedPush Back advances, and Battery Back is blocked; RN can return through its registered onboarding stack. **S**
    RN: `client/src/Navigators.tsx:279`, `client/src/screens/EmailVerificationScreen.tsx:216`. iOS: uses a navigation stack, `apps/ios/Noah/Features/Onboarding/OnboardingFlow.swift:13`. Android: `apps/android/app/src/main/java/com/noahwallet/ui/Onboarding.kt:31`, `apps/android/app/src/main/java/com/noahwallet/ui/launch/PlatformScreens.kt:65`, `apps/android/app/src/main/java/com/noahwallet/ui/launch/PlatformScreens.kt:105`.

22. **iOS deletion does not reset the live biometric-lock object.** RN resets the wallet’s biometric preference; iOS clears persisted settings but retains `RootView`’s existing lock policy, so same-process re-onboarding can inherit the previous lock. **S**
    RN: `client/src/hooks/useWallet.ts:379`. iOS: `apps/ios/Noah/App/AppModel.swift:354`, `apps/ios/Noah/Features/Shell/RootView.swift:6`. Android: resets it, `apps/android/app/src/main/java/com/noahwallet/ui/AppModel.kt:176`.

23. **Android hardcodes the regtest Lightning-address domain.** RN supports `EXPO_PUBLIC_REGTEST_LNURL_DOMAIN`, and iOS supports its native configuration override; Android always previews `localhost.com`. **S**
    RN: `client/src/constants.ts:69`. iOS: `apps/ios/Noah/Services/Server/LightningIdentity.swift:30`. Android: `apps/android/app/src/main/java/com/noahwallet/services/server/LightningIdentity.kt:20`.

**Acceptable/intentional**

- **Backup deletion adds confirmation and backup status information.** This is a useful visible safety addition. RN: `BackupSettingsScreen.tsx:163`; iOS: `BackupSettingsView.swift:55`; Android: `BackupScreens.kt:72`. **No fix.**
- **Dangerous Debug offboarding adds typed confirmation.** RN confirms Drop only; native confirms Offboard All too. RN: `DebugScreen.tsx:289`; iOS: `DebugView.swift:30`; Android: `OtherScreens.kt:141`. **No fix.**
- **Seed display adds clipboard expiry, screen protection and background clearing.** These improve privacy; the unavailable-authentication case remains gap 2. RN: `MnemonicScreen.tsx:90`; iOS: `OnboardingFlow.swift:189`; Android: `Onboarding.kt:66`. **No fix to these safeguards.**
- **Retained-seed recovery replaces RN’s stale-seed deletion.** Explicitly required by PLAN.md. RN: `Navigators.tsx:549`; iOS: `RootView.swift:32`; Android: `LaunchGateView.kt:128`. **No fix.**
- **Android exposes recurring payments on non-GMS devices and adds a Battery Settings row.** Native UnifiedPush now dispatches recurring work, unlike RN’s old handler. RN: `SettingsScreen.tsx:339`; iOS: `SettingsView.swift:38`; Android: `SettingsScreen.kt:123`, `PushRuntime.kt:25`. **No fix.**
- **UnifiedPush timeout becomes recoverable.** RN stops polling without leaving registering state; Android displays a failure and permits retry. RN: `UnifiedPushScreen.tsx:88`; iOS: not applicable; Android: `PlatformScreens.kt:139`. **No fix.**
- **iOS QR scanning omits EAN-13.** RN accepts it, but numeric EAN-13 payloads are not useful supported wallet destinations. Normal payment QR handling remains. RN: `useQRCodeScanner.ts:28`; iOS: `SendScanner.swift:77`; Android: `SendScanner.kt:52`. **No fix needed for wallet parity.**
- **OS payment deep links remain absent.** RN has no payment-link consumer; native absence is not lost functionality. RN: `Navigators.tsx:693`; iOS: `RootView.swift:10`; Android: `MainActivity.kt:55`. **No fix for this audit.**

**Settings row and visibility coverage**

RN’s initialized-wallet order is: Account → Info → Wallet → Debug → Danger Zone. Within Wallet: Seed → VTXOs → Emergency Exit → Recurring → Backup → conditional UnifiedPush → Board → Auto-board → conditional Biometrics → Mailbox. Every ordinary row/action exists natively; ordering/value-summary differences are gap 14.

- Mainnet, signet and regtest use the same row set; variant-specific endpoints were traced separately.
- Debug is unlocked by five version taps with a two-second reset on all three implementations; it is not restricted to debug builds.
- UnifiedPush is Android/non-GMS only. Native Android additionally exposes it on non-GMS emulators, whereas RN excludes emulators.
- Native Settings is available after onboarding; RN’s uninitialized Configuration registration has no discovered entry action.
- Suspend/resume, typed wallet deletion, auto-board preference, authenticated biometric toggles, mailbox revoke-before-disable, Story, Telegram and GitHub actions are present.

**Verified matching, with no additional screen-specific gaps**

- **BetaWarningScreen:** risk warning, explicit acceptance, decline, duplicate-create prevention and creation error state.
- **RestoreWalletScreen entry points:** Welcome restore and retained-seed recovery; restore internals excluded as requested.
- **PushNotificationsRequiredScreen:** physical-device resolved-denial gate, no skip, enable/recheck/settings actions and foreground recheck.
- **ReceiveSuccessScreen:** received amount/unit, optional fiat, Settled status, wallet balance placeholder and Done → Home.
- **iOS CurrencySettingsScreen, BitcoinUnitSettingsScreen and LanguageSettingsScreen:** supported options, persisted selection and immediate return.
- **QRHubScreen’s payment-QR flow on both platforms:** literal Lightning-address QR/copy, name, missing-address Profile route, scanner/paste handoff and permission-denial handling; EAN-13 difference noted above.
- **Android BatteryOptimization onboarding/root gate:** applicability, persisted Skip, foreground exemption recheck, OS-settings fallbacks and manual failure instructions.

One unresolved service-level comparison remains: RN explicitly runs an app-wide 60-second sync/claim loop (`AppServices.tsx:18`). I found native daemon/feature-driven refresh paths, but did not establish that they provide the same cadence while these Settings screens stay open. This is an uncertainty, not a confirmed gap.

Runtime lifecycle, photo-picker and OS-authentication behaviour were not exercised. No money-flow or restore-internal re-audit was performed.
