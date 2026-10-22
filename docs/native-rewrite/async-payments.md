# Background payments: design

**Status:** draft for review, 2026-10-22. Design only; no app or server code changes.

**Scope:**
- Lightning sends that outlast the screen.
- On-chain sends and receives waiting for blocks.

**Platforms:** iOS (iOS 18+) and Android (minSdk 24, targetSdk 37).

**Fits:** the in-app "Still sending" result and block row in [design/send-receive.md](design/send-receive.md).

**Path prefixes:**
- `B/`: bark 0.8.0 (`~/.cargo/git/checkouts/bark-f3369c4cebbcdc63/959a489`).
- `F/`: bark-bolt-ffi 0.5.0.
- `A/`: `apps/android/app/src/main/java/com/noahwallet`.

## Recommendation

1. **Hand the payment to Ark, then let go.**
   - Pay Lightning with `wait: false` and `retry_for = 60 s`.
   - From `PaymentInitiated` on, the Ark server pays whether or not Noah runs; the app only learns and records the result.
   - Background code never calls `pay*`.
2. **The server wakes a closed app.** Noah's server already streams each user's bark mailbox but ignores `LightningSendFinished`. It should send a silent `lightning_send_finished` push.
3. **iOS:** a Live Activity started at hold-to-send, updated whenever the app runs. No `BGContinuedProcessingTask`. Raw APNs Live Activity pushes are optional, last.
4. **Android:** a `shortService` foreground service for up to 2 minutes, with an ongoing notification (Android 16 `ProgressStyle` Live Update, with a fallback below). Pushes and WorkManager finish slower sends.
5. **On-chain:** the block row outside the app, refreshed when the app gets time. No long-running work.

## What happens today

**bark:**
- **Send phases.** A Lightning send is a persisted checkpoint: `Start → InputsLocked → HtlcReceived → PaymentInitiated`, then settle, or `RevocableHtlcs → RevocationStuck` (`B/bark/src/actions/lightning/pay.rs:293-310`).
- **Retry window.** The server caps the client's `retry_for` (`pay.rs:564-583`; `B/server/src/ln/mod.rs:299-303`). A default captaind uses 60 s when the client sends none, with a 5 min maximum (`B/server/captaind.default.toml:155,158`).
  - xpay "will keep finding routes and retrying" until then ([CLN](https://docs.corelightning.org/reference/xpay)).
  - It doesn't release an HTLC already stuck in flight.
- **Result.** Bark polls every 2 s (`pay.rs:331`) or settles from the mailbox (`B/bark/src/mailbox.rs:611-668`).
- **Stuck sends.** It revokes once the tip passes HTLC expiry (258 blocks) minus 144 (`pay.rs:113-117,163-168`; `B/bark/src/config.rs:268`), about 19 h on mainnet.
- **Wait modes.**
  - `wait: true` sleeps through parks and retries until done (`B/bark/src/actions/mod.rs:656-680`), so a stuck send blocks for hours.
  - `check_lightning_payment(hash, false)` drives an existing checkpoint to its next park (at most one status poll) and never creates a new payment (`B/bark/src/lightning/pay.rs:152-163`).
  - `wallet.sync()` drives every pending send (`B/bark/src/lib.rs:2036`).
  - A resumed send keeps its original `retry_for` (`pay.rs:376-391`).

**Apps:**
- **Paying.** Both apps pay with `wait: true` (`apps/ios/Noah/Services/Wallet/WalletSession+Send.swift:154,161`; `A/send/SdkSendWallet.kt:124,126`).
- **Journal.** Both write a journal first (`apps/ios/Noah/Features/Send/SendPayment.swift:318-322`; `A/send/SendController.kt:113-122`).
  - Reconciliation only advances existing checkpoints (`SendLightningCheckpoint.swift:56`).
  - A pending send shows "Payment may have been sent".
- **Background, iOS.** It suspends seconds after backgrounding, with only a log-drain task (`apps/ios/Noah/App/AppDelegate.swift:50-60`). BGTasks run at 15 min / 6 h earliest with 25 s budgets (`BackgroundScheduler.swift:8`, `BackgroundExecution.swift:13`).
- **Background, Android.** No foreground service. Periodic work and push jobs get 25 s budgets (`A/services/background/PeriodicWork.kt:14-27`, `A/services/push/PushJobs.kt:30-34`).
- **Server.** It pushes Lightning claims and receives from the mailbox; everything else falls through `_ => {}` (`server/src/mailbox_worker.rs:737-804`), through Expo only (`server/src/push.rs:6`).

## Lightning: `retry_for` and `wait`

**`retry_for = 60 s`, set explicitly, for every send including recurring ones:**
- **Unchanged success rates.** Users already get 60 s from a default captaind; pinning it protects Noah from operator changes.
- **Longer windows.** They help hard routes as liquidity shifts, but keep coins locked in the HTLC and delay the honest "failed, it's back, try another way".
- **Background support.** Now that nobody has to watch the screen, quick refunds matter more than waiting longer, and a push-less result is usually settled by the next launch.
- **Not 0** (a single attempt).
- **Revisit with data.** Add anonymous time-to-result telemetry and raise it to 120 s only if sends often succeed after 60 s.
- **The UI never assumes a deadline:** `retry_for` doesn't bound a stuck in-flight HTLC.

**`wait: false`:**
- **Return.** The call returns at `PaymentInitiated` (or with an immediate result), so no wallet call holds the single-writer session for hours.
- **Who drives** `checkLightningPayment(hash, wait: false)` (one status poll, never a new payment):
  - the foreground app: every 2 s for 90 s, then every 30 s;
  - the iOS handoff task;
  - the Android service;
  - push jobs, BGTasks and periodic work (`sync()` already drives pending sends);
  - the next launch.
- **Pending is a state, not an error.** `InProgress` becomes the spec's "Still sending" result. The journal and checkpoint guards still block paying the same invoice twice.
- **Recurring.** Recurring sends get the same pending state with their existing result notifications. iOS can't start Live Activities in the background.

## iOS

**Live Activity:**
- **Start.** ActivityKit (iOS 16.1+). The app requests it as hold-to-send completes; `NoahWidget` renders it; the app's Info.plist gets `NSSupportsLiveActivities`.
  - Starting needs the foreground; updating and ending work "while it runs in the background" ([Apple](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities)).
  - If activities are off (`areActivitiesEnabled`) or `targetMaximumExceeded`, show a local result notification instead.
- **Handoff.** Wrap the `pay` call and the first polls in `beginBackgroundTask`, which gives "about 30 seconds" ([DTS](https://developer.apple.com/forums/thread/85066)).
- **Stale and end.**
  - Each update sets `staleDate` to now + 3 min; a stale view reads "Open Noah to check".
  - End on wallet evidence with `.after(now + 15 min)`.
  - Limits: active "up to eight hours", plus "up to four additional hours" on the Lock Screen; 4 KB of data (same page).
- **Relaunch and force quit.**
  - Activities survive force quit ([Apple](https://developer.apple.com/news/?id=qpqf1gru)). On launch, re-attach with `Activity.activities` and reconcile.
  - A force-quit app gets no silent pushes "until it's launched again" ([Apple](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app)), so its activity goes stale until then.

**Not `BGContinuedProcessingTask` (iOS 26.0).** It must start "from the foreground… as a result of a person's action" ([Apple](https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtaskrequest)) and shows its own system progress Live Activity. It doesn't fit:
- **The handoff is too short.** It takes seconds; DTS says to skip the API under about 30 s ([forum](https://developer.apple.com/forums/thread/796944)).
- **The progress would be invented.** After the handoff the app only waits, but Apple asks for "accurate progress".
- **Swipe-away kills it.** "The system cancels any running tasks if a person closes the app in the app switcher" ([Apple](https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados)).
- **On-chain has no starting action.** Nothing starts it there.

## Android

**Lightning: `shortService`** ([types](https://developer.android.com/develop/background-work/services/fgs/service-types)):
- **The type.** It needs only `FOREGROUND_SERVICE` and runs "about 3 minutes", then `onTimeout()`. On Android 13 and lower there is no timeout, so the app keeps its own cap.
- **Timing.** Start it at the tap, while visible, so no background-start limits apply. Stop it at the result or at `retry_for + 60 s`.
- **Swipe-away.** Removing the task doesn't kill a process with a foreground service (AOSP `ActivityTaskSupervisor`).
- **After the cap.** A plain ongoing notification remains. A one-time WorkManager check (network constraint) runs at +5 min and then hourly, and the push finishes it.
- **Why not the alternatives.**
  - `dataSync` needs a Play declaration and video ([Play](https://support.google.com/googleplay/android-developer/answer/13392821)), and AOSP still says it "should NOT be used" for targetSdk 34+. Its longer life only helps stuck HTLCs, which last hours anyway.
  - UIDT jobs are API 34+ "network data transfers" without WorkManager support ([UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt)).
  - `shortService` is exempt from Play's FGS criteria ([policy](https://support.google.com/googleplay/android-developer/answer/16559646)).

**Notification:**
- **Channel.** A new "Payments in progress" channel at `IMPORTANCE_DEFAULT`, silent; `MIN` would block promotion.
- **API 36+.** `ProgressStyle` with three segments (Lock · Pay · Done) and an orange-ring coin tracker icon, plus `setShortCriticalText` ("Sending", "Paid"; ≤7 chars).
- **Live Update.** `setRequestPromotedOngoing(true)` with `POST_PROMOTED_NOTIFICATIONS` (API 36.1).
  - Requirements: ongoing, a title, no `RemoteViews`, not colorized ([Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)).
  - It is meant for things "ongoing, user-initiated, and time-sensitive", which fits Lightning but not block waits.
  - Never repost one the user dismissed.
- **AndroidX.** `NotificationCompat` from androidx.core 1.17.0+; declare it explicitly (today 1.18.0 arrives transitively). Below 36, set `setProgress(0, 0, true)` yourself.
- **Dismissal.** Since Android 14, ongoing notifications are dismissible except on the lock screen ([14](https://developer.android.com/about/versions/14/behavior-changes-all)); Activity stays the record.
- **Swipe-away vs force stop.**
  - Swipe-away keeps WorkManager and FCM working on stock Android; some OEMs differ.
  - Force stop cancels jobs and drops FCM messages until the next launch ([FCM](https://firebase.blog/posts/2024/07/understand-fcm-delivery-rates/)).
  - The "Restricted" battery setting stops foreground services, alarms and jobs ([13](https://developer.android.com/about/versions/13/behavior-changes-all)).
  - Noah shows the true state on the next launch.

## On-chain

- **Watched:**
  - outgoing transactions with a txid (on-chain wallet sends, Ark offboard/send-onchain);
  - incoming unconfirmed ones, seen in the foreground on iOS (activities can't start in the background) and in any sync on Android.
- **Data:** `onchain.tipHeight()` and `transactionStatus(txid)` from the wallet's own Esplora. Confirmations = tip − height + 1. Sends follow the spec's "1 of 2" rule; receives finish at one confirmation.
- **Refresh:**
  - foreground: on tip change, or every 30 s (spec);
  - iOS background: BGAppRefresh and push wakes;
  - Android background: one-time WorkManager checks every 10 min while a watch exists.
  - No foreground service and no Live Update, which Google says isn't for "tracking a package".
- **Stop:** at the required confirmations ("Confirmed in block N"). After 24 h, or if the transaction disappears, show "Open Noah to check"; reconciliation owns conflicts.

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
| Paying (`PaymentInitiated`, <10 s) | Sending ₿X | Paying over Lightning | step 2, indeterminate |
| Slow (≥10 s) | Sending ₿X | Still paying. This can take a few minutes. | step 2 |
| Long (> `retry_for` + 30 s) | Still sending ₿X | Taking longer than usual. If it fails, Ark signs it back to you. | step 2 |
| Returning (`RevocableHtlcs`) | Payment didn’t go through | Ark is signing ₿X back to you | grey |
| Refund stuck | Payment didn’t go through | ₿X is still locked. Open Noah. | grey |
| Paid | Sent ₿X | Paid over Lightning | full |
| Refunded | Payment failed | ₿X is back in your wallet | grey, coin back at start |
| Stale | unchanged | Open Noah to check | unchanged |
| On-chain waiting | Sending ₿X on-chain / Incoming ₿X | Waiting for block {tip+1} | blocks |
| On-chain confirmed | Sent ₿X / Received ₿X | Confirmed in block N (· 1 of 2) | blocks |

Tapping opens the Send result or the Activity row, behind the app lock as usual.

## State machine

A new app-owned **payment watch** drives the UI:
- **Storage:** atomic, fsynced JSON next to the send journal.
- **Fields:** id (payment hash or txid), kind, wallet fingerprint and network, amount, start time, `retry_for`, required confirmations, last state, and the activity or notification id.
- **Lifecycle:** created only after the user confirms. It is display-only: it never authorizes or triggers a payment.

Each driver tick:

| Wallet evidence | Watch state | Then |
| --- | --- | --- |
| `InProgress`, phase before `PaymentInitiated` | Locking | drive |
| `InProgress(PaymentInitiated)` | Paying → Slow → Long (elapsed time) | drive |
| `InProgress(Revoking)` | Returning | drive |
| `InProgress`, `hasFailedRevocation` | Refund stuck | stop active drivers; recheck on launch |
| `Paid` + preimage + exactly one successful `bark.lightning_send` movement matching hash and amount | Paid | end |
| not in progress + failed movement, effective balance 0, no exited VTXOs | Refunded | end |
| failed movement with exited VTXOs | "Returning on-chain" | end; Exits shows it |
| no checkpoint and no movement, or a read error | last state, stale | retry later |
| wallet deleted, suspended, restored, or fingerprint changed | — | end all activities and notifications, delete watches |

**Admission.** Drivers keep today's admission rules:
- iOS: `WalletSession` counted calls in the foreground; `withBackgroundWallet` and `HeadlessWalletGate` headless.
- Android: `withOpen`; `requireBackgroundWallet` under `WalletBackgroundActivity.exclusive`.

On deletion or suspension, `WalletLifecycle` also stops the Android service and clears the watches.

## Safety rules

1. **No double payment.**
   - Background code calls only `checkLightningPayment(wait: false)`, `lightningSendState`, `pendingLightningSends`, `sync`, `transactionStatus` and `tipHeight`.
   - A test fake fails on any `pay*` call outside the user's submit.
   - Today's checkpoint-match and "already in progress" guards stay.
   - Driving a checkpoint that stopped before `PaymentInitiated` (the process died mid-handoff) completes that same confirmed payment, as `sync()` already does today. It never starts another. If the server refuses it (an expired invoice, for example), bark fails or revokes the send.
2. **No claim without evidence.** Only the rows above move the UI to "Sent" or "back in your wallet"; pushes and timers never do.
3. **Bounded battery use.**
   - One `shortService` per send, at most 2 min.
   - Background on-chain checks at most every 10 min.
   - Watches expire after 24 h.
   - Before first unlock the seed can't be read, so watches go stale.
4. **Privacy.**
   - Nothing outside the app shows the recipient, addresses, txids or preimages.
   - **Hide-balance on** (`isHomeBalanceHidden`): no amounts either ("Sending a payment", ₿••••).
   - **Hide-balance off:** amounts show.
     - Android: `VISIBILITY_PRIVATE`, with an amount-free public version for secure lock screens.
     - iOS: mark amounts `.privacySensitive()`. Apple doesn't document whether Live Activities honour that, so the hide-balance setting is the guarantee.
   - Toggling the setting updates live items.

## FFI changes (bark-bolt-ffi on bark 0.8.0)

**Required:**
1. **Options record:** `LightningSendOptions { wait: bool, retry_for_secs: Option<u32> }`.
   - `None` uses the server default.
   - `0` makes a single attempt.
   - Larger values are capped by the server.
   - Document that it is ignored when an existing checkpoint resumes.
2. **New methods.** Add these, mapping to bark's `*_with`, and keep the old ones:
   - `pay_lightning_invoice_with(invoice, amount_sats: Option<u64>, options) -> LightningSendStatus`
   - `pay_lightning_offer_with(offer, amount_sats: Option<u64>, options)`
   - `pay_lightning_address_with(address, amount_sats, comment: Option<String>, options)`
   - `pay_lnurl_with(lnurl, amount_sats, comment: Option<String>, options)`

   Noah uses the first two.
3. **New fields on `LightningSend`** (`F/src/types.rs:416`):
   - `phase: LightningSendPhase` (`Start | InputsLocked | HtlcReceived | PaymentInitiated | Revoking | RevocationStuck`, 1:1 from `Progress`);
   - `retry_for_secs: Option<u32>`;
   - `htlc_expiry_height: u32`.
4. **Documented contracts:**
   - `pay_*_with(wait: false)` returns `InProgress` (normally at `PaymentInitiated`) or `Paid`;
   - `check_lightning_payment(hash, false)` never creates a payment and returns at the next park, after at most one status poll;
   - `wait: true` can block until near HTLC expiry.

**Recommended:**
5. A `LightningSendStatus::Failed { payment_hash, movement_id }` variant. Today a send revoked inside the call reads `Unknown`, just like "never started". It breaks exhaustive switches, so it belongs in the 0.8 bump.

**Not needed:**
- progress callbacks: movement notifications plus `phase` suffice;
- a tip height on `Wallet`: `OnchainWallet.tipHeight()` exists.

## Server changes

**Required:**
- **Handler.** In `process_mailbox_message` (`server/src/mailbox_worker.rs:711`), handle `Message::LightningSendFinished` with a content-available, high-priority push without title or body.
  - Keep the catch-up suppression (`:817`).
  - Only users with mailbox authorization are covered; others rely on BGTasks and the next launch.
- **Type.** Add `NotificationData::LightningSendFinished(LightningSendFinishedNotification { notification_id })` to `server/src/types.rs:612`.
  - The payload is a random id only, so Android's per-payload unique work never merges two pushes.
  - No hash, amount, outcome or preimage: the device checks all its pending sends.
  - Regenerate the TS and OpenAPI clients and test beside the mailbox-worker tests.

**Optional (last phase):**
- **Raw APNs.** A token-auth (.p8) sender for `liveactivity` pushes, plus per-activity token registration.
  - Expo's message format has no push-type or topic field ([Expo](https://docs.expo.dev/push-notifications/sending-notifications/)).
  - It only lets the server mark a force-quit app's activity "Finished · open Noah to see the result", never "Sent".
  - It builds on the "Raw APNs/FCM token registration" backlog item.

## Risks

- **iOS pushes are discretionary** (Apple suggests "two or three per hour") and skip force-quit apps, so "Open Noah to check" will be common on iOS.
- **Stuck HTLCs** can stay pending for about 19 h, beyond the 8 h activity and the Android service; then only Noah shows them.
- **Recurring and existing tests.** `wait: false` changes pending semantics for recurring payments and for today's "may have been sent" tests.
- **Many concurrent sends** mean many activities, against an unspecified system limit. Fall back to notifications.
- **Intermediaries learn timing.** Expo, Apple and Google learn that a send finished at time T. Noah's server already reads the mailbox.

## Phased plan and tests

1. **SDK.** Adopt the new FFI with `wait: true, retry_for: 60`. Tests: options reach the call; an unroutable regtest invoice fails and refunds; current suites stay green.
2. **Pending state.** `wait: false`, the pending state, the watch store and in-app drivers.
   - Tests:
     - every evidence→state row;
     - the "no `pay*` outside submit" fake;
     - process death between handoff and result;
     - recurring sends left pending.
   - Regtest: an LND hold invoice (`just lncli addholdinvoice`) keeps a send pending. Settle it → Sent; cancel it → Refunded.
3. **Server push and push jobs.** Tests: the payload has no hash, amount or preimage; the job drives and ends the watch. Device: background the app, then settle the hold invoice.
4. **System UI.** The Live Activity, plus the Android service and notification.
   - Tests: content per state with hide-balance on and off; the service's cap and `onTimeout`.
   - Manual: swipe-away (Pixel), force quit (iPhone), Live Activities off.
   - Recordings per platform.
5. **On-chain watches.** Tests: confirmation maths, stop rules, not-found. Regtest: `bcli sendtoaddress`, then mine one and two blocks.
6. **Optional:** raw APNs Live Activity updates.

## Open questions

1. Is 60 s right for every send, including recurring ones? Should large amounts get longer?
2. Is raw APNs (a .p8 key and a new sender) worth it, only to mark a force-quit app's activity "Finished · open Noah"?
3. Should the server ping devices that registered "waiting on a block" (no txid) on each new block? Recommended: no; the server stays out of on-chain.
4. Should incoming on-chain payments start a Live Activity or notification automatically, or only while Receive is open?
5. Is the privacy rule right: never the recipient, and amounts follow hide-balance?
6. When Live Activities are off, should results arrive as ordinary notifications ("Sent ₿X")? Recommended: yes.
