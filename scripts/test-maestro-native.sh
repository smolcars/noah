#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
platform="${1:?Usage: test-maestro-native.sh android <apk> <emulator-serial> [smoke|funded|all]}"
artifact="${2:?Pass the regtest APK}"
device="${3:?Pass an explicit emulator serial}"
suite="${4:-all}"
maestro_command="${MAESTRO_COMMAND:-maestro}"
output="${MAESTRO_DEBUG_OUTPUT:-${TMPDIR:-/tmp}/noah-native-maestro-$device}"
export MAESTRO_DRIVER_STARTUP_TIMEOUT="${MAESTRO_DRIVER_STARTUP_TIMEOUT:-120000}"
[[ "$platform" == android ]] || { echo 'The native Maestro flows run on Android only.' >&2; exit 2; }
case "$suite" in all|smoke|funded) ;; *) echo "Unknown suite: $suite" >&2; exit 2 ;; esac
mkdir -p "$output"

command -v adb >/dev/null
[[ "$device" == emulator-* ]] || { echo 'Use an emulator serial.' >&2; exit 2; }
deadline=$((SECONDS + 300))
until [[ "$(adb -s "$device" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
  if ((SECONDS >= deadline)); then echo "Timed out booting $device" >&2; exit 1; fi
  sleep 2
done
[[ "$(adb -s "$device" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
adb -s "$device" install -r "$artifact"
# No animations (the app's reduced-motion path) for speed, and mobile data only: the
# emulator's Wi-Fi can reconnect mid-payment and leave a send's outcome uncertain.
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$device" shell settings put global "$scale" 0
done
adb -s "$device" shell svc wifi disable
trap 'adb -s "$device" logcat -d > "$output/logcat.txt" 2>&1 || true' EXIT

run_flows() {
  local name="$1"
  shift
  "$maestro_command" --platform android test --udid "$device" --no-reinstall-driver \
    --format JUNIT --output "$output/$name.xml" --debug-output "$output/$name" \
    --test-output-dir "$output/$name" "$@"
}

if [[ "$suite" == smoke || "$suite" == all ]]; then
  run_flows smoke --include-tags smoke apps/maestro
fi
if [[ "$suite" == funded || "$suite" == all ]]; then
  grpc_port="${MAESTRO_EMULATOR_GRPC_PORT:?Launch this emulator with -grpc <port> -grpc-use-token and pass MAESTRO_EMULATOR_GRPC_PORT}"
  discovery="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/avd/running"
  if [[ "$(uname)" == Darwin ]]; then discovery="$HOME/Library/Caches/TemporaryItems/avd/running"; fi
  token=""
  for info in "$discovery"/pid_*.ini; do
    if [[ -f "$info" ]] && grep -q "^grpc.port=$grpc_port$" "$info"; then
      token="$(sed -n 's/^grpc.token=//p' "$info")"; break
    fi
  done
  : "${token:?No emulator gRPC token found}"
  run_flows funded-setup apps/maestro/subflows/fund-wallet.yml
  request="$(grpcurl -max-time 30 -plaintext -H "authorization: Bearer $token" \
    -import-path "${ANDROID_HOME:?Set ANDROID_HOME}/emulator/lib" -proto emulator_controller.proto \
    "localhost:$grpc_port" android.emulation.control.EmulatorController/getClipboard | jq -r '.text')"
  invoice="$(sed -n 's/.*[?&]lightning=\([^&]*\).*/\1/p' <<<"$request")"
  ark="$(sed -n 's/.*[?&]ark=\([^&]*\).*/\1/p' <<<"$request")"
  [[ "$invoice" == lnbcrt* && "$ark" == tark1* ]] || { echo 'Expected a regtest payment request in the clipboard.' >&2; exit 1; }
  # The caller owns the stack lifecycle. This only pays the job's own invoice from LND; no blocks are mined.
  read -ra lncli <<<"${MAESTRO_LNCLI:-just lncli}"
  "${lncli[@]}" payinvoice --force --timeout 60s "$invoice" >/dev/null
  # The funded folder on its own: config.yaml's ordered smoke chain can't match the funded tag.
  run_flows funded --include-tags funded -e "ARK=$ark" apps/maestro/funded
fi
