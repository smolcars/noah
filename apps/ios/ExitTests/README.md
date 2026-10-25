# iOS emergency exit, boarding and VTXO tests

These cover Settings › Emergency exit, Board to Ark, Auto-Board and VTXOs. The unit
tests run with the rest of `NoahTests`. `ExitLiveTests` broadcasts on regtest and is
off unless `NOAH_M6_LIVE_STAGE` is set. The bark-bolt-ffi stage suites that board and
estimate exits are in [SendTests](../SendTests/README.md). Android's counterpart is
[apps/android/ExitTests](../../android/ExitTests/README.md).

## What's covered

- Shared RN fixtures: `ExitFixtureTests` reads `exit-fee-estimate.json`,
  `exit-cancellation.json`, `exit-timeline.json`, `exit-overview-coordination.json`
  and `auto-boarding.json`.
- Journal, review and recovery: `OnchainJournalTests`, `OnchainModelTests`,
  `OnchainRecoveryTests`, `OnchainOverrideTests`, `OnchainOrphanBoardTests`,
  `OnchainReceiptFeeTests`, `OnchainChangesTests`, `ExitSDKPolicyTests`,
  `ExitFallbackModelTests`, `ExitFallbackDeadlineTests`, `ExitTypedErrorTests`.
- Exit pages and claims: `ExitProgressTests`, `ExitHoldTests`, `ExitSceneTests`,
  `ExitRestartTests`, `ExitHistoryTests`, `ExitClaimLedgerTests`, `ExitClaimRecordTests`,
  `ExitClaimAutoBoardTests`, `ExitClaimDestinationTests`,
  `ExitClaimDestinationModelTests`, `ExitClaimExecutionTests`.
- Boarding and VTXOs: `BoardPreparedTests`, `BoardReviewLifetimeTests`,
  `RenewSubmissionTests`, `VtxoListTests`.

The fixtures come from RN's own sources. Regenerate them from the repository root:

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-exits.ts
```

## Running

Use your own simulator and DerivedData:

```sh
cd apps/ios
xcodegen generate
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$SIMULATOR" \
  -derivedDataPath /private/tmp/noah-exit-dd -parallel-testing-enabled NO \
  CODE_SIGN_IDENTITY=- test
```

Keep simulator signing on: `CODE_SIGNING_ALLOWED=NO` removes the Keychain
entitlements, and the Keychain tests then fail. Select a suite with
`-only-testing:NoahTests/<Suite>`. Read results as for Send (see
[Running](../SendTests/README.md#running)). In a normal run, `ExitLiveTests` and
`ExitShotTests` show as skipped.

## Live regtest stages

`ExitLiveTests.regtestAcceptance()` runs one stage per invocation. It needs a fresh,
disposable simulator, the local regtest stack (`just up`) and the full function ID:

```sh
TEST_RUNNER_NOAH_M6_LIVE_STAGE=create xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$SIMULATOR" \
  -derivedDataPath /private/tmp/noah-exit-dd -parallel-testing-enabled NO \
  CODE_SIGN_IDENTITY=- '-only-testing:NoahTests/ExitLiveTests/regtestAcceptance()' test
```

`create` refuses an existing wallet or seed. It makes a throwaway wallet with
Auto-Board off and writes `m6-live-owned-wallet.txt` (its fingerprint) and
`m6-live-address.json` (a deposit address) to the app's Documents
(`xcrun simctl get_app_container "$SIMULATOR" com.noahwallet.regtest data`). Every
later stage refuses a wallet whose fingerprint doesn't match. Fund the address with
`just send-to <address> <btc>` and mine with `just generate <n>`.

| Stage | What it does |
| --- | --- |
| `board` | Boards exactly 30,000 sats after review. Mine three blocks (`required_board_confirmations` in `scripts/captaind.toml`) |
| `board-missing-fee`, then `reconcile-fee` | Boards 30,000 sats with the receipt's fee removed, then checks that the model fills the fee by txid without a new transaction |
| `uncertain-board`, then `check-uncertain-board` | Boards 30,000 sats with the final receipt dropped, then checks that Check status resolves it without a second board |
| `refresh` | Refreshes one spendable VTXO |
| `estimate` | Read-only exit estimate; exit list and transactions unchanged |
| `start` | Starts an exit for one eligible VTXO of 30,000 sats or less; refuses when exits exist |
| `observe` | Lets the daemon progress the exits, then records their state |
| `progress` | Reviewed manual progress of the open exits (mine 144 blocks first for the timelock) |
| `claim` | Reviewed claim of claimable exits to a new address in the same wallet |
| `max` | Boards everything (`boardAll`) and records the actual fee |
| `cancel-cycle`, then `cancel-reopen` | Starts and cancels an exit with the daemon off, then reopens and checks the canceled detail |

`estimate`, the fee and uncertain-board stages and the cancel stages open the wallet
without bark's daemon. The others reopen it with the daemon running, so the daemon
may broadcast and fee-bump exits that were started. Mutation stages journal before
they call the SDK and never clear an uncertain attempt. Use them only on a disposable
simulator, and never repeat a stage without reading its evidence first.

Each stage writes `m6-live-<stage>.json` (plus `m6-live-board.json`,
`m6-live-claim-receipt.json`, `m6-live-max.json`, `m6-live-canceled-id.txt` and so on)
to Documents. Pass means the single selected test passed.

## Screenshot fixtures

`ExitShotTests` (`NOAH_EXIT_SHOTS=1`) shows the real exit pages in Settings' stack with
fake wallet data. Write a state name (listed in the test's header comment) to
`Documents/exit-shot`, wait for `exit-shot-ready`, and take the screenshot. `stop` ends
the session. Light and dark follow the simulator. A wide window shows the panes.
