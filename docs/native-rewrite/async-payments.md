# Background payments: design

**Status:** draft for review, revised after review on 2026-10-22. Design only; no app or server code changes.

**Scope:**
- Lightning sends that outlast the screen.
- On-chain sends and receives waiting for blocks.

**Platforms:** iOS (iOS 18+) and Android (minSdk 24, targetSdk 37).

**Fits:** the in-app "Still sending" result and block row in [design/send-receive.md](design/send-receive.md).

**Path prefixes:**
- `B/`: bark 0.8.0 (`~/.cargo/git/checkouts/bark-f3369c4cebbcdc63/959a489`). `pay.rs` alone means `B/bark/src/actions/lightning/pay.rs`.
- `F/`: bark-bolt-ffi 0.5.0.
- `I/`: `apps/ios/Noah`.
- `A/`: `apps/android/app/src/main/java/com/noahwallet`.

## Recommendation

1. **Hand the payment to Ark, then let go.**
   - Pay Lightning with `wait: false` and `retry_for = 60 s`.
   - From `PaymentInitiated` on, the Ark server pays whether or not Noah runs; the app only learns and records the result.
   - Background code never calls `pay*`.
2. **Track each attempt until bark proves the outcome.** A durable payment watch is bound to one confirmed attempt and its own checkpoint and movement. One serialized owner settles it. Pushes and timers only ask for a check.
3. **The server nudges a closed app** with a silent, normal-priority `lightning_send_finished` push. It already streams each user's bark mailbox but ignores `LightningSendFinished`.
4. **iOS:** a Live Activity started at hold-to-send, updated whenever the app runs. No `BGContinuedProcessingTask`. Raw APNs Live Activity pushes are optional, last.
5. **Android:** a `shortService` foreground service for up to 2 minutes, with an ongoing notification (Android 16 `ProgressStyle` Live Update, with a fallback below). Pushes and WorkManager finish slower sends.
6. **On-chain:** the block row outside the app, refreshed when the app gets time. No long-running work.
7. **bark:** three small fixes. Until they land, Noah shows "Still sending" rather than guess.

## What happens today

**bark:**

| Fact | Source |
| --- | --- |
| A Lightning send is a persisted checkpoint: `Start → InputsLocked → HtlcReceived → PaymentInitiated`, then settle, or `RevocableHtlcs → RevocationStuck`. | `pay.rs:293-310` |
| The server caps the client's `retry_for`. A default captaind uses 60 s when the client sends none, with a 5 min maximum. xpay "will keep finding routes and retrying" until then ([CLN](https://docs.corelightning.org/reference/xpay)), but doesn't release an HTLC already stuck in flight. | `pay.rs:564-583`; `B/server/src/ln/mod.rs:299-303`; `B/server/captaind.default.toml:155,158` |
| Bark polls every 2 s or settles from the mailbox. | `pay.rs:331`; `B/bark/src/mailbox.rs:611-668` |
| **Revoking doesn't mean failed.** Bark revokes when the server reports failure, but also when a payment is still pending, or a step keeps failing, within 144 blocks of HTLC expiry: about 19 h in. | `pay.rs:162-166,215-223`; `B/bark/src/config.rs:268` |
| The server refuses to revoke an in-flight payment until expiry (258 blocks, about 43 h), and refuses a paid one. Each refusal parks bark in `RevocationStuck`, which retries on every sync. | `B/server/src/ln/mod.rs:496,514-527`; `pay.rs:263-283` |
| **Late success is lost.** Once revoking, bark never asks for the payment status again, and the mailbox ignores a preimage outside `PaymentInitiated`. A late success stays stuck with its HTLCs locked. | `pay.rs:177-181`; `mailbox.rs:638-648` |
| **An error can leave a checkpoint.** `pay*` saves it before contacting the server; with `wait: false` a transient failure returns an error and keeps it. | `B/bark/src/actions/mod.rs:647-654` |
| **One movement per payment hash.** It is keyed by `ln_pay.<hash>` under a unique index and never unlinked, so re-paying an invoice after a failure reuses the old movement, still `Failed`. | `pay.rs:403-427`; `B/bark/src/persist/sqlite/mod.rs:132-154`; migration 39 |
| `drive_action` holds a per-send lock; mailbox settlement with a preimage doesn't. The foreground daemon reads the mailbox stream continuously and runs `wallet.sync()`, which drives pending sends too. | `B/bark/src/actions/mod.rs:540-553`; `B/bark/src/lightning/pay.rs:167-181`; `B/bark/src/daemon/mod.rs:128-150,281-400`; `B/bark/src/lib.rs:2014-2050` |
| `wait: true` sleeps through parks and retries until done, so a stuck send blocks for hours. | `B/bark/src/actions/mod.rs:656-680` |
| `check_lightning_payment(hash, false)` drives an existing checkpoint to its next park (at most one status poll) and never creates a payment. A resumed send keeps its original `retry_for`. | `B/bark/src/lightning/pay.rs:152-163,376-391` |

**Apps:**

| Fact | Source |
| --- | --- |
| Both apps pay with `wait: true`. | `I/Services/Wallet/WalletSession+Send.swift:154,161`; `A/send/SdkSendWallet.kt:124,126` |
| Both write a journal marker before any SDK call; reconciliation only advances existing checkpoints. | `I/Features/Send/SendPayment.swift:318-322`; `A/send/SendController.kt:113-122`; `I/Features/Send/SendLightningCheckpoint.swift:56` |
| A pending send shows "Payment may have been sent" and keeps Send locked. Done can't clear it; only the audited override can, by deleting the journal. | `SendPayment.swift:197,463-490`; `SendController.kt:27,224-243` |
| Both allow another attempt on an invoice whose last attempt failed. | `WalletSession+Send.swift:387`; `A/send/SendCheckpoint.kt:55-58` |
| An uncertain recurring occurrence marks its schedule `needs_attention`, and a later success keeps that status. | `I/Features/Recurring/RecurringExecutor.swift:315-330,362-367`; `A/recurring/RecurringExecutor.kt:239-250` |
| iOS suspends seconds after backgrounding, with only a log-drain task. BGTasks run at 15 min / 6 h earliest with 25 s budgets. | `I/App/AppDelegate.swift:50-60`; `BackgroundScheduler.swift:8`; `BackgroundExecution.swift:13` |
| Android has no foreground service. Periodic work and push jobs get 25 s budgets. | `A/services/background/PeriodicWork.kt:14-27`; `A/services/push/PushJobs.kt:30-34` |
| The server pushes Lightning claims and receives from the mailbox; everything else falls through `_ => {}`. Mailbox pushes go to Expo tokens and UnifiedPush endpoints, at high priority. | `server/src/mailbox_worker.rs:737-804`; `server/src/push.rs:382,455` |

## Lightning: `retry_for` and `wait`

**`retry_for = 60 s`, set explicitly, for every send including recurring ones:**
- **Unchanged success rates.** Users already get 60 s from a default captaind; pinning it protects Noah from operator changes.
- **Longer windows** help hard routes, but keep coins locked and delay the honest "failed, it's back, try another way".
- **Not 0** (a single attempt).
- **Revisit with data.** Add anonymous time-to-result telemetry; raise it to 120 s only if sends often succeed after 60 s.
- **No deadline in the UI:** `retry_for` doesn't bound a stuck in-flight HTLC.

**`wait: false`:**
- **Return.** The call returns at `PaymentInitiated` (or with an immediate result), so no wallet call holds the single-writer session for hours.
- **Driving.** Ticks call `checkLightningPayment(hash, wait: false)`. They come from the foreground app (every 2 s for 90 s, then every 30 s), the iOS handoff, the Android service, push jobs, BGTasks, periodic work (`sync()` already drives pending sends), and the next launch or resume.
- **Pending is a state, not an error.** `InProgress` becomes the spec's "Still sending" result. The journal and checkpoint guards still block paying the same invoice twice.

## Payment watch

**Identity and binding.** A watch is created for each confirmed attempt, right after the journal marker and before any SDK call.

| Field | Source |
| --- | --- |
| `attemptID` | A UUID in the journal marker: the watch's identity before any hash or txid exists. |
| owner | Send journal, recurring occurrence (key and `recurringAttemptID`), parked (taken over from Send), or orphan (found in bark with no journal). |
| wallet | Fingerprint and network. Other wallets' watches are ignored. |
| baseline | Movement IDs and pending payment hashes at prepare. The apps already record the IDs (`SendSnapshot.movementIDs`). |
| payment hash | From the invoice, the resolved LNURL invoice, the offer call's result, or reconstruction. |
| movement ID | `LightningSend.movement_id` of this hash's checkpoint. It must be outside the baseline, unless the attempt resumes its own reviewed checkpoint (today's `SendLightningCheckpoint.movementID`). |
| amount, fee bound, `retry_for`, start time | From the reviewed quote. |
| state, highest phase, `revision`, display ids | Written only by the coordinator. |

**Admission.** Noah doesn't start an attempt on a payment hash that already has a finished bark movement; it asks for a new invoice. Bark 0.8 would reuse that movement, so no evidence could tell the attempts apart. LNURL, Lightning addresses and offers fetch a fresh invoice each time, so this only affects re-paying a pasted BOLT11 invoice. Resuming the user's own reviewed checkpoint stays allowed.

**Evidence.** Each tick reads bark for the bound attempt only:

| Wallet evidence for the bound attempt | Watch state | Terminal |
| --- | --- | --- |
| no hash yet, call still running | Locking | no |
| `InProgress`, phase before `PaymentInitiated` | Locking | no |
| `InProgress(PaymentInitiated)` | Paying → Slow → Long (elapsed time) | no |
| `InProgress(RevocableHtlcs)` | Finishing | no |
| `InProgress(RevocationStuck)` | Stuck: active drivers stop; launch and hourly checks continue | no |
| `Paid` + preimage + the bound movement `Successful`, amounts match | Paid | yes |
| no checkpoint + the bound movement `Failed`, effective balance 0, amounts match, no exited VTXOs | Failed (refunded, or never sent) | yes |
| no checkpoint + the bound movement `Failed` with exited VTXOs | Returning on-chain; Exits shows it | yes |
| no checkpoint and no bound movement, or a read error | last state, stale | no |
| any movement in the baseline, or not bound to this attempt | ignored | — |

Today's guards stay: movement ID equality, a sent-to amount equal to the attempt's, and a fee within the quote (`SendLightningCheckpoint.swift:29-36`; `A/send/SendReconciliation.kt:25-35`). The server cosigns a revocation only for a failed payment, or after HTLC expiry without success, and never for a paid one. So a revoked movement is the refund evidence; the revocation phases are not.

**One writer.** One `PaymentWatchCoordinator` per process (a Swift actor; a Kotlin object behind one `Mutex`) owns every watch write, the settlement of watched journals, and every activity, notification and service update.

| Rule | Detail |
| --- | --- |
| Requests | Drivers only call `request(reason)`. Requests coalesce into one running tick and one queued. |
| Wallet access | A tick uses its caller's existing admission (iOS counted call or background gate; Android `withOpen` or `requireBackgroundWallet`). The coordinator's lock, not those paths, serializes decisions, so foreground and background ticks can't interleave. |
| Writes | Compare-and-set on `revision`. Terminal states never change. A tick whose watch revision or wallet generation changed since it started drops its result. |
| Display | Updated only from the committed watch. A terminal watch ends its display once and records that, so a stale tick can't recreate it. |
| bark's own writers | The daemon and mailbox change bark state between ticks; the coordinator only reads bark. If a race inside bark (fix 2) brings a checkpoint back, bark's paid record still wins in `lightning_send_state`. |
| Lifecycle | On suspension, deletion or restore, `WalletLifecycle` closes the coordinator to new ticks, waits for the running tick and every admitted native call, stops the daemon (`stopDaemonWait`, as `I/Services/Wallet/WalletSession.swift:236` does), and only then ends activities, cancels notifications, stops the service and cancels watch work. Deletion then deletes the watches; suspension keeps them. |

**Recovery on launch and resume.**

| Found | Action |
| --- | --- |
| unresolved journal attempt without a watch | Create it from the journal (the app died after the marker). |
| watch without a hash, call not running | Bind the one checkpoint or movement outside the baseline with this offer (`payment_method`) and amount. With none, nothing was created: Failed (never sent). With several: uncertain, never terminal. |
| bark pending send without a watch | Orphan watch: display only, settles no journal. |
| terminal watch, journal still pending | Re-run settlement (idempotent). |

A `pay*` error never means "not sent": the coordinator reads the hash's state, or reconstructs as above, first.

**Lifetime.** Tracking ends only on terminal evidence or wallet deletion; a send can stay pending past HTLC expiry (about 43 h). Display follows each system UI's limits; the in-app result and Activity row always show the truth.

## Send and recurring journals

1. **A `pending` result.** Both controllers get a `pending` state, set only from evidence for the bound attempt. Unlike `uncertain`, it has a checkpoint and an owner. The journal stores it with `attemptID` and the binding.
2. **Done closes the screen, not the journal.** The controllers are process-wide (`SendPaymentController.shared`; Android's process-owned `SendController`), so the attempt stays owned.
3. **Exactly-once settlement.** On terminal evidence the coordinator writes the watch terminal, then calls `controller.settleWatched(attemptID, resolution)`.
   - It applies only if the journal's `attemptID` matches and the state is `pending`.
   - It runs today's `finish`, which writes the journal and the recurring evidence; `RecurringJournal.record` keeps one final outcome per `recurringAttemptID`.
   - The recurring executor's `settle` applies it once per settlement token, as today.
   - Repeating any step is a no-op. If the Send screen is closed, the controller then acknowledges the result itself, so a finished send never blocks the next one.
4. **Another send while one is pending.** Recommended: Send stays locked, as today, with a "Still sending ₿X · View" banner. The audited override ("Send another payment anyway") now parks the attempt instead of deleting it: the watch takes it over, including recurring keys, and is fsynced before the Send journal is cleared. It then settles the same way.
5. **Recurring.**
   - A pending occurrence records a new outcome, `pending`, so the schedule shows "Sending" instead of `needs_attention`.
   - The next occurrence waits, as for an in-flight one today. The start-up "interrupted" check (`RecurringExecutor.swift:139-143`; `RecurringExecutor.kt:117-118`) skips occurrences that have a watch.
   - A success restores `active` only if the schedule's issue is still this occurrence's `pending`. A refund is today's retryable failure.

## iOS

**Live Activity:**
- **Start.** ActivityKit (iOS 16.1+). The app requests it as hold-to-send completes; `NoahWidget` renders it; the app's Info.plist gets `NSSupportsLiveActivities`.
  - Starting needs the foreground; updating and ending work "while it runs in the background" ([Apple](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities)).
  - If activities are off (`areActivitiesEnabled`) or `targetMaximumExceeded`, show a local result notification instead.
- **Stale and end.**
  - Each update sets `staleDate` to now + 3 min; a stale view reads "Open Noah to check".
  - End on terminal evidence with `.after(now + 15 min)`. Ended activities ignore updates, so their final content carries no amount.
  - Limits: active "up to eight hours", plus "up to four additional hours" on the Lock Screen; 4 KB of data (same page).
- **Force quit.** Activities survive it ([Apple](https://developer.apple.com/news/?id=qpqf1gru)); on launch, re-attach with `Activity.activities` and reconcile. A force-quit app gets no silent pushes "until it's launched again" ([Apple](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app)), so its activity goes stale.

**Handoff:**
- **The task.** Wrap the `pay` call and the first polls in `beginBackgroundTask`. Its time is an estimate ("about 30 seconds", [DTS](https://developer.apple.com/forums/thread/85066)), and expiry can come at once.
- **No time.** If it returns `.invalid`, skip background polls; the activity, the push and the next launch carry on.
- **Ending.** One handoff object per send ends the task exactly once, by atomically swapping in `.invalid`, from completion or expiry. The expiration handler stops scheduling polls, then ends the task at once.
- **Native calls.** A running native call is never cancelled or abandoned. Its counted-call admission stays held until Rust returns, perhaps after the app resumes, so the session can't close under it. The checkpoint makes a suspended or killed call safe.

**Not `BGContinuedProcessingTask` (iOS 26.0).** It must start "from the foreground… as a result of a person's action" ([Apple](https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtaskrequest)) and show "accurate progress". Our handoff is shorter than DTS's 30 s minimum ([forum](https://developer.apple.com/forums/thread/796944)) and then only waits. Swiping the app away cancels it ([Apple](https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados)), and on-chain waits have no starting action.

## Android

**Lightning: `shortService`** ([types](https://developer.android.com/develop/background-work/services/fgs/service-types)):
- **The type.** It needs only `FOREGROUND_SERVICE` and runs "about 3 minutes", then `onTimeout()`. On Android 13 and lower there is no timeout, so the app keeps its own cap.
- **Timing.** Start it at the tap, while visible, so no background-start limits apply. Stop it at the result or at `retry_for + 60 s`.
- **Swipe-away** doesn't kill a process with a foreground service (AOSP `ActivityTaskSupervisor`).
- **After the cap.** The ongoing notification remains. A one-time WorkManager check (network constraint) runs at +5 min, then hourly while a Lightning watch is pending, and the push finishes it.
- **Why not `dataSync`.**
  - It is for transfers (upload, download, backup), not waiting on a remote result.
  - Android 15 limits it to 6 h per 24 h when targeting 35+ and bars starting it from `BOOT_COMPLETED` ([15](https://developer.android.com/about/versions/15/behavior-changes-15)).
  - Play needs a declaration and a video ([Play](https://support.google.com/googleplay/android-developer/answer/13392821)), while `shortService` is exempt ([policy](https://support.google.com/googleplay/android-developer/answer/16559646)).
  - A wait past 2 minutes can last 43 h, which only pushes and WorkManager can span anyway.
- **Why not UIDT jobs:** API 34+ "network data transfers" without WorkManager support ([UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt)).

**Notification:**
- **Channel.** A new "Payments in progress" channel at `IMPORTANCE_DEFAULT`, silent; `MIN` would block promotion.
- **API 36+.** `ProgressStyle` with three equal segments (Lock · Pay · Done), an orange-ring coin tracker icon, and `setShortCriticalText` ("Sending", "Paid"; ≤7 chars).
  - Never `setProgressIndeterminate(true)`: it hides segments, points and the tracker.
  - The tracker sits at a fixed point: Locking at the start, every pending state at the start of Pay, Paid at the end. The text carries the pending status.
- **Live Update.** `setRequestPromotedOngoing(true)` with `POST_PROMOTED_NOTIFICATIONS` (API 36.1).
  - Requirements: ongoing, a title, no `RemoteViews`, not colorized ([Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)).
  - It is for things "ongoing, user-initiated, and time-sensitive": Lightning, not block waits.
  - Never repost one the user dismissed.
- **Below 36.** `NotificationCompat` from androidx.core 1.17.0+, declared explicitly (1.18.0 arrives transitively today), with `setProgress(0, 0, true)` and the same text.
- **Dismissal.** Since Android 14, ongoing notifications are dismissible except on the lock screen ([14](https://developer.android.com/about/versions/14/behavior-changes-all)); Activity stays the record.
- **Swipe-away vs force stop.** Swipe-away keeps WorkManager and FCM working on stock Android (some OEMs differ). Force stop drops jobs and FCM messages until the next launch ([FCM](https://firebase.blog/posts/2024/07/understand-fcm-delivery-rates/)); "Restricted" battery use stops services, alarms and jobs ([13](https://developer.android.com/about/versions/13/behavior-changes-all)). The next launch shows the truth.

## On-chain

- **Watched:**
  - outgoing transactions with a txid (on-chain wallet sends, Ark offboard/send-onchain);
  - incoming unconfirmed ones, seen in the foreground on iOS (activities can't start in the background) and in any sync on Android.
- **Data:** `onchain.tipHeight()` and `transactionStatus(txid)` from the wallet's own Esplora. Confirmations = tip − height + 1. Sends follow the spec's "1 of 2" rule; receives finish at one confirmation.
- **Refresh:** in the foreground on tip change or every 30 s (spec); on iOS, BGAppRefresh and push wakes; on Android, one-time WorkManager checks every 10 min while a watch exists. No foreground service and no Live Update, which Google says isn't for "tracking a package".
- **Stop:** at the required confirmations ("Confirmed in block N"). After 24 h unconfirmed, or if the transaction disappears, the system item ends with "Open Noah to check"; the in-app watch continues and reconciliation owns conflicts.

## What the user sees

**Style:**
- Monochrome, with orange only for **you**: your coin's ring and your dot in the "next" block.
- The bolt and blocks use the foreground colour; copy follows the Send spec.

**Layout:**
- **iOS Lock Screen:** title, a muted phase line, and a thin three-step track with your coin at the current step. On-chain shows the mini block row instead: three mined blocks, then a dashed "next" block holding your orange dot.
- **Dynamic Island:**
  - compact: your coin, plus the amount (₿•••• when hidden) or ✓;
  - minimal: your coin;
  - expanded: the Lock Screen layout.
- **Android:** the same title, line and segments.

| State (from evidence) | Title | Line | Track |
| --- | --- | --- | --- |
| Locking (`Start`…`HtlcReceived`) | Sending ₿X | Locking ₿X | step 1 |
| Paying (`PaymentInitiated`, <10 s) | Sending ₿X | Paying over Lightning | step 2 |
| Slow (≥10 s) | Sending ₿X | Still paying. This can take a few minutes. | step 2 |
| Long (> `retry_for` + 30 s) | Still sending ₿X | Taking longer than usual. If it fails, Ark returns ₿X to you. | step 2 |
| Finishing (`RevocableHtlcs`) | Still sending ₿X | Waiting for the final result. If it failed, Ark returns ₿X to you. | step 2 |
| Stuck (`RevocationStuck`) | Still sending ₿X | No final result yet. ₿X stays locked until Ark settles it. Open Noah. | step 2 |
| Paid | Sent | Paid over Lightning | full |
| Failed (refunded or never sent) | Payment failed | It's back in your wallet | grey, coin back at start |
| Stale | unchanged | Open Noah to check | unchanged |
| On-chain waiting | Sending ₿X on-chain / Incoming ₿X | Waiting for block {tip+1} | blocks |
| On-chain confirmed | Sent / Received | Confirmed in block N (· 1 of 2) | blocks |

Terminal rows carry no amount, because an ended Live Activity can't be updated if hide-balance changes later. Result notifications add the amount only while hide-balance is off. Tapping opens the Send result or the Activity row, behind the app lock as usual.

## Safety rules

1. **No double payment.**
   - Background code calls only `checkLightningPayment(wait: false)`, `lightningSendState`, `pendingLightningSends`, `sync`, `transactionStatus` and `tipHeight`; a test fake fails on any other `pay*` call.
   - Today's checkpoint-match and "already in progress" guards stay. A hash with a finished movement is never paid again.
   - Driving a checkpoint that stopped before `PaymentInitiated` (the process died mid-handoff) completes that same confirmed payment, as `sync()` does today; it never starts another. If the server refuses it, bark fails or revokes the send.
   - The override parks a pending attempt; it never deletes its tracking.
2. **No claim without evidence.** Only the terminal evidence rows say "Sent" or "back in your wallet". Pushes, timers, revocation phases and other attempts' movements never do.
3. **Bounded battery use.** One `shortService` per send, at most 2 min. Background checks: Lightning at +5 min, then hourly; on-chain at most every 10 min. Before first unlock the seed can't be read, so watches go stale.
4. **Privacy.**
   - Nothing outside the app shows the recipient, addresses, txids or preimages.
   - **Hide-balance on** (`isHomeBalanceHidden`): no amounts either ("Sending a payment", ₿••••).
   - **Hide-balance off:** amounts show while pending and in result notifications. Android uses `VISIBILITY_PRIVATE` with an amount-free public version. iOS marks amounts `.privacySensitive()`; Apple doesn't document whether Live Activities honour that, so the setting is the guarantee.
   - **Turning it on** updates active activities and ongoing notifications at once. iOS removes delivered result notifications (`removeDeliveredNotifications`); Android reposts them amount-free under the same id, silently.

## Required bark changes

Small changes to bark 0.8, upstream or in a Noah fork; bark-bolt-ffi then pins the new revision.

1. **Late success after revoking starts.**
   - In `RevocableHtlcs` and `RevocationStuck`, check the payment status before asking to revoke. On a valid preimage, settle as `PaymentInitiated` does: paid record, HTLCs spent, movement `Successful`.
   - Do the same when the server refuses because the payment completed (`B/server/src/ln/mod.rs:496,524`).
   - Let the mailbox settle a valid preimage in any phase that holds HTLCs (`mailbox.rs:638-648`).
   - Until then a late success shows as Stuck: slow, but never a false refund.
2. **Mailbox settlement under the send's lock.** `handle_lightning_send_finished` takes `lock_manager.try_lock(ln_pay.<hash>)` and skips when a drive holds it. Otherwise a concurrent `Park` can save a checkpoint that settlement just removed.
3. **One movement per attempt.** When a send action ends, unlink its movement (`action_id = NULL`), so a new attempt on the same hash gets its own movement. Noah can then drop its "new invoice" rule.

## FFI changes (bark-bolt-ffi on bark 0.8.0)

**Required:**
1. **Options.** `LightningSendOptions { wait: bool = false, retry_for_secs: Option<u32> = None }` on `pay_lightning_invoice`, `pay_lightning_offer`, `pay_lightning_address` and `pay_lnurl`.
   - Either `*_with` methods or an `options` parameter in place of `wait` works; the in-progress 0.6.0 tree does the latter. Noah always passes both fields.
   - `None` uses the server default; `0` makes a single attempt; larger values are capped by the server; it is ignored when an existing checkpoint resumes.
2. **New fields on `LightningSend`** (`F/src/types.rs:416`):
   - `phase: LightningSendPhase` (`Start | InputsLocked | HtlcReceived | PaymentInitiated | RevocableHtlcs | RevocationStuck`), 1:1 from `Progress`, keeping bark's names so nobody reads `RevocableHtlcs` as "failed";
   - `retry_for_secs: Option<u32>`;
   - `htlc_expiry_height: u32`;
   - `payment_method: PaymentMethod` (the existing `{ method_type, value }` record), from `original_payment_method`, so an offer attempt can be matched before Noah knows its hash.
3. **Documented contracts**, each with a contract test:

   | Call | Contract |
   | --- | --- |
   | `pay_*`, `wait: false` | Returns `InProgress` (normally at `PaymentInitiated`), `Paid`, or `Unknown` when the payment failed and was refunded inside the call. |
   | `pay_*` error | Either it failed before the server cosigned HTLCs, or a checkpoint remains. Callers check `lightning_send_state` or `pending_lightning_sends` first. |
   | `check_lightning_payment(hash, false)` | Never creates a payment; returns at the next park after at most one status poll. In revocation phases it only tries to revoke, until bark change 1. |
   | `phase` | `RevocableHtlcs` and `RevocationStuck` don't mean the payment failed. |
   | `movement_id` | `None` only at `Start`. |
   | `wait: true` | Can block until near HTLC expiry. |
4. **bark changes 1–3**, by bumping the bark revision once they exist.

**Recommended:**
5. A `LightningSendStatus::Failed { payment_hash, movement_id }` variant. Today a send refunded inside the call reads `Unknown`, like "never started". It breaks exhaustive switches, so it belongs in the same bump.

**Not needed:** progress callbacks (movement notifications plus `phase` suffice), or a tip height on `Wallet` (`OnchainWallet.tipHeight()` exists).

## Server changes

**Required:**
- **Handler.** In `process_mailbox_message` (`server/src/mailbox_worker.rs:711`), handle `Message::LightningSendFinished` with a push without title or body, through `send_push_notification` like the other mailbox pushes. It reaches Expo tokens and UnifiedPush endpoints.
  - Keep the catch-up suppression (`:817`).
  - Only users with mailbox authorization are covered; others rely on BGTasks and the next launch.
- **Priority:** `content_available: true` with `Priority::Normal`, not the `High` used for claims.
  - iOS: Expo `normal` reaches APNs as priority 5, which Apple requires for background pushes; Expo `high` means 10.
  - Android: normal FCM priority. A result can wait for a Doze window, and repeated high-priority messages with no visible notification get an app deprioritised.
  - UnifiedPush: a plain POST, as today.
- **Best effort.** A push only queues a coordinator tick. A lost, late, repeated or reordered push changes nothing; the next launch, BGTask or WorkManager check finds the same result.
- **Type.** Add `NotificationData::LightningSendFinished(LightningSendFinishedNotification { notification_id })` to `server/src/types.rs:612`.
  - The payload is a random id only, so Android's per-payload unique work never merges two pushes. No hash, amount, outcome or preimage: the device checks all its watches.
  - Regenerate the TS and OpenAPI clients.

**Optional (last phase): raw APNs.** A token-auth (.p8) sender for `liveactivity` pushes and per-activity tokens, since Expo has no push-type or topic field ([Expo](https://docs.expo.dev/push-notifications/sending-notifications/)). It only lets the server mark a force-quit app's activity "Finished · open Noah", never "Sent". It builds on the "Raw APNs/FCM token registration" backlog item.

## Risks

- **iOS pushes are discretionary** (Apple suggests "two or three per hour") and skip force-quit apps, so "Open Noah to check" will be common on iOS.
- **Stuck HTLCs** can stay pending for about 43 h, or longer while revocation is refused: beyond the 8 h activity and the Android service. Then only Noah shows them.
- **Before bark change 1,** a late success stays Stuck, and its HTLCs count as locked.
- **Before bark change 3,** re-paying a failed BOLT11 invoice needs a new invoice.
- **Existing tests.** `wait: false` and `pending` change today's "may have been sent" and recurring semantics.
- **Many concurrent sends** mean many activities, against an unspecified system limit. Fall back to notifications.
- **Intermediaries learn timing.** Expo, Apple, Google and UnifiedPush distributors learn that a send finished at time T. Noah's server already reads the mailbox.

## Phased plan and tests

| Phase | Work | Tests |
| --- | --- | --- |
| 1. SDK and bark | Adopt the new FFI with `wait: true, retry_for: 60`; land the bark changes. | Options reach the call; an unroutable regtest invoice fails and refunds; current suites stay green. Change 1: an LND hold invoice held past expiry − 144 blocks, then settled → Paid, not stuck. |
| 2. Pending state | `wait: false`, the watch store, the coordinator, the `pending` journal state, in-app drivers. | See below. Regtest: an LND hold invoice (`just lncli addholdinvoice`) keeps a send pending; settle it → Sent, cancel it → Failed. |
| 3. Server push and push jobs | The handler, type and clients; device push jobs. | Delivery to an Expo token and a UnifiedPush endpoint (`push_deliveries`); normal priority, content-available; no hash, amount or preimage; the job drives and ends the watch. Device: background the app, then settle the hold invoice. |
| 4. System UI | The Live Activity, the handoff, the Android service and notification. | Content per state with hide-balance on and off, including after an activity ended; fixed tracker positions; a handoff that is `.invalid` or expires at once ends exactly once; the service's cap and `onTimeout`. Manual: swipe-away (Pixel), force quit (iPhone), Live Activities off. Recordings per platform. |
| 5. On-chain watches | Block-row watches. | Confirmation maths, stop rules, not-found. Regtest: `bcli sendtoaddress`, then mine one and two blocks. |
| 6. Optional | Raw APNs Live Activity updates. | — |

| Phase 2 test | Proves |
| --- | --- |
| Every evidence→state row | Revocation phases are never terminal. |
| An old failed movement for the same hash | It never ends a new watch, and re-paying that hash is refused. |
| A stale tick after a terminal write | It neither overwrites the state nor reposts the display. |
| Suspension or deletion during a tick | Ticks and native calls drain before cleanup. |
| The app killed between marker and watch; an offer killed mid-call; an errored `pay*` that left a checkpoint | Recovery binds or concludes correctly. |
| Done while pending, then a result | Send and recurring settle exactly once. |
| Override while pending | The attempt is parked, not dropped. |
| The "no `pay*` outside submit" fake | Background code never pays. |

## Open questions

1. Is 60 s right for every send, including recurring ones? Should large amounts get longer?
2. Is raw APNs (a .p8 key and a new sender) worth it, only to mark a force-quit app's activity "Finished · open Noah"?
3. Should the server ping devices that registered "waiting on a block" (no txid) on each new block? Recommended: no; the server stays out of on-chain.
4. Should incoming on-chain payments start a Live Activity or notification automatically, or only while Receive is open?
5. Is the privacy rule right: never the recipient, amounts follow hide-balance, and ended Live Activities never show amounts?
6. When Live Activities are off, should results arrive as ordinary notifications ("Sent ₿X")? Recommended: yes.
7. While a Lightning send is pending, should Send stay locked, with the override parking it? Recommended: yes. Allowing other sends freely invites paying the same merchant twice.
8. Should the bark changes go upstream first, or into a Noah fork now? Recommended: upstream, with the conservative behaviour meanwhile.
9. Until bark change 3, is "ask for a new invoice" acceptable when re-paying a failed BOLT11 invoice? Recommended: yes.
10. Should Android use high-priority pushes when a visible update is certain, for faster results in Doze? Recommended: no, normal everywhere.
