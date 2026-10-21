# Physical-device acceptance, 2026-10-20

Devices:
- Pixel 6 (Android 17, GMS, no screen lock), `com.noahwallet.regtest` debug build of `604c80a5`.
- iPhone 17 (iOS 27.2), `com.noahwallet.regtest` Release-Regtest build of `604c80a5`. The user's mainnet and signet apps were not touched.

Backend: local regtest stack plus a Noah server on port 3100 with real Expo credentials. It shares the stack's Postgres with the Docker server on port 3000, so both servers run the five-minute recurring sweep.

## Results

| Check | Platform | Result |
| --- | --- | --- |
| `recurring_payment_due` push wakes a backgrounded app and pays | Pixel | Pass. 6:00 PM monthly 1,000-sat Ark schedule. At 18:00:00 the server sent the wake-up. Both servers' pushes arrived at 18:00:02 and the push job sent one arkoor payment at 18:00:08. The receiver (bark CLI) recorded exactly one 1,000-sat receive (movement 88). The "Recurring payment sent" notification was posted. |
| `recurring_payment_due` background delivery | iPhone | Pass, delivery only. With Settings in the foreground, a due server row for the iPhone's wallet produced a push at 18:05:00.366. The app log shows `Handling recurring_payment_due push` at 18:05:00.674 and `Finished` at 18:05:01.158, and the app synced at 18:05:00.995. The row was inserted directly into the test DB: UI automation needed the device passcode, so no local schedule existed and nothing was paid. A `backup_trigger` push was also handled in the background (report 200 at 22:00:06Z). |
| Timed local reminder (24 h before) | Pixel | Pass. Schedule for Oct 21 6:05 PM. "Upcoming recurring payment: ₿ 500 to Reminder test will be sent tomorrow" posted at 18:05:06. |
| Foreground 60 s sync/claim cadence | Pixel | Pass. `[SYNC]` passes every 60 s in the foreground (17:53:28, 17:54:29, 18:05:27, 18:06:27) and none while backgrounded (17:56–18:05). A 2,345-sat Lightning payment made while Settings was open was claimed at 18:06:39, 3 s after LND paid. The claim came via the SDK mailbox notification. |
| Recovery phrase on a device with no screen lock | Pixel | Pass. "Authenticate to view…" shows the "No screen lock" warning. Cancel keeps 0 words visible; "Show recovery phrase" reveals 12. Screenshots are black (FLAG_SECURE). The Recents card is black. Returning from Home clears the phrase. |
| BOLT12 offer (regtest CLN) | Pixel | Pass. 2,000 sats, 83-sat fee; CLN invoice `paid`. |
| BOLT12 offer (regtest CLN) | iOS simulator | Pass. 1,500 sats, 81-sat fee; CLN invoice `paid`. |
| Lightning address (local LNURL-pay mock backed by LND) | Pixel | Pass. `alice@localtest.me`, 3,000 sats, 87-sat fee. The LND invoice settled with the metadata description hash. |
| Lightning address (local LNURL-pay mock backed by LND) | iOS simulator | Pass. 4,000 sats, 91-sat fee; LND invoice settled. |

The LNURL mock was a throwaway HTTPS server on the Mac with a three-day test CA. `localtest.me` resolves to 127.0.0.1, and the Pixel used `adb reverse tcp:443`. The Pixel ran a local-only build whose `regtestDebug` network config trusted that CA. The build was removed and the normal APK reinstalled afterwards. The simulator trusted the CA via `simctl keychain add-root-cert`.

## iPhone, second session (user present for the passcode, Face ID and camera)

| Check | Result |
| --- | --- |
| Recurring payment from a real local schedule | Pass, with one issue. A 6:50 PM monthly 500-sat schedule was saved and the app sent to the background at 18:43. The 18:50 sweep sent the push at 18:50:00.7; the receiver recorded exactly one 500-sat payment. Issue: the app log shows sync plus the arkoor send at 18:50:01–02, *before* `Handling recurring_payment_due push` (18:50:03.4). Some path other than the push job executed the payment after the push resumed the suspended process. Reproduced and fixed in the native wake follow-up below. |
| Timed local reminder | Pass. "Upcoming recurring payment: ₿ 300 to Reminder test iOS will be sent tomorrow" banner at 18:55. |
| Biometric lock, app switcher, unlock | Pass. Enabling the lock asked for Face ID permission and Face ID. The app-switcher card showed "Noah is Locked" with no wallet content. Face ID prompted automatically on return, the Unlock button remains as a fallback, and the same Settings screen and scroll position came back. |
| Recovery phrase Face ID gate | Pass. Opening the screen prompts Face ID automatically; success showed 12 words (never screenshotted or logged). Cancel left 0 words and the "Authenticate to view your seed phrase" button. |
| Camera QR | Pass both ways. The iPhone scanned the Pixel's receive QR and the Pixel scanned the iPhone's; both offered Ark/On-chain for the unified request. |

Also observed: Home shows "Backup failed: CancellationError" after background/foreground transitions. A cancelled backup was presented as a failure with a raw error type. Reproduced and fixed in the native wake follow-up below.

## Not verified on hardware

Physical iOS LNURL: needs a trusted certificate, which can't be installed without the passcode. Covered on the simulator instead.

## Native wake and backup follow-up

Implementation base: `8d9cfb48`; task checkout `t3/fix-native-wake-lifecycle`.
No RN, server, log-privacy, physical-device or Docker changes.

### Wake diagnosis and fix

The deterministic reproduction follows the real minute runner and shared recurring
executor: a foreground runner sleeps, the scene becomes background, and the sleep
completes when the process wakes without a scene activation or another deactivation
notification. Before the fix, `RecurringForegroundRunner.swift:26` (base revision)
checked only task cancellation, then minted a fresh submission budget at line 28.
`AppModel.swift:106` ran foreground sync, line 108 refreshed, and line 109 called
`RecurringExecutor.shared.execute()`. The executor could therefore acquire wallet
access, sync and pay before `PushJobs.swift:43` logged Handling inside its access
scope. The regression observed all three synthetic sync stages and one actual
harness submission. The shared executor/journal still prevented a second payment.

The device log contains no trigger identity, so it cannot establish whether that
historical invocation was the sleeping tick or an earlier foreground-reentry task.
The latter also crossed awaits after its initial scene check. Both paths are fixed
and tested; this report does not claim a new physical-push trace.

- iOS `RecurringForegroundRunner.swift:27` checks the active scene before starting
  and again after sleep at line 35. `ForegroundExecution.swift:7,13,28` requires
  an active UIApplication and foreground-active scene, retains inherited admission,
  and revokes budgets on scene/application deactivation or backgrounding.
  `AppModel.swift:104,462,512` and `RecurringPaymentsView.swift:72,82,86` cover
  minute, reentry, delayed registration and screen/manual triggers.
- Sync checks inherited scene state after queued access in `AppServices.swift:98`,
  between independent stages in `WalletSync.swift:34`, and immediately before
  session-native admission in `WalletSession.swift:640`. Recurring checks at
  `RecurringExecutor.swift:78,90,156,165,180,215` cover entry, lifecycle waits,
  wallet-open completion, quote completion and marker admission.
  `WalletSession+Send.swift:123` checks again immediately before submission and
  preserves proven-not-submitted classification. Settlement remains owned.
- `BackgroundExecution.swift:20` explicitly clears the foreground-only scene
  requirement while retaining its own deadline, cancellation and submission budget.
  A regression pays once through this boundary with a stale foreground requirement.
  Push/BGTask stages and gates remain authoritative.
- Android `RecurringForegroundRunner.kt:15,20,26,31` moves recurring cadence from
  STARTED/ON_STOP to RESUMED/ON_PAUSE, with lifecycle rechecks after sleep and IO
  readiness loading. `ForegroundSyncAdmission.kt:15,21` revokes every overlapping
  callback's budget on pause; a later resume cannot revive old admission.
  `RecurringRuntime.kt:44,54`, `RecurringPaymentsScreen.kt:37,70,89` and
  `LaunchGateView.kt:124` route cadence, delayed startup, time-change, screen/manual
  and reentry work through that admission. WorkManager/FCM cannot start it merely
  by waking the process. Background execution keeps its existing explicit budgets.

No lock is made reentrant or moved: background access → lifecycle mutation →
RestoreRecovery → session reservation retains its existing order. Recurring policy,
wallet binding, durable occurrence evidence, exactly-once settlement and the SDK
owned daemon are unchanged. This supersedes the earlier parity report's deliberate
Android STARTED recurring lifetime.

### Backup cancellation and copy

`BackupCoordinator.swift:205,220` treats Swift cancellation, URLSession cancellation
(including a generated ClientError wrapper), task cancellation and budget revocation
as pending/idle with no failure banner or error retry timer. The manual Settings
operation also consumes cancellation (`BackupSettingsView.swift:106`); Android's
existing Settings action wrapper already propagates cancellation. Android's
`BackupCoordinator.kt:109` treats coroutine cancellation the same way. Flush callers
still receive cancellation; coordinator/native ownership is retained until settlement.

Real failures persist RN's existing English fallback, **“An unknown error occurred
while backing up.”** (`client/src/components/BackupStatusBanner.tsx:69`). Both Home
presenters (`HomeModel.swift:36`, `HomeRules.kt:18`) use that copy rather than rendering
persisted provider/type text, covering old `ClientError` records as well.

### Regression and full verification

Before fixes: iOS **26 definitions / 3 suites, 5 failed definitions / 20 assertion
issues**. The sleeping-background, queued-reentry and post-sync scene-change tests
failed, as did cancellation and raw-type-copy cases. Only injectable test seams were
added to the old runner body for this run. Android **25 tests / 3 suites, 4 failed**;
a separate overlapping-callback pause/resume probe also failed against old admission.

After fixes: iOS **42 definitions / 5 suites passed**, including cancellation forms,
background-budget independence, quote-time admission loss, push and sync settlement.
Android **40 JVM tests / 5 suites passed**, including the overlapping callback probe.
The final review also caught a new timer-lifetime edge: a timer created during
reentry inherited the activation budget, which ended before its first tick. A
permanent regression failed with zero ticks; timer creation now clears only that
short inherited token, while each tick keeps fresh scene admission and inherited
deadline checks. One earlier full iOS attempt was interrupted for this correction
before the final source/test change; it is not counted as a completed full run.

Full iOS: **810 definitions / 164 suites; 784 passed / 26 skipped / zero failed; 1,619 expanded passes / 26 skips; 546.064 seconds of Swift Testing execution**. Full Android: **3,603 JVM tests / 149 suites, zero failures/errors/skips; regtest debug APK assembly and instrumentation Kotlin compilation passed (1m 37s)**.
The requested Android JVM/APK/instrumentation-compile command ran once after the
last Android source/test change. The completed full NoahTests run followed the
last iOS change, one owned heavy build at a time. During an earlier final attempt,
the task's `/private/tmp` logs/result paths disappeared while Xcode was running;
it exited 73 with no verifiable bundle. The completed rerun therefore wrote its
log/result into this worktree's private Git evidence directory. Focused counts
above were recorded in the tool transcript before their raw temporary files
also disappeared; Android's original Gradle XML results remained intact. XcodeGen ran first; Xcode used arm64/ONLY_ACTIVE_ARCH, two jobs,
normal signing, no parallel tests and `/private/tmp/noah-wake-fix-dd`.

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
xcodegen generate --spec apps/ios/project.yml
task_evidence="$(git rev-parse --absolute-git-dir)/noah-wake-evidence"
xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=B4D4679A-3200-4D35-AEBC-C1482DCA6F6F' \
  -derivedDataPath /private/tmp/noah-wake-fix-dd \
  -resultBundlePath "$task_evidence/ios-full.xcresult" \
  -disableAutomaticPackageResolution -skipPackageUpdates -collect-test-diagnostics never \
  -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO \
  -only-testing:NoahTests test
```

Translation generation and `--check` are clean; all **7 translation-tool tests**
passed. Existing RN copy was reused, so generated catalogs have no byte changes.
All commits ran the normal hook, including RN checks/148 unit tests/TypeScript and
Rust formatting, and have the requested Claude co-author trailer.

Implementation commits: `493f6bf9` (iOS wake), `cad7424f`
(Android wake), `a4e061b3` (iOS backup), `81380496` (Android backup), `d1ebf7df` (iOS timer-lifetime follow-up).

Owned simulator: **Noah Wake Fix 1020**, iPhone 17 / iOS 27.0,
`B4D4679A-3200-4D35-AEBC-C1482DCA6F6F`. The Device panel rejected that new UDID twice
as unknown; Xcode/simctl targeted only this owned device. Cleanup completed: the exact name/UDID/device type and **Shutdown** state were
confirmed before deletion, then absence verified. Removed owned DerivedData and
verified all **seven** owned result paths absent (earlier temporary bundles were
already missing after the output-loss incident). The protected final summary,
test tree, log, Android XML count summary and cleanup receipt remain under
`$(git rev-parse --absolute-git-dir)/noah-wake-evidence`; the bulky protected result
bundle was deleted after export. No owned build process remains; unrelated idle
processes and devices were left alone.
No physical push, funded payment, protected simulator, shared emulator or Docker
was used. Deterministic stage and transport substitutes establish admission and
settlement behavior, not live OS delivery. Hardware reacceptance remains follow-up.
