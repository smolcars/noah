#!/bin/sh
set -eu

# Same archive-only gates as client/plugins/withNoahIosPrebuildFix.js.
case "${CONFIGURATION:-}" in
    Release-Mainnet|Release-Signet) ;;
    *) exit 0 ;;
esac
[ "${PLATFORM_NAME:-}" = iphoneos ] || exit 0
[ "${ACTION:-}" = install ] || exit 0

: "${SENTRY_AUTH_TOKEN:?Provide SENTRY_AUTH_TOKEN to upload archive symbols}"
: "${SENTRY_ORG:?Provide the RN Sentry organization}"
: "${SENTRY_PROJECT:?Provide the RN Sentry project}"
: "${DWARF_DSYM_FOLDER_PATH:?Missing archive dSYM directory}"

SENTRY_CLI_EXECUTABLE="${SENTRY_CLI_EXECUTABLE:-$(command -v sentry-cli || true)}"
[ -x "$SENTRY_CLI_EXECUTABLE" ] || { echo 'error: Install sentry-cli for archive symbol upload' >&2; exit 1; }
# No source bundles, logs or wallet data. Upload all app/extension dSYMs in the build folder.
"$SENTRY_CLI_EXECUTABLE" debug-files upload --wait "$DWARF_DSYM_FOLDER_PATH"
