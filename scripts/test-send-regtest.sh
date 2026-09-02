#!/usr/bin/env bash

set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maestro_command="${MAESTRO_COMMAND:-maestro}"
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
  client/.maestro/subflows/prepare-send-wallet-ios.yml

simulator_ark_address="$(xcrun simctl pbpaste "$simulator_id" | tr -d '\r\n')"
if [[ ! "$simulator_ark_address" =~ ^tark1 ]]; then
  printf 'Expected a regtest Ark address in the simulator clipboard, got: %s\n' \
    "$simulator_ark_address" >&2
  exit 1
fi

printf 'Funding simulator address %s with 100000 sats from Bark.\n' "$simulator_ark_address"
just bark send-to "$simulator_ark_address" "100000 sats"

bark_ark_address="$(just bark address 2>&1 | sed -n '/^tark1/p' | tail -n 1)"
if [[ ! "$bark_ark_address" =~ ^tark1 ]]; then
  printf 'Could not generate a regtest Bark address.\n' >&2
  exit 1
fi

printf 'Sending 5000 sats from the simulator to Bark address %s.\n' "$bark_ark_address"
"$maestro_command" test --udid "$simulator_id" \
  -e "BARK_ARK_ADDRESS=$bark_ark_address" \
  client/.maestro/subflows/send-funded-ark.yml

just bark balance
