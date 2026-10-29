# Mainnet preparation

The [migration runbook](../../docs/railway_mainnet_migration_plan.md) requires
explicit cutover approval. These files do not create a production environment,
copy data, or change GitHub variables on merge. Signet's configuration is separate.

## One-time bootstrap

[railway.ts](railway.ts) is only for a **fresh** `production` environment in the
existing Noah project. Set `RAILWAY_MAINNET_ENVIRONMENT_ID` to its actual UUID and
`RAILWAY_MAINNET_POSTGRES_MAJOR` to the chosen, rehearsed major (16, 17 or 18).
The file rejects missing/mismatched IDs and signet. After provisioning approval,
link the native CLI to project `8847a6a1-bf07-4321-a62b-9839e9954e46` and that
environment, then preview:

```sh
railway config plan --file .railway/mainnet/railway.ts --environment "$RAILWAY_MAINNET_ENVIRONMENT_ID"
```

Review the plan before any apply. The initial graph has Postgres, persistent
Redis with AOF and `noeviction`, a 1 GB wallet volume, and two Debian tools services.
Both API and Barkd run `/bin/sleep infinity`; neither application or wallet can
start. There is no public domain or API healthcheck at this stage. Inventory disk
requirements and adjust capacity before applying or importing. Transfer existing
production secrets privately; `preserve()` keeps installed values but does not
import secrets from Fly. Review additional variables before every apply.
Service/volume names (`api-mainnet`, `postgres-mainnet`, `redis-mainnet`,
`bark_data_mainnet`, `noah-barkd-mainnet`) are distinct from signet so the bootstrap
does not bind to existing signet resource IDs by name.

This is a **bootstrap, not an HA or active-runtime configuration**. Enable the
chosen HA/PITR and backup schedules separately through Railway's supported
CLI/API flows, test them, and never reapply this graph over a converted HA cluster
or activated wallet. Doing so could remove cluster resources or stop applications.
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
