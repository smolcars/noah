# Barkd Fly setup

Noah uses one private barkd app and wallet per Bitcoin network. Barkd owns its SQLite wallet state
in `/data/barkd` on a Fly Volume; the Noah server remains stateless and calls barkd over Fly's
private 6PN network. The subdirectory keeps the filesystem's `lost+found` directory outside Barkd's
datadir, which must not contain unexpected files when the wallet is created.

The barkd apps intentionally have no `http_service`, `services`, Flycast address, or public IP.
They must remain in the same Fly organization/private network as their corresponding Noah apps.

## Files

- `fly/barkd.Dockerfile`: pinned barkd runtime image
- `fly/signet.barkd.fly.toml`: `noah-barkd-signet`
- `fly/mainnet.barkd.fly.toml`: `noah-barkd-mainnet`
- `.github/workflows/barkd-build-push.yml`: manual multi-architecture image publication
- `.github/workflows/barkd-signet-deploy.yml`: manual signet deployment
- `.github/workflows/barkd-mainnet-deploy.yml`: manual production-approved mainnet deployment

## Build the image locally

Second publishes barkd binaries for Linux x86-64 and ARM64. The Dockerfile selects the matching
asset through BuildKit's `TARGETARCH` and verifies a pinned SHA-256 for each architecture.

```sh
docker build \
  --file fly/barkd.Dockerfile \
  --tag noah-barkd:0.6.2 \
  .

docker run --rm noah-barkd:0.6.2 barkd --version
```

The manual build workflow builds natively on amd64 and arm64 runners, then publishes shared
`0.6.2` and `latest` manifests to `niteshbalusu/noah-barkd` on Docker Hub and
`ghcr.io/smolcars/noah-barkd` on GHCR:

```sh
gh workflow run barkd-build-push.yml --ref master
```

The workflow uses the existing `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` repository secrets.
The Fly configs deliberately use the immutable `0.6.2` tag rather than `latest`, so signet and
mainnet promote the same image version.

## Provision signet

Verify these commands against the installed `flyctl` before running them.

```sh
fly apps create noah-barkd-signet

fly volumes create bark_data \
  --app noah-barkd-signet \
  --region iad \
  --size 1 \
  --snapshot-retention 30

gh workflow run barkd-signet-deploy.yml --ref master
```

The workflow requires a `FLY_BARKD_SIGNET_API_TOKEN` repository secret with access to only the
signet barkd app. The app and volume are one-time prerequisites; deployment never recreates them.

Verify that there is exactly one Machine and one attached volume, automatic snapshots are enabled,
and no public IP was allocated:

```sh
fly machine list --app noah-barkd-signet
fly volumes list --app noah-barkd-signet
fly ips list --app noah-barkd-signet
```

Release any accidentally allocated public IP before continuing. Do not add an `http_service` or
`services` section to make the API reachable.

## Retrieve the REST token

Barkd generates an auth token in the datadir on first start. Display it in a controlled terminal:

```sh
fly ssh console \
  --app noah-barkd-signet \
  --command "barkd --datadir /data/barkd secret show"
```

Store the value on the Noah app, then discard it from the clipboard and terminal scrollback:

```sh
fly secrets set --app noah-signet \
  BARKD_AUTH_TOKEN=<token> \
  BARKD_URL=http://noah-barkd-signet.internal:3000
```

Leave `BARKD_FORWARDED_INVOICES_ENABLED` unset or `false` until wallet initialization and signet
testing are complete. Enable it separately so token/URL installation cannot begin serving invoices
accidentally:

```sh
fly secrets set --app noah-signet BARKD_FORWARDED_INVOICES_ENABLED=true
```

Do not disable barkd authentication. The private network limits reachability; bearer authentication
still limits which workloads on that network can control the wallet.

## Create the signet wallet

Open a local tunnel to the service-less Machine:

```sh
fly proxy 3001:3000 --app noah-barkd-signet
```

In a second controlled terminal, call the create endpoint once:

```sh
curl --fail-with-body \
  --request POST \
  --header "Authorization: Bearer <token>" \
  --header "Content-Type: application/json" \
  --data-binary @- \
  http://127.0.0.1:3001/api/v1/wallet/create <<'JSON'
{
  "network": "signet",
  "ark_server": "https://ark.signet.2nd.dev",
  "chain_source": {
    "esplora": {
      "url": "https://esplora.signet.2nd.dev"
    }
  }
}
JSON
```

Record the returned wallet fingerprint. Barkd generates the mnemonic inside `/data/barkd`; retrieve
it in a controlled SSH session and store it in the approved offline recovery system:

```sh
fly ssh console \
  --app noah-barkd-signet \
  --command "cat /data/barkd/mnemonic"
```

Do not enable the REST mnemonic endpoint in the normal deployment.

Check the Ark connection through the same tunnel:

```sh
curl --fail-with-body \
  --header "Authorization: Bearer <token>" \
  http://127.0.0.1:3001/api/v1/wallet/ark-info
```

## Provision mainnet

Run the same procedure with these substitutions only after signet testing succeeds:

| Setting | Mainnet value |
| --- | --- |
| Barkd app | `noah-barkd-mainnet` |
| Noah app | `noah-mainnet` |
| Fly config | `fly/mainnet.barkd.fly.toml` |
| Network | `mainnet` |
| Ark server | `https://ark.second.tech` |
| Esplora | `https://mempool.second.tech/api` |
| Noah URL | `http://noah-barkd-mainnet.internal:3000` |

Never reuse the signet volume, mnemonic, REST token, or wallet fingerprint on mainnet.

Deploy mainnet manually after publishing and testing the pinned image on signet:

```sh
gh workflow run barkd-mainnet-deploy.yml --ref master
```

The workflow uses the GitHub `production` environment for approval and requires a
`FLY_BARKD_MAINNET_API_TOKEN` secret with access to only the mainnet barkd app.

## Snapshots and restore

Fly takes automatic daily snapshots. Both configs request 30-day retention. Create an on-demand
snapshot before a barkd upgrade or risky operational change:

```sh
fly volumes list --app <barkd-app>
fly volumes snapshots create <volume-id>
fly volumes snapshots list <volume-id>
```

Restore a selected snapshot into a new volume of equal or greater size:

```sh
fly volumes create bark_data_restored \
  --app <barkd-app> \
  --region iad \
  --size 1 \
  --snapshot-id <snapshot-id> \
  --snapshot-retention 30
```

Stop the original Machine before attaching and starting a replacement. Never run two barkd
processes against copies intended to represent the same live wallet. After restoration, verify the
recorded fingerprint and pending receive statuses before re-enabling invoice creation.

Daily snapshots can miss changes made since the last snapshot. The independently stored mnemonic
is the recovery backstop for wallet balance, but it does not restore recent history or every
in-progress action.

## Upgrade and rollback

1. Disable new forwarded invoices in Noah.
2. Leave barkd running until existing paid receives settle.
3. Create and verify an on-demand volume snapshot.
4. Update the pinned barkd version and both architecture checksums in `fly/barkd.Dockerfile`, the
   image tags in the Fly and Compose configs, and `BARK_VERSION` in the build workflow.
5. Dispatch the image build workflow and deploy the versioned image to signet.
6. Test signet, then dispatch the production-approved mainnet deployment.
7. Verify the wallet fingerprint, Ark connection, and a complete signet receive.
8. Re-enable forwarded invoices.

Do not blindly roll back the binary after an upgrade has migrated SQLite. Check Bark's release notes
and restore the pre-upgrade snapshot when the old binary cannot read the migrated database.
