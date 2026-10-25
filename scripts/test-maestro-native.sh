#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
platform="${1:?Usage: test-maestro-native.sh <android|ios> <apk|app> <device-id> [all|smoke|funded]}"
artifact="${2:?Pass the regtest APK or simulator .app}"
device="${3:?Pass an explicit emulator serial or simulator UDID}"
suite="${4:-all}"
maestro_command="${MAESTRO_COMMAND:-maestro}"
output="${MAESTRO_DEBUG_OUTPUT:-${TMPDIR:-/tmp}/noah-native-maestro-$device}"
export MAESTRO_DRIVER_STARTUP_TIMEOUT="${MAESTRO_DRIVER_STARTUP_TIMEOUT:-120000}"
case "$suite" in all|smoke|funded) ;; *) echo "Unknown suite: $suite" >&2; exit 2 ;; esac
mkdir -p "$output"

case "$platform" in
  android)
    command -v adb >/dev/null
    [[ "$device" == emulator-* ]] || { echo 'Use an emulator serial.' >&2; exit 2; }
    deadline=$((SECONDS + 300))
    until [[ "$(adb -s "$device" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
      if ((SECONDS >= deadline)); then echo "Timed out booting $device" >&2; exit 1; fi
      sleep 2
    done
    [[ "$(adb -s "$device" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
    adb -s "$device" install -r "$artifact"
    trap 'adb -s "$device" logcat -d > "$output/logcat.txt" 2>&1 || true' EXIT
    ;;
  ios)
    xcrun simctl list devices -j | python3 -c '
import json, sys
candidate = next(d for group in json.load(sys.stdin)["devices"].values() for d in group if d["udid"] == sys.argv[1])
assert candidate["state"] == "Booted", candidate
' "$device"
    xcrun simctl install "$device" "$artifact"
    ;;
  *) echo "Unknown platform: $platform" >&2; exit 2 ;;
esac

run_flow() {
  local name="$1" flow="$2"
  shift 2
  "$maestro_command" --platform "$platform" test --udid "$device" --no-reinstall-driver \
    --format JUNIT --output "$output/$name.xml" --debug-output "$output/$name" \
    --test-output-dir "$output/$name" \
    "$@" "$flow"
}

clipboard() {
  if [[ "$platform" == ios ]]; then
    if [[ "$1" == read ]]; then xcrun simctl pbpaste "$device" | tr -d '\r\n'
    else
      # Match the RN harness: host pasteboard sync otherwise overwrites simulator requests.
      printf '%s' "$2" | pbcopy
      printf '%s' "$2" | xcrun simctl pbcopy "$device"
    fi
  elif [[ "$1" == read ]]; then
    grpcurl "${grpc_args[@]}" "localhost:$grpc_port" \
      android.emulation.control.EmulatorController/getClipboard | jq -r '.text' | tr -d '\r\n'
  else
    jq -n --arg text "$2" '{text: $text}' | grpcurl "${grpc_args[@]}" -d @ "localhost:$grpc_port" \
      android.emulation.control.EmulatorController/setClipboard
  fi
}

if [[ "$suite" == smoke || "$suite" == all ]]; then
  run_flow smoke apps/maestro --include-tags smoke
fi
if [[ "$suite" == funded || "$suite" == all ]]; then
  # The caller owns the stack lifecycle. This script only funds its throwaway wallet.
  just bark balance
  if [[ "$platform" == android ]]; then
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
    grpc_args=(-max-time 30 -plaintext -H "authorization: Bearer $token"
      -import-path "${ANDROID_HOME:?Set ANDROID_HOME}/emulator/lib" -proto emulator_controller.proto)
  fi
  run_flow prepare apps/maestro/subflows/prepare-funded-send.yml
  if [[ "$platform" == ios ]]; then
    version="$(xcrun simctl getenv "$device" SIMULATOR_RUNTIME_VERSION)"
    if [[ "${version%%.*}" -ge 27 ]]; then xcrun simctl privacy "$device" grant pasteboard com.noahwallet.regtest; fi
  fi
  address="$(clipboard read)"
  [[ "$address" == tark1* ]] || { echo 'Expected a regtest Ark address in clipboard.' >&2; exit 1; }
  just bark send --wait "$address" '20000 sats'
  destination="$(just bark address 2>&1 | sed -n '/^tark1/p' | tail -1)"
  [[ "$destination" == tark1* ]] || { echo 'Could not get Bark destination.' >&2; exit 1; }
  clipboard write "$destination"
  run_flow recipient-reset apps/maestro/subflows/send-recipient-reset-on-back.yml -e "DESTINATION=$destination"
  run_flow ark-send apps/maestro/subflows/send-funded-ark.yml
  onchain="$(just bcli getnewaddress 2>&1 | sed -n '/^bcrt1/p' | tail -1)"
  [[ "$onchain" == bcrt1* ]] || { echo 'Could not get regtest Bitcoin address.' >&2; exit 1; }
  clipboard write "bitcoin:$onchain?amount=0.00005"
  run_flow max-reset apps/maestro/subflows/send-max-back-fixed-request.yml
  clipboard write "bitcoin:$onchain?amount=0"
  run_flow amountless apps/maestro/subflows/send-amountless-request.yml
  run_flow exit-deposit apps/maestro/subflows/exit-deposit-landscape.yml
  deposit="$(clipboard read)"
  [[ "$deposit" == bcrt1* ]] || { echo 'Expected a regtest Bitcoin deposit address.' >&2; exit 1; }
  just bcli validateaddress "$deposit" | sed -n '/^{/,/^}/p' | jq -e '.isvalid'
fi
