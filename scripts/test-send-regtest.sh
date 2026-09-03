#!/usr/bin/env bash

set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maestro_command="${MAESTRO_COMMAND:-maestro}"
maestro_debug_output="${MAESTRO_DEBUG_OUTPUT:-client/maestro-debug-output/send-funded}"
simulator_id="${SIMULATOR_ID:-}"

if [[ -z "$simulator_id" ]]; then
  simulator_id="$(xcrun simctl list devices booted | awk -F '[()]' '/Booted/ { print $2; exit }')"
fi
if [[ -z "$simulator_id" ]]; then
  printf 'No booted iOS simulator found.\n' >&2
  exit 1
fi

cd "$project_root"

"$maestro_command" test --udid "$simulator_id" \
  --debug-output "$maestro_debug_output/prepare" \
  client/.maestro/subflows/prepare-funded-send-ios.yml

simulator_ark_address="$(xcrun simctl pbpaste "$simulator_id" | tr -d '\r\n')"
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

printf '%s' "$bark_ark_address" | xcrun simctl pbcopy "$simulator_id"
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
printf 'bitcoin:%s?amount=0.00005' "$fixed_request_address" | xcrun simctl pbcopy "$simulator_id"
"$maestro_command" test --udid "$simulator_id" \
  --debug-output "$maestro_debug_output/max-back-fixed-request" \
  client/.maestro/subflows/send-max-back-fixed-request.yml

amountless_request_address="$(just bcli getnewaddress 2>&1 | sed -n '/^bcrt1/p' | tail -n 1)"
if [[ ! "$amountless_request_address" =~ ^bcrt1 ]]; then
  printf 'Could not generate a regtest Bitcoin address for the amountless-request regression.\n' >&2
  exit 1
fi

printf 'Verifying a zero-amount BIP-321 request stays on the amount composer.\n'
printf 'bitcoin:%s?amount=0' "$amountless_request_address" | xcrun simctl pbcopy "$simulator_id"
"$maestro_command" test --udid "$simulator_id" \
  --debug-output "$maestro_debug_output/amountless-request" \
  client/.maestro/subflows/send-amountless-request.yml

just bark balance
