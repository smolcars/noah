#!/bin/sh
set -eu

# Never auto-upload a developer's build. Eligible CI releases fail closed before invoking the CLI.
case "${CI:-}" in true|1) ;; *) exit 0 ;; esac
if [ -z "${SENTRY_AUTH_TOKEN:-}" ] || [ -z "${SENTRY_ORG:-}" ] || [ -z "${SENTRY_PROJECT:-}" ]; then
    echo 'Sentry mapping upload requires SENTRY_AUTH_TOKEN, SENTRY_ORG and SENTRY_PROJECT in CI.' >&2
    exit 1
fi
sentry_cli=${SENTRY_CLI_EXECUTABLE:-sentry-cli}
if ! command -v "$sentry_cli" >/dev/null 2>&1; then
    echo 'Sentry mapping upload requires sentry-cli in CI.' >&2
    exit 1
fi
mapping_file=$1
metadata_file=$2
if [ ! -s "$mapping_file" ] || [ ! -f "$metadata_file" ]; then
    echo 'Sentry mapping output or metadata is missing.' >&2
    exit 1
fi
mapping_uuid=$(sed -n 's/^io.sentry.ProguardUuids=//p' "$metadata_file")
if ! echo "$mapping_uuid" | LC_ALL=C grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; then
    echo 'Sentry mapping UUID is invalid.' >&2
    exit 1
fi
# Keep credentials and any CLI/server diagnostics out of build logs.
upload_log=$(mktemp)
trap 'rm -f "$upload_log"' EXIT HUP INT TERM
export SENTRY_LOG_LEVEL=warn
if ! "$sentry_cli" upload-proguard --uuid "$mapping_uuid" --require-one "$mapping_file" >"$upload_log" 2>&1; then
    echo 'Sentry mapping upload failed; CI release is blocked.' >&2
    exit 1
fi
echo 'Sentry R8 mapping uploaded.'
