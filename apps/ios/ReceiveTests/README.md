# Native iOS Receive checks

Generate the project with `xcodegen generate --spec apps/ios/project.yml`. Use a dedicated
simulator and worktree-local DerivedData, then run:

```sh
xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$NOAH_RECEIVE_UDID" \
  -derivedDataPath apps/ios/build/DerivedData \
  -parallel-testing-enabled NO test -skip-testing:NoahTests/UpgradeCompatibilityTests
```

The shared URI and fiat fixtures execute RN's real TypeScript implementations:

```sh
nix develop --command bun --tsconfig-override client/tsconfig.json \
  apps/fixtures/tools/generate-receive.ts
```

`ReceiveFixtureTests` checks URI precision, amount validation, fiat conversion/rounding,
and the UTF-16 note limit. `ReceiveCompletionTests` checks destination sums, unrelated
payments, pending/failed/canceled Lightning movements and unavailable sessions.
`ReceiveModelTests` checks late invoices, receipts during invoice generation, removing
an amount, and wallet-not-ready recovery.

## Regtest verification (2026-10-19)

The final run passed **125 tests in 32 suites**, with environment-gated integration
suites skipped and UpgradeCompatibilityTests excluded. All commit hooks passed
(client lint, React Compiler checks, 148 RN unit tests, TypeScript, and Rust formatting).

On the dedicated Noah Receive Agent simulator (iPhone 17, iOS 27.0):

- Created a wallet through onboarding, opened Receive, copied its Ark address and paid
  **1,234 sats** from the local Bark CLI wallet. The open copy sheet dismissed, the
  success screen showed 1,234 sats and the balance updated to 1,234 sats.
- Entered **2,500 sats** and `Native receive test` in the amount/note sheet. Amount and
  Next stayed visible above the keyboard; the note appeared on the resulting request.
- Decoded a screenshot of the amountful QR with Apple's Vision framework, including
  its center logo. The decoded amount and BOLT11 matched the copied payment details.
- Paid that invoice from the local Bark CLI wallet. The success screen showed 2,500 sats
  and the wallet balance updated to **3,629 sats** (2,395 sats net after Lightning fees).
  As in RN, success displays the requested Lightning amount, while balance includes fees.
- Opened the native share sheet in dark mode without sending anything. Fiat entry
  converted $1.00 to 1,220 sats at the fetched rate; removing the amount restored the
  amountless request.
- Direct `just cln pay` returned code 203, `Unknown invoice`, and `listpays` confirmed
  failure before the successful Bark attempt. No blocks were mined for either receipt.

The allowed `just bark balance` command unexpectedly recreated bitcoind, Postgres and
captaind: its wrapper uses `docker-compose run` with dependencies enabled. No volumes
were reset. Remaining Bark commands used
`docker-compose -f scripts/docker-compose.yml run --rm --no-deps bark bark ...`.
Use that form against an already-running shared stack to avoid dependency restarts.

## Parity and limits

FFI 0.2.0 supplies destination amounts and payment hashes, but no RN-style filtered
subscription helpers. `WalletSession` filters its general notifications and reconciles
history on subscribe/channel lag; transient subscription failures retry. No FFI change
is required. On-chain receipts do not trigger success, matching the RN screen.

Native adaptations: scrollable system-keyboard amount/note sheet, system share sheet,
and a spring/checkmark success animation (Reduce Motion supported). Success stays in
the Receive tab until Done returns Home. Receive amounts and success balance remain
visible even when Home balance privacy is enabled, matching RN.

QRHub, Android, physical-device camera scanning, and an iOS 18 runtime check are outside
this verification. Build output and screenshots are temporary and must not be committed.
