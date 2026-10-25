# iOS Send tests

Send's unit tests run with the rest of `NoahTests`. The live suites below spend regtest
coins and stay off unless their environment variable is set. Emergency exits, boarding
and VTXOs are in [ExitTests](../ExitTests/README.md); Android's counterpart is
[apps/android/SendTests](../../android/SendTests/README.md).

## What's covered

- Parsing and composing: `SendFixtureTests`, `SendAmountTests`, `SendDestinationNativeTests`,
  `SendOfferTests`, `SendLnurlClientTests`, `SendSuggestionQueryTests`, `PaymentLinkTests`,
  `PaymentLinkLifecycleTests`.
- Submission safety and recovery: `SendSafetyTests`, `SendPaymentTests`, `SendPreparedTests`,
  `SendDiscardTests`, `SendRecipientSafetyTests`, `SendLnurlSafetyTests`,
  `SendLightningSafetyTests`, `SendLightningRetryTests`, `SendReceiptRestartTests`,
  `SendJournalMigrationTests`, `SendOverrideAuditTests`, `SendOffboardReceiptTests`,
  `SendMovementIdentityTests`, `SendWalletIdentityTests`, `SendCheckpointAdoptionTests`,
  `SendRoundSevenTests`, `ChainTransactionStatusTests`.
- Presentation: `SendMotionTests`, `SendSuccessDetailsTests`, `SendRepeatParityTests`,
  `PaymentParityTests`.

`SendFixtureTests` reads `send-flow.json`, `send-utils.json`, `onchain-send.json` and
`lightning-invoice.json` from [apps/fixtures](../../fixtures/README.md). They come from RN's
own implementations; regenerate them from the repository root:

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-send.ts
```

Some invoices in `send-utils.json` have altered headers for decoder tests. Their
signatures are invalid: never submit them as payments.

## Running

Use your own simulator and DerivedData, never another developer's device:

```sh
cd apps/ios
xcodegen generate
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$SIMULATOR" \
  -derivedDataPath /private/tmp/noah-send-dd -parallel-testing-enabled NO \
  CODE_SIGN_IDENTITY=- test
```

- Keep simulator signing on. `CODE_SIGNING_ALLOWED=NO` drops the Keychain entitlements,
  and the Keychain tests then fail.
- `-only-testing:NoahTests/SendSafetyTests` runs one suite. A single Swift Testing
  function needs its trailing `()`, quoted:
  `'-only-testing:NoahTests/FFI050LiveTests/acceptance()'`.
- Add `-resultBundlePath <path>.xcresult`, then read the counts with
  `xcrun xcresulttool get test-results summary --path <path>.xcresult`.

In a normal run, every live and screenshot suite shows as skipped. Tests that open a
real bark wallet also skip when the regtest bitcoind isn't reachable;
`NOAH_REGTEST_LIVE=0` always skips them and `=1` always runs them.

## Live regtest suites

They need the local regtest stack (`just up`, or `just setup-everything` for a fresh
one) and a fresh, disposable simulator running the `Noah-Regtest` build.

- Pass each variable to `xcodebuild` with a `TEST_RUNNER_` prefix (for example
  `TEST_RUNNER_NOAH_SEND_ROUND2_LIVE=1`), and select the suite with `-only-testing`.
- The tests talk to the host through files in the app's Documents folder:
  `"$(xcrun simctl get_app_container "$SIMULATOR" com.noahwallet.regtest data)/Documents"`.
  They publish funding addresses there, wait for marker files you create, and write
  non-secret evidence JSON.
- Fund and mine with the repo's wrappers: `just send-to <address> <btc>`, `just bcli`,
  `just bark`, `just cln`, `just lncli`, `just generate 1`.
- Fund each published address once. The tests never retry a submission. If one fails
  or times out, inspect the receipt and history before doing anything else, and never
  rerun a creating test in a wallet that already holds funds or a journal.

| Suite (`-only-testing`) | Variables | Host steps | Writes |
| --- | --- | --- | --- |
| `SendLiveTests` | `NOAH_SEND_LIVE_STALE=1`, `NOAH_SEND_ROUTE_A`, `NOAH_SEND_ROUTE_B` (two different fresh Ark addresses) | Needs an existing funded regtest wallet on the simulator. Spends 1,000 sats | — |
| `SendRoundTwoLiveTests` | `NOAH_SEND_ROUND2_LIVE=1`, `NOAH_SEND_ARK_RECIPIENT`, `NOAH_SEND_INVOICE` (fresh 2,000-sat BOLT11) | Within 120 s, send at least 10,000 sats to the address in `send-round2-funding-address.txt`, e.g. `just bark send "<address>" "10000 sats"` | — |
| `SendRoundThreeLiveTests` | `NOAH_SEND_ROUND3_LIVE=1`, `NOAH_SEND_INVOICE` (fresh **amountless** BOLT11), `NOAH_SEND_ONCHAIN_RECIPIENT` (Core address) | Within 180 s, fund both addresses in `send-round3-funding.json` once and mine one block. Confirm the review in the app, then create `send-round3-finish` | `send-round3-evidence.json` |
| `SendRoundFourLiveTests/arkReceiptBeforeDone()` | `NOAH_SEND_ROUND4_LIVE=1`, `NOAH_SEND_ARK_RECIPIENT` | Within 180 s, fund `send-round4-funding-address.txt` once with 20,000 sats. On `send-round4-ark-ready`, kill and relaunch the app before Done and check the receipt, then create `send-round4-finish` | `send-round4-ark-evidence.json` |
| `SendRoundFiveLiveTests` | `NOAH_SEND_ROUND5_LIVE=1`, `NOAH_SEND_INVOICE` (fresh fixed 2,000-sat hold invoice) | Run `createHeldCheckpoint()`, fund `send-r5-fund.txt` once with 20,000 sats, and make sure the receiver holds the HTLC before the app ends. Then run `adoptHeldCheckpointAfterRelaunch()` and settle the invoice after `send-r5-adopted-ready` | `send-r5-adopted.json`, `send-r5-receipt.json` |
| `SendPreparedLiveTests` | `NOAH_PREPARED_LIVE=1`, `NOAH_PREPARED_RECIPIENT` (Core address) | Fund `prepared-fund.txt` with two confirmed UTXOs totalling 100,000 sats. Tap Back on the first review and Confirm on the second, create `prepared-max-go` once the entered receipt checks out, then confirm MAX and kill/relaunch before Done | `prepared-{cancel-review,entered-review,entered-receipt,max-review,max-receipt}.json` |

`SendRoundTwoLiveTests` through `SendPreparedLiveTests` create their own wallet, so
they need an empty regtest wallet directory. Funding has to arrive before each test's
deadline.

## bark-bolt-ffi stage suites

`FFI050LiveTests`, `FFI060LiveTests` and `FFI070LiveTests` each have one `acceptance()`
test. Each run executes one stage, named in `TEST_RUNNER_NOAH_FFI0x0_STAGE`. The host
funds, pays and mines between stages, and each stage writes non-secret
`ffi0x0-<stage>.json` evidence (plus small handoff files) to Documents.

- **FFI050** (default stack): `create` (or `auto-setup`, with Auto-Board on), `sync`,
  `amount`, `max`, `auto`, `board-status`, `offboard` (needs `NOAH_FFI050_RECIPIENT`, a
  fresh Core address), `confirmed`, `cancel`, `reopen`. The board stages show the real
  review. Approve it in the app.
- **FFI060**: `create`, `receive` (pay the onchain address and mine a block; the stage
  writes `ffi060-invoice.txt` for the host to pay over Lightning), `ark`, `ln-send`,
  `ln-probe`, `ln-fail` (each with `NOAH_FFI060_INVOICE`; for `ln-fail` hold the invoice
  and then cancel it), `offboard` (`NOAH_FFI060_RECIPIENT`, a Core address),
  `offboard-confirmed` (after mining one block), `reopen`, `upgrade`.
- **FFI070**: `upgrade`, `autoboard`, then `exit-all` after the board's three
  confirmations (`required_board_confirmations` in `scripts/captaind.toml`). Nothing
  here starts an exit.

FFI060 and FFI070 target a Bark 0.8 stack. If that stack doesn't use the default
ports, pass its endpoints to `xcodebuild` as build settings (see
`apps/ios/Config/Regtest.xcconfig`), e.g.
`NOAH_REGTEST_ARK_URL=http://127.0.0.1:13535 NOAH_REGTEST_BITCOIND_URL=http://127.0.0.1:28443 NOAH_REGTEST_SERVER_URL=http://127.0.0.1:13000`.

### Upgrading from the previous SDK build

The `upgrade` stages check that a wallet made by the previous bark-bolt-ffi build
opens unchanged. For FFI070 that is the 0.6.0 build:

1. In a scratch worktree, check out the parent of the commit that pinned the current
   SDK (`git log -S'exactVersion: 0.7.0' --format=%h -- apps/ios/project.yml`) and
   build it for the same simulator.
2. Create a wallet with that build (`FFI060LiveTests`, stage `create`) and fund it
   with at least two confirmed onchain coins plus an Ark balance (`receive`).
   `autoboard` needs two confirmed coins.
3. Write `Documents/ffi070-upgrade-baseline.json` from that wallet, with the keys the
   stage compares: `fingerprint`, `spendable`, `onchainConfirmed`, `movements`, `utxos`
   and `vtxos`, in the formats `FFI070LiveTests` reads.
4. Install the current build over it. Don't erase the simulator. Then run `upgrade`,
   `autoboard`, mine three blocks, and run `exit-all`.

FFI060's `upgrade` stage works the same way, from the 0.5.0 build, with
`ffi060-upgrade-baseline.json` (`fingerprint`, `spendable`, `movements`).

## Screenshot fixtures

`SendReceiveShotTests` (`NOAH_SR_SHOTS=1`) shows the real Send and Receive screens with
scripted data. It uses no wallet, server or recovery phrase. Write a state name to
`Documents/sr-shot`, wait for `sr-shot-ready`, and take the screenshot. `stop` ends the
session. The states are listed in the test's header comment.

## Reading results

A live suite passes when its single selected test passes. Read its Documents evidence
before mining or paying for the next stage. The evidence holds amounts, txids and
receipts, never seeds. Runtime destinations, invoices, wallet data and screenshots stay
out of the repo.
