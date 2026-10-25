# Native Android recurring payments

Tests for recurring payments: schedule math and fee policy, RN storage
compatibility, the durable occurrence journal, the executor and its triggers,
plus opt-in storage and live regtest checks on a disposable emulator. iOS's
equivalent is [apps/ios/RecurringTests/README.md](../../ios/RecurringTests/README.md).

## What the tests cover

JVM tests in `app/src/test/java/com/noahwallet/recurring/`:

- **Schedule math and fee policy:** `RecurringFixtureTest` (shared calendar,
  policy, zone-change and fee-cap fixtures), `RecurringTimestampTest`,
  `RecurringEndDateTest`.
- **RN storage compatibility:** `RecurringStoreTest`, `RecurringVersionTest`,
  `RecurringNumericGrammarTest`, `RecurringRNRequiredFieldsTest`,
  `RecurringReasonUpgradeTest`, `RecurringLeadingZeroPaymentTest`.
- **Journal, settlement and replay safety:** `RecurringJournalTest`,
  `RecurringJournalValidationTest`, `RecurringJournalMigrationRaceTest`,
  `RecurringPolicyLedgerTest`, `RecurringSettlementTest`,
  `RecurringSettlementDurabilityTest`, `RecurringSettlementRepeatTest`,
  `RecurringSettlementRollbackTest`, `RecurringOverrideSettlementTest`,
  `RecurringReapprovalTest`.
- **Executor, triggers and lifecycle:** `RecurringExecutorTest`,
  `RecurringMoneyPathTest`, `RecurringLoadingTest`, `RecurringForegroundRunnerTest`,
  `RecurringPeriodicTest`, `RecurringWakeHandoffTest`, `RecurringWakeCleanupTest`,
  `RecurringPushDeadlineTest`, `RecurringStageBudgetTest`,
  `RecurringUnbudgetedJoinTest`, `RecurringRaceTest`, `RecurringAdmissionRaceTest`,
  `RecurringReadAdmissionTest`, `RecurringProtectedReadRaceTest`,
  `RecurringLiveAdmissionTest`, `RecurringAutomationAuthorityTest`,
  `RecurringRound3RegressionTest`, `RecurringOnboardingTest`,
  `RecurringReminderProtectionTest`.
- **UI and localization:** `RecurringEditorTest`, `RecurringFocusTest`,
  `RecurringHistoryLinkTest`, `RecurringLocalizationTest`.

Instrumentation tests in `app/src/androidTest/java/com/noahwallet/recurring/`:
`RecurringStorageDeviceTest` (real MMKV envelope and wallet-instance binding) and
`RecurringLiveTest` (funded payments). Both are opt-in.

Filesystem failures, deadline and clock jumps, policy rollback and lifecycle
races are covered by deterministic tests. Physical FCM/UnifiedPush delivery,
timed reminder delivery, real power loss and external LNURL providers are not.

## Fixtures

The shared fixtures are `apps/fixtures/recurring-*.json`, read through
`SharedFixtures` and also used by iOS. Regenerate the calendar, policy,
zone-change and storage fixtures from RN's unmodified code with
`bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-recurring.ts`.
`app/src/test/resources/recurring-rn-range.json` holds rows written by RN's real
validator and store; the one-off script that produced it is not in the repo.

## Running the JVM tests

From the repository root:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew :app:testRegtestDebugUnitTest --tests "*Recurring*"'
```

Drop `--tests "*Recurring*"` to run the whole suite.

## Device tests (opt-in)

Use one **empty, disposable emulator** and pass its serial to every `adb`
command. Build and install both APKs:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest'
adb -s YOUR_SERIAL install -r apps/android/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk
adb -s YOUR_SERIAL install -r apps/android/app/build/outputs/apk/androidTest/regtest/debug/app-regtest-debug-androidTest.apk
```

### Storage

Run this before the funded test, while the app holds no recurring store:

```sh
adb -s YOUR_SERIAL shell am instrument -w -e noahRecurringStorage disposable-emulator \
  -e class com.noahwallet.recurring.RecurringStorageDeviceTest \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

### Funded live payments

Needs the regtest stack (`just setup-everything`), a Noah server reachable from
the emulator at the build's regtest server URL (default `http://10.0.2.2:3000`;
override with `-PnoahRegtestServerURL`), and a fresh reusable BOLT12 offer from
the stack's CLN (`just cln …`). The app must hold no wallet.

```sh
adb -s YOUR_SERIAL shell am instrument -w -e noahRecurringLive disposable-emulator \
  -e offerRecipient <fresh regtest BOLT12 offer> \
  -e class 'com.noahwallet.recurring.RecurringLiveTest#pushForegroundArkAndAutomaticLightningPayExactlyOnce' \
  com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

1. The test writes an onchain address to the app's
   `files/recurring-live-funding-address.txt`. Within 180 seconds, send it
   **100,000 sats once** and confirm it (`just bcli …`).
2. It boards 30,000 sats and writes `files/recurring-live-board-ready.txt` with
   the number of confirmations the server requires. Mine exactly that many blocks.
3. It pays a 1,000-sat Ark schedule to a second wallet it creates itself, then a
   1,000-sat BOLT12 schedule that must auto-pay within the fee cap. Push and
   foreground triggers run together, followed by repeat, pause and cancel.
   Exactly two new payments must result, with both occurrences consumed.

Non-secret results go to `files/recurring-live-evidence.json`. The test leaves a
future schedule for inspecting the list and editor. Afterwards, run the same
command with `-e noahRecurringCleanup disposable-emulator` and
`#cleanupOwnedLiveWallet` instead. It cancels the future schedules and deletes
the wallet and its saved seed.

**Never rerun a failed or ambiguous payment** until you have inspected the
recurring and Send journals and the wallet history. Use
`-e resumeSetupOnly owned-wallet-with-no-send-journals` only to resume after a
failure *before* any schedule existed. It refuses any recurring policy, Send
journal or outgoing payment.

## Contract and safety

`RecurringSchedule` follows RN's local Gregorian calendar policy: DST folds and
gaps, month-end clamping without drift, millisecond preservation, latest-due-only
execution with skipped backlog, finite schedules and exponential retry backoff.
The 5,000-occurrence scan bound stops extreme backlogs for manual review.

`RecurringStore` uses RN's `noah-wallet-storage` MMKV instance, the
`recurring-payment-storage` key and the version-0 Zustand envelope. Unknown
fields survive writes. Invalid or future envelopes keep their bytes and stop
reads and writes; a failed write turns automation off until restart. Rows that
can't be represented stay visible as **Needs attention**, cancellable, with
their original JSON preserved.

Spending approval binds fingerprint, network and a local instance UUID in the
wallet data directory, which backups exclude. A same-seed replacement gets a new
instance, so it cannot inherit approval. Foreign policies stay visible and
cancellable.

An atomic, fsynced occurrence journal is written before the MMKV in-flight
marker and the Send journal. It keeps consumed indices per revision, so a paid
occurrence can't be replayed after an MMKV rollback or a timezone change. All
money goes through the existing Send controller and session under an exclusive
reservation. A final guard runs immediately before the SDK call. Ambiguous
outcomes keep both locks and never retry automatically. They can resume only
after a history check and a two-step destructive warning, which is audited and
skips already-due occurrences.

## Fee policy

The quote captures `ArkInfo.feeSchedule.lightningSend` (min, base and highest
ppm); without ArkInfo the defaults are min 20, base 0 and max 5,000 ppm.

- `F(a) = max(min, base + ceil(a × maxPpm / 1,000,000))`.
- `cap = min(2 × F(a), max(200, floor(a / 50)))`.
- Lightning auto-pays only if both the quote and `F(a)` fit the cap; Ark
  requires a zero fee.
- Otherwise the occurrence becomes **Needs approval**, with a notification and
  no automatic retry. **Review and pay manually** shows the exact quote in Send's
  confirmation component.

## Triggers and differences from iOS

Foreground checks, `recurring_payment_due` pushes and periodic WorkManager work
share one executor. A push's `due_count` is advisory; local policy alone decides
what pays. The 25-second push deadline returns without waiting for settlement
and revokes new submissions. Server wake sync sends only opaque schedule IDs and
due times. Android uses WorkManager reminders and a time/timezone broadcast
receiver where iOS uses notification-daemon and calendar callbacks. LNURL with a
user note follows attended Send's Lightning route so the note reaches the
recipient.
