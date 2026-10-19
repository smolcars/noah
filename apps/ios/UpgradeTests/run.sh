#!/usr/bin/env bash
# Upgrade test: React Native regtest build -> native regtest build, same
# simulator, no uninstall in between. Requires the regtest stack (`just up`).
#
#   apps/ios/UpgradeTests/run.sh <simulator-udid> <path/to/RN/Noah-Regtest.app>
#
# The simulator's Noah regtest app and keychain are wiped first; never point
# this at a simulator whose data you care about.
set -euo pipefail

SIM="$1"
RN_APP="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
IOS="$(dirname "$HERE")"
BUNDLE=com.noahwallet.regtest
DD="$IOS/build/DerivedData"

xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl uninstall "$SIM" "$BUNDLE" 2>/dev/null || true
xcrun simctl install "$SIM" "$RN_APP"
maestro --device "$SIM" test "$HERE/rn-create-wallet.yml"

container="$(xcrun simctl get_app_container "$SIM" "$BUNDLE" data)"
test -f "$container/Documents/noah-data-regtest/db.sqlite" || { echo "RN wallet DB missing"; exit 1; }
echo "RN install ready: wallet DB present, MMKV files: $(ls "$container/Documents/mmkv" 2>/dev/null | tr '\n' ' ')"

cd "$IOS"
xcodegen generate >/dev/null
# `test` installs the native build over the RN app (data container and
# keychain are kept) and runs the in-sandbox checks.
TEST_RUNNER_NOAH_UPGRADE_CHECK=1 xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "id=$SIM" -derivedDataPath "$DD" \
  -only-testing:NoahTests/UpgradeCompatibilityTests test

xcrun simctl launch --terminate-running-process "$SIM" "$BUNDLE" >/dev/null
sleep 3
xcrun simctl io "$SIM" screenshot "${UPGRADE_SCREENSHOT:-/tmp/noah-upgrade.png}" >/dev/null
echo "upgrade check passed"
