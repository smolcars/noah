---
name: ark-regtest-controller
description: Control Noah's Docker-backed Ark regtest environment for local app and simulator testing. Use when Codex needs to generate Ark or Bitcoin addresses, create or pay Lightning invoices, send Ark or on-chain regtest funds between a Noah simulator and the local Bark or Bitcoin Core wallets, mine blocks, inspect transaction state, or run Bark, ASP, bitcoin-cli, LND, or CLN commands through the repository's just recipes.
---

# Ark Regtest Controller

Operate Noah's local Ark regtest stack from the repository root. Use the `just` recipes backed by `scripts/ark-dev.sh`; bypass them only when diagnosing the wrappers themselves.

## Protect local state

- Stay on regtest. Never substitute signet or mainnet endpoints, invoices, or addresses.
- Inspect service state before changing it:

  ```bash
  docker-compose -f scripts/docker-compose.yml ps
  ```

- `just setup-everything` will fund all necessary wallets and set up the stack.
- Use `just up` when an initialized stack is merely stopped. Use `just stop` when services should stop without deleting data.
- Obtain explicit confirmation before running any state-resetting command unless the user directly requested that reset:
  - `just down` removes the stack's Docker volumes.
  - `just create-bark-wallet` deletes the existing Bark wallet data before creating a wallet.

## Establish readiness

1. Confirm the working directory contains `justfile` and `scripts/ark-dev.sh`.
2. Inspect the Docker services with the read-only command above.
3. Start an already-initialized stack with `just up` only if required.
4. Probe the component needed by the test with a read-only command, such as:

   ```bash
   just bcli getblockchaininfo
   just bark balance
   ```

5. If a pass-through subcommand is unfamiliar, inspect its help first, for example `just bark --help` or `just lncli help`. Do not invent flags from a different Bark or Lightning version.

If setup is missing, report that `just setup-everything` is available and explain that it replaces Bark wallet state before seeking confirmation.

## Preserve amount units

Treat the amount syntax as part of the command contract:

| Rail                   | Required form           | Example       |
| ---------------------- | ----------------------- | ------------- |
| Ark through Bark       | quoted amount with unit | `"1234 sats"` |
| Lightning through Bark | quoted amount with unit | `"1234 sats"` |
| Bitcoin Core on-chain  | decimal BTC             | `0.01`        |
| Block generation       | integer block count     | `6`           |

Never silently convert between sats and BTC. Ask when the requested amount or rail is ambiguous.

## Exercise payment flows

Copy dynamic addresses and invoices exactly. Confirm the destination and amount before executing a payment.

### Send Ark funds from Bark to the simulator

1. Read or generate an Ark receive address in the simulator.
2. Pay it from the Bark wallet:

   ```bash
   just bark send-to "<ark-address>" "1234 sats"
   ```

3. Verify the command result and the simulator's balance or activity entry.

### Send Ark funds from the simulator to Bark

1. Generate the local Bark wallet's Ark address:

   ```bash
   just bark address
   ```

2. Copy the returned address into the simulator and send the requested sats amount.
3. Verify both the simulator result and Bark's resulting state. Use `just bark balance` or the relevant read-only Bark command supported by `just bark --help`.

### Pay a simulator Lightning invoice from Bark

1. Create the invoice in the simulator and copy the complete BOLT11 value.
2. Pay it:

   ```bash
   just bark ln pay "<bolt11-invoice>"
   ```

3. Verify the terminal result and the simulator's received-payment state.

### Pay a Bark Lightning invoice from the simulator

1. Create a fixed-amount invoice:

   ```bash
   just bark ln invoice "1234 sats"
   ```

2. Copy the returned invoice into the simulator and pay it.
3. Verify the simulator result and Bark's resulting payment state.

Do not mine blocks for a normal Lightning payment. Mine only when a test explicitly needs chain progress or channel confirmation.

### Send on-chain bitcoin from Bitcoin Core to the simulator

1. Read or generate a regtest on-chain receive address in the simulator.
2. Send a BTC-denominated amount:

   ```bash
   just send-to "<bitcoin-address>" 0.01
   ```

The wrapper creates or loads Bitcoin Core's `dev-wallet`, sends the transaction, and mines one confirming block. Do not mine another block unless the test needs additional confirmations.

### Send on-chain bitcoin from the simulator to Bitcoin Core

1. Generate a destination in Bitcoin Core:

   ```bash
   just bcli getnewaddress
   ```

2. Send to that address from the simulator.
3. Confirm the transaction when needed:

   ```bash
   just generate 1
   ```

4. Verify both the app activity and the Core wallet state.

### Advance the regtest chain

Mine the exact number of blocks the scenario requires:

```bash
just generate <number>
```

`just generate` defaults to 101 blocks when no number is supplied, so always pass an explicit count during tests.

## Use advanced pass-through commands

- Run Bark CLI commands with `just bark <args...>`.
- Run Ark service-provider (`captaind`) commands with `just aspd <args...>`. The actual recipe is `aspd`, even if a request calls it “asp.”
- Run Bitcoin Core CLI commands against the `dev-wallet` with `just bcli <args...>`.
- Run LND commands on regtest with `just lncli <args...>`.
- Run Core Lightning commands on regtest with `just cln <args...>`.

Prefer the higher-level payment recipes above for routine simulator flows. Use these pass-through commands for inspection, diagnostics, or operations the wrappers do not expose directly.

## Verify without duplicating payments

- Treat addresses, invoices, transaction IDs, and payment hashes as runtime values; never reuse an old value unless the scenario requires it.
- After sending, inspect the command result and the receiving wallet or simulator. A zero exit code alone does not prove the UI processed the payment.
- Allow a bounded wait for background sync, then recheck. Do not resend merely because the UI is slow.
- Before retrying a failed or timed-out send, determine whether the first attempt produced a transaction ID, payment hash, or receiver-side activity.
- Report the rail, amount, sender, receiver, relevant non-secret identifier, confirmation action, and observed result.
