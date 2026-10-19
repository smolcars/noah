# Native iOS history checks

Generate with `xcodegen generate --spec apps/ios/project.yml`, then use a dedicated
simulator and worktree-local DerivedData:

```sh
nix develop --command xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$NOAH_HISTORY_UDID" \
  -derivedDataPath apps/ios/DerivedData/History -parallel-testing-enabled NO \
  test -skip-testing:NoahTests/UpgradeCompatibilityTests
```

Regenerate the shared fixtures without native wallet dependencies:

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-history.ts
```

The generator executes `transactionHistory.ts`, `barkMovement.ts`, `repeatPayment.ts`
and the actual pure transforms in `useTransactions.ts`. React/native/network imports
are removed from the latter; the transform code itself is not copied or rewritten.
The fixtures contain 103 history cases and four movement-status cases, including the
original unit scenarios, all known subsystems, failed/pending/canceled movements,
BDK mapping, merge precedence, sorting, and repeat metadata/routing. Swift tests
adapt the fixture's RN records to the SDK's typed `MovementDestination` records.

`HistoryModelTests` covers wallet-not-open handling, observer refresh/cancellation,
late enrichment, historical timestamps/currencies, the daily rate cache, uncached
current-rate fallback, filtered signed CSV values/escaping, and explorer variants.

## Verification (2026-10-19)

- Full suite: **166 tests in 37 suites passed**; UpgradeCompatibilityTests excluded.
  Environment-gated integration suites remained disabled. Final focused run:
  **11 tests in two suites passed**, including all 107 JSON cases.
- Created a fresh wallet through onboarding on **Noah History Agent**, iPhone 17,
  iOS 27.0. Used only that simulator and worktree-local DerivedData.
- Received **1,234 sats over Ark**, **2,500 sats over Lightning**, and **50,000 sats
  onchain**. Home updated after receipt; the history showed all three rails. The
  Lightning receipt displayed $2.05 and its 105-sat Ark fee.
- Unconfirmed onchain activity sorted first. After `just generate 1` and refresh,
  it displayed **Confirmed at block 183**. Ark, Lightning, and Onchain filters were
  exercised against the real payments.
- Exported the **Ark-filtered** list through ShareLink → Save to Files. Read the
  exported CSV and verified exactly one row: Ark / Successful / Incoming / 1234 sats,
  historical BTC price **82032 USD**. No unrelated rows were exported.
- Opened the phone detail sheet and the inline detail pane in landscape (wide
  layout). Pull-to-refresh and technical-details expansion were exercised.
  Verified readable VoiceOver copy labels and preserved selection when rotating
  the phone sheet into the wide pane, including dark appearance.
  Historical prices came from the running server's generated Swift client.
- Used the existing regtest services and `just bark`'s `--no-deps` wrapper. No
  services, containers with persistent state, ports, or volumes were restarted.
  The current CLI uses `bark send`; the skill's old `send-to` was rejected before
  any payment. Screenshots and payment identifiers are not committed.

## Integration and parity

`WalletSession.transactionHistory()` makes finite, counted reads. `HistoryModel`
observes the session-owned `stateChanges()` stream, separately from wallet calls.
BDK and movement entries are enriched before merging so the RN fee, confirmation,
and fiat fallback precedence is preserved. Regtest has no Esplora date lookup, so
confirmed onchain rows use the block label instead of inventing a timestamp.

`TransactionsView` and `HomeView` expose `onRepeatPayment: (RepeatPayment) -> Void`.
`WalletTabs` stores the request and selects Send. The single `TODO(M3)` in
`Features/Shell/RootView.swift` is the Send agent's integration point. The payload
preserves normalized destination, comment, amount mode/input, sat amount, and fiat
currency. Use `prefill(preferredCurrency:)` to restore fiat only for a matching
currency; otherwise restore sats, as RN does.

Native adaptations: system sheet / wide split pane, ShareLink, accessible amount
colors, and two-line destinations with full-value copy. CSV deliberately says
**Amount (sats)** because RN's **Amount (₿)** header contains raw satoshi values;
CSV also correctly quotes commas, quotes and newlines, and leaves missing rates
blank. Accounting signs, board transfers, offboards, and canceled zeros match RN.

Not exercised live: outgoing repeat (Send is absent on this branch), board/offboard
or canceled-exit creation (covered by fixtures), iPad-specific behavior, and iOS 18.
The wide-layout branch was exercised on the isolated iPhone in landscape.
