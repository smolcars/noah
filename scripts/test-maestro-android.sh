#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
export MAESTRO_DRIVER_STARTUP_TIMEOUT="${MAESTRO_DRIVER_STARTUP_TIMEOUT:-120000}"

mkdir -p client/maestro-debug-output
adb -s "$ANDROID_SERIAL" logcat -c
trap 'adb -s "$ANDROID_SERIAL" logcat -d > client/maestro-debug-output/logcat.txt 2>&1' EXIT
adb -s "$ANDROID_SERIAL" install -r "${1:?Usage: test-maestro-android.sh <release-regtest.apk>}"
maestro --udid "$ANDROID_SERIAL" test --debug-output client/maestro-debug-output client/.maestro
maestro --udid "$ANDROID_SERIAL" test --debug-output client/maestro-debug-output/adaptive client/tests/maestro/adaptive-layout-smoke.yml
scripts/test-send-regtest.sh
