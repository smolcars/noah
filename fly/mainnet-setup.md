# Mainnet Fly setup

`fly/mainnet.fly.toml` is the Fly app config for the Noah mainnet server.

There is intentionally no `fly/mainnet.postgres.toml`.
Fly Managed Postgres is not deployed as a second app config like the legacy Signet Postgres machine. It is created and attached separately.

Metabase for mainnet is configured separately in `fly/mainnet.metabase.fly.toml`.
See `fly/mainnet-metabase-setup.md` for the Metabase app database and read-only
reporting role setup.

## One-time setup

Create the mainnet app:

```sh
fly apps create noah-mainnet
```

Create the managed Postgres cluster in the same region:

```sh
fly mpg create \
  --name noah-mainnet-db \
  --region iad \
  --plan production \
  --volume-size 10
```

Attach the cluster to the app and store the connection string in `POSTGRES_URL`:

```sh
fly mpg attach <cluster-id> \
  --config fly/mainnet.fly.toml \
  --database noah \
  --username postgres \
  --variable-name POSTGRES_URL
```

Set the other required runtime secrets on the Fly app:

```sh
fly secrets set -a noah-mainnet \
  REDIS_URL=... \
  EXPO_ACCESS_TOKEN=... \
  ARK_SERVER_URL=... \
  S3_BUCKET_NAME=... \
  AUTH_JWT_SECRET=...
```

You will likely also want to set:

```sh
fly secrets set -a noah-mainnet \
  AWS_ACCESS_KEY_ID=... \
  AWS_SECRET_ACCESS_KEY=... \
  AWS_REGION=... \
  SENTRY_URL=... \
  NTFY_AUTH_TOKEN=...
```

After the one-time setup, mainnet deploys can run through the manual GitHub Actions workflow.

The workflow expects a `FLY_API_TOKEN` GitHub secret. Because the workflow targets the `production` environment, an environment-scoped secret is a good fit.
