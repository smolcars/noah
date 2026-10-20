# Native recurring payments — M7

Implementation base: `feat/native-rewrite` at `cb802af7` (round-8 Send audit).
Swift 6, iOS 18 minimum, bark-bolt-ffi **0.4.0**.

## Journal revision settlement (2026-10-20)

Base: `feat/native-rewrite` at `172446b6`. Fix: `85b8e45e`.

The Android finding is also present on iOS: the former settlement branches did
not check a policy revision at all, and Check payment status supplied its freshly
loaded policy as the original. `RecurringExecutor.swift:233` now permits policy
advancement only when the stored revision equals `entry.policyRevision` (including
legacy null). Current wallet identity/instance and stored binding must both match
(`:217`). Terminal journal resolution/consumption happens before policy writes
(`:230`) and acknowledgement (`:252`), independently of policy revision matching.
An obsolete receipt never pays the edited policy's amount, recipient or count.

The run model lacks recipient/revision ownership and the journal lacks a policy
snapshot, so obsolete outcomes only resolve the journal; they add no history or
counters to the edited row. A fallback success notice requires the original paid
revision (`:247`); a status check holding only an edited snapshot stays silent.
A retained actual paid snapshot still produces the original recipient/amount's
notice after edit/cancel. Same-revision settlement preserves pause and safety
failure state while recording money already submitted.

Audit fixes in the same executor: uncertainty now shares `markUncertain` (`:205`),
which checks current/stored binding and the journal revision before changing a
fresh row. The submission catch verifies the exact plan, binding and revision
(`:189`) rather than just an occurrence index. Marker recovery reloads and
compares binding plus the entire row (`:81`) after earlier notification awaits;
a cancellation/edit cannot be overwritten by an old recovery snapshot.

Permanent regressions in `NoahTests/RecurringExecutorTests.swift`:

- `oldSuccessfulReceiptCannotSettleAnEditedPolicyRevision()` mirrors Android's
  review probe: pay 1,000 sats, fail Send receipt removal, edit another recipient
  to 2,000 sats with a one-payment limit, recover and check status twice. It pins
  the entire edited row, truthful notifications, consumption/acknowledgement and
  one submission. Before the fix: **1 test / 1 failure / 0 skips**, reproducing
  false completion of the edited policy.
- `statusCheckOutcomesCannotMutateAnotherPolicyRevision` covers success,
  already-paid, proven failure and continued ambiguity, unchanged edited history,
  Send lock ownership and no automatic retry.
- `changesAfterSubmissionKeepSettlementOwnership` covers edit, wallet replacement,
  instance replacement, persisted binding replacement, failure latch and suspend;
  a post-edit notice describes the actually paid original policy.
- `recoveryNotificationSuspensionCannotRecreateCancelledPolicy()` cancels another
  marked schedule while recovery awaits its first result notification.

The last three definitions were also run against the old executor: **3 failed
definitions / 10 expanded runs (7 failed, 3 passed) / 0 skips**, exposing stale
outcome writes, a cleared failure latch and recreation after cancellation. The
persisted-binding replacement parameter was added afterward and passes in both
final runs. The first probe selector includes its trailing `()`; the final focused
run selects whole suites, with nonzero execution.

Both settlement callers (submission `:185`, status check `:260`) and uncertainty
callers (`:191`, `:228`) were audited. Launch marker recovery, post-open/post-quote
reads, final guarded Send submission, manual review and server-wake synchronization
retain their policy/binding/generation checks across suspensions. AppModel startup,
activation and the foreground timer, push jobs, background scheduler and screen
entry all share this executor; none independently settles a freshly loaded policy.
Submission budgets, storage health/failure latches, lifecycle stops, wallet/instance
binding, fee policy, legacy uncertainty and independent rollback/replay evidence
remain in force. Android's corresponding audit and final command are in its notes.

Verification: the focused executor/store/journal/write-failure/background/
foreground/reservation run passed **60 definitions / 120 expanded runs / 7 suites**,
zero failures/skips. Full **NoahTests ran once**, without suite exclusions:
**606 total / 583 passed / 23 opt-in skipped / 0 failed**, **122 suites**;
expanded runs were **848 passed / 23 skipped**. All Xcode runs used Nix,
`/opt/homebrew/bin/xcodegen generate`, normal code signing, `ARCHS=arm64
ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, disabled parallel testing and automatic diagnostic
collection, and DerivedData under `/private/tmp`.

Owned simulator: **Noah Recurring Revision Sol 41f7ba6f**, iPhone 17 / iOS 27.0,
UDID **C9301570-C7F4-46EB-BEAE-A7EA9C72BBB4**. Its exact name/UDID and **Shutdown**
state were confirmed immediately before deletion, then absence verified. Removed
only owned `/private/tmp/noah-recurring-revision-41f7ba6f-dd` and all **four** owned
`.xcresult` bundles after exporting every summary/test tree. Logs, summaries and
`cleanup.json` remain under `/private/tmp/noah-recurring-revision-41f7ba6f-*`.
The Device panel rejected the exact owned UDID twice as unavailable; Xcode used
that simctl-verified simulator successfully.

No funded/live payment, UI-tap verification or physical notification delivery was
run. Protected/shared simulators, Android emulators, physical devices and Docker
were untouched. No MMKV opening, logging, server or RN code changed.

## Storage strictness — envelope version (2026-10-20)

Base: `feat/native-rewrite` at `c6283d16`. Compare the original version token,
without NSNumber conversion. Only an absent legacy version or the exact token
`0` is accepted (surrounding JSON whitespace is allowed). RN's store retains
Zustand's default numeric version 0, serialized by `createJSONStorage` through
`JSON.stringify` as `0`; even JavaScript negative zero serializes as `0`.

Expected outcomes: absent / `0` → readable and writable; `1e-400`, `0.0`, `-0`,
`0e5`, `"0"`, `1`, `1e400`, booleans, null, objects and arrays → entire store
unavailable, no execution or writes, original bytes preserved across reopen.
`RecurringVersionTests` uses all 11 populated RN rows, both executor triggers,
binding, upsert, pending, cancellation and reopen. The pre-fix targeted run
reported **2 test definitions / 15 expanded runs**, with **4 expanded failures**
(the numeric zero variants) and 11 passes, zero skips. The rejected-overflow
`1e400` case also stays closed when Foundation rejects it during JSON validation.

## Storage strictness — timestamp parity (2026-10-20)

Any non-integral start/end, next-run, created/updated, retry, in-flight-start or
run-history scheduled/attempted timestamp makes the entire row unsupported.
`RecurringPayment.validPolicy` and approval validation require finite whole
milliseconds. Stored timestamps are never rounded. Existing per-row isolation
keeps the original row visible as **Needs attention**, stopped and cancellable;
its values and raw JSON survive unrelated writes. RN's normal UI/runner gets
whole milliseconds from `Date.now()` and `Date` calendar math. The native clock
now captures whole milliseconds too, so new policies and execution updates remain
readable under the same rule; this does not normalize saved data.

Both platforms assert every row's `expectedClassifications` entry in the shared
`recurring-rn-range.json`, including its reason in failure output. Only the four
representable count rows run; all seven unsupported rows stay stopped. The
`start-0` and `end-fraction` rows retain 1000.5 and 2000.5 unchanged. Existing tests
cover cancellation, unsupported edit/resume/override/new-approval refusal,
unchanged journals and server-wake exclusion. `RecurringTimestampTests` adds all
nine time-field paths, unrelated healthy execution exactly once, no unsupported
journal, preserved JSON through pause/reopen/pending writes and cancellation. A
new native-clock policy also remains executable after store reopen.

Before the timestamp fix, the selected range/timestamp/version run failed as
expected: **9 definitions / 39 expanded runs / 23 expanded failures**, zero skips.
Version regressions already passed in that run. After both fixes, the broader
14-suite run passed 78/81 definitions; its three failing range test definitions
exposed a helper matching the new expectations map instead of `state.schedules`.
The helper now scopes its byte-preservation check to actual schedule rows.
Final focused range/timestamp/version verification: **9 definitions / 39 expanded
runs / 3 suites**, zero failures or skips.

## Storage strictness — final verification (2026-10-20)

Full **NoahTests ran once**, without suite exclusions: **500 total / 481 passed /
19 opt-in skipped / 0 failed**, **107 suites**, **42.369 seconds** of execution;
**725 passed / 19 skipped** expanded runs. The Android README records its passing
required full unit-test/APK command. The fee policy and shared fee cases are
unchanged; no launch/seed/upgrade-test code or other stores changed.

Every Xcode build used Nix, `/opt/homebrew/bin/xcodegen generate`, normal code
signing, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, disabled parallel testing
and automatic diagnostics, and DerivedData under `/private/tmp`. Suite selectors
ran nonzero tests; no function selectors were used. The first environment-only
attempt stopped before building because Nix's PATH omitted XcodeGen; subsequent
commands used its installed absolute path. The Device panel rejected the owned
simulator twice with `No device FEC6D3C3-719B-496C-A35E-A67B437D8DD3 on host local.`;
Xcode used that exact `simctl`-verified owned UDID successfully.

RN's original writer ran read-only through real Zustand and reproduced the
original envelope byte-for-byte (SHA-256
`c96c5f940008ac97f3f0757b79cef276381a56ac8e9fcca4a8d1cd27b23954b3`), excluding
only the shared native expectations metadata. Logs, JSON summaries/test trees,
writer output/receipt and cleanup evidence remain under
`/private/tmp/noah-recurring-strict-5fec2083-`. No funded/live payment, physical
notification delivery, UI inspection or upgrade test was attempted; these are
deterministic storage/executor regressions, with upgrade work assigned separately.
Protected simulators, Android emulators, the physical Pixel 6 and Docker were
untouched.

Cleanup completed: owned **Noah Recurring Strict Sol 5fec2083**, iPhone 17 /
iOS 27.0, UDID **FEC6D3C3-719B-496C-A35E-A67B437D8DD3**, was confirmed by exact
name/UDID and **Shutdown** state immediately before deletion, then verified absent.
Removed only `/private/tmp/noah-recurring-strict-5fec2083-dd` and all **five** owned
`.xcresult` bundles after exporting their summaries/test trees. The cleanup JSON
records each removed path. No shared device or build artifacts were removed.

## RN wide-number storage compatibility (2026-10-20)

Base: `feat/native-rewrite` local HEAD `38af11f3`.

Before this fix, `RecurringStore.load()` decoded the entire schedule dictionary
at once. The RN-written range fixture failed the selected simulator regression
with `Number 9223372036854776000 is not representable in Swift`: **1 test ran,
1 failed, 0 skipped**. Because `change()` first calls `load()`, that failure also
blocked cancellation and unrelated writes. A probe using the unchanged production
`RecurringPayment` decoder confirmed that 3e9, MAX_SAFE_INTEGER and the serialized
integer `9223372036854775000` decode exactly as `Int`; 2^63 (RN's token
`9223372036854776000`), 1e100 and Number.MAX_VALUE fail. Fractional start/end
milliseconds decode normally on iOS. The fixture's two starts beyond JavaScript's
Date range decode but fail policy validation, previously stopping the whole store
as well. Fractional counts are rejected by RN's validator, but a malformed saved
fractional count is now also isolated to its row.

- Decode and validate each row independently. Unsupported rows remain visible as
  **Needs attention**, with Android's cancel-and-create guidance. Their display
  model is explicitly marked unsupported and cannot plan, execute, resume, edit or
  destructively override. Its placeholder is never written back. Cancel remains
  available; unsupported rows are excluded from date refresh and server wakes.
- Representable counts retain `Int` and normal scheduling, including 3e9 and
  MAX_SAFE_INTEGER. All counts beyond `Int.max` stay stopped; no unlimited-limit
  substitution or scheduling-equivalence assumption is made.
- Foundation validates the envelope, while a small object-value slicer retains
  original JSON tokens through binding, pending-state updates, healthy schedule
  payments/edits, pause, reopen and cancellation of other rows. Unsupported rows
  remain verbatim, including unknown fields and numeric literals. Invalid or
  future-version envelopes still stop reads/writes and retain original bytes.
- `apps/fixtures/recurring-rn-range.json` is a shared copy of Android commit
  `a4ea8f0e`'s 11-row RN-written resource. Android's original resource is retained
  unchanged; no Android code changed. Re-running RN's actual validator, factory
  and Zustand `persist` / `createJSONStorage` writer reproduced both copies
  byte-for-byte (SHA-256 `c96c5f940008ac97f3f0757b79cef276381a56ac8e9fcca4a8d1cd27b23954b3`).
  The shared-fixtures README records provenance and the writer invocation.

Verification: focused store/executor/parity/range run **54 tests / 104 expanded
runs / 4 suites**, zero failures/skips. Final executor/range run after the
unsupported status-check guard: **42 tests / 72 expanded runs / 2 suites**, zero
failures/skips. The five new range test definitions expand to 12 runs; they cover
six RN schedules paying exactly once, all five unsupported rows staying stopped
and byte-for-byte unchanged, resume/edit/override/new-approval refusal, status
checks preserving existing journal bytes, cancellation/reopen, malformed counts,
and unknown nested/string/number token preservation. One intermediate targeted
build caught a Swift 6 actor-isolated test-argument constant; marking that immutable
list `nonisolated` fixed it. That build ran zero tests.

Full **NoahTests ran once**, without exclusions: **496 total / 477 passed /
19 opt-in skipped / 0 failed**, **105 suites**, **37.223 seconds** of execution;
**698 passed / 19 skipped** expanded runs. All invocations used Nix, XcodeGen,
normal code signing, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, disabled
parallel testing and automatic diagnostic collection, and DerivedData under
`/private/tmp`. The baseline function selector included its trailing `()`.
No funded/live payment, physical notification delivery, UI-tap verification or
Android tests were run; these are deterministic compatibility regressions.
The shared fixture filename also appears in Android's local resources; read-only
inspection of its pinned AGP 9.4.1 `ProcessJavaResTask.CreationAction` confirmed
`DuplicatesStrategy.INCLUDE`, so its unchanged identical copy is supported.

Owned simulator: **Noah Recurring Range Sol 24fa25b5**, iPhone 17 / iOS 27.0,
UDID **BCE5DCA2-C3DA-4317-B7DA-197AEE458A6F**. Confirmed exact identity and
**Shutdown** state before deletion, then verified absence. Removed owned
`/private/tmp/noah-recurring-range-sol-24fa25b5-dd` and all **five** result bundles
after exporting summaries/test trees. Logs, JSON summaries, the RN writer output,
decode probe and cleanup evidence remain under
`/private/tmp/noah-recurring-range-sol-24fa25b5-`. Protected/shared simulators,
Android emulators, the physical Pixel 6 and Docker were untouched.

## Round-4 submission/status race (2026-10-20)

Base: `feat/native-rewrite` at `95cd6af0`. Fix: `ee261608`.
Only iOS recurring executor/UI/tests and the two requested docs changed.

- `RecurringExecutor.swift:258` rejects status checks while execution is running;
  `RecurringPaymentsView.swift:53` disables **Check payment status** in that state.
- `RecurringExecutor.swift:249` explicitly leaves a live `.submitting` state
  unsettled instead of marking it uncertain. Successful submission then records
  its single consumed occurrence and leaves the schedule active.
- Settled uncertain attempts still expose the status check and reconcile through
  Send. No Send internals changed. Notification taps still open the recurring
  screen, whose screen-open execution retains every existing policy/fee guard.

Permanent regressions in `RecurringExecutorTests`:
`statusCheckDuringSubmissionDoesNotStopSuccessfulSchedule()` reloads the screen's
schedule data and checks status while the guarded payment is submitting. It pins
unchanged in-flight policy/journal, no reconciliation or attention notification,
one submission/consumption, an active successful schedule and no repeat payment.
`settledUncertainAttemptStillAllowsStatusCheck()` verifies a timed-out attempt can
later reconcile to a proven receipt, consume once and release the Send lock.

### Verification and cleanup

Before the fix, selected
`-only-testing:NoahTests/RecurringExecutorTests/statusCheckDuringSubmissionDoesNotStopSuccessfulSchedule()`:
**1 test ran / 1 failed / 0 skipped**, with three failed assertions demonstrating
the changed in-flight policy, stopped successful schedule and attention notice.
The test still submitted once and consumed the occurrence. Xcode's automatic
post-failure `simctl diagnose` stalled; only that owned diagnostic child was
terminated, letting Xcode finish with exit 65 and export its failure summary.

After the fix, executor/foreground/background/reservation/fee-policy suites:
**49 tests / 81 expanded runs / 5 suites / 0 failures or skips**, 8.342 seconds.
Full **NoahTests ran once**, without exclusions: **491 total / 472 passed /
19 opt-in skipped / 0 failed**, **104 suites**, 39.663 seconds of test execution;
**686 passed / 19 skipped** expanded runs. All invocations used Nix, XcodeGen,
normal code signing, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, disabled
parallel testing and DerivedData under `/private/tmp`. Passing runs disabled
automatic diagnostic collection. No funded/live payment, physical notification
delivery or UI-tap verification was attempted; these are deterministic regressions.

Owned simulator: **Noah Recurring R4 Sol 611d17d7**, iPhone 17 / iOS 27.0,
UDID **681E6EFF-377F-46EE-A1B5-E234470DE9C3**. Confirmed exact identity and
**Shutdown** state before deletion, then verified absence. Removed owned
`/private/tmp/noah-recurring-r4-sol-611d17d7-dd` and all **three** result bundles
after exporting summaries/test trees. Logs, JSON summaries and cleanup evidence
remain under `/private/tmp/noah-recurring-r4-sol-611d17d7-`. Protected/shared
simulators, Android, the physical Pixel 6 and Docker were untouched.

## Round-3 cross-platform review fixes (2026-10-20)

Base: `feat/native-rewrite` at `be45d670`. Only iOS recurring code,
WalletLifecycle's stop latch, notification-response routing, tests and these docs
changed. The user-decided fee policy and `apps/fixtures/recurring-fee-cap.json`
are unchanged. Android, RN, server, Send/Onchain and SDK pins were untouched.

- **Journal validation/quarantine** (`RecurringJournal.swift:62`): validate all
  scopes, nonnegative sorted unique consumption indices, pay-plan/attempt identity,
  timestamps and consumed/resolved consistency before accepting or mutating any
  journal. A consumed v2 attempt must also have its exact revision/index and a
  covering timestamp watermark. Validate legacy inputs before migration and the
  migrated state before writing. Invalid original bytes stay untouched; the first
  `.quarantine` copy is fsynced and retained. A quarantined journal stops this
  wallet across relaunch; all its policies show **Needs attention**. No automatic
  repair or replacement of malformed evidence occurs.
- **Foreground revocation** (`RecurringForegroundRunner.swift:27`, final guard
  `RecurringExecutor.swift:172`): each minute tick owns an inherited revocable
  submission budget. Scene deactivation revokes it before cancelling the timer,
  so an already-started quote/preparation cannot submit. A submitted payment still
  settles and consumes once; reactivation starts a fresh budget.
- **Failed stop writes** (`RecurringStore.swift:24`, `WalletLifecycle.swift:138`,
  `RecurringExecutor.swift:166`): a failed policy write latches that store off until
  relaunch reads durable state. A failed lifecycle generation write latches the
  associated settings instance off before throwing; resume/reads cannot clear it,
  but a successful durable stop can. Both latches are checked immediately before
  submission. Injected pause/cancel/edit and suspend/delete write failures submit
  zero payments even after the underlying writer recovers.
- **Legacy uncertainty** (`RecurringExecutor.swift:82`, `:329`): check it separately
  from due planning, including paused/future/already-needs-attention policies.
  Resume, edit and destructive override reject it before clearing errors, Send
  locks or audit/consumption state. Android's guidance is used verbatim:
  “Noah can't reconstruct this schedule's earlier payment history. Cancel it and
  explicitly approve a new recurring payment.” Cancel plus approval of a fresh ID
  remains available and preserves old evidence.
- **LNURL notes** (`RecurringLive.swift:72`): retain Lightning for any nonempty note,
  including when an Ark route exists. RN `recurringPayments.ts:461` forwards the
  comment to `usePayments.ts:519`; `repeatPayment.ts:153` allows Ark direct only
  without a comment. Amount bounds and recipient-visible notes are retained.
- **Notification taps** (`AppDelegate.swift:21`, `RootView.swift:91`): register the
  notification-response delegate and open the recurring screen for reminder,
  result and due taps, including Expo/nested payloads. A cold-launch tap remains
  pending until wallet navigation exists. Result notifications now carry their
  recurring type as reminders already did; attention notifications use the saved
  reason instead of suggesting an unsafe resume.
- **End date** (`RecurringPaymentEditorView.swift:107`): retain **23:59:00.000**.
  RN `RecurringPaymentEditorScreen.tsx:132` calls `parseLocalDateTime(endDate,
  "23:59")`, whose Date constructor (`:52`) explicitly sets seconds/milliseconds
  to zero. Tests pin the final included millisecond in UTC and New York's fall DST
  day. The Android 23:59:59.999 value would extend the approved RN policy.
- **Amounts/version** (`RecurringSchedule.swift:47`, `RecurringStore.swift:38`,
  `:52`): new/edited approval is capped at **2,100,000,000,000,000 sats**. An
  oversized RN policy remains visible as **Needs attention**, with its exact amount
  and original bytes retained; resume/override cannot activate it. Other valid
  policies still run. Reject string, boolean, null, object, array, fractional and
  unsupported envelope versions without changing their bytes; an absent version
  remains the legacy v0 case.

### Round-3 verification

All four `/private/tmp/noah-m7-review-probes.swift` probes are permanent regressions
in `RecurringJournalValidationTests`, `RecurringBackgroundRegressionTests` and
`RecurringLegacyRegressionTests`. Before fixes, **4 tests ran: 3 failed / 1 passed**:
quote-after-deactivation submitted once, the negative-index rollback submitted
again and overwrote evidence, and legacy override cleared needs-attention status.
The revision-A / failed-B / zone-rollback probe already passed and remains pinned.
The pre-fix Xcode process stalled finalizing its result bundle after reporting
those verdicts; only that owned process was terminated. Its raw log is retained.

Passing focused runs, each with zero failures/skips (test definitions / expanded
individual runs):

- Journal/executor/store: **43 / 82**, three suites.
- Foreground/executor/reservation: **43 / 74**, four suites.
- Write-failure/executor/store/reservation/lifecycle: **62 / 118**, five suites.
- Legacy/executor/journal validation: **40 / 81**, three suites.
- Parity/legacy/fee/schedule/store/executor/push/LNURL: **64 / 111**, eight suites.

The final focused run also verifies the updated legacy guidance for policies
already marked needs attention. The first parity compile caught Swift 6's
non-Sendable notification-response capture; parsing now occurs before crossing
to MainActor and only the routing decision crosses. That attempt ran zero tests;
it is separate from the passing focused run above. All targeted selectors named
whole suites; all ran nonzero tests. Function selectors, when used, require `()`.

Full **NoahTests ran once**, without exclusions: **477 total / 458 passed /
19 opt-in skipped / 0 failed**, **104 suites**, **49.822 seconds** of test
execution. Parameterized execution reports **654 passed / 19 skipped** individual
runs. All Xcode invocations used Nix, XcodeGen, normal signing,
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, disabled parallel testing and
DerivedData under `/private/tmp`. The 24 shared fee-cap cases passed unchanged.
No funded/live payment, physical push, timed OS notification, external provider
or funded RN upgrade was attempted; deterministic tests cover these changes.

Owned simulator: **Noah Recurring R3 Sol 7511ef4b**, iPhone 17 / iOS 27.0,
UDID **6C0724A5-D277-4D93-AC32-EAE37B49D8EA**. After the full pass, confirmed
its exact identity and **Shutdown** state, deleted it and verified absence.
Removed `/private/tmp/noah-recurring-r3-sol-dd` and all **eight** owned result
bundles (including the incomplete pre-fix and failed-build bundles). Logs,
summary JSONs and cleanup evidence remain under `/private/tmp/noah-recurring-r3-sol-`.
Protected simulators, Android devices, the physical Pixel 6 and Docker were untouched.

### Android follow-ups (inspection only; no Android changes or test runs)

- Align new end dates with RN's 23:59:00.000, or obtain an explicit cross-platform
  product decision to extend the boundary. Preserve existing approved timestamps.
- Preserve oversized RN policies as visible needs-attention rows. Android currently
  rejects the entire store via its amount-bound `validPolicy` check.
- Consider the stricter v2 cross-record checks added here: Android validates
  consumed attempts, watermarks and index arrays separately, without requiring a
  consumed attempt's index/timestamp to be represented by the other evidence.
  This is a source-inspection follow-up, not a reproduced Android payment claim.

## Round-2 safety fixes (2026-10-20)

Base: `feat/native-rewrite` at `211ecdf2`. The ArkInfo fee policy below is unchanged.

- Consumed occurrence indices survive MMKV rollback, device-zone changes and
  replacement of the latest attempt by a different policy revision. Regression:
  monthly index 1 paid in New York at `1774753200000` cannot pay again in UTC at
  `1775016000000`. Daily spring/fall DST shifts and wallet/revision isolation are
  also covered.
- Attended manual approval retains Send's terminal outcome/receipt and lock.
  The existing receipt's **Done** action acknowledges it. Replaying the review
  before or after Done sends nothing; the persisted recurring result stays paid.
  Unattended settlement still acknowledges immediately.
- A single foreground timer waits 60 seconds between executor checks, matching
  RN's `useRecurringPaymentsRunner`. AppModel starts it at active startup and on
  activation, with wallet-phase/suspension eligibility checked on every tick.
  `UIScene.willDeactivateNotification` cancels the timer, including an already
  woken tick. Reactivation starts one new timer. Native payment settlement stays
  owned by the executor and can outlive cancellation. Timer, push and screen-open
  triggers join the same executor task and reuse its markers, execution budget,
  wallet-instance binding and persistent stop generation.

### Portable recurring journal v2

`noah-recurring-journal.json` is UTF-8 JSON, atomically replaced and file/directory
fsynced before execution can continue. Android can use these exact field names
and types; no Swift keyed UUID maps or platform date encodings are used:

- `version`: integer `2`.
- `attempts`: object keyed by schedule ID, retaining the v1 `Entry` fields.
- `consumed`: array of `{binding, scheduleID, through}` cross-revision watermarks;
  `through` is Unix milliseconds, inclusive.
- `consumedIndices`: array of `{binding, scheduleID, policyRevision?, indices}`;
  `indices` is a sorted unique integer array. An absent/null `policyRevision`
  denotes the original RN policy; otherwise it is a UUID string, compared as a UUID.
- `legacyUncertainty`: array of `{binding, scheduleID}` scopes whose earlier
  revision/index history cannot be reconstructed safely.

Every `binding` is `{wallet: {fingerprint, network}, instance}`; `instance` is a
UUID string compared as a UUID. Scope equality includes every binding field.
An occurrence is blocked if its exact revision/index is recorded, its timestamp
is at/before the cross-revision watermark, **or** its wallet/schedule has legacy
uncertainty. Attempt replacement must never remove any of those records.

Migration is durable on the first read, before another attempt can overwrite
old evidence. Both v1 and legacy dictionary journals derive exact revision/index
records from every retained `consumed: true` attempt, and retain/derive timestamp
watermarks. A v1 watermark may cover overwritten revisions with unknown indices,
so every such scope also gains `legacyUncertainty`. Legacy dictionaries cannot
prove whether an earlier consumed attempt was overwritten, so every retained
scope gains that flag, even when the latest attempt failed. These scopes stay
stopped at `needs_attention`, including after a zone change, edit or destructive
resume. Cancel and create a fresh schedule with explicit approval to continue.
Unknown versions, malformed consumption evidence and failed migration writes
stop execution without treating the journal as empty.

### Round-2 verification and cleanup

Focused passing runs (all zero failures/skips):

- Journal/executor + schedule + store: **44 tests / 67 individual runs**, three suites.
- Attended settlement/executor: **35 tests / 58 individual runs**, one suite.
- Foreground runner + executor + real session reservation: **41 tests / 71 individual
  runs**, three suites. The injected clock advances minutes deterministically;
  the tests post the production scene-deactivation notification.

Before each of the first two fixes, its selected regression was run against the
old behavior and failed on the exact symptom (one failing test each: duplicate
payment after zone rollback; cleared attended success/receipt). Function-level
Swift Testing selectors included trailing `()`; neither selected run was empty.

The full `NoahTests` suite ran **once**, without exclusions: **447 total,
429 passed, 18 opt-in skipped, zero failed**, **95 suites**, 36.344 seconds of
execution. Parameterized results report **576 passed / 18 skipped** individual
runs. All Xcode runs used Nix, XcodeGen, normal code signing,
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2` and disabled parallel testing.
No live payment, physical wake or timed OS-notification delivery was attempted;
the opt-in integration tests remained skipped. Fee policy, Send, Onchain/M6,
the SDK pin and Android source were untouched.

Owned simulator: **Noah Recurring R2 Sol cbf385d4**, iPhone 17 / iOS 27.0,
UDID `D9736DCB-3522-49F6-9B39-E9329FDDE7F8`. After the passing full run,
confirmed its exact name/UDID and **Shutdown** state, deleted it and verified its
absence. Removed `/private/tmp/noah-recurring-r2-dd` and all **six** owned
xcresult bundles after exporting summary JSONs. Logs, summaries and cleanup
evidence remain under `/private/tmp/noah-recurring-r2-`. Protected simulators,
Android emulators, the physical Pixel 6 and Docker were untouched.

## ArkInfo unattended fee policy (2026-10-20)

Base: `feat/native-rewrite` at `1aa9a618`. Supersedes round 1's max(1%, 200 sats)
limit and blanket Lightning approval.
The quote captures `ArkInfo.feeSchedule.lightningSend` (`LightningSendFees` in
BarkBoltFFI 0.4.0); unavailable/failed ArkInfo uses min 20, base 0, and tiers
0→2000, 1008→4000, 2016→5000 ppm, with a debug log containing no payment data.
For amount a, F(a) = max(min, base + ceil(a × maxPpm / 1,000,000)); the cap is
min(2 × F(a), max(200, floor(a / 50))). Lightning pays unattended only if the
quote and F(a) are both within the cap. Quote refusal takes precedence. Ark
requires a zero quote fee. Full-width multiplication and saturating addition
avoid overflow, including at UInt64.max. All 24 shared fixture cases are
unchanged. The Pixel's supplied 1,000-sat BOLT11 observation (79-sat quote,
83-sat worst case, 166-sat cap) is a fixture; this run does not access the Pixel.

The executor rechecks the **same captured quote and schedule** immediately
before submission, without fetching ArkInfo again. Refusals retain
`needs_approval`, notify once and show the cap in the schedule's saved reason.
The attended Send review remains uncapped.

Source verification: the resolved SPM checkout is tag `v0.4.0`, pinning bark
`bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c`. In `lib/src/fees.rs:236`,
`LightningSendFees::calculate` adds the base to the tier-weighted fee and applies
the minimum. `calc_ppm_expiry_fee` (:508) charges at most the payment amount
across tiers and rounds once. In `bark/src/actions/lightning/pay.rs:348–385`,
`start_lightning_send` selects inputs with the wallet's ArkInfo schedule and
stores the fee. The cosign path (:469–509) constructs HTLC/change outputs from
that amount and verifies the server's signatures on them. No additional
server-selected fee is accepted. F(a) therefore bounds the fee under the checked
schedule. The accepted residual risk is a schedule change between quote and
submission; there is no SDK maximum-fee argument. Arkoor remains zero-fee.

Verification: focused `RecurringFeePolicyTests` + `RecurringExecutorTests` passed
**34 tests / 56 individual runs, zero failures/skips**, including all 24 shared
fee cases, UInt64 saturation, automatic Lightning exactly once, quote/schedule
refusals + notification, ArkInfo failure fallback, captured-schedule recheck,
and uncapped attended approval with replay protection. The full `NoahTests`
suite ran **once**, without exclusions: **433 total, 415 passed, 18 opt-in
skipped, zero failed**, **93 suites**, 38.451 seconds of test execution.
Parameterized execution reports **554 passed / 18 skipped** individual runs.
Both passing runs used Nix, XcodeGen, normal signing,
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2` and disabled parallel testing.
The separate opt-in live run below failed only at its funding requirement.

Live attempt (**one test failed at the funding requirement, zero skips**): a fresh wallet and reusable CLN BOLT12 offer were created on the
owned **Noah Recurring ArkInfo Sol**, iPhone 17 / iOS 27.0, UDID
`7E844FE8-97F2-418B-AC8A-B71021D7BF14`. The existing Noah backend at
`127.0.0.1:3000` returned HTTP 200. The wallet published a 31,000-sat funding
invoice. CLN recorded the sole funding attempt as **failed, 0 msat sent**,
payment hash `0141968cc4f6d886d0570671622980300a56387e78dbf65f08ae5382f7b5833c`.
CLN's sole channel reported `CHANNELD_NORMAL` but `connected: false`; its peer
was also disconnected. No funding was retried, no peer reconnected, no Docker
service changed, no fee reconfigured and no block mined. The live test stops at
its 180-second funding requirement before creating a schedule or submitting a
recurring payment. Read-only inspection confirmed no recurring journal or Send
journal existed. **No successful native live automatic-payment claim is made.**
Above-cap behavior is covered deterministically. The Pixel observation above
is supplied evidence, not a new physical-device run.

Live re-run, passed (2026-10-20, 00:58–00:59 EDT, after merge to `feat/native-rewrite`
`45fec2b3`). Ran on the dedicated **Noah FeeCap Live** simulator, iPhone 17 / iOS 27.0, UDID
`AC0BC636-0D66-4ABC-A1A1-808BEEBE055F`, using `NoahTests/RecurringFeeLiveTests/belowCapLightningAutomaticallyPaysOnce()`.
LND was down, so the wallet was funded over Ark instead of Lightning: the real
Pixel 6 (regtest build) sent **31,000 sats** to the published
`recurring-fee-funding-address.txt`, with a 0-sat fee on review.
- The unattended executor then quoted **155 sats** for the 20,000-sat BOLT12 payment against a
  **400-sat cap**, with a worst-case published-schedule fee of **235 sats**.
  These match the `regtest: 20,000 sats limited by 2% of the amount` fixture case.
- It decided `within_cap` and paid automatically, **once**, across the automatic and repeated manual triggers.
- CLN's fresh offer `b06dd616ee71…` shows exactly one paid invoice, 20,000,000 msat, at 00:59:07.
- The wallet recorded one successful movement (#2), and the occurrence was consumed.
- The test then cancelled the schedule and deleted its disposable wallet.
- **1 test passed**, 64.2 s.
- Cleanup: simulator shut down and deleted, and its DerivedData and result bundle removed.

Reproduce on a fresh dedicated simulator with
`TEST_RUNNER_NOAH_RECURRING_FEE_LIVE=1`,
`TEST_RUNNER_NOAH_RECURRING_FEE_OFFER=<fresh reusable regtest CLN offer>` and
`TEST_RUNNER_NOAH_REGTEST_SERVER_URL=http://127.0.0.1:3000`, selecting
`NoahTests/RecurringFeeLiveTests`. Fund the published
`Documents/recurring-fee-funding-address.txt` once with 30,000 Ark sats, **or**
pay `Documents/recurring-fee-funding-invoice.txt` once (31,000 Lightning sats),
within 180 seconds. Inspect any failed/timed-out funding before retrying.
The test expects one unattended 20,000-sat BOLT12 payment across repeated
triggers, a consumed occurrence, and exactly one successful sender movement;
it then cancels the schedule and deletes its disposable wallet. Successful runs
write non-secret `Documents/recurring-fee-evidence.json` with quote, worst-case
fee and cap. Failed funding never reaches this payment path.

Cleanup completed: reset only the unfunded owned simulator before the full
suite; then confirmed its exact name/UDID above and **Shutdown** state, deleted
it and verified its absence. Removed `/private/tmp/noah-arkinfo-sol-dd` and all
**three** result bundles (focused, failed funding-only live, full). Disabled
only the CLN offer created for this test. JSON test summaries, logs and
non-secret blocked-live/cleanup evidence remain under
`/private/tmp/noah-arkinfo-sol-`. Protected simulators, Android devices, the
physical Pixel and Docker services were untouched.

## Round-1 unattended safety (2026-10-20)

Historical evidence below uses the superseded fee policy; the ArkInfo policy
above replaces only that fee behavior.

Base: `feat/native-rewrite` HEAD `b46fc2e2`. Six focused fixes:

- Suspend/Delete durably rotate an automation generation before teardown. Even
  when the Send reservation makes close fail and the suspension flag is restored,
  an older execution cannot submit. Both races use the real session reservation.
- Journal v1 stores consumed timestamp watermarks per wallet instance + schedule,
  separately from the latest attempt, across every policy revision. Legacy
  dictionaries migrate durably before another attempt can replace their evidence.
  A revision-A → revision-B → MMKV rollback-to-A regression never pays A again.
- The round-1 automatic fee limit was max(1% of the amount, 200 sats), rounded
  down to whole sats; the ArkInfo policy above supersedes it. Excessive fees stop at `needs_approval`, without an occurrence or
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
envelopes stop execution and retain their bytes. Unsupported rows remain visible,
stopped and cancellable with their original JSON preserved. Nothing is silently filtered out.
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
- iOS follows RN's server-wake, activation and one-minute foreground checks.
  RN's periodic WorkManager task is Android-only. Local reminder/result notifications are
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
