#!/bin/sh
set -eu

# Only a successful archive has dSYMs to upload.
[ -n "${CI_ARCHIVE_PATH:-}" ] || exit 0
[ "${CI_XCODEBUILD_EXIT_CODE:-1}" = 0 ] || exit 0

# Set in both Xcode Cloud workflows' environment; the token as a secret.
: "${SENTRY_AUTH_TOKEN:?Provide SENTRY_AUTH_TOKEN to upload archive symbols}"
: "${SENTRY_ORG:?Provide the RN Sentry organization}"
: "${SENTRY_PROJECT:?Provide the RN Sentry project}"

# Pinned and checksum-checked: it runs with the Sentry token before TestFlight distribution.
SENTRY_CLI_VERSION=3.8.2
SENTRY_CLI_SHA256=2d1b118fa46960ba5e8e0cca9a6e197366905776a0ddc5a89c7d05a3908b77db
cli="${TMPDIR:-/tmp}/sentry-cli-$SENTRY_CLI_VERSION"
curl -fsSL -o "$cli" "https://github.com/getsentry/sentry-cli/releases/download/$SENTRY_CLI_VERSION/sentry-cli-Darwin-universal"
echo "$SENTRY_CLI_SHA256  $cli" | shasum -a 256 -c -
chmod +x "$cli"

# No source bundles, logs or wallet data: only the app and widget dSYMs in the archive.
"$cli" debug-files upload --wait "$CI_ARCHIVE_PATH/dSYMs"
