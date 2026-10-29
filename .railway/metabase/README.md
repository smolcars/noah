# Metabase on Railway

Metabase v0.62.1.3 runs on Railway separately from the Noah wallet services. Its
application database stores dashboards, questions, users and connection settings;
its reporting connection reads Noah's existing mainnet database.

Configuration lives in [the production IaC](../mainnet/railway.ts), alongside the
existing resources so a full-environment apply cannot omit them. Application
releases do not apply this file. Use a fresh reviewed native plan and reject any
change to the wallet services, HA cluster, existing volumes or PITR bucket.

## Small deployment

- `metabase`: one instance, 1 vCPU / 1.5 GiB limit, Java heap 128–512 MiB. No volume.
- `postgres-metabase`: one private Postgres 18 node, 1 vCPU / 2 GiB limit, 5 GiB
  persistent volume. No public database proxy, HA or PITR initially.
- Both run in Virginia in the existing production environment. Railway bills
  actual CPU/memory usage; resource limits are ceilings. Budget roughly $12–18
  extra per month for light use, subject to measured memory and CPU after startup.
- Daily volume backups protect Metabase's application database; this permits up
  to a day's loss of dashboard edits. Keep an independent encrypted export.
- Increase application memory only if startup and representative dashboards do
  not fit reliably. Changing the limit alone does not reserve or consume RAM.

## Migration and recovery

1. Keep the old Metabase app stopped. Export its Postgres application database
   and verification manifest in the same read-only snapshot. Encrypt the dump
   and original settings, including `MB_ENCRYPTION_SECRET_KEY`, outside Git.
2. Pin the source Metabase image digest. Do not combine the move with a Metabase
   upgrade. Provision the destination app held on a sleep command until restore.
3. Restore into the empty dedicated database in one transaction, stopping on
   errors. Retain `citext`; omit Fly's `pg_stat_monitor`, `pgaudit` and PgBouncer
   helper objects. Verify all table fingerprints and sequence states before app
   startup, then analyze the restored database.
4. Preserve the original encryption key; replace only application-database
   connection credentials with the new destination values. Keep scheduled jobs
   disabled during validation so subscriptions/alerts do not fire prematurely.
5. Update the existing Noah reporting connection in place, retaining its database
   ID so saved questions keep their references. Use the HA endpoint with the
   dedicated read-only login, five connections and a query timeout. Keep this
   credential distinct from both application database owners.
6. Verify login, dashboards, saved questions, representative query results,
   `/api/health`, memory limits and a recovery copy before changing the hostname.
   Configure `MB_SITE_URL` for the agreed hostname and enable scheduled jobs once
   the reporting connection is verified.
7. Keep Fly stopped until acceptance. After acceptance, archive recovery evidence,
   delete only Metabase's old app/database and remove its remaining Fly files.
   New dashboard edits on Railway require reconciliation before any data rollback.

The original `MB_ENCRYPTION_SECRET_KEY` is required to read stored credentials.
Never regenerate it during migration, put it in a plan/repository, or log API
sessions, decrypted database details or backups. The old `*.fly.dev` address
cannot be transferred to Railway; the new hostname must be configured explicitly.

## Preparation evidence

The 2026-10-29 22:39:59 UTC source snapshot contains 150 public tables, two
dashboards, 51 saved questions and three users. The source is Postgres 16.15,
about 145 MiB; the encrypted archive and original settings were verified and
retained privately. The dump took 4.68 seconds. The native provision plan created
exactly two services and one volume; existing service configuration was unchanged.

The single-transaction restore completed in 85.94 seconds over a private SSH
tunnel. All 150 table fingerprints and 120 sequence states matched; indexes and
constraints were valid. `citext` and `plpgsql` were retained. Daily backups were
verified through API readback and a named restored-volume snapshot was requested.
All ten pre-existing production deployment IDs were unchanged. The approved
hostname is `metabase.noahwallet.io`; its previous A record is retained privately
for recovery until cutover. Administrator login and all 12 saved Noah queries are verified; public HTTPS
is verified against the Railway edge; browser acceptance and Fly retirement
remain pending.

The original 1 GiB application limit passed startup and all 12 saved Noah
queries, but anonymous memory reached about 988 MiB. The deployed limit is
therefore 1.5 GiB with the same 512 MiB Java heap, leaving room for native JVM
memory. Reporting database ID 2 was updated in place; all 12 queries completed
with the new read-only role and returned the expected 984-user total.

DNS now routes `metabase.noahwallet.io` to `9wwolztt.up.railway.app`, with a
valid certificate and propagated provider DNS status. Cached clients may still
resolve the previous address until its TTL expires. The final native IaC plan
reports no changes. The app and its database are serving successful deployments;
all ten existing wallet-service deployment IDs are unchanged.
