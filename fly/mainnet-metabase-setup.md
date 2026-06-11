# Mainnet Metabase Fly setup

`fly/mainnet.metabase.fly.toml` is the Fly app config for the Noah mainnet
Metabase instance.

Metabase needs two Postgres connections:

- an application database, where Metabase stores its own users, dashboards,
  saved questions, permissions, and sync metadata
- a read-only reporting connection to the existing Noah mainnet database

The current Noah mainnet Managed Postgres cluster is:

```text
Cluster ID: 3x9jv02ywl6r6qp7
Cluster name: noah-mainnet-db
Database: fly-db
Schema admin user: fly-user
Region: iad
Direct IP: fdaa:38:716f:0:1::4
```

Fly Managed Postgres roles are cluster-wide. A `schema_admin` user can read and
write across the cluster, so the safest setup is to use a separate small Managed
Postgres cluster for Metabase's application database and use a `reader` user on
`noah-mainnet-db` only for reporting.

## One-time setup

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

Create a read-only reporting user on the existing Noah mainnet cluster:

```sh
fly mpg users create 3x9jv02ywl6r6qp7 \
  --username metabase-readonly \
  --role reader
```

Deploy Metabase:

```sh
fly deploy --config fly/mainnet.metabase.fly.toml --remote-only
```

The first boot can be slow because Metabase creates and migrates its application
database before `/api/health` returns healthy. The Fly config uses a longer
deploy wait for that first start.

After the first deploy, open Metabase and add the Noah mainnet database as a
data source:

```text
Database type: PostgreSQL
Host: fdaa:38:716f:0:1::4
Port: 5432
Database name: fly-db
User: metabase-readonly
Password: <metabase-readonly password from Fly>
Use a secure connection: disabled
```

This keeps Metabase's own state outside the production Noah cluster while
limiting reporting access to read-only queries against the production `fly-db`
database.
