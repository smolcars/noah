# Railway signet

Manage the signet destination through the Railway CLI and [railway.ts](railway.ts).
This uses Railway's current [Infrastructure as Code](https://docs.railway.com/infrastructure-as-code)
format. Credentials stay in Railway or the local authenticated CLI, outside this repository.

The migration project is `noah` (`8847a6a1-bf07-4321-a62b-9839e9954e46`) in
workspace `Noah`. The environment is `signet` (`37880684-30ae-4242-af12-ddba1ba620ad`).
All services use Virginia (the live API's canonical region ID, `iad`).

```sh
bun install --frozen-lockfile
npm install --global @railway/cli@5.71.0
railway login
railway link --project 8847a6a1-bf07-4321-a62b-9839e9954e46 --environment signet

# Supply both current image pins before an infrastructure plan/apply.
services=$(railway service list --project 8847a6a1-bf07-4321-a62b-9839e9954e46 --environment signet --json)
export SIGNET_API_IMAGE=$(printf '%s' "$services" | jq -er '.[] | select(.name == "api") | .source.image')
export SIGNET_BARKD_IMAGE=$(printf '%s' "$services" | jq -er '.[] | select(.name == "noah-barkd-signet") | .source.image')
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

The API automatically releases on relevant `master` pushes after same-commit
Server CI and multiarch image publication succeed. CI resolves that commit's
Docker Hub digest and passes it to `railway.ts` through `SIGNET_API_IMAGE`.
`SIGNET_BARKD_IMAGE` comes from Railway's current configured pin, so an API release
preserves the wallet version. Both inputs are mandatory digest references; no
stale image default is committed in the configuration. Railway GitHub autodeploy
and image auto updates remain disconnected/disabled. Mainnet still deploys to Fly
on version tags; neither signet CI job deploys to Fly.

Barkd is released **manually**, with a reviewed immutable image reference:

```sh
gh workflow run barkd-signet-deploy.yml --repo smolcars/noah --ref master \
  -f image='niteshbalusu/noah-barkd@sha256:<reviewed-digest>'
```

Before dispatch, confirm a recent wallet-volume backup and compatibility with
the current wallet format. The job preserves the API's current image pin and
keeps the existing `/data` volume, singleton, and shutdown grace period. After
release, verify a small Lightning receive settles in the app. Build/publish the
Barkd image separately; publishing alone never releases it.

Both jobs call [release.mjs](release.mjs), which reads current pins with the
native CLI, runs `railway config plan --out`, validates that the **only change is
the selected service's image**, and applies that exact saved plan. The first
apply can also record Railway's ownership metadata for the existing five
resources. Changed mounts, variables, replicas, database settings, resource lists,
or other source settings fail the release. Such infrastructure changes need a
separate reviewed plan/apply before retrying CI. Saved plans reject environment
drift; no `--confirm-destructive` flag is used. Plans stay in a temporary private
directory and are removed on exit. API and Barkd jobs share one concurrency group.
Older superseded API builds are skipped.

The helper checks apply results, waits up to ten minutes for a new successful
deployment, and the workflow checks public `/health`. This does not establish
Barkd payment readiness; the receive check remains necessary. A failed release
leaves the current state for inspection and fails CI; it does not roll back
wallet data or application migrations automatically.

For a code-only API rollback, resolve a known-good image digest and run
`node .railway/release.mjs api '<full-image-reference>'` with an environment-scoped
`RAILWAY_TOKEN`. Confirm database compatibility first. Never downgrade Barkd
without checking wallet-format compatibility. Local manual releases must not
overlap CI releases; stale-plan checks reject configuration drift but cannot
serialize an independently started deployment.

The encrypted GitHub secret `RAILWAY_SIGNET_TOKEN` exists. Its scope and a live
CI release still need verification. It must be a project token for this exact
signet environment. Local checks: `node --test .railway/release.test.mjs`.

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
