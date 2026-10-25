# Android emergency exit, boarding and VTXO tests

These cover Settings › Emergency exit, Board to Ark, Auto-Board and VTXOs. The JVM
tests run in `:app:testRegtestDebugUnitTest`. `ExitLiveTest` broadcasts on regtest and
is skipped unless its argument is passed. The bark-bolt-ffi stage tests that board and
estimate exits (`Ffi050LiveTest`, `Ffi070LiveTest`) are in
[SendTests](../SendTests/README.md#bark-bolt-ffi-stage-tests). iOS's counterpart is
[apps/ios/ExitTests](../../ios/ExitTests/README.md).

## What's covered

- JVM (`com.noahwallet.onchain`): `OnchainFixtureTest` reads the shared
  `exit-fee-estimate.json`, `exit-cancellation.json`, `exit-timeline.json`,
  `exit-overview-coordination.json` and `auto-boarding.json` from
  [apps/fixtures](../../fixtures/README.md). `OnchainJournalTest`,
  `OnchainOperationsTest` and `OnchainModelTest` cover durable attempts, reviews,
  overrides and recovery. `BoardPreparationsTest` covers boarding, `ExitStagesTest` the
  exit pages' phases, and `VtxoRulesTest` and `VtxoSelectionSemanticsTest` VTXOs.
- Instrumented, always on: `ExitDeviceTest` (hold submits once, claim and cancel
  rules, folds, recreation), `ExitSubmitDeviceTest` (start and claim reach a fake bark
  once, across a posture change and process death) and `RenewReviewTest`.

The fixtures come from RN's sources. Regenerate them with
`bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-exits.ts`.

## Running

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

Add `--tests 'com.noahwallet.onchain.*'` to run only these. For instrumented tests,
build `:app:assembleRegtestDebugAndroidTest` and install both APKs on your own
emulator with `adb -s`, as in [SendTests](../SendTests/README.md#live-instrumentation-tests).
Never use `./gradlew install*` or `connected*`: they target every connected device.

## Opt-in fixture gallery

`ExitGalleryTest` uses no wallet. It renders every exit page, phase, failure state and
both claim destinations in the device's current posture, theme and animation setting:

```sh
adb -s "$SERIAL" shell am instrument -w -e class com.noahwallet.onchain.ExitGalleryTest \
  -e noahExitShots <label> com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

`-e noahExitScenes warn,list` limits the scenes. Pull `files/exit-shots/<label>` with
`run-as com.noahwallet.regtest`.

## Live regtest stages

`ExitLiveTest#regtestAcceptance` runs one stage per call, on your own disposable
emulator with the local regtest stack (`just up`). Build with
`-PnoahRegtestServerURL=http://10.0.2.2:3100` (or your server). Every call needs
`-e noahM6Owner 2f7e-throwaway -e noahM6Live <stage>`:

```sh
adb -s "$SERIAL" shell am instrument -w -e class com.noahwallet.onchain.ExitLiveTest \
  -e noahM6Owner 2f7e-throwaway -e noahM6Live create com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

`create` refuses existing wallet data or a seed. It makes a throwaway wallet with
Auto-Board off and records its fingerprint, and every later stage refuses any other
wallet. Each stage writes `cache/m6-<stage>.json`. Read it with
`adb -s "$SERIAL" shell run-as com.noahwallet.regtest cat cache/m6-<stage>.json`.
`create`'s file holds the deposit address. Fund it with `just send-to <address> <btc>`
and mine with `just generate <n>`.

| Stage | What it does |
| --- | --- |
| `funded` | Syncs and requires at least 100,000 confirmed sats |
| `board` | Reviews and boards 30,000 sats. Mine three blocks (`required_board_confirmations` in `scripts/captaind.toml`) |
| `board-all` | Boards every confirmed coin (MAX), leaving no confirmed onchain funds |
| `refresh` | Refreshes one refreshable VTXO |
| `estimate` | Read-only exit estimate for all eligible VTXOs; exits and transactions unchanged |
| `start` | Starts exits for every eligible VTXO and remembers their ids |
| `status` | Records the started exits' states, timelines and claimable heights |
| `inspect` | Records what the exit pages read: states, packages, fee children, the waiting package's fee |
| `ledger` | Records auto-board's handled ledger and whether it would offer anything |
| `progress` | Reviewed manual progress of the open exits (mine past the timelock first) |
| `claim` | Reviewed claim of claimable exits to a new address in this wallet |
| `claimed` | Like `status`, and requires every started exit to be claimed |
| `cancel-cycle`, then `cancel-reopen` | Starts and cancels exits with bark's daemon off, then checks the canceled details after reopening |

Only `cancel-cycle` opens the wallet without bark's daemon, so cancellation wins
before the final broadcast. In every other stage the daemon may broadcast and fee-bump
started exits. Mutation stages journal before they call the SDK and never clear an
uncertain attempt. Read each stage's evidence before the next stage. A skipped result
means the argument was missing; pass means the single test passed.
