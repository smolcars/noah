# Native iOS history checks

## Coverage

- `HistoryFixtureTests` runs the shared `transaction-history.json`, `bark-movement.json`
  and `history-regressions.json` cases: all known subsystems, failed/pending/canceled
  movements, BDK mapping, merge precedence, sorting, and repeat metadata/routing. It
  adapts the fixture's RN records to the SDK's typed `MovementDestination` records.
- `HistoryModelTests` covers wallet-not-open handling, observer refresh/cancellation,
  late enrichment, historical timestamps/currencies, the daily rate cache, uncached
  current-rate fallback, filtered signed CSV values/escaping, and explorer variants.

## Running

Generate with `xcodegen generate --spec apps/ios/project.yml`, then use a dedicated
simulator and worktree-local DerivedData:

```sh
nix develop --command xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$NOAH_HISTORY_UDID" \
  -derivedDataPath apps/ios/DerivedData/History -parallel-testing-enabled NO \
  test -skip-testing:NoahTests/UpgradeCompatibilityTests
```

The RN-derived fixtures (`transaction-history.json`, `bark-movement.json`) are frozen from the React Native app and edited by hand; keep both platforms' tests passing.

Their generator executed `transactionHistory.ts`, `barkMovement.ts`, `repeatPayment.ts`
and the pure transforms in `useTransactions.ts`; React/native/network imports were
removed from the latter, and the transform code itself was not copied. It wrote
`transaction-history.json` and `bark-movement.json`, not `history-regressions.json`.

## Manual regtest check

With the regtest stack running, receive over Ark, Lightning and on-chain on a dedicated
simulator, then mine with `just generate 1` and refresh: unconfirmed on-chain rows sort
first, and confirmed rows show the block label (regtest has no Esplora date lookup).
Exercise the filters, CSV export and the detail sheet/wide pane. Don't commit
screenshots or payment identifiers.
