#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
export MAESTRO_DRIVER_STARTUP_TIMEOUT="${MAESTRO_DRIVER_STARTUP_TIMEOUT:-120000}"
apk_path="${1:?Usage: test-maestro-android.sh <release-regtest.apk> [ui-device-serial]}"
ui_serial="${2:-$ANDROID_SERIAL}"
devices=("$ANDROID_SERIAL")
if [[ "$ui_serial" != "$ANDROID_SERIAL" ]]; then
  devices+=("$ui_serial")
fi
ui_pid=""
funded_pid=""

mkdir -p client/maestro-debug-output
cleanup() {
  if [[ -n "$ui_pid" ]]; then kill "$ui_pid" 2>/dev/null || true; fi
  if [[ -n "$funded_pid" ]]; then kill "$funded_pid" 2>/dev/null || true; fi
  for serial in "${devices[@]}"; do
    adb -s "$serial" logcat -d > "client/maestro-debug-output/logcat-$serial.txt" 2>&1 || true
  done
}
trap cleanup EXIT

for serial in "${devices[@]}"; do
  deadline=$((SECONDS + 300))
  until [[ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if ((SECONDS >= deadline)); then
      printf 'Timed out waiting for %s to boot.\n' "$serial" >&2
      exit 1
    fi
    sleep 2
  done
  adb -s "$serial" shell input keyevent 82
  adb -s "$serial" logcat -c
  adb -s "$serial" install -r "$apk_path"
done

run_ui() {
  maestro test --udid "$ui_serial" --no-reinstall-driver \
    --debug-output client/maestro-debug-output/ui \
    client/.maestro
  maestro test --udid "$ui_serial" --no-reinstall-driver \
    --debug-output client/maestro-debug-output/adaptive \
    client/tests/maestro/adaptive-layout-smoke.yml
}

if [[ "$ui_serial" == "$ANDROID_SERIAL" ]]; then
  run_ui
  scripts/test-send-regtest.sh
else
  run_ui &
  ui_pid=$!
  scripts/test-send-regtest.sh &
  funded_pid=$!
  status=0
  wait "$ui_pid" || status=1
  ui_pid=""
  wait "$funded_pid" || status=1
  funded_pid=""
  exit "$status"
fi
