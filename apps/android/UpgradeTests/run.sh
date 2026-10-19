#!/usr/bin/env bash
# Fresh GMS emulator: RN release -> native debug, same certificate and sandbox.
# Usage: run.sh emulator-5556 [path/to/prebuilt-RN-regtest-release.apk]
# Use a root-capable Google APIs image so the release app's Expo ID can be
# hashed before replacement. No uninstall or clear-data operations occur.
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

if [[ $# -lt 2 ]]; then
    (cd "$ROOT/client/android" && CI_DEBUG_SIGNING=true SENTRY_DISABLE_AUTO_UPLOAD=true CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew assembleRegtestRelease -PreactNativeArchitectures=arm64-v8a --max-workers=2 --no-parallel \
        -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m")
fi
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
maestro --device "$SERIAL" test --debug-output "$ARTIFACTS" --test-output-dir "$ARTIFACTS" "$HERE/rn-create-wallet.yml"
"$ADB" -s "$SERIAL" shell am force-stop "$BUNDLE"
"$ADB" -s "$SERIAL" shell test -f "/data/user/0/$BUNDLE/files/noah-data-regtest/db.sqlite"
echo 'RN wallet ready: DB present'

EXPO_HASH="$(python3 - "$ADB" "$SERIAL" "$BUNDLE" <<'PY'
import hashlib, subprocess, sys, uuid, xml.etree.ElementTree as ET
adb, serial, bundle = sys.argv[1:]
base = '/data/user/0/' + bundle

def read(path):
    result = subprocess.run([adb, '-s', serial, 'exec-out', 'cat', base + path], capture_output=True)
    return result.stdout if result.returncode == 0 else None

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
    "$BUNDLE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$ARTIFACTS/instrumentation.txt"
grep -q 'OK (1 test)' "$ARTIFACTS/instrumentation.txt"
"$ADB" -s "$SERIAL" shell am start -W -n "$BUNDLE/com.noahwallet.MainActivity" >/dev/null
echo 'Upgrade check passed: seed migrated and verified; legacy untouched; DB, MMKV and launch state preserved; Expo ID checked'
