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
volume. The API is built from uploaded source using the root Dockerfile; Barkd's
dedicated [Dockerfile](barkd/Dockerfile) pins its existing image by digest. The API checks
`/health`; Barkd has a 30-second shutdown grace period.
Daily, weekly, and monthly volume backups are enabled. The
[migration runbook](../docs/railway_signet_migration_plan.md) records the verified
imports, recovery copies, and acceptance checks.

The user approved execution on 2026-10-27. The Fly API and Barkd are fenced with
sleep commands and stopped; their historical stores are retained for recovery.
Never start those old wallet files alongside the active Railway wallet. Do not
reapply an older preparation config that replaces Barkd with the importer image.

## Releases

Backend changes merged to `master` run Server CI and publish the multiarch server
image. The API release waits for both, resolves the digest of that commit's
`sha-<commit>` image, and uploads a temporary directory containing only:

```dockerfile
FROM niteshbalusu/noah-server@sha256:<published-digest>
```

The native CLI deploys this directory with `railway up --path-as-root --ci`.
Railway imports the existing image; it does not upload the Rust source or compile
the server again. The single `FROM` inherits the image's entrypoint and command.
CI waits for deployment success, then checks public `/health`. Service-source
changes are handled separately from CI deployments. IaC leaves the API's
image/repository source unset, preventing later
configuration applies from restoring an old image. No infrastructure graph is
applied during an application release.

Both deployment workflows use GitHub secret `RAILWAY_SIGNET_TOKEN`, passed to the CLI as
`RAILWAY_TOKEN`, scoped to only this project's signet environment. The CLI is
pinned to 5.71.0. Set that secret before merging the deployment workflow; without
it, the deployment job fails before changing Railway. Releases are serialized,
and builds superseded by a newer signet build are skipped. Client-only and
documentation changes do not release the API. The image built in GitHub includes
the server and its embedded migrations; the Railway upload contains only the
wrapper Dockerfile.

Existing image publication also serves mainnet and manual image consumers.
`v*` tags keep their existing mainnet Fly deployment and do not deploy
signet. The Fly signet API job is removed and the manual Barkd workflow now deploys
to Railway. Keep
`SIGNET_DEPLOY_TARGET=railway` as a fence for historical workflow versions.

**Barkd upgrades remain manual:** build and review the new image, update its digest
in [barkd/Dockerfile](barkd/Dockerfile), and merge the reviewed change. Take a fresh
wallet backup and check wallet-format compatibility, then dispatch the workflow
from the CLI:

```sh
gh workflow run barkd-signet-deploy.yml --repo smolcars/noah --ref master
```

This runs `railway up .railway/barkd --path-as-root` against the existing Barkd
service, using a build context containing only its Dockerfile. The single `FROM`
instruction inherits the pinned image's entrypoint and command. The workflow
shares the API's deployment concurrency group and preserves Barkd's single
replica, `/data` volume, authentication, and shutdown grace period. Verify wallet
identity, balance, and a Lightning receive afterward; deployment success alone
does not prove wallet-format compatibility. Changes to the Barkd Dockerfile do
not trigger API deployments, and neither API merges nor image publication deploy
Barkd automatically. Normal API releases never restart Barkd or modify Postgres,
Redis, or volumes.

For an API rollback, resolve a known-good commit's published image digest, create
the same single-`FROM` Dockerfile, and upload its directory with `railway up
--path-as-root --project 8847a6a1-bf07-4321-a62b-9839e9954e46 --environment signet
--service api --ci`. This imports the known-good image; it does not undo database
migrations. The API keeps running its existing SQLx migrations at startup, so
automatic releases require compatible schema changes.

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
