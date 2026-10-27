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

The preparation configuration provisions Postgres 18, Redis, a server-image
staging service, and a tools-only importer with Barkd's `/data` volume. **Both
application services run only `sleep`; the API and Barkd wallet stay off.**
Keep the source Fly wallet active until the coordinated
maintenance window in the [migration runbook](../docs/railway_signet_migration_plan.md).

After importing and verifying the final datasets, replace the tools image with
the recorded Barkd 0.8.0 digest and remove its `start` override. Deploy the
server version containing the exact Railway hostname allowlist. After explicit
cutover approval and final data restore, remove the API's sleep override and set
its healthcheck to `/health`. Existing signet secrets are preserved privately.
Then run the runbook's acceptance checks.
Do not reapply the importer configuration over an active wallet deployment.

Before the write freeze, set the GitHub repository variable
`SIGNET_DEPLOY_TARGET=railway` to pause both Fly signet deploy jobs. Their default
remains Fly until that variable changes. The mainnet build/deploy job remains
independent of this signet switch.

Use the native Railway CLI for IaC commands; the SDK's version detection can
misidentify the Bun launcher when invoking `bunx @railway/cli config ...`.
The current IaC engine can preserve an existing custom domain but cannot register
a new one. Register it once before planning a fresh project:

```sh
railway domain signet.noahwallet.io --port 3000 --service api --environment signet
```

Apply the returned DNS routing records only after cutover verification.

**Approval boundary:** preparation and rehearsal are authorized. Stopping Fly,
starting the Railway API or Barkd wallet, and switching traffic DNS require the
user's explicit approval. Leave the migration PR unmerged during preparation.
