#!/bin/sh
set -eu

command -v xcodegen >/dev/null 2>&1 || brew install xcodegen
cd "$CI_PRIMARY_REPOSITORY_PATH/apps/ios"
xcodegen generate
