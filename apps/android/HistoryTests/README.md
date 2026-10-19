# Native Android history checks

From the repository root (Nix supplies JDK 17):

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

`HistoryFixtureTest` runs **103 transaction-history cases + four bark-movement
cases** as individual parameterized JUnit tests. It adapts RN fixture records to
SDK records and compares every JSON field, including omitted values. Shared
fixtures are read as test resources and are not modified.

`HistoryModelTest` covers unavailable/suspended wallets, historical timestamp and
currency, daily cache keys, current-rate fallback, offline quotes, publication
before enrichment, overlapping refreshes, cancellation, observer subscription and
resubscription, block-time enrichment before merge, signed filtered CSV escaping,
and explorer variants. `WalletSessionTest` verifies that canceling a history
caller still finishes both native reads before closing the handles.

## Verification — 2026-10-19

- **339 JUnit tests passed**, no failures or skips; regtest debug APK assembled.
  Repository pre-commit checks passed (lint, React Compiler checks, **148 RN unit
  tests**, TypeScript and Rust formatting).
- Created a fresh wallet through the app on a new **NoahHistoryM4**, Android 15
  Google APIs ARM64 Pixel 6 AVD, ultimately using **emulator-5580**. The initial
  port 5560 had a stale agent-device claim; only this new AVD was moved. No reserved
  emulators or physical devices were used.
- Received **1,234 sats over Ark**, **2,500 sats over Lightning**, and **50,000 sats
  onchain**, using the existing regtest services. Ark and Lightning completion
  appeared in the app; Home and History reflected the receipts.
- Unconfirmed onchain sorted first. After exactly `just generate 1`, the row
  updated to **Confirmed at block 188**. All, Lightning, Ark and Onchain filters,
  refresh action and pull gesture were exercised with these real payments.
- Lightning detail showed **$2.05**, **Completed**, and **105 sats Ark fee**. Its
  historical quote came through the generated authenticated Kotlin client.
  Expanded technical details, transaction-ID copy, chain confirmation/raw
  transaction fields and the explorer action were inspected.
- Ark-filtered export opened the Android share sheet (CSV attachment, Gmail,
  Drive and Quick Share targets). Read the cache file and parsed the CSV:
  **exactly one row**, Ark / Successful / Incoming / **1234 sats**, with historical
  BTC price **81994 USD**. No message was sent to an external recipient.
- Phone detail sheet and landscape two-pane layout were checked, including
  selection surviving rotation in both directions and dark appearance. Home
  exposes the recent three rows, View all and the same detail surface.
- The AVD and Android build outputs were deleted after verification. No screenshots
  or payment identifiers are committed; services and ports 3000/3100 were not changed.

## Integration and parity

`WalletSession.transactionHistory()` makes finite counted reads of Ark movements
and onchain transactions. `HistoryModel` separately observes session-owned state
changes while the app is STARTED; it stops on background/cancellation and
resubscribes on `resyncRequired`. A closed or suspended wallet is recoverable and
clears stale history. No provider exceptions or full identifiers are logged.

Board/offboard merge precedence, labels, filtering, sorting, canceled accounting,
repeat metadata and CSV values match iOS/RN fixtures. Quotes use the generated
`V0HistoricalPriceClient.historicalFiatPrice` endpoint, with the RN day/currency
cache and uncached current-rate fallback. Confirmed chain dates use Esplora when
configured; regtest instead displays the block label. Regtest explorer URLs use
the signet explorer, intentionally matching iOS/RN.

Android adaptations are Material 3 controls, a modal bottom sheet below 700 dp,
a split pane at wider widths, and a FileProvider share. The provider exposes only
`cacheDir/history-exports/`, grants read permission, and preserves files for
receivers; the next export removes files older than a day. No dependency was added.

`TransactionsScreen` and `HomeScreen` expose `(RepeatPayment) -> Unit`. `NoahApp`
retains the payload in `AppModel.repeatPayment` and selects Send. Its single
`TODO(M3)` is the Send integration point: consume the payload, use
`prefill(preferredCurrency)` to restore fiat only for a matching currency, then
clear the payload. Android Send remains a placeholder.

Not exercised live: outgoing repeat, board/offboard/canceled-exit creation
(covered by fixtures), actual foldable hardware or API 24–29. Regtest has no
Esplora date lookup; that enrichment and merge are covered by model tests.
