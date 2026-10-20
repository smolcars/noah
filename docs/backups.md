# Backup API

The Noah server API remains under `/v0`. The `v2` segment below identifies the backup
object format and workflow; it is not a new version of the entire HTTP API.

## Current backup endpoints

New clients must use the verified snapshot workflow:

- `POST /v0/backup/v2/upload`
- `POST /v0/backup/v2/complete`
- `POST /v0/backup/v2/list`
- `POST /v0/backup/v2/download`
- `POST /v0/backup/v2/delete`

`POST /v0/backup/settings` remains the shared endpoint for enabling or disabling backups.

## Deprecated legacy endpoints

The following whole-wallet-directory backup endpoints are deprecated:

- `POST /v0/backup/upload_url`
- `POST /v0/backup/complete_upload`
- `POST /v0/backup/list`
- `POST /v0/backup/download_url`
- `POST /v0/backup/delete`

They remain available only for compatibility with older clients and for restoring existing
legacy backups. Do not use them for new client development. There is currently no fixed
removal release; removal requires confirming that supported clients use backup v2 and that
the legacy restore window has ended.

Removal is tracked in [GitHub issue #264](https://github.com/smolcars/noah/issues/264).

## Local inspection tool

`scripts/decrypt_backup.js` accepts raw binary or base64 v1/v2 envelopes. Its
existing three arguments still write a **plaintext ZIP** to the output file:

```sh
bun scripts/decrypt_backup.js encrypted.noahbackup decrypted.zip "<recovery phrase>"
```

For v2, add `--extract <new-directory>` to also extract exactly `manifest.json`
and `db.sqlite`. Validation finishes before extraction creates the directory;
an existing directory is refused. Required manifest fields/types/version, ZIP
entry names/types/duplicates, per-entry CRC, inflation limits (64 KiB manifest,
1 GiB database) and the manifest's snapshot SHA-256 are checked. Stored/deflated,
single-disk, non-ZIP64 v2 archives are supported; unknown manifest fields are
allowed, matching the apps. This is an inspection tool: it does not run Bark's
schema/identity validation or install a wallet. V1 keeps its existing ZIP output;
the optional extraction flag is v2-only.

Diagnostics print paths, versions and lengths, never the recovery phrase,
derived AES key or plaintext. The ZIP and extracted database intentionally
contain decrypted wallet data; keep and delete them accordingly. New output
files use mode 0600 and extraction directories 0700. The existing command-line
phrase interface remains visible to shell history/process listings. No secrets
are required to run `bun test scripts/decrypt_backup.test.js` or the equivalent
`node --test` command (Node 22.2+); all fixtures contain public synthetic data.
