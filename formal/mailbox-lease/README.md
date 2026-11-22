# Mailbox lease model

`MailboxLease.tla` is a TLA+ model of the database lease that gives one mailbox worker one mailbox (`mailbox_authorizations.lease_owner` and `lease_expires_at`). TLC checks every order of its steps for two workers, one mailbox row and three messages.

Run it with `just formal-mailbox-lease` (or `formal/mailbox-lease/check.sh`). It needs Java 17 or later and downloads `tla2tools.jar` v1.7.4 into `formal/.tools/` (ignored by git), checked against a pinned sha256. The run takes about 50 s on a laptop. CI runs it in `.github/workflows/formal-mailbox-lease.yml`. Agents changing the covered code follow `.agents/skills/verify-mailbox-lease/SKILL.md`.

## What each step stands for

One step is one SQL statement, or local Rust work with no await on another actor in it. A function that awaits between two statements is two steps, so other workers and lease expiry can come between them.

| Action | Code | Notes |
| --- | --- | --- |
| `Post` | captaind stores a message in the mailbox | Outside this repo. |
| `Expire` | wall clock passes `lease_expires_at` | Allowed at any step. Renewal can fail (`run` only logs the error), stall behind `claim_runnable` in the same `select!` loop, or come late. |
| `Claim` | `MailboxAuthorizationRepository::claim_runnable` (one `WITH ... UPDATE`), then the loop in `MailboxWorker::schedule_runnable_sessions` | The `active_sessions.contains_key` skip and the spawn run with no await after the UPDATE. |
| `Renew` | `MailboxWorker::renew_active_sessions`: `bulk_renew_claims` (one statement), then cancel the sessions it did not return | |
| `Join` | `MailboxWorker::handle_session_result` | Removes the entry from `active_sessions`. |
| `StartCheck` | `claim_is_active` at the top of `process_mailbox_session` | A false result returns before `release_claim`. |
| `CheckActive` | `claim_is_active` in `process_mailbox_message` | Includes the `cancellation_token.is_cancelled()` read before it. |
| `Push` | `send_push_notification` in `process_mailbox_message` | No push while `should_suppress_catchup_notifications` holds. |
| `UpdateCheckpoint` | `update_checkpoint` (one UPDATE guarded on `lease_owner`, not on `lease_expires_at`) | False ends the session as `Completed`. |
| `CaughtUp` | `read_mailbox` returns no messages in `Beta8MailboxTransport::run_session` | Turns catch-up suppression off. |
| `StopOnCancel` | the `cancellation_token.cancelled()` branches in `run_session`, and the token check in `process_mailbox_message` | |
| `StreamEnds` | the stream ends or errors; `run_session` returns `Retryable` | |
| `ClearError` | `clear_error` or `mark_retry` (one UPDATE each, guarded on `lease_owner`) | Both clear the lease. |
| `ReleaseClaim` | `release_claim` at the end of `process_mailbox_session` | |
| `BeginShutdown` | `MailboxWorker::shut_down` cancels every session token | |
| `AbortSessions` | `join_set.shutdown()` after `SHUTDOWN_GRACE` | |
| `ReleaseAll` | `release_all_claims` (one UPDATE) | Runs after every session has stopped. |
| `Killed` | the process dies on SIGTERM with no handler | Only with `BugNoShutdownRelease`. |

Files: `server/src/mailbox_worker.rs`, `server/src/db/mailbox_authorization_repo.rs`, migrations 0010 to 0012. `coverage.txt` records their git blob hashes and the commit at which the model was last confirmed against them.

## Properties

| Invariant | Rule | Harm if broken | At master |
| --- | --- | --- | --- |
| `OneSessionPerWorker` | A worker runs at most one session for a mailbox. | One worker streams the mailbox twice and pushes each payment twice. | Passes |
| `NoClaimOfLiveLease` | `claim_runnable` never takes a lease that is still live, the claimer's own included. | A worker re-takes mailboxes it already streams; those rows use up the scan's `LIMIT` and only the `active_sessions` skip stops a second session. | Passes |
| `CheckOnlyUnderLiveLease` | A worker decides to push only while its lease is live. | A worker whose lease lapsed pushes while another worker takes the mailbox over and pushes the same message. | Passes |
| `StoppedWorkerHoldsNoLease` | A worker that has shut down holds no live lease. | After a deploy no worker can claim the mailbox until the lease runs out (120 s), so payments in that gap get no push. | Passes |
| `AtMostOnePushPerCheckpoint` | Each checkpoint is pushed at most once. | The user gets the same payment notification twice. | **Fails** (finding 1) |
| `CheckpointNeverMovesBack` | `last_checkpoint` never moves down. | Messages already handled are read and pushed again later. | **Fails** (finding 2) |

Why `AtMostOnePushPerCheckpoint` is the duplicate-push rule: delivery is at least once by design, since a push followed by a failed `update_checkpoint` statement is replayed. The model has no SQL errors, so every duplicate it finds happens with every statement succeeding. A duplicate found here is one the lease was meant to prevent, not the designed replay.

## Past bugs as switches

Each `bug-*.cfg` turns one fixed fault back on. `check.sh` requires each to fail on the named invariant.

| Config | Fault put back | Fails on |
| --- | --- | --- |
| `bug-86b883f05-self-reclaim.cfg` | `claim_runnable` also selects rows where `lease_owner = $3` | `NoClaimOfLiveLease` |
| `bug-b018632a5-no-lease-expiry.cfg` | `claim_is_active` and `bulk_renew_claims` test `lease_owner` but not `lease_expires_at` | `CheckOnlyUnderLiveLease` |
| `bug-3ed2b04c8-no-shutdown-release.cfg` | SIGTERM kills the worker with its leases held | `StoppedWorkerHoldsNoLease` |

What the replays show:

- 86b883f05: the `active_sessions.contains_key` skip existed before this fix (`git show 86b883f05^:server/src/mailbox_worker.rs`). With the old predicate on, `OneSessionPerWorker` still holds over 7,645,710 distinct states. The old predicate re-took live leases; it did not start a second session.
- b018632a5: the fix makes the check refuse a lease that has already lapsed. It does not close the duplicate push: at master the lease can lapse just after the check (finding 1).

## Findings at master

`finding-*.cfg` check the two rules that fail at master. `check.sh` expects them to fail and prints each counterexample, so CI stays green while the findings stay visible. If one of them starts to pass, `check.sh` fails: move the rule into `MailboxLease.cfg` and drop the finding.

Finding 1, `AtMostOnePushPerCheckpoint`: one message pushed by two workers.

1. W1 `claim_runnable` sets `lease_owner = W1`; its session catches up to checkpoint 1 and subscribes.
2. captaind stores message 2.
3. W1 `process_mailbox_message` calls `claim_is_active`, which passes.
4. W1's `lease_expires_at` passes before its `update_checkpoint` runs.
5. W1 `send_push_notification` for checkpoint 2.
6. W2 `claim_runnable` sees the expired lease, sets `lease_owner = W2`, and opens a session from `last_checkpoint = 1`.
7. W2's `read_mailbox` returns message 2; its `claim_is_active` passes; W2 `send_push_notification` for checkpoint 2 again.
8. W1 `update_checkpoint` fails its `lease_owner` guard and its session ends.

The window is from W1's `claim_is_active` to its `update_checkpoint`, which includes the push (`SEND_DEADLINE` is 20 s in `server/src/direct_push.rs`). With the default 120 s lease renewed every 30 s, the lease reaches it only if renewal fails or stalls (inferred).

Finding 2, `CheckpointNeverMovesBack`: a stale session rewinds the checkpoint.

1. captaind stores messages 1 to 3.
2. W1 `claim_runnable` takes the row and spawns a session holding `last_checkpoint = 0`; the session has not yet run its first `claim_is_active`.
3. W1's lease expires. W2 `claim_runnable` takes the row and processes messages 1 and 2; `last_checkpoint = 2`.
4. W2's lease expires. W1 `claim_runnable` takes the row again; W1 still has its first session in `active_sessions`, so it spawns nothing.
5. W1's first session runs: `claim_is_active` passes (W1 owns a live lease), it reads message 1 from checkpoint 0, and `update_checkpoint` sets `last_checkpoint = 1`.

A later session then starts from 1 and pushes message 2 again. This needs W1's `renew_active_sessions` not to cancel the first session while W2 held the row, for example because `bulk_renew_claims` errored (the error is only logged) (inferred).

## What the model leaves out

- SQL errors and timeouts, so the designed at-least-once replay is not modeled.
- Revocation and reauthorization (`revoke`, `upsert`, `auth_version`), `mark_invalid`, `mark_expired`, `mark_expired_authorizations` and `next_retry_at` backoff. 4cd39132f and cc6b1e389 are not replayed.
- Timing: renewal interval, TTL and jitter (0f0ce905b, d15c46fad). Expiry at any step covers every timing.
- More than one mailbox, the scan `LIMIT`, and liveness (every message is eventually pushed).
- Message kinds: every message is treated as one that pushes.
- A crash at master (SIGKILL, OOM): only the pre-3ed2b04c8 SIGTERM path kills a worker.
