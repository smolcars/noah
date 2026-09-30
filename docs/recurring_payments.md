# Recurring payments (MVP)

Tracking issue: [#349](https://github.com/smolcars/noah/issues/349)

Recurring payments let a user schedule a fixed amount to a fixed recipient on a
fixed interval (weekly, monthly or every _N_ days), with an optional start date,
end date or number of occurrences. Noah executes them automatically, including
when the app is closed, and the user can view, pause, resume and cancel them at
any time.

## Supported recipients

| Rail | Destination | Notes |
| --- | --- | --- |
| Ark | Ark address (`ark1…` / `tark1…`) or BIP-321 URI with an Ark address | Push-based, cheapest |
| Lightning | Lightning address (`name@domain`) | Resolved on every run; uses the Ark-direct route when the LNURL server advertises one and no note is set |
| Lightning | BOLT12 offer (`lno1…`) | Pull/subscription-style: a merchant publishes a reusable offer and Noah pays it each period |

BOLT11 invoices are single-use and are rejected. On-chain destinations are out
of scope for the MVP because miner fees vary per payment.

## Trust model

The spending policy (recipient, amount, interval, limits) is created and stored
**only on the device** (`client/src/store/recurringPaymentStore.ts`, MMKV). Every
payment is signed by the local wallet at execution time; nothing is pre-signed.

The server stores only `(pubkey, opaque schedule_id, next_run_at)` in
`recurring_payment_schedules` (migration `0022`). It uses this to send a silent
`recurring_payment_due` push. The push carries only a count. The device then
re-evaluates every local schedule against its own policy before paying, so a
compromised or buggy server can at most wake the device at the wrong time: it
cannot change the recipient, the amount or the frequency, and cannot move funds.

Executor guarantees (`client/src/lib/recurringSchedule.ts`, covered by
`client/tests/unit/recurringSchedule.test.js`):

- Only `active` schedules are paid.
- At most one payment per execution, for the most recent due occurrence.
  Occurrences missed while the phone was off are recorded as `skipped` and
  never paid in bulk, so a long outage can't drain the wallet.
- An in-flight marker is persisted before sending. If the app dies mid-payment,
  the schedule moves to `needs_attention` instead of retrying automatically.
- Retryable failures back off before the next automatic attempt (15 minutes,
  doubling up to 6 hours) and only the first one triggers a notification.
  "Run due payments now" retries immediately.
- Failures that happen before funds could move (wallet not loaded, insufficient
  balance incl. estimated fee, LNURL server unreachable, amount outside the
  LNURL limits) are retried on the next wake-up. Failures during the send itself
  are treated as ambiguous and pause the schedule (`needs_attention`).
- Resuming a paused schedule skips occurrences that passed while it was paused.

## Execution paths

1. **Silent push** — the server cron (`send_recurring_payment_notifications`,
   every 5 minutes) sends `recurring_payment_due` with `Priority::High` to users
   with a due schedule, at most once per hour per user while the payment stays
   due, and stops after 72 hours. The background notification task in
   `client/src/lib/pushNotifications.ts` runs `executeDueRecurringPayments("push")`.
2. **Android background job** — while at least one schedule is active,
   `client/src/lib/recurringBackgroundTask.ts` registers a periodic WorkManager
   job (`expo-background-task`, once every 24 hours, requires network).
   It runs the same executor when the app is not in the foreground, so payments
   also go through while Noah is closed, without depending on the server, at
   most about a day late (the overdue notification still fires after 2 hours). The
   job is removed when no schedule is active. Timing depends on Android battery
   optimisation (Doze, OEM restrictions); the Recurring Payments screen links to
   the app settings so the user can set battery usage to "Unrestricted".
3. **Foreground** — `useRecurringPaymentsRunner` (mounted in `AppServices`) runs
   due payments on start, every minute while open, and whenever the app becomes
   active.
4. **Manual** — "Run due payments now" on the Recurring Payments screen.

Local notifications:

- a reminder 24 hours before each payment,
- an "open Noah to send…" nag 2 hours after the due time, which is cancelled and
  rescheduled as soon as the payment executes (covers throttled/undelivered
  pushes on iOS or aggressive battery optimisation on Android),
- a result notification after every attempt (sent / will retry / needs attention).

## API

`POST /v0/recurring_payments/sync` (authenticated, registered user)

```json
{ "schedules": [{ "schedule_id": "7c1e…", "next_run_at": 1767258000 }] }
```

Replaces the complete set of active schedules for the user. Paused, cancelled
and completed schedules are simply omitted. Limits: 50 schedules, ids match
`[A-Za-z0-9_-]{1,64}`, `next_run_at` within 10 years. `last_notified_at` is kept
when a due time does not change so re-syncs don't cause duplicate pushes.
Schedules are deleted on `/deregister` and inactive-user deregistration.

## UI

- Settings → Wallet → **Recurring Payments**: list with status, next payment,
  last attempt and error; pause / resume / cancel; create new.
- Send flow review sheet: **Make this a recurring payment** (for Ark, Lightning
  address and BOLT12 recipients) opens the editor prefilled with recipient,
  amount and note.

## Known limitations / follow-ups

- iOS may throttle or drop silent pushes; the overdue nag is the fallback.
- Dates are entered as `YYYY-MM-DD` / `HH:MM`; a native date picker
  (`@expo/ui` DateTimePicker) is a natural follow-up.
- Fixed sats amounts only (no fiat-denominated amounts yet).
- No BOLT12 `recurrence` TLV support yet (the spec extension is still in draft);
  offers are paid with the user-defined schedule instead.
- iOS has no equivalent background job yet (BGTaskScheduler needs Info.plist
  changes); the WorkManager job is Android-only. A self-hosted always-on
  executor for power users is not included in the MVP. The native wallet worker
  in PR #303 could later replace the JS job on Android.
- Schedules are device-local and are not part of encrypted wallet backups yet.
