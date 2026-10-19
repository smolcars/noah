#!/usr/bin/env bash
# Upgrade test: React Native regtest build -> native regtest build, same
# simulator, no uninstall in between. Requires the regtest stack (`just up`).
#
#   apps/ios/UpgradeTests/run.sh <simulator-udid> <path/to/RN/Noah-Regtest.app>
#
# FUNDED=1 also receives 100k sats from the regtest bark CLI and sends 5k
# back through the RN Send flow before upgrading, then checks the native
# side sees the same fingerprint, movements and spendable balance.
#
# The simulator's Noah regtest app and keychain are wiped first; never point
# this at a simulator whose data you care about.
set -euo pipefail

SIM="$1"
RN_APP="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
IOS="$(dirname "$HERE")"
REPO="$(cd "$IOS/../.." && pwd)"
FLOWS="$REPO/client/.maestro/subflows"
BUNDLE=com.noahwallet.regtest
DD="$IOS/build/DerivedData"

xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl uninstall "$SIM" "$BUNDLE" 2>/dev/null || true
xcrun simctl install "$SIM" "$RN_APP"
if [[ "${FUNDED:-0}" == 1 ]]; then
  maestro --device "$SIM" test "$FLOWS/prepare-funded-send.yml"
  runtime="$(xcrun simctl getenv "$SIM" SIMULATOR_RUNTIME_VERSION)"
  if [[ "${runtime%%.*}" -ge 27 ]]; then
    xcrun simctl privacy "$SIM" grant pasteboard "$BUNDLE"
  fi
  rn_ark_address="$(xcrun simctl pbpaste "$SIM" | tr -d '\r\n')"
  [[ "$rn_ark_address" =~ ^tark1 ]] || { echo "no RN Ark address on the clipboard"; exit 1; }
  (cd "$REPO" && just bark send --wait "$rn_ark_address" "100000 sats")
  bark_address="$(cd "$REPO" && just bark address 2>&1 | sed -n '/^tark1/p' | tail -n 1)"
  printf '%s' "$bark_address" | xcrun simctl pbcopy "$SIM"
  maestro --device "$SIM" test "$FLOWS/send-funded-ark.yml"
  xcrun simctl terminate "$SIM" "$BUNDLE" 2>/dev/null || true
else
  maestro --device "$SIM" test "$HERE/rn-create-wallet.yml"
fi

container="$(xcrun simctl get_app_container "$SIM" "$BUNDLE" data)"
test -f "$container/Documents/noah-data-regtest/db.sqlite" || { echo "RN wallet DB missing"; exit 1; }
echo "RN install ready: wallet DB present, MMKV files: $(ls "$container/Documents/mmkv" 2>/dev/null | tr '\n' ' ')"

# Ground truth straight from the RN-written database (copied, with any WAL).
snapshot="$(mktemp -d)"
cp "$container/Documents/noah-data-regtest/"db.sqlite* "$snapshot/"
query() { sqlite3 -bail "$snapshot/db.sqlite" "$1"; }
# Assigned separately so a failing query stops the script (export would mask it).
fingerprint="$(query 'select fingerprint from bark_properties')"
movements="$(query 'select count(*) from bark_movements')"
spendable="$(query "select coalesce(sum(amount_sat), 0) from vtxo_view where state_kind = 'Spendable'")"
rm -rf "$snapshot"
[[ -n "$fingerprint" && "$movements" =~ ^[0-9]+$ && "$spendable" =~ ^[0-9]+$ ]] \
  || { echo "could not read expectations from the RN database"; exit 1; }
export TEST_RUNNER_NOAH_UPGRADE_EXPECT_FINGERPRINT="$fingerprint"
export TEST_RUNNER_NOAH_UPGRADE_EXPECT_MOVEMENTS="$movements"
export TEST_RUNNER_NOAH_UPGRADE_EXPECT_SPENDABLE="$spendable"
echo "RN DB: movements=$movements spendable=$spendable"

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
