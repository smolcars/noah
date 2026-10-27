# Railway signet

Manage the signet destination through the Railway CLI and [railway.ts](railway.ts).
This uses Railway's current [Infrastructure as Code](https://docs.railway.com/infrastructure-as-code)
format. Credentials stay in Railway or the local authenticated CLI, outside this repository.

The migration project is `noah` (`8847a6a1-bf07-4321-a62b-9839e9954e46`) in
workspace `Noah`. The environment is `signet` (`37880684-30ae-4242-af12-ddba1ba620ad`).
All services use Virginia (the live API's canonical region ID, `iad`).

```sh
bun install --frozen-lockfile
bunx @railway/cli login
bunx @railway/cli link --project 8847a6a1-bf07-4321-a62b-9839e9954e46 --environment signet
railway config plan --environment signet
railway config apply --environment signet
```

The active configuration runs Postgres 18, persistent Redis with AOF and
`noeviction`, the API, and one Barkd 0.8.0 instance with its restored `/data`
volume. API and Barkd images are pinned by digest with automatic image updates
disabled. The API checks `/health`; Barkd has a 30-second shutdown grace period.
Daily, weekly, and monthly volume backups are enabled. The
[migration runbook](../docs/railway_signet_migration_plan.md) records the verified
imports, recovery copies, and acceptance checks.

The user approved execution on 2026-10-27. The Fly API and Barkd are fenced with
sleep commands and stopped; their historical stores are retained for recovery.
Never start those old wallet files alongside the active Railway wallet. Do not
reapply an older preparation config that replaces Barkd with the importer image.

`SIGNET_DEPLOY_TARGET=railway` is set in GitHub to skip both Fly signet deploy
jobs. Mainnet remains on Fly. Railway GitHub autodeploy is not connected:
**Railway releases are explicit image-pin updates.** To release the API, wait for
the server workflow to publish `sha-<commit>`, resolve its image digest, update
the API image in `railway.ts`, review the plan, and apply it. Keep Barkd's version
and volume unchanged during an API release.

Existing application secrets are installed privately and declared with
`preserve()`. Declare any new manually installed setting before the next apply:
an omitted existing variable is deleted by IaC.

Use the native Railway CLI for IaC commands; the SDK's version detection can
misidentify the Bun launcher when invoking `bunx @railway/cli config ...`.
The current IaC engine can preserve an existing custom domain but cannot register
a new one. The signet domain is already registered. For a fresh project only:

```sh
railway domain signet.noahwallet.io --port 3000 --service api --environment signet
```

The production hostname is `signet.noahwallet.io`; the Railway diagnostic hostname
is `api-signet.up.railway.app`. The production CNAME target is
`s8qd7ioc.up.railway.app`. Verify TLS on the production hostname after DNS changes.
