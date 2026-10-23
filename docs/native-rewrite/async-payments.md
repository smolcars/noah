# Background payments: design

**Status:** draft for review, revised after three reviews on 2026-10-22. Design only; no app or server code changes.

**Scope:**
- Lightning sends that outlast the screen.
- On-chain sends and receives waiting for blocks.

**Platforms:** iOS (iOS 18+) and Android (minSdk 24, targetSdk 37).

**Fits:** the in-app "Still sending" result and block row in [design/send-receive.md](design/send-receive.md).

**Path prefixes:**
- `B/`: bark 0.8.0 (`~/.cargo/git/checkouts/bark-f3369c4cebbcdc63/959a489`). `pay.rs` alone means `B/bark/src/actions/lightning/pay.rs`.
- `F/`: bark-bolt-ffi 0.5.0 (`git show v0.5.0:<path>`). Commit `7be5e19`, after it, already adds the send options.
- `I/`: `apps/ios/Noah`.
- `A/`: `apps/android/app/src/main/java/com/noahwallet`.

## Recommendation

1. **Hand the payment to Ark, then let go.**
   - Pay Lightning with `wait: false` and `retry_for = 60 s`.
   - From `PaymentInitiated` on, the Ark server pays whether or not Noah runs; the app only learns and records the result.
   - Background code never calls `pay*`.
2. **Track each attempt until bark proves the outcome.** A durable payment watch saves its baseline before submission and binds only its attempt's checkpoint and movement. One serialized owner settles it in recorded steps. Pushes and timers only ask for a check.
3. **The server nudges a closed app** with a silent, normal-priority `lightning_send_finished` push. It already streams each user's bark mailbox but ignores `LightningSendFinished`.
4. **iOS:** an amount-free Live Activity started at hold-to-send, updated whenever the app runs. No `BGContinuedProcessingTask`. Raw APNs Live Activity pushes are optional, last.
5. **Android:** a `shortService` foreground service for up to 2 minutes, with an ongoing notification (Android 16 `ProgressStyle` Live Update, with a fallback below). Pushes and WorkManager finish slower sends.
6. **On-chain:** the block row outside the app, refreshed when the app gets time. No long-running work.
7. **bark:** three small fixes. Fix 2 (drivers reload under the send's lock and finish interrupted settlement) blocks `wait: false`. Until fixes 1 and 3 land, Noah shows "Still sending", or a cautious failure for a retried invoice, rather than guess.

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
| **An error doesn't say where the send stopped.** `pay*` saves the checkpoint before contacting the server. With `wait: false` an error can come before anything exists, with the checkpoint kept for a retry, or after bark ended the send: an abort before cosigning fails the movement and removes the checkpoint, and an HTLC exit also ends in an error. | `B/bark/src/actions/mod.rs:647-654,694-699`; `pay.rs:203-283` |
| **One movement per payment hash.** It is keyed by `ln_pay.<hash>` under a unique index and never unlinked, so re-paying an invoice after a failure reuses the old movement, still `Failed`. | `pay.rs:403-427`; `B/bark/src/persist/sqlite/mod.rs:132-154`; migration 39 |
| **Drivers can act on a stale copy.** `drive_action` holds a per-send lock, but `check_lightning_payment`, the sync driver and the mailbox load the checkpoint before taking it, and mailbox settlement with a preimage takes no lock. The pay path checks `is_invoice_paid` before its lock. The foreground daemon reads the mailbox continuously and runs `wallet.sync()`, which drives pending sends too. | `B/bark/src/actions/mod.rs:540-553`; `B/bark/src/lightning/pay.rs:108-116,152-161,167-181,368-378`; `mailbox.rs:630-648`; `B/bark/src/daemon/mod.rs:128-150,281-400`; `B/bark/src/lib.rs:2014-2050` |
| **Settlement takes several writes.** It records the paid invoice, then marks the HTLCs spent and finishes the movement; the driver then releases locks and removes the checkpoint. A crash in between leaves a paid record beside a checkpoint, perhaps with the movement still pending. `check_lightning_payment` and the mailbox stop at the paid record; only the sync driver re-polls and finishes it. | `pay.rs:651-660`; `B/bark/src/actions/mod.rs:529-533,688-693`; `B/bark/src/lightning/pay.rs:52-60,155-158`; `mailbox.rs:630-633` |
| `wait: true` sleeps through parks and retries until done, so a stuck send blocks for hours. | `B/bark/src/actions/mod.rs:656-680` |
| `check_lightning_payment(hash, false)` drives an existing checkpoint to its next park (at most one status poll) and never creates a payment. A resumed send keeps its original `retry_for`. | `B/bark/src/lightning/pay.rs:152-163,376-391` |

**Apps:**

| Fact | Source |
| --- | --- |
| Both apps pay with `wait: true`. | `I/Services/Wallet/WalletSession+Send.swift:154,161`; `A/send/SdkSendWallet.kt:124,126` |
| For invoices and LNURL, both write a marker (`inspectionPending`, empty baseline) before any SDK call, then save the prepared baseline in a second write before paying. Offers get no marker. Recovery keeps a marker `uncertain` and only advances existing checkpoints. | `I/Features/Send/SendPayment.swift:318-329`; `A/send/SendController.kt:113-122`; `WalletSession+Send.swift:247`; `A/send/SdkSendWallet.kt:171`; `I/Features/Send/SendLightningCheckpoint.swift:56` |
| A pending send shows "Payment may have been sent" and keeps Send locked. Done can't clear it; only the audited override can, by deleting the journal. | `SendPayment.swift:197,463-490`; `SendController.kt:27,224-243` |
| `finish` saves a terminal journal; a separate acknowledgment (Done, or the recurring executor's release) clears it. | `SendPayment.swift:404,483-492`; `SendController.kt:164-165,191,224` |
| Both allow another attempt on an invoice whose last attempt failed. | `WalletSession+Send.swift:387`; `A/send/SendCheckpoint.kt:55-58` |
| An uncertain recurring occurrence marks its schedule `needs_attention`, and a later success keeps that status. | `I/Features/Recurring/RecurringExecutor.swift:315-330,362-367`; `A/recurring/RecurringExecutor.kt:239-250` |
| Recurring outcomes are final unless `uncertain`: `record` replaces only `uncertain`, and the executor settles anything else. On Android, settling also clears Send's journal through `releaseRecurring`. | `I/Features/Recurring/RecurringJournal.swift:190-195`; `A/recurring/RecurringJournal.kt:143-146`; `RecurringExecutor.swift:338`; `RecurringExecutor.kt:254-255`; `SendController.kt:164-165` |
| Recurring sends also run unattended, from BGTasks, `recurring_payment_due` pushes and periodic work. Their result notification reads "Recurring payment sent" for any status it doesn't name. | `I/Services/Background/BackgroundScheduler.swift:100`; `I/Services/Push/PushJobs.swift:82-85`; `A/services/background/PeriodicRuntime.kt:19`; `A/services/push/PushRuntime.kt:25`; `I/Features/Recurring/RecurringLive.swift:115-117`; `A/recurring/RecurringNotifications.kt:28` |
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

**Identity and binding.** A watch is created for each confirmed Lightning attempt, right after the journal marker and before any SDK call. Offers get the marker too.

| Field | Source |
| --- | --- |
| `attemptID` | A UUID in the journal marker: the watch's identity before any hash or txid exists. |
| owner | Send journal, recurring occurrence (key and `recurringAttemptID`), parked (taken over from Send), or orphan (found in bark with no journal). |
| wallet | Fingerprint and network. Other wallets' watches are ignored. |
| stage | `admitting` until the baseline is saved, then `prepared`. Recovery uses the earlier of the journal's and the watch's stage. |
| baseline | Every movement ID and every pending Lightning send (hash and `payment_method`) that `prepare` read. It exists only from `prepared`: an `admitting` attempt has no baseline, never an empty one. |
| payment hash | From the invoice, the resolved LNURL invoice, the offer call's result, or reconstruction. |
| movement ID | `LightningSend.movement_id` of this hash's checkpoint. It must be outside the baseline, unless the attempt resumes its own reviewed checkpoint (today's `SendLightningCheckpoint.movementID`). |
| amount, fee bound, `retry_for`, start time | From the reviewed quote. |
| attempt snapshot | Payment, quote, wallet identity, recurring key, `recurringAttemptID` and the occurrence's marker (`markedAt`): enough to settle without the Send journal. |
| state, highest phase, `revision`, display ids, settlement steps | Written only by the coordinator. |

**Admission order.** Submission waits for a saved baseline, and only a live submission may save it:
1. Register the attempt's submission authority with the coordinator. Write the journal marker, then the watch, both `admitting`.
2. `prepare` reads the checkpoint state, the quote and history.
3. Under the coordinator's authority lock, check the authority, then durably save the complete baseline with `prepared`: the journal (today's second write, `SendPayment.swift:328`; `SendController.kt:122`), then the watch.
4. Under that lock again, check the authority and mark it paying. Only then `pay*`. Paying stays marked, in memory under the same lock, from this check until the `pay*` call returns; recovery treats a paying attempt as a running call.

Authority is in memory, per process. The authority lock is short: it guards these checks, recovery's decision and admission changes, never a bark call. Today's submit task survives screen and app transitions, and after `prepare` it checks only its generation (`SendPayment.swift:286-331`; `SendController.kt:103-137`). The lifecycle revokes authority under that lock; a revoked task stops at its next check (before `prepared`, or between `prepared` and paying), writes nothing more and discards its quote. Once paying, authority can't be revoked.

**Live authority wins in recovery.** Every recovery decision about an attempt without a terminal outcome is taken under the authority lock. If this process still holds live authority for the attempt, at any stage (`admitting`, `prepared` but not yet paying, or paying), recovery decides nothing: the submit task owns it and requests a tick when it ends. Recovery applies the stage rules below only when no live authority exists: in a new process, or after the lifecycle revoked it under that lock. Revocation and the decision happen under the same lock, so a revoked task can never mark itself paying afterwards.

**Reused hashes (retrying a failed invoice).** The user may retry the same BOLT11 invoice after a failure; a new invoice for the same destination usually fails the same way, so Noah never asks for one (user decision, 2026-10-22). Until bark change 3, bark reuses the hash's finished movement for the new attempt, so movement status can't tell the attempts apart. Noah therefore binds a retry by its own `attemptID`, the payment hash and the checkpoint created after the attempt began, never by the old movement:
- **Success:** the paid record for the hash, which a failed first attempt never wrote, plus the attempt's checkpoint ending. Shows Sent.
- **Failure:** the attempt's checkpoint ending with no paid record. Shows "Payment didn't go through" without "back in your wallet", unless the refund outputs can be tied to this attempt.
- **Never** "already paid" because of the old failed movement.
LNURL, Lightning addresses and offers fetch a fresh invoice each time and aren't affected. Resuming the user's own reviewed checkpoint stays allowed.

**Evidence.** Each tick reads bark for the bound attempt only:

| Wallet evidence for the bound attempt | Watch state | Terminal |
| --- | --- | --- |
| call still running, nothing in bark yet | Locking | no |
| `InProgress`, phase before `PaymentInitiated` | Locking | no |
| `InProgress(PaymentInitiated)` | Paying → Slow → Long (elapsed time) | no |
| `InProgress(RevocableHtlcs)` | Finishing | no |
| `InProgress(RevocationStuck)` | Stuck: active drivers stop; launch and hourly checks continue | no |
| `Paid` + preimage + the bound movement `Successful`, amounts match | Paid | yes |
| `Paid`, but the bound movement isn't `Successful` yet (settlement interrupted) | Paying; the next drive finishes it (bark change 2) | no |
| no checkpoint + the bound movement `Failed`, effective balance 0, amounts match, no exited VTXOs | Failed (refunded) | yes |
| no checkpoint + the bound movement `Failed` with exited VTXOs | Returning on-chain; Exits shows it | yes |
| call returned; no paid record, checkpoint or bound movement for this hash; every read succeeded | Failed (never sent) | yes |
| a read error | last state, stale | no |
| any movement in the baseline, or not bound to this attempt | ignored | — |

Today's guards stay: movement ID equality, a sent-to amount equal to the attempt's, and a fee within the quote (`SendLightningCheckpoint.swift:29-36`; `A/send/SendReconciliation.kt:25-35`). The server cosigns a revocation only for a failed payment, or after HTLC expiry without success, and never for a paid one. So a revoked movement is the refund evidence; the revocation phases are not.

**Errors.** A `pay*` or `checkLightningPayment` error can come before anything exists, while a checkpoint remains, or after the send ended (see the FFI contracts). Neither it nor a missing checkpoint proves anything. For a known hash the coordinator reads the paid record, checkpoint and bound movement; otherwise it reconstructs from the saved baseline.

**One writer.** One `PaymentWatchCoordinator` per process (a Swift actor; a Kotlin object behind one `Mutex`) owns every watch write, every settlement step, and every activity, notification and service update.

| Rule | Detail |
| --- | --- |
| Requests | Drivers only call `request(reason)`, which never waits. Requests coalesce into one running tick and one queued. |
| Wallet access | A tick reads bark under its caller's existing admission (iOS counted call or background gate; Android `withOpen` or `requireBackgroundWallet`). The coordinator's lock, not those paths, serializes decisions, so foreground and background ticks can't interleave. |
| Lock order | Coordinator, then the wallet mutation gate, never the reverse. Step 2 takes the gate through the executors' `storage` (`I/Features/Recurring/RecurringExecutor.swift:62-63`; `A/recurring/RecurringExecutor.kt:42-57`), and neither gate is reentrant. Gate holders (foreground sync, wallet open, recurring storage) may `request` a tick but never await one, and a caller that awaits a tick holds no gate. After taking the gate, step 2 rechecks admission and wallet generation; if either changed it writes nothing and stays unfinished. |
| Writes | Compare-and-set on `revision`. Terminal outcomes never change. A tick whose watch revision or wallet generation changed since it started drops its result. |
| Display | Updated only from the committed watch. A terminal watch ends its display once and records that, so a stale tick can't recreate it. |
| bark's own writers | The daemon and mailbox change bark state between ticks; the coordinator only reads bark. Bark change 2 stops their races from bringing a checkpoint back; bark's paid record wins in `lightning_send_state` either way. |
| Lifecycle | Android suspension and deletion, and iOS deletion and restore, hold the mutation gate during cleanup (`A/services/wallet/WalletLifecycle.kt:104-110,129-141`; `I/Services/Wallet/WalletLifecycle.swift:138-169`; `I/Services/Backup/WalletRestorer.swift:61`), so they drain the coordinator before taking it: (1) under the authority lock, close admission (every coordinator write checks it there) and revoke live submissions' authority; (2) wait for the running tick, bounded by deletion's drain timeout (if it expires, the late tick finds admission closed and writes nothing); (3) only then take the gate, wait for admitted native calls, stop the daemon (`stopDaemonWait`, as `I/Services/Wallet/WalletSession.swift:236` does), end activities, cancel notifications, stop the service and cancel watch work. Admission stays closed until the operation ends: after resume, or once the replacement wallet is open, bound to its generation. Deletion then deletes the watches; suspension keeps them. |

**Recovery on launch and resume.**

| Found | Action |
| --- | --- |
| unresolved journal attempt without a watch | Create it from the journal, with the journal's stage and baseline (the app died between the two writes). |
| `admitting` attempt, no live authority | `pay*` never started, because it needs `prepared` on disk and live authority. Bind nothing and read no history as a baseline. Repeat the read-only inspection: no send for the invoice ends it "Not sent" (recurring: today's retryable not-submitted failure); an existing one shows as today's "already in progress" or "already paid"; an error keeps today's `uncertain`. An offer has no hash yet, so it ends "Not sent". |
| `prepared`, no hash, no live authority (so no call running) | Using the saved baseline only, bind the one pending send or movement outside it with this offer (`payment_method`) and amount. None, with every read successful: Failed (never sent). Several, or a read error: uncertain, never terminal. |
| bark pending send without a watch | Orphan watch: display only, settles no journal. |
| terminal watch with unfinished settlement steps | Run those steps by `attemptID`, even if the journal is already terminal. |

**Lifetime.** Tracking ends only once a terminal outcome has every settlement step done, or on wallet deletion. A send can stay pending past HTLC expiry (about 43 h). Display follows each system UI's limits; the in-app result and Activity row always show the truth.

## Send and recurring journals

1. **A `pending` result.** Both controllers get a `pending` state, set only from evidence for the bound attempt. Unlike `uncertain`, it has a checkpoint and an owner. The journal stores it with `attemptID` and the binding, and loading restores `pending`, not `uncertain` (`SendPayment.swift:262-264`).
2. **Done closes the screen, not the journal.** The controllers are process-wide (`SendPaymentController.shared`; Android's process-owned `SendController`), so the attempt stays owned. Done while pending makes the watch's acknowledgment automatic.
3. **Exactly-once settlement, in recorded steps.** Today a crash between `finish` and the acknowledgment leaves a terminal journal that nothing clears. Instead, the coordinator writes the terminal outcome to the watch, then runs three steps by `attemptID`, marking each done only once its write is durable:

   | Step | Runs when | Does | No-op when |
   | --- | --- | --- | --- |
   | 1. Send journal | The Send journal holds this `attemptID` | `settleWatched`: today's `finish` writes the result, recurring evidence included | The journal already holds this result, another attempt, or nothing |
   | 2. Recurring | The watch has a recurring key and `recurringAttemptID` | `RecurringJournal.record` from the watch's attempt snapshot and outcome, unless the entry already holds final evidence for this `recurringAttemptID` (step 1 may have written it). Then the executor's `settle` with the entry's settlement token, repeated until its durable flags (`resolved`, `historyApplied`, `released`) read released | The entry is released, gone (or holds another marker), or was consumed by the audited resume (resolved without final evidence) |
   | 3. Acknowledge | Acknowledgment is automatic: Done was tapped while pending, the attempt is parked, or a recurring send ran without review | Clears the Send journal if it still holds this `attemptID` with this result | The journal holds another attempt or nothing |

   On every launch, resume and tick, the coordinator re-runs every unfinished step, whatever state the Send journal is in. A crash before a step's mark repeats the step, harmlessly. Recorded evidence alone never completes step 2: after a crash between `record` and `settle`, or inside `settle`, it resumes `settle` with the same token. While the user views the result, acknowledgment stays with their Done.
4. **Another send while one is pending.** Recommended: Send stays locked, as today, with a "Still sending · View" banner. The audited override ("Send another payment anyway") now parks the attempt instead of deleting it:
   - Parking copies the attempt snapshot into the watch and sets its owner to parked, fsynced before the Send journal is cleared.
   - A parked attempt A settles through steps 2 and 3; step 1 finds B's journal or none and does nothing. A is never restored into Send, and B's journal is never written: the executor's release clears only a journal holding A's `recurringAttemptID` (`SendPayment.swift:309-311`; `SendController.kt:164-165`).
5. **Recurring.**
   - **`pending` is not final.** Both `RecurringJournal`s get a `pending` outcome, recorded from an attempt with a bound pending watch. `record` may replace `uncertain` or `pending` for the same `recurringAttemptID`. Succeeded, failed and already-paid stay final.
   - **The executor settles only final outcomes.** Every "not `uncertain`" check becomes "final" (`RecurringExecutor.swift:338,391,468,487`; `RecurringExecutor.kt:254,302,366,386`). For `pending`, `settle` only shows "Sending": no history applied, no resolve or release, and no `releaseRecurring`, which on Android would clear Send's journal mid-payment.
   - **The occurrence stays locked.** No new occurrence starts for a schedule with an unresolved entry, even once parking unlocks Send; it would overwrite the entry. The start-up "interrupted" check (`RecurringExecutor.swift:139-143`; `RecurringExecutor.kt:117-118`) skips `pending` entries with a watch.
   - **Results.** A success restores `active` only if the schedule's issue is still this occurrence's `pending`. A refund is today's retryable failure. The audited resume parks the attempt and consumes the occurrence, as today; its later result only updates the watch, Activity and notification.
   - **Ordinary notifications only.** Recurring sends are unattended, so they never start a Live Activity, the service or a progress notification, which belong to a person's tap. iOS starts activities from the foreground or an app intent, not a BGTask or silent push ([Apple](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities)); Android 12+ refuses background service starts outside narrow exemptions ([Android](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)). `pending` posts no notification; today's notifier would call it "Recurring payment sent".

## iOS

**Live Activity:**
- **Start.** ActivityKit (iOS 16.1+). The app requests it as hold-to-send completes; only Send does. `NoahWidget` renders it; the app's Info.plist gets `NSSupportsLiveActivities`.
  - Starting needs the foreground; updating and ending work "while it runs in the background" ([Apple](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities)).
  - If activities are off (`areActivitiesEnabled`) or `targetMaximumExceeded`, show a local result notification instead.
- **No amounts.** Neither the attributes nor the content state has an amount field. iOS ends an activity by itself after eight hours, keeping its last content on the Lock Screen "up to four additional hours" (same page). Noah may not run then, and Apple documents no way to change an ended activity, so an amount could outlive turning hide-balance on.
- **Stale and end.**
  - Each update sets `staleDate` to now + 3 min; a stale view reads "Open Noah to check".
  - End on terminal evidence with `.after(now + 15 min)`.
  - Limits: 4 KB of data (same page).
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
- **Timing.** Start it at Send's hold-to-send, while visible, so no background-start limits apply. Stop it at the result or at `retry_for + 60 s`.
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
  - compact: your coin, plus ✓ when paid;
  - minimal: your coin;
  - expanded: the Lock Screen layout.
- **Android:** the same title, line and segments.

| State (from evidence) | Title | Line | Track |
| --- | --- | --- | --- |
| Locking (`Start`…`HtlcReceived`) | Sending | Locking your coins | step 1 |
| Paying (`PaymentInitiated`, <10 s) | Sending | Paying over Lightning | step 2 |
| Slow (≥10 s) | Sending | Still paying. This can take a few minutes. | step 2 |
| Long (> `retry_for` + 30 s) | Still sending | Taking longer than usual. If it fails, Ark returns your coins. | step 2 |
| Finishing (`RevocableHtlcs`) | Still sending | Waiting for the final result. If it failed, Ark returns your coins. | step 2 |
| Stuck (`RevocationStuck`) | Still sending | No final result yet. Your coins stay locked until Ark settles it. Open Noah. | step 2 |
| Paid | Sent | Paid over Lightning | full |
| Failed (refunded or never sent) | Payment failed | It's back in your wallet | grey, coin back at start |
| Stale | unchanged | Open Noah to check | unchanged |
| On-chain waiting | Sending on-chain / Incoming payment | Waiting for block {tip+1} | blocks |
| On-chain confirmed | Sent / Received | Confirmed in block N (· 1 of 2) | blocks |

Live Activities never show amounts. Android titles add one ("Sending ₿X", "Incoming ₿X") only while hide-balance is off, and so do result notifications on both platforms. Tapping opens the Send result or the Activity row, behind the app lock as usual.

## Safety rules

1. **No double payment.**
   - Background code calls only `checkLightningPayment(wait: false)`, `lightningSendState`, `pendingLightningSends`, `sync`, `transactionStatus` and `tipHeight`; a test fake fails on any other `pay*` call.
   - Submission calls `pay*` only after its attempt is `prepared` on disk.
   - Today's checkpoint-match and "already in progress" guards stay. A hash with a finished movement is never paid again.
   - Driving a checkpoint that stopped before `PaymentInitiated` (the process died mid-handoff) completes that same confirmed payment, as `sync()` does today; it never starts another. If the server refuses it, bark fails or revokes the send.
   - The override parks a pending attempt; it never deletes its tracking.
2. **No claim without evidence.** Only the terminal evidence rows say "Sent" or "back in your wallet". Pushes, timers, errors, a missing checkpoint, revocation phases and other attempts' movements never do.
3. **Bounded battery use.** One `shortService` per send, at most 2 min. Background checks: Lightning at +5 min, then hourly; on-chain at most every 10 min. Before first unlock the seed can't be read, so watches go stale.
4. **Privacy.**
   - The surfaces this design adds never show the recipient, addresses, txids or preimages.
   - Live Activities never show amounts.
   - **Hide-balance on** (`isHomeBalanceHidden`): notifications show no amounts either ("Sending", ₿••••).
   - **Hide-balance off:** Android's ongoing notification and both platforms' result notifications show amounts. Android uses `VISIBILITY_PRIVATE` with an amount-free public version.
   - **Turning it on** updates ongoing notifications at once. iOS removes delivered result notifications (`removeDeliveredNotifications`); Android reposts them amount-free under the same id, silently.

## Required bark changes

Small changes to bark 0.8, upstream or in a Noah fork; bark-bolt-ffi then pins the new revision.

| Change | Blocks release? | Without it |
| --- | --- | --- |
| 2. Drive only the current checkpoint, and finish interrupted settlement | Yes: Noah doesn't ship `wait: false` (phase 2) without it | A stale driver can bring a checkpoint back after the send ended. A refunded send then reads "Still sending" indefinitely, keeping Send locked, and a paid one stays in bark's pending sends. `wait: false` adds many drivers, so the race becomes likely. A reload that stops at the paid record would strand interrupted settlement: the movement never becomes `Successful`, so Noah never shows Paid. |
| 1. Late success after revoking starts | No | A late success shows Stuck: slow, but never a false refund. |
| 3. One movement per attempt | No | A retried invoice's failure shows without "back in your wallet". |

1. **Late success after revoking starts.**
   - In `RevocableHtlcs` and `RevocationStuck`, check the payment status before asking to revoke. On a valid preimage, settle as `PaymentInitiated` does: paid record, HTLCs spent, movement `Successful`.
   - Do the same when the server refuses because the payment completed (`B/server/src/ln/mod.rs:496,524`).
   - Let the mailbox settle a valid preimage in any phase that holds HTLCs (`mailbox.rs:638-648`).
2. **Drive only the current checkpoint.** Every driver takes `ln_pay.<hash>`, reloads the paid record and the checkpoint, and acts only on the reloaded state, never the copy it was handed:

   | Reloaded | Action |
   | --- | --- |
   | no checkpoint | Return without writing; with a paid record, settlement is complete. |
   | checkpoint, no paid record | Drive it. |
   | checkpoint and a paid record | Settlement was interrupted. Finish it idempotently from the stored preimage: mark the checkpoint's HTLCs spent, finish the movement `Successful` unless it already is (`finish_movement_with_update` would rewrite it), release the action's locks, then remove the checkpoint. With the pay path's recheck, such a checkpoint always holds that payment's HTLCs. |

   - Callers that load first today: `check_lightning_payment` and the sync driver (`B/bark/src/lightning/pay.rs:155-161,108-116`), and the mailbox's drive (`mailbox.rs:635-644`). Reloading inside `drive_action` covers them all.
   - `check_lightning_payment` and the mailbox no longer stop at a paid record while a checkpoint remains (`B/bark/src/lightning/pay.rs:155-158`; `mailbox.rs:630-633`).
   - Mailbox settlement with a preimage takes the same lock, skips when a drive holds it (that drive's status poll finds the result), and settles only a reloaded `PaymentInitiated` checkpoint.
   - The pay path rechecks `is_invoice_paid` under its lock (`B/bark/src/lightning/pay.rs:368-378`).
   - Tests: mailbox settlement while a driver holds a stale copy brings no checkpoint back. Interrupt settlement after each write (paid record, HTLCs spent, movement `Successful`, locks released), then drive through each caller: settlement finishes once, no checkpoint remains, and the movement is `Successful` with one update.
**If upstream is slow, an alternative to forking change 2: one driver.** Today Noah opens the wallet with bark's daemon (`runDaemon: true`), whose always-on mailbox stream settles or drives a send (`B/bark/src/mailbox.rs:625-650`) alongside Noah's own pay, check and sync calls; that concurrency is what change 2 makes safe. Noah could instead make its coordinator the only caller that drives Lightning sends, processing mailbox messages, checks and syncs in one serialized queue, with the daemon's own mailbox handling off. The mailbox alone can't be the source of truth: refunds and the near-expiry revocation of a stuck send are driven by bark's checks and sync, not by a mailbox message. Interrupted settlement is then finished by the next sync, as today. Cost: Noah takes over the daemon's mailbox loop, and the FFI must let the daemon run without its mailbox processing (or Noah runs without the daemon). Decide only if change 2 isn't merged upstream by phase 2.

3. **One movement per attempt.** When a send action ends, unlink its movement (`action_id = NULL`), so a new attempt on the same hash gets its own movement. Noah's retry outcomes are then exact.

## FFI changes (bark-bolt-ffi on bark 0.8.0)

**Required:**
1. **Options.** Done in `7be5e19`: `LightningSendOptions { wait: bool = false, retry_for_secs: Option<u32> = None }` replaces `wait` on `pay_lightning_invoice`, `pay_lightning_offer`, `pay_lightning_address`, `pay_lnurl` and the new `send_payment`. Noah always passes both fields. `None` uses the server default; `0` makes a single attempt; larger values are capped by the server; it is ignored when an existing checkpoint resumes.
2. **New fields on `LightningSend`** (`F/src/types.rs:416`):
   - `phase: LightningSendPhase` (`Start | InputsLocked | HtlcReceived | PaymentInitiated | RevocableHtlcs | RevocationStuck`), 1:1 from `Progress`, keeping bark's names so nobody reads `RevocableHtlcs` as "failed";
   - `retry_for_secs: Option<u32>`;
   - `htlc_expiry_height: u32`;
   - `payment_method: PaymentMethod` (the existing `{ method_type, value }` record), from `original_payment_method`, so an offer attempt can be matched before Noah knows its hash.
3. **No error after bark succeeds.** Each `pay_*` reads the state after bark returns, and that read can fail (`F/src/core/wallet.rs:596-608,676-684`; `send_payment` too in `7be5e19`). Once bark returns `Ok`, return the payment hash with the status, even if the read fails: for example a `LightningSendStatus::StateUnavailable { payment_hash }` variant. Every error then comes from bark, and an offer always learns its hash. Recovery stays correct without this, through reconstruction.
4. **Documented contracts**, each with a contract test:

   | Call | Contract |
   | --- | --- |
   | `pay_*`, `wait: false` | Returns `InProgress` (normally at `PaymentInitiated`), `Paid`, or `Unknown` when the payment failed and was refunded inside the call. |
   | `pay_*` or `check_lightning_payment` error | Can come before anything was created (parsing, already paid, lock busy); while a checkpoint remains (a transient failure, or `RevocationStuck` parked with an error); or after bark ended the send (an abort before cosigning, with its movement `Failed`, or an HTLC exit). Until item 3, it can also be the FFI's own state read after bark returned, whatever the outcome. A missing checkpoint proves nothing: callers check `lightning_send_state` (paid record, then checkpoint), `pending_lightning_sends` and the movements before concluding. |
   | `check_lightning_payment(hash, false)` | Never creates a payment; returns at the next park after at most one status poll. In revocation phases it only tries to revoke, until bark change 1. Until bark change 2 it returns `Paid` without driving, even when settlement was interrupted; after it, it finishes that settlement first. |
   | `phase` | `RevocableHtlcs` and `RevocationStuck` don't mean the payment failed. |
   | `movement_id` | `None` only at `Start`. |
   | `wait: true` | Can block until near HTLC expiry. |
5. **bark changes**, by bumping the bark revision: change 2 before Noah ships `wait: false`; 1 and 3 once they exist.

**Recommended:**
6. A `LightningSendStatus::Failed { payment_hash, movement_id }` variant. Today a send refunded inside the call reads `Unknown`, like "never started", and has no hash. It breaks exhaustive switches, so it belongs in the same bump.

**Not needed:** progress callbacks (movement notifications plus `phase` suffice), a tip height on `Wallet` (`OnchainWallet.tipHeight()` exists), or a public settle-with-preimage call (mailbox settlement stays inside bark).

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
- **Before bark change 3,** a retried invoice's failure can't claim "back in your wallet".
- **Existing tests.** `wait: false`, `pending` and the `admitting` recovery change today's "may have been sent" and recurring semantics.
- **Many concurrent sends** mean many activities, against an unspecified system limit. Fall back to notifications.
- **Intermediaries learn timing.** Expo, Apple, Google and UnifiedPush distributors learn that a send finished at time T. Noah's server already reads the mailbox.

## Phased plan and tests

| Phase | Work | Tests |
| --- | --- | --- |
| 1. SDK and bark | Adopt the new FFI with `wait: true, retry_for: 60`; land bark change 2, then 1 and 3. | Options reach the call; an unroutable regtest invoice fails and refunds; current suites stay green. Change 2: the stale-driver and interrupted-settlement tests above. Change 1: an LND hold invoice held past expiry − 144 blocks, then settled → Paid, not stuck. |
| 2. Pending state (needs bark change 2) | `wait: false`, the watch store, the coordinator, the `pending` journal states, settlement steps, in-app drivers. | See below. Regtest: an LND hold invoice (`just lncli addholdinvoice`) keeps a send pending; settle it → Sent, cancel it → Failed. |
| 3. Server push and push jobs | The handler, type and clients; device push jobs. | Delivery to an Expo token and a UnifiedPush endpoint (`push_deliveries`); normal priority, content-available; no hash, amount or preimage; the job drives and ends the watch. Device: background the app, then settle the hold invoice. |
| 4. System UI | The Live Activity, the handoff, the Android service and notification. | Content per state with hide-balance on and off. No amount in any Live Activity: the content type has no amount field, and on a device an activity left pending past 8 h, ended by iOS, then hide-balance turned on and Noah restarted, still shows none. Fixed tracker positions; a handoff that is `.invalid` or expires at once ends exactly once; the service's cap and `onTimeout`; a recurring send starts no activity or service. Manual: swipe-away (Pixel), force quit (iPhone), Live Activities off. Recordings per platform. |
| 5. On-chain watches | Block-row watches. | Confirmation maths, stop rules, not-found. Regtest: `bcli sendtoaddress`, then mine one and two blocks. |
| 6. Optional | Raw APNs Live Activity updates. | — |

| Phase 2 test | Proves |
| --- | --- |
| Every evidence→state row | Revocation phases are never terminal. |
| An old failed movement for the same hash | It never ends a new watch, and re-paying that hash is refused. |
| A stale tick after a terminal write | It neither overwrites the outcome nor reposts the display. |
| Suspension, deletion or restore while a tick waits for the gate in step 2, while a gate holder requests a tick, and with the drain timing out | No deadlock: admission closes and ticks drain before the gate is taken; a late tick writes nothing; admission stays closed through replacement, and the step reruns after resume. |
| The app killed while `admitting`, with an earlier payment to the same offer and amount in history | Recovery binds nothing and never reports Sent. |
| Resume while the submit task is suspended in `prepare`; then the same with wallet suspension revoking its authority | Recovery leaves the live task alone, and it pays once; "Not sent" never precedes a payment. A revoked task never saves `prepared` or pays, and recovery then ends it "Not sent". |
| An offer task pauses after saving `prepared`, before its paying check; recovery runs; then the task resumes. Repeat with the lifecycle revoking authority in that pause | Without revocation, recovery decides nothing and the task pays once, ending Sent or Failed from evidence only. With revocation, the task's paying check fails, it never calls `pay*`, and recovery ends it "Failed (never sent)". Never a "never sent" followed by a payment. |
| The app killed between marker and watch; an offer killed mid-call after `prepared`; an errored `pay*` that left a checkpoint | Recovery binds or concludes correctly. |
| A `pay*` error after bark paid, and after it refunded | The paid record or the bound movement settles it; a missing checkpoint never does. |
| A crash after each settlement step, between `record` and `settle`, and after each `settle` flag, for Send-owned, parked and recurring attempts | Restart finishes the remaining steps once: history applies once, the occurrence is released, and an automatic acknowledgment unlocks Send. |
| Override while pending, then A finishes while B is pending | A is parked, not dropped; its occurrence settles; B's journal is untouched. |
| Recurring pending → success and pending → refund, each across a restart | The occurrence stays locked and no history applies while pending; then it settles once: success restores `active`, a refund is retryable. |
| A final recurring outcome followed by `pending` or `uncertain` | It stays final. |
| The payment fakes | Background code never pays, and submission never pays before `prepared` is durable. |

## Decisions (user, 2026-10-22)

1. **`retry_for`:** the new bark-bolt-ffi (0.6, in progress) exposes it. Noah passes an explicit value on every Lightning send, starting at 60 s (today's server default), and can tune it later.
2. **Raw APNs:** explained to the user (Expo can't send Live Activity pushes). Pending their call; recommended later.
3. **Server pings on new blocks:** no.
4. **Automatic on-chain progress:** yes, as recommended. An incoming transaction is noticed only when a wallet sync sees it unconfirmed (foreground, background refresh or the server's refresh wake); the server never watches addresses. A sync that sees one posts an ordinary notification (an Android progress notification), followed by "Confirmed in block N"; a Live Activity starts only when Noah is open.
5. **Amount-free Live Activities:** yes, ship them.
6. **Result notifications when Live Activities are off:** yes.
7. **Send stays locked while a Lightning send is pending,** with the override parking it: yes.
8. **Bark changes:** send all three upstream. Change 2 is open as [ark-bitcoin/bark!2663](https://gitlab.com/ark-bitcoin/bark/-/merge_requests/2663) (2026-10-22; it also makes bark's daemon sync drive pending Lightning sends); changes 1 and 3 follow as separate small MRs. Change 2 blocks only `wait: false` background payments (phase 2), not today's app or the Send/Receive redesign; fork it only if upstream hasn't merged it by then.
9. **Retrying a failed invoice:** allowed, never "ask for a new invoice" (the same destination usually fails again). Until bark change 3, Noah binds the retry by attempt and paid record and shows a cautious failure; change 3 goes upstream as its own small MR.
10. **Push priority:** normal for every push this design adds. The existing high-priority server wake pushes (maintenance refresh) stay as they are.
