# Native Android recurring payments — M7

Base: `feat/native-rewrite` at `1aa9a61852c7e06797e729ee90b49dfec802b6d6`.
Kotlin / Compose, API 24 minimum, bark-bolt-ffi **0.4.0**.

## Contract and safety

`RecurringSchedule` runs RN's local Gregorian calendar policy, including DST folds,
gaps, month-end clamping without drift, millisecond preservation, latest-due-only
execution, skipped backlog, finite schedules and exponential retry backoff. The
5,000-occurrence scan bound stops extreme backlog for manual review.

`RecurringStore` uses RN's **noah-wallet-storage** MMKV instance,
**recurring-payment-storage** key and version-0 Zustand envelope. Unknown envelope,
state and schedule fields survive mutations. Invalid/future stores preserve their
bytes and stop reads/writes; a failed write latches automation off until restart.
Native spending approval binds fingerprint + network + a local instance UUID in
the wallet datadir. Android backup/transfer rules already exclude this directory;
Noah's encrypted backups contain only SQLite. Same-seed replacement gets a new
instance. Creation/restore seals unbound legacy policies to an unverified prior
wallet before installing a replacement. Foreign policies stay visible/cancellable.

The independently fsynced, atomic occurrence journal precedes the MMKV in-flight
marker and Send journal. Consumed timestamp watermarks span policy revisions and
migrate before legacy attempts can be overwritten. Android additionally retains
consumed indices per revision: timezone changes can move a previously paid index
to a later timestamp, so timestamp-only watermarks cannot prevent its replay after
MMKV rollback. The regression fails without this index watermark.

All money goes through the existing Send controller/journal and session. Its
exclusive recurring reservation drains counted calls, excludes new unrelated
activity/close/replacement, and outlives caller cancellation. The final guard runs
immediately before the SDK creating call, after checkpoint inspection: current
policy/marker, wallet identity/instance, persistent automation-stop generation,
suspend/restore/delete state, current-clock due occurrence/backoff, monotonic push
budget and captured fee policy must all still match. Pre-existing Lightning
checkpoints require review. Ambiguous outcomes retain both locks and never retry
automatically. Pauses/cancellation during settlement remain paused/removed.

Suspend/delete rotate the durable stop generation before waiting for the lifecycle
gate; failed teardown cannot revive pending approval. A generation-write failure
latches automation off. Lock order remains lifecycle gate → RestoreRecovery →
session reservation; callers already holding the non-reentrant gate pass
`walletMutationHeld = true`. Restore recovery, new creation and replacement use
the same gates. An uncertain outcome can only resume after history checking and a
two-step destructive warning; exact warning/confirmation and legacy ownership
are durably audited before locks clear. Resume skips already-due occurrences.

## Fee policy and attended approval

All **24** unchanged `recurring-fee-cap.json` cases are consumed through
`SharedFixtures`, along with 1,260 calendar, 36 policy and three zone-change cases.
The RN storage document is also loaded through `SharedFixtures`.

The quote captures `ArkInfo.feeSchedule.lightningSend` (min/base/highest ppm); the
pre-submit guard reuses those captured values. Missing ArkInfo uses min 20, base 0,
max 5,000 ppm. Arithmetic saturates safely in `Long`:

- `F(a) = max(min, base + ceil(a × maxPpm / 1,000,000))`.
- `cap = min(2 × F(a), max(200, floor(a / 50)))`.
- Lightning auto-pays only if both quote and F(a) fit the cap; Ark requires zero fee.
- Otherwise the occurrence becomes **Needs approval**, with no occurrence/Send
  marker, consumption, uncertainty or automatic retry, and a notification.
- **Review and pay manually** uses the exact quote in the same Compose Send
  confirmation component as attended Send, retaining recurring policy/journal
  guards. A review cannot approve a subsequently edited/resumed/paused policy.

Deliberately not ported from round-1 iOS: the old max(1%, 200 sats) fee policy and
blanket Lightning approval gate, superseded by the user's October 20 decision.
Android uses WorkManager reminders and a time/timezone broadcast receiver instead
of iOS notification-daemon/calendar callbacks. LNURL with a user note follows
Android attended Send's Lightning route so the note reaches the recipient; the
iOS reference unconditionally prefers an Ark route even with a note.

## Triggers and UI

Settings → Recurring payments provides create/edit, native date/time pickers,
three-occurrence preview, weekly/monthly/custom intervals, end date/count,
pause/resume/cancel, last attempt, needs-attention and needs-approval controls.
Editing starts a new explicitly approved revision while retaining visible runs.
Amounts use the numeric system keyboard.

Foreground and generated `recurring_payment_due` payloads join the process-owned
executor through `AppServices.ensureWalletOpen`. Push `due_count` is advisory;
local policy alone determines the payment. The independent 25-second push deadline
returns without waiting for cancellation-shielded settlement and revokes any new
submission. Already-submitted money still settles once.

Wake sync uses the generated authenticated client with serialized replacement
payloads containing only opaque schedule IDs and unix due seconds. Edits during a
request force another replacement; failures retain the durable pending retry flag.
Reminders/results use the existing notification channel and honor
POST_NOTIFICATIONS. WorkManager reminders recheck the current due date, policy,
suspension and wallet binding before displaying. Tapping opens the schedule screen.

## Verification and live evidence

The required full check ran **once**, without exclusions, after the final production
change: **2,725 JUnit cases, 67 suites, zero failures/errors/skips**. This includes
**1,369 recurring cases** and **560 Send cases**. APK assembly passed in the same
**58-second** invocation. Targeted checks ran while implementing; one targeted
zone-rollback regression deliberately failed before its fix. A prior PushJobs test
still expected the removed log-only placeholder and was updated to expect opening
and execution. The first race-test compile required an explicit parameter-array
type, corrected before the passing runs.

```sh
NIX_CONFIG='eval-cache = false' nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

The environment-only Nix option avoids the shared evaluation-cache SQLite lock;
it changes no project or shared cache configuration. All Gradle invocations used
`--max-workers=2`. Android-test APK assembly also passed. Raw logs remain under
`/tmp/noah-android-recurring-*`; portable summaries/receipts are in `evidence/`.

The funded invocation passed **1 test, zero failures/skips**, 19.064 seconds;
production-lifecycle cleanup passed **1 test, zero failures/skips**, 5.226 seconds.
Together with storage, **four distinct instrumentation tests passed**. The separate
pre-schedule setup failure below remains documented and is not counted as passing.

Live receipts: [sender and Ark peer](evidence/live.json),
[independent CLN receipt](evidence/cln-receipt.json),
[full JUnit summary](evidence/junit-summary.json).
Exactly **one 1,000-sat Ark send**, sender movement **2**, zero fee, arrived as
peer movement **1**, +1,000 sats. Exactly **one automatic 1,000-sat BOLT12 send**,
sender movement **3**, paid **79 sats** in fees: quote 79, captured worst-case
schedule fee **83**, cap **166**. CLN independently recorded **paid**, **1,000,000
msat received**, pay index **9**, hash
`2c0c5e7754b0b45833b2c9f87b8b1e359e0df056f17c0e4848ad801aaa3fd064`.
Push/foreground races, repeat, pause and cancel left the sender at exactly two
new payments total. Both durable occurrences were consumed, Send unlocked and
the server pending flag clear. No attended approval was used for Lightning.

Real Device-panel inspection covered the populated list, editor, preview, numeric
Gboard, native notification intent routing, saving an edited future policy and
cancelling it back to the empty list. The finish reviewer marked the scoped UI
**Ship** after the end-date display correction; the documentation check confirmed
an extension of existing controls/tokens with no new design artifacts. Captures:
[list](evidence/list.png), [editor](evidence/editor.png),
[preview/end date](evidence/preview.png), [numeric keyboard](evidence/numeric-keyboard.png).
TalkBack/large-font/dark/tablet/landscape checks were not run. Shared amber-text
contrast and status-bar clipping are inherited theme/shell drift, not changed here.

Switching input methods during the first keyboard inspection produced an emulator
ANR. Its captured main stack was waiting in
`HardwareRenderer.syncAndDrawFrame`, with RenderThread in framework frame-metrics
reporting; no recurring code was on the blocked stack. After reinstall/restart,
the real numeric keyboard, Done, scroll and Save worked and a clean screenshot was
captured. This is a test-environment limitation, not proof of an app-wide fix.

Dedicated AVD: **NoahRecurringM7Sol6506**, Pixel 6 profile / Android 15 API 35 ARM64,
explicit **emulator-5590**. The Device panel and agent-device use that exact serial
and the host/session flags returned by `device_open`. Protected emulators, the
physical Pixel 6 and iOS simulators were untouched.

Two real storage instrumentation tests passed (**2 tests, zero failures**, 3.596 s):
RN MMKV envelope + persisted in-flight marker through a fresh reader, and a real
SDK-created wallet's instance change/refusal during restore/deletion markers.

The first live invocation stopped at server registration before schedules existed:
port 3100 from prior Send evidence was unavailable; the existing server is on 3000.
The funded wallet was retained. Before resuming setup, the test requires an owned
boarding marker, an empty recurring store, no Send/occurrence journals (including
Send's backup), and no outgoing SDK sends. It neither regenerates the main seed
nor funds/boards again. This setup failure is not counted as a passing funded test.

Funding: **100,000 onchain sats once**, transaction
`05d98938c1370b9fb88cfbe33462093d051cf4dd6ea02c62cec631542fe51411`.
One funding block and the server-required three boarding confirmation blocks were
mined through `just bcli`; **30,000 sats** boarded through production M6 APIs.
No blocks are needed for the subsequent recurring payments. A fresh second native
wallet in the owned emulator is the Ark recipient; local CLN supplies a reusable
BOLT12 offer. A `just bark --help` probe earlier created a transient CLI container;
that exceeded the Docker restriction and was not used again. All remaining stack
operations use only the allowed `just bcli` / `just cln` wrappers; no existing
container was stopped, restarted, reconfigured or reset.

Reproduction: on an empty dedicated emulator, build the regtest APK and Android-test
APK, then select only `com.noahwallet.recurring.RecurringLiveTest` with
`noahRecurringLive=disposable-emulator` and `offerRecipient=<fresh local CLN offer>`.
The test publishes `files/recurring-live-funding-address.txt`; fund once with
100,000 onchain sats and confirm it. Wait for `recurring-live-board-ready.txt`, then
mine exactly the reported boarding confirmations. Non-secret results are written
to `files/recurring-live-evidence.json`. Never rerun a failed payment before
inspecting journals and history. `resumeSetupOnly=owned-wallet-with-no-send-journals`
is only for the documented pre-schedule setup failure and refuses any send journal,
policy or outgoing movement. Normal runs require an empty wallet.

No physical FCM/UnifiedPush delivery, timed day-before/overdue notification, real
power loss between journal boundaries, external LNURL provider, funded RN upgrade,
or API-24 device is claimed. Push is exercised by invoking production PushJobs,
not by a remote transport. Filesystem failures, deadline/clock jumps, policy
rollback and lifecycle races use deterministic tests.

Cleanup completed: cancelled the future inspection policy through Compose, then
used the production lifecycle to deregister/delete the wallet and its saved seed.
Disabled only the owned CLN test offer. Immediately before shutdown,
`adb -s emulator-5590 emu avd name` confirmed **NoahRecurringM7Sol6506**. Closed it
through the Device panel, verified the serial was absent and its QEMU process had
exited, then deleted only that AVD; both its `.avd` directory and `.ini` are absent.
[Cleanup evidence](evidence/device-cleanup.json) records these checks. No build
output was created outside the worktree; logs/public summaries remain under the
`/tmp/noah-android-recurring-*` prefix. Temporary runtime offer/mining-address files
were removed. Protected devices and existing Docker service configuration/lifecycle were not changed.

## Implementation map

Each location is relative to `apps/android/app/src/`:

1. Schedule math/policy: `main/java/com/noahwallet/recurring/RecurringSchedule.kt:38`.
2. RN storage/migration/binding: `main/java/com/noahwallet/recurring/RecurringStore.kt:15`;
   real binding at `RecurringStore.kt:67`.
3. Durable journal/executor: `main/java/com/noahwallet/recurring/RecurringJournal.kt:57`,
   `RecurringExecutor.kt:60`; exclusive reservation in
   `main/java/com/noahwallet/services/wallet/WalletSession.kt:143`; stop generation
   in `main/java/com/noahwallet/services/AppServices.kt:107`.
4. Unattended fee cap: `main/java/com/noahwallet/recurring/RecurringFeePolicy.kt:11`;
   quote capture in `main/java/com/noahwallet/send/SdkSendWallet.kt:24`.
5. Screens: `main/java/com/noahwallet/recurring/RecurringPaymentsScreen.kt:20` and
   `RecurringPaymentEditorScreen.kt:21`; Settings destination/row in
   `main/java/com/noahwallet/ui/settings/SettingsScreen.kt:32`.
6. Triggers/deadline/notifications: `main/java/com/noahwallet/recurring/RecurringRuntime.kt:44`,
   `RecurringPushDeadline.kt:8`, `RecurringTimeReceiver.kt:7`,
   `RecurringNotifications.kt:18`; push routing in
   `main/java/com/noahwallet/services/push/PushJobs.kt:70`.
7. Server wake synchronization: `main/java/com/noahwallet/recurring/RecurringExecutor.kt:222`.
8. JUnit coverage: `test/java/com/noahwallet/recurring/RecurringFixtureTest.kt:13`,
   `RecurringRaceTest.kt:11`, `RecurringExecutorTest.kt:87`, plus store/journal/deadline
   and real session/lifecycle regressions.
9. Live/storage acceptance: `androidTest/java/com/noahwallet/recurring/RecurringLiveTest.kt:22`
   and `RecurringStorageDeviceTest.kt:16`; portable evidence in this directory.
