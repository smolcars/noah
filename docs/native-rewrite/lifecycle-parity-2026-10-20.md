# Native lifecycle and backup parity — 2026-10-20

Base: `6d4b3cd0`. Implementation checkout: `t3/native-parity-fixes`. No `client/` or `server/` changes.

## 1. Deleted latest backup

iOS `apps/ios/Noah/Services/Backup/BackupCoordinator.swift:278`; Android `apps/android/app/src/main/java/com/noahwallet/services/backup/BackupCoordinator.kt:72`. After draining an in-flight pass, forgetting the remembered upload schedules `.manual` through the existing coordinator. Only a matching upload triggers it; enabled/suspended eligibility, five-second debounce, generation coverage and single-flight behaviour are retained. Tests cover replacement upload, other IDs, disabled/suspended wallets and an unchanged pass already in flight. Commits: iOS `8afe9d1d`; Android `b519e018`.

## 2. Phrase access without a screen lock

iOS `apps/ios/Noah/Features/Settings/WalletLock.swift:27` and `apps/ios/Noah/Features/Onboarding/OnboardingFlow.swift:189`; Android `apps/android/app/src/main/java/com/noahwallet/ui/settings/WalletLock.kt:98` and `apps/android/app/src/main/java/com/noahwallet/ui/Onboarding.kt:93`. Only iOS passcode-not-set classification or Android nonsecure keyguard permits the explicit “No screen lock” warning and “Show recovery phrase” confirmation. Credential presence is checked again at confirmation. Credentialed-device cancellation, failure, lockout and unavailable biometrics never enter this fallback. Generation/foreground guards, phrase shielding, expiring clipboard and background clearing remain. Tests cover LA error classification and Android secure-keyguard detection on SDK 28/35. Commits: iOS `bb69a570`; Android `3353b74e`.

## 3. Complete Reset Registration

iOS `apps/ios/Noah/App/AppModel.swift:370` and `apps/ios/Noah/Services/Server/ServerRegistration.swift:136`; Android `apps/android/app/src/main/java/com/noahwallet/ui/AppModel.kt:183` and `apps/android/app/src/main/java/com/noahwallet/services/server/ServerRegistration.kt:107`. Registration now loads the wallet, requires completed push registration, immediately renews the mailbox, then publishes registration success. Load/push/mailbox failures propagate; explicit mailbox opt-out reports RN’s disabled-mailbox failure. An unsupported iOS simulator remains RN’s successful push skip. Failure tests assert ordering and unregistered state until every step succeeds. Commits: iOS `a9ef1e7b`, contract follow-up `f1e0adba`; Android `0f19834c`.

## 4. Manual backup success

iOS `apps/ios/Noah/Features/Settings/BackupSettingsView.swift:32`; Android `apps/android/app/src/main/java/com/noahwallet/ui/settings/BackupScreens.kt:57`. An uploaded backup keeps its success message when list refresh fails; a separate inline list error remains retryable. Regression tests distinguish upload failure, secondary refresh failure and complete success. Commits: iOS `56bf3113`; Android `8c45d58c`.

## 5. Live biometric policy after deletion

iOS `apps/ios/Noah/App/AppModel.swift:360`, `apps/ios/Noah/Features/Shell/RootView.swift:6`, `apps/ios/Noah/Features/Settings/WalletLock.swift:94`. AppModel owns the same live lock RootView uses. Successful deletion invalidates outstanding authentication and resets its policy. The regression checks the shared live instance, generation invalidation and disabled/unlocked policy. Android’s existing `AppModel.kt:176` reset is unchanged. Commit: iOS `d77fef70`.

## 6. Mounted content beneath lock/checking

iOS `apps/ios/Noah/Features/Shell/RootView.swift:24` and `apps/ios/Noah/Features/Settings/WalletLock.swift:114`; Android `apps/android/app/src/main/java/com/noahwallet/ui/LaunchGateView.kt:68` and `apps/android/app/src/main/java/com/noahwallet/ui/WalletPrivacy.kt:18`. Locked/checking wallet screens stay mounted, transparent and hidden from accessibility. iOS disables the source window’s interaction/accessibility and uses an opaque higher-level window, including synchronous background snapshot covering. Android retains the last resolved platform presentation during checks and uses a secure opaque modal cover. Every wallet alert/sheet also shields its own separate window, blocks private pointer/key/accessibility interaction, and offers Unlock when appropriate; original slot content stays mounted even for a dialog created after locking. Native date/time pickers retain their instances while hidden and have FLAG_SECURE; late callbacks consult current privacy before showing. Tests verify mounted draft/navigation identity, iOS source-window access, and Android late-dialog private-text removal/Unlock/draft retention. Commits: iOS `42e5c815`; Android `ea51ce5e`, picker follow-up `92b58f9c`.

## 7. Foreground sync/claim cadence

iOS `apps/ios/Noah/App/AppModel.swift:104`, `apps/ios/Noah/Services/AppServices.swift:93`, `apps/ios/Noah/Services/Wallet/WalletSession.swift:617`; Android `apps/android/app/src/main/java/com/noahwallet/recurring/RecurringRuntime.kt:43`, `apps/android/app/src/main/java/com/noahwallet/services/AppServices.kt:274`, `apps/android/app/src/main/java/com/noahwallet/services/wallet/WalletSession.kt:335`. The existing app-wide minute runners previously executed recurring payments only; they now run onchain sync, Ark sync and nonwaiting Lightning receive claims on any foreground screen, including Settings. Foreground reentry also syncs. Independent stages continue after ordinary failures, with revocation checked between stages. Configuration/lifecycle gates, background-activity accounting, open-session admission and suspension/replacement checks are retained; the new sync stage never opens a wallet. Overlapping sync ticks drop; Send reconciliation waits on the same per-session sync flight so it cannot read stale evidence. Native work admitted before deactivation settles before releasing ownership; revoked work cannot admit another stage. Tests cover independent failures, overlap, foreground revocation/settlement, and queued reconciliation. Existing minute-runner lifecycle regressions remain. Commits: iOS `bf4e229d`; Android `2b980623`.

The SDK daemon was also checked: bark-bolt-ffi 0.5.0 pins Bark `bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c`. Bark `bark/src/config.rs:276` defaults to 60 seconds, but `bark/src/daemon/mod.rs:281` periodically syncs fees/pending boards/offboards/onchain/rounds; it does not periodically run the full wallet sync and Lightning receive claims. The full startup sync is not an equivalent foreground cadence. SDK onchain work retains its shared write lock (`bark/src/lib.rs:1528`); daemon configuration and lifecycle are unchanged.

## Deliberately unchanged

Launch decisions and locking conditions remain authoritative. Onboarding, seed/data recovery, interrupted restore/deletion, custom-server failure, and resolved push/battery gates still replace wallet content because they admit or replace the wallet; retaining an old wallet subtree there would preserve state belonging to the wrong lifecycle. Only lock and transient platform-check presentation retain existing screens. Onboarding phrase presentation remains its existing unauthenticated flow. No seed/backup formats, wallet creation/deletion ordering, recurring policy, server endpoint or RN code changed.

## Verification

Added **14 Swift Testing definitions** across `BackupCoordinatorTests.swift`, `SettingsFeatureTests.swift`, `ServerRegistrationTests.swift`, `WalletContentProtectionTests.swift` and `WalletSyncTests.swift`; several definitions expand parameterized cases. Added **12 Android test methods / 13 JVM cases** across `BackupCoordinatorTest.kt`, `SettingsFeaturesTest.kt`, `ServerRegistrationTest.kt`, `RecoveryPhraseCredentialTest.kt`, `WalletContentProtectionTest.kt` and `WalletSyncTest.kt`, plus strengthened the existing in-flight deletion regression.

Focused iOS: **32 tests / 6 suites / 0 failures or skips** after correcting the foreground-open call signature. Focused Android checks passed; the final privacy/sync subset was **6 tests / 2 suites / 0 failures/errors/skips**. The late-dialog test uses real Compose dialog semantics under Robolectric and checks both a hidden private draft and retained content after unlocking.

Full Android: **3,567 JVM tests / 134 suites / 0 failures / 0 errors / 0 skips**, regtest debug APK assembly and instrumentation Kotlin compilation passed. The exact requested command ran once after the final Android source change:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Full iOS ran **once after the final iOS source change**: **778 definitions / 155 suites / 753 passed / 25 opt-in skipped / 0 failed**, Swift Testing execution 262.572 seconds. XcodeGen regenerated the project before the run. The signed regtest suite used the owned iPhone 17 simulator, `ARCHS=arm64`, `ONLY_ACTIVE_ARCH=YES`, two Xcode jobs and `/private/tmp/noah-parity-lifecycle-dd`:

```sh
/opt/homebrew/bin/xcodegen generate --spec apps/ios/project.yml
xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=5E02D714-F99B-4F08-A9BE-0622A13FE4C2' \
  -derivedDataPath /private/tmp/noah-parity-lifecycle-dd \
  -resultBundlePath /private/tmp/noah-parity-lifecycle-final.xcresult \
  -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO test
```

Native translations regenerated with `bun apps/tools/translations.mjs`; `--check` clean and **7 generator tests passed**. English warning/confirmation copy is present on both native catalogs; existing RN English keys are reused. Every implementation commit has the requested co-author trailer and ran through the repository hook. The first commit was amended through the hook after installing the missing generated Husky files with locked dependencies.

Two dedicated read-only safety review rounds found and closed Android late-modal exposure, native picker snapshot protection, iOS immediate activation revocation, and Send reconciliation bypassing sync serialization. No physical-device authentication, real push transport, funded wallet, Docker, or Android emulator was used for this task. JVM/Swift regressions use fake transports or synthetic state; they do not establish live OS authentication or assistive-technology acceptance.

## Owned artifacts

Owned simulator: **Noah Parity Lifecycle 1020**, iPhone 17 / iOS 27.0, UDID `5E02D714-F99B-4F08-A9BE-0622A13FE4C2`. The Device panel returned “No device … on host local” twice; it was driven only by its explicit simctl UDID. DerivedData: `/private/tmp/noah-parity-lifecycle-dd`. Explicit results: `/private/tmp/noah-parity-lifecycle-focused.xcresult`, `...-focused-2.xcresult`, `...-final.xcresult`.

Cleanup completed: the exact simulator name/UDID was checked, shutdown completed, its **Shutdown** state was confirmed by UDID, then it was deleted and absence rechecked. The requested DerivedData and all three explicit result bundles above were removed and absence checked. No owned Xcode/Gradle build invocation remains; shared idle daemons and unrelated builds/devices were left alone. One owned heavy build ran at a time. Small text logs/count summaries remain under `/private/tmp/noah-parity-*`; the committed report preserves the results after bundle removal.
