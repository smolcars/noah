# Signet migration from Fly.io to Railway

Planning date: **2026-10-26**, execution **2026-10-27**. Status: **Railway services active; production DNS and HTTPS verified; client receive acceptance pending**. Scope: the signet API, PostgreSQL data, Redis data, and Barkd's existing wallet/volume. Mainnet stays on Fly. Related: [Railway assessment](railway_migration_assessment.md).

**Execution authorization:** the user explicitly approved migration and separately approved an administrator merge of [preparation PR #383](https://github.com/smolcars/noah/pull/383). Server, Android, and Android end-to-end CI passed; the iOS CocoaPods CDN certificate failure was the known reason for overriding `client_gate`. The PR merged as `dfc325fb77fa962fe44c290a6692b56d1f0013f7` before the write freeze.

Project `noah`, environment `signet`, and workspace `Noah` are managed by [.railway/railway.ts](../.railway/railway.ts). Railway now holds the **final frozen-source copies**, replacing the rehearsal datasets. Postgres 18 was approved by the user. The API and restored Barkd are active; old Fly application Machines have sleep-command fences and remain stopped.

## Execution record

- Outage began **10:37:33 UTC**; the coordinated source freeze completed **10:41:35 UTC**. Cloudflare DNS switched **11:00:39 UTC**, replacing both Fly A/AAAA records with the Railway CNAME in the Hampus account.
- Normal HTTPS hostname/certificate validation and `/health` HTTP 200 on `signet.noahwallet.io` were verified at **11:07:23 UTC** (about **30 minutes** including verification). Existing Lightning-address metadata also passed through the production hostname. Railway's explicit `customDomainIssueCertificate` API was used after DNS propagated; TLS verification was never disabled.
- Final Postgres archive: **818,301 bytes**, SHA-256 `9be4b813e092dfd39cf202ae1de37bb2fe994437430aa14f0ac14bd98c670a6f`. After restore, all **12 tables' exact row counts and data hashes, 22 SQLx migration checksums, and three sequence states** matched the frozen source.
- Final Redis copy: **two permanent strings**, verified exactly, RDB saved and local AOF fsync acknowledged. Byte/TTL copying had already been rehearsed; no stale rehearsal data was merged into the final keyspace.
- Final complete wallet-volume archive: **2,026,782 bytes**, SHA-256 `add9189b0dea0809c22bdd698151ee0907e8aeb80eaf798cb14d4e5aac698956`. All **nine restored files** matched the archive. Offline SQLite integrity/foreign-key checks passed and the REST token matched the existing API secret.
- Railway Barkd retained fingerprint **`ae27ceed`**, signet network, the exact pre-stop balance, and zero pending receives. Authentication and wallet identity survived a controlled restart. The API's Postgres, Redis, and Barkd startup readiness checks passed; authenticated S3 listing from the Railway container passed.
- HTTPS smoke checks on `api-signet.up.railway.app` passed. An existing Lightning address returned metadata with the unchanged production callback hostname. An API-issued auth challenge had its 600-second TTL and single-use consumption verified; requests varying forged IP headers still shared the LNURL limit (59 HTTP 400 and 16 HTTP 429 responses).
- Manual `signet-final-import` snapshots exist for all three destination volumes, in addition to their scheduled backups. A source Fly wallet-volume snapshot was also requested. Persistent recovery copies are under `~/.local/share/noah-migrations/signet-2026-10-27`, outside this repository, with directory mode 0700/files 0600 on FileVault-protected storage. They include final archives, inventories, source Machine/DNS configurations, and timestamps.
- At cutover, Railway GitHub deployment triggers were absent and releases used explicit pinned-image updates. See the [current release workflow](../.railway/README.md#releases) for subsequent CI automation; the historical Fly signet deployment fence remains in place.
- The CLI refused agent deletion of `/data/barkd-import.tar.gz`; the temporary archive remains on the private wallet volume for human cleanup. Barkd uses `/data/barkd` and ignores that root-level archive. Do not copy wallet archives or credentials into this repository.

Verified preparation:

- Postgres 17.2 export restored on Railway 18.6: **12 application tables, 22 successful SQLx migrations**. The custom archive was 814,161 bytes and took 6.6 seconds to produce. Source and uploaded SHA-256 matched; `repmgr` schema/extension were excluded.
- Redis inventory: **two permanent string keys**, no expiring keys at inventory time. Byte-preserving string copy verified both on Railway; synthetic checks verified binary values, absolute expiry, expired/consumed keys, and refusal of a non-empty target.
- Redis uses AOF with `appendfsync everysec` plus snapshots and `noeviction`; its restart test retained the value and expiration. A dummy file on Barkd's destination volume survived restart and download/hash comparison.
- Daily, weekly, and monthly snapshots are enabled on all three destination volumes. Verified retention: **6/27/89 days** respectively.
- Existing signet application settings were installed privately on the inactive API, including the JWT secret, AWS credentials, and Barkd bearer token. IaC preserves those existing values rather than checking them into source.
- Review follow-up compared live source setting names and the 22 preserved target values: no relevant source settings were missing and all preserved values matched. Optional overrides such as `RUN_MAILBOX_WORKER`, `SES_FROM_ADDRESS`, `AUTH_JWT_TTL_HOURS`, and the additional cron settings were unset on Fly. Repeat this comparison at cutover if source settings change.
- Barkd's read-only baseline recorded signet wallet fingerprint `ae27ceed` and no pending receives at that time. `/data` currently occupies about 32 MiB, including `barkd/db.sqlite` (1,220,608 bytes), its configuration/auth files, and logs. The directory is owned by numeric UID/GID `999:999`. There is also an auth file outside the nested datadir, so archive the entire `/data` volume after stopping the daemon. Recheck receives and file inventory at the freeze.
- Cloudflare CLI authenticated. The user selected the **Hampus account** (`4b89665ef6a974a9db30192e09eeab1e`); zone `noahwallet.io` is `894a63ad1efb270163c649415a574f19`. The `_railway-verify.signet` TXT record is installed. Traffic still uses the original Fly A/AAAA; the future CNAME target is `s8qd7ioc.up.railway.app`.

## Approach and completion criteria

Use a coordinated stop/copy/start migration. Prepare and rehearse the destination first; stop all old application writers, drain and stop Barkd, take final copies of all three stores, restore, then start Railway and change DNS. Replication and dual writes are unnecessary for this outage-tolerant move.

The move is complete when existing signet users, authentication, backup access, mailbox/background jobs, and a complete forwarded Lightning receive work through `https://signet.noahwallet.io`; Barkd has the same wallet identity and survives a redeploy with its restored volume; Redis retains durable state and original expirations; and old Fly writers remain stopped.

## Inventory

Read-only Fly CLI inventory on 2026-10-26 showed:

- **API:** `noah-signet`, one Machine, `iad`, 1 GB RAM, `niteshbalusu/noah-server:latest`, no volume. Use an explicitly recorded image digest for the migration, including the hostname fix described below.
- **Postgres:** `noah-signet-postgres`, one primary, image `flyio/postgres-flex:17.2`, 1 GB `pg_data` volume. Read-only SQL confirmed the `postgres` database is 21,507,219 bytes and contains 12 Noah tables in `public`, including `_sqlx_migrations`. Fly also stores its `repmgr` schema and extension in this same database; explicitly exclude both from the export. Installed extensions: `pg_trgm` 1.6, `plpgsql` 1.0, `repmgr` 5.5. Recheck inventory and source app connection before the final dump.
- **Barkd:** `noah-barkd-signet`, one Machine, `niteshbalusu/noah-barkd:0.8.0`, 1 GB `bark_data` volume mounted at `/data`; wallet lives in `/data/barkd`. The source image digest is `sha256:e1d9dff674ff1c548d27a5c6f2c84e0a690921867ec2b49aacc6a82cb5b78e6d`; registry inspection verified `linux/amd64`. Pin `niteshbalusu/noah-barkd@sha256:e1d9dff674ff1c548d27a5c6f2c84e0a690921867ec2b49aacc6a82cb5b78e6d` for initial Railway activation.
- **Redis:** `noah-signet-redis`, Fly-managed Upstash pay-as-you-go in `iad`, eviction disabled. Rehearsal inventory found two permanent strings in logical database 0; re-inventory after freezing writers.
- **DNS:** `signet.noahwallet.io` has both Fly A and AAAA records, each with a 300-second TTL. Recheck before cutover.

Inventory commands: `flyctl machine list --app <app>`, `flyctl volumes list --app <app>`, `flyctl postgres db list --app noah-signet-postgres`, `flyctl redis list`, and `dig`. These observations are not a data-integrity or restore test.

## 1. Prepare without interrupting Fly

### Railway services

Create a signet environment with four services in the same available region:

- `api`: existing Noah server image, one replica initially, public HTTPS, port 3000, `/health` deployment check, no persistent volume.
- `postgres`: stock supported **Postgres 18** image/template (user approved), persistent volume, empty application database. Rehearse the logical migration from Fly 17.2. Size for measured restored data and working space, not only dump size.
- `redis`: stock supported Redis template with persistent volume, authentication, persistence, and eviction disabled. Verify persistence and restart recovery before using it for auth state.
- `noah-barkd-signet`: **the existing Barkd 0.8.0 image digest**, exactly one replica, persistent volume at **`/data`**, no public HTTP domain or TCP proxy. Keep the current version and wallet format during this move.

Start database services as needed, but hold the API and actual Barkd daemon until the final import is verified. For Barkd volume preparation, deploy a tools-only image with an inert command such as `sleep infinity`, without Barkd or wallet initialization logic. Do not call wallet creation or token rotation. Disable automatic deployments and service sleeping for the cutover; keep API/Barkd always running afterward. Enable database and wallet-volume backup schedules explicitly. Single-node databases are the proposed initial signet topology; this does not rehearse HA failover. [Railway Postgres](https://docs.railway.com/databases/postgresql), [Redis](https://docs.railway.com/databases/redis), [volumes](https://docs.railway.com/volumes/reference).

Set Barkd's `RAILWAY_DEPLOYMENT_DRAINING_SECONDS=30` explicitly as the initial value to rehearse, increasing it if shutdown needs longer. Railway sends SIGTERM followed by SIGKILL and documents a default draining time of zero; the Fly timeout does not transfer automatically. [Teardown controls](https://docs.railway.com/deployments/deployment-teardown), [lifecycle defaults](https://docs.railway.com/deployments/reference#singleton-deploys).

### Required implementation before the window

1. **Allow the exact Railway signet Barkd origin.** [Config](../server/src/config.rs) currently pins `http://noah-barkd-signet.internal:3000`. Add the exact private Railway signet hostname assigned to this service, preserving origin validation, port checks, mainnet separation, and regtest loopback restrictions. Do not allow arbitrary hosts or every `.railway.internal` name. Add focused accepted/rejected-URL tests.
   The LNURL limiter also needs the hosting proxy's trusted client-IP header: retain `fly-client-ip` on Fly and select Railway's `X-Real-IP` only in a Railway process. Never accept a caller-supplied Fly header on Railway. [Railway request headers](https://docs.railway.com/networking/public-networking/specs-and-limits).
2. **Prepare manual Railway deployment settings/workflow.** Reuse the root [Dockerfile](../Dockerfile) and [Barkd image](../fly/barkd.Dockerfile). Record digests; do not follow mutable `latest` during the window.
3. **Pause the old Fly deployment path.** [server-push.yml](../.github/workflows/server-push.yml) deploys signet to Fly after image publication. Merge the guard for `SIGNET_DEPLOY_TARGET`, set that repository variable to `railway`, and check for already queued/running deployments before freezing writes. The guarded mainnet tag job still requires image publication and permits the intentionally skipped Fly signet job; a failed signet job remains a block. The same guard protects manual Barkd signet deployments. Image publication also produces `sha-<commit>` multi-architecture tags for a fixed deployment version.
4. **Prepare and rehearse an offline volume importer** and a binary-safe Redis copier as described below. These are operator tools, not new app features.

Run the existing server tests and formatting checks for the hostname/workflow change before the window. A documentation-only plan does not require running the application.

### Configuration and secrets

Transfer existing **signet** values through private secret handling, including JWT signing secret, Barkd bearer token, AWS/S3/SES settings, Expo token, Sentry, support integrations, and worker/cron settings. Retain the JWT secret so existing sessions stay valid. Never print wallet keys, credentials, challenge values, or token-bearing URLs into logs or this document.

Set the Railway API's:

- `SERVER_NETWORK=signet`, `LNURL_DOMAIN=signet.noahwallet.io`, `HOST=0.0.0.0`, `PORT=3000`.
- **`POSTGRES_URL`** to a reference to Railway Postgres's private connection URL; Noah does not read Railway's `DATABASE_URL` name directly.
- `REDIS_URL` to the Railway Redis private connection URL.
- `BARKD_URL` to the exact private origin permitted by the new validation; retain the existing `BARKD_AUTH_TOKEN` and `BARKD_FORWARDED_INVOICES_ENABLED=true` for normal operation.
- Existing `MAILBOX_WORKER_*`, `POSTGRES_MAX_CONNECTIONS=20`, and `REDIS_POOL_SIZE=64` unless inventory establishes a need to change them.

Keep Barkd's `BARKD_DATADIR=/data/barkd`, `BARKD_BIND_HOST=::`, `BARKD_BIND_PORT=3000`, `BARKD_NO_UI=true`. Check actual private DNS/address-family reachability from the API container. Keep the current signet Ark/chain-source configuration in the restored datadir. S3 objects and external services stay where they are; preserve their references and test access from Railway. [Local deployment settings](../fly/signet.fly.toml), [Barkd settings](../fly/signet.barkd.fly.toml), [Barkd datadir/bind flags](https://second.tech/docs/barkd/start).

**IaC variable ownership:** a plan-only omission test confirmed that removing an
existing API variable from this file plans a destructive `variable.delete`.
Declare every installed setting explicitly, using `preserve()` for privately
installed secrets. Do not add a variable manually and assume future applies will
keep it. Inspect the plan before applying changes.

### Rehearsal and preflight gates

- Record actual source database, PostgreSQL version, Noah schemas/extensions, database size, table counts, SQLx migration versions/checksums, and sequence state. Noah needs `pg_trgm` ([migration](../server/migrations/0008_ln_address_autocomplete.sql)); verify the target can restore it and all required schema objects. Review provider-only objects/permissions rather than suppressing restore errors.
- Rehearse a logical Postgres dump/restore into a disposable destination and time it. Do not start an application with real push/cron credentials against a rehearsal copy.
- Inventory Redis through its authenticated TCP endpoint over the protected Fly private-network proxy: selected database, key types/counts, permanent vs expiring keys, and source `PEXPIRETIME` support. Rehearse byte-preserving copy with absolute expirations on the destination. Do not log names containing challenges or payloads.
- Rehearse volume upload/extraction with dummy files and verify that they survive the destination service redeploy. Inspect only filenames/sizes to identify all source wallet files; do not assume a particular SQLite filename or that `/data` contains no state outside `barkd`.
- A rehearsal with a copied real wallet must remain **offline, with no Barkd daemon**. Use SQLite integrity checks on a disposable extracted copy; never start two instances of the same wallet against Ark.
- Record wallet fingerprint, signet network, version, balance, and pending receive/action status through supported Barkd read APIs; exclude mnemonic/token endpoints. Drain problematic actions before scheduling the final stop.
- Save the old Machine configuration and DNS records privately for recovery. Confirm who controls the DNS zone and Railway project and confirm the ability to request/validate the custom-domain certificate. Keep backups outside the repo on restricted encrypted storage.

Reserve a **one-hour maintenance window**, then refine from rehearsal timings. The 1 GB allocations do not establish the actual data-transfer duration. Budget includes copy, restore, validation, DNS/certificate issuance, and time to abandon the move before activating Railway.

## 2. Freeze the old deployment

1. Pause deploy automation and any scripts/standalone workers/admin tasks that can mutate signet Postgres or Redis. Hold the destination API and Barkd daemon.
2. Begin the outage by stopping every old API/worker writer and preventing Fly proxy autostart and restart/redeployment from resuming them. Verify stopped state and absence of application database sessions. DNS staying on Fly temporarily is acceptable; stale clients must get an error rather than write to the old stores.
3. Keep old Barkd running while already-paid receives finish. Inventory still-payable invoices as well: if any are outstanding, wait for settlement/expiry or establish their safe resumption before stopping. The outage duration includes this drain; do not assume stopping invoice creation cancels existing invoices.
4. Stop Barkd explicitly with SIGTERM and a sufficient timeout; verify the process is gone. Its autonomous wallet work must also be stopped before final copying.
5. Record a freeze timestamp. Keep source Postgres and Upstash running for read-only export, with all application writers fenced. Now capture the final Postgres, Redis, and Barkd copies. These form the migration set; do not substitute an earlier snapshot for one member.

The API starts SQLx migrations, cron, Ark connections, and the mailbox worker at process startup ([main.rs](../server/src/main.rs)). `RUN_MAILBOX_WORKER=false` alone does not disable all jobs. It also currently lacks an HTTP graceful-shutdown hook, so stopping it can interrupt requests; verify the resulting pending work before resuming. Restored mailbox leases may need their existing 120-second claim TTL to expire ([worker](../server/src/mailbox_worker.rs)).

At step 2, **only after migration approval**, fence every source API Machine with
the following command, then ensure it is stopped. The update itself can stop the
running process and begins the outage; it must not be run during preparation:

```sh
flyctl machine update "$SIGNET_API_MACHINE" --app noah-signet \
  --autostart=false --restart no --skip-start --yes
flyctl machine stop "$SIGNET_API_MACHINE" --app noah-signet \
  --signal SIGTERM --timeout 30
```

Read the resulting Machine JSON and require `config.services[].autostart=false`,
`config.restart.policy=no`, and stopped state. Exercise the old Fly route with a
health request and verify that the Machine stays stopped, then check for remaining
application database sessions before recording the freeze timestamp. Keep the
GitHub deploy guard active so a redeploy cannot undo this fence. The installed CLI
supports these flags; [Fly's update implementation](https://github.com/superfly/flyctl/blob/master/internal/command/machine/update.go)
passes `--skip-start` through as `SkipLaunch`, and its
[configuration builder](https://github.com/superfly/flyctl/blob/master/internal/command/machine/run.go)
updates each service's autostart setting explicitly.

**Observed during execution:** the old API was found running again during the
old-route probe despite `autostart=false` and restart policy `no`. Before final
exports, its command was additionally replaced with `/bin/sleep infinity` and it
was stopped again. The absence of API database sessions was confirmed. Keep this
command fence on the historical source Machine: an unexpected Machine wake must
not launch the server. Barkd's historical Machine has the same command fence.

## 3. Export and restore Postgres

Use `pg_dump`/`pg_restore` for the **application database**, not a physical copy of Fly's Postgres volume. Include schema, data, indexes, constraints, sequences, and `_sqlx_migrations`. Omit Fly roles/ACLs, the `repmgr` schema, and the `repmgr` extension; restore as the target application owner. Use matching **Postgres 18** dump/restore tools for the approved 17.2-to-18 migration. A dump tool cannot read a newer server major. [pg_dump reference](https://www.postgresql.org/docs/current/app-pgdump.html).

Configure private libpq service definitions `noah_signet_source` and `noah_signet_target` with password-file/secret handling. For the source, a local tunnel can use `flyctl proxy 15432:5432 --app noah-signet-postgres`; use the app's actual database and correct TLS settings. Reach the destination through a private SSH tunnel, TLS-protected temporary TCP proxy, or importer inside Railway's private network. Do not assume Railway private DNS resolves on a laptop. [Fly proxy](https://docs.fly.io/flyctl/cmd/fly_proxy).

Command templates, to run only after the freeze and service definitions are verified:

```sh
umask 077
SIGNET_MIGRATION_DIR="$(mktemp -d)"

pg_dump --dbname='service=noah_signet_source' \
  --format=custom --no-owner --no-privileges \
  --exclude-schema=repmgr --exclude-extension=repmgr \
  --file="$SIGNET_MIGRATION_DIR/postgres.dump"

pg_restore --list "$SIGNET_MIGRATION_DIR/postgres.dump" \
  > "$SIGNET_MIGRATION_DIR/postgres.contents"

pg_restore --dbname='service=noah_signet_target' \
  --no-owner --no-privileges --single-transaction --exit-on-error \
  "$SIGNET_MIGRATION_DIR/postgres.dump"

psql 'service=noah_signet_target' --set=ON_ERROR_STOP=1 --command='ANALYZE;'
```

Restore into an empty target application database **before the API ever initializes it**. Fix any restore failure before continuing; use a newly empty destination for a retry. A single-transaction restore prevents a partially applied archive. Compare all application table counts, required extensions/indexes/constraints, SQLx migration checksums, and sequence positions before starting jobs. Protect and checksum the final archive. [pg_restore reference](https://www.postgresql.org/docs/current/app-pgrestore.html).

## 4. Copy Redis with expiration deadlines intact

Migrate from **`noah-signet-redis` only**, using its primary authenticated TCP endpoint through the protected private-network proxy. Stop all its writers first. The app stores:

- `maintenance:last_round_timestamp` and `maintenance:round_counter`: permanent state, preserve exact values and absence of TTL.
- `k1:*`: one-time challenges with a 600-second TTL, consumed using `GETDEL`.
- `invoice:*`: invoice exchange responses with a 60-second TTL.
- `email_verification:*`: verification email/code state with a 600-second TTL.

Sources: [maintenance](../server/src/cache/maintenance_store.rs), [K1](../server/src/cache/k1_store.rs), [invoice](../server/src/cache/invoice_store.rs), [verification](../server/src/cache/email_verification_store.rs), [K1 TTL](../server/src/main.rs).

The rehearsal confirmed all current source keys are strings. A byte-preserving string copier using `GET` and `SET ... PXAT` avoids cross-provider RDB encoding dependencies while preserving exact values and expiration deadlines. It must reject an unexpected data type, refuse a non-empty target, capture after all source writers stop, and verify restored values/deadlines. Rehearse both permanent and expiring values. The rehearsed operator helper and protected manifests are outside the repository in `/tmp/noah-railway-tools`; they contain connection credentials and potentially authentication state and must not be committed.

The equivalent general-purpose `DUMP`/`RESTORE` approach has this contract:

1. Require an explicitly selected, fresh empty target database; verify source and target are different. Connect using secrets without echoing them. Iterate the entire selected keyspace using `SCAN` until cursor zero, tolerating duplicates.
2. For each still-existing key, capture `DUMP` bytes and `PEXPIRETIME` from the frozen source. A short per-key read-only Lua script can capture them atomically if needed. `DUMP` carries the value/type, **not its expiration**. Do not pass its bytes through UTF-8/string conversions or shell substitution. [Upstash DUMP](https://upstash.com/docs/redis/commands/generic/dump), [expiration deadline](https://upstash.com/docs/redis/commands/generic/pexpiretime).
3. Restore permanent keys using `RESTORE key 0 payload`; restore expiring keys using `RESTORE key <original-unix-ms-deadline> payload ABSTTL`. Skip missing or already-expired keys; never turn a missing-key sentinel into TTL zero. Check source/target clocks and fail on an incompatible payload rather than dropping data. Rehearse the actual provider/image combination. [Redis RESTORE](https://redis.io/docs/latest/commands/restore/).
4. Verify permanent values/types exactly and each surviving expiration deadline. Compare expiring keys with allowance for natural expiry during the copy; an equal `DBSIZE` alone is not sufficient. Retain a protected manifest/report with counts, hashes, and expiry metadata, not raw auth values in logs.
5. Verify target disk persistence and restart recovery before activating the API. On a failed copy, retry into an empty target while both application sides remain stopped. Once the new API starts, replaying the old copy can resurrect challenges that it has already consumed.

Use a normal TCP Redis client for these commands; typed Upstash HTTP SDK helpers do not necessarily expose them. If payload compatibility fails, resolve it in rehearsal; the current application uses string values, allowing a byte-preserving string copier with absolute expirations as a scoped alternative after inventory confirms no other types.

## 5. Copy the stopped Barkd datadir

Copy **the complete `/data` volume**, including `/data/barkd`, SQLite databases and any remaining `-wal`, `-shm`, or journal files, wallet configuration, mnemonic, and REST auth secrets. Inventory found an additional auth file at `/data/auth_token`; preserve it too. The archive is a wallet secret. A lone `.sqlite` file or a newly created wallet is not equivalent to this migration. Copying while no transactions run is safe; a WAL or rollback journal left after an unclean stop must stay paired with its database. [SQLite backup/corruption guidance](https://sqlite.org/howtocorrupt.html#_backup_or_restore_while_a_transaction_is_active), [Barkd authentication/datadir](https://second.tech/docs/barkd/authenticate).

### Access the source after stopping it

Fly SSH/SFTP requires a running Machine. **Do not restart the normal Barkd command to retrieve its files.** Keep the same source volume, temporarily replace the stopped Machine's command with `/bin/sleep infinity`, and start that maintenance instance only. The installed `flyctl machine update --help` supports `--command`, `--restart no`, and `--skip-start`; validate the saved config and retain the same volume attachment. Source [entrypoint](../fly/barkd-entrypoint.sh) accepts this command without invoking Barkd.

Illustrative templates, after recording the real Machine ID and saving its original config:

```sh
flyctl machine stop "$SIGNET_BARKD_MACHINE" \
  --app noah-barkd-signet --signal SIGTERM --timeout 30

flyctl machine update "$SIGNET_BARKD_MACHINE" \
  --app noah-barkd-signet --command '/bin/sleep infinity' \
  --restart no --skip-start --yes

flyctl machine start "$SIGNET_BARKD_MACHINE" --app noah-barkd-signet
```

Verify no Barkd process exists. In that maintenance instance, create a mode-0600 archive outside the volume using `tar -C /data -czf /tmp/noah-signet-barkd.tar.gz .`; set `umask 077` first. Compute its SHA-256, then download with:

```sh
flyctl ssh sftp get /tmp/noah-signet-barkd.tar.gz \
  "$SIGNET_MIGRATION_DIR/barkd.tar.gz" \
  --app noah-barkd-signet --machine "$SIGNET_BARKD_MACHINE"
```

Verify the downloaded checksum, protect the archive, then stop the maintenance instance. Keep the source wallet frozen. A Fly volume snapshot is an additional recovery copy, not a substitute for the final stopped archive. [Fly SFTP get](https://docs.fly.io/flyctl/cmd/fly_ssh_sftp_get).

### Populate the destination volume

Use the same Railway service/volume in **maintenance/import mode** to receive the archive. Railway documents the following command shape; substitute verified project/environment/volume IDs and rehearse it with dummy files first:

```sh
railway volume --project <PROJECT_ID> --environment <SIGNET_ENV_ID> \
  files --volume <VOLUME_ID> upload \
  "$SIGNET_MIGRATION_DIR/barkd.tar.gz" /barkd-import.tar.gz
```

With `/data` as the mount, the volume-relative path maps to `/data/barkd-import.tar.gz`. The inspected released CLI requires an active deployment despite a changelog claiming container-free volume access, which is why the tools container runs during import. It needs shell, tar, checksum, and ownership tools; budget disk for both archive and extracted data. Native SFTP to that tools container is a documented fallback. [Volume upload](https://docs.railway.com/cli/volume#manage-files), [companion research and released-source evidence](railway_signet_import_research.md).

Use `railway ssh` into that explicit importer service to compare SHA-256, inspect/reject unexpected archive paths or escaping links, and extract into the fresh destination using `tar -xzpf /data/barkd-import.tar.gz -C /data`. Verify the result is `/data/barkd` (not `/data/barkd/barkd`). Do not merge into an initialized wallet directory. `railway run` executes locally; pre-deploy hooks do not mount the volume. Neither is the place to extract the wallet. [Remote SSH](https://docs.railway.com/cli/ssh), [pre-deploy limitations](https://docs.railway.com/deployments/pre-deploy-command).

Preserve numeric ownership (`999:999` on the recorded source image) and verify it matches the pinned destination image's `barkd:barkd` user/group. Correct the **entire imported tree** if needed; preserve restrictive file permissions and set the datadir to mode 0700. The current entrypoint changes only the directory ownership, so it will not repair every imported root-owned file. Verify expected files and offline SQLite `PRAGMA integrity_check` and `PRAGMA foreign_key_check` on a disposable extracted copy. Do not delete journals manually. Remove the uploaded archive after retaining a protected external copy, then take a destination snapshot before switching the service to its normal Barkd command.

## 6. Activate Railway, then switch DNS

1. Verify all imports, private references, and backups. Confirm old API/worker and old Barkd remain stopped and cannot auto-start. Mark this as the final point for the simplest rollback.
2. Start **Railway Barkd first** on the restored volume with the pinned image. Clear the importer's start-command override so the image's normal entrypoint and user handling run. Verify the recorded fingerprint, existing wallet, signet network, Ark connection, auth token, and pending receives. A daemon start may sync/change wallet state even without public traffic: treat this as the boundary after which the old wallet copy may be stale. [Start-command behavior](https://docs.railway.com/deployments/start-command).
3. Start **one Railway API instance** against the restored Postgres and Redis. Verify migrations have no unexpected changes, Redis connects, the Barkd startup readiness check succeeds, and jobs/mailbox sessions recover. `/health` is only an HTTP liveness response; Barkd probe failure only logs a warning and can fall back to device wake-up. Neither alone proves forwarded invoices work.
   Check the LNURL limiter through Railway's edge with forged `X-Real-IP` and `fly-client-ip` headers to confirm the proxy supplies the actual client IP. Verify whether GitHub autodeploy is connected, retain the intended deployment policy, and record the final API commit/image digest.
4. Use Railway's temporary HTTPS hostname for smoke checks; ensure generated Lightning callbacks still use `signet.noahwallet.io`. Register that custom domain on the API service and set target port 3000. Install Railway's displayed CNAME and ownership TXT records and validate TLS after DNS changes. DNS/certificate completion can exceed the nominal maintenance window; do not reopen traffic until the actual hostname works. [Railway custom domains](https://docs.railway.com/networking/domains/working-with-domains#custom-domains).
5. Replace the **old A and AAAA records** with Railway's required records (normally the provided CNAME for this subdomain). A CNAME cannot coexist with old A/AAAA at the same name. Do not leave IPv6 pointed at Fly. Keep Fly writers stopped through propagation; cached clients can fail temporarily but cannot split the data between hosts.
6. Test through the real domain: existing JWT session and fresh login/challenge one-time consumption, existing user/Lightning identity, backup listing/download from existing S3 keys, price endpoints, invoice exchange, verification, and a real signet forwarded receive reaching settlement/delivery. Check mailbox wake-up/job reporting, recurring jobs, and maintenance state without resetting counters.
7. Perform a controlled Barkd redeploy/restart and confirm wallet identity/state persist. Observe errors, memory, DB connections, disk, receive backlog, and backup completion through the next scheduled job runs before retiring the old services.

The Cloudflare CLI batch below was validated with `--dry-run`. It is a preview,
not authorization to change DNS. Re-read the live records before the approved
cutover; the recorded IDs may have changed. Only after approval and acceptance
checks should the same reviewed request be sent without `--dry-run`:

```sh
CLOUDFLARE_ACCOUNT_ID=4b89665ef6a974a9db30192e09eeab1e \
  npm exec --yes --package cf -- cf dns records batch \
  --zone 894a63ad1efb270163c649415a574f19 --dry-run \
  --deletes '[{"id":"7c1a15559f4e08baabbaf2df7f6c2929"},{"id":"3d033673c45fe4fc66fca87f7f9759f4"}]' \
  --posts '[{"type":"CNAME","name":"signet.noahwallet.io","content":"s8qd7ioc.up.railway.app","proxied":false,"ttl":300}]'
```

Existing React Native and native iOS/Android clients already use `signet.noahwallet.io`; keeping that hostname avoids a client release ([React Native constants](../client/src/constants.ts), [iOS endpoint](../apps/ios/Noah/Services/Server/ServerEndpoint.swift), [Android endpoint](../apps/android/app/src/main/java/com/noahwallet/services/server/ServerClient.kt)).

## Rollback and retirement

**Before Railway Barkd/API is activated:** discard or preserve the failed destination imports for diagnosis, restore the saved Fly Machine commands/config, restart the original Barkd and API against their original stores, and restore DNS if it changed. Keep Railway application processes stopped. Source Redis expirations naturally continue during downtime.

**After either Railway application process has run:** stop new traffic and jobs, drain pending paid actions, and stop the Railway wallet. Treat Railway's three stores as authoritative. Copy current Postgres data, current Redis values/deadlines, and the latest stopped Barkd datadir back to isolated Fly targets, verify, then restart only the Fly stack and reverse DNS. In particular, replace the old Redis keyspace rather than merging it: old consumed challenges must not survive. Check reverse Redis payload compatibility. Because the target is Postgres 18, do not assume a new dump will restore into Fly's old Postgres 17; provision a compatible recovery target or prove reverse-format compatibility first. A forward fix can be quicker than this reverse migration. Never reactivate the frozen old wallet alongside the new one or restore just one store to an earlier time.

Retain the stopped source services/volumes and protected migration archives for an agreed observation period (proposed: seven days). They are historical recovery material, not a live rollback replica. After successful acceptance, remove the old signet API, Postgres, Barkd, and **signet Upstash Redis**, and close any temporary import endpoints/credentials. Check detached volumes, IPs, and billing before considering retirement complete. Keep all mainnet services/deployment paths intact.

## Acceptance and observation remaining

- Use the signet app to confirm the existing session, backup access, and a complete forwarded Lightning receive. Public metadata and backend readiness checks do not replace that client test.
- Observe scheduled jobs and backup completion. Keep historical Fly stores stopped/fenced for the proposed seven-day observation period before retirement.
- Have a human remove the temporary wallet import archive using the authenticated Railway CLI; its agent safety guard rejected automated deletion.
