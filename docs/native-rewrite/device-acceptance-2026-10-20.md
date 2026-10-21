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

### Round-2 wake handoff and automatic backup lifetime

Implementation base: `249cb85f`, checkout `t3/native-wake-backup-races`.
The review probes in `/private/tmp/noah-wake-review-r1-evidence` are now permanent
regressions. No RN, server, log-privacy, layout, physical-device or Docker changes.

Both executors previously returned after joining a foreground execution, even
when scene deactivation/pause revoked that execution during its quote and no
payment was submitted. The joining push therefore lost its independent admission;
iOS could still return `.newData` with zero payments.
`RecurringExecutor.swift:77` and `RecurringExecutor.kt:75` now serialize distinct
budgets, recheck the waiting caller's admission, and reread due work under that
caller's context. Same-budget callers still coalesce; legacy unbudgeted iOS joins
only drain settlement without extending an expired worker's admission. The iOS
worker clears its slot before resuming waiters so an earlier caller cannot clear
a newer flight.
Consumed/settlement evidence, ambiguous-outcome stops, policy/fee checks and lock
order (background access → lifecycle gate → RestoreRecovery → session reservation)
are unchanged; no gate is reentrant.

iOS `AppModel.swift:413` creates the wallet-change backup observer with a detached,
MainActor-isolated, Sendable task. `BackupCoordinator.swift:72,231,250` likewise
detaches immediate, debounced and retry tasks. These automatic lifetimes no longer
inherit the activation token revoked when foreground reentry finishes. Explicit
`flush` passes still inherit push/BGTask submission budgets and deadlines;
eligibility, cancellation, single-flight ownership and settlement are retained.
Android has no analogous inherited-token problem: `AppServices.kt:40,62` launches
the observer from its own supervisor scope, and `BackupCoordinator.kt:24,86,127`
creates passes and timers from an independent IO supervisor scope. No Android
backup production change was needed.

`RecurringWakeHandoffTests` / `RecurringWakeHandoffTest` hold the production
executor's quote or submitted payment, join two wakes, then revoke foreground
admission. Four cases per platform cover revoked foreground quotes, expired wakes,
successful settlement and ambiguous outcomes. Admitted wakes pay/consume once;
expired wakes pay zero (iOS returns `.failed`); uncertainty stays locked and later
wakes cannot replay either consumed or ambiguous occurrences.
`BackupCoordinatorTests` adds immediate/debounced passes after reentry, a later
request from an observer retaining an expired activation, an automatic retry after
foreground flush, and explicit revoked-background-flush admission.

Before production fixes: Android **4 cases, 1 failed**, with zero payments in the
join-then-revoke case; iOS **23 definitions / 2 suites, 4 failed definitions / 12
assertion issues**, reproducing both findings. After fixes: focused Android **64
tests / 6 suites**, final focused iOS **90 definitions / 7 suites**, all passed with zero
failures or skips. An initial focused iOS build caught a Swift 6 sending-closure
diagnostic; explicitly marking the MainActor observer closure Sendable corrected
it before the passing focused run. The first full iOS attempt was interrupted when
an existing unbudgeted settlement-drain caller started another quote. A new bounded
regression failed with **three assertion issues** before the correction. Handoff
now requires an explicit independent budget, and the older expired-push fixture
fails promptly if a second quote is attempted. The final focused run includes the
entire recurring executor suite; full verification follows the last correction.

Full Android ran once after the final Android source/test changes: **3,608 JVM tests / 150
suites, zero failures/errors/skips**, regtest debug APK assembly and instrumentation
Kotlin compilation passed in the same **2m 38s** invocation:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Implementation commits: `85c77497` (iOS handoff), `da08d0a1` (Android handoff),
`7f403ab5` (iOS automatic backups), `10681151` (iOS unbudgeted-drain compatibility).

The completed full iOS run followed the last source/test correction: **817
definitions / 165 suites; 791 passed / 26 opt-in skipped / zero failed; 1,630
expanded passes / 26 skips**, **438.441 seconds** of Swift Testing execution.
The export counts a passing test with a runtime-warning child as a pass; counting
only childless test nodes would omit it. The one runtime warning is AVAudioSession
synchronous activation in unchanged `StoryPlaybackTests.swift:17`.
XcodeGen generated the project first; Xcode used normal signing, arm64,
ONLY_ACTIVE_ARCH, two jobs, no parallel tests and the requested DerivedData path:

```sh
/opt/homebrew/bin/xcodegen generate --spec apps/ios/project.yml
task_evidence="$(git rev-parse --absolute-git-dir)/noah-wake-r2-evidence"
nix develop --command xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=58755C07-3BA6-423D-ACDB-CA837571EC59' \
  -derivedDataPath /private/tmp/noah-wake-r2-dd \
  -resultBundlePath "$task_evidence/ios-full-final.xcresult" \
  -disableAutomaticPackageResolution -skipPackageUpdates -collect-test-diagnostics never \
  -jobs 2 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO \
  -only-testing:NoahTests test
```

Owned native builds ran sequentially. Translation `--check` is clean and all
**seven translation-tool tests** passed. Each commit ran the normal hook (including
RN's 148 unit tests, TypeScript and Rust formatting), with the requested Claude
co-author trailer; hooks were not bypassed.

Owned simulator **Noah Wake R2 1020**, iPhone 17 / iOS 27.0,
`58755C07-3BA6-423D-ACDB-CA837571EC59`, was rejected twice by the Device panel as
unknown, so Xcode/simctl targeted only that UDID. Cleanup completed: exact UDID,
name, device type and **Shutdown** state were confirmed before deletion, then
absence verified. Removed `/private/tmp/noah-wake-r2-dd` and all **seven** owned
result bundles, with absence checked. No owned build process remains. Protected
devices, physical devices, shared emulators and Docker were not used.
Summaries, test trees, logs, Android XML/count evidence and `cleanup.json` remain
under `$(git rev-parse --absolute-git-dir)/noah-wake-r2-evidence`.
These deterministic regressions do not establish live OS push delivery or funded
payments; hardware reacceptance remains follow-up.

### Round-3 Android cleanup and independent wake failure handoff

Implementation base: `02f589a9`, checkout `t3/android-recurring-wake-cleanup`.
The supplied `/private/tmp/noah-wake-review-r2-WakeRoundTwoReviewProbeTest.kt`
is now permanent coverage in `RecurringWakeCleanupTest.kt`, without its source-line
stack-trace dependency. The test revokes foreground admission during cleanup's
final asynchronous policy read, after one payment has settled, and adds a second
due schedule while an independent push is already joining that worker.

Previously `RecurringExecutor.kt:231` threw from cleanup's final `requireStage`;
the join at line 81 propagated that exception before checking the push's budget.
The permanent regression reproduced **one payment, second paid count zero**, and
`IllegalStateException: Recurring background work stopped.` before the fix.
`RecurringExecutor.kt:232` now checks admission without throwing before notifying.
Cleanup bookkeeping and already-submitted settlement can finish after revocation.
The join at `RecurringExecutor.kt:81` captures the predecessor result and checks
the waiting coroutine's own cancellation. Distinct budgets then recheck admission
and durable due work even after predecessor failure or cancellation; same-budget
callers retain the predecessor result. Each caller still performs at most one own
execution pass; no payment retry or re-evaluation loop was added.

The five-test suite also injects failed and cancelled predecessors, cancels a
waiting caller with a live budget, and holds a submitted payment while three
pushes join before/after foreground revocation. Before the fix: **5 tests, 3
failed, 2 passed**. Successful handoffs must consume and release both occurrences
with one paid count/history row each, and a subsequent wake must submit nothing.
Existing ambiguous-outcome, durable settlement and consumed checks are unchanged,
as is lock order: background access → lifecycle gate → RestoreRecovery → session
reservation. No gate is reentrant.

iOS has no equivalent exception path: `RecurringExecutor.swift:33` stores a
`Task<Void, Never>`, `:81` awaits its nonthrowing value, and `:306-307` performs
cleanup reload/notification without a throwing admission check. No iOS source or
test change was needed, so its full suite was not rerun.

Full Android ran once after the last source/test change: **3,615 JVM tests / 151
suites, zero failures/errors/skips**, including all **five** new regressions.
Regtest debug APK assembly and instrumentation Kotlin compilation passed in the
same **3m 22s** Gradle invocation:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Baseline/final logs, final JUnit XML and count summary are retained under
`$(git rev-parse --absolute-git-dir)/noah-wake-r3-evidence`.
Removed this worktree's Android `app/build`, `build`, `.gradle` and `.kotlin`
directories after exporting results; `cleanup.json` records their absence.
No simulator/emulator was created or used, and no owned native build remains.
No RN, server, log-privacy, iOS layout, physical-device or Docker changes.
The tests use deterministic transport/stage substitutes; live OS push delivery
and funded hardware reacceptance remain unverified.

Fix commit: `cd744107`. Both final commits use the requested Claude co-author
trailer and normal pre-commit hook (RN lint/compiler checks, **148 unit tests**,
TypeScript and Rust formatting). This fresh worktree initially lacked Husky's
generated launcher; it was installed and the fix commit amended through the hook.
One hook attempt failed on a missing local `noah-tools` dependency link; restoring
that workspace link fixed the environment without changing RN sources.

### Round-4 admission after policy reads and unbudgeted joins

Implementation base: `e6226e49`, checkout `t3/android-recurring-revocation`.
Android `RecurringExecutor.kt:170` rechecks the budget, caller cancellation and
worker cancellation after the asynchronous schedule read, immediately before
quoting. `:175` checks again after the quoted-policy read, before fee review;
`:190` checks inside marker admission after its current-policy read, before either
durable marker write. The checks stop preparation without recording a retryable
failure or leaving a fresh marker when admission was lost during these reads.

iOS had the same read boundaries. `RecurringExecutor.swift:215-216`, `:227-228`
and `:253-254` now check inherited foreground admission, submission budget,
deadline and task cancellation before quote, fee review and marker writes.
Its existing `:82` unbudgeted-join drain and permanent
`RecurringWakeHandoffTests.unbudgetedJoinOnlyDrainsRevokedWork` already cover the
second finding; no iOS join change was needed.

The await audit also checked quote completion, marker completion, queued Send
reservation, payment submission and settlement. Quote-completion checks already
existed. Android's Send guard runs before preparation and again before payment;
iOS's native `withOpenWallet` checks the inherited budget before preparation,
and guarded Send checks again before payment. Submitted-payment settlement,
recovery, cleanup and round-3's nonthrowing cleanup notification remain unchanged.
Independent budgets still re-evaluate after predecessor failure/cancellation;
cancelled waiters stop. Exactly-once consumption/history, ambiguous-outcome stops,
storage-failure latching and lock order (background access → lifecycle gate →
RestoreRecovery → session reservation) remain intact; no gate is reentrant.

Android `RecurringExecutor.kt:75-90` now represents an omitted budget as `null`:
joining callers only drain the existing flight, and a new pass after joining
requires an explicit independent budget. Standalone omitted-budget calls retain
their previous behavior. Production callers already supply explicit budgets.

Permanent Android regressions are `RecurringReadAdmissionTest` (three tests:
revocation and caller cancellation at every prequote policy read, plus all four
quoted-policy/marker reads) and `RecurringUnbudgetedJoinTest` (one test). All
**four failed before** the fixes and pass afterward; the omitted-budget probe
previously submitted one payment. After draining, a later explicitly admitted wake
still pays and records one occurrence/history row. iOS
`RecurringReadAdmissionTests` failed **one definition / all three argument cases /
five assertion issues** before the fix and now passes. Focused verification:
Android **42 tests / 10 suites**, iOS **16 definitions / four suites**, all passed.

Fix commits: `f320c5ca` (both platforms' policy-read admission), `79adea54`
(Android unbudgeted drain). Each used the normal pre-commit hook, including RN's
**148 unit tests**, TypeScript and Rust formatting, and carries the requested
Claude co-author trailer. This fresh checkout needed dependency links and Husky's
generated launcher restored; no RN source changed and no hook was bypassed.

Full NoahTests after the last iOS source/test change: **835 definitions / 170
suites; 808 passed / 27 skipped / zero failed; 1,656 expanded passes / 27 skips**,
**334.991 seconds** of Swift Testing execution. The unchanged
`StoryPlaybackTests.swift` synchronous AVAudioSession warning remains. XcodeGen
ran first; Xcode used normal signing, arm64/ONLY_ACTIVE_ARCH, two jobs, no parallel
tests and `/private/tmp/noah-wake-r4-dd`, targeting only the owned **Noah Wake R4
1020**, iPhone 17 / iOS 27.0, `D2CD16AE-5F68-4642-AD10-A29669DCDA09`.
The first Device-panel open missed the new simulator; after boot, the second open
succeeded. After full verification, the panel shut it down; exact name, UDID,
device type and Shutdown state were checked before deletion, then absence verified.
DerivedData and all **three** exported result bundles were deleted and verified
absent. Logs, summaries, test trees and `cleanup.json` remain under
`$(git rev-parse --absolute-git-dir)/noah-wake-r4-evidence`.

Full Android ran once after the last source/test change: **3,625 JVM tests / 154
suites, zero failures/errors/skips**. Regtest debug APK assembly and instrumentation
Kotlin compilation passed in the same **2m 10s** invocation:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Both platforms' native builds ran sequentially. Final Android JUnit XML and count
summaries are retained beside the iOS exports. No owned build process remains;
unrelated idle daemons were left alone. No Android device, protected simulator,
physical device or Docker was used. No server, RN source, log-privacy or layout
file changed. These deterministic regressions establish admission and settlement
behavior, not live OS push delivery or funded hardware acceptance.
