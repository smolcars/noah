# Mainnet Metabase Fly setup

`fly/mainnet.metabase.fly.toml` is retained until Metabase's separate migration.
It is the only remaining Fly deployment config in this repository.

## Retained state after wallet cutover

As of **2026-10-29**, `noah-mainnet-metabase` is stopped with an inert sleep
command. Its separate application database, `noah-mainnet-metabase-db`
(`kyzl60xpw9xopj9g`), is preserved and ready. The old Noah production database
was deleted; its reporting hostname/IP must not be used again.

Keep the existing application database credentials and `MB_ENCRYPTION_SECRET_KEY`.
The encryption key is required to read Metabase's stored connection secrets;
do not replace it with a newly generated value during migration.

Metabase needs two Postgres connections:

- an application database, where Metabase stores its own users, dashboards,
  saved questions, permissions, and sync metadata
- a read-only reporting connection to the authoritative Noah mainnet database

The wallet services now run on Railway; see the
[mainnet operations notes](../.railway/mainnet/README.md). Preserve Metabase's
application database and encryption key when moving it, and reconnect reporting
using a dedicated read-only Railway database role before resuming analytics. Railway
private DNS cannot be reached directly from Fly; connectivity must be arranged
if Metabase resumes there before its migration. Do not restart it against the
retired data source.

## Historical one-time setup

These commands document the original bootstrap. The app and application database
already exist; do not rerun this setup over the retained resources. The normal
deployment command would also replace the deliberate sleep command.

The original setup used a separate Fly Managed Postgres cluster for Metabase's
application database and a cluster-wide `reader` role for reporting. Recreate
read-only reporting privileges on Railway during migration.

Create the Metabase Fly app:

```sh
fly apps create noah-mainnet-metabase
```

Create a separate Managed Postgres cluster for Metabase's own application state:

```sh
fly mpg create \
  --name noah-mainnet-metabase-db \
  --region iad \
  --plan Basic \
  --volume-size 10 \
  --pg-major-version 16
```

List the new cluster and save its ID:

```sh
fly mpg list personal
```

Attach the new Metabase database cluster to the Metabase app. This stores the
Metabase application database connection string as the `MB_DB_CONNECTION_URI`
secret:

```sh
fly mpg attach <metabase-cluster-id> \
  --config fly/mainnet.metabase.fly.toml \
  --database fly-db \
  --username fly-user \
  --variable-name MB_DB_CONNECTION_URI
```

`fly mpg attach` stores a normal Postgres URL. Metabase expects a JDBC URL for
`MB_DB_CONNECTION_URI`, so replace that secret with a JDBC URL after attaching.
Use the Metabase database cluster's Direct IP from `fly mpg status`:

```sh
fly secrets set -a noah-mainnet-metabase \
  MB_DB_CONNECTION_URI='jdbc:postgresql://[<metabase-db-direct-ip>]:5432/fly-db?sslmode=disable' \
  MB_DB_USER=fly-user \
  MB_DB_PASS='<fly-user password from the attach URL>' \
  MB_ENCRYPTION_SECRET_KEY='<random 32+ character secret>'
```

Deploy Metabase:

```sh
fly deploy --config fly/mainnet.metabase.fly.toml --remote-only
```

The first boot can be slow because Metabase creates and migrates its application
database before `/api/health` returns healthy. The Fly config uses a longer
deploy wait for that first start.

The original reporting connection is retired. Configure the new Railway data
source during the separate Metabase migration, then verify existing questions,
dashboards and read-only access before resuming analytics.
