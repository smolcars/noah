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
