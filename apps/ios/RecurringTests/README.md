# Native iOS recurring payments

Tests for recurring payments: schedule math, RN storage compatibility, the
durable occurrence journal, the executor and its triggers, plus two opt-in live
regtest payments. Android's equivalent is
[apps/android/RecurringTests/README.md](../../android/RecurringTests/README.md).

## What the suites cover

All suites live in `NoahTests`:

- **Schedule math and fee policy:** `RecurringScheduleTests`,
  `RecurringTimestampTests`, `RecurringFeePolicyTests`, `RecurringParityTests`.
- **RN storage compatibility:** `RecurringStoreTests`, `RecurringVersionTests`,
  `RecurringRangeTests`, `RecurringNumericGrammarTests`,
  `RecurringRNRequiredFieldsTests`, `RecurringReasonUpgradeTests`,
  `RecurringLegacyRegressionTests`.
- **Journal, settlement and replay safety:** `RecurringJournalValidationTests`,
  `RecurringJournalMigrationRaceTests`, `RecurringPolicyLedgerTests`,
  `RecurringSettlementAdmissionTests`, `RecurringSettlementDurabilityTests`,
  `RecurringSettlementRepeatTests`, `RecurringSettlementRollbackTests`,
  `RecurringOverrideSettlementTests`, `RecurringReapprovalTests`,
  `RecurringWriteFailureTests`.
- **Executor, triggers and lifecycle:** `RecurringExecutorTests`,
  `RecurringMoneyPathTests`, `RecurringForegroundRunnerTests`,
  `RecurringWakeHandoffTests`, `RecurringBackgroundRegressionTests`,
  `RecurringAdmissionRaceTests`, `RecurringReadAdmissionTests`,
  `RecurringProtectedReadRaceTests`, `RecurringReservationTests`,
  `RecurringAutomationAuthorityTests`, `RecurringRound3RegressionTests`,
  `RecurringOnboardingTests`.
- **Localization of stable reason codes:** `RecurringLocalizationTests`.
- **Opt-in live payments:** `RecurringLiveTests` and `RecurringFeeLiveTests`
  (both in `RecurringLiveTests.swift`).

Crash boundaries and races are covered by deterministic persistence, controller
and session tests. Real power loss, external LNURL/BOLT12 providers, physical
APNs/Expo delivery and 24-hour reminder delivery are not tested here.

## Fixtures

The shared fixtures are `apps/fixtures/recurring-*.json`, also read by Android.
The calendar, execution-policy, zone-change and storage fixtures are generated
by running RN's unmodified recurring code:

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-recurring.ts
```

## Running the deterministic suites

Generate the project and use a dedicated simulator (see [../README.md](../README.md)):

```sh
cd apps/ios && xcodegen generate
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$UDID" \
  -parallel-testing-enabled NO \
  -only-testing:NoahTests/RecurringExecutorTests test
```

Repeat `-only-testing:NoahTests/<Suite>` for each suite you want, or use
`-only-testing:NoahTests` for everything. Without their environment variables
the live suites report as skipped.

## Live regtest payments (opt-in)

Both live suites need the regtest stack (`just setup-everything`) and a Noah
server, and run only on a **new, dedicated simulator**. Each one creates its own
throwaway wallet, uses production services and refuses to run over an existing
wallet. Run one suite per invocation, passing variables with the
`TEST_RUNNER_` prefix:

- `TEST_RUNNER_NOAH_REGTEST_SERVER_URL`: the Noah server, e.g.
  `http://127.0.0.1:3000`.

**Never retry a failed live payment** until you have inspected the recurring and
Send journals and the wallet history.

### `RecurringLiveTests`: Ark push and foreground

Variables: `TEST_RUNNER_NOAH_RECURRING_LIVE=1` and
`TEST_RUNNER_NOAH_RECURRING_RECIPIENT=<fresh external regtest Ark address>`.

1. The test writes its receive address to the app's
   `Documents/recurring-live-funding-address.txt`. Within 180 seconds, fund it
   **once** with 10,000 Ark sats (for example with `just bark send`).
2. It creates a daily 1,000-sat Ark schedule. A real `recurring_payment_due`
   push payload and a foreground check then run together, followed by a manual
   trigger, a pause and a cancel. Exactly one payment must result.
3. It then opens the production recurring list with a future schedule for
   inspection. Create `Documents/recurring-live-finish` to finish early;
   otherwise it continues after 180 seconds and cancels that schedule.

On success it writes non-secret results to `Documents/recurring-live-evidence.json`.

### `RecurringFeeLiveTests`: automatic Lightning within the fee cap

Variables: `TEST_RUNNER_NOAH_RECURRING_FEE_LIVE=1` and
`TEST_RUNNER_NOAH_RECURRING_FEE_OFFER=<fresh reusable regtest CLN BOLT12 offer>`.

Within 180 seconds, either fund `Documents/recurring-fee-funding-address.txt`
**once** with 30,000 Ark sats, or pay `Documents/recurring-fee-funding-invoice.txt`
once (31,000 Lightning sats). The test expects exactly one unattended 20,000-sat
BOLT12 payment across repeated triggers and a consumed occurrence. It then
cancels the schedule and deletes its wallet. On success it writes the quote,
worst-case fee and cap to `Documents/recurring-fee-evidence.json`. It never
changes the server's fee policy, so above-cap behaviour is covered only by the
deterministic tests.

## Contract and safety

`RecurringStore` reads and writes RN's `noah-wallet-storage` MMKV instance under
the `recurring-payment-storage` key, in the version-0 Zustand envelope. Unknown
envelope, state and schedule fields survive writes. Unreadable, invalid or
future-version envelopes stop execution and keep their bytes. Unsupported rows
stay visible, stopped and cancellable, with their original JSON preserved.

Spending approval is bound to the wallet's fingerprint and network **and** a
local instance UUID that is excluded from backup. A restore, even of the same
seed, gets a new instance and cannot inherit the old approval: old schedules
stay visible and cancellable but cannot run. A normal RN upgrade binds to the
existing wallet without changing its schedules.

Push, foreground and manual triggers share one executor. It pays through the
same Send controller and wallet session as manual Send, under an exclusive Send
reservation. An atomic, fsynced occurrence journal is written before the MMKV
in-flight marker, which is written before Send's journal and submission. A
marker without a receipt stops on restart with no automatic retry. A consumed
occurrence stays blocked even if MMKV rolls back. Just before the SDK call, the
executor re-reads the policy, marker, wallet identity and lifecycle state.
Ambiguous outcomes lock manual Send too. Resuming after one goes through Send's
destructive double-payment warning, skips past-due occurrences and is audited.

## RN parity and deliberate native differences

- Destinations: reusable Ark addresses, Lightning addresses/LNURL and BOLT12
  offers. BOLT11 and onchain destinations are rejected, as in RN.
- Only the latest due occurrence pays; missed ones are recorded as skipped.
  Calendar math follows RN (local Gregorian calendar, JavaScript's DST rules,
  month day kept without drift).
- Native stops immediately on an interrupted occurrence; RN waits ten minutes.
  Native also stops schedules beyond RN's 5,000-occurrence scan bound.
- Only proven pre-submission failures retry automatically, with RN's backoff.
  Unknown outcomes need the audited override.
- Editing starts a new, explicitly approved policy revision; RN offers only
  create, pause/resume and cancel. Native date pickers replace RN's text inputs.
- Server wake sync sends only opaque schedule IDs and due times, never amounts
  or recipients. iOS follows RN's server wake, activation and one-minute
  foreground checks; RN's periodic WorkManager task is Android-only. Local
  reminder and result notifications are advisory and never block payments.
