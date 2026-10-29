# Mainnet migration from Fly to Railway

Planning date: **2026-10-29**. Status: **preparation code for review; cutover not authorized**.
This PR includes the runbook, exact mainnet Barkd hostname support, an inert
production bootstrap, and guarded release workflows. Fly remains the default
production target. Merging does not provision services, transfer production data,
set deployment-target variables, or approve the cutover. See the
[bootstrap and release instructions](../.railway/mainnet/README.md).

Use the completed [signet migration](railway_signet_migration_plan.md) as the
operational reference: prepare and rehearse while Fly serves production, then
freeze all writers and transfer one final migration set. Keep the existing
`https://noahwallet.io` API and Lightning addresses. All operations use the
Railway CLI/API, Fly CLI, GitHub Actions, and Cloudflare API.

## Scope and decisions

Move the API, production Postgres database, Redis state, and the complete Barkd
wallet volume into a fresh Railway production environment, isolated from signet.
Keep AWS S3 objects, SES, Ark, and other external services in place.

Metabase has its own application database as well as a read-only connection to
Noah's database. Migrate it separately after the wallet services are accepted.
Pause reporting during cutover until it is connected to the new authoritative
database; do not leave dashboards serving the frozen Fly dataset. Preserve its
application database and encryption key, and recreate its reporting role with
read-only privileges. See [the existing setup](../fly/mainnet-metabase-setup.md).

Confirmed choices:

- **Postgres:** source 16.15; target 18, using a logical dump and restore. Native
  Fly access works from the MacBook Pro. Both the API's older hostname and the
  current managed-cluster endpoint were verified against the same internal
  database identity. The source is approximately 60 MB with 984 users and 22
  successful SQLx migrations. Rehearse the restore before cutover.
- **Barkd:** upgrade the live 0.7.1 wallet to 0.8.0 when activating Railway.
  Pin the reviewed 0.8.0 image, retain the untouched offline source backup, and
  verify the restored wallet before starting the API.
- **Metabase:** migrate separately after the wallet services are accepted.

Remaining choices before activation:

- **Postgres protection and cost:** recommend HA, enabled PITR, and independent
  encrypted logical backups. Railway's default template is a single node; HA and
  PITR are explicit features, not properties of the Pro subscription. Size and
  price the full topology against the actual Fly setup before choosing it.
- **Maintenance window:** provisionally reserve 60–90 minutes, then replace this
  estimate with timed export, restore, validation, payment-drain, and DNS/TLS
  measurements. Choose an abort deadline before activating the destination.
- **Metabase timing:** keep its migration outside the wallet cutover window;
  arrange secure connectivity if it temporarily remains on Fly. Railway private
  DNS is not directly reachable from a Fly container.

Platform references: [Postgres](https://docs.railway.com/databases/postgresql),
[HA](https://docs.railway.com/databases/postgresql-ha),
[PITR](https://docs.railway.com/volumes/point-in-time-recovery), and
[the earlier assessment](railway_migration_assessment.md).

## Preparation without downtime

1. **Inventory the running production system.** Record API and Barkd image
   digests, source revisions, Machine/volume IDs, resource usage, database names,
   Postgres version/extensions/size, Redis database and key types, and complete
   wallet-volume size/layout. Verify the running system rather than relying on
   the mutable tags in [API](../fly/mainnet.fly.toml) and
   [Barkd](../fly/mainnet.barkd.fly.toml) configs. Record wallet identity/network,
   balance, and pending actions using supported read APIs, excluding secret
   endpoints. Inventory every database writer and hostname used by clients.
2. **Prepare production configuration.** Use a fresh production environment with
   fresh volumes and production-only project/environment guards. The existing
   [signet IaC](../.railway/railway.ts) deliberately rejects environments other than
   signet; do not remove that guard or apply it to production. The separate
   [production bootstrap](../.railway/mainnet/railway.ts) requires the exact
   production environment ID and an explicitly chosen Postgres major. It starts
   with a single-node database for import/rehearsal, not the final HA topology.
   Never reapply it after HA conversion or wallet activation. Provision the
   chosen database topology, persistent authenticated Redis with eviction
   disabled, a private singleton Barkd service with `/data`, and one API instance
   initially. Disable service sleeping and image/GitHub autodeploys during the
   move. Configure and test backup schedules and recovery.
3. **Hold the application processes.** Keep the real API and Barkd daemon stopped
   until the final import passes verification. Use a tools-only importer on the
   destination wallet volume, with no wallet initialization logic. Set Barkd's
   shutdown grace explicitly, starting with the existing Fly 30 seconds and
   increasing it if rehearsal requires it. Railway's default is zero seconds;
   see [deployment lifecycle](https://docs.railway.com/deployments/reference#singleton-deploys).
4. **Prepare the hostname change.** This PR extends
   [server validation](../server/src/config.rs) to permit the exact Railway origin
   `http://noah-barkd-mainnet.railway.internal:3000` alongside its existing Fly
   origin, preserving network separation, port/origin checks, and regtest
   restrictions, with accepted/rejected-URL tests. Build a reviewed API image containing the
   required change and schema compatible with the production dataset; avoid
   bundling unrelated feature or database changes into the move.
5. **Transfer production configuration privately.** Preserve `AUTH_JWT_SECRET`
   so existing sessions remain valid, the existing Barkd bearer token, AWS/S3/SES,
   Expo, Sentry, and other integration secrets. Compare all variable names and
   settings, including optional cron, mailbox, auth TTL, and support settings.
   Generate new database/Redis credentials and set the API's `POSTGRES_URL`,
   `REDIS_URL`, and `BARKD_URL` references. Keep `SERVER_NETWORK=bitcoin` and
   `LNURL_DOMAIN=noahwallet.io`. Declare every installed IaC-owned variable;
   omission can plan deletion. Never copy signet credentials into production or
   print credential values or wallet material in logs.
6. **Prepare releases and Fly fences.** Reuse signet's native `railway up --ci`
   deployment of a one-line `FROM <image>@sha256:<digest>` Dockerfile. Deploy the
   reviewed CI-built image without recompiling Rust. Keep production API
   releases manual/tag-based, with production environment protections and a
   separately scoped Railway token; master remains signet-only. Barkd stays
   manual and pinned to the reviewed 0.8.0 digest. Serialize production API
   and Barkd releases. Fence all existing Fly mainnet deployment paths before
   the freeze: the tag job in [server-push.yml](../.github/workflows/server-push.yml),
   [manual API deploy](../.github/workflows/fly-mainnet-deploy.yml), and
   [manual Barkd deploy](../.github/workflows/barkd-mainnet-deploy.yml). Verify no
   queued/running deployment can undo the source fences. Image publication alone
   must not deploy production. Keep all release automation held through cutover.
   The repository variable `MAINNET_DEPLOY_TARGET` selects the path: empty or
   `fly` keeps Fly, `paused` blocks both, and `railway` enables the new path.
   Set `railway` only after the approved migration and acceptance, not merely
   when bootstrap services exist. Production environment variables supply the
   destination UUIDs; neither workflows nor bootstrap invent them.
7. **Prepare DNS and access.** Inventory Cloudflare's current apex A/AAAA/CNAME,
   TTL, proxy mode, ownership records, and TLS settings; preserve unrelated mail,
   verification, and subdomain records. Register the Railway custom domain and
   stage its required ownership records without redirecting live traffic. Plan
   the apex CNAME flattening and certificate validation using Railway's exact
   assigned records. Test private database access and transfer paths before the
   window; private Railway names do not resolve on an ordinary laptop. See
   [custom domains](https://docs.railway.com/networking/domains/working-with-domains).

## Rehearsal and go or no go

- Restore a preliminary logical Postgres dump into a disposable, empty target.
  Include application schemas, data, indexes, constraints, sequences, and
  `_sqlx_migrations`; review provider-specific roles/extensions instead of
  blindly copying or excluding them. Use compatible dump/restore clients and
  `pg_restore --no-owner --no-privileges --single-transaction --exit-on-error`
  for a custom-format archive. Verify required extensions, including `pg_trgm`,
  table counts/data hashes, migration checksums, and sequence positions. Run
  `ANALYZE` after restore. Measure duration and verify backup restoration.
- Rehearse a binary-safe Redis transfer preserving permanent values and absolute
  expiration deadlines. Check every source type; the signet string copier is
  reusable only if production inventory confirms its assumptions. Require an
  empty destination and skip naturally expired keys. Cover `k1:*`, `invoice:*`,
  `email_verification:*`, `maintenance:*`, and any additional discovered state.
  Verify persistence and restart recovery without replaying old auth challenges.
- Rehearse wallet upload/extraction with dummy files. Any real wallet rehearsal
  copy stays offline, with no Barkd daemon. Verify checksums, complete archive
  paths, ownership/modes, and SQLite integrity on a disposable offline copy.
  Include capacity for both archive and extracted data. Never create a new
  wallet, import only a mnemonic, or run two copies of the production wallet.
- Never boot the API with production integration credentials on rehearsal data:
  [startup](../server/src/main.rs) runs migrations, cron jobs, and mailbox work.
  `RUN_MAILBOX_WORKER=false` does not disable all background activity. Test
  connectivity and image deployment with isolated data and credentials instead.
- Require working CLI permissions, transfer access, DNS/TLS procedure, a recent
  successful Railway test deployment, and no active deployment outage. Set the
  payment-drain deadline, rollback owner, and acceptance checks before approval.

## Approved cutover order

**Execution requires separate explicit user approval.** Keep the destination API
and Barkd held until step 6. Retain encrypted, checksummed final artifacts outside
Git, Docker images, and the application's own volume/deletion boundary.

1. Freeze release automation and block new API requests. Let in-flight work
   finish where possible, then stop every Fly API/worker/admin writer. The
   server currently has no HTTP graceful-shutdown hook, so inspect interrupted
   work rather than assuming every request completed.
2. Fence every old API Machine against proxy autostart, restart, and redeploy.
   Save original configurations privately and use an inert command fence as
   well as stopped state: signet's old API woke despite autostart/restart
   settings alone. Probe old routes and verify no server resumes or application
   database sessions remain. Keep Fly databases available for export.
3. Keep the source Barkd running only long enough to drain already-paid receives
   and resolve still-payable invoices. Record and reconcile pending operations;
   stopping invoice creation does not cancel issued invoices. Wait for safe
   settlement/expiry or abort at the agreed deadline. Stop Barkd cleanly, verify
   it is gone, and fence it before recording the final freeze timestamp.
4. Export the final Postgres and Redis state and archive the **complete `/data`
   volume**, including wallet configuration, auth files, SQLite databases, and
   any WAL/SHM/journal companions. If Fly needs a running Machine to retrieve
   files, run only the saved volume under an inert maintenance command, never
   the wallet daemon. No application writer may restart during export.
5. Restore this final migration set into empty Railway targets, replacing rather
   than merging rehearsal copies. Compare Postgres contents/migrations/sequences,
   Redis values/types/expiration deadlines, and every wallet file checksum and
   permission. Correct any failure before continuing. **Do not start the API to
   initialize its schema before restoring Postgres.**
   Enable Postgres HA/PITR and scheduled volume backups according to the chosen
   topology before activation; verify the completed first backup and recovery
   coverage. The bootstrap alone does not supply these protections. Configure
   the API's Dockerfile build and `/health` check before its first image upload.
6. Start the single Railway Barkd 0.8.0 with the original image entrypoint, clearing
   importer command overrides and keeping the same restored volume. Confirm
   network, wallet identity, reconciled balances/actions, and authenticated
   private reachability. Then start the pinned API image on the restored stores;
   verify any approved migrations and allow restored mailbox leases to expire
   according to the configured claim TTL. This activation starts the rollback
   boundary below, even while public DNS still points at Fly.
7. Verify API/dependency behavior through the Railway endpoint, then switch only
   the intended Cloudflare routing records for `noahwallet.io`. Verify domain
   ownership, normal TLS certificate validation, and requests through the real
   hostname. Keep old Fly routes unable to write while DNS caches expire. DNS
   propagation and certificate issuance are part of the maintenance window.
8. Confirm a new successful **serving** API deployment for the intended image and
   revision, not merely an older healthy deployment. Check existing-user auth,
   backup access, LNURL metadata, Redis one-time challenge consumption, mailbox
   processing, and a user-approved small real mainnet Lightning receive that
   settles and appears in the wallet. Monitor errors, pending payments, worker
   progress, resource headroom, and backup coverage before declaring acceptance.

## Rollback and retention

**Before destination Barkd/API activation:** if imports or readiness checks miss
the agreed deadline, keep Railway applications stopped, preserve the failure
evidence, and restore the saved Fly commands/settings. Resume only the source
wallet/API after verifying there has been no destination wallet or application
activity. Restore DNS if routing was changed.

**After destination activation:** assume wallet and background-job state may have
advanced even before DNS moves. Prefer fixing forward on the current stores. A
return to Fly is a controlled reverse migration: stop/fence Railway writers,
capture and reconcile its latest Postgres, Redis, and wallet state, verify source
version/schema compatibility, then activate only the restored Fly side. A newer
Postgres target may complicate reverse compatibility. Never restart the old
wallet snapshot or merge/replay the old Redis copy; that can fork wallet history
or resurrect consumed challenges. A DNS reversal alone is not a data rollback.

API image rollback also leaves database migrations and wallet state in place;
only use a version compatible with the current schema and data. Do not apply
signet's IaC or rollout settings to production as a recovery shortcut.

Keep stopped Fly services, historical stores, DNS/config recovery records, and
final migration artifacts until payment acceptance and backup/recovery checks
pass. Record a retention/deletion date for separate approval; no automated
destruction is part of this migration. Remove temporary transfer credentials and
access after acceptance, migrate/reconnect Metabase, and only then enable the
reviewed normal production release path.
