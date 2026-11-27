# Mailbox lease model

`MailboxLease.tla` is a TLA+ model of the database lease that gives one mailbox worker one mailbox (`mailbox_authorizations.lease_owner` and `lease_expires_at`). TLC checks every order of its steps for two workers, one mailbox row and three messages. No CONSTRAINT cuts the search: the per-checkpoint push counter stops at 2 inside `Push`, so states after a third push of one checkpoint are still explored.

Run it with `just formal-mailbox-lease` (or `formal/mailbox-lease/check.sh`). It needs Java 17 or later and downloads `tla2tools.jar` v1.7.4 into `formal/.tools/` (ignored by git), checked against a pinned sha256. The run takes about a minute and a half on a laptop (83 s measured; 61 to 76 s of that is `MailboxLease.cfg`, 13,020,369 distinct states). `check.sh` requires TLC's exit status to agree with its log (0 for a pass, 12 for a safety violation), so a crashed or killed run fails. CI runs it in `.github/workflows/formal-mailbox-lease.yml`. Agents changing the covered code follow `.agents/skills/verify-mailbox-lease/SKILL.md`.

## What each step stands for

Most steps are one SQL statement, or local Rust work with no await on another actor in it. Where the code awaits between two parts and the model still joins them, the Notes column says why the join loses no behavior, or what it hides.

| Action | Code | Notes |
| --- | --- | --- |
| `Post` | captaind stores a message in the mailbox | Outside this repo. |
| `Expire` | wall clock passes `lease_expires_at` | Allowed at any step. Renewal can fail (`run` only logs the error), stall behind `claim_runnable` in the same `select!` loop, or come late. The model has no clock: TTL, renewal interval and jitter are not modeled. |
| `Claim` | `MailboxAuthorizationRepository::claim_runnable` (one `WITH ... UPDATE`), then the loop in `MailboxWorker::schedule_runnable_sessions` | Joined, though the UPDATE commits before the worker reads the response and spawns. The join loses nothing: only this worker's run loop reads or changes its `active_sessions` (renewal, join, `shut_down`), and it is busy in `schedule_runnable_sessions` until the spawn, so the `contains_key` skip is fixed at the UPDATE. A session spawned at the UPDATE waits in `starting` while other workers and expiry step, which covers another worker taking the lease before the real spawn. |
| `Renew` | `MailboxWorker::renew_active_sessions`: `bulk_renew_claims` (one statement), then cancel the sessions it did not return | Joined. After the statement reports a lease lost, only this worker's run loop could make it this worker's again, and the loop is busy until the cancels; a session's check that reads the token before the cancel took its `Utc::now()` before it too, which `CheckSample`/`CheckQuery` cover. Approximations: a live lease outside the renewal window is valid but not extended; with no clock both are "live". A renewal whose `Utc::now()` predates expiry can revive a lapsed lease no one else took; `Renew` has no such step, but `Claim` by the same worker reaches the same state. |
| `Join` | `MailboxWorker::handle_session_result` | Removes the entry from `active_sessions`. |
| `StartSample` | `Utc::now()` for the `claim_is_active` at the top of `process_mailbox_session` | No token read comes first, so a cancelled session still runs this check. |
| `StartQuery` | that `claim_is_active` SELECT | In the code its lease test is `lease_owner` is this worker and `lease_expires_at > $5`, the time sampled (it also tests `auth_version` and the authorization, which the model leaves out). The model stands in for that comparison with "the lease was live at some moment since the sample", which is stronger than the code: a late renewal can satisfy the SQL with no live lease in that span (see below the properties table). False returns before `clear_error` and `release_claim`. |
| `CheckSample` | `cancellation_token.is_cancelled()` and `Utc::now()` in `process_mailbox_message` | Needs a message past the session's checkpoint. |
| `CheckQuery` | that `claim_is_active` SELECT | As `StartQuery`. False ends the session as `Completed`. The token is not read again. |
| `Push` | `send_push_notification` in `process_mailbox_message` | No push while `should_suppress_catchup_notifications` holds. Counts calls, not deliveries: delivery to APNs and FCM runs inside the call (`push.rs`), so a call that `join_set.shutdown()` aborts may have delivered to none, some or all tokens. |
| `UpdateCheckpoint` | `update_checkpoint` (one UPDATE guarded on `lease_owner`, not on `lease_expires_at`), or for an Arkoor message matched to an LNURL-pay receive, the same UPDATE in `LnurlPayReceiveMetadataRepository::mark_ready_and_update_checkpoint`'s transaction | False ends the session as `Completed`; a lost lease in the matched case is an error, which ends it as `Retryable`. The LNURL-pay matching before `Push` only reads and writes its own tables and ends a session early without a push or a checkpoint move: `Completed` when more records remain to check (as `StopOnCancel`), `Retryable` when barkd's answer is pending or failed (as `StreamEnds`). |
| `CaughtUp` | `read_mailbox` returns no messages in `Beta8MailboxTransport::run_session` | Turns catch-up suppression off. Requires the session to be at the newest stored message. captaind can store a message after it answers the read and before suppression turns off; that `Post` commutes past this step, since nothing else reads suppression, so the requirement loses no behavior. |
| `StopOnCancel` | the `cancellation_token` checks in `run_session` | Only after the first `claim_is_active` passed; returns `Completed`, so `clear_error` frees the row. |
| `StreamEnds` | the stream ends or errors; `run_session` returns `Retryable` | |
| `ClearError` | `clear_error` or `mark_retry` (one UPDATE each, guarded on `lease_owner`) | Both clear the lease. |
| `ReleaseClaim` | `release_claim` at the end of `process_mailbox_session` | |
| `BeginShutdown` | `MailboxWorker::shut_down` cancels every session token | |
| `AbortSessions` | `join_set.shutdown()` after `SHUTDOWN_GRACE` | |
| `ReleaseAll` | `release_all_claims` (one UPDATE) | Runs after every session has stopped. |
| `Killed` | the process dies on SIGTERM with no handler | Only with `BugNoShutdownRelease`. |

Files: `server/src/mailbox_worker.rs`, `server/src/db/mailbox_authorization_repo.rs`, migrations 0010 to 0012. `coverage.txt` records their git blob hashes and the commit at which the model was last confirmed against them. `check.sh` reports DRIFT when a covered file changed or has no row in `coverage.txt`.

## Properties

| Invariant | Rule | Harm if broken | At master |
| --- | --- | --- | --- |
| `OneSessionPerWorker` | A worker runs at most one session for a mailbox. | One worker streams the mailbox twice and pushes each payment twice. | Holds |
| `NoClaimOfLiveLease` | `claim_runnable` never takes a lease that is still live, the claimer's own included. | A worker re-takes mailboxes it already streams; those rows use up the scan's `LIMIT` and only the `active_sessions` skip stops a second session. | Holds |
| `CheckTestsExpiry` | `claim_is_active` passes only through its `lease_expires_at > $5` test. | A worker keeps processing a lapsed lease for as long as no one else claims it. | Holds. This is the SQL test restated, so it fails only when that test is removed (`bug-no-expiry-guard.cfg`). It is a switch for that replay, not a safety guarantee. |
| `StoppedWorkerHoldsNoLease` | A worker that has shut down holds no live lease. | After a deploy no worker can claim the mailbox until the lease runs out (120 s), so payments in that gap get no push. | Holds |
| `CheckOnlyUnderLiveLease` | A worker decides to push only while its lease is live. | A worker whose lease lapsed pushes while another worker takes the mailbox over and pushes the same message. | **Fails** (finding 1) |
| `AtMostOnePushPerCheckpoint` | Each checkpoint is pushed at most once. | The user gets the same payment notification twice. | **Fails** (finding 2) |
| `CheckpointNeverMovesBack` | `last_checkpoint` never moves down. | Messages already handled are read again later. | **Fails** (finding 3) |

So master guarantees nothing about the lease being live when a check passes. A check can pass after the lease lapsed (finding 1). It can also pass when the lease was not live at any moment since the session read the clock: `renew_active_sessions` computes the new expiry from its own earlier `Utc::now()`, so a `bulk_renew_claims` statement that runs late writes an expiry that is already past for everyone except a session whose clock read is older still. The model does not represent that late write. Its `Renew` makes the lease live at the moment it runs.

Why `AtMostOnePushPerCheckpoint` is the duplicate-push rule: delivery is at least once by design, since a push followed by a failed `update_checkpoint` statement is replayed. The model has no SQL errors, so every duplicate it finds happens with every statement succeeding. A duplicate found here is one the lease was meant to prevent, not the designed replay.

## Past bugs as switches

Each `bug-*.cfg` turns one fault back on. `check.sh` requires each to fail on the named invariant.

| Config | Fault put back | Fails on |
| --- | --- | --- |
| `bug-86b883f05-self-reclaim.cfg` | `claim_runnable` also selects rows where `lease_owner = $3` | `NoClaimOfLiveLease` |
| `bug-no-expiry-guard.cfg` | `claim_is_active` and `bulk_renew_claims` test `lease_owner` but not `lease_expires_at` | `CheckTestsExpiry` |
| `bug-3ed2b04c8-no-shutdown-release.cfg` | SIGTERM kills the worker with its leases held | `StoppedWorkerHoldsNoLease` |

What the replays show:

- 86b883f05: the `active_sessions.contains_key` skip existed before this fix (`git show 86b883f05^:server/src/mailbox_worker.rs`). With the old predicate on, `OneSessionPerWorker` still holds over 26,040,738 distinct states (measured). The old predicate re-took live leases; it did not start a second session.
- `bug-no-expiry-guard.cfg` is today's protocol with the expiry test that b018632a5 added removed. It is not the code before b018632a5: that code awaited `renew_mailbox_claim` (which extended the lease) before each message (`git show b018632a5^:server/src/mailbox_worker.rs`), a different operation from today's check. The replay shows only that the model carries the expiry test.
- The replays are narrow: each switch turns one predicate back into an older one.

## Findings at master

`finding-*.cfg` check the three rules that fail at master. `check.sh` expects them to fail and prints each counterexample, so CI stays green while the findings stay visible. If one of them starts to pass, `check.sh` fails: move the rule into `MailboxLease.cfg` and drop the finding. The steps below are TLC's shortest counterexamples (one worker thread), in the code's words. Every one needs the lease to lapse, which with the default 120 s lease renewed every 30 s needs renewal to fail or stall (inferred).

Finding 1, `CheckOnlyUnderLiveLease` (`finding-stale-check.cfg`, 8 states): a check passes on a lapsed lease.

1. captaind stores message 1.
2. W1 `claim_runnable` takes the row; its session's first `claim_is_active` passes.
3. `read_mailbox` returns message 1. `process_mailbox_message` finds the token uncancelled and takes `Utc::now()`.
4. W1's `lease_expires_at` passes while the query is in flight.
5. The SELECT compares against the earlier `now` and passes, so W1 goes on toward `send_push_notification` with a lapsed lease. (Here the push is suppressed because the session caught up from checkpoint 0; finding 2 shows the push.)

Finding 2, `AtMostOnePushPerCheckpoint` (`finding-duplicate-push.cfg`, 19 states): two workers push the same message.

1. W1 `claim_runnable` takes the row (`last_checkpoint = 0`) and spawns a session. The session takes `Utc::now()`, W1's lease lapses, and its first `claim_is_active` still passes against that earlier `now`.
2. W2 `claim_runnable` takes the lapsed row and spawns a session; its first `claim_is_active` passes.
3. Both sessions' `read_mailbox` return nothing; both turn catch-up suppression off and subscribe from checkpoint 0.
4. captaind stores message 1, and both streams deliver it.
5. Both `process_mailbox_message` calls find their tokens uncancelled and take `Utc::now()` while W2's lease is live.
6. W2's lease lapses; W2's SELECT passes against its earlier `now`.
7. W1 `claim_runnable` re-takes the lapsed row (its first session is still in `active_sessions`, so it spawns nothing); W1's SELECT passes.
8. W1 and W2 both call `send_push_notification` for checkpoint 1.

Step 7 needs W1's `renew_active_sessions` not to have run while W2 held the row, or to have errored (the error is only logged); a renewal then would cancel W1's session (inferred). The window also opens without the stale clock read: a check that passes just before the lease lapses leaves the push and `update_checkpoint` (which tests only `lease_owner`) to run after it, and the push can take longer than the 20 s `SEND_DEADLINE` of one direct send (`server/src/direct_push.rs`), because `send_push_notification` also awaits the token lookup and any Expo or UnifiedPush send (inferred; TLC's shortest trace takes the path above).

Finding 3, `CheckpointNeverMovesBack` (`finding-checkpoint-moves-back.cfg`, 24 states): a stale session rewinds the checkpoint.

1. captaind stores messages 1 and 2.
2. W1 `claim_runnable` takes the row (`last_checkpoint = 0`) and spawns a session that has not yet run.
3. W1's lease lapses. W2 `claim_runnable` takes the row and spawns a session.
4. W1's session takes `Utc::now()` for its first `claim_is_active` and awaits it.
5. W2's session processes messages 1 and 2 with notifications suppressed (it started from 0); `update_checkpoint` sets `last_checkpoint = 1`, then 2. W2's lease lapses during the second check, which still passes.
6. W1 `claim_runnable` re-takes the row; W1's first session is still in `active_sessions`, so it spawns nothing.
7. W1's first session's `claim_is_active` passes (W1 owns a live lease). It reads message 1 from checkpoint 0 and `update_checkpoint` sets `last_checkpoint = 1`.

The trace shows only that `last_checkpoint` moves from 2 back to 1, so the next session reads message 2 again. Whether that re-read sends a second push depends on whether message 2 was pushed the first time; in this trace it was not, since W2 was catching up from checkpoint 0 with notifications suppressed. Like finding 2, step 6 needs W1's renewal not to cancel the first session while W2 held the row (inferred).

## Approximations and what the model leaves out

- Joined steps (see the table): `Claim` joins the UPDATE and the spawn, `Renew` joins the statement and the cancels, `CaughtUp` requires the session to be at the newest message. Each is argued above to lose no behavior (inferred, not checked by TLC).
- `Renew` sets the lease live whether or not the statement extended it, and has no step that revives a lapsed lease from a stale clock read; `Claim` reaches the same state.
- `Push` counts `send_push_notification` calls, not deliveries. A call aborted at shutdown may have delivered to none, some or all of the user's tokens.
- SQL errors and timeouts, so the designed at-least-once replay is not modeled.
- Revocation and reauthorization (`revoke`, `upsert`, `auth_version`), `mark_invalid`, `mark_expired`, `mark_expired_authorizations` and `next_retry_at` backoff. 4cd39132f and cc6b1e389 are not replayed.
- Timing: renewal interval, TTL and jitter (0f0ce905b, d15c46fad). Expiry at any step covers every timing.
- More than one mailbox, the scan `LIMIT`, and liveness (every message is eventually pushed).
- Message kinds: every message is treated as one that pushes.
- A crash at master (SIGKILL, OOM): only the pre-3ed2b04c8 SIGTERM path kills a worker.
