# Mainnet preparation

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
rehearsal results are recorded below. HA and application activation remain
separate decisions/steps.

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
on `/bin/sleep infinity`; the wallet volume has not received source state.
Replace the rehearsal copies with a fresh final migration set after the
approved write freeze, before activating either application.

## Backup and recovery rehearsal

Postgres PITR was enabled with the native CLI on **2026-10-29**, using the private
`Postgres-PITR` bucket. WAL archiving and a completed pgBackRest full backup
(`20261029-191226F`) were verified directly. The stock image schedules weekly
full and daily differential backups, retaining four full and fourteen
differential backups. This is still a single-node database, without HA.

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

## Held preparation configuration

[railway.ts](railway.ts) provisions and preserves the **held, single-node**
`production` environment in the existing Noah project. Set
`RAILWAY_MAINNET_ENVIRONMENT_ID` to its actual UUID and
`RAILWAY_MAINNET_POSTGRES_MAJOR=18`, the chosen target for the 16.15 source.
Rehearse the logical restore before cutover.
The file rejects missing/mismatched IDs and signet. After provisioning approval,
link the native CLI to project `8847a6a1-bf07-4321-a62b-9839e9954e46` and that
environment, then preview:

```sh
railway config plan --file .railway/mainnet/railway.ts --environment "$RAILWAY_MAINNET_ENVIRONMENT_ID"
```

Review the plan before any apply. The graph has Postgres, persistent Redis with
AOF and `noeviction`, a 1 GB wallet volume, the PITR bucket, and two Debian tools
services. Database volumes and their installed backup policies remain in place.
Both API and Barkd run `/bin/sleep infinity`; neither application or wallet can
start. There is no public domain or API healthcheck at this stage. Inventory disk
requirements and adjust capacity before applying or importing. Transfer existing
production secrets privately; `preserve()` keeps installed values but does not
import secrets from Fly. Review additional variables before every apply.
Exclude the retired `NTFY_AUTH_TOKEN`. Preserve the existing production `RUST_LOG`
value exactly; the API defaults to `server=debug,tower_http=debug` when it is unset.
Service/volume names (`api-mainnet`, `postgres-mainnet`, `redis-mainnet`,
`bark_data_mainnet`, `noah-barkd-mainnet`) are distinct from signet so the bootstrap
does not bind to existing signet resource IDs by name.

After initial creation, inspect another plan before importing data. The database
creation template may use its default Redis command; the provisioning run needed
a second reviewed apply to install the declared AOF/eviction settings. Verify
those settings in the running Redis process, not just the authoring file.

CLI 5.71.0 also reports `deploy.sleepApplication: false` as drift when the
environment-config response omits that default. The public API confirms `false`
for both held services. Do not reapply solely for those two default-value diffs.

This is **not an HA or active-runtime configuration**. PITR enablement, recovery,
and backup policies use Railway's CLI/API, with runtime verification. Never
reapply this graph over a converted HA cluster or activated wallet. Doing so could
remove cluster resources or stop applications.
Do not enable normal release workflows to perform the initial migration.

During the approved cutover, after verifying final imports, use the CLI/API to
replace the tools sources with the reviewed pinned images, clear the sleep start
commands (preserving each image's normal entrypoint), and configure Dockerfile
builds for later wrapper uploads. Keep one Barkd replica on the same `/data`
volume, its required mount, and 30-second grace. Configure API `/health`, port
3000 and the custom domain. Start Barkd first, verify it, then start the API as
specified in the runbook. These changes are not performed by CI or this PR.

## Releases after acceptance

`MAINNET_DEPLOY_TARGET` is a **repository-level** variable:

| Value | Fly paths | Railway paths |
| --- | --- | --- |
| Missing or `fly` | Existing tag and manual deploys | Disabled |
| `paused` or any unknown value | Disabled | Disabled |
| `railway` | Disabled | Enabled |

At the approved freeze, set `paused`, cancel/drain queued and running deployments,
and fence the Fly Machines. Old workflow revisions lack these guards; the variable
alone does not cancel work or stop Machines. Set `railway` only after activation,
DNS/TLS, payment acceptance, and verification of the active runtime settings.

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
master after acceptance:

```sh
gh workflow run mainnet-railway-deploy.yml --ref master \
  -f component=api -f image_digest='sha256:<reviewed-server-digest>'
```

Use `component=barkd` with the reviewed Barkd digest for a manual wallet upgrade,
after a fresh backup and wallet-format compatibility checks. API releases never
restart Barkd. The workflow fixes each component's image repository and target
service, rejects malformed digests, and stops on upload failure. The API also
checks public `/health`; verify the new serving deployment/revision separately,
since public health alone could still come from an older container. A manual API
digest must already have passed the appropriate code review and CI. Image rollback
does not undo database migrations or wallet state.
