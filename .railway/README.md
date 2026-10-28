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

## Releases

Backend changes merged to `master` run Server CI and publish a multi-architecture
`sha-<commit>` image. The server workflow then deploys that exact digest to the
existing signet API, waits for its deployment to succeed, and checks public
`/health`. Deployments share one concurrency group with manual Barkd releases.
Older builds finishing after a newer signet build are skipped. Client-only and
documentation changes do not release the API. `v*` tags keep their existing
mainnet Fly deployment; they do not deploy signet.

Both workflows use GitHub secret `RAILWAY_SIGNET_TOKEN`, passed to the CLI as
`RAILWAY_TOKEN`. Create it with access to only this project's signet environment;
do not use an account token. The CLI is pinned to 5.71.0. No Railway GitHub source
or image auto-update is connected, so GitHub Actions is the only automatic
release path. The workflows contain no Fly signet jobs. Keep the legacy
`SIGNET_DEPLOY_TARGET=railway` repository variable as a fence for older workflows.

Infrastructure configuration reads the live API and Barkd image pins using the
authenticated `railway` executable on `PATH`. Missing or unexpected image sources
fail closed. Infrastructure applies therefore preserve the image selected by CI,
rather than resetting it to a digest committed before a newer release. Review
infrastructure plans separately; application releases never apply the graph or
change databases, variables, volumes, replicas, or DNS.

For a **Barkd upgrade**, build and review the image separately, then dispatch
`barkd-signet-deploy.yml` on `master` with its `sha256:...` digest. It updates only
the existing Barkd service's image; Railway keeps the single mounted `/data`
volume and shutdown grace period. Take a fresh wallet backup before upgrading,
check compatibility with the existing wallet, and verify a Lightning receive
afterward. Container success and the public API healthcheck do not establish
wallet compatibility. A normal API release never restarts Barkd.

The Actions summary records the previous image, new image, commit (API), and
deployment ID. For an intentional image rollback, use the recorded previous
digest with the same helper, outside the automatic build workflow:

```sh
python3 .railway/deploy.py api 'niteshbalusu/noah-server@sha256:<previous-digest>'
```

The helper requires Python 3.11+ and an authenticated Railway CLI. It does not
roll back database migrations or wallet formats. The API runs its existing SQLx
migrations at startup; use compatible migrations for automatic releases. A
failed deployment fails CI, with no automatic data restoration or image rollback.

Local release checks (no deployments):

```sh
python3 -m unittest discover -s .railway -p 'test_*.py' -v
actionlint .github/workflows/server-push.yml .github/workflows/server.yml \
  .github/workflows/barkd-signet-deploy.yml .github/workflows/railway-checks.yml
railway config plan --environment signet
```

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
