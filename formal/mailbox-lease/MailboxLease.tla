---------------------------- MODULE MailboxLease ----------------------------
(***************************************************************************)
(* The lease that gives one mailbox worker one mailbox, at one             *)
(* mailbox_authorizations row. README.md maps every action to the Rust     *)
(* function or SQL statement it stands for.                                *)
(*                                                                         *)
(* One action is one SQL statement, or local work with no await on another *)
(* actor between its start and its end. A Rust function that awaits      *)
(* twice is two or more actions.                                           *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS
    Workers,           \* distinct worker_id values, one per process
    NoWorker,          \* lease_owner IS NULL
    MaxCheckpoint,     \* messages captaind posts to the mailbox
    BugSelfReclaim,    \* claim_runnable predicate from before 86b883f05
    BugNoExpiryGuard,  \* claim_is_active and renewal without the lease_expires_at test b018632a5 added
    BugNoShutdownRelease \* SIGTERM handling from before 3ed2b04c8

Checkpoints == 1..MaxCheckpoint

VARIABLES
    owner,       \* lease_owner
    live,        \* lease_expires_at > now()
    lastCp,      \* last_checkpoint
    posted,      \* highest checkpoint captaind has stored for the mailbox
    sess,        \* per worker: its active_sessions entries for this mailbox, in spawn order
    pushes,      \* send_push_notification calls per checkpoint
    stopping,    \* shut_down has begun
    stopped,     \* the worker's process is gone
    claimedLive, \* history: some claim_runnable took a live lease
    staleCheck,  \* history: some claim_is_active before a push passed on a lapsed lease
    unguarded,   \* history: some claim_is_active passed with the lease lapsed both at its Utc::now() and at its SELECT
    cpBack       \* history: some update_checkpoint moved last_checkpoint down

vars == <<owner, live, lastCp, posted, sess, pushes, stopping, stopped,
          claimedLive, staleCheck, unguarded, cpBack>>

\* Two slots are enough for OneSessionPerWorker to see a second session.
Slots == 1..2

Phases == {"starting", "startQuery", "idle", "checking", "checked", "pushed",
           "clearing", "releasing", "done"}
\* seenLive: the lease has been live at some moment since the session took
\* the Utc::now() its pending claim_is_active binds as $5. FALSE outside
\* startQuery and checking.
Session == [pos : 0..MaxCheckpoint, phase : Phases, cancelled : BOOLEAN,
            quiet : BOOLEAN, seenLive : BOOLEAN]

TypeOK ==
    /\ owner \in Workers \cup {NoWorker}
    /\ live \in BOOLEAN
    /\ lastCp \in 0..MaxCheckpoint
    /\ posted \in 0..MaxCheckpoint
    /\ \A w \in Workers : \A i \in 1..Len(sess[w]) : sess[w][i] \in Session
    /\ pushes \in [Checkpoints -> 0..2]
    /\ stopping \in [Workers -> BOOLEAN]
    /\ stopped \in [Workers -> BOOLEAN]

Init ==
    /\ owner = NoWorker
    /\ live = FALSE
    /\ lastCp = 0
    /\ posted = 0
    /\ sess = [w \in Workers |-> <<>>]
    /\ pushes = [c \in Checkpoints |-> 0]
    /\ stopping = [w \in Workers |-> FALSE]
    /\ stopped = [w \in Workers |-> FALSE]
    /\ claimedLive = FALSE
    /\ staleCheck = FALSE
    /\ unguarded = FALSE
    /\ cpBack = FALSE

Running(w) == ~stopping[w] /\ ~stopped[w]

\* The `valid` CTE of bulk_renew_claims, read at the statement.
LeaseIsMine(w) == owner = w /\ (live \/ BugNoExpiryGuard)

\* The claim_is_active SELECT for session s: lease_owner = $3 AND
\* lease_expires_at > $5, where $5 is the Utc::now() the session took before
\* awaiting the query. Between two frees the row's expiry only grows (a
\* claim needs it lapsed and writes a later one, renewal extends it), and
\* lease_owner = w after a free needs a new claim. So the test passes iff
\* lease_owner = w and the lease was live at some moment since the sample.
CheckPasses(w, s) == owner = w /\ (s.seenLive \/ live \/ BugNoExpiryGuard)

\* A step that makes the lease live marks every pending claim_is_active.
MarkSeenLive(ss) ==
    [w \in Workers |-> [i \in DOMAIN ss[w] |->
        IF ss[w][i].phase \in {"startQuery", "checking"}
        THEN [ss[w][i] EXCEPT !.seenLive = TRUE] ELSE ss[w][i]]]

SetPhase(w, i, p) == sess' = [sess EXCEPT ![w][i].phase = p]

Remove(s, i) == [j \in 1..(Len(s) - 1) |-> IF j < i THEN s[j] ELSE s[j + 1]]

Free == owner' = NoWorker /\ live' = FALSE

-----------------------------------------------------------------------------
(* Actors outside the workers *)

\* captaind stores the next message in the mailbox.
Post ==
    /\ posted < MaxCheckpoint
    /\ posted' = posted + 1
    /\ UNCHANGED <<owner, live, lastCp, sess, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* Wall-clock time passes lease_expires_at. Renewal can fail (its error is
\* only logged), stall behind claim_runnable in the same select! loop, or
\* come too late, so the model lets a lease lapse at any step.
Expire ==
    /\ owner # NoWorker
    /\ live
    /\ live' = FALSE
    /\ UNCHANGED <<owner, lastCp, posted, sess, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

-----------------------------------------------------------------------------
(* The worker loop, MailboxWorker::run *)

\* schedule_runnable_sessions: the claim_runnable UPDATE and, joined to it,
\* the active_sessions skip and the spawn of process_mailbox_session. In Rust
\* the UPDATE commits before the worker reads the response and spawns. Joining
\* them loses nothing: only this worker's run loop reads or changes sess[w]
\* (Renew, Join, shut_down), and it is busy in schedule_runnable_sessions
\* until the spawn, so the skip decision is fixed at the UPDATE. A session
\* spawned early just waits in "starting" while others step.
Claim(w) ==
    /\ Running(w)
    /\ \/ owner = NoWorker
       \/ ~live
       \/ BugSelfReclaim /\ owner = w
    /\ owner' = w
    /\ live' = TRUE
    /\ claimedLive' = (claimedLive \/ live)
    \* The skip predates 86b883f05, so a re-claim never spawns a second session.
    /\ sess' = MarkSeenLive(
               IF sess[w] # <<>>
                 THEN sess
                 ELSE [sess EXCEPT ![w] = <<[pos |-> lastCp, phase |-> "starting",
                                             cancelled |-> FALSE, quiet |-> lastCp = 0,
                                             seenLive |-> FALSE]>>])
    /\ UNCHANGED <<lastCp, posted, pushes, stopping, stopped, staleCheck, unguarded, cpBack>>

\* renew_active_sessions: one bulk_renew_claims statement and, joined to it,
\* the cancel of each session it did not report as valid. Joining loses
\* nothing: after the statement reports a lease lost, only this worker's run
\* loop could make it this worker's again, and the loop is busy until the
\* cancels. A session's check that reads the token before the cancel took its
\* Utc::now() before it too, which CheckSample/CheckQuery represent.
\* live' = TRUE is no change at master (the lease was live). A live lease
\* outside the renewal window is valid but not extended; with no clock in
\* the model both are "live", and Expire can lapse either at any step. A
\* renewal whose Utc::now() predates expiry can revive a lapsed lease that no
\* one else took; the model has no such Renew step, but Claim(w) reaches the
\* same state from there.
Renew(w) ==
    /\ Running(w)
    /\ sess[w] # <<>>
    /\ IF LeaseIsMine(w)
         THEN /\ live' = TRUE
              /\ sess' = MarkSeenLive(sess)
         ELSE /\ sess' = [sess EXCEPT ![w] = [i \in DOMAIN sess[w] |->
                                               [sess[w][i] EXCEPT !.cancelled = TRUE]]]
              /\ UNCHANGED live
    /\ UNCHANGED <<owner, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* handle_session_result removes a finished task from active_sessions.
Join(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ ~stopped[w]
    /\ sess[w][i].phase = "done"
    /\ sess' = [sess EXCEPT ![w] = Remove(sess[w], i)]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

-----------------------------------------------------------------------------
(* One session, process_mailbox_session and process_mailbox_message *)

\* process_mailbox_session takes Utc::now() for its first claim_is_active.
\* It reads no cancellation token first.
StartSample(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "starting"
    /\ sess' = [sess EXCEPT ![w][i].phase = "startQuery", ![w][i].seenLive = live]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* That claim_is_active SELECT. False returns before clear_error and
\* release_claim; true goes on to run_session.
StartQuery(w, i) ==
    LET s == sess[w][i] IN
    /\ i \in DOMAIN sess[w]
    /\ s.phase = "startQuery"
    /\ sess' = [sess EXCEPT ![w][i].phase = IF CheckPasses(w, s) THEN "idle" ELSE "done",
                            ![w][i].seenLive = FALSE]
    /\ unguarded' = (unguarded \/ (CheckPasses(w, s) /\ ~s.seenLive /\ ~live))
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

\* A message past the session's checkpoint arrives. process_mailbox_message
\* reads the cancellation token (cancelled is StopOnCancel), then takes
\* Utc::now() for claim_is_active.
CheckSample(w, i) ==
    LET s == sess[w][i] IN
    /\ i \in DOMAIN sess[w]
    /\ s.phase = "idle"
    /\ ~s.cancelled
    /\ s.pos < posted
    /\ sess' = [sess EXCEPT ![w][i].phase = "checking", ![w][i].seenLive = live]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* That claim_is_active SELECT. False ends the session as Completed. The
\* token is not read again, so a cancel since CheckSample does not stop it.
CheckQuery(w, i) ==
    LET s == sess[w][i] IN
    /\ i \in DOMAIN sess[w]
    /\ s.phase = "checking"
    /\ sess' = [sess EXCEPT ![w][i].phase = IF CheckPasses(w, s) THEN "checked" ELSE "clearing",
                            ![w][i].seenLive = FALSE]
    /\ staleCheck' = (staleCheck \/ (CheckPasses(w, s) /\ ~live))
    /\ unguarded' = (unguarded \/ (CheckPasses(w, s) /\ ~s.seenLive /\ ~live))
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, cpBack>>

\* send_push_notification for the message at checkpoint pos + 1. Nothing on
\* this path reads the lease again. A session that started from checkpoint 0
\* sends nothing for the backlog (should_suppress_catchup_notifications).
\* This counts calls. Delivery to APNs/FCM runs inside the call, so a call
\* that join_set.shutdown() aborts may have delivered to none, some or all
\* tokens. The count stops at 2, which is enough to see a duplicate and keeps
\* the state space finite without cutting off later steps.
Push(w, i) ==
    LET c == sess[w][i].pos + 1 IN
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "checked"
    /\ pushes' = IF sess[w][i].quiet THEN pushes
                 ELSE [pushes EXCEPT ![c] = IF @ < 2 THEN @ + 1 ELSE 2]
    /\ SetPhase(w, i, "pushed")
    /\ UNCHANGED <<owner, live, lastCp, posted, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* update_checkpoint guards on lease_owner but not on lease_expires_at.
\* False ends the session as Completed.
UpdateCheckpoint(w, i) ==
    LET c == sess[w][i].pos + 1 IN
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "pushed"
    /\ IF owner = w
         THEN /\ lastCp' = c
              /\ cpBack' = (cpBack \/ c < lastCp)
              /\ sess' = [sess EXCEPT ![w][i].pos = c, ![w][i].phase = "idle"]
         ELSE /\ SetPhase(w, i, "clearing")
              /\ UNCHANGED <<lastCp, cpBack>>
    /\ UNCHANGED <<owner, live, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded>>

\* A session in run_session sees its cancellation token (renewal lost the
\* lease, or shutdown) and returns Completed. A session still in "starting"
\* or "startQuery" runs its first claim_is_active first, as the code does.
StopOnCancel(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "idle"
    /\ sess[w][i].cancelled
    /\ SetPhase(w, i, "clearing")
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* read_mailbox returns no messages: the backlog is done, and later messages
\* come from subscribe_mailbox with notifications on. captaind can store a
\* message after it answers the read and before the worker turns suppression
\* off. That Post commutes past this step, since nothing else reads quiet, so
\* requiring pos = posted here loses no behavior.
CaughtUp(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "idle"
    /\ sess[w][i].quiet
    /\ sess[w][i].pos = posted
    /\ sess' = [sess EXCEPT ![w][i].quiet = FALSE]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* The stream ends or errors: run_session returns Retryable.
StreamEnds(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "idle"
    /\ SetPhase(w, i, "clearing")
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* clear_error (Completed) or mark_retry (Retryable): both clear the lease
\* where lease_owner is this worker.
ClearError(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "clearing"
    /\ IF owner = w THEN Free ELSE UNCHANGED <<owner, live>>
    /\ SetPhase(w, i, "releasing")
    /\ UNCHANGED <<lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* release_claim, the last statement of process_mailbox_session.
ReleaseClaim(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "releasing"
    /\ IF owner = w THEN Free ELSE UNCHANGED <<owner, live>>
    /\ SetPhase(w, i, "done")
    /\ UNCHANGED <<lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

-----------------------------------------------------------------------------
(* Shutdown, MailboxWorker::shut_down *)

\* SIGTERM cancels the run loop, and shut_down cancels every session.
BeginShutdown(w) ==
    /\ ~BugNoShutdownRelease
    /\ Running(w)
    /\ stopping' = [stopping EXCEPT ![w] = TRUE]
    /\ sess' = [sess EXCEPT ![w] = [i \in DOMAIN sess[w] |->
                                     [sess[w][i] EXCEPT !.cancelled = TRUE]]]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* SHUTDOWN_GRACE ran out: join_set.shutdown() aborts the sessions left.
AbortSessions(w) ==
    /\ stopping[w]
    /\ sess[w] # <<>>
    /\ sess' = [sess EXCEPT ![w] = <<>>]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* release_all_claims, after every session has stopped; then the process exits.
ReleaseAll(w) ==
    /\ stopping[w]
    /\ sess[w] = <<>>
    /\ IF owner = w THEN Free ELSE UNCHANGED <<owner, live>>
    /\ stopping' = [stopping EXCEPT ![w] = FALSE]
    /\ stopped' = [stopped EXCEPT ![w] = TRUE]
    /\ UNCHANGED <<lastCp, posted, sess, pushes, claimedLive, staleCheck, unguarded, cpBack>>

\* Before 3ed2b04c8 nothing handled SIGTERM: the process died holding its lease.
Killed(w) ==
    /\ BugNoShutdownRelease
    /\ Running(w)
    /\ stopped' = [stopped EXCEPT ![w] = TRUE]
    /\ sess' = [sess EXCEPT ![w] = <<>>]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping,
                   claimedLive, staleCheck, unguarded, cpBack>>

\* Every worker has stopped and captaind has posted everything: nothing is left to do.
Finished ==
    /\ \A w \in Workers : stopped[w]
    /\ posted = MaxCheckpoint
    /\ UNCHANGED vars

-----------------------------------------------------------------------------

Next ==
    \/ Post
    \/ Expire
    \/ \E w \in Workers :
         \/ Claim(w)
         \/ Renew(w)
         \/ BeginShutdown(w)
         \/ AbortSessions(w)
         \/ ReleaseAll(w)
         \/ Killed(w)
    \/ \E w \in Workers, i \in Slots : StartSample(w, i)
    \/ \E w \in Workers, i \in Slots : StartQuery(w, i)
    \/ \E w \in Workers, i \in Slots : CheckSample(w, i)
    \/ \E w \in Workers, i \in Slots : CheckQuery(w, i)
    \/ \E w \in Workers, i \in Slots : Push(w, i)
    \/ \E w \in Workers, i \in Slots : UpdateCheckpoint(w, i)
    \/ \E w \in Workers, i \in Slots : StopOnCancel(w, i)
    \/ \E w \in Workers, i \in Slots : CaughtUp(w, i)
    \/ \E w \in Workers, i \in Slots : StreamEnds(w, i)
    \/ \E w \in Workers, i \in Slots : ClearError(w, i)
    \/ \E w \in Workers, i \in Slots : ReleaseClaim(w, i)
    \/ \E w \in Workers, i \in Slots : Join(w, i)
    \/ Finished

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Properties. README.md gives each one's harm and its result at master. *)

\* A worker runs at most one session for a mailbox.
OneSessionPerWorker ==
    \A w \in Workers :
        Cardinality({i \in DOMAIN sess[w] : sess[w][i].phase # "done"}) <= 1

\* claim_runnable never takes a lease that is still live, the claimer's own included.
NoClaimOfLiveLease == ~claimedLive

\* A worker decides to push only while its lease is live.
CheckOnlyUnderLiveLease == ~staleCheck

\* claim_is_active passes only if the lease was live at some moment between
\* the session's Utc::now() and the SELECT. This restates the
\* lease_expires_at > $5 test, so it fails only when that test is removed.
CheckPassesOnlyIfLiveSinceSample == ~unguarded

\* A worker that has shut down holds no live lease.
StoppedWorkerHoldsNoLease == \A w \in Workers : stopped[w] => ~(owner = w /\ live)

\* Each checkpoint is pushed at most once.
AtMostOnePushPerCheckpoint == \A c \in Checkpoints : pushes[c] <= 1

\* last_checkpoint never moves down, so no message is read twice from the store.
CheckpointNeverMovesBack == ~cpBack

=============================================================================
