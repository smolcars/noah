#!/usr/bin/env bash
# Fresh GMS emulator: RN release -> native debug, same certificate and sandbox.
# Usage: run.sh emulator-5594 [path/to/prebuilt-RN-regtest-release.apk]
# FUNDED=1: Ark receive/send, confirmed onchain funds, paused recurring rule and settings.
# Use a root-capable Google APIs image so the release app's Expo ID can be
# hashed before replacement. Native replacement never uninstalls or clears data.
# FUNDED uses the RN prepare flow, which resets only the fresh test install.
set -euo pipefail
umask 077
SECONDS=0

SERIAL="${1:?Pass a fresh Android emulator serial}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$(dirname "$HERE")"
ROOT="$(cd "$ANDROID/../.." && pwd)"
BUNDLE=com.noahwallet.regtest
RN_APK="${2:-$ROOT/client/android/app/build/outputs/apk/regtest/release/app-regtest-release.apk}"
NATIVE_APK="$ANDROID/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk"
TEST_APK="$ANDROID/app/build/outputs/apk/androidTest/regtest/debug/app-regtest-debug-androidTest.apk"
RN_BASELINE_APK="$ANDROID/app/build/outputs/apk/rn-upgrade-baseline.apk"
ADB="${ADB:-adb}"
APKSIGNER="${APKSIGNER:-${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/37.0.0/apksigner}"

[[ "$SERIAL" =~ ^emulator-([0-9]+)$ && "${BASH_REMATCH[1]}" -ge 5590 ]] || {
    echo 'Use your own fresh emulator on an explicit port >= 5590 (5554/5556 are reserved)'; exit 1;
}
[[ "$SERIAL" == emulator-* ]] || { echo 'An emulator is required'; exit 1; }
[[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || exit 1
if "$ADB" -s "$SERIAL" shell pm path "$BUNDLE" | grep -q 'package:'; then
    echo 'Refusing to overwrite an existing test installation. Use a fresh emulator.'
    exit 1
fi
"$ADB" -s "$SERIAL" shell pm path com.google.android.gms | grep -q 'package:' || {
    echo 'Google Play Services required for the RN keychain path'; exit 1;
}
"$ADB" -s "$SERIAL" root >/dev/null
"$ADB" -s "$SERIAL" wait-for-device
[[ "$("$ADB" -s "$SERIAL" shell id -u | tr -d '\r')" == 0 ]] || {
    echo 'Use a root-capable Google APIs emulator to capture the release Expo ID'; exit 1;
}

check_disk() {
    python3 - <<'PY_DISK'
import shutil, sys
if shutil.disk_usage('/System/Volumes/Data' if sys.platform == 'darwin' else '/').free < 12 * 1024**3:
    sys.exit('Build needs at least 12 GiB free to preserve the 10 GiB floor')
PY_DISK
}
# Maestro may capture the recovery screen on failure; keep artifacts private
# and remove them on exit. Do not upload these artifacts to CI.
ARTIFACTS="$(mktemp -d)"
trap 'if [[ -n "${writer_pid:-}" ]]; then kill "$writer_pid" 2>/dev/null || true; wait "$writer_pid" 2>/dev/null || true; fi; rm -r "$ARTIFACTS"' EXIT
check_disk
if [[ $# -lt 2 ]]; then
    if ! (cd "$ROOT" && bun install --frozen-lockfile > "$ARTIFACTS/install.log" 2>&1); then
        echo 'Dependency install failed; output withheld because asset download URLs may contain credentials.'
        exit 1
    fi
    check_disk
    (cd "$ROOT/client/android" && CI_DEBUG_SIGNING=true SENTRY_DISABLE_AUTO_UPLOAD=true CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew assembleRegtestRelease -PreactNativeArchitectures=arm64-v8a --max-workers=2 --no-parallel \
        -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m")
fi
check_disk
(cd "$ANDROID" && ./gradlew --max-workers=2 -PnoahRNUpgradeBaseline=true :app:assembleRegtestDebugAndroidTest)
cp "$TEST_APK" "$RN_BASELINE_APK"
(cd "$ANDROID" && ./gradlew --max-workers=2 :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest)
cert() {
    "$APKSIGNER" verify --print-certs "$1" |
        awk '/^(Signer #[0-9]+|V[0-9]+ Signer): certificate SHA-256 digest: / {print $NF}' | sort -u
}
RN_CERT="$(cert "$RN_APK")"
[[ -n "$RN_CERT" && "$RN_CERT" == "$(cert "$NATIVE_APK")" ]] || {
    echo 'RN/native signing certificates differ'; exit 1;
}
echo "Signing certificates match: $RN_CERT"
python3 - <<'PY_READY'
import socket, sys
for port in (18443, 3535, 3000):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=3):
            pass
    except OSError:
        sys.exit(f'Regtest prerequisite unavailable: host port {port}. Restore the existing stack before running the upgrade.')
PY_READY
"$ADB" -s "$SERIAL" install "$RN_APK"

run_flow() {
    maestro --device "$SERIAL" test --debug-output "$ARTIFACTS" --test-output-dir "$ARTIFACTS" "$1"
}
# Keep client sources unchanged; standalone post-onboarding flows need to wait
# for RN's cold-start icon/wallet loading screen on the emulator.
run_ready_rn_flow() {
    local prepared="$ARTIFACTS/$(basename "$1")"
    python3 - "$1" "$prepared" <<'PY_READY_RN'
from pathlib import Path
import sys
source = Path(sys.argv[1]).read_text()
launch = '- launchApp\n'
assert launch in source
ready = '- extendedWaitUntil:\n    visible: "Home"\n    timeout: 60000\n'
Path(sys.argv[2]).write_text(source.replace(launch, launch + ready, 1))
PY_READY_RN
    run_flow "$prepared"
}
# ark-dev.sh uses compose run without --no-deps, which can recreate the live stack
# when this worktree's config differs. Keep the existing services and volumes.
bark() { docker compose -f "$ROOT/scripts/docker-compose.yml" run --rm --no-deps bark bark --quiet --no-logfile "$@"; }
if [[ "${FUNDED:-0}" == 1 ]]; then
    python3 "$HERE/clipboard.py" "$SERIAL" get >/dev/null
    run_flow "$ROOT/client/.maestro/subflows/prepare-funded-send.yml"
    rn_ark_address="$(python3 "$HERE/clipboard.py" "$SERIAL" get | tr -d '\r\n')"
    [[ "$rn_ark_address" =~ ^tark1 ]] || { echo 'RN Ark address missing from clipboard'; exit 1; }
    bark send --wait "$rn_ark_address" "100000 sats"
    bark_output="$(bark address)"
    bark_address="$(printf '%s\n' "$bark_output" | sed -n '/^tark1/p' | tail -n 1)"
    [[ "$bark_address" =~ ^tark1 ]] || { echo 'Bark address unavailable'; exit 1; }
    printf '%s' "$bark_address" | python3 "$HERE/clipboard.py" "$SERIAL" set
    maestro --device "$SERIAL" test --debug-output "$ARTIFACTS" --test-output-dir "$ARTIFACTS" -e "DESTINATION=$bark_address" "$ROOT/apps/ios/UpgradeTests/rn-send-funded.yml"
    run_flow "$HERE/rn-finish-send.yml"
    run_ready_rn_flow "$ROOT/client/.maestro/subflows/android-upgrade-settings-recurring.yml"
    run_ready_rn_flow "$ROOT/client/.maestro/subflows/android-upgrade-onchain-address.yml"
    onchain_address="$(python3 "$HERE/clipboard.py" "$SERIAL" get | tr -d '\r\n')"
    [[ "$onchain_address" =~ ^bcrt1 ]] || { echo 'RN onchain address missing'; exit 1; }
    (cd "$ROOT" && just bcli sendtoaddress "$onchain_address" 0.00030000)
    (cd "$ROOT" && just generate 1)
    run_flow "$HERE/rn-confirm-onchain.yml"
else
    run_flow "$HERE/rn-create-wallet.yml"
fi
run_flow "$HERE/rn-place-widget.yml"
# RN deliberately skips automatic push registration on emulators. Provision using RN's
# own Expo InstallationId + FCM runtime without changing the RN APK or wallet data.
"$ADB" -s "$SERIAL" install -r "$RN_BASELINE_APK"
"$ADB" -s "$SERIAL" shell pm grant "$BUNDLE" android.permission.POST_NOTIFICATIONS
"$ADB" -s "$SERIAL" shell am instrument -w -r "$BUNDLE.test/com.noahwallet.services.RNPushBaseline" > "$ARTIFACTS/expo-baseline.txt"
EXPO_TOKEN_HASH="$(sed -n 's/^INSTRUMENTATION_RESULT: expoTokenSha256=//p' "$ARTIFACTS/expo-baseline.txt" | tr -d '\r')"
[[ "$EXPO_TOKEN_HASH" =~ ^[0-9a-f]{64}$ ]] || {
    echo 'Real RN-runtime Expo token baseline unavailable; refusing to claim continuity'; exit 1;
}
echo 'Real Expo token provisioned in RN sandbox (SHA-256 only)'
WIDGET_INFO_HASH="$(sed -n 's/^INSTRUMENTATION_RESULT: widgetInfoSha256=//p' "$ARTIFACTS/expo-baseline.txt" | tr -d '\r')"
[[ "$WIDGET_INFO_HASH" =~ ^[0-9a-f]{64}$ ]] || { echo 'RN widget provider baseline missing'; exit 1; }
run_flow "$HERE/rn-confirm-shortcut.yml"
if [[ "${FUNDED:-0}" == 1 ]]; then run_flow "$HERE/rn-locale.yml"; fi
"$ADB" -s "$SERIAL" shell am force-stop "$BUNDLE"
"$ADB" -s "$SERIAL" shell test -f "/data/user/0/$BUNDLE/files/noah-data-regtest/db.sqlite"
db_inode="$("$ADB" -s "$SERIAL" shell stat -c %i "/data/user/0/$BUNDLE/files/noah-data-regtest/db.sqlite" | tr -d '\r')"
echo 'RN wallet ready: DB present'
# Force-stop first; pull the full directory so any WAL accompanies the DB.
"$ADB" -s "$SERIAL" pull "/data/user/0/$BUNDLE/files/noah-data-regtest" "$ARTIFACTS/wallet" >/dev/null
query() { sqlite3 -bail "$ARTIFACTS/wallet/db.sqlite" "$1"; }
# Standalone assignments preserve sqlite3 failures; export/local would mask them.
fingerprint="$(query 'select fingerprint from bark_properties')"
movements="$(query 'select count(*) from bark_movements')"
movement_ids="$(query 'select group_concat(id, ",") from (select id from bark_movements order by id)')"
spendable="$(query "select coalesce(sum(amount_sat), 0) from vtxo_view where state_kind = 'Spendable'")"
[[ -n "$fingerprint" && "$movements" =~ ^[0-9]+$ && "$spendable" =~ ^[0-9]+$ ]] || {
    echo 'Could not read required RN wallet expectations'; exit 1;
}
if [[ "${FUNDED:-0}" == 1 ]]; then
    [[ "$movements" -ge 2 && "$spendable" == 95000 ]] || {
        echo 'Funded RN flow did not settle at 95,000 sats with receive/send history'; exit 1;
    }
fi
echo "RN DB: fingerprint=$fingerprint movements=$movements spendable=$spendable"

EXPO_HASH="$(python3 - "$ADB" "$SERIAL" "$BUNDLE" <<'PY'
import hashlib, subprocess, sys, uuid, xml.etree.ElementTree as ET
adb, serial, bundle = sys.argv[1:]
base = '/data/user/0/' + bundle

def read(path):
    # exec-out can return success with cat's missing-file diagnostic in stdout.
    exists = subprocess.run([adb, '-s', serial, 'shell', 'test', '-f', base + path], capture_output=True)
    if exists.returncode != 0:
        return None
    result = subprocess.run([adb, '-s', serial, 'exec-out', 'cat', base + path], capture_output=True)
    if result.returncode != 0:
        sys.exit('Could not read an existing RN Expo identity file')
    return result.stdout

def file_uuid(path):
    value = read(path)
    if value is not None:
        try:
            return str(uuid.UUID(value.decode().splitlines()[0]))
        except (ValueError, IndexError, UnicodeError):
            pass

value = file_uuid('/no_backup/expo_notifications_installation_uuid.txt')
if value is None:
    prefs = read('/shared_prefs/host.exp.exponent.SharedPreferences.xml')
    if prefs is not None:
        value = next((s.text or '' for s in ET.fromstring(prefs).findall('string') if s.get('name') == 'uuid'), None)
if value is None:
    value = file_uuid('/no_backup/expo_installation_uuid.txt')
print('absent' if value is None else hashlib.sha256(value.encode()).hexdigest())
PY
)"
[[ "$EXPO_HASH" != absent ]] || { echo 'RN Expo installation UUID missing'; exit 1; }
echo 'RN Expo ID captured (SHA-256 only)'
# Preserve every live RN data/CRC file before replacement.
"$ADB" -s "$SERIAL" pull "/data/user/0/$BUNDLE/files/mmkv" "$ARTIFACTS/mmkv-stopped" >/dev/null
python3 "$ROOT/apps/ios/UpgradeTests/check-mmkv.py" record "$ARTIFACTS/mmkv-stopped" "$ARTIFACTS/mmkv-stopped.json"
mmkv_hash="$("$ADB" -s "$SERIAL" shell sha256sum "/data/user/0/$BUNDLE/files/mmkv/noah-wallet-storage" | awk '{print $1}')"
"$ADB" -s "$SERIAL" shell dumpsys appwidget > "$ARTIFACTS/widgets-before.txt"
"$ADB" -s "$SERIAL" shell dumpsys shortcut > "$ARTIFACTS/shortcuts-before.txt"
if [[ "${INSTALL_WHILE_RUNNING:-1}" == 1 ]]; then
    maestro --device "$SERIAL" test --debug-output "$ARTIFACTS/writer" --test-output-dir "$ARTIFACTS/writer" "$HERE/rn-write-during-install.yml" > "$ARTIFACTS/writer.log" 2>&1 &
    writer_pid=$!
    changed=0
    for attempt in {1..120}; do
        current="$("$ADB" -s "$SERIAL" shell sha256sum "/data/user/0/$BUNDLE/files/mmkv/noah-wallet-storage" | awk '{print $1}')"
        if grep -q 'currency-option-EUR.*COMPLETED' "$ARTIFACTS/writer.log" &&
           [[ "$mmkv_hash" != "$current" ]] && kill -0 "$writer_pid" 2>/dev/null; then changed=1; break; fi
        kill -0 "$writer_pid" 2>/dev/null || break
        sleep 1
    done
    [[ "$changed" == 1 ]] || { echo 'RN did not write MMKV before replacement'; exit 1; }
    echo 'Replacing RN while running and writing MMKV'
fi
sync_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
"$ADB" -s "$SERIAL" install -r "$NATIVE_APK"
if [[ -n "${writer_pid:-}" ]]; then kill "$writer_pid" 2>/dev/null || true; wait "$writer_pid" 2>/dev/null || true; unset writer_pid; fi
if [[ "${INSTALL_WHILE_RUNNING:-1}" != 1 ]]; then
    [[ "$mmkv_hash" == "$("$ADB" -s "$SERIAL" shell sha256sum "/data/user/0/$BUNDLE/files/mmkv/noah-wallet-storage" | awk '{print $1}')" ]] || { echo 'RN MMKV bytes changed during replacement'; exit 1; }
fi
# Replacement has stopped the last RN writer; native has not launched yet.
"$ADB" -s "$SERIAL" pull "/data/user/0/$BUNDLE/files/mmkv" "$ARTIFACTS/mmkv-final" >/dev/null
if [[ "${INSTALL_WHILE_RUNNING:-1}" == 0 ]]; then
    python3 "$ROOT/apps/ios/UpgradeTests/check-mmkv.py" check "$ARTIFACTS/mmkv-final" "$ARTIFACTS/mmkv-stopped.json"
fi
python3 "$ROOT/apps/ios/UpgradeTests/check-mmkv.py" record "$ARTIFACTS/mmkv-final" "$ARTIFACTS/mmkv-final.json"
if [[ "${FUNDED:-0}" == 1 ]]; then run_flow "$HERE/native-notice.yml"; fi
"$ADB" -s "$SERIAL" shell dumpsys appwidget > "$ARTIFACTS/widgets-after.txt"
"$ADB" -s "$SERIAL" shell dumpsys shortcut > "$ARTIFACTS/shortcuts-after.txt"
python3 "$HERE/check-launcher.py" "$BUNDLE" "$ARTIFACTS"
"$ADB" -s "$SERIAL" install -r "$TEST_APK"
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class com.noahwallet.services.UpgradeCompatibilityTest \
    -e noahUpgradeCheck 1 -e expoIdSha256 "$EXPO_HASH" -e expoTokenSha256 "$EXPO_TOKEN_HASH" \
    -e funded "${FUNDED:-0}" -e expectedOnchain 30000 -e widgetInfoSha256 "$WIDGET_INFO_HASH" \
    -e expectedFingerprint "$fingerprint" -e expectedMovementIds "$movement_ids" -e expectedMovements "$movements" -e expectedSpendable "$spendable" \
    "$BUNDLE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$ARTIFACTS/instrumentation.txt"
grep -q 'OK (1 test)' "$ARTIFACTS/instrumentation.txt"
# Freeze the final database/WAL generation after all foreground assertions.
"$ADB" -s "$SERIAL" shell am force-stop "$BUNDLE"
[[ "$db_inode" == "$("$ADB" -s "$SERIAL" shell stat -c %i "/data/user/0/$BUNDLE/files/noah-data-regtest/db.sqlite" | tr -d '\r')" ]] || { echo 'Wallet DB was replaced'; exit 1; }
"$ADB" -s "$SERIAL" pull "/data/user/0/$BUNDLE/files/mmkv" "$ARTIFACTS/mmkv-after" >/dev/null
python3 "$ROOT/apps/ios/UpgradeTests/check-mmkv.py" check "$ARTIFACTS/mmkv-after" "$ARTIFACTS/mmkv-final.json"
"$ADB" -s "$SERIAL" pull "/data/user/0/$BUNDLE/no_backup/mmkv.native-backup" "$ARTIFACTS/mmkv-snapshot" >/dev/null
python3 "$ROOT/apps/ios/UpgradeTests/check-mmkv.py" snapshot "$ARTIFACTS/mmkv-snapshot" "$ARTIFACTS/mmkv-final.json"
"$ADB" -s "$SERIAL" pull "/data/user/0/$BUNDLE/files/noah-data-regtest" "$ARTIFACTS/wallet-after" >/dev/null
python3 - "$ARTIFACTS/wallet/db.sqlite" "$ARTIFACTS/wallet-after/db.sqlite" <<'PY_HISTORY'
import sqlite3, sys
connections = [sqlite3.connect('file:' + p + '?mode=ro', uri=True) for p in sys.argv[1:]]
query = 'select id, status, subsystem_name, movement_kind, intended_balance, effective_balance, offchain_fee from bark_movements order by id'
assert connections[0].execute(query).fetchall() == connections[1].execute(query).fetchall(), 'Upgrade changed RN movements or made a payment'
PY_HISTORY
"$ADB" -s "$SERIAL" pull "/data/user/0/$BUNDLE/files/noah-upgrade-result.json" "$ARTIFACTS/result.json" >/dev/null
if [[ "${FUNDED:-0}" == 1 ]]; then
    login_key="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["loginKey"])' "$ARTIFACTS/result.json")"
    python3 "$ROOT/apps/ios/UpgradeTests/check-server-sync.py" "$sync_since" "$login_key" "ktor-client"
fi
cp "$ARTIFACTS/result.json" "${UPGRADE_EVIDENCE:-/private/tmp/noah-android-upgrade-evidence.json}"
echo "Upgrade compatibility assertions passed (wallet, settings, notices, MMKV, server sync, Expo and launcher); elapsed ${SECONDS}s"
