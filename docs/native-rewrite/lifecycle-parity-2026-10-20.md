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


## Round 2 — lifecycle review fixes

Implementation base: `be132094`, checkout `t3/native-lifecycle-round2-fixes`.
No `client/` or `server/` source changes. No Send, recurring executor/runner,
restore or Esplora execution changes.

### Suspension and close admission (iOS)

`apps/ios/Noah/Services/Wallet/WalletLifecycle.swift:62` persists suspension inside
`WalletSession.closeSettled(beforeClose:)` (`WalletSession.swift:272`). The session
actor increments a pending-close admission hold before that synchronous callback
or any opening/closing await. Every sync stage reenters the session actor and
requires an open session with no pending close (`WalletSession.swift:639`). Direct
`close()` also revokes admission by publishing its existing closing state before
its first await. A count supports overlapping settled-close requests without an
out-of-order completion restoring admission prematurely; failure releases only
that request's hold. The whole pass remains one counted `withOpenWallet` call,
so admitted native work settles before daemon teardown or a replacement reservation.
No lifecycle gate is acquired from inside another gate. Commit: `43a8036a`.

`WalletSyncTests.swift:7` uses a temporary real SDK session, synthetic counted
stages and the production suspension path. Both suspension and direct close are
started during stage one; persistence, rejected reservation, unfinished close,
settled native work and omission of stages two/three are asserted. The injectable
stages use the same counted orchestration as production onchain/Ark/claim stages.

### Pause admission (Android)

`apps/android/app/src/main/java/com/noahwallet/services/wallet/ForegroundSyncAdmission.kt:15`
admits minute sync only after ON_RESUME and revokes its independent budget on
ON_PAUSE. `MainActivity.kt:106` attaches that observer, and
`recurring/RecurringRuntime.kt:43` routes only sync through it. The existing
STARTED/ON_STOP minute runner and recurring execution budget remain intact.
A pause-revoked sync cancellation is consumed only while the caller job remains
active; cancellation of the runner still propagates. This allows recurring work
to proceed after sync's admitted native stage drains. The existing session and
background-activity accounting remain held until settlement. Commit: `bcb4cb86`.

`ForegroundSyncAdmissionTest.kt:18` drives a real LifecycleRegistry and the existing
minute runner, pauses during the first sync stage, checks background ownership
until settlement, rejects later stages and paused ticks, and checks that recurring
continues while STARTED and sync restarts after resume.

### Overlap dropping before outer gates (both platforms)

`apps/ios/Noah/Services/Wallet/ForegroundWalletSync.swift:14` claims an actor-owned
slot before configuration/background access and the lifecycle mutation gate.
`apps/android/app/src/main/java/com/noahwallet/services/wallet/ForegroundWalletSync.kt:9`
uses a nonblocking mutex slot before background access and the lifecycle gate.
AppServices routes foreground reentry and minute requests through these helpers;
all existing eligibility checks and the per-session sync flight remain inside.
The slot is retained through admitted work's settlement and released on every
exit. Lock order stays background access → lifecycle gate → RestoreRecovery →
session reservation; none becomes reentrant. Commits: iOS `c63ad293`, Android
`bf79846d`.

`ForegroundWalletSyncTests.swift:6` and `ForegroundWalletSyncTest.kt:10` hold each
outer gate in turn, overlap minute/reentry requests before the session flight,
and require one pass plus a successful later independent request. This catches
queuing at the outer gates, beyond the round-1 per-session overlap tests.

### Explicit window restoration fixture (iOS)

`apps/ios/NoahTests/WalletContentProtectionTests.swift:46` explicitly sets both
starting accessibility/interaction flags before covering the unattached hidden
window. Restoration no longer depends on UIKit's initial accessibility state.
Commit: `22be336a`.

### Round-2 verification and cleanup

Before the fixes, the two iOS regressions failed with **eight assertion issues**:
both direct close and suspension admitted all three stages, and both outer-gate
cases queued a second pass. The Android regressions both failed: the paused pass
ran two later stages, and the overlapped request ran a second pass. These regressions
are permanent. Focused runs after the fixes passed **10 iOS tests / four suites**
and **13 Android JVM tests / four suites**, with zero failures/skips. The Android
subset includes all seven existing recurring minute-runner tests. iOS function
selectors used trailing `()`.

The exact requested full Android command ran once after the final Android code
change: **3,574 JVM tests / 137 suites / zero failures, errors or skips**. Regtest
debug APK assembly and instrumentation Kotlin compilation passed; Gradle completed
in **2m 39s**.

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Full NoahTests ran **once after the final iOS code/test change**, with XcodeGen
regeneration first: **785 definitions / 157 suites / 760 passed / 25 opt-in skipped /
zero failed** (Swift Testing execution **315.252 seconds**). The xcresult summary
independently reports the same 785 total, 760 passed and 25 skipped.

```sh
xcodegen generate --spec apps/ios/project.yml
xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=0AC6E859-0941-4805-BAFA-4759C10DC6F6' \
  -derivedDataPath /private/tmp/noah-lifecycle-r2-dd \
  -disableAutomaticPackageResolution -skipPackageUpdates -collect-test-diagnostics never \
  -resultBundlePath /private/tmp/noah-lifecycle-r2-ios-full.xcresult \
  -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO \
  -only-testing:NoahTests test
```

`bun apps/tools/translations.mjs --check` is clean. Each commit has the requested
Claude co-author trailer and completed the repository hook (RN lint, compiler
checks, 148 RN unit tests, TypeScript and Rust formatting). Missing ignored Husky
files were installed through locked dependencies before the first commit.

Owned simulator **Noah Lifecycle R2 1020**, iPhone 17 / iOS 27.0, UDID
`0AC6E859-0941-4805-BAFA-4759C10DC6F6`, was identity-checked by UDID, name and
device type. Its **Shutdown** state was confirmed by UDID before deletion, then
absence was verified. `/private/tmp/noah-lifecycle-r2-dd` and all three created
`noah-lifecycle-r2-*.xcresult` bundles were removed and absence checked. No owned
Xcode/Gradle build or package resolver remains. Small text logs and count summaries
remain under `/private/tmp/noah-lifecycle-r2-*`; shared idle daemons were untouched.

The Device panel twice returned “No device 0AC6E859-0941-4805-BAFA-4759C10DC6F6 on
host local”, so Xcode/simctl used only the owned simulator's explicit UDID. An
initial MMKV submodule fetch stalled; owned resolver processes were stopped and
existing local package checkouts copied into the owned DerivedData. The expected
failing run triggered slow simulator diagnostics; only that owned diagnostics
process was stopped after assertions completed (Xcode exited 65). Subsequent runs
disabled automatic diagnostics. No protected device, physical device, Android
emulator or Docker was used. One owned heavy build ran at a time. No live funded
wallet or physical-device lifecycle acceptance was attempted.
