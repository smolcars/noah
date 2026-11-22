---
name: verify-mailbox-lease
description: Check a change to the Noah server's mailbox worker lease against its TLA+ model (formal/mailbox-lease). Use when a change touches server/src/mailbox_worker.rs, server/src/db/mailbox_authorization_repo.rs or migrations 0010 to 0012, when `just formal-mailbox-lease` or the "Mailbox lease model" CI job fails or reports DRIFT, or when you need to know whether two workers can push the same mailbox message.
---

# Verify the mailbox lease

The mailbox worker gives one worker one mailbox through a lease on its `mailbox_authorizations` row (`lease_owner`, `lease_expires_at`). `formal/mailbox-lease/MailboxLease.tla` models that lease, and TLC checks every order of its steps for two workers, one mailbox and three messages. Read `formal/mailbox-lease/README.md` first. It maps each model action to the Rust function or SQL statement it stands for, says which steps it joins across an await and why, states each rule and its harm, and lists the findings at master, the approximations and what the model leaves out.

## Does the change touch the lease?

The model covers these files, recorded with their git blob hashes in `formal/mailbox-lease/coverage.txt`:

- `server/src/mailbox_worker.rs`
- `server/src/db/mailbox_authorization_repo.rs`
- `server/migrations/0010_add_mailbox_authorizations.sql`, `0011_add_mailbox_worker_state.sql`, `0012_add_mailbox_worker_leases.sql`

`coverage.txt` also records `synced-at`: the commit at which someone last confirmed that the model describes these files. Any edit to a covered file makes the check report DRIFT, also an edit the lease never sees.

The change touches the lease when it changes any of these:

- the SQL or the bound values (including the `Utc::now()` taken before each query) of `claim_runnable`, `bulk_renew_claims`, `claim_is_active`, `update_checkpoint`, `clear_error`, `mark_retry`, `release_claim` or `release_all_claims`, or the lease columns in the migrations;
- which of those calls run, in what order, or with an `.await` between them, in `schedule_runnable_sessions`, `renew_active_sessions`, `handle_session_result`, `shut_down`, `process_mailbox_session`, `run_session` or `process_mailbox_message`;
- when `send_push_notification` runs relative to `claim_is_active` and `update_checkpoint`, or `should_suppress_catchup_notifications`;
- how a session is cancelled or ends.

To tell, diff the covered files against `synced-at` (`git diff <synced-at> -- <file>`) and look for those symbols. A change to notification text, amounts, logging, the channel pool or decoding does not touch the lease.

## Run the check

```sh
just formal-mailbox-lease
```

It needs Java 17 or later and runs in about a minute and a half. It exits non-zero on any of these:

- `FAIL MailboxLease.cfg`: a rule that held at master breaks. Treat it as a real bug until you show otherwise. A FAIL that names no violated rule, or shows an exit status other than 0 or 12, is TLC failing (a parse error, out of memory, a killed JVM); read the log it prints.
- `FAIL bug-*.cfg`: a past bug replay no longer fails on its rule. The model has stopped catching that bug; fix the model.
- `FAIL finding-*.cfg`: a known finding at master no longer fails. If the change fixed it, move the rule into `MailboxLease.cfg`, delete the finding config and its line in `check.sh`, and update the README. Otherwise the model changed; find out why.
- `DRIFT`: a covered file changed since `coverage.txt`, or has no row in it. TLC still runs, so you see its result for the model as it is.

`PASS finding-*.cfg` and the `KNOWN FINDING` counterexamples it prints are expected at master.

## Read every result with /statechart

Read each TLC result, pass or fail, with the pstack `/statechart` skill, on `formal/mailbox-lease/MailboxLease.tla` and the config in question. For a violated rule it gives the shortest path to the break in plain steps; for a pass it draws how the lease moves between states. Map each step back to code through the README's action table.

## Real bug or modeling error?

Replay the counterexample against the code, one action at a time, using the README's table:

- Each step must be something the code can do. If a step needs two SQL statements to run as one, or skips an `.await` where another worker or lease expiry can come in, the model is wrong: fix the model. The README's table names the few joins kept on purpose and why each loses no behavior; a change that breaks one of those arguments (for example, a renewal that no longer runs in the same task as the claim) means the join must be split.
- `claim_is_active` is two steps, `StartSample`/`CheckSample` (the `Utc::now()` it binds) and `StartQuery`/`CheckQuery` (the SELECT), because the lease can lapse while the query is in flight.
- If every step matches the code, it is a real bug. Do not weaken the rule or the model to make it pass, and do not move a rule into a `finding-*.cfg` to make CI green. Report it with the path in the code's words, as the README's findings do.
- A step that needs the lease to lapse (`Expire`) is real: renewal errors are only logged, and the model lets a lease lapse at any step on purpose.

## Update the model for an intended change

1. Find the actions the change affects in the README's table, and change those actions in `MailboxLease.tla` so each one is still one SQL statement or one stretch of code with no await, or a join the README argues loses no behavior.
2. Update the README's table and rules when an action, rule or finding changes. Rewrite a finding's numbered steps only from a trace TLC printed.
3. Run `just formal-mailbox-lease` and read the results with `/statechart`. `MailboxLease.cfg` must pass, and each `bug-*.cfg` and `finding-*.cfg` must end as `check.sh` expects.
4. Only after you have confirmed that the model describes the covered code at your commit, run `formal/mailbox-lease/check.sh --record`. It rewrites the blob hashes and sets `synced-at` to `HEAD`, so commit the code change first. Commit `coverage.txt` with the change.

When a change does not touch the lease, still run the check, then `--record`, and say in the commit message that the model was confirmed unchanged.
