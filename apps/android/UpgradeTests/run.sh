#!/usr/bin/env bash
# Fresh GMS emulator: RN release -> native debug, same certificate and sandbox.
# Usage: run.sh emulator-5556 [path/to/prebuilt-RN-regtest-release.apk]
# FUNDED=1 receives 100k sats and sends 5k before comparing the opened wallet.
# Use a root-capable Google APIs image so the release app's Expo ID can be
# hashed before replacement. Native replacement never uninstalls or clears data.
# FUNDED uses the RN prepare flow, which resets only the fresh test install.
set -euo pipefail
umask 077

SERIAL="${1:?Pass a fresh Android emulator serial}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$(dirname "$HERE")"
ROOT="$(cd "$ANDROID/../.." && pwd)"
BUNDLE=com.noahwallet.regtest
RN_APK="${2:-$ROOT/client/android/app/build/outputs/apk/regtest/release/app-regtest-release.apk}"
NATIVE_APK="$ANDROID/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk"
TEST_APK="$ANDROID/app/build/outputs/apk/androidTest/regtest/debug/app-regtest-debug-androidTest.apk"
ADB="${ADB:-adb}"
APKSIGNER="${APKSIGNER:-${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/37.0.0/apksigner}"

[[ "$SERIAL" != emulator-5554 ]] || { echo 'BarkRegtest is reserved for another workstream'; exit 1; }
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
check_disk
if [[ $# -lt 2 ]]; then
    (cd "$ROOT" && bun install --frozen-lockfile)
    check_disk
    (cd "$ROOT/client/android" && CI_DEBUG_SIGNING=true SENTRY_DISABLE_AUTO_UPLOAD=true CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew assembleRegtestRelease -PreactNativeArchitectures=arm64-v8a --max-workers=2 --no-parallel \
        -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m")
fi
check_disk
(cd "$ANDROID" && ./gradlew :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest)
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

# Maestro may capture the recovery screen on failure; keep artifacts private
# and remove them on exit. Do not upload these artifacts to CI.
ARTIFACTS="$(mktemp -d)"
trap 'rm -r "$ARTIFACTS"' EXIT
run_flow() {
    maestro --device "$SERIAL" test --debug-output "$ARTIFACTS" --test-output-dir "$ARTIFACTS" "$1"
}
# ark-dev.sh uses compose run without --no-deps, which can recreate the live stack
# when this worktree's config differs. Keep the existing services and volumes.
bark() { docker compose -f "$ROOT/scripts/docker-compose.yml" run --rm --no-deps bark bark "$@"; }
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
    run_flow "$ROOT/client/.maestro/subflows/send-funded-ark.yml"
else
    run_flow "$HERE/rn-create-wallet.yml"
fi
"$ADB" -s "$SERIAL" shell am force-stop "$BUNDLE"
"$ADB" -s "$SERIAL" shell test -f "/data/user/0/$BUNDLE/files/noah-data-regtest/db.sqlite"
echo 'RN wallet ready: DB present'
# Force-stop first; pull the full directory so any WAL accompanies the DB.
"$ADB" -s "$SERIAL" pull "/data/user/0/$BUNDLE/files/noah-data-regtest" "$ARTIFACTS/wallet" >/dev/null
query() { sqlite3 -bail "$ARTIFACTS/wallet/db.sqlite" "$1"; }
# Standalone assignments preserve sqlite3 failures; export/local would mask them.
fingerprint="$(query 'select fingerprint from bark_properties')"
movements="$(query 'select count(*) from bark_movements')"
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
if [[ "$EXPO_HASH" == absent ]]; then echo 'RN Expo ID absent'; else echo 'RN Expo ID captured (SHA-256 only)'; fi
"$ADB" -s "$SERIAL" install -r "$NATIVE_APK"
"$ADB" -s "$SERIAL" install -r "$TEST_APK"
"$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class com.noahwallet.services.UpgradeCompatibilityTest \
    -e noahUpgradeCheck 1 -e expoIdSha256 "$EXPO_HASH" \
    -e expectedFingerprint "$fingerprint" -e expectedMovements "$movements" -e expectedSpendable "$spendable" \
    "$BUNDLE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$ARTIFACTS/instrumentation.txt"
grep -q 'OK (1 test)' "$ARTIFACTS/instrumentation.txt"
"$ADB" -s "$SERIAL" shell am start -W -n "$BUNDLE/com.noahwallet.MainActivity" >/dev/null
echo 'Upgrade check passed: seed migrated and verified; legacy untouched; DB opened with matching fingerprint, history and balance; MMKV and launch state preserved; Expo ID checked'
