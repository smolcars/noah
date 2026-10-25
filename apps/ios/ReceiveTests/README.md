# Native iOS Receive checks

## Coverage

- `ReceiveFixtureTests`: URI precision, amount validation, fiat conversion/rounding
  and the UTF-16 note limit, from the shared `receive-request.json` and
  `fiat-currency.json` fixtures.
- `ReceiveCompletionTests`: destination sums, unrelated payments, pending/failed/canceled
  Lightning movements and unavailable sessions.
- `ReceiveModelTests`: late invoices, receipts during invoice generation, removing an
  amount, and wallet-not-ready recovery.

## Running

Generate the project with `xcodegen generate --spec apps/ios/project.yml`. Use a dedicated
simulator and worktree-local DerivedData, then run:

```sh
xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$NOAH_RECEIVE_UDID" \
  -derivedDataPath apps/ios/build/DerivedData \
  -parallel-testing-enabled NO test -skip-testing:NoahTests/UpgradeCompatibilityTests
```

Environment-gated integration suites report as skipped unless their variables are set.

The shared URI and fiat fixtures execute RN's real TypeScript implementations.
Regenerate them with:

```sh
nix develop --command bun --tsconfig-override client/tsconfig.json \
  apps/fixtures/tools/generate-receive.ts
```

## Manual regtest check

On a dedicated simulator with the regtest stack running, create a wallet, open Receive
and pay the Ark address or a BOLT11 request from the stack's Bark wallet
(`just bark ...`). Success shows the requested Lightning amount; the balance reflects
fees, as in RN. On-chain receipts do not trigger the success screen. Build output and
screenshots are temporary and must not be committed.
