# Native recurring payments — M7

Implementation base: `feat/native-rewrite` at `cb802af7` (round-8 Send audit).
Swift 6, iOS 18 minimum, bark-bolt-ffi **0.4.0**.

## Round-1 unattended safety (2026-10-20)

Base: `feat/native-rewrite` HEAD `b46fc2e2`. Six focused fixes:

- Suspend/Delete durably rotate an automation generation before teardown. Even
  when the Send reservation makes close fail and the suspension flag is restored,
  an older execution cannot submit. Both races use the real session reservation.
- Journal v1 stores consumed timestamp watermarks per wallet instance + schedule,
  separately from the latest attempt, across every policy revision. Legacy
  dictionaries migrate durably before another attempt can replace their evidence.
  A revision-A → revision-B → MMKV rollback-to-A regression never pays A again.
- The automatic fee limit is max(1% of the amount, 200 sats), rounded down to
  whole sats. Excessive fees stop at `needs_approval`, without an occurrence or
  Send marker, consumption, uncertainty or automatic retry; a result notification
  asks the user to open the schedule. **Review and pay manually** opens the normal
  Send review with the exact quote; confirmation retains recurring identity,
  occurrence/policy guards, journaling and settlement.
  BarkBoltFFI 0.4.0's invoice/offer calls have no maximum-fee argument and can
  advance before returning even with `wait:false`; inspecting pending Lightning
  sends afterwards cannot enforce a ceiling. All Lightning routes therefore need
  approval even below the cap. LNURL resolved to a same-server Ark route remains
  eligible. Pinned bark's `fees.rs` and Arkoor implementation charge no Arkoor
  fee; the quote and final submission guard require that zero-fee path. A future
  SDK with variable Arkoor fees must provide a bound before enabling automation.
- Immediately before submission the plan is recomputed with the current clock
  and zone, excluding only this executor's in-flight marker and honoring backoff.
  It must still be the same latest due occurrence. Backward/forward jumps and a
  jump behind backoff abort without consuming or introducing uncertainty.
- Every execution pass (including idle ones), significant-time-change and
  `NSSystemTimeZoneDidChange` refresh persisted next dates, reminders and server
  wake entries. Three new shared RN-generated zone-change cases include the
  New York Feb 28 2026 23:00 monthly schedule: switching to UTC moves its next
  date from Mar 29 03:00 UTC to Apr 1 04:00 UTC (73 hours).
- The silent-push callback returns at its monotonic 25-second deadline independently
  of cancellation-shielded work. The deadline follows executor/controller/session
  tasks and forbids new submissions after expiry. A payment already submitted
  still settles and consumes its occurrence once.

Verification: targeted **59 tests / 108 individual runs, six suites, zero
failures/skips**; funded opt-in **one test, zero failures/skips**. One intermediate
compile attempt caught a sendability annotation in the deadline test; it was
corrected before the passing targeted run. The full `NoahTests` suite ran **once**,
with no exclusions: **421 total, 403 passed, 18 opt-in skipped, zero failed**,
**90 suites**, 46.939 seconds of test execution. Parameterized execution reports
**539 passed / 18 skipped** individual runs. It includes the final additional
clock/backoff regression.
All runs used Nix, XcodeGen, normal signing, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`,
`-jobs 2`, and disabled parallel testing. Owned simulator: **Noah Recurring
Round1 Sol**, iPhone 17 / iOS 27.0, UDID
`7A9668D6-65C5-4F70-A344-8C2DCD88BEEE`. DerivedData:
`/private/tmp/noah-recurring-r1-sol-dd`; logs/results/summary JSON prefix:
`/private/tmp/noah-recurring-r1-sol-`.

Live evidence: a fresh wallet received **30,000 Ark sats once**. A **20,000-sat
BOLT12** schedule became **Needs approval** across repeated automatic/manual-run
triggers, with **zero new movements** and no recurring journal entry. Its real
quote was **75 sats**, below the **200-sat** cap, so this exercised the unbounded
submission-fee gate. Explicit manual confirmation through the production executor,
Send controller and SDK then produced exactly **one** successful sender movement,
**ID 2**; replaying the same review did not pay again. CLN independently recorded
`paid`, **20,000,000 msat received**, payment hash prefix `35d2ec477338`, pay index
**6**. The test cancelled the schedule and deleted its disposable wallet using
production lifecycle code. No service was restarted, no fee configuration changed
and no block mined. This was a programmatic confirmation of the action used by
Send's review sheet; no UI tap or physical push-delivery claim is made.

A live **above-cap** quote was not exercised: this funded wallet quoted 75 sats,
and changing the shared server fee policy was outside the allowed scope.
Above-cap/refusal/copy and below-cap unbounded Lightning behavior use deterministic
tests; the shared server was left alone.
The opt-in check can be reproduced on a fresh dedicated simulator with
`TEST_RUNNER_NOAH_RECURRING_FEE_LIVE=1`, a fresh reusable regtest CLN offer in
`TEST_RUNNER_NOAH_RECURRING_FEE_OFFER`, and
`TEST_RUNNER_NOAH_REGTEST_SERVER_URL=http://127.0.0.1:3100`, selecting
`NoahTests/RecurringFeeLiveTests`. Fund the published
`Documents/recurring-fee-funding-address.txt` **once** with 30,000 sats within
180 seconds. Never retry a failed payment without inspecting history and journals.
Non-secret evidence is written to `Documents/recurring-fee-evidence.json`.

Cleanup completed after exporting the summaries and non-secret live evidence:
confirmed the owned simulator's exact name/UDID and **Shutdown** state, deleted
it, and verified its absence. Removed the owned DerivedData and all **five**
xcresult bundles. Logs, summaries and evidence remain under the `/private/tmp`
prefix above. Protected simulators and other devices were untouched; no Docker
service was stopped, restarted or reconfigured.

## Contract and safety

`RecurringStore` reads and writes RN's `noah-wallet-storage` MMKV instance,
`recurring-payment-storage` key, version-0 Zustand envelope. Unknown envelope,
state and top-level schedule fields survive writes. Unreadable/invalid/future-version
stores stop execution and retain their bytes. Nothing is silently filtered out.
The upgrade fixture is generated by running RN's actual `createRecurringPayment`.
Real MMKV reopen, unknown-field and invalid-store tests cover migration.

Native approval binds the entire store to fingerprint/network **and** a local
instance UUID excluded from backup. Deleted wallets clear MMKV through the
existing deletion pipeline. Restores replace that local token, so even a
same-seed restore cannot inherit the old instance's spending approval. Old
schedules remain visible/cancellable but cannot execute; create fresh schedules
for the replacement wallet. Before creation/restore/recovery, unbound RN policies
are sealed as belonging to an unverified previous wallet rather than adopted by
the replacement. A normal RN upgrade binds to the existing wallet without
changing its schedule policy.

The executor joins push, foreground and manual triggers. It uses
`AppServices.ensureWalletOpen`, then the same `SendPaymentController` and
`WalletSession.quoteSend/prepareSend/submitSend/reconcileSend` as manual Send.
An exclusive mode on the existing Send reservation drains counted wallet calls,
blocks new unrelated calls and excludes close/replacement. Both reservation work
and native calls outlive caller cancellation. The daemon remains SDK-owned.

An independently atomic, file-and-directory-fsynced occurrence journal precedes
the flushed MMKV in-flight marker, which precedes Send's journal/submission.
The Send attempt also records its recurring occurrence key. A marker without a
receipt stops immediately on restart, with no automatic retry. A consumed
occurrence remains blocked even if MMKV rolls back to an older snapshot. All
in-flight markers, including migrated RN ones, require review. Successful
receipts advance only once. Removed policies are never recreated, and a pause
during a native payment remains paused after recording the result.

Immediately before the SDK payment call, the recurring guard re-reads the
persisted policy, occurrence marker, wallet identity/instance, suspension and
restore/deletion state. Edits invalidate the old policy revision. In-flight and
uncertain policies cannot be edited. A checkpoint discovered during recurring
preparation cannot be adopted without manual review; existing Send Lightning
checkpoint and invoice guards remain in force. Ambiguous outcomes lock manual
Send too. Cancellation removes policy, not an unresolved Send attempt.

The destructive resume uses Send's double-payment warning, explicitly says past
due occurrences will be skipped, and persists the exact confirmation text before
unlocking. Its audit survives removal of the occurrence; legacy ownership is
recorded as unverified. Audit, journal or MMKV write errors retain the lock.

## RN parity and deliberate native differences

- Supports reusable Ark addresses, Lightning addresses/LNURL and BOLT12 offers.
  RN rejects BOLT11 and onchain destinations, so recurring never enters an
  onchain payment path. Manual Send/M6 prepared sends remain unchanged apart
  from the optional submission guard.
- LNURL resolves again at execution, validates provider amount bounds, prefers a
  valid same-server Ark route, otherwise obtains and validates an exact-amount,
  network/expiry-checked invoice before Send's checkpoint inspection.
- Latest due occurrence only; missed occurrences are recorded as skipped.
  Calendar math uses the current local Gregorian calendar and JavaScript's DST
  disambiguation (first fold, forward by the gap), preserving milliseconds and
  the original month day without drift.
- `apps/fixtures/tools/generate-recurring.ts` executes the unmodified RN code:
  **1,260 calendar cases** in six zones plus **36 execution-policy cases**, and
  the RN storage envelope. Swift consumes them; Android can reuse them. Generate
  with `bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-recurring.ts`.
- Native stops immediately on an interrupted occurrence; RN waits ten minutes.
  Native also stops schedules beyond RN's 5,000-occurrence scan bound instead
  of letting repeated wakes pay successive chunks of an extreme backlog.
- Only proven pre-submission failures retry automatically, with RN's backoff.
  Unknown outcomes require the audited destructive override and skip past due
  occurrences. Native adds wallet-instance binding and the independent durable
  consumed-occurrence journal.
- The requested edit flow starts a new explicitly approved policy/revision at
  its chosen first date, retaining visible prior attempts. RN currently exposes
  creation, pause/resume and cancellation only. Native date pickers replace RN's
  date/time text inputs. Recurring amounts use RN's numeric system keyboard;
  the Send custom keypad is never mixed with it.
- The generated authenticated server client sends only opaque IDs and due times
  in seconds. Requests serialize, resend edits made during a request and retain
  `serverSyncPending` after failures. Wake sync sends neither amounts nor recipients.
- iOS follows RN's server-wake and foreground triggers. RN's periodic
  WorkManager task is Android-only. Local reminder/result notifications are
  advisory and asynchronous: notification-daemon delays cannot block payments
  or settings. A generation check prevents stale reminder callbacks replacing
  a newer schedule list.

## Verification

The final focused run passed **64 tests / 11 suites, zero failures/skips**,
covering all recurring suites plus push, Send controller/override, Lightning,
prepared-send, session identity and discard regressions. The full `NoahTests`
suite ran **once** with no target/suite exclusions: **405 total, 388 passed,
17 opt-in skipped, zero failed**, **88 suites** (26.608 s test execution).
The funded opt-in test independently passed **1 test, zero skips/failures**.
A final build verifies removal of the now-unused Settings placeholder helper;
there were no further functional changes after the full suite.

All Xcode runs used Nix, XcodeGen, normal code signing,
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, and tests disabled parallel
execution. DerivedData: `/private/tmp/noah-recurring-m7-0c03-dd`.
Log/result prefix: `/private/tmp/noah-recurring-m7-0c03-`.
Summary JSONs and logs are retained outside the repository; result bundles and
DerivedData are removed after exporting the summaries.

### Funded regtest evidence (2026-10-19, America/New_York)

Disposable iPhone 17 / iOS 27.0 simulator: **Noah Recurring M7 Live 0c036e0e**,
UDID `903B796F-F0B6-4498-AF55-1332500F3BA5`. A new wallet, fingerprint
`a18f8717`, received **10,000 Ark sats once** through `just bark send`.

- Created a daily **1,000-sat** Ark schedule due two seconds later. The real
  `PushJobs` generated `recurring_payment_due` payload (deliberately `due_count:
  999`) and foreground execution ran together through production services,
  shared executor, local policy, Send journal/reservation and SDK.
- Sender produced **exactly one** new successful movement, **ID 2**. A second
  manual trigger left the movement count at one and the paid count at one.
  The durable occurrence entry was resolved/consumed.
- Bark independently reported **movement 42**, `bark.arkoor / receive`,
  `successful`, **+1,000 sats**, **0-sat fee**. Its preceding movement 41 was
  the one-time -10,000-sat funding send.
- Paused the schedule, triggered again, then cancelled it and triggered again.
  Status became paused, then the policy disappeared. Total new sender
  movements remained **one**. No policy was recreated.
- Authenticated generated-client registration/save/pause/cancel sync to the
  existing server at **127.0.0.1:3100** succeeded; the durable pending flag was
  clear. No server/Android/RN source or running Docker service was changed.
  No blocks were mined by this verification.
- Opened the production list and editor in a hosted native window. Inspected
  the populated list, editor navigation, numeric keyboard and Done toolbar
  through the Device panel. The visible keyboard/Save overlap was corrected by
  hiding the Save CTA until editing finishes. The temporary future policy used
  for inspection was cancelled before closing the wallet.

The first disposable simulator (**Noah Recurring M7 Sol 0c036e0e**,
`C2D05375-D612-42FB-B28A-73FAEA7B8AD4`) ran the iterative tests. Its first
funded live attempt stopped before submission while an iOS local-notification
callback stalled after authenticated schedule sync. Neither occurrence nor Send
journal existed: no payment was submitted. The run was interrupted and the
simulator shut down; no payment was retried. Its interrupted result bundle
records one failed test, distinct from the passing fresh-wallet run. The notification path was changed
to advisory asynchronous callbacks, and the passing live run used the second
fresh wallet above. Both disposable wallets received their own one-time regtest
funding; no production wallet was involved.

### Reproduction and limits

On a fresh dedicated simulator, run only `NoahTests/RecurringLiveTests` with
`TEST_RUNNER_NOAH_RECURRING_LIVE=1`,
`TEST_RUNNER_NOAH_RECURRING_RECIPIENT=<fresh external regtest Ark address>` and
`TEST_RUNNER_NOAH_REGTEST_SERVER_URL=http://127.0.0.1:3100`.
The test publishes `Documents/recurring-live-funding-address.txt`; fund it
**once** with 10,000 sats within 180 seconds. It creates its own throwaway seed,
uses production services and refuses an existing wallet. It writes only
non-secret summary evidence to `Documents/recurring-live-evidence.json`.
After UI inspection, create `Documents/recurring-live-finish` (otherwise it
continues after 180 seconds) to cancel the inspection policy and close.
Never retry a failed live send without inspecting the two journals and history.

Crash boundaries/races use deterministic persistence/controller/session
regressions. No real power loss, actual kill between marker and receipt,
external LNURL/BOLT12 provider acceptance, physical APNs/Expo delivery or
24-hour reminder/overdue delivery is claimed. The silent-push test directly
invokes the production push-job pipeline; simulator injection is not evidence
of real background push delivery. Real RN-created MMKV binary migration is
covered by the existing upgrade infrastructure, while this milestone adds RN-
generated recurring envelopes and real MMKV write/reopen tests; a funded
RN-to-native recurring upgrade was not rerun.

Cleanup completed: confirmed each simulator's exact name/UDID and Shutdown
state before deleting it, then confirmed both UDIDs were absent. Removed only
`/private/tmp/noah-recurring-m7-0c03-dd` and all **15** owned xcresult bundles
after exporting available summary JSONs. The final build succeeded. Retained
logs/summary JSONs and non-secret live evidence under the same `/private/tmp`
prefix. Protected simulators, other agents' devices, Android emulators, the
physical Pixel 6 and running Docker services were untouched.
