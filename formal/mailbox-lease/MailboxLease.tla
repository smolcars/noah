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
    BugNoLeaseExpiry,  \* claim_is_active and renewal from before b018632a5
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
    cpBack       \* history: some update_checkpoint moved last_checkpoint down

vars == <<owner, live, lastCp, posted, sess, pushes, stopping, stopped,
          claimedLive, staleCheck, cpBack>>

\* Two slots are enough for OneSessionPerWorker to see a second session.
Slots == 1..2

Phases == {"starting", "idle", "checked", "pushed", "clearing", "releasing", "done"}
Session == [pos : 0..MaxCheckpoint, phase : Phases, cancelled : BOOLEAN]

TypeOK ==
    /\ owner \in Workers \cup {NoWorker}
    /\ live \in BOOLEAN
    /\ lastCp \in 0..MaxCheckpoint
    /\ posted \in 0..MaxCheckpoint
    /\ \A w \in Workers : \A i \in 1..Len(sess[w]) : sess[w][i] \in Session
    /\ pushes \in [Checkpoints -> Nat]
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
    /\ cpBack = FALSE

Running(w) == ~stopping[w] /\ ~stopped[w]

\* The WHERE clause shared by claim_is_active and the `valid` CTE of
\* bulk_renew_claims. Before b018632a5 neither tested lease_expires_at.
LeaseIsMine(w) == owner = w /\ (live \/ BugNoLeaseExpiry)

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
                   claimedLive, staleCheck, cpBack>>

\* Wall-clock time passes lease_expires_at. Renewal can fail (its error is
\* only logged), stall behind claim_runnable in the same select! loop, or
\* come too late, so the model lets a lease lapse at any step.
Expire ==
    /\ owner # NoWorker
    /\ live
    /\ live' = FALSE
    /\ UNCHANGED <<owner, lastCp, posted, sess, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

-----------------------------------------------------------------------------
(* The worker loop, MailboxWorker::run *)

\* schedule_runnable_sessions: the claim_runnable UPDATE, then, with no await,
\* the active_sessions skip and the spawn of process_mailbox_session.
Claim(w) ==
    /\ Running(w)
    /\ \/ owner = NoWorker
       \/ ~live
       \/ BugSelfReclaim /\ owner = w
    /\ owner' = w
    /\ live' = TRUE
    /\ claimedLive' = (claimedLive \/ live)
    \* The skip predates 86b883f05, so a re-claim never spawns a second session.
    /\ sess' = IF sess[w] # <<>>
                 THEN sess
                 ELSE [sess EXCEPT ![w] = <<[pos |-> lastCp, phase |-> "starting",
                                             cancelled |-> FALSE]>>]
    /\ UNCHANGED <<lastCp, posted, pushes, stopping, stopped, staleCheck, cpBack>>

\* renew_active_sessions: one bulk_renew_claims statement, then cancel each
\* session it did not report as valid.
Renew(w) ==
    /\ Running(w)
    /\ sess[w] # <<>>
    /\ IF LeaseIsMine(w)
         THEN /\ live' = TRUE
              /\ UNCHANGED sess
         ELSE /\ sess' = [sess EXCEPT ![w] = [i \in DOMAIN sess[w] |->
                                               [sess[w][i] EXCEPT !.cancelled = TRUE]]]
              /\ UNCHANGED live
    /\ UNCHANGED <<owner, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

\* handle_session_result removes a finished task from active_sessions.
Join(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ ~stopped[w]
    /\ sess[w][i].phase = "done"
    /\ sess' = [sess EXCEPT ![w] = Remove(sess[w], i)]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

-----------------------------------------------------------------------------
(* One session, process_mailbox_session and process_mailbox_message *)

\* The claim_is_active call at the top of process_mailbox_session. A failed
\* check returns before release_claim.
StartCheck(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "starting"
    /\ SetPhase(w, i, IF LeaseIsMine(w) THEN "idle" ELSE "done")
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

\* A message past the session's checkpoint arrives and process_mailbox_message
\* runs claim_is_active. False ends the session as Completed.
CheckActive(w, i) ==
    LET s == sess[w][i] IN
    /\ i \in DOMAIN sess[w]
    /\ s.phase = "idle"
    /\ ~s.cancelled
    /\ s.pos < posted
    /\ SetPhase(w, i, IF LeaseIsMine(w) THEN "checked" ELSE "clearing")
    /\ staleCheck' = (staleCheck \/ (LeaseIsMine(w) /\ ~live))
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, cpBack>>

\* send_push_notification for the message at checkpoint pos + 1. Nothing on
\* this path reads the lease again.
Push(w, i) ==
    LET c == sess[w][i].pos + 1 IN
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "checked"
    /\ pushes' = [pushes EXCEPT ![c] = @ + 1]
    /\ SetPhase(w, i, "pushed")
    /\ UNCHANGED <<owner, live, lastCp, posted, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

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
                   claimedLive, staleCheck>>

\* The session sees its cancellation token (renewal lost the lease, or shutdown).
StopOnCancel(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase \in {"starting", "idle"}
    /\ sess[w][i].cancelled
    /\ SetPhase(w, i, IF sess[w][i].phase = "starting" THEN "done" ELSE "clearing")
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

\* The stream ends or errors: run_session returns Retryable.
StreamEnds(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "idle"
    /\ SetPhase(w, i, "clearing")
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

\* clear_error (Completed) or mark_retry (Retryable): both clear the lease
\* where lease_owner is this worker.
ClearError(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "clearing"
    /\ IF owner = w THEN Free ELSE UNCHANGED <<owner, live>>
    /\ SetPhase(w, i, "releasing")
    /\ UNCHANGED <<lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

\* release_claim, the last statement of process_mailbox_session.
ReleaseClaim(w, i) ==
    /\ i \in DOMAIN sess[w]
    /\ sess[w][i].phase = "releasing"
    /\ IF owner = w THEN Free ELSE UNCHANGED <<owner, live>>
    /\ SetPhase(w, i, "done")
    /\ UNCHANGED <<lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

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
                   claimedLive, staleCheck, cpBack>>

\* SHUTDOWN_GRACE ran out: join_set.shutdown() aborts the sessions left.
AbortSessions(w) ==
    /\ stopping[w]
    /\ sess[w] # <<>>
    /\ sess' = [sess EXCEPT ![w] = <<>>]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping, stopped,
                   claimedLive, staleCheck, cpBack>>

\* release_all_claims, after every session has stopped; then the process exits.
ReleaseAll(w) ==
    /\ stopping[w]
    /\ sess[w] = <<>>
    /\ IF owner = w THEN Free ELSE UNCHANGED <<owner, live>>
    /\ stopping' = [stopping EXCEPT ![w] = FALSE]
    /\ stopped' = [stopped EXCEPT ![w] = TRUE]
    /\ UNCHANGED <<lastCp, posted, sess, pushes, claimedLive, staleCheck, cpBack>>

\* Before 3ed2b04c8 nothing handled SIGTERM: the process died holding its lease.
Killed(w) ==
    /\ BugNoShutdownRelease
    /\ Running(w)
    /\ stopped' = [stopped EXCEPT ![w] = TRUE]
    /\ sess' = [sess EXCEPT ![w] = <<>>]
    /\ UNCHANGED <<owner, live, lastCp, posted, pushes, stopping,
                   claimedLive, staleCheck, cpBack>>

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
    \/ \E w \in Workers, i \in Slots : StartCheck(w, i)
    \/ \E w \in Workers, i \in Slots : CheckActive(w, i)
    \/ \E w \in Workers, i \in Slots : Push(w, i)
    \/ \E w \in Workers, i \in Slots : UpdateCheckpoint(w, i)
    \/ \E w \in Workers, i \in Slots : StopOnCancel(w, i)
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

\* A worker that has shut down holds no live lease.
StoppedWorkerHoldsNoLease == \A w \in Workers : stopped[w] => ~(owner = w /\ live)

\* Each checkpoint is pushed at most once.
AtMostOnePushPerCheckpoint == \A c \in Checkpoints : pushes[c] <= 1

\* last_checkpoint never moves down, so no message is read twice from the store.
CheckpointNeverMovesBack == ~cpBack

\* Bounds the search once a duplicate push has been reached.
StateBound == \A c \in Checkpoints : pushes[c] <= 2

=============================================================================
