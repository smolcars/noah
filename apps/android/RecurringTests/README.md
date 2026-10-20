# Native Android recurring payments — M7

Initial implementation base: `feat/native-rewrite` at `1aa9a61852c7e06797e729ee90b49dfec802b6d6`.
Kotlin / Compose, API 24 minimum, bark-bolt-ffi **0.4.0**.

## Round-2 money-path fixes (2026-10-20)

Base: `feat/native-rewrite` at `4f7228c5`; bark-bolt-ffi **0.5.0**.
These changes retain revision-bound settlement, the independent policy ledger,
strict/lossless RN rows, journal quarantine and cross-revision consumption.
They do not depend on acknowledgement of MMKV recovery quarantine.

The wallet datadir now also contains `.noah-recurring-automation.json` and its
`.initialized` marker. Both are version-1 JSON. The marker stores an `instance`
UUID; the authority stores that same UUID, an independent UUID `generation`
epoch, and required `suspended` / `stopped` booleans. Epochs rotate on stop,
suspension changes and explicit wallet resume; they are equality tokens, not
numeric counters. Existing MMKV stop-generation checks remain in addition.
Wallet binding/instance and datadir checks still independently gate execution.

Bootstrap writes the marker before the authority. Every document uses temporary
file → file fsync → atomic rename → directory fsync. Lifecycle stop/suspension
writes precede the MMKV callback and UI success. Only explicit wallet resume
clears `stopped`; failed suspension rollback does not. A failed MMKV resume write
restores a durable stop. Missing/corrupt authority or marker, interrupted writes
and missing required fields stop execution without importing MMKV again. An
in-process failure latch also defeats an already-admitted final guard. Production
wallet deletion removes the files with the datadir and clears that path's latch
only after removing the data. MMKV recovery acknowledgement cannot clear it.

Protected reads overlay a visible stopped row without changing RN bytes. Each
execution captures the durable epoch before opening; policy checks and the final
synchronous Send guard reread it. A stop followed by resume still invalidates
work admitted under an earlier epoch, even when MMKV is rolled back.

Terminal journal entries now store `policyApplied` with resolution/consumption,
before policy-ledger/MMKV advancement and receipt acknowledgement. This is a
write-ahead application claim: interruption may leave advancement stopped, but
cannot apply success/history/notifications twice. Repeated status checks only
acknowledge the exact retained receipt. Already-consumed pre-marker receipts are
upgraded without applying them again. Durable consumption/application remains
acknowledgeable even when a policy write latches storage failure or a recovered
row mismatches the policy ledger; that row stays visibly stopped. Wallet/binding
replacement still prevents settling another wallet's entry.

A healthy ledger-ahead mismatch can now be explicitly reapproved through Edit or
**Approve and resume**. Reapproval requires a resolved journal and unlocked Send,
writes a fresh revision under the current ledger generation, and skips already-due
occurrences. It never removes consumption timestamps/indices or legacy-uncertainty
evidence; corrupt evidence and ambiguous attempts remain blocked. The destructive
history override cannot silently adopt a rolled-back policy.

Android-specific fixes:

- `RecurringNotifications.kt:75` attaches policy/automation protection before the
  reminder worker reads a schedule. Failed pause/cancel MMKV writes cannot
  advertise the older active row.
- `RecurringExecutor.kt:72` checks budget and caller cancellation before wallet
  open, server reporting, widget refresh and reminder refresh, including after
  suspended reads. The final Send guard checks the caller too. Already-admitted
  native calls still settle and consume money after cancellation/revocation.
- Ledger file fsync is injectable at `RecurringStore.kt:118`; directory syncing
  remains injectable. `RecurringWorld` skips both for fast unit tests, and the
  existing real-session reservation fixture uses its lifecycle sync hook.
  Dedicated authority tests execute real file fsync and pin temp/rename/directory
  ordering and interrupted boundaries.

Implementation (relative to `apps/android/app/src/main/java/com/noahwallet/`):
`recurring/RecurringAutomationAuthority.kt:9`,
`services/wallet/WalletLifecycle.kt:103`, `recurring/RecurringStore.kt:28`,
`recurring/RecurringExecutor.kt:81` / `:124` (authority), `:186` (idempotence),
`:213` / `:216` (acknowledgement), `:237` / `:261` (reapproval),
`recurring/RecurringPolicyLedger.kt:64` (new approval revision).

Permanent regression files under `app/src/test/java/com/noahwallet/recurring/`:
`RecurringMoneyPathTest`, `RecurringSettlementRepeatTest`,
`RecurringSettlementRollbackTest`, `RecurringReapprovalTest`,
`RecurringReminderProtectionTest`, `RecurringStageBudgetTest`, and
`RecurringAutomationAuthorityTest`.

The three supplied money-path probes failed before their fixes (**3/3**). The
reapproval and stage-budget probes also failed before (**2/2**). Removing only the
reminder protection reproduced its regression; restoring it passed. A second
pre-marker upgrade probe failed before / passes after. Repeated checks pin the
entire applied row and notices, one submission, consumption and unlocked Send.
Additional cases cover terminal acknowledgement with latched policy storage,
durable stop+resume during final preparation, required fields, unavailable
control files and both fsync boundaries.

Final targeted recurring/lifecycle run: **1,511 tests / 23 suites / zero
failures/errors/skips**. Real-session/restore fixture check: **46 tests / 2 suites /
zero failures/errors/skips**. Final full JVM/APK result: **2,968 tests / 99 suites / zero failures/errors/skips; regtest APK assembly succeeded** in the same **1m 22s** completed run.

Remaining excluded integration: `services/AppServices.kt:107` also writes the
MMKV stop generation when preparing restore, independently of WalletLifecycle.
That file was explicitly excluded because another agent owns its headless work.
Its owner must durably stop the authority inside the existing `try`, before
`settings.set(generation, …)`. The unapplied, unverified wiring patch is
`/private/tmp/noah-money-appservices-owner.patch`:

```kotlin
val directory = WalletDataLocation(context.filesDir, variant).directory
if (directory.isDirectory) com.noahwallet.recurring.RecurringAutomationAuthority(directory).stop()
```

This is a remaining finding-1 boundary; the lifecycle suspension/deletion fixes
and their rollback probes are implemented and tested. No AppServices file was
modified here.

No emulator, physical device, Docker, funded payment or real power cut was used.
One early full-command attempt was stopped during unrelated PBKDF2 tests after
inspection found the existing real-session test needed the new sync injection;
it is not counted as a completed full run. The final completed run follows that
fixture correction. An earlier overlap of two targeted Gradle runs caused report
file collisions; subsequent platform runs were serialized.

Code commits:

```text
80170e37 test(android): inject recurring ledger file sync in executor tests
b84d738a fix(android): apply each recurring settlement at most once
73bc9f91 fix(android): acknowledge paid receipts despite policy rollback
20132ff5 fix(android): persist wallet automation stops outside MMKV
7f07a6e2 fix(android): gate every recurring background stage on its budget
e0cee1b3 fix(android): allow explicit reapproval after policy rollback
99beda75 fix(android): protect recurring reminders with policy authority
19373aa8 fix(android): release consumed receipts despite latched policy storage
e8118c97 test(android): pin idempotent recurring history and notifications
eb6402cf fix(android): retain automation failure latches through recovery
a271ca0f fix(android): preserve idempotence for pre-marker consumed receipts
4825166a fix(android): observe caller cancellation between recurring stages
c1ade935 fix(android): label recurring rollback reapproval explicitly
ad6f318a test(android): inject lifecycle sync in recurring reservation test
```

## MMKV policy rollback safety (2026-10-20)

Base: `feat/native-rewrite` at `fce8f757`, retaining revision-bound settlement
from `19d8de9f` / `8e2d2e4b`. Fix: `7e8e0741`.

The permanent unpaid pause/cancel/edit probes restore the earlier active MMKV
snapshot after committing the change. Before the fix: **3 tests / 3 failures**, each paying once. They now make zero payments,
including repeated manual runs, and retain a visible `needs_attention` row.
Editing changes both recipient and amount; the older, larger policy cannot run.

`noah-recurring-journal.json.policies` is a version-1 JSON ledger beside the
occurrence journal in the wallet datadir. Its header contains the bootstrap
binding and monotonic generation. Entries are keyed by SHA-256 of the full wallet
binding (instance UUID + fingerprint/network), followed by schedule ID; each stores
that binding/ID, generation, native revision, status and SHA-256 of the complete
known row. A deleted entry has status `deleted` and no digest. Keeping the known
row digest also protects skipped indices, backoff and failure state without adding
fields to RN's version-0 MMKV envelope. Replacements have independent entries;
old-wallet tombstones remain authoritative for their own binding.

First protected load durably writes `.policies.initialized` **before** the initial
ledger, without changing MMKV. Both documents retain the matching bootstrap
binding. A crash after the marker, a missing/corrupt ledger or marker, or an unknown
row after bootstrap stops execution; none silently imports again. For every
mutation, the ledger temp file is synced, atomically renamed and its directory
synced **before** the MMKV write/flush/readback and UI success. The iOS helper now
syncs the temp file before rename as well. Even cancelling a row already missing
from MMKV writes a tombstone. Existing approved policies cannot be overwritten
from a mismatching snapshot, including through destructive override; cancellation
and a newly approved schedule remain possible when the ledger is healthy.

Every protected store read validates the ledger and overlays mismatching rows as
`needs_attention`, retaining their original MMKV bytes and a fixed redacted
message. Date refresh and marker recovery skip these rows so they cannot bless a
rollback; independent occurrence-journal validation still runs. Stop generations,
storage health/write-failure latches, final synchronous submission guards,
consumption-before-advancement and revision-bound settlement remain in force.
Missing/corrupt durable evidence stays stopped; MMKV cannot repair it. Removing
both the wallet datadir and recurring MMKV state through the existing production
wallet deletion also removes the ledger/marker and instance token.

Enforcement paths (relative to `apps/android/app/src/main/java/com/noahwallet/`):

- Common policy gate: `recurring/RecurringStore.kt:27` / `:43`; write-ahead
  mutation: `:69` / `:74`; cancel tombstone: `:91`.
- Executor attaches protection at `recurring/RecurringExecutor.kt:21`;
  launch/trigger and post-quote reads use `:43` / `:46`. Foreground/minute runner
  and launch share `recurring/RecurringRuntime.kt:44` / `:47` / `:49`.
- FCM/WorkManager share `services/push/PushRuntime.kt:25` and
  `services/push/PushJobs.kt:70`; both reach the same runtime/executor gate.
- Manual due runner uses `recurring/RecurringExecutor.kt:70`; attended review
  uses `:188`; the last synchronous guarded Send check rereads the protected store
  at `:111`, immediately before submission. Settlement reads also remain protected.
- Deletion clears the containing datadir through
  `services/wallet/WalletLifecycle.kt:150` / `:153`; no new lifecycle path.

Permanent coverage includes the three review probes, rollback during final Send
preparation, resume/skip rollback, bootstrap once, unknown rows after bootstrap,
missing/corrupt ledger and marker, interrupted bootstrap, ledger-before-MMKV
ordering and interruption, MMKV-ahead-of-ledger refusal, tombstones when a row is
present or missing, binding separation, and production wallet deletion/recreation.
The prior journal corruption/zone/revision tests still pass; where both policy
rollback and journal corruption exist, policy attention remains the visible reason
while the journal's independent quarantine is verified. Unsupported RN rows stay
lossless and cancellable; healthy approvals remain independently executable.

Focused recurring + wallet-lifecycle run: **1,496 tests / 16 suites**, zero
failures/errors/skips. Final JVM/APK check ran **once**, after the final production
change, with no test filters:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**2,927 tests / 90 suites passed**, zero failures/errors/skips; regtest APK assembly
passed in the same **2m 11s** invocation. All Gradle calls used `--max-workers=2`.
The new ledger tests use real temp-file/rename/file-sync operations with injected
directory-sync callbacks for JVM execution; a throwing callback also proves that
an unconfirmed directory sync cannot permit an MMKV commit. No Android emulator
or device was opened. Raw logs and JSON summaries remain under
`/private/tmp/noah-policy-db76c70d-*`.

New rollback tests inject old snapshots deterministically; this does not claim a
real power cut, a funded payment, live push transport or physical-device testing.
No protected/shared devices, Android emulators, physical devices, Docker, server,
RN client, MMKV opening/backup code, logging implementations, WalletSession opening
or Android AppServices files were modified.

## Journal revision settlement (2026-10-20)

Base: `feat/native-rewrite` at `172446b6`. Fix: `6c4c8faa`.

`RecurringExecutor.kt:161` compares the stored policy revision with
`entry.policyRevision`, including the legacy null revision. A snapshot loaded by
Check payment status cannot authorize an older attempt to advance the edited
policy. Settlement uses the matching current row, preserving pause and safety
failure state. Journal resolution/consumption precedes policy advancement and
exact-attempt acknowledgement independently of the revision match.

The run model has no recipient or policy-revision field, and the journal does
not retain the approved recipient/amount. A stale outcome therefore only resolves
its journal evidence; it adds no run or counters to the edited policy. The fallback
success notice (`:173`) requires an original snapshot with the journal revision;
a status check that only has the edited snapshot emits no misleading success.
An execution retaining the actual paid snapshot can still notify about that
original payment after an edit/cancel.

`RecurringSettlementTest.oldSuccessfulReceiptCannotSettleAnEditedPolicyRevision`
is the permanent adaptation of `/tmp/noah-review-probes/ReviewRound2ProbeTest.kt`.
It pays 1,000 sats, fails Send receipt removal, edits to another recipient for
2,000 sats with a one-payment limit, recovers to Needs attention, then checks
status twice. Before the fix: **1 test / 1 failure**, with the edited policy
incorrectly Completed and a successful 2,000-sat run. Afterward: the entire edited
row and result notifications remain unchanged, A is consumed/acknowledged, and
another execution sends nothing. A second regression covers successful,
already-paid, proven-failed and still-ambiguous status checks against an edited
revision, including unchanged history, the Send lock and no automatic retry.

Audit: both settlement callers (submission `:120`, status check `:183`) and all
uncertainty callers (`:118`, `:123`, `:154`) were checked. `markUncertain` already
checks current binding, stored binding and journal revision. Startup marker
recovery uses `updateIf`; quote/review, marker writes and the final Send guard
retain exact policy comparisons after IO suspensions. The final guard retains
binding/instance, stop generation, runtime/storage failure latches, lifecycle
markers, budgets, fee policy and journal consumption checks. Foreground startup,
timer, screen-open, time-change, push and WorkManager routes in
`RecurringRuntime`, `RecurringForegroundRunner`, `PushJobs`, `PushRuntime` and
`PushWork` share this executor. Reminder workers do not submit payments. Server
wake synchronization still compares replacement payloads after requests.

Verification: targeted `--tests "*Recurring*"` passed **1,463 tests / 16 suites**,
zero failures/errors/skips. The required full command ran **once** after the final
Android code/test changes:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**2,913 tests / 89 suites passed**, zero failures/errors/skips, including **1,461
recurring-package cases**. Regtest APK assembly passed in the same **5m 1s** run.
Every Gradle invocation used `--max-workers=2`; `NIX_CONFIG='eval-cache = false'`
avoided the shared Nix evaluation cache lock. Logs and count summaries remain at
`/private/tmp/noah-recurring-revision-41f7ba6f-*`.

No funded/live payment, device UI or physical notification delivery was tested.
No Android emulator, physical device or Docker was accessed. MMKV opening,
logging, server and RN code are unchanged. iOS parity and owned-simulator cleanup
are recorded in its RecurringTests README.

## Storage strictness — envelope version (2026-10-20)

Base: `feat/native-rewrite` at `c6283d16`. Check the raw JSON primitive's token,
without integer/double conversion: only absent legacy version or exact `0` is
accepted (surrounding JSON whitespace is allowed). RN's recurring store leaves
Zustand's default numeric version 0 unchanged; `createJSONStorage` serializes it
with `JSON.stringify` as `0`, including if passed JavaScript negative zero.

Expected outcomes: absent / `0` → readable and writable; `1e-400`, `0.0`, `-0`,
`0e5`, `"0"`, `1`, `1e400`, booleans, null, objects and arrays → entire store
unavailable, zero execution, zero writes, original bytes preserved across reopen.
`RecurringVersionTest` exercises a populated RN store, both execution triggers,
binding, upsert, pending, removal and reopen. Before the fix, the targeted
`--tests "*Recurring*"` run reported **1,444 tests / 1 failure**, reproducing
acceptance of `1e-400` before any mutation in that test.

## Storage strictness — timestamp parity (2026-10-20)

Android's existing `Long` timestamp decoding already isolates fractional times as
unsupported rows; no scheduling code change is needed. Both platforms now assert
every `expectedClassifications` entry in the shared `recurring-rn-range.json`:
**four active count rows / seven stopped-unsupported rows**, with a reason per row.
The shared resource is selected by Gradle over the historical local copy (the test
requires its expectations map). RN's normal UI/runner emits whole milliseconds;
its factory accepts the synthetic start/end fractions in this fixture.

`RecurringTimestampTest` separately tests start, end, next-run, created, updated,
retry, in-flight start, run scheduled-for and run attempted-at fractions. Each row
remains visible as Needs attention and cancellable, retains its original JSON
values across unrelated healthy execution, pause, pending writes and reopen, and
cannot execute, resume, override or edit. A healthy row pays exactly once.
Targeted `--tests "*Recurring*"`: **1,453 tests / 14 suites**, zero failures,
errors or skips. An earlier new-test compile failed on a parenthesis in fixture
construction; it ran zero tests and was corrected before this passing run.

## Storage strictness — preservation and final verification (2026-10-20)

Unsupported rows preserve JSON values, unknown fields and numeric tokens across
rewrites. **Formatting is not preserved**: object spacing/indentation may change.
This is accepted because RN reads parsed JSON values. Reads do not rewrite bytes;
invalid envelopes remain byte-for-byte unchanged. No formatting code changed.

The required full command ran **once**, after the final Android code/test change:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**2,842 tests / 75 suites passed**, zero failures/errors/skips, including **1,451
recurring package cases**. Regtest APK assembly passed in the same **2m 36s** run.
All Gradle invocations used `--max-workers=2`; iterations used `--tests "*Recurring*"`.
`NIX_CONFIG='eval-cache = false'` avoided the shared Nix evaluation-cache lock
without changing shared configuration. Logs/count summaries remain under
`/private/tmp/noah-recurring-strict-5fec2083-android-`.

The real RN writer was rerun read-only from this worktree with installed
dependencies via `NODE_PATH`; it reproduced the 11 original rows and version 0
exactly (only the shared native expectations map differs). No Android emulator,
physical Pixel 6 or Docker was accessed. No funded payment, physical notification
or upgrade test was run. Fee policy, launch/seed/upgrade code and other stores
are unchanged. iOS verification/owned-simulator cleanup is recorded in its README.

## Round-3 final cross-platform fixes (2026-10-20)

Base: `feat/native-rewrite` local HEAD `95cd6af0`.

### RN numeric storage compatibility

`maxOccurrences` and the editor's count parser now use `Long`. Each stored row is
read independently: unrepresentable values or unsupported policies remain visible
as **Needs attention**, cannot run/resume/edit/override, and remain cancellable.
Their original JSON (including unknown fields) survives unrelated writes, pause,
reopen and execution of healthy schedules. Reads do not rewrite storage. Invalid
or future envelopes still stop reads/writes.

`app/src/test/resources/recurring-rn-range.json` was generated by executing RN's
actual `validateRecurringPaymentInput`, `createRecurringPayment`, and
`useRecurringPaymentStore.getState().upsertSchedule` through Zustand `persist` /
`createJSONStorage`. Only MMKV, logging, translation and unused React hooks were
mocked; the real validator, factory, store and JSON writer ran under Bun with
`--tsconfig-override client/tsconfig.json`. The verification script remains at
`/tmp/noah-android-recurring-r3-rn.ts`. Shared `recurring-storage.json` is unchanged.

RN accepts **finite positive integer JS numbers**, without a safe-integer cap:
verified counts **1, 3,000,000,000, 9,007,199,254,740,991**, the JS value serialized
as **9,223,372,036,854,775,000**, **2^63**, **1e100**, and **Number.MAX_VALUE
(1.7976931348623157e308)**. Zero, negatives, fractions, Infinity and NaN were
rejected. Android supports counts whose serialized integer fits `Long`; larger
counts stay stopped and lossless. RN also accepted/wrote fractional start/end
milliseconds and starts beyond JS Date's range; Android retains these as stopped
rows. Tests execute all four supported RN-written rows alongside seven unsupported
ones, then check cancellation, reopen, no audit and unchanged original JSON.

Storage targeted run: **1,435 tests / 11 suites**, zero failures/errors/skips, using Nix,
`--max-workers=2` and `--tests "*Recurring*"`.

### Result notifications

The first active retryable preflight failure now notifies after successfully
saving its backoff. Further automatic retries stay silent; explicit manual
retries notify, matching RN/iOS. A pause/cancel during preflight wins and produces
no retry result notification. Confirmed successful settlement after cancellation
notifies once without recreating policy; journal consumption and attended receipt
retention still apply. Cancelled proven failures and ambiguous outcomes never
produce a success notification. The notification-tap/screen-open execution path
is unchanged.

Notification targeted run: **1,439 tests / 11 suites**, zero
failures/errors/skips. An initial test compile failed because an assertion edit
also matched a different test; that edit was corrected before this passing run.

### Remaining parity alignments

Envelope versions must be numeric: string `"0"` and other non-numeric versions
stop reads/writes without changing the original bytes. Missing versions and RN's
numeric `0` remain supported. Resume and edit now check durable legacy uncertainty
before any policy change, independently of due planning or visible status. They
return the same guidance as iOS: **“Noah can't reconstruct this schedule's earlier
payment history. Cancel it and explicitly approve a new recurring payment.”**
The existing destructive override uses that same check. Tests cover active,
paused, needs-attention and completed schedules, due and future dates, unchanged
policy/journal bytes, no audits and cancellation/fresh approval.

Selecting Custom in the editor now defaults to **14 days**, as RN/iOS do. Existing
intervals retain their values when opening the editor; weekly/monthly defaults
remain one. Parity targeted run: **1,442 tests / 12 suites**,
zero failures/errors/skips. All round-3 targeted commands used Nix,
`--max-workers=2` and `--tests "*Recurring*"`.

### Round-3 final verification and limits

The requested full command ran **once** after the final production/test changes:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**2,830 tests / 73 suites passed**, zero failures/errors/skips; the recurring
package contributes **1,440 cases**. The targeted pattern also selects two
session/teardown regressions. Regtest APK assembly passed in the same **1m 8s**
invocation. Existing native-library stripping warnings were non-fatal. Logs and
JSON count summaries remain at `/tmp/noah-android-recurring-r3-*`.

Only Android recurring code/tests, this README and the Android M7 TODO line
changed. RN code ran read-only for writer verification; shared fixtures, iOS,
Send, Onchain, fee policy and server were untouched. No emulator, physical Pixel 6,
simulator or Docker was accessed. Device/UI, live/funded payment and notification
delivery checks were not run, as requested; these changes use JVM regressions
and APK assembly. Notification taps still open the recurring screen, whose normal
due-payment execution keeps every existing policy/fee guard.

## Android parity follow-ups (2026-10-20)

Base: `feat/native-rewrite` local HEAD `51c24bc0` (iOS round 3).

- New and edited end dates use RN's **23:59:00.000 local time**. Persisted
  timestamps, including previously approved 23:59:59.999 boundaries, are read
  unchanged. Regressions cover UTC, New York's spring/fall DST days, the inclusive
  final millisecond and unchanged timestamps through reads/pause/reopen.
  Shared calendar fixtures only calculate occurrence dates and all shared policy
  fixtures have null end dates, so they require no changes.
- Oversized RN amounts remain visible as **Needs attention**, preserving the exact
  stored amount and unknown fields. Reading does not rewrite the stored bytes;
  unrelated policies continue to execute. New/edited approvals retain the
  **2,100,000,000,000,000-sat** cap; resume/override reject oversized policies before
  auditing or clearing locks, and the final submission guard checks the cap.
- Journal validation now requires each consumed attempt to have a covering
  wallet/schedule timestamp watermark and its exact wallet/schedule/revision/index
  record. Binding/revision UUIDs, attempt identity/pay plan, resolved/consumed state,
  nonnegative sorted unique indices and required timestamps are validated before
  reads or writes. Legacy inputs validate before migration; new consumed entries
  gain complete evidence before validation/persistence.
- Invalid evidence retains the original bytes and a file/directory-fsynced first
  `.quarantine` copy. That file blocks reads and writes across relaunch, including
  if the journal is later replaced or removed. A quarantine-write failure latches
  that journal off. The shared journal stops this wallet's policies, all visible
  as **Needs attention**; malformed bytes are never automatically repaired.
- Journal regressions include **37 malformed-evidence cases**, invalid UTF-8,
  failed quarantine writes and valid-evidence preservation. The reviewer's monthly
  index-1 case pays once in New York at `1774753200000`, restores the original
  MMKV policy, records an invalid `-1` (also tested with a wrong nonnegative index),
  then switches to UTC at `1775016000000`. It remains at **one submission**, keeps
  both original/quarantine bytes and shows **Needs attention**; override cannot
  clear it. Persisted v1/dictionary inputs are still migrated conservatively.
- Both journal targeted runs passed **1,432 tests / 11 suites**, zero
  failures/errors/skips. The second also pins strict UTF-8 rejection and isolates
  invalid v1 revision evidence from invalid attempt indices.
- Amount targeted run: **1,390 tests / 10 suites**, zero failures/errors/skips.
  Store and executor regressions cover the maximum, maximum + 1, Long.MAX_VALUE,
  unknown fields, cancellation and an unaffected healthy payment.
- End-date targeted run: **1,387 tests / 10 suites**, zero failures/errors/skips,
  using Nix and `--max-workers=2 :app:testRegtestDebugUnitTest --tests "*Recurring*"`.

### Parity final verification and limits

The required full command ran **once**, without exclusions, after the final code
change: **2,817 tests / 72 suites / zero failures, errors or skips**. The recurring
package contributes **1,430 cases**; the targeted pattern additionally selects
**two** session/teardown regressions. APK assembly passed in the same **57-second**
invocation. Existing native-library stripping warnings were non-fatal.

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

All Gradle invocations used Nix and `--max-workers=2`; targeted iterations selected
`--tests "*Recurring*"`. Logs and the JSON count summary remain under
`/tmp/noah-android-recurring-parity-*`. All targeted runs passed; no pre-fix
Android duplicate-payment claim is made (negative indices already stopped sends;
this round additionally preserves quarantine and visible attention status).
Only Android recurring code/tests, this README and the Android M7 TODO line
changed. Shared fixtures needed no edits. iOS, RN, Send, Onchain, fee policy and
server were untouched. No emulator, physical Pixel 6, simulator or Docker was
accessed. No device/UI, funded/live, physical-push or real-power-loss verification
was run; these changes are covered by deterministic JVM tests and APK assembly.

## Round-1 fixes (2026-10-20)

Fix base: `feat/native-rewrite` local HEAD `bf8ac96f`. Android now matches iOS
`30a27dd8`, `eae1c5e1` and `9f6b42be` for these three findings. The user-decided
ArkInfo fee policy and `apps/fixtures/recurring-fee-cap.json` are unchanged.

### Journal migration and replay prevention

`noah-recurring-journal.json` now uses the portable **version 2** format described
in `apps/ios/RecurringTests/README.md`: `attempts`, cross-revision `consumed`
timestamp watermarks, revision-scoped `consumedIndices` sorted unique integer
arrays, and wallet/schedule-scoped `legacyUncertainty`. Binding includes wallet
fingerprint, network and local instance UUID; the original RN revision is null.
An attempt from a later revision never replaces earlier consumption evidence.

Migration completes durably on the first read, before an attempt can be replaced:

- v1 retains every timestamp watermark and reconstructs exact indices from all
  retained consumed attempts. Android's old `revisions[].throughIndex` values are
  also retained as known consumed indices. Every old timestamp/revision-watermark
  scope gains legacy uncertainty because overwritten indices are unknowable.
- Unversioned dictionaries reconstruct retained consumed attempts and mark every
  retained wallet/schedule scope uncertain, including a latest proven failure.
- A known revision/index, a timestamp at/before its watermark, **or** legacy
  uncertainty blocks execution. Unknown versions, incomplete/invalid evidence
  and failed durable migration writes stop sending and preserve the evidence.
- Legacy uncertainty stays **Needs attention** across zone changes, edits and
  destructive resume. Resume cannot clear it. Cancel and explicitly approve a
  fresh schedule with a new ID to continue; cancellation preserves old evidence.

The exact regression paid monthly index 1 in New York at `1774753200000`, retained
only its timestamp after revision B's proven pre-submission failure, rolled MMKV
back to A and changed to UTC at `1775016000000`. Before the fix it failed with
**expected 1 submission, observed 2**. It now stays at one. Spring/fall daily DST,
cross-revision replacement, reopened persistence, wallet/revision isolation,
malformed evidence, failed migration and cancel/fresh-approval paths are covered.

### Attended receipt and foreground runner

Attended settlement retains the normal Send terminal outcome, receipt and lock
through slow server sync and after execution finishes. The review sheet keeps
showing that outcome; **Done** acknowledges it before dismissing. Replaying the
review before or after Done cannot submit again. Unattended settlement still
acknowledges immediately. The pre-fix blocked-sync regression failed with
**expected succeeded, observed ready** and now passes.

`RecurringForegroundRunner` is registered with `MainActivity` as a lifecycle
observer. It waits **60 seconds** between checks while the activity is STARTED,
starts idempotently, and cancels on STOP. Each tick checks onboarding and enters
the existing shared executor. Stopping also revokes the tick's submission budget,
so a quote/preparation already in progress cannot submit after backgrounding.
Already-submitted settlement remains process-owned and finishes once.

Injected-minute tests drive the actual Android lifecycle registry and production
executor: becoming due without another foreground transition, restart without
extra timers, timer/push/screen/time-change coalescing, cancellation during quote
and preparation, settlement after STOP, per-tick suspension/wallet checks, stop
generation, and pause/cancel/edit/replace/restore races. Existing lifecycle-gate,
restore-recovery, session-reservation, journal and fee guards are reused.
WorkManager reminder workers remain advisory.

### Verification and limits

Every Gradle invocation used Nix and `--max-workers=2`. Passing targeted runs used
`:app:testRegtestDebugUnitTest --tests "*Recurring*"`:

- Journal migration: **1,377 tests / 8 suites**.
- Receipt retention: **1,378 tests / 8 suites**.
- Foreground runner: **1,384 tests / 9 suites**, then **1,384 / 9** after a test
  helper rename. Every passing run had **zero failures/errors/skips**.
- Two separate one-test pre-fix runs deliberately failed on the duplicate
  submission and cleared attended receipt, respectively; neither selected run
  was empty.

The required full test/APK command ran **once**, without exclusions, after the
final production change: **2,742 JUnit cases / 68 suites / zero failures, errors
or skips**. The recurring package contributes **1,382 cases**; the targeted
pattern also selects two real session/teardown regressions. APK assembly passed
in the same **1m 5s** invocation. Existing native-library stripping warnings were
non-fatal.

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

Logs and JSON summaries remain under `/tmp/noah-android-recurring-r1-*`.
Only Android recurring code, its activity lifecycle hook/tests, this README and
the Android M7 TODO line changed. Send, Onchain/M6, SDK pins, iOS, server, client,
fee fixtures and reminder workers were untouched. No emulator, physical Pixel 6,
simulator or Docker was accessed. No new device/UI, funded-payment, physical-push,
timed-notification or real-power-loss verification is claimed for this round;
these races are deterministic JVM regressions. Earlier live evidence below is
historical and was not rerun.

## Contract and safety

`RecurringSchedule` runs RN's local Gregorian calendar policy, including DST folds,
gaps, month-end clamping without drift, millisecond preservation, latest-due-only
execution, skipped backlog, finite schedules and exponential retry backoff. The
5,000-occurrence scan bound stops extreme backlog for manual review.

`RecurringStore` uses RN's **noah-wallet-storage** MMKV instance,
**recurring-payment-storage** key and version-0 Zustand envelope. Unknown envelope,
state and schedule fields survive mutations. Invalid/future envelopes preserve their
bytes and stop reads/writes; a failed write latches automation off until restart.
Native spending approval binds fingerprint + network + a local instance UUID in
the wallet datadir. Android backup/transfer rules already exclude this directory;
Noah's encrypted backups contain only SQLite. Same-seed replacement gets a new
instance. Creation/restore seals unbound legacy policies to an unverified prior
wallet before installing a replacement. Foreign policies stay visible/cancellable.

The independently fsynced, atomic occurrence journal precedes the MMKV in-flight
marker and Send journal. Portable v2 retains cross-revision timestamp watermarks,
exact consumed indices per revision and conservative legacy-uncertainty scopes
before attempts can be overwritten. Timezone changes can move a previously paid
index to a later timestamp; the durable index and legacy blocks prevent its replay
after MMKV rollback. See the round-1 migration rule above.

All money goes through the existing Send controller/journal and session. Its
exclusive recurring reservation drains counted calls, excludes new unrelated
activity/close/replacement, and outlives caller cancellation. The final guard runs
immediately before the SDK creating call, after checkpoint inspection: current
policy/marker, wallet identity/instance, persistent automation-stop generation,
suspend/restore/delete state, current-clock due occurrence/backoff, monotonic push
budget and captured fee policy must all still match. Pre-existing Lightning
checkpoints require review. Ambiguous outcomes retain both locks and never retry
automatically. Pauses/cancellation during settlement remain paused/removed.

Suspend/delete rotate the durable stop generation before waiting for the lifecycle
gate; failed teardown cannot revive pending approval. A generation-write failure
latches automation off. Lock order remains lifecycle gate → RestoreRecovery →
session reservation; callers already holding the non-reentrant gate pass
`walletMutationHeld = true`. Restore recovery, new creation and replacement use
the same gates. An uncertain outcome can only resume after history checking and a
two-step destructive warning; exact warning/confirmation and legacy ownership
are durably audited before locks clear. Resume skips already-due occurrences.

## Fee policy and attended approval

All **24** unchanged `recurring-fee-cap.json` cases are consumed through
`SharedFixtures`, along with 1,260 calendar, 36 policy and three zone-change cases.
The RN storage document is also loaded through `SharedFixtures`.

The quote captures `ArkInfo.feeSchedule.lightningSend` (min/base/highest ppm); the
pre-submit guard reuses those captured values. Missing ArkInfo uses min 20, base 0,
max 5,000 ppm. Arithmetic saturates safely in `Long`:

- `F(a) = max(min, base + ceil(a × maxPpm / 1,000,000))`.
- `cap = min(2 × F(a), max(200, floor(a / 50)))`.
- Lightning auto-pays only if both quote and F(a) fit the cap; Ark requires zero fee.
- Otherwise the occurrence becomes **Needs approval**, with no occurrence/Send
  marker, consumption, uncertainty or automatic retry, and a notification.
- **Review and pay manually** uses the exact quote in the same Compose Send
  confirmation component as attended Send, retaining recurring policy/journal
  guards. A review cannot approve a subsequently edited/resumed/paused policy.

Deliberately not ported from round-1 iOS: the old max(1%, 200 sats) fee policy and
blanket Lightning approval gate, superseded by the user's October 20 decision.
Android uses WorkManager reminders and a time/timezone broadcast receiver instead
of iOS notification-daemon/calendar callbacks. LNURL with a user note follows
Android attended Send's Lightning route so the note reaches the recipient; the
iOS reference unconditionally prefers an Ark route even with a note.

## Triggers and UI

Settings → Recurring payments provides create/edit, native date/time pickers,
three-occurrence preview, weekly/monthly/custom intervals, end date/count,
pause/resume/cancel, last attempt, needs-attention and needs-approval controls.
Editing starts a new explicitly approved revision while retaining visible runs.
Amounts use the numeric system keyboard.

Foreground and generated `recurring_payment_due` payloads join the process-owned
executor through `AppServices.ensureWalletOpen`. Push `due_count` is advisory;
local policy alone determines the payment. The independent 25-second push deadline
returns without waiting for cancellation-shielded settlement and revokes any new
submission. Already-submitted money still settles once.

Wake sync uses the generated authenticated client with serialized replacement
payloads containing only opaque schedule IDs and unix due seconds. Edits during a
request force another replacement; failures retain the durable pending retry flag.
Reminders/results use the existing notification channel and honor
POST_NOTIFICATIONS. WorkManager reminders recheck the current due date, policy,
suspension and wallet binding before displaying. Tapping opens the schedule screen.

## Initial verification and live evidence

The required full check ran **once**, without exclusions, after the final production
change: **2,725 JUnit cases, 67 suites, zero failures/errors/skips**. This includes
**1,369 recurring cases** and **560 Send cases**. APK assembly passed in the same
**58-second** invocation. Targeted checks ran while implementing; one targeted
zone-rollback regression deliberately failed before its fix. A prior PushJobs test
still expected the removed log-only placeholder and was updated to expect opening
and execution. The first race-test compile required an explicit parameter-array
type, corrected before the passing runs.

```sh
NIX_CONFIG='eval-cache = false' nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

The environment-only Nix option avoids the shared evaluation-cache SQLite lock;
it changes no project or shared cache configuration. All Gradle invocations used
`--max-workers=2`. Android-test APK assembly also passed. Raw logs remain under
`/tmp/noah-android-recurring-*`; portable summaries/receipts are in `evidence/`.

The funded invocation passed **1 test, zero failures/skips**, 19.064 seconds;
production-lifecycle cleanup passed **1 test, zero failures/skips**, 5.226 seconds.
Together with storage, **four distinct instrumentation tests passed**. The separate
pre-schedule setup failure below remains documented and is not counted as passing.

Live receipts: [sender and Ark peer](evidence/live.json),
[independent CLN receipt](evidence/cln-receipt.json),
[full JUnit summary](evidence/junit-summary.json).
Exactly **one 1,000-sat Ark send**, sender movement **2**, zero fee, arrived as
peer movement **1**, +1,000 sats. Exactly **one automatic 1,000-sat BOLT12 send**,
sender movement **3**, paid **79 sats** in fees: quote 79, captured worst-case
schedule fee **83**, cap **166**. CLN independently recorded **paid**, **1,000,000
msat received**, pay index **9**, hash
`2c0c5e7754b0b45833b2c9f87b8b1e359e0df056f17c0e4848ad801aaa3fd064`.
Push/foreground races, repeat, pause and cancel left the sender at exactly two
new payments total. Both durable occurrences were consumed, Send unlocked and
the server pending flag clear. No attended approval was used for Lightning.

Real Device-panel inspection covered the populated list, editor, preview, numeric
Gboard, native notification intent routing, saving an edited future policy and
cancelling it back to the empty list. The finish reviewer marked the scoped UI
**Ship** after the end-date display correction; the documentation check confirmed
an extension of existing controls/tokens with no new design artifacts. Captures:
[list](evidence/list.png), [editor](evidence/editor.png),
[preview/end date](evidence/preview.png), [numeric keyboard](evidence/numeric-keyboard.png).
TalkBack/large-font/dark/tablet/landscape checks were not run. Shared amber-text
contrast and status-bar clipping are inherited theme/shell drift, not changed here.

Switching input methods during the first keyboard inspection produced an emulator
ANR. Its captured main stack was waiting in
`HardwareRenderer.syncAndDrawFrame`, with RenderThread in framework frame-metrics
reporting; no recurring code was on the blocked stack. After reinstall/restart,
the real numeric keyboard, Done, scroll and Save worked and a clean screenshot was
captured. This is a test-environment limitation, not proof of an app-wide fix.

Dedicated AVD: **NoahRecurringM7Sol6506**, Pixel 6 profile / Android 15 API 35 ARM64,
explicit **emulator-5590**. The Device panel and agent-device use that exact serial
and the host/session flags returned by `device_open`. Protected emulators, the
physical Pixel 6 and iOS simulators were untouched.

Two real storage instrumentation tests passed (**2 tests, zero failures**, 3.596 s):
RN MMKV envelope + persisted in-flight marker through a fresh reader, and a real
SDK-created wallet's instance change/refusal during restore/deletion markers.

The first live invocation stopped at server registration before schedules existed:
port 3100 from prior Send evidence was unavailable; the existing server is on 3000.
The funded wallet was retained. Before resuming setup, the test requires an owned
boarding marker, an empty recurring store, no Send/occurrence journals (including
Send's backup), and no outgoing SDK sends. It neither regenerates the main seed
nor funds/boards again. This setup failure is not counted as a passing funded test.

Funding: **100,000 onchain sats once**, transaction
`05d98938c1370b9fb88cfbe33462093d051cf4dd6ea02c62cec631542fe51411`.
One funding block and the server-required three boarding confirmation blocks were
mined through `just bcli`; **30,000 sats** boarded through production M6 APIs.
No blocks are needed for the subsequent recurring payments. A fresh second native
wallet in the owned emulator is the Ark recipient; local CLN supplies a reusable
BOLT12 offer. A `just bark --help` probe earlier created a transient CLI container;
that exceeded the Docker restriction and was not used again. All remaining stack
operations use only the allowed `just bcli` / `just cln` wrappers; no existing
container was stopped, restarted, reconfigured or reset.

Reproduction: on an empty dedicated emulator, build the regtest APK and Android-test
APK, then select only `com.noahwallet.recurring.RecurringLiveTest` with
`noahRecurringLive=disposable-emulator` and `offerRecipient=<fresh local CLN offer>`.
The test publishes `files/recurring-live-funding-address.txt`; fund once with
100,000 onchain sats and confirm it. Wait for `recurring-live-board-ready.txt`, then
mine exactly the reported boarding confirmations. Non-secret results are written
to `files/recurring-live-evidence.json`. Never rerun a failed payment before
inspecting journals and history. `resumeSetupOnly=owned-wallet-with-no-send-journals`
is only for the documented pre-schedule setup failure and refuses any send journal,
policy or outgoing movement. Normal runs require an empty wallet.

No physical FCM/UnifiedPush delivery, timed day-before/overdue notification, real
power loss between journal boundaries, external LNURL provider, funded RN upgrade,
or API-24 device is claimed. Push is exercised by invoking production PushJobs,
not by a remote transport. Filesystem failures, deadline/clock jumps, policy
rollback and lifecycle races use deterministic tests.

Cleanup completed: cancelled the future inspection policy through Compose, then
used the production lifecycle to deregister/delete the wallet and its saved seed.
Disabled only the owned CLN test offer. Immediately before shutdown,
`adb -s emulator-5590 emu avd name` confirmed **NoahRecurringM7Sol6506**. Closed it
through the Device panel, verified the serial was absent and its QEMU process had
exited, then deleted only that AVD; both its `.avd` directory and `.ini` are absent.
[Cleanup evidence](evidence/device-cleanup.json) records these checks. No build
output was created outside the worktree; logs/public summaries remain under the
`/tmp/noah-android-recurring-*` prefix. Temporary runtime offer/mining-address files
were removed. Protected devices and existing Docker service configuration/lifecycle were not changed.

## Implementation map

Each location is relative to `apps/android/app/src/`:

1. Schedule math/policy: `main/java/com/noahwallet/recurring/RecurringSchedule.kt:38`.
2. RN storage/migration/binding: `main/java/com/noahwallet/recurring/RecurringStore.kt:15`;
   real binding at `RecurringStore.kt:67`.
3. Durable journal/executor: `main/java/com/noahwallet/recurring/RecurringJournal.kt:57`,
   `RecurringExecutor.kt:60`; exclusive reservation in
   `main/java/com/noahwallet/services/wallet/WalletSession.kt:143`; stop generation
   in `main/java/com/noahwallet/services/AppServices.kt:107`.
4. Unattended fee cap: `main/java/com/noahwallet/recurring/RecurringFeePolicy.kt:11`;
   quote capture in `main/java/com/noahwallet/send/SdkSendWallet.kt:24`.
5. Screens: `main/java/com/noahwallet/recurring/RecurringPaymentsScreen.kt:20` and
   `RecurringPaymentEditorScreen.kt:21`; Settings destination/row in
   `main/java/com/noahwallet/ui/settings/SettingsScreen.kt:32`.
6. Triggers/deadline/notifications: `main/java/com/noahwallet/recurring/RecurringRuntime.kt:44`,
   `RecurringPushDeadline.kt:8`, `RecurringTimeReceiver.kt:7`,
   `RecurringNotifications.kt:18`; push routing in
   `main/java/com/noahwallet/services/push/PushJobs.kt:70`.
7. Server wake synchronization: `main/java/com/noahwallet/recurring/RecurringExecutor.kt:222`.
8. JUnit coverage: `test/java/com/noahwallet/recurring/RecurringFixtureTest.kt:13`,
   `RecurringRaceTest.kt:11`, `RecurringExecutorTest.kt:87`, plus store/journal/deadline
   and real session/lifecycle regressions.
9. Live/storage acceptance: `androidTest/java/com/noahwallet/recurring/RecurringLiveTest.kt:22`
   and `RecurringStorageDeviceTest.kt:16`; portable evidence in this directory.

## 0.5.0 review fixes, round 1 — approval-copy parity

Unattended fee skips now use iOS's reason-specific approval copy: a quoted fee
shows its actual sats and automatic cap; a published schedule fee shows its
worst-case sats and cap. Ark copy explains the zero-fee requirement. The fee
policy, precedence and shared fixtures are unchanged. Exact-copy assertions
cover both Lightning reasons, simultaneous quote/schedule excess, Ark fees and
within-cap payments. RecurringStoreTest, RecurringFixtureTest and
RecurringExecutorTest: **1,357 tests passed**, zero failures/errors/skips.

### Round-1 final Android verification

Ran the requested full check exactly once after all code changes:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**2,770 tests in 70 suites passed**, zero failures/errors/skips; regtest APK
assembly succeeded in the same **1m 4s** run. Final targeted runs for fixes 1–5
passed **17 / 40 / 19 / 61 / 1,357 tests**, respectively (overlapping suites).
No live device, funded broadcast or actual process kill was used; interruption
and recovery were checked deterministically. No iOS, server or RN files changed.

## Initialized launch I/O (base `37ab2125`)

Executor storage operations now suspend on IO while trigger coalescing, synchronous
Send submission and the final pre-payment guard keep their existing ownership.
Each run awaits the stop generation, current wallet binding/database, policy,
quarantine, legacy scopes, consumed timestamp watermarks and exact revision indices
before quoting/submitting. There is no cached readiness or journal evidence.
`RecurringStore.lock` and journal monitors remain inside the IO blocks; mutation
validation and its write share one block. Occurrence journal → in-flight marker →
Send journal → guarded SDK call, and consumption → policy advancement → Send
acknowledgement, retain their order. The final synchronous guard still rereads
identity, stop generation, policy, budget and consumption inside the session's IO
reservation immediately before payment. UI reload now awaits its IO read.

`RecurringLoadingTest` holds each of generation/binding/policy/journal reads off
Main while foreground and push triggers join the same unfinished run. It proves
zero quotes/submissions while pending, consumed evidence cannot replay afterward,
failed reads prevent execution, and stop/replacement during binding cannot pay.
The targeted payment/widget-value run passed **2,053 cases / 31 suites**, zero
failures/errors/skips, including **1,455 recurring cases**. Final full-suite and
funded-device StrictMode results are in [ParityTests](../ParityTests/README.md#initialized-wallet-main-thread-io-base-37ab2125).

## Launch-threading regression fixes (base `1d9aff5d`)

Settlement captures the exact Send attempt, outcome and error before any storage
suspension. It validates the wallet/database binding inside the durable mutation,
consumes only that occurrence, advances only its policy revision, and acknowledges
only the captured attempt. A later attended payment's failure cannot turn a paid
occurrence into a retry, and settlement cannot dismiss that later payment's result.
Pause and safety failure latches survive advancement; edit/cancel/replacement
retain their own state. Ambiguous settlement also checks binding/revision and uses
the captured error before marking attention.

`RecurringSettlementTest` derives its primary race from the review probe: hold
A's post-payment binding read, acknowledge A, submit B with a proven failure,
release A, then advance 15 minutes. It **failed before / passes after**: A remains
consumed and paid exactly once, B retains its result, and no duplicate occurs.
A second test changes pause/cancel/edit/suspend/wallet/instance/failure-latch state
at settlement and verifies ownership, consumption and no replay.

Every storage suspension introduced by `85c80d98` was rechecked. Startup generation,
open, date refresh, binding and marker recovery cannot authorize submission:
quotes and safety reads still lead to occurrence journal → in-flight policy →
counted Send reservation → final synchronous guarded SDK call. That final guard
rereads stop generation, the runtime storage-failure latch, suspension/deletion/
restore markers, exact wallet/instance/policy, budget, consumption and fee policy.
User changes during any earlier read/write invalidate the comparison; existing
loading/race tests cover held/failed reads and writes, quote/preparation changes,
stop/replacement, backoff/clock jumps, ambiguous outcomes and storage-failure latches.
Policy mutations retain their store locks/update-if comparisons, and wake-sync
recompares the replacement payload after requests. Post-submission work settles
already-submitted money even after a stop/deadline; unresolved outcomes retain
markers and cannot retry automatically. Consumption remains durable before
policy advancement and exact-attempt acknowledgement.

Final counts and owned-emulator StrictMode evidence are in
[ParityTests](../ParityTests/README.md#launch-threading-review-regressions-base-1d9aff5d).

The final full check ran once: **2,899 tests / 88 suites passed**, zero failures/
errors/skips, with APK assembly in the same run. Owned-AVD Recurring entry and a
75-second foreground sample covering the timer tick measured zero main-thread
StrictMode violations. The initialized wallet was unfunded; settlement races use
permanent deterministic tests, with no live recurring payment or power loss.
