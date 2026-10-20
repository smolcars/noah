#!/usr/bin/env bash
# Destructive only to the explicitly supplied disposable simulator's regtest
# app. Never uninstall between RN creation and native installation.
# FUNDED=1 adds Ark send/receive, confirmed onchain funds, UI-created recurring
# policy, and non-default settings. See README.md for the physical push gate.
set -euo pipefail
SIM="$1"
RN_APP="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
IOS="$(dirname "$HERE")"
REPO="$(cd "$IOS/../.." && pwd)"
FLOWS="$REPO/client/.maestro/subflows"
BUNDLE=com.noahwallet.regtest
DD="${UPGRADE_DERIVED_DATA:-$(mktemp -d /private/tmp/noah-upgrade-dd.XXXXXX)}"
DEBUG="$(mktemp -d /private/tmp/noah-upgrade-maestro.XXXXXX)"
snapshot="$(mktemp -d /private/tmp/noah-upgrade-db.XXXXXX)"
cleanup() {
  rm -rf "$snapshot" "$DEBUG"
  if [[ -z "${UPGRADE_DERIVED_DATA:-}" ]]; then rm -rf "$DD"; fi
  if [[ -n "${container:-}" ]]; then
    container="$(xcrun simctl get_app_container "$SIM" "$BUNDLE" data 2>/dev/null || printf '%s' "$container")"
    rm -rf "$container/Documents/noah-upgrade-rn-mmkv" "$container/Documents/noah-upgrade-check.json"
  fi
}
trap cleanup EXIT
# Protect the shared machine's established devices, even if passed by mistake.
python3 - "$SIM" <<'PY'
import json, subprocess, sys
all_devices = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', '-j']))['devices']
matched = [d for group in all_devices.values() for d in group if d['udid'] == sys.argv[1]]
assert len(matched) == 1, 'Simulator not found'
assert matched[0]['name'] not in ['Noah Upgrade 030', 'Noah Native iPhone', 'Noah Native iPhone 2', 'Noah Native SE', 'iPhone 18 Pro'], 'Protected shared simulator'
print('Disposable upgrade simulator:', matched[0]['name'], matched[0]['udid'])
PY
xcrun simctl boot "$SIM" 2>/dev/null || true
flow() { maestro --device "$SIM" test --debug-output "$DEBUG" "$@"; }
if [[ "${RN_PREPARED:-0}" != 1 ]]; then
xcrun simctl uninstall "$SIM" "$BUNDLE" 2>/dev/null || true
xcrun simctl install "$SIM" "$RN_APP"
if [[ "${FUNDED:-0}" == 1 ]]; then
  flow "$FLOWS/prepare-funded-send.yml"
  runtime="$(xcrun simctl getenv "$SIM" SIMULATOR_RUNTIME_VERSION)"
  if [[ "${runtime%%.*}" -ge 27 ]]; then xcrun simctl privacy "$SIM" grant pasteboard "$BUNDLE"; fi
  rn_ark_address="$(xcrun simctl pbpaste "$SIM" | tr -d '\r\n')"
  [[ "$rn_ark_address" =~ ^tark1 ]] || { echo "no RN Ark address on clipboard"; exit 1; }
  (cd "$REPO" && just bark send --wait "$rn_ark_address" "100000 sats")
  bark_address="$(cd "$REPO" && just bark address 2>&1 | sed -n '/^tark1/p' | tail -n 1)"
  [[ "$bark_address" =~ ^tark1 ]] || { echo "no Bark destination"; exit 1; }
  printf '%s' "$bark_address" | xcrun simctl pbcopy "$SIM"
  flow "$FLOWS/send-funded-ark.yml"
  flow -e "RECURRING_DESTINATION=$bark_address" "$HERE/rn-settings-and-recurring.yml"
  onchain_address="$(xcrun simctl pbpaste "$SIM" | tr -d '\r\n')"
  [[ "$onchain_address" =~ ^bcrt1 ]] || { echo "no RN onchain address on clipboard"; exit 1; }
  (cd "$REPO" && just bcli sendtoaddress "$onchain_address" 0.0001 && just generate 1)
  flow "$HERE/rn-confirm-onchain.yml"
  flow "$HERE/rn-locale.yml"
else
  flow "$HERE/rn-create-wallet.yml"
fi
else
  installed="$(xcrun simctl get_app_container "$SIM" "$BUNDLE" app)"
  python3 - "$RN_APP" "$installed" <<'PYPREPARED'
import hashlib, pathlib, plistlib, sys
source, installed = map(pathlib.Path, sys.argv[1:])
assert plistlib.loads((source / 'Info.plist').read_bytes())['CFBundleVersion'] == plistlib.loads((installed / 'Info.plist').read_bytes())['CFBundleVersion'], 'Prepared app is not the RN build'
assert hashlib.sha256((source / 'main.jsbundle').read_bytes()).digest() == hashlib.sha256((installed / 'main.jsbundle').read_bytes()).digest(), 'Prepared RN JS bundle differs'
PYPREPARED
fi
if [[ "${FUNDED:-0}" == 1 ]]; then
  if ! python3 "$HERE/check-widget-placement.py" record "$SIM" "$snapshot/widget-state.json"; then
    flow "$HERE/rn-place-widget.yml"
    python3 "$HERE/check-widget-placement.py" record "$SIM" "$snapshot/widget-state.json"
  fi
fi
xcrun simctl terminate "$SIM" "$BUNDLE" 2>/dev/null || true
container="$(xcrun simctl get_app_container "$SIM" "$BUNDLE" data)"
test -f "$container/Documents/noah-data-regtest/db.sqlite"
# Snapshot the real RN MMKV bytes before native startup can update them.
cp -R "$container/Documents/mmkv" "$container/Documents/noah-upgrade-rn-mmkv"
cp "$container/Documents/noah-data-regtest/"db.sqlite* "$snapshot/"
python3 - "$snapshot/db.sqlite" "$container/Documents/noah-upgrade-check.json" "${FUNDED:-0}" "$container/Library/Preferences/$BUNDLE.plist" <<'PY'
import json, plistlib, sqlite3, sys
connection = sqlite3.connect('file:' + sys.argv[1] + '?mode=ro', uri=True)
connection.row_factory = sqlite3.Row
funded = sys.argv[3] == '1'
result = dict(connection.execute('select network, fingerprint from bark_properties').fetchone())
result['funded'] = funded
with open(sys.argv[4], 'rb') as preferences:
    result['locale'] = plistlib.load(preferences).get('generaltranslation.locale')
if funded:
    assert result['locale'] == 'sv', 'RN locale was not persisted'
result['spendable'] = connection.execute("select coalesce(sum(amount_sat),0) from vtxo_view where state_kind='Spendable'").fetchone()[0]
result['onchain'] = 10000 if funded else 0
result['movements'] = [dict(r) for r in connection.execute('select id, status, subsystem_name, movement_kind, intended_balance, effective_balance, offchain_fee from bark_movements order by id')]
if funded:
    assert result['spendable'] == 95000 and len(result['movements']) == 2, 'RN funded baseline is incomplete'
with open(sys.argv[2], 'w') as output:
    json.dump(result, output)
print('RN baseline: movements=%d Ark=%d confirmed onchain=%d' % (len(result['movements']), result['spendable'], result['onchain']))
PY
cd "$IOS"
xcodegen generate >/dev/null
TEST_RUNNER_NOAH_UPGRADE_CHECK=1 xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "id=$SIM" -derivedDataPath "$DD" ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  -parallel-testing-enabled NO -only-testing:NoahTests/UpgradeCompatibilityTests test
python3 "$HERE/check-widgets.py" "$RN_APP" "$DD/Build/Products/Debug-Regtest-iphonesimulator/Noah.app"
if [[ "${FUNDED:-0}" == 1 ]]; then
  flow "$HERE/native-recurring.yml"
  recurring_shot="${UPGRADE_SCREENSHOT:-/private/tmp/noah-upgrade.png}"
  xcrun simctl io "$SIM" screenshot "${recurring_shot%.png}-recurring.png" >/dev/null
  flow "$HERE/widget-survives.yml"
  python3 "$HERE/check-widget-placement.py" check "$SIM" "$snapshot/widget-state.json"
fi
xcrun simctl io "$SIM" screenshot "${UPGRADE_SCREENSHOT:-/private/tmp/noah-upgrade.png}" >/dev/null
container="$(xcrun simctl get_app_container "$SIM" "$BUNDLE" data)"
python3 - "$container/Documents/noah-data-regtest/db.sqlite" "$container/Documents/noah-upgrade-check.json" "$container/Documents/noah-upgrade-result.json" <<'PYPOST'
import json, sqlite3, sys
with open(sys.argv[2]) as source:
    before = json.load(source)
connection = sqlite3.connect('file:' + sys.argv[1] + '?mode=ro', uri=True)
connection.row_factory = sqlite3.Row
rows = [dict(r) for r in connection.execute('select id, status, subsystem_name, movement_kind, intended_balance, effective_balance, offchain_fee from bark_movements order by id')]
assert rows == before['movements'], 'Opening native recurring UI changed wallet movements'
with open(sys.argv[3]) as source:
    result = json.load(source)
result['recurringAndSettingsVerified'] = before['funded']
result['widgetPlacementPreserved'] = before['funded']
with open(sys.argv[3], 'w') as output:
    json.dump(result, output, indent=2)
PYPOST
cp "$container/Documents/noah-upgrade-result.json" "${UPGRADE_EVIDENCE:-/private/tmp/noah-upgrade-evidence.json}"
echo "Wallet/settings/recurring upgrade checks passed. Expo continuity requires a physical-device run; see README.md."
