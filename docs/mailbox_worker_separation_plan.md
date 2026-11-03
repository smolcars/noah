# Mailbox worker separation: research and plan

Date: 2026-11-03. Status: proposal, nothing implemented.

## Question

Should the mailbox subscription workload (roughly one long-lived gRPC stream
per authorised mailbox, about a thousand today) be split out of the API
process into its own service?

## Short answer

Yes, but the split is the smaller half of the work. The code is already
written for it: the worker is a self-contained module with database leases,
there is a `RUN_MAILBOX_WORKER` toggle on the API, and a standalone
`mailbox_worker` binary exists. What is missing is packaging and Railway
wiring. The bigger win is that every mailbox session currently opens its own
TCP+TLS connection to the Ark server, so the thousand streams are also a
thousand connections. Multiplexing them over a few HTTP/2 channels removes
most of the pressure regardless of where the worker runs. Do both, in that
order: connection sharing first (small, in-place), then the service split.

## What the code does today

Everything below is from the repository, not from production metrics.

- `server/src/mailbox_worker.rs` is a scheduler plus a transport trait.
  The scheduler claims rows from `mailbox_authorizations` with
  `FOR UPDATE SKIP LOCKED`, stamps them with a per-process `worker_id` and a
  lease (`MAILBOX_WORKER_CLAIM_TTL_SECS`, 120 s in Railway), renews leases in
  bulk every 30 s, and cancels a session when renewal shows the lease was
  lost. Multiple worker processes already coexist safely.
- `Beta8MailboxTransport::run_session` builds a fresh `ServerConnection`
  for every session and again on every idle reconnect
  (`MAILBOX_WORKER_STREAM_IDLE_RECONNECT_SECS`, 600 s). `ServerConnection`
  does a TCP connect, TLS, a handshake RPC and an `ArkInfo` fetch each time.
  With N active mailboxes that is N connections to `ark.second.tech`, plus
  N handshakes every ten minutes. tonic's `Channel` is cheap to clone and
  multiplexes streams, so none of this is required by the protocol.
- `server/src/main.rs` spawns the worker inside the API process unless
  `RUN_MAILBOX_WORKER` is false. Neither Railway environment sets that
  variable, so the API runs the worker.
- `server/src/bin/mailbox_worker.rs` is a 30-line standalone binary that
  builds `AppState` and runs the worker. Cargo auto-discovers it, but the
  root `Dockerfile` only copies `server` and `noah-cli` into the runtime
  image, so it has never shipped.
- The worker shares the API's resources: `POSTGRES_MAX_CONNECTIONS=20` and
  `REDIS_POOL_SIZE=64` on signet. Per mailbox message the worker runs
  `claim_is_active`, a fiat-rate read, the push send, and `update_checkpoint`,
  and every 30 s it runs a bulk renewal across all active rows. All of that
  competes with request handlers for the same 20 Postgres connections.
- There is no graceful shutdown in either `main.rs` or the worker binary.
  On SIGTERM the process exits with leases still held. A replacement process
  gets a new `worker_id`, so it cannot pick those rows up until the 120 s
  lease expires plus up to one 15 s scan. Every API deploy therefore drops
  all streams and leaves a window of up to about two minutes where incoming
  payments produce no push. Mainnet deploys with `overlapSeconds: 0`.
- `claim_runnable` subscribes every enabled, active authorisation for an
  active user. It does not check whether the user has any push target
  (`push_tokens` row or UnifiedPush endpoint). `send_push_notification`
  returns early with "no push tokens found" in that case, so some fraction of
  the streams can never result in a notification.
- Ark server side (captaind `server-0.8.0`): each `SubscribeMailbox` stream
  is a `tokio::watch` receiver on a per-mailbox tip plus a DB read when the
  tip moves. It is light per stream. The public gRPC server uses tonic
  defaults, which means hyper's default of 200 concurrent streams per HTTP/2
  connection. That is the number to design around when sharing channels. We
  do not know what sits in front of `ark.second.tech` (load balancer, proxy),
  and a proxy may impose its own per-connection stream limit, commonly 100
  to 128.

## What "bottleneck" most likely means in practice

Ranked by how confident the code makes me, without production numbers:

1. Connection count, not stream count. A thousand TLS connections cost file
   descriptors, kernel buffers, TLS state and keepalive pings on both ends,
   and the Ark operator sees a thousand clients from one IP. Our static
   egress IP is whitelisted with Second, so rate limiting is not a concern;
   the cost is the connection overhead itself. Fix: share channels.
2. Deploy-time reconnect storm and the two-minute lease gap. Fix: separate
   service so API deploys do not touch streams, plus graceful lease release.
3. Database pool contention between request handlers and worker bookkeeping.
   Fix: separate service with its own pool size, and fewer per-message
   queries.
4. Blast radius. A worker bug (a panic loop, a slow reconnect storm) degrades
   the public API, and an API memory spike kills notifications. Fix:
   separate service.

Memory and task count of the streams themselves are not the problem. A
thousand tokio tasks with one gRPC stream each is a few tens of megabytes.

## Options considered

### A. Share gRPC channels across sessions (do first)

Hold a small pool of connected `ServerConnection` values in the transport
(for example one per 100 sessions, capped and lazily created) and hand
each session a cloned `mailbox_client` instead of calling `connect()`.
Reconnect by replacing the pooled channel, not by dialling per session.

- Cost: small change inside `Beta8MailboxTransport`, no schema or infra.
- Effect: connections drop from N to about N/100. Handshake and `ArkInfo`
  RPCs drop from N per ten minutes to a handful.
- Risks: one channel failure now takes out up to 100 sessions at once (they
  all return `Retryable` and reconnect through the retry schedule, so this
  is self-healing but bursty). Stay under 100 streams per channel to leave
  room for any proxy limit. Head-of-line blocking on one TCP connection is
  irrelevant for this traffic volume.

### B. Run the worker as its own Railway service (do second)

Ship the existing `mailbox_worker` binary in the image, add a
`mailbox-worker` service per environment running it, and set
`RUN_MAILBOX_WORKER=false` on the API.

- Cost: Dockerfile line, Railway IaC for two environments, a release
  workflow that deploys both services from the same image digest, and a
  shutdown handler. Roughly a day including review and a signet soak.
- Effect: API deploys and restarts no longer drop streams. Worker gets its
  own Postgres and Redis pool sizes. Memory and CPU for the API shrink and
  become predictable. Worker can be scaled vertically or to two replicas
  without touching the API.
- Risks: one more service to watch. The worker has no HTTP port, so Railway
  can only restart it on exit; add a liveness signal (see plan). Both
  services run migrations on boot today; only one should.

### C. Subscribe only where a push can be delivered (cheap filter)

Join `push_tokens` (and the UnifiedPush endpoint column if separate) in
`claim_runnable` so users with no delivery target are not streamed. Also
consider skipping users whose last heartbeat is older than the
deregistration window; the cron already deletes those authorisations, so
this is mostly a timing improvement.

- Cost: one SQL change plus a test.
- Effect: unknown until measured; could be anywhere from a few percent to a
  large share of the thousand.
- Risk: a user who re-registers a token must be picked up on the next scan.
  `claim_runnable` runs every 15 s, so that is fine.

### D. Poll `ReadMailbox` for dormant mailboxes instead of streaming

Tiered model: stream for users active in the last N days, poll every 30 to
60 s for the rest. Not recommended now. Lightning claim pushes are
time-sensitive (the HTLC must be claimed before it expires), polling adds
latency and N RPCs per interval, and option A already removes the
connection cost that made streaming look expensive.

### E. Upstream a multi-mailbox subscription to bark

Add `SubscribeMailboxes(repeated MailboxRequest) returns (stream ...)`
to captaind, or a server-to-server webhook. This collapses a thousand
streams into one. It is the right long-term shape for a custodial-style
notifier, but it depends on Second's roadmap and review cycle, and
captaind's per-mailbox `watch` design means the server-side work is
moderate. Worth opening as a discussion after A and B ship, with our real
numbers attached.

## Recommended plan

Each step is independently shippable and reviewable.

1. Measure before changing anything. Add three gauges to the worker log line
   that already exists on renewal: active sessions, open channels (after
   step 2 this is meaningful), and sessions whose user has no push target.
   Pull the Railway memory and CPU graphs for the API during a deploy and
   during steady state so the before and after can be compared.
   Verify: the numbers appear in signet logs.

2. Share channels (option A). Introduce a channel pool inside
   `Beta8MailboxTransport` keyed by nothing (single Ark URL), with a
   configurable max streams per channel (default 100) and a cap on channels.
   Sessions borrow a clone of `mailbox_client`. On a transport-level error
   the session returns `Retryable` as today; the pool drops the broken
   channel and dials a new one on next borrow.
   Verify: unit test with a fake transport is unaffected; on signet, open
   connection count to the Ark host drops to a handful (check with
   `ss -tn` or Railway metrics), and incoming payment pushes still arrive.

3. Graceful shutdown. Listen for SIGTERM in both `main.rs` and the worker
   binary, cancel all sessions, and bulk-release leases (one UPDATE setting
   `lease_owner = NULL, lease_expires_at = NULL` for this `worker_id`).
   Verify: after a restart the new process claims the full set within one
   scan interval instead of waiting out the lease TTL.

4. Package and deploy the separate service (option B).
   - `Dockerfile`: copy `target/release/mailbox_worker` into the runtime
     image.
   - Worker binary: skip `run_migrations` (API keeps that job), load a
     smaller `POSTGRES_MAX_CONNECTIONS`, and expose a tiny `/health` on
     `PORT` so Railway can health-check it. Returning 200 only when the
     scheduler loop ticked within the last two scan intervals is enough.
   - `.railway/railway.ts` and `.railway/mainnet/railway.ts`: add a
     `mailbox-worker` service using the same image source, `startCommand`
     `mailbox_worker`, no domain, one replica, `RUN_MAILBOX_WORKER=false`
     on the API, and move the `MAILBOX_WORKER_*` variables to the worker.
     Keep `drainingSeconds` at least equal to the shutdown path in step 3.
   - Release workflow: deploy the worker service with the same digest as the
     API in the same job, worker first so streams are already up when the
     API restarts.
   - Rollout: deploy the worker with the API still running the worker
     (both claim, leases arbitrate), confirm the new service holds sessions,
     then flip `RUN_MAILBOX_WORKER=false` on the API and redeploy it.
     Rollback is the reverse flip.
   Verify: signet soak of a day, API deploy during the soak with a test
   payment landing a push mid-deploy, `/health` green, lease ownership
   fully on the worker's `worker_id`.

5. Filter undeliverable mailboxes (option C) once step 1 shows the share.
   Verify: repository test for the join, session count drops accordingly.

6. Open the upstream discussion (option E) with the measured numbers.

## Open questions for the owner

- Does anything in front of `ark.second.tech` cap streams per connection?
  Ask Second, or probe by opening one channel with 150 streams on signet.
- Is a second worker replica wanted for failover, or is one plus Railway's
  restart policy enough? The lease scheme supports either.
- Mainnet currently preserves the `MAILBOX_WORKER_*` variables on the API
  service. Moving them to the worker in IaC deletes them from the API on the
  next apply, which is correct but should be timed with the cutover.

## Files involved

- `server/src/mailbox_worker.rs` (transport, channel pool, shutdown)
- `server/src/bin/mailbox_worker.rs` (health endpoint, migrations skip)
- `server/src/main.rs` (shutdown handling)
- `server/src/db/mailbox_authorization_repo.rs` (bulk release, push filter)
- `Dockerfile`
- `.railway/railway.ts`, `.railway/mainnet/railway.ts`
- `.github/workflows/` API release jobs
