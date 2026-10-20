#!/usr/bin/env bash
# Destructive only to the explicitly supplied disposable simulator's regtest
# app. Never uninstall between RN creation and native installation.
# FUNDED=1 adds Ark send/receive, confirmed onchain funds, UI-created recurring
# policy, and non-default settings. See README.md for the physical push gate.
set -euo pipefail
umask 077
SECONDS=0
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
  if [[ -n "${writer_pid:-}" ]]; then kill "$writer_pid" 2>/dev/null || true; wait "$writer_pid" 2>/dev/null || true; fi
  rm -rf "$snapshot" "$DEBUG"
  if [[ -z "${UPGRADE_DERIVED_DATA:-}" ]]; then rm -rf "$DD"; fi
  if [[ -n "${container:-}" ]]; then
    container="$(xcrun simctl get_app_container "$SIM" "$BUNDLE" data 2>/dev/null || printf '%s' "$container")"
    rm -rf "$container/Documents/noah-upgrade-rn-mmkv" \
      "$container/Documents/noah-upgrade-rn-mmkv.native-backup" \
      "$container/Documents/noah-upgrade-rn-mmkv.native-backup.pending" \
      "$container/Documents/noah-upgrade-rn-mmkv.native-settings.json" \
      "$container/Documents/noah-upgrade-check.json"
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
flow() { maestro --device "$SIM" test --debug-output "$DEBUG" --test-output-dir "$DEBUG" "$@"; }
if [[ "${RN_PREPARED:-0}" != 1 ]]; then
xcrun simctl uninstall "$SIM" "$BUNDLE" 2>/dev/null || true
xcrun simctl install "$SIM" "$RN_APP"
if [[ "${FUNDED:-0}" == 1 ]]; then
  flow "$FLOWS/prepare-funded-send.yml"
  # Read the displayed request, independent of simulator/host clipboard syncing.
  xcrun swiftc -module-cache-path "$snapshot/swift-cache" "$HERE/read-rn-qr.swift" -o "$snapshot/read-rn-qr"
  xcrun simctl io "$SIM" screenshot "$snapshot/rn-qr.png" >/dev/null
  "$snapshot/read-rn-qr" "$snapshot/rn-qr.png" > "$snapshot/rn-addresses.json"
  rn_ark_address="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ark"])' "$snapshot/rn-addresses.json")"
  rn_onchain_address="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["onchain"])' "$snapshot/rn-addresses.json")"
  [[ "$rn_ark_address" =~ ^tark1 ]] || { echo "no RN Ark address in displayed QR"; exit 1; }
  (cd "$REPO" && just bark --quiet --no-logfile send --wait "$rn_ark_address" "100000 sats")
  bark_address="$(cd "$REPO" && just bark --quiet --no-logfile address 2>&1 | sed -n '/^tark1/p' | tail -n 1)"
  [[ "$bark_address" =~ ^tark1 ]] || { echo "no Bark destination"; exit 1; }
  [[ "$bark_address" != "$rn_ark_address" ]] || { echo "Bark destination equals RN wallet"; exit 1; }
  flow -e "DESTINATION=$bark_address" "$HERE/rn-send-funded.yml"
  flow -e "RECURRING_DESTINATION=$bark_address" "$HERE/rn-settings-and-recurring.yml"
  onchain_address="$rn_onchain_address"
  [[ "$onchain_address" =~ ^bcrt1 ]] || { echo "no RN onchain address in displayed QR"; exit 1; }
  (cd "$REPO" && just bcli sendtoaddress "$onchain_address" 0.0003 && just generate 1)
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
db_inode="$(/usr/bin/stat -f %i "$container/Documents/noah-data-regtest/db.sqlite")"
# Record stopped RN bytes; native must never write this directory.
python3 "$HERE/check-mmkv.py" record "$container/Documents/mmkv" "$snapshot/mmkv-stopped.json"
cp "$container/Documents/noah-data-regtest/"db.sqlite* "$snapshot/"
python3 - "$snapshot/db.sqlite" "$container/Documents/noah-upgrade-check.json" "${FUNDED:-0}" "$container/Library/Preferences/$BUNDLE.plist" "${bark_address:-}" <<'PY'
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
result['onchain'] = 30000 if funded else 0
result['movements'] = [dict(r) for r in connection.execute('select id, status, subsystem_name, movement_kind, intended_balance, effective_balance, offchain_fee from bark_movements order by id')]
if funded:
    assert result['spendable'] == 95000 and len(result['movements']) == 2, 'RN funded baseline is incomplete'
    if sys.argv[5]:
        assert [tuple(r) for r in connection.execute('select destination_value, amount from bark_movements_sent_to')] == [(sys.argv[5], 5000)], 'RN payment did not go to Bark'
with open(sys.argv[2], 'w') as output:
    json.dump(result, output)
print('RN baseline: movements=%d Ark=%d confirmed onchain=%d' % (len(result['movements']), result['spendable'], result['onchain']))
PY
cd "$IOS"
xcodegen generate >/dev/null
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "id=$SIM" -derivedDataPath "$DD" ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -jobs 2 build-for-testing
if [[ "${INSTALL_WHILE_RUNNING:-1}" == 1 ]]; then
  before="$(shasum -a 256 "$container/Documents/mmkv/noah-wallet-storage")"
  maestro --device "$SIM" test --debug-output "$DEBUG/writer" --test-output-dir "$DEBUG/writer" "$HERE/rn-write-during-install.yml" > "$DEBUG/writer.log" 2>&1 &
  writer_pid=$!
  changed=0
  for attempt in {1..60}; do
    if grep -q 'currency-option-EUR.*COMPLETED' "$DEBUG/writer.log" &&
       [[ "$before" != "$(shasum -a 256 "$container/Documents/mmkv/noah-wallet-storage")" ]] && kill -0 "$writer_pid" 2>/dev/null; then changed=1; break; fi
    kill -0 "$writer_pid" 2>/dev/null || break
    sleep 1
  done
  [[ "$changed" == 1 ]] || { echo 'RN did not write MMKV before replacement'; exit 1; }
  echo 'Replacing RN while running and writing MMKV'
fi
# Explicitly replace the running RN process; the test runner must not terminate it first.
sync_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
xcrun simctl install "$SIM" "$DD/Build/Products/Debug-Regtest-iphonesimulator/Noah.app"
if [[ -n "${writer_pid:-}" ]]; then kill "$writer_pid" 2>/dev/null || true; wait "$writer_pid" 2>/dev/null || true; unset writer_pid; fi
# RN has now been killed by replacement. Capture its final generation before
# native's first launch, including the running/writing variant.
container="$(xcrun simctl get_app_container "$SIM" "$BUNDLE" data)"
if [[ "${INSTALL_WHILE_RUNNING:-1}" == 0 ]]; then
  python3 "$HERE/check-mmkv.py" check "$container/Documents/mmkv" "$snapshot/mmkv-stopped.json"
fi
python3 "$HERE/check-mmkv.py" record "$container/Documents/mmkv" "$snapshot/mmkv-final.json"
cp -R "$container/Documents/mmkv" "$container/Documents/noah-upgrade-rn-mmkv"
if [[ "${FUNDED:-0}" == 1 ]]; then flow "$HERE/native-notice.yml"; fi
TEST_RUNNER_NOAH_UPGRADE_CHECK=1 xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "id=$SIM" -derivedDataPath "$DD" ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  -parallel-testing-enabled NO -only-testing:NoahTests/UpgradeCompatibilityTests test-without-building
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
[[ "$db_inode" == "$(/usr/bin/stat -f %i "$container/Documents/noah-data-regtest/db.sqlite")" ]] || { echo "Wallet DB was replaced"; exit 1; }
python3 "$HERE/check-mmkv.py" check "$container/Documents/mmkv" "$snapshot/mmkv-final.json"
python3 "$HERE/check-mmkv.py" snapshot "$container/Documents/mmkv.native-backup" "$snapshot/mmkv-final.json"
if [[ "${FUNDED:-0}" == 1 ]]; then
  login_key="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["loginKey"])' "$container/Documents/noah-upgrade-result.json")"
  native_build="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$DD/Build/Products/Debug-Regtest-iphonesimulator/Noah.app/Info.plist")"
  python3 "$HERE/check-server-sync.py" "$sync_since" "$login_key" "Noah/$native_build "
fi
cp "$container/Documents/noah-upgrade-result.json" "${UPGRADE_EVIDENCE:-/private/tmp/noah-upgrade-evidence.json}"
echo "Elapsed: ${SECONDS}s"
echo "Wallet/settings/recurring upgrade checks passed. Expo continuity requires a physical-device run; see README.md."
