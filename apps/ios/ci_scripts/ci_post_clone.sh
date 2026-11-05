#!/bin/sh
set -eu

command -v xcodegen >/dev/null 2>&1 || brew install xcodegen
cd "$CI_PRIMARY_REPOSITORY_PATH/apps/ios"
xcodegen generate
# Xcode Cloud uploads symbols in ci_post_xcodebuild.sh, so turn off the build-phase upload.
echo 'NOAH_SENTRY_UPLOAD_IN_BUILD = NO' >> Config/Sentry.local.xcconfig
