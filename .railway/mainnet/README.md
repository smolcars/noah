# Mainnet preparation

Production cutover completed on **2026-10-29**. The preparation and rehearsal
records below are historical; current release instructions follow them.

The [migration runbook](../../docs/railway_mainnet_migration_plan.md) requires
explicit cutover approval. These files do not create a production environment,
copy data, or change GitHub variables on merge. Signet's configuration is separate.

## Provisioning record

The held bootstrap was provisioned on **2026-10-29** in the existing Noah project
(`8847a6a1-bf07-4321-a62b-9839e9954e46`), production environment
`f39192f6-6cea-4c33-83cd-b62259cd10cb`.

| Resource | ID |
| --- | --- |
| `api-mainnet` | `4f866c7b-6b84-4275-a9fb-aee6bceb1fa6` |
| `noah-barkd-mainnet` | `679b8d67-f0db-43ba-8b02-213d81482d11` |
| `postgres-mainnet` | `421299ae-2646-42f4-8a23-155e47c15a43` |
| `redis-mainnet` | `a214a946-1dd7-4904-9267-ebdc03b0bca7` |
| `bark_data_mainnet` volume | `78ed2cd5-3d94-4bdf-9491-27be0d1dcc1d` |

Runtime checks confirmed Postgres 18.6 with no application tables; empty Redis
8.2.11 with AOF, `everysec`, and `noeviction`; and both application containers
running only `/bin/sleep infinity`. The 1 GB wallet volume is mounted at `/data`
and contains no wallet database. Subsequently, 21 production integration secrets
and settings were staged with deploys skipped and verified equal to the running
Fly API, including the existing `RUST_LOG`. `NTFY_AUTH_TOKEN` was excluded and
destination connection references were retained. Backup/recovery and Redis
rehearsal results are recorded below. HA conversion is complete; application
activation still requires separate cutover approval.

## Postgres rehearsal

The **2026-10-29 18:51:13 UTC** source snapshot was exported from the MacBook Pro
using a shared read-only snapshot for the dump and verification manifest. The
Postgres 16.15 dump took 4.27 seconds; the single-transaction restore into Railway
18.6 took 4.17 seconds. These timings exclude wallet draining, transfer,
verification, DNS/TLS, and backup/recovery preparation.

All 12 application tables matched exact row counts and full-row fingerprints,
including 984 users and all 22 successful SQLx migrations/checksums. Three
sequence states matched their archived values, with next values above existing IDs;
all indexes and constraints were valid. `ANALYZE` completed.

The restore retains `pg_trgm` 1.6 and omits Fly's `pg_stat_monitor`, `pgaudit`, and
PgBouncer helper schema/authentication function. The application has no
references to those provider objects. Source ownership and privileges were not
imported. The encrypted snapshot is retained privately outside Git; temporary
plaintext dump and remote staging files were removed.

Railway now contains a **rehearsal snapshot**, not the final cutover dataset.
Fly remains authoritative and continues accepting writes. API and Barkd remain
on `/bin/sleep infinity`; the wallet volume is empty after the offline recovery
test below.
Replace the rehearsal copies with a fresh final migration set after the
approved write freeze, before activating either application.

## Postgres HA preparation

On **2026-10-29**, [enable-ha.mjs](enable-ha.mjs) converted only the existing
Railway destination with the native `railway postgres ha convert` command:

- Three Postgres 18.6 data nodes: `postgres-mainnet`, `Postgres-2`, `Postgres-3`.
- Three etcd services, each with its own volume.
- One `Postgres HA` HAProxy service with **two running replicas**.

The API's existing connection reference was moved to HAProxy by the native
conversion, and its running tools container was checked to resolve that endpoint.
The original database volume and the imported rehearsal dataset were retained.
The script fixes the Noah project, production environment and database UUIDs;
refuses active application images, missing PITR wiring or an unexpected Postgres
image; and requires `--apply` before conversion. Repeating it on an HA cluster
does not convert or scale anything. It does not claim runtime health from a
successful conversion response.

```sh
RAILWAY_CLI=/path/to/railway bun .railway/mainnet/enable-ha.mjs
# Only for the initial conversion of this held standalone destination:
RAILWAY_CLI=/path/to/railway bun .railway/mainnet/enable-ha.mjs --apply
```

A quick automatic-failover drill wrote a disposable marker through HAProxy and
restarted only the original primary with `railway restart`. Patroni promoted
`Postgres-3`; a new connection through the same proxy read the marker and accepted
a write. The old primary rejoined, both replicas streamed with zero replay lag,
and all 12 application-table fingerprints still matched the imported snapshot.
The marker schema was removed afterward. This was a graceful primary restart,
not a network-partition or regional-outage test; no outage duration was measured.

On the promoted primary, `pgbackrest check` passed and the archive catalog reported
`ok` with a completed full backup. The HA image needs `WAL_ARCHIVE_*` mapped to
pgBackRest options and its image-owned `${PGDATA}/.pgbackrest_repo_path` when
running these checks through a new SSH session, as the CLI's PITR probe does.
The earlier standalone PITR restore exercise below was not repeated on the HA
cluster. Keep a fresh backup and final recovery verification in the cutover.

The installed native policy is **asynchronous replication** (`synchronous_mode`
false). Automatic failover can therefore lose recent unreplicated commits; it
does not establish zero data loss. Choose the required durability policy before
activation. API and Barkd remain on their sleeping tools images, Fly remains
authoritative, and DNS is unchanged.

## Backup and recovery rehearsal

Postgres PITR was enabled with the native CLI on **2026-10-29**, using the private
`Postgres-PITR` bucket. WAL archiving and a completed pgBackRest full backup
(`20261029-191226F`) were verified directly. The stock image schedules weekly
full and daily differential backups, retaining four full and fourteen
differential backups. The initial recovery exercise below used the standalone
database, before the HA conversion recorded above.

A synthetic marker was committed before **19:35:04.167420 UTC** and changed
afterward, only in the Railway rehearsal database. `railway postgres pitr restore`
created a separate service at that timestamp: the recovered marker had the
earlier value while the primary retained the later one. All 12 public table
fingerprints, three sequences, and index/constraint checks passed on the recovered
database. Its WAL archiving was off, so the recovery clone did not overwrite the
source archive. The temporary recovery service and marker were removed after
validation. Volume deletion was accepted; Railway retains the detached volume
until its scheduled purge on **2026-10-31 19:47 UTC**.

Daily, weekly, and monthly volume backups are enabled for Postgres, Redis, and
Barkd. Railway reports retention of 6, 27, and 89 days respectively. Named manual
snapshots, `mainnet-preparation-2026-10-29`, were also created and listed. The
wallet snapshot covers an empty staging volume; it does not back up the live Fly
wallet. Keep the complete offline wallet archive and its recovery test in the
cutover requirements. The encrypted logical Postgres rehearsal backup is retained
privately outside Railway and Git.

The SDK exposes `backupSchedules`, but **CLI 5.71.0 does not reliably apply it**.
A controlled test on the empty held wallet volume changed its API policy to
daily-only: IaC planned all three schedules and reported an applied result, but
API readback still showed daily-only. The full policy was immediately restored
and verified through the API. The native database compiler also drops attachment
and variable overrides. Do not add ineffective backup declarations or treat a
successful apply as proof of protection. Use the supported API and verify readback:

```sh
railway api 'mutation Policy($id:String!,$kinds:[VolumeInstanceBackupScheduleKind!]!){volumeInstanceBackupScheduleUpdate(volumeInstanceId:$id,kinds:$kinds)}' \
  --raw-var id="$VOLUME_INSTANCE_ID" --var 'kinds=["DAILY","WEEKLY","MONTHLY"]'
railway api 'query Policy($id:String!){volumeInstanceBackupScheduleList(volumeInstanceId:$id){kind retentionSeconds}}' \
  --raw-var id="$VOLUME_INSTANCE_ID"
```

Use the **volume instance**, not volume ID, from the selected production
environment. The authoring file includes the existing PITR bucket so a later plan
does not delete it. PITR enablement and the installed `WAL_ARCHIVE_*` wiring remain
CLI-managed. Use `railway postgres pitr enable` for initial setup and
`railway postgres pitr restore --at <UTC> --new-service-name <temporary-name>` for
recovery drills. Validate the new database and remove only that disposable
service/volume. Never apply the full environment graph while temporary recovery
resources exist: omitted resources can be planned for deletion. Reject plans that
remove the PITR bucket, credentials, or existing volumes.

## Redis rehearsal

A read-only source export at **2026-10-29 19:31:47 UTC** found two permanent string
keys, both in the maintenance family, and no other keys. Its encrypted artifact
was transferred from the MacBook Pro; both ciphertext and plaintext hashes were
verified, with plaintext kept in memory. The empty Railway Redis DB0 received
both values and retained their exact bytes and permanent expirations.

Separate synthetic test databases verified binary values, absolute expiry
deadlines, expired-key skipping, one-time `GETDEL` behavior, and refusal to restore
into a nonempty target. After an acknowledged AOF fsync and a destination-only
Redis restart, the process ID changed, the source values and absolute deadline
survived, and consumed keys remained absent. Synthetic keys were then removed.
Fly was unchanged. This live scan is a rehearsal, not a frozen final snapshot;
replace the destination keyspace after the approved freeze rather than merging
it with a later export.

## Barkd wallet recovery rehearsal

A fresh Fly volume snapshot at **2026-10-29 20:42:57 UTC** was restored into a
separate temporary Fly app with a Debian tools-only Machine. The original Barkd
0.7.1 Machine kept running with the same instance/configuration. The clone never
contained or ran the Barkd executable. Its mount-root ownership was normalized
to the recorded source metadata before archiving the complete `/data` tree.

The encrypted archive contains all seven regular files, totaling 35,533,811
uncompressed bytes, including configuration/authentication material and the
SQLite database. Archive encryption/decryption hashes were verified, and the
archived auth token matched the staged API setting without printing either.
The archive was streamed into a private temporary subdirectory of the held
Railway wallet volume; `/data/barkd` remained absent throughout the test.

All restored file hashes, numeric owners/groups and modes matched the archive,
including the `999:999`, mode-0700 wallet directory. SQLite checks on a separate
disposable copy passed: **32 tables, integrity `ok`, zero foreign-key violations**.
The pristine restored files also matched after restarting only Railway's held
tools container. No Barkd daemon or API process was activated.

The temporary Fly app/volume and Railway plaintext copies were removed after
verification. The Railway wallet volume again contains only `lost+found`.
The encrypted complete-volume archive, manifest and recovery evidence are
retained privately outside Git and temporary storage. Both public health probes
returned 200 afterward.

This tests recovery from a live volume snapshot, which becomes stale as Fly
continues writing. It is not the final stopped-wallet migration set or a Barkd
0.8.0 runtime test. After the separately approved freeze, take a fresh complete
archive from the stopped source, repeat validation, and only then activate the
single restored wallet as specified in the runbook.

## Held HA configuration

[railway.ts](railway.ts) now captures the **existing held HA environment**,
replacing the retired standalone bootstrap. It rejects every project/environment
except Noah production and requires `RAILWAY_MAINNET_ENVIRONMENT_ID` to equal
`f39192f6-6cea-4c33-83cd-b62259cd10cb` and
`RAILWAY_MAINNET_POSTGRES_MAJOR=18`. Link the native CLI to the Noah project and
production environment, then preview:

```sh
railway config plan --file .railway/mainnet/railway.ts --environment "$RAILWAY_MAINNET_ENVIRONMENT_ID"
```

The snapshot contains all ten services, eight volumes and the PITR bucket. Both
API and Barkd still run `/bin/sleep infinity`. Existing variables, including
connection references and `RUST_LOG`, use `preserve()`; no secret values are in
the file. The retired `NTFY_AUTH_TOKEN` remains absent. Volume sizes are capacity
limits, not measured disk usage.

Do not apply the raw output of `railway config pull` over HA. CLI 5.71.0's import
omits cluster membership metadata and renders the data nodes with `postgres()`.
The database compiler supplies standalone defaults, including credential fields,
and ignores authored HA variables and mount overrides. This snapshot instead
uses `service()` for the existing database services, preserves membership and
their exact mounted volume IDs, and keeps image update policies under the native
database flows. The four database/service classification warnings are expected.
The reviewed native plan reports **zero changes**; no IaC apply was needed.

Use Railway's native HA commands for conversion, scaling and switchover; refresh
and review the snapshot after changing topology. PITR and backup policies also
remain CLI/API-managed. Never apply the retired bootstrap from Git history, or
apply this held snapshot after activating the applications: it could revert
their images and commands. No normal release workflow performs initial migration
or applies this infrastructure file.

During the approved cutover, after verifying final imports, use the CLI/API to
replace the tools sources with the reviewed pinned images, clear the sleep start
commands (preserving each image's normal entrypoint), and configure Dockerfile
builds for later wrapper uploads. Keep one Barkd replica on the same `/data`
volume, its required mount, and 30-second grace. Configure API `/health`, port
3000 and the custom domain. Start Barkd first, verify it, then start the API as
specified in the runbook. These changes are not performed by CI or this PR.

## Releases after acceptance

Railway is the sole production deployment target. Pushing a `v*` tag runs Server
CI and image publication, then deploys that commit's published image to mainnet.
Master pushes deploy only signet. The tag caller inherits secrets so the reusable
workflow receives `RAILWAY_MAINNET_TOKEN`, with the existing GitHub `production`
environment approval and ref restrictions still enforced.

The Fly tag job and both manual Fly deployment workflows have been removed.
Current workflows do not read `MAINNET_DEPLOY_TARGET`; retain its repository
value `railway` to fence historical Fly workflow revisions. Keep old Fly wallet
Machines stopped and inert; removing CI paths does not delete retained resources.

In GitHub's existing `production` environment, install:

- Secret `RAILWAY_MAINNET_TOKEN`: scoped to the Railway production environment.
- Variables `RAILWAY_MAINNET_ENVIRONMENT_ID`, `RAILWAY_MAINNET_API_SERVICE_ID`, and
  `RAILWAY_MAINNET_BARKD_SERVICE_ID`: actual UUIDs from provisioning. Verify the API
  ID belongs to `api-mainnet` and the Barkd ID to `noah-barkd-mainnet`; no service is created
  by the release workflow. Signet's known IDs and identical API/Barkd IDs are rejected.

Retain the `production` environment's approval/ref restrictions. Tag releases
run Server CI and image publication before calling
[the Railway workflow](../../.github/workflows/mainnet-railway-deploy.yml).
Master still deploys only signet. Each release imports a pinned image through a
temporary single-`FROM` Dockerfile using CLI 5.71.0; it does not apply IaC or compile
Rust. API and Barkd releases share a concurrency group.

For an explicit release of a previously reviewed, published digest, dispatch from
master:

```sh
gh workflow run mainnet-railway-deploy.yml --ref master \
  -f component=api -f image_digest='sha256:<reviewed-server-digest>'
```

Use `component=barkd` with the reviewed Barkd digest for a manual wallet upgrade,
after a fresh backup and wallet-format compatibility checks. Build new Barkd
images using [docker/barkd/Dockerfile](../../docker/barkd/Dockerfile) and the manual
`barkd-build-push.yml` workflow. API releases never
restart Barkd. The workflow fixes each component's image repository and target
service, rejects malformed digests, and stops on upload failure. The API also
checks public `/health`; verify the new serving deployment/revision separately,
since public health alone could still come from an older container. A manual API
digest must already have passed the appropriate code review and CI. Image rollback
does not undo database migrations or wallet state.
