#!/usr/bin/env bash

set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maestro_command="${MAESTRO_COMMAND:-maestro}"
maestro_debug_output="${MAESTRO_DEBUG_OUTPUT:-client/maestro-debug-output/send-funded}"
simulator_id="${SIMULATOR_ID:-}"

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  simulator_id="$ANDROID_SERIAL"
elif [[ -z "$simulator_id" ]]; then
  simulator_id="$(xcrun simctl list devices booted | awk -F '[()]' '/Booted/ { print $2; exit }')"
fi
if [[ -z "$simulator_id" ]]; then
  printf 'No booted iOS simulator found.\n' >&2
  exit 1
fi

cd "$project_root"

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  discovery_dir="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/avd/running"
  if [[ "$(uname)" == "Darwin" ]]; then
    discovery_dir="$HOME/Library/Caches/TemporaryItems/avd/running"
  fi
  grpc_token=""
  for info_file in "$discovery_dir"/pid_*.ini; do
    if [[ -f "$info_file" ]] && grep -q '^grpc.port=8556$' "$info_file"; then
      grpc_token="$(sed -n 's/^grpc.token=//p' "$info_file")"
      break
    fi
  done
  : "${grpc_token:?Launch the Android emulator with -grpc 8556 -grpc-use-token}"
  # The emulator's allowlist blocks reflection; use its bundled API definition.
  grpc_args=(-max-time 30 -plaintext -H "authorization: Bearer $grpc_token"
    -import-path "${ANDROID_HOME:?Set ANDROID_HOME to the emulator SDK}/emulator/lib"
    -proto emulator_controller.proto)
fi

copy_to_simulator() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    jq -n --arg text "$1" '{text: $text}' | grpcurl "${grpc_args[@]}" -d @ localhost:8556 \
      android.emulation.control.EmulatorController/setClipboard
    return
  fi
  # Automatic pasteboard sync can overwrite the simulator with the Mac clipboard.
  printf '%s' "$1" | pbcopy
  printf '%s' "$1" | xcrun simctl pbcopy "$simulator_id"
}

read_from_simulator() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    grpcurl "${grpc_args[@]}" localhost:8556 \
      android.emulation.control.EmulatorController/getClipboard | jq -r '.text' | tr -d '\r\n'
  else
    xcrun simctl pbpaste "$simulator_id" | tr -d '\r\n'
  fi
}

"$maestro_command" test --udid "$simulator_id" \
  --debug-output "$maestro_debug_output/prepare" \
  client/.maestro/subflows/prepare-funded-send.yml

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  # iOS 27's simulator can deny clipboard reads without showing the Allow Paste prompt.
  simulator_runtime_version="$(xcrun simctl getenv "$simulator_id" SIMULATOR_RUNTIME_VERSION)"
  if [[ "${simulator_runtime_version%%.*}" -ge 27 ]]; then
    xcrun simctl privacy "$simulator_id" grant pasteboard com.noahwallet.regtest
  fi
fi
simulator_ark_address="$(read_from_simulator)"
if [[ ! "$simulator_ark_address" =~ ^tark1 ]]; then
  printf 'Expected a regtest Ark address in the simulator clipboard, got: %s\n' \
    "$simulator_ark_address" >&2
  exit 1
fi

printf 'Funding simulator address %s with 100000 sats from Bark.\n' "$simulator_ark_address"
just bark send --wait "$simulator_ark_address" "100000 sats"

bark_ark_address="$(just bark address 2>&1 | sed -n '/^tark1/p' | tail -n 1)"
if [[ ! "$bark_ark_address" =~ ^tark1 ]]; then
  printf 'Could not generate a regtest Bark address.\n' >&2
  exit 1
fi

copy_to_simulator "$bark_ark_address"
printf 'Verifying an abandoned recipient is not reused for a new amount.\n'
"$maestro_command" test --udid "$simulator_id" \
  --debug-output "$maestro_debug_output/recipient-reset-on-back" \
  client/.maestro/subflows/send-recipient-reset-on-back.yml

printf 'Sending 5000 sats from the simulator to Bark address %s.\n' "$bark_ark_address"
"$maestro_command" test --udid "$simulator_id" \
  --debug-output "$maestro_debug_output/payment" \
  client/.maestro/subflows/send-funded-ark.yml

fixed_request_address="$(just bcli getnewaddress 2>&1 | sed -n '/^bcrt1/p' | tail -n 1)"
if [[ ! "$fixed_request_address" =~ ^bcrt1 ]]; then
  printf 'Could not generate a regtest Bitcoin address for the fixed-request regression.\n' >&2
  exit 1
fi

printf 'Verifying MAX resets before pasting a fixed-amount payment request.\n'
copy_to_simulator "bitcoin:$fixed_request_address?amount=0.00005"
"$maestro_command" test --udid "$simulator_id" \
  --debug-output "$maestro_debug_output/max-back-fixed-request" \
  client/.maestro/subflows/send-max-back-fixed-request.yml

amountless_request_address="$(just bcli getnewaddress 2>&1 | sed -n '/^bcrt1/p' | tail -n 1)"
if [[ ! "$amountless_request_address" =~ ^bcrt1 ]]; then
  printf 'Could not generate a regtest Bitcoin address for the amountless-request regression.\n' >&2
  exit 1
fi

printf 'Verifying a zero-amount BIP-321 request stays on the amount composer.\n'
copy_to_simulator "bitcoin:$amountless_request_address?amount=0"
"$maestro_command" test --udid "$simulator_id" \
  --debug-output "$maestro_debug_output/amountless-request" \
  client/.maestro/subflows/send-amountless-request.yml

printf 'Verifying the exit deposit address is reachable and copyable.\n'
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  copy_to_simulator ""
fi
"$maestro_command" test --udid "$simulator_id" \
  --debug-output "$maestro_debug_output/exit-deposit-landscape" \
  client/.maestro/subflows/exit-deposit-landscape.yml

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  deposit_address="$(read_from_simulator)"
  [[ "$deposit_address" == bcrt1* ]]
  just bcli validateaddress "$deposit_address" | sed -n '/^{/,/^}/p' | jq -e '.isvalid'
fi

just bark balance
