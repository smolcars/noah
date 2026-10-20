# Native Send verification

## Recurring occurrence handoff (2026-10-20)

Base `7e88fdd8`. A recurring terminal receipt is now copied into its occurrence's
fsynced journal before success is published or the shared Send slot can be
cleared/reused. Restored receipts use the same handoff. Handoff failure preserves
the receipt and blocks both Done and destructive removal until its evidence is
safe. Done and an unrelated Send cannot affect recurring settlement, which reads
only the occurrence journal. The retained-result cache/API is gone; normal Send
submission, exact-txid journaling/recovery and chain-status evidence are unchanged.
Full format, crash-boundary tests, counts and cleanup are recorded in
[RecurringTests](../RecurringTests/README.md#durable-occurrence-settlement-2026-10-20).


## bark-bolt-ffi 0.5.0 review round 2 fixes (2026-10-20)

Base `51c24bc0`. `71bfc2e6` reconciles exact cached wallet txids before the optional
`transactionStatus` lookup. A lost board receipt recovers from the journaled txid
in BDK transactions, pending boards or board movement anchors even if that lookup
fails. Unrelated txids never resolve it. Lookup errors persist separately on the
attempt and appear beside the last known network status; a successful lookup
clears the error. Errors and `notFound` never fail or unlock the attempt, and
recovered boards retain the broadcast/acknowledgement lock until confirmation.

`296b8464` makes Auto-Board reread confirmed onchain funds before claiming its preparation.
With fixed signed gross and onchain fee, requiring at least the reviewed confirmed
balance preserves the exact reserve shown, including unselected coins. Added
funds are allowed; a reduced or unreadable balance discards the preparation,
records no submission attempt, and uses the existing review-again copy. A review
invalidated during the read cannot submit. Failed discard remains session-owned.
Manual boarding retains its existing selected-input validation.

Both regressions failed before their fixes: the exact cached-board probe threw a
transport error (**1 test / 2 cases**), and the reserve probe submitted and wrote
a receipt after a **4,000-sat** balance drop (**1 test**, three failed assertions).
Final targeted runs passed **32 tests / 38 executions in 6 suites** for recovery
and **39 tests / 57 executions in 6 suites** for boarding, zero failures/skips.
The runs overlap in journal/model/recovery coverage. They exercise error retention
and clearing, restart and acknowledgement locks, unrelated txids, **5,000/4,999-sat**
reserve edges, a **1,000-sat** remainder, zero/insufficient funds, unchanged/added
funds, balance-read failure, failed cleanup ownership, late invalidation and manual
boarding. Every method filter included its parentheses; suite filters selected all
methods. Counts were checked with `xcresulttool`, including nonzero red runs.

Full `NoahTests` ran **once** after final source/test changes: **489 total,
470 passed, 19 explicitly gated skips, zero failures across 104 suites**;
parameterized execution **684 passed / 19 skipped**. XcodeGen preceded the run;
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGN_IDENTITY=-`, signing enabled, two jobs,
disabled parallel testing, DerivedData `/private/tmp/noah-ffi-r2-ac22-dd`.

Owned simulator **Noah FFI R2 Sol ac22**, iPhone 17 / iOS 27.0,
`35205DFD-C032-4328-9F1C-231048D534AC`: T3 rejected this new UDID twice as unknown,
so Xcode targeted it directly. After testing, its exact name/type/UDID and
**Shutdown** state were checked, only that simulator was deleted and absence
verified. Its DerivedData, all **five owned `.xcresult` bundles**, logs and scratch
summaries were removed. Protected simulators, Android devices, physical hardware
and Docker were untouched. Both findings are fixed; live funded regtest and
external-network/hardware exercises were not repeated for these deterministic
production-seam regressions.

## bark-bolt-ffi 0.5.0 review round 1 fixes (2026-10-20)

Based on `be45d670`. `972aca72` keeps successful BDK reconciliation independent
of a subsequent `transactionStatus` failure. Evicted originals and confirmed
competing spends downgrade the persisted receipt and retain the acknowledgement
lock. The last known chain status remains alongside a separately persisted lookup
error, restored after restart and cleared by a successful lookup. Chain-source
confirmation cannot override BDK's downgrade; `notFound` cannot fail or unlock it.

`89fab615` replaces Auto-Board's estimated-gross first preparation with a
confirmed-only DrainAll fee probe. The probe is discarded before one reduced-gross
preparation, allowing for the added change output (up to 43 vB at the reviewed
rate). The review requires the exact signed reserve to be at least 5,000 sats and
the net amount to meet the boarding threshold. Probe/review errors and cancellation
release their preparations; failed probe cleanup prevents a review and retains
session cleanup ownership. Typed insufficient-funds, too-many-inputs and
below-minimum errors use the existing copy, including the automatic prompt.

Regression tests reproduced both findings before the fixes: Send retained a
confirmed receipt and acknowledgement unlocked it in both eviction/conflict
cases; Auto-Board threw insufficient funds before reaching review. Final focused
runs passed **76 tests / 133 individual executions across 10 suites**, no
failures/skips: Send **45 / 90**, boarding **31 / 43**. They cover both status-error
refresh orders, stale positive chain evidence, restart/acknowledgement locks,
twenty confirmed 50,000-sat inputs at 10 sat/vB, 5,000/4,999-sat reserve edges,
gross/net thresholds, typed failures at either preparation, cancellation at either
preparation, failed probe cleanup and existing lifecycle/journal regressions.

Full `NoahTests` ran **once** after final source/test changes: **467 total,
448 passed, 19 explicitly gated skips, zero failures, 99 suites**; expanded device
execution is **611 passed / 19 skipped**. XcodeGen ran immediately before the
signed arm64 run, with `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGN_IDENTITY=-`,
two jobs, disabled parallel testing, and DerivedData
`/private/tmp/noah-ffi-r1-7f01-dd`. Counts were read from `xcresulttool` summaries;
no focused invocation silently ran zero tests (method IDs include their argument
signature/parentheses; suite filters select all methods).

Owned simulator **Noah FFI Fix R1 Sol 7f01**, iPhone 17 / iOS 27.0,
`671A0A0B-E3F0-4075-AE95-3F273960EFDA`, was opened in T3's Device panel. Its exact
name/type/UDID were checked, it was shut down through T3, **Shutdown** was
rechecked, then only that simulator was deleted and its absence verified.
Its DerivedData, all **six owned `.xcresult` bundles**, and scratch logs/summaries
were removed. No live funded regtest was needed: these are deterministic
regressions at the production reconciliation/preparation seams. Protected
simulators, Android devices, physical hardware and Docker containers were untouched.

## bark-bolt-ffi 0.5.0 adoption (2026-10-20)

Base `211ecdf2` (`feat/native-rewrite`). iOS pins **exactVersion 0.5.0** at
`68b88c73ccaaa8e5fe607fbcc1e7d783a9ef4609`. XcodeGen regenerated the project;
the app's SPM `Package.resolved` is now tracked. The SPM checkout's manifest hashes
`2b76c832b05efc83cf787aae5e3625e40af297942a768a8f475567a84aa60608`; its resolved
cached XCFramework archive hashes
`d9b3b1e74d31d92412f1dac06a38c338c834a962846f560f745922678f155e27`.
Both match the release `SHA256SUMS` and SPM's artifact-source record.
[Artifact evidence](evidence/ffi050-artifact.json).
The migration guide and Plan 23 were fetched into a fresh `mktemp` directory and
read as untrusted data. Contract sources:
[migration](https://github.com/smolcars/bark-bolt-ffi/blob/v0.5.0/docs/migration.md#prepared-boarding-and-stored-exits-v050),
[Plan 23](https://github.com/smolcars/bark-bolt-ffi/blob/v0.5.0/docs/plans/23-noah-prepared-boarding.md).

`transactionStatus` now tracks Ark→onchain/offboard Send receipts using the exact
SDK-returned txid: not yet seen by this source, in mempool, confirmed at height.
Transport errors retain the last saved receipt/status and show an error. Absence
never fails or automatically unlocks a payment. Onchain-wallet Send retains its
BDK sync, reorg, missing-history, confirmed competing-spend and broadcast-retry
lock rules; the new chain-source observation supplies additional display evidence.
Board and claim receipts use the same read-only chain API through M6. Prepared
boards journal the signed funding txid before submit and reconcile through exact
cached/pending/movement-anchor/network evidence, never amount matching. Prepared
offboards and generic durable operation keys remain open.

Final focused verification: **66 tests / 89 individual executions, 15 suites,
zero failures/skips**. This includes exact board review/outcomes, two-minute
expiry, post-acceptance persistence cleanup and failed-cleanup ownership, reserve
re-preparation, stale/foreign/edited reviews, late review/readiness invalidation,
chain-status recovery/error/reorg/lock checks, terminal exit mapping, existing
claim/journal/override, prepared Send, Send round-7 and discard regressions.
[Final targeted summary](evidence/ffi050-targeted-final-summary.json).
An earlier focused run passed **48 tests / 53 executions in 11 suites**; the first
build caught actor-isolated assertions, corrected before any test execution.


Full `NoahTests` ran **once** after the final source/test changes: **452 total,
433 passed, 19 explicitly gated skips, zero failures, 98 suites**; expanded device
execution is **577 passed / 19 skipped**. XcodeGen ran immediately before the
signed arm64 run, with `ONLY_ACTIVE_ARCH=YES`, two jobs and disabled parallel
execution. [Full summary](evidence/ffi050-full-summary.json).

Cleanup: verified the owned simulator's exact name, UDID and iPhone 17 type,
shut it down through T3, rechecked **Shutdown**, deleted only that simulator and
verified absence. Removed its `/private/tmp/noah-ffi-v050-dd` and **all 14 owned
`.xcresult` bundles**, retaining public summaries/evidence in the repo.
[Cleanup evidence](../ExitTests/evidence/ffi050-cleanup.json). No protected
simulator, Android emulator, physical device or Docker lifecycle was changed.

Live verification used only **Noah FFI 050 Sol c8d6719b**, iPhone 17 / iOS 27.0,
UDID `290521A4-1583-46F9-AE4E-119EFEA023FC`, opened in T3's Device panel and driven
with its pinned `agent-device` session. Bitcoin Core funded **300,000 sats** and
**60,000 sats** with `just bcli sendtoaddress`; no Lightning funding or Docker
lifecycle changes. This task mined **five one-block increments**: funding,
amount-board/change, MAX/new funding, Auto-Board, offboard. The shared chain also
advanced independently. The opt-in `FFI050LiveTests.acceptance()` harness showed
the production review, and host clicks approved the actual amount/MAX/Auto-Board
and Ark→onchain Send confirmations. All **nine final live stages passed** (one test
each); a supplemental stage first ran against a stale binary, failed before any
mutation, then passed after rebuilding. [Live summaries](evidence/ffi050-live-summary.json).

Exact app review versus independent Core `getmempoolentry` / funding outputs:

- **Amount:** gross **30,000**, net **29,670**, Ark fee **330**, onchain fee
  **155**, total leaving **30,155 sats**, **154 vB / 1 input**. Core: output
  **30,000 sats**, fee **155 sats**, **154 vB**, same funding outpoint and txid
  `ab6867afa617a02d25a0b000c85002f1b742d3cb54517b975301adce610f0c23`.
  [Review](evidence/ffi050-amount-review.json), [receipt](evidence/ffi050-amount-receipt.json),
  [Core](evidence/ffi050-amount-core.json), [mempool](evidence/ffi050-amount-mempool.json).
- **MAX:** confirmed **269,845**, gross **269,733**, net **269,363**, Ark fee
  **370**, onchain fee **112**, total leaving **269,845 sats**, **111 vB / 1 input**.
  Core: its sole output is **269,733 sats**, fee **112 sats**, **111 vB**, same
  reviewed input and txid `ee416cb3ae37549bda13555587e09260e13cb0920ca94f6926ef372931299940`.
  [Review](evidence/ffi050-max-review.json), [receipt](evidence/ffi050-max-receipt.json),
  [Core](evidence/ffi050-max-core.json), [mempool](evidence/ffi050-max-mempool.json).
- **Auto-Board:** confirmed **60,000**, gross **54,792**, net **54,462**, Ark fee
  **330**, onchain fee **155**, total leaving **54,947**, exact reserve **5,053 sats**,
  **154 vB / 1 input**. Core: funding **54,792**, change **5,053**, fee **155 sats**,
  **154 vB**, same reviewed outpoint and txid
  `a7eece0e3012fb3a5a989d66ba976366a3b4549d67693ccddf43a2ec9751abf8`.
  [Review](evidence/ffi050-auto-review.json), [receipt](evidence/ffi050-auto-receipt.json),
  [Core](evidence/ffi050-auto-core.json), [mempool](evidence/ffi050-auto-mempool.json).

The amount/MAX txids were absent from Core's mempool before approval. An
assert-based host check matched all three reviewed txids, fees, sizes, input
counts/outpoints and gross outputs; Auto-Board's change matched the reviewed
reserve. Evidence strips witness data and raw transaction hex. The first Core
inspection included witness data; that raw scratch dump was removed and subsequent
queries were filtered before display or retention.

Ark→onchain Send: recipient **5,000 sats**, SDK receipt fee **2,560 sats**, txid
`da0d0051fa246c3ede60b2191b3b19aaa74660c50e8f583d0d02087357bc8a3c`.
The saved receipt moved **inMempool → confirmed(625)** after exactly one mined
block, preserving its identity/amount/fee. Core independently reports that txid
with one confirmation and the **5,000-sat recipient output**; its onchain network
fee is **1,853 sats** (**185 vB**), distinct from Bark's total receipt fee.
[Mempool receipt](evidence/ffi050-offboard-mempool.json),
[confirmed receipt](evidence/ffi050-offboard-confirmed.json),
[Core mempool](evidence/ffi050-offboard-mempool-core.json),
[confirmed Core](evidence/ffi050-offboard-confirmed-core.json).
A read-only board receipt check also returned **confirmed(621)** with the original
**155-sat onchain / 330-sat Ark fees**, without sync or resubmission.
[Board status](evidence/ffi050-board-status.json).

Repeat with `TEST_RUNNER_NOAH_FFI050_STAGE` set to `create`, `amount`, `max`, `auto`,
`offboard`, `confirmed`, `cancel`, `reopen` or `board-status`; offboard also needs a
fresh Core address in `TEST_RUNNER_NOAH_FFI050_RECIPIENT`. Select
`'-only-testing:NoahTests/FFI050LiveTests/acceptance()'` (the trailing `()` matters).
`create` refuses an existing wallet; all stages are gated off by default. Fund/mine
between stages only as needed, inspect receipts before repeating, and use a new
owned simulator. Xcode uses Nix, signing enabled (`CODE_SIGN_IDENTITY=-`),
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, two jobs, disabled parallel testing and isolated
DerivedData under `/private/tmp`.

Live claimed exits, network outages, post-acceptance disk-failure injection and
external networks/hardware were not exercised. Claimed terminal rows, transport
failure retention and persistence/discard recovery are covered by focused tests;
live cancel/reopen is recorded in [Exit verification](../ExitTests/README.md).
Pinned server-rpc initializes its ArkInfo cache once; heartbeat does not update
fee parameters. No test assumes heartbeat can reprice an existing preparation.
Earlier sections below retain their historical SDK behavior and verification.


## Review follow-up: padded URI amounts and final verification (2026-10-20)

The Send-only parser now removes insignificant integer leading zeros and
fractional trailing zeros before checking precision and scale. It retains full
ASCII-string validation, the 96-byte input bound, 32 significant mantissa digits,
bounded exponent/normalized scale (-32...32), and exact 0...21-million-BTC range
validation before conversion. It follows RN's `Math.round(btc * 100_000_000)`
semantics for excess fractional precision, including IEEE-754 halfway cases.
The rounded count converts through `UInt64` to `Decimal`, preserving exact sats
at the supply boundary (Foundation's `Decimal(Double)` can introduce a fraction).

Both platforms consume **108** shared `send-amount-safety.json` vectors. Running
RN's pinned **bip-321@0.0.11** plus its `btcToSats` expression generated twenty
padded/precision results, including two updated raw-digit-limit expectations and
eighteen new inputs. Each records RN's BTC and satoshi results. Leading zeros,
trailing zeros, both, zero/one-sat/supply boundaries, exponent padding, the
96-character boundary and excess-fraction rounding are covered. Examples:
`1.00000000000000000000000000000000` → 100,000,000 sats;
`1.000000005` → 100,000,000 sats; `0.000000145` → 14 sats.
Regenerate with `bun apps/fixtures/tools/generate-send-amount-padding.ts` using
the pinned client dependency. Existing malformed strings, extreme exponents,
Unicode digits, true excess precision and out-of-supply cases remain invalid.

Final targeted signed verification: **25 tests / 48 individual runs passed**,
**5 suites**, zero failures/skips: migration, amount vectors, RN Send fixtures,
override audit and round-7 recovery. The initial failing migration regression
finished its six test definitions, then Xcode stalled collecting failure
diagnostics; that owned process was stopped. Final targeted/full commands used
`-collect-test-diagnostics never` and completed normally. An intermediate amount
run caught Foundation's large-Double conversion issue; its integer conversion
fix passed the final targeted and full runs.

The full signed `NoahTests` suite ran **once** after final iOS code/test changes,
without target/suite exclusions: **408 total, 391 passed, 17 opt-in skipped,
0 failed**, across **89 suites**. Parameterized execution reports **520 passed /
17 skipped individual runs**. Full Android verification also ran once:
**1,133 JUnit cases / 56 suites passed**, no failures/errors/skips, and regtest APK
assembly passed. Its command and detail are in `apps/android/SendTests/README.md`.
All iOS runs used Nix, XcodeGen, normal simulator code signing,
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, two build jobs and disabled parallel testing:

```sh
nix develop --command sh -c 'cd apps/ios && /opt/homebrew/bin/xcodegen generate && xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest -destination "platform=iOS Simulator,id=2D63BF21-9539-4CD1-9147-0F64310B7D9E" -derivedDataPath /private/tmp/noah-send-r2-8d05-dd -resultBundlePath /private/tmp/noah-send-r2-ios-full.xcresult -jobs 2 -parallel-testing-enabled NO -collect-test-diagnostics never ARCHS=arm64 ONLY_ACTIVE_ARCH=YES test'
```

Cleanup completed: dedicated **Noah Send R2 Recovery Sol 8d05d178**, iPhone 17 /
iOS 27.0 (`2D63BF21-9539-4CD1-9147-0F64310B7D9E`) had its exact identity and
Shutdown state verified before deletion, then was confirmed absent. Removed its
DerivedData, all four owned xcresult bundles and temporary RN dependencies.
The Device panel could not discover this new simulator after two attempts;
Xcode/simctl performed verification and cleanup. Logs and exported summaries
remain under `/private/tmp/noah-send-r2-*`. No funded live payment was needed;
other simulators, Android emulators, physical devices and Docker were untouched.
M6 and Recurring implementation files were not changed. Both review findings
are fixed; existing external-provider/live-fault limitations below remain.

## Review follow-up: blocked migration recovery (2026-10-20)

Base: `feat/native-rewrite` HEAD `99a9177c09ee7da2d7ef3df716f6aff6edc731ff`.
iOS had the same blocked-state recovery gap. Migration now retains the original
identity-checked journal in memory until persistence succeeds, preserving original
receipt txids for the audit rather than prematurely replacing them with migrated
evidence. **Retry** re-attempts migration from that read-only evidence without
rereading disk, reconciling, paying or releasing the lock. The blocked screen
also offers the existing destructive history-check confirmation with its full
stored-transaction/retry/double-spend warning. Audit/removal failures stay locked.
Foreign or unverified ownership is still gated before activating evidence.

`SendJournalMigrationTests` adds successful Retry after a transient write failure,
an unavailable second read, retained original txid/version, canceled confirmation,
and blocked override success/audit failure/removal failure. Its **6 tests / 12
individual runs** pass in the targeted run. Existing legacy/provenance and
round-7/8 override regressions remain intact.

## Round-8 override audit recovery and legacy evidence (2026-10-19)

Unreadable `noah-send-overrides.json` data is moved to a unique sibling named
`noah-send-overrides.json.unreadable-<UUID>` before recording a fresh audit. The
quarantined bytes are preserved and excluded from backup; Send never deletes
these files. A failed quarantine, fresh audit write or attempt removal keeps
Send locked. The new confirmation must be saved before removing the journal.

Identity-free legacy journals remain privately quarantined, unavailable to
receipt display and reconciliation. Their destructive override now audits the
original receipt/broadcast txid, movement candidates and possible txids, with
`ownershipUnverified: true` and no invented current-wallet identity. These new
audit fields are optional so existing audit histories remain readable.

`SendOverrideAuditTests` covers corrupt-byte preservation before journal removal,
restart recovery, real filesystem quarantine/write failures, and legacy receipt
and broadcast txids/candidates reaching the persisted audit. Round-7 failed
refresh handling and session-owned preparation discard remain unchanged.
No funded live payment or shared-stack fault injection is needed for this round.
Base: `feat/native-rewrite` HEAD `337245c2565e7a7296137ebdee1b9e8806ff949d`.

Before the fix, the new suite failed **2 of 3 tests / 3 of 5 individual runs**:
corrupt audit recovery and both legacy txid cases. Final targeted verification
passed **29 tests / 53 individual runs**, **6 suites**, zero failures/skips,
including round-7 refresh/discard, wallet identity, journal migration and prepared
send recovery coverage.

All runs used Nix, XcodeGen, normal simulator code signing,
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2` and disabled parallel testing.
Dedicated device: **Noah Send R8 Sol 0b94f03c**, iPhone 17 / iOS 27.0, UDID
`A43F1B2B-439D-4B34-9518-F90AFFDBEB61`. DerivedData:
`/private/tmp/noah-send-r8-0b94-dd`; result prefix:
`/private/tmp/noah-send-r8-0b94-{red,targeted,full}.xcresult`.
Summary JSON exports and logs use the same prefix.

The full `NoahTests` suite ran **once** after the final code/test changes, with
no suite or target exclusions: **371 total, 355 passed, 16 opt-in skipped,
0 failed**, across **83 suites**. Parameterized execution reports
**458 passed / 16 skipped** individual runs.

Cleanup completed after exporting summaries: verified the simulator's exact
name/UDID and Shutdown state, deleted it and confirmed its absence. Removed only
the owned DerivedData directory and all three xcresult bundles. Logs and summary
JSON remain under `/private/tmp`; other simulators, emulators, physical devices
and Docker were untouched.

## Round-7 unresolved receipts, offline refresh and discard ownership (2026-10-19)

This section supersedes the historical override refusals below. Round-6 crash
recovery still locks ordinary sending and Done for an unconfirmed prepared spend.
Broadcast-pending, unlocated and conflicted receipts now offer the explicit
destructive history-check override. Its confirmation says that forgetting the
attempt **does not cancel Bark's stored transaction or future broadcast retries**
and sending again may spend twice. Before unlocking, Noah atomically appends the
timestamp, original txid, wallet identity, exact confirmation title/warning/button
and current status error to `noah-send-overrides.json` (last 100 overrides, excluded
from backup). An audit-write or attempt-removal failure keeps Send locked. The
audit survives removal of the active attempt and subsequent payment journals.

Prepared quotes now retain input outpoints. A confirmed different BDK transaction
spending an original input establishes a conflict, including when the original
has been evicted from history; older quotes can use the original's cached bytes.
The receipt and confirmation name that confirmed competitor and explain that the
original cannot confirm on the current chain. Missing, malformed or unconfirmed
competing evidence never proves a conflict or grants permission to send.

A thrown refresh leaves the last proven receipt and durable journal unchanged.
The UI shows that the latest check failed (offline where known), rather than
claiming the transaction disappeared. Confirmed/accepted and external-offboard
receipts retain Done. Unresolved crash attempts cannot acquire a receipt from
cached evidence after a failed sync; broadcast-pending receipts retain their lock.
A completed lookup with missing evidence still marks the receipt unlocated.

Preparation discard runs in a session-owned task, independent of review caller
cancellation. The session retains the preparation and expiry ownership until
discard succeeds, prevents claiming it once discard starts, coalesces concurrent
cleanup, and tries at most three times per pass. Exhaustion retains the handle
for another pass on the next counted wallet activity or close. Close refuses to
release the session if cleanup still fails. Failed quote validation uses this
same owned cleanup path. Failure/cancellation fakes retain their input reservation
until discard actually succeeds; coverage includes bounded exhaustion, another
wallet operation, and failed close followed by successful close.

Regression coverage: `SendRoundSevenTests`, `SendDiscardTests`, and the updated
round-6/reorg tests. These are controller/session/SDK-value tests; no funded live
RBF, conflict, offline broadcast or Rust discard fault was injected. Android's
parallel port must adopt all three behaviours; its M3 follow-up is in
`docs/native-rewrite/TODO.md`.

Targeted verification passed **41 tests / 83 individual runs**, **4 suites**,
zero failures/skips. The initial build stopped on actor-isolated fake assertions;
those assertions were corrected before the passing run. All runs used Nix,
XcodeGen, normal simulator code signing, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`,
`-jobs 2` and disabled parallel testing. Dedicated device: **Noah Send R7 Sol
d6f6bf92**, iPhone 17 / iOS 27.0, UDID
`4621F4BE-2003-454B-851D-0ABB0D6B58FA`. DerivedData:
`/private/tmp/noah-send-r7-d6f6-dd`; results:
`/private/tmp/noah-send-r7-d6f6-{targeted1,targeted2,full}.xcresult`.
Summary JSON exports and logs use the same prefix.

The full `NoahTests` suite ran **once** after the final code changes, without
target or suite exclusions: **366 total, 350 passed, 16 opt-in skipped, 0 failed**,
across **81 suites**; parameterized execution reports **451 passed / 16 skipped**
individual runs. The implementation starts at `feat/native-rewrite` HEAD
`4fa48411cd872907f49d2896e9f63dc726124624`, captured at task start.

Cleanup completed after exporting summaries: verified the dedicated simulator's
exact name/UDID and Shutdown state, deleted it, and confirmed its absence. Removed
only the owned DerivedData directory and all three xcresult bundles. Logs and
summary JSON remain under `/private/tmp`; no other device, emulator, physical
hardware or Docker state was changed.

## Round-6 broadcast recovery and review lifetime (2026-10-19)

Crash recovery from a prepared/broadcasting journal treats an exact but
unconfirmed cached transaction as **Submitted, awaiting broadcast** with
`broadcastRetryPending: true`. It uses the same “Noah will keep retrying the
broadcast” state as a returned `failed` outcome; Done and ordinary review cannot
erase it. Confirmation clears the flag and enables Done. A missing transaction
still stays uncertain with the existing explicit destructive override.

Reconciliation now propagates onchain sync errors to the controller's visible
status-check error and retains the attempt. It does not upgrade cached evidence
after a failed sync. Even a successful sync plus an unconfirmed cached txid does
not prove acceptance: the SDK's transaction record does not distinguish a stored
spend awaiting retry from chain-source acceptance. See the
[v0.4.0 prepared-send contract](https://github.com/smolcars/bark-bolt-ffi/blob/v0.4.0/docs/migration.md#prepared-onchain-sends-v040).

Regression coverage includes a crash before receipt save, a failed-broadcast
receipt write error, relaunch with a failed sync, unconfirmed exact-txid recovery,
Done/override refusal, another restart and confirmation finally enabling Done.
No recovery test can call payment submission.

Every review discard now increments a generation, even while its quote is still
nil. SendView's existing exit/background/dismissal paths and recipient/reset
changes therefore invalidate preparation already in flight. A late result from
an older generation is discarded immediately through the session's
`discardPrepared` path and cannot reopen review. Late errors cannot modify the
new flow. Suspended-preparation regressions cover exit, background, discard,
recipient change, reset and cancellation, plus an error after reset; a discard
spy verifies the exact returned prepared quote is released before the request
finishes. These are model/controller tests, not live crash or lifecycle UI tests.

Before the fixes, the recovery run failed **2 of 5 tests** (the direct crash
assertion and both cases of the restart test), and the review run failed
**2 of 11 tests** (exit/background/discard cases and the stale-error test).
The recovery suite passed in the latter run after its fix.

Final targeted Send verification: **61 tests / 103 individual runs**, all passed,
**9 suites**, zero failures/skips. The full `NoahTests` suite ran **once** after
the final code changes, with no suite or target exclusions: **351 total,
335 passed, 16 opt-in skipped, 0 failed**, across **79 suites**;
parameterized execution reports **403 passed / 16 skipped** individual runs.

All runs used Nix, XcodeGen, normal simulator code signing,
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, and disabled parallel testing.
Dedicated device: **Noah Send R6 Sol f375e3d1**, iPhone 17 / iOS 27.0,
UDID `51E00223-3DE7-4FEB-BDEF-6753F9D727FB`. DerivedData:
`/private/tmp/noah-send-r6-f375-dd`; result prefix:
`/private/tmp/noah-send-r6-f375-{recovery-red,review-red,targeted,full}.xcresult`.
Summary JSON exports and logs use the same prefix. The simulator's exact identity
and Shutdown state were checked before deletion; its DerivedData and result
bundles were removed after exporting summaries. No other device or shared stack
was changed. No new funded live test was run in this round.

## FFI 0.4.0 adoption (2026-10-19)

This section supersedes the historical disabled-MAX and estimated-BDK-send notes
below. Onchain-wallet review now calls `prepareSend` with `.amount` or `.drainAll`
and `confirmedOnly: true`. Review shows the signed recipient amount, fee, total,
vsize and input count. Confirm claims that process-local handle; it does not
re-estimate, rebuild, send or drain. The durable attempt stores `broadcastingTxid`
and the complete reviewed quote **before** the first `broadcastPrepared(id)` call.

Reviews expire **two minutes after signing**, releasing the reserved inputs and
returning to editing. A new review fetches a fresh fee rate and signs again.
Back, editing/reset, sheet dismissal, leaving Send, app inactivity and late or
canceled quote results discard unclaimed preparations. The session owns a timeout
independent of the sheet. Confirmation cancels that timeout while the existing
Send reservation protects journaling/submission. Failure to save the journal also
discards the preparation. `WalletSession.close` drains running calls and discards
all remaining handles before stopping the daemon, covering close/suspend/delete
without modifying `WalletLifecycle` or `AppModel`. A reopened wallet never accepts
an old review handle.

`accepted` produces a Submitted receipt for the exact prepared txid and values.
`failed` produces a locked Submitted receipt with **“Noah will keep retrying the
broadcast”**; Done/override cannot release it. Core may already have received a
failed RPC, and Bark retains the spend for sync retry. Confirmation clears this
broadcast-pending flag. `notAttempted` produces a not-submitted error and permits
a new review through the failure screen. Discard after submission forgets only
the SDK handle and cannot cancel the stored spend or its retries. Raw SDK reasons
are not exposed in this UI; `SafeErrorDescription` redaction is unchanged.

After a crash in the broadcasting phase, only `broadcastingTxid` found in BDK
state establishes Submitted/Confirmed, using the signed reviewed values. Absence
stays uncertain with the existing destructive override: canonical history can
evict an unconfirmed transaction. Snapshot differences/possible matches apply
only to older journals without this exact identity; all existing wallet-identity
quarantine and outcome-provenance migrations remain conservative.
Round 6 above adds the missing broadcast lock to unconfirmed crash recovery and
requires successful sync before reconciliation can upgrade the evidence.

Typed-error audit against the resolved **v0.4.0** sources:

- [`core/onchain.rs`](https://github.com/smolcars/bark-bolt-ffi/blob/v0.4.0/src/core/onchain.rs)
  `prepare_send`/`prepared_onchain.rs` sign without recording/broadcasting. Any
  failed preparation is safe to abandon. For the app's **first and only**
  `broadcast_prepared` call, `preparedTransactionNotFound` and
  `preparedInputsUnavailable` precede recording; they permit another review.
  Persistence failures use `notAttempted`. Other thrown errors stay uncertain.
  This classifier must never be reused for retrying an already-recorded ID.
- [`error.rs`](https://github.com/smolcars/bark-bolt-ffi/blob/v0.4.0/src/error.rs)
  maps BDK `CreateTxError::CoinSelection/NoUtxosSelected` to `insufficientFunds`
  and `OutputBelowDustLimit` to `dust`. Pinned Bark's `board` builds the PSBT
  before signing, requesting cosignatures or writing its payment checkpoint;
  these two codes from `boardAmount`/`boardAll` are safe to release.
- [`core/wallet.rs`](https://github.com/smolcars/bark-bolt-ffi/blob/v0.4.0/src/core/wallet.rs)
  refresh/start/cancel ID validation and claim PSBT/address failures still use
  `other`; they remain uncertain rather than being inferred from message text.
  Claim broadcasting is separately wrapped as uncertain. Local pre-broadcast
  claim-fee changes and explicit canceled/refused results retain their existing
  safe behavior. No diagnostic-prefix allowlist remains in Send or M6.
- Round-5 Lightning adoption still validates canonical invoice, hash, amount,
  movement/revocation evidence and the reviewed fee/total bounds before
  `checkLightningPayment`. 0.4.0 preserves these fields; no Lightning code change
  was necessary. Boarding and Ark→onchain MAX/offboards retain estimate copy
  because no prepared API exists for those operations (FFI 18 / remaining 14).

Targeted verification: **38 tests / 63 individual runs**, 0 failures/skips,
including all broadcast outcomes, pre-broadcast journal contents, failed-broadcast
restart locking, exact-id crash reconciliation, wrong-id/absent history, legacy
journals and typed errors for every M6 action. Initial test compilation caught a
non-Sendable test-storage capture; it was corrected before the passing run.
The full `NoahTests` suite was run **once** after the final code changes:
**342 total, 326 passed, 16 opt-in skipped, 0 failed**, across **79 suites**;
parameterized execution reports **388 passed / 16 skipped**. No target or suite
was excluded. All runs used Nix, XcodeGen, normal simulator code signing,
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, disabled parallel testing and
`/private/tmp/noah-040-a7-dd`. Summaries were exported from xcresulttool before
removing the result bundles.

Live verification used a fresh throwaway wallet on **Noah Prepared 040 a7dba81a**,
iPhone 17 / iOS 27.0, UDID `53C1AED5-6012-4B4D-B72B-684104C80488`:

- Funded once with 60,000 + 40,000 sats via two Core payments; exactly two blocks
  mined to confirm funding. No service, container, other wallet or device reset.
- Native Back from a signed 5,000-sat review released its reservation. Core
  reported “Transaction not in mempool” before confirmation; another preparation
  successfully used the same input. The live harness also refused expired and
  edited reviews, closed/reopened the wallet, verified its preparation registry
  was empty, and refused the stale pre-close handle.
- Entered-amount review/receipt/Core agree: **5,000 sats**, **143-sat fee**,
  **142 vbytes**, **1 input**, txid `c2ef1c4aff32…`. Core independently decoded
  the recipient output and reported the mempool fee; no outgoing block mined.
- Confirmed-only MAX review/receipt/Core agree: **59,900 sats**, **100-sat fee**,
  **99 vbytes**, **1 input**, txid `c453b0573f2a…`. It spent the remaining
  **60,000 confirmed sats**, excluding **34,857 sats of unconfirmed change**.
- The production app reopened the unacknowledged MAX receipt. The app process was
  explicitly terminated with `simctl terminate`, then relaunched. Send again
  showed Submitted, 59,900 sats, Actual fee 100 sats, and the **same full txid**.
  The crash-before-final-receipt-write case uses the journal regression tests;
  this live kill was after the returned receipt had been saved.

The opt-in live test passed **1 test, 0 failures/skips**, through production
session/SDK, controller/journal and native confirmation UI (Back/Confirm driven
through the Device panel). Broadcast `failed`/`notAttempted` were covered with
SDK-value/controller regressions rather than injecting storage/network faults
into the shared regtest stack. External providers and physical hardware were not
exercised in this round.

Repeat with `TEST_RUNNER_NOAH_PREPARED_LIVE=1` and a fresh Core address in
`TEST_RUNNER_NOAH_PREPARED_RECIPIENT`, selecting
`-only-testing:NoahTests/SendPreparedLiveTests/preparedReviewCancelEnteredAndMax()`.
Fund `Documents/prepared-fund.txt` with two confirmed UTXOs totaling 100,000 sats.
Tap Back on the first review, inspect/tap Confirm on the second, then create
`Documents/prepared-max-go` after checking the entered receipt. Inspect/confirm
MAX, reopen the real app and kill/relaunch before Done. The harness exports
`Documents/prepared-{cancel-review,entered-review,entered-receipt,max-review,max-receipt}.json`.
Never rerun the creating test in a wallet that already holds funds/journals.

Artifacts: `/private/tmp/noah-040-{targeted-summary,entered-core-evidence,max-core-evidence}.json`,
`/private/tmp/noah-040-*-ax.txt`, and review/relaunch PNGs. Runtime destinations,
seeds and databases are not committed. DerivedData and xcresult bundles are
removed during final cleanup; summary JSON/logs retain the verification counts.
Cleanup completed: closed the Device panel, verified the owned simulator’s exact
name/UDID and Shutdown state, deleted only that simulator, and confirmed its
absence. Removed the owned DerivedData directory and all result bundles, including
the initial failed-build bundle. Other simulators/devices and Docker lifecycle
were untouched.

Run shared fixture generation from the repository root:

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-send.ts
```

The generator executes the RN implementations. `send-flow.json` covers every
combination of stage confirmations, one/multiple rails, unavailable rails and
zero/one/two funding sources. `send-utils.json` includes the RN test inputs plus
BOLT11 amountless, zero, sub-satoshi, fractional-satoshi and network cases. Altered
invoice headers are decoder-only fixtures; their signatures are not valid and
these fixtures must never be submitted as payments. Existing `fiat-currency.json`
is consumed by `ReceiveFixtureTests` for the shared fiat implementation.

Use a dedicated simulator and DerivedData (never another developer's device):

```sh
cd apps/ios
xcodegen generate
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$SEND_SIMULATOR" \
  -derivedDataPath /private/tmp/noah-send-derived test \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -parallel-testing-enabled NO
```

The round-2 full `NoahTests` xcresult reports **246 tests: 236 passed,
10 opt-in tests skipped, 0 failures** (56 suites) on a dedicated iPhone 17,
iOS 27.0 simulator, built with `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`. No target or
suite was excluded. Swift Testing's parameterized cases account for 269 passed
individual runs. The pre-fix regression run failed both missing-BDK-history and
wrong-destination-amount assertions; both pass after the fix.

## Round-5 wallet identity and checkpoint adoption (2026-10-19)

Reviews and attempts now carry the wallet fingerprint and network. A foreign
journal is removed before exposing its receipt or reconciling it; failed removal
blocks Send. A successful deletion, creation, restore or committed-restore
recovery invalidates the process-wide controller while replacement is reserved.
A generation guard rejects status/submission callbacks queued for the old wallet.
Same-wallet restarts preserve the journal and lock.

**Legacy rule:** journals without an identity are quarantined, never displayed as
success and never reconciled/adopted. The old format recorded neither an identity
nor a wallet-change epoch, so even an old file timestamp cannot prove that the
wallet has remained unchanged. We therefore never automatically assign an
identity-free journal to the current wallet. It remains locked with only the
explicit, twice-confirmed destructive history-check override; a failed removal
keeps the lock. Existing v1/v2/v3 outcome-provenance migrations still apply to
identity-bound attempts. Tests without live storage inject their own identities
or deliberately exercise the older migration logic in isolation.

A pending BOLT11 checkpoint may be tracked when its canonical invoice, hash and
amount match the reviewed recipient, its fee is within the persisted bound, and
overflow-checked amount + fee is within the authorized total. Failed revocation,
changed movement identity, mismatches and unknown evidence stay locked. Existing
checkpoint amount/fee remains visible on mismatches. The quote retains a
checkpoint already observed during review: disappearance before confirmation
cannot become a new-payment permission. LNURL resolves and validates its invoice
at review, then confirmation reuses that exact invoice rather than asking the
callback to issue another one. The recipient, comment and original callback
parameters retain round-4 validation.

The session exclusively holds Send from confirmation inspection through durable
journal writes and advancement, excluding both replacement and another creator.
The adopted checkpoint is serialized before any advancement and survives caller
cancellation/restart. Advancement uses only `checkLightningPayment(wait: false)`;
there is no `payLightningInvoice` fallback, including when the checkpoint vanishes
inside that check. No HTLC exit policy is enabled. A preliminary durable marker
also preserves the lock if inspection or the adoption write fails.

Tracking uses the checkpoint's held funds, not a fresh spendable-balance gate.
Direct-invoice review stays available for a known pending hash even at zero
spendable balance; LNURL defers this decision until its invoice resolves. Fresh
payments still require sufficient funds. A paid SDK status with nonempty preimage
and one successful exact-amount movement establishes success; receipt amounts and
fees come from that movement and must remain within the reviewed authorization.
Missing/ambiguous evidence, errors and failed revocation cannot unlock an adopted
attempt, even when failed/canceled history appears later.

Verification uses the dedicated `Noah Send Round 5 t3-6082a3cd` iPhone 17 / iOS
27.0 simulator, UUID `D0B4DDBF-3EA1-4440-A16E-0FD10D55F582`, Nix, XcodeGen,
`ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, and `/tmp/noah-send-r5-6082-dd`.

Final full `NoahTests`: **318 tests, 303 passed, 15 opt-in skipped, 0 failed**,
across **73 suites**; parameterized cases account for **364 passed individual
runs**. No targets or suites were excluded. Result:
`/tmp/noah-send-r5-full.xcresult`. The related Send/restore run passed **46 tests /
69 individual runs** (`/tmp/noah-send-r5-targeted4.xcresult`); the final identity/
adoption run passed **13 tests / 29 individual runs**
(`/tmp/noah-send-r5-targeted-final.xcresult`). All have zero failures/skips.
The simulator's exact name/UUID and Shutdown state were verified before deletion;
other devices and the shared stack were left alone.

The real replacement regression uses the SDK, SQLite, encrypted backup archive,
Keychain, WalletLifecycle.delete and WalletRestorer in the same process. It
starts with an unacknowledged Ark receipt, deletes the wallet, restores a backup
from another generated seed and checks the new fingerprint and empty controller.
Its backup server is injected; it does not exercise Settings/onboarding controls.
The cancellation test holds a real open SDK session and proves close, replacement
reservation and a second Send hold all fail until the original hold ends.

Live attempt: the disposable wallet was funded **20,000 Ark sats once**. A single
SDK creating call produced a **2,000-sat** Lightning checkpoint; the native app
was launched and terminated with the checkpoint outstanding, then a separate test
process attempted re-review/adoption. CLN's hold invoice remained **unpaid** with
no incoming HTLC, and the checkpoint failed/revoked before adoption. The adoption
test therefore failed its pending-checkpoint assertion; this is **not evidence of
successful live adoption**. Sender SQLite contains one Lightning movement, failed
with zero effective balance change; no success receipt was claimed. LND was not
running, and the existing CLN route did not hold the payment. Only our disposable
hold invoice was canceled; no service was started/reconfigured and no blocks
were mined. The final harness additionally requires a checkpoint in the quote
before submitting, so it cannot create a fresh payment in this situation.

Live artifacts: `/tmp/noah-send-r5-live-create.xcresult` (1 passed),
`/tmp/noah-send-r5-live-adopt.xcresult` (1 failed before adoption),
`/tmp/noah-send-r5-hold-before.json` and `/tmp/noah-send-r5-hold-cleanup.txt`.
No seeds, invoices, destinations or wallet databases are committed.

To repeat with a reachable held invoice, pass `TEST_RUNNER_NOAH_SEND_ROUND5_LIVE=1`
and `TEST_RUNNER_NOAH_SEND_INVOICE` (a fresh fixed 2,000-sat BOLT11). Run
`-only-testing:NoahTests/SendRoundFiveLiveTests/createHeldCheckpoint()` on a fresh
simulator, fund `Documents/send-r5-fund.txt` once with 20,000 sats, and confirm the
receiver actually holds an HTLC before terminating the app. Then run
`-only-testing:NoahTests/SendRoundFiveLiveTests/adoptHeldCheckpointAfterRelaunch()`.
Wait for `Documents/send-r5-adopted-ready` before settling the hold invoice. The
second process waits up to 180 seconds, tracks through production controller/
journal/session/SDK code, requires a proven actual receipt and exactly one
successful Lightning movement, and exports `send-r5-{adopted,receipt}.json` in
Documents. These test-only creating calls simulate an inherited pre-Noah
checkpoint; the production adoption path never makes them.

## Round-4 payment safety (2026-10-19)

LNURL and Lightning addresses resolve the callback invoice in the app, persist it
and its hash before submission, validate exact amount/network/expiry, then use
the BOLT11 read-only checkpoint guard and payment API. Comments and callback
query parameters are preserved. Like pinned Bark/RN, metadata hash mismatches
warn without rejecting the invoice (LUD-06). The app never calls SDK payLnurl or
payLightningAddress. Offers cannot expose their invoice hash before SDK payment;
any pending Lightning checkpoint blocks the offer before pay, with explicit copy
and a durable uncertain lock. Regression spies assert zero payment calls for an
LNURL checkpoint collision and an unrelated pending checkpoint before an offer;
normal BOLT11/resolved LNURL and empty-checkpoint offer paths still call pay once.

Ark→onchain MAX stays enabled with exact reviewed VTXO IDs and total coins.
Review says “Recipient receives about …” and explains network fee changes;
the fee is estimated and the total is labeled “Total (reviewed coins)”. Receipts
read actual recipient outputs and fees only from an offboard movement whose
metadata txid equals the SDK-returned txid. Missing/malformed/ambiguous evidence
leaves the recipient amount and fee pending, never filled from the quote.
Refresh fills them when available. Older MAX receipts lacking actual-amount
provenance are normalized to pending while preserving their submitted txid/lock.
FFI item 14 now requests recipient amount/fee binding for prepared offboards.

Direct Ark submission now persists `submitReturnedArkSuccess` only after the
SDK's `sendArkoorPayment` returns success (0.3.0 returns Void). The loader accepts
that proof only for the exact reviewed Ark amount. History candidates still
cannot manufacture this provenance. A parameterized regression submits and
serializes a successful attempt without Done, reloads it, verifies the same
receipt remains locked, rejects a second submit, then clears only through Done
for Ark, BOLT11, LNURL, offers, onchain and offboard. Cross-rail and wrong-amount
Ark proofs are rejected; legacy migration remains conservative.

Full final `NoahTests`: **291 tests, 278 passed, 13 opt-in skipped, 0 failed**,
across **70 suites**; parameterized cases account for **323 passed individual
runs**. All six close-before-Done rail cases pass. Dedicated simulator:
`Noah Send Round 4 Fix 73185be8`, iPhone 17 / iOS 27.0, UUID
`943CC723-49C8-4648-8438-622F6B21BCCB`. XcodeGen was regenerated after adding
files; tests used Nix, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, no target exclusions,
and `/tmp/noah-send-r4-fix-73185be8-dd`. Result:
`/tmp/noah-send-r4-final-full.xcresult`. Targeted checkpoint tests also passed
(8 tests / 10 individual runs), as did offboard/existing safety tests
(29 tests / 54 individual runs).

Live harness: **1 passed, 0 skipped, 0 failed**,
`/tmp/noah-send-r4-live.xcresult`. A fresh disposable wallet received **20,000
Ark sats once**, sent **1,000 Ark sats once**, and persisted the unacknowledged
receipt with `proof: submitReturnedArkSuccess`. Bark receiver movement **35**
independently reports successful receipt of 1,000 sats. After the harness exited,
the real app reopened the receipt; it was then explicitly terminated while
showing “Payment sent / 1000 sats” before Done and relaunched. Send still showed
the same successful receipt and Done, with no destructive override. Sender
history contained exactly one Ark send and balance remained 19,000 sats.

After Done, the real composer selected Ark→onchain MAX. Accessibility snapshots
verified “Recipient receives about 16441 sats”, “Estimated fee, 2559 sats”,
“Total (reviewed coins), 19000 sats”, and the network-fee explanation. One Confirm
submitted txid prefix `466906ef2469`. The receipt showed **Submitted**, **16,441
sats**, and **Actual fee: 2,559 sats**, read from resulting movement **3** and
its transaction metadata. Core independently reports **16,441 sats received,
zero confirmations**. SQLite confirms the exact **four reviewed VTXO IDs** were
consumed and recipient + fee = reviewed 19,000 sats. Live quote and actual agreed;
the regression test exercises a changed fee/output. No block was mined and no
Docker service was restarted or reconfigured.

Evidence: `/tmp/noah-send-r4-ark-evidence.json`,
`/tmp/noah-send-r4-offboard-evidence.json`, `/tmp/noah-send-r4-core-evidence.json`;
AX snapshots and PNGs: `/tmp/noah-send-r4-ark-{before,after}-relaunch.{txt,png}`,
`/tmp/noah-send-r4-offboard-{review,receipt}.{txt,png}`. No seeds, wallet data,
invoices or runtime destinations are committed.

Repeat on a fresh dedicated simulator with `TEST_RUNNER_NOAH_SEND_ROUND4_LIVE=1`
and `TEST_RUNNER_NOAH_SEND_ARK_RECIPIENT` (a fresh Bark regtest address), selecting
`-only-testing:NoahTests/SendRoundFourLiveTests/arkReceiptBeforeDone()`.
Fund `Documents/send-round4-funding-address.txt` once with **20,000 sats** within
180 seconds. The harness hosts the real receipt and writes
`Documents/send-round4-ark-ready`; create `Documents/send-round4-finish` after
inspection to finish without acknowledging. Reopen the actual app, open Send,
kill/relaunch before Done and verify the receipt again. Then use the composer
for Ark→onchain MAX to a fresh Core address and compare receipt/movement/Core.

The dedicated simulator was shut down through the Device panel, its exact name,
UUID and Shutdown state checked, then deleted. No other device was changed.

Remaining verification limits: external LNURL/BOLT12 provider acceptance and
camera hardware were not exercised live. Their payment guards use injected
resolution/state and payment-call spies. FFI 14–16 remain SDK work; the app
continues to label estimates/pending values and never infers network acceptance.

## Round-3 payment safety (2026-10-19)

Full `NoahTests` target: **264 tests, 253 passed, 11 opt-in tests skipped,
0 failures**, across 61 suites, on the dedicated `Noah Send Round 3 Fix`
iPhone 17 / iOS 27.0 simulator. Parameterized cases account for **292 passed
individual runs**. XcodeGen was regenerated after adding the test files;
build/test used `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, no target exclusions,
and `/tmp/noah-send-r3-fix-derived`. Result: `/tmp/noah-send-r3-fix-full3.xcresult`.
The checkpoint regressions exercise both equal and mismatched checkpoint
amounts and assert **zero SDK payment calls**. Raw v1/v2 migration fixtures cover
wrong-amount BDK receipts, fabricated Lightning receipts/already-paid states,
missing provenance, and failed migration writes. Existing unreadable-journal,
missing-BDK-history, disabled MAX, pre-recording error and reorg tests pass.

- Journal safety v3 persists submit-returned-txid / actual-movement provenance.
  All older receipts (including early-v2 fabricated Lightning and wrong-amount
  BDK receipts) reopen uncertain; old txids survive as possible evidence only.
  Current-version receipts missing proof also reopen. Migration is atomically
  saved before exposing state; write errors and unreadable journals block Send.
  The destructive override is available for uncertain attempts without a proven
  receipt, including Lightning. It never runs from Done or ordinary Confirm.

- Only a txid returned directly from submission, with its provenance persisted,
  binds an onchain transaction to the attempt. Snapshot differences (including a
  rediscovered old same-address/amount transaction) display “This transaction may
  be your payment: <txid>” and remain uncertain/locked across refresh and restart.
  Only the explicit destructive history-check override permits a fresh attempt.

- Entered-amount onchain-wallet sends remain enabled by user decision. Review
  labels both fee and total as estimates and explicitly warns that more input
  coins may raise the final fee/total. Receipts show the actual BDK fee when
  available (including after refresh); otherwise they retain the estimate labels
  and warning. FFI item 14 now prioritizes prepared sends with a fee ceiling.

- Before BOLT11 submission, SDK 0.3.0 `lightningSendState(paymentHash:)` reads the
  paid fact/checkpoint without driving it. Existing checkpoints never call pay,
  including checkpoints inherited before any movement existed. Their actual
  amount and fee are persisted and displayed alongside any reviewed mismatch.
  Read-only status checks keep mismatched attempts uncertain even if the old
  checkpoint later settles. Pending/unknown movement evidence also blocks pay.
  Paid invoices use actual movement amounts/fees and never call pay again.
  See [SDK implementation](https://github.com/smolcars/bark-bolt-ffi/blob/v0.3.0/src/core/wallet.rs).

Live round-3 acceptance: **1 test passed, 0 skipped, 0 failures**, result
`/tmp/noah-send-r3-fix-live.xcresult`. A fresh disposable wallet received 10,000
Ark sats and 30,000 onchain sats, with exactly one block mined to confirm the
funding. It paid a fresh **amountless** CLN BOLT11 for 2,000 sats (83-sat fee,
hash prefix `4bde856bb481`), then reviewed and confirmed the same invoice for
1,000 sats. The result was **Invoice already paid**, showing the real 2,000 sats
and 83-sat fee, with an unchanged movement-ID set (exactly one Lightning send).
CLN independently reports `paid` and `amount_received_msat: 2000000`.

The test then hosted the production `SendConfirmation` / `SendOutcomeView` in
its app window. Device-panel accessibility snapshots verified **Estimated fee,
208 sats**, **Estimated total, 5208 sats**, and the complete warning that more
coins may raise the final fee and total. One UI confirm sent 5,000 sats from BDK.
The receipt showed **Submitted**, **5,000 sats**, **Actual fee: 143 sats**, and
txid prefix `ede68f662b8d`. Core independently reports a 5,000-sat receipt with
zero confirmations. No extra block was mined for the outgoing send. The durable
receipt has `proof: submitReturnedTxid`; Lightning has `proof: actualMovement`.
Evidence JSON and accessibility snapshots are in `/tmp/noah-send-r3-live-evidence.json`,
`/tmp/noah-send-r3-review-snapshot.txt`, and `/tmp/noah-send-r3-receipt-snapshot.txt`;
screenshots are `/tmp/noah-send-r3-review.png` and `/tmp/noah-send-r3-receipt.png`.

Repeat on a fresh dedicated simulator with `TEST_RUNNER_NOAH_SEND_ROUND3_LIVE=1`,
`TEST_RUNNER_NOAH_SEND_INVOICE` (a fresh **amountless** regtest BOLT11), and
`TEST_RUNNER_NOAH_SEND_ONCHAIN_RECIPIENT` (a fresh Core address). Select
`-only-testing:NoahTests/SendRoundThreeLiveTests/amountlessInvoiceReuseAndEnteredOnchainFee()`.
The test publishes both funding addresses in `Documents/send-round3-funding.json`;
fund each once within 180 seconds, mine one confirming block, inspect the review
and tap Confirm in the Device panel. After inspecting the receipt, create
`Documents/send-round3-finish` to finish the test. This exercises real quote,
journal, SDK submission, confirmation/receipt UI and reconciliation, with no
faked payment services. It does not drive the composer or manufacture a stuck
live checkpoint; mismatched checkpoint refusal uses SDK-value regression tests.

The dedicated simulator `859E8322-DA9F-418B-879E-A8D53315B016` was shut down,
its exact name and Shutdown state checked, and then deleted. No other device or
Docker service was changed. No wallet data, seeds, invoices or destinations are
committed. Fee ceilings, durable operation IDs and explicit broadcast outcomes
remain FFI items 14–16.

## Round-2 payment safety (2026-10-19)

- Onchain-wallet MAX is disabled in the UI, quote/review and submission paths.
  The only app call to BDK drain was removed. Offchain MAX still submits precisely
  the reviewed VTXO IDs.
- Empty canonical BDK history never proves non-submission. No matches, mismatched
  amounts, multiple exact matches, old MAX attempts without receipts, malformed
  evidence and missing snapshots remain locked. Round 3 supersedes the old
  single-new-transaction rule: a snapshot difference is only possible evidence.
- The pinned FFI exposes a message-only `BarkError`, not typed error variants.
  Only the exact outer address/network, fee-validation and transaction-building
  contexts from `OnchainWallet.send` prove pre-recording failure. Source links
  accompany the allowlist. Persistence, unknown and nested backend errors remain
  uncertain; history absence never substitutes for this proof.
- BOLT11 reconciliation and returned Lightning successes read actual movement
  destination amounts and fees. A successful movement in the pre-submit snapshot
  is **Invoice already paid**, with its real amount, not a new success. Pending
  hash recovery also checks amount; mismatches remain uncertain.
- Refresh revalidates confirmed onchain receipts too. A reorg can move them back
  to Submitted, or to an uncertain “can't find” outcome. The recorded txid survives
  refresh and restart; missing evidence cannot enable retry or delete that record.
  External offboards stay Submitted with explicit in-app tracking limitations.
  Mainnet/signet receipts link to the same explorers as History; regtest has no
  public explorer link.
- Round-1 BDK failures inferred from absence and BOLT11 receipts with unverified
  amounts reopen uncertain. All new journal fields remain optional.
- Send tests use deadline polling rather than fixed completion waits. Receipt
  write-failure injection no longer races the async payment. The receive-close
  test accepts cancellation while still asserting no completion and successful
  session reservation after close.

Live round-2 sanity check: **1 test passed, 0 skipped, 0 failures** on the
`Noah Send Round 2` simulator, through production quote, journal, controller and
SDK submission code. A fresh disposable wallet received 10,000 Ark sats once,
then sent 1,000 sats to Bark (receiver movement 32, successful, zero fee) and
paid a fresh CLN BOLT11 invoice for 2,000 sats (hash prefix `778e6e4c5862`). CLN
reported paid with 2,000,000 msat received. Each submission produced exactly one
new successful sender movement; the Lightning receipt fee matched its movement.
No blocks were mined and no services restarted. The simulator is deleted after
verification. No wallet data, seeds, invoices or runtime destinations are committed.

To repeat this opt-in test on a fresh dedicated simulator, pass
`TEST_RUNNER_NOAH_SEND_ROUND2_LIVE=1`, `TEST_RUNNER_NOAH_SEND_ARK_RECIPIENT`, and
`TEST_RUNNER_NOAH_SEND_INVOICE` (a fresh 2,000-sat regtest BOLT11) to xcodebuild,
with `-only-testing:NoahTests/SendRoundTwoLiveTests`. The test publishes its fresh
Ark address in the app's `Documents/send-round2-funding-address.txt`; fund it once
with `just bark send "<address>" "10000 sats"` within 120 seconds. It never retries
submissions, and requires an empty default regtest wallet directory. This test
uses real wallet operations but does not exercise the SwiftUI controls.

The historical acceptance runs below predate the disabling of onchain MAX.

## Payment-safety verification (2026-10-19)

Dedicated simulator: `Noah Send Fix Agent` (iPhone 17, iOS 27), with DerivedData
inside this worktree. No other simulator or port 3000/3100 service was changed;
Docker services were neither restarted nor recreated. Bark at `bfbe6b7c` calls
its unified payment command `send`; the older `send-to` name is unavailable.

- Funded a new simulator wallet with 100,000 Ark sats and 30,000 onchain sats;
  mined one block for the onchain deposit.
- Sent 5,000 sats from the onchain wallet. UI said **Submitted**, with txid prefix
  `37c9de4686f8`; Core saw 5,000 sats with zero confirmations. Actual fee: 143 sats.
- With 24,857 sats of unconfirmed change and zero confirmed sats, onchain MAX
  refused at source confirmation: **Wait for pending onchain funds to confirm.**
  No payment was submitted. After `just generate 1` and wallet refresh, MAX
  reviewed 24,649 sats plus an explicitly approximate 208-sat fee (24,857 total).
  It submitted 24,757 sats with an actual 100-sat fee, txid prefix `f1d176567e4e`.
  After another block, Refresh changed **Submitted** to **Payment confirmed**.
- Stale-route scenario: opt-in `SendLiveTests` resolved Lightning recipient A to
  Ark route A, then imported distinct Ark address B through the scanner's import
  path. Review and actual SDK submission paid only B, 1,000 sats, zero fee. Local
  sender history and Bark receiver movement 27 agree. Only the LNURL resolver
  was faked; payment, quote, controller journal and history were live. No camera
  hardware or external LNURL provider was involved.
- Paid a fresh CLN BOLT11 invoice for 2,000 sats; review fee 83 sats. UI showed
  **Payment sent**; CLN reported `paid`, 2,000,000 msat received.
- Offchain MAX reviewed 93,989 sats plus 2,928 sats fee, total 96,917 sats. It
  submitted txid prefix `b9579cef9e61`. Compared the persisted quote's five VTXO
  IDs with movement 4's input-VTXO records in SQLite: **exactly the same set**.
  Core received 93,989 sats and confirmed it after one block. UI conservatively
  stays **Submitted** because external offboards are absent from BDK's wallet
  history. Relaunch preserved that submitted txid and never exposed retry.
- Final outgoing Ark-backed history: one Ark send, one Lightning send, one
  offboard; the two BDK sends are separate. No duplicate payment.

The dedicated simulator and `apps/ios/build` are removed after verification.
No screenshots, wallet databases, runtime destinations, invoices, or seeds are
committed.

To repeat the live stale-route test, first create and fund a disposable regtest
simulator wallet and obtain two distinct fresh regtest Ark destinations. Pass
`TEST_RUNNER_NOAH_SEND_LIVE_STALE=1`, `TEST_RUNNER_NOAH_SEND_ROUTE_A`, and
`TEST_RUNNER_NOAH_SEND_ROUTE_B` to the xcodebuild command above, replacing the
skip flag with `-only-testing:NoahTests/SendLiveTests`. This test **spends 1,000
regtest sats**. Without the explicit gate it is disabled.

## Live regtest acceptance (2026-10-19)

Created a wallet through onboarding on the dedicated `Noah Send Agent` simulator.
Used its Receive tab to copy fresh receiving addresses. Existing Docker services
were not restarted or recreated. Loaded the existing Bitcoin Core `dev-wallet`
(it was present but unloaded). All CLI payments used the repository's `just`
wrappers, with Bark's `--no-deps` behavior.

- Funded 100,000 sats over Ark. Receive displayed success and Send showed 100,000
  spendable sats.
- Sent 1,000 sats over Ark, amount-first, with a double-tap on confirm. Exactly one
  successful outgoing movement; zero fee.
- Paid a CLN BOLT11 invoice for 2,000 sats, recipient-first. CLN reported `paid`;
  exactly one successful Lightning movement, 83-sat fee.
- Sent 5,000 sats onchain from Ark. Successful offboard movement, 2,560-sat fee.
  Mined one block with `just generate 1`; Bitcoin Core confirmed receipt of
  0.00005 BTC (txid prefix `d6d1420927d2`).
- Funded the app's onchain wallet with 30,000 sats and one confirmation. MAX showed
  both funding buckets separately (89,357 Ark / 30,000 onchain). Selected onchain;
  review estimated 208 sats in fees. The actual drain sent 29,900 sats with a
  100-sat fee, displayed the actual amount, and left no onchain funds. Mined one
  block; Bitcoin Core confirmed 0.000299 BTC (txid prefix `b14261cfa930`).
- Submitted malformed checksum-less offer `lno1q` for 1,000 sats, forcing a native
  throw after confirmation. No new movement or balance change. Send displayed
  “Payment may have been sent”, disabled Back/confirm, and only offered a status
  check. Repeated checks and a full app relaunch kept that state; no resubmission.
- Final Ark movement net: 89,357 sats. Three outgoing Ark-backed movements plus
  the separate BDK onchain drain; no duplicate payment.
- The custom amount keypad and system recipient keyboard were exercised in
  separate stages. Keyboard Done dismisses input before navigation. Review and
  success controls were checked through the accessibility tree.

The dedicated simulator and `apps/ios/build` are removed after verification.
No screenshots, wallet data or runtime payment destinations are committed.

## Submission safety and limits

`SendPaymentController.shared` locks synchronously on the first confirm tap. It
revalidates the quote/balance and snapshots history, then atomically records the
attempt before submission. A sheet's cancellation cannot cancel its owned task;
all SDK operations use counted `WalletSession.withOpenWallet` calls. A failed
metadata write never converts a successful payment into a retryable failure.

Reconciliation follows the rail and strength of identity:

- Onchain-wallet sends use only BDK transactions, never Ark movements. Both Ark
  movement IDs and BDK txids are snapshotted before submission. A new outgoing
  transaction paying exactly the reviewed amount is only a possible match,
  even if confirmed; it never establishes submission. Missing BDK evidence stays uncertain:
  canonical history can evict a transaction that was already broadcast.
  Malformed/unreadable evidence and old journals without a BDK snapshot stay locked. A persist error can leave the transaction in BDK
  memory, so reconciliation reads transactions even if sync fails.
- BOLT11 uses its pre-known hash plus the movement's actual amount. A successful
  pre-existing movement is already paid; a new exact-amount success uses actual
  amounts/fees. Failed/canceled with the reviewed amount permits a new payment;
  pending or mismatched amounts remain locked.
- Ark, offers, Lightning addresses/LNURL and offboards use weak correlation:
  source, subsystem/kind, destination, exact amount (except MAX), new movement ID
  and creation time. Candidates are shown within the uncertain screen. A weak
  success says it appears to have gone through; weak failure or missing history
  **never automatically unlocks**. The explicit “I've checked my history, start
  a new payment” action requires a second destructive confirmation warning that
  the first payment may still complete. Failed marker removal keeps Send locked.
  The native History tab is still a placeholder; candidate evidence is therefore
  displayed directly here. FFI item 16 replaces this weak matching/override limit.
- A returned txid is persisted as **Submitted**, never as proof of broadcast.
  Refresh can upgrade or downgrade confirmation, preserving the txid even when
  history disappears. An external offboard may remain Submitted until FFI item
  15 exposes chain-source status; copy explicitly explains this limitation.

The journal also persists the reviewed quote (including MAX VTXO IDs), method,
source, destination, amount, start time and BOLT11 hash. Pre-fix journals with weak terminal outcomes are reopened as
uncertain; unproven legacy txids remain evidence only. New submit-returned txids
retain their proof and remain tracked across restart. Recipient assignments
invalidate parsing, routes, comment, choices, quote and payment synchronously;
asynchronous results carry a revision guard, and a route records its originating
LNURL recipient before any substitution is allowed.

MAX safeguards until FFI item 14:

- Onchain MAX is unavailable. Separate balance/drain locks cannot prevent daemon
  sync from discovering additional funds between review and spending.
- Offchain MAX quotes `estimateOffboardFee(address:vtxoIds:)` for the exact
  spendable IDs, rechecks that set before submission, and calls `offboardVtxos`
  with precisely the reviewed IDs. Added, removed or replaced IDs require review.
- `feeRates()` is sat/kwu; integer sat/vB rounds up. Exact BDK construction fees
  remain blocked on prepared sends, so the 208-vbyte estimate for entered amounts
  stays explicitly approximate. Only a proven pre-recording SDK error unlocks
  automatically; missing history never does.

Regression tests use fake dependencies and SDK value objects for route changes,
late resolution, MAX balance/set changes, strong and weak identity, unrelated
history, corrupt transaction evidence, all rail recovery paths, submitted receipts,
confirmation, override confirmation/storage failure, and legacy journal safety.
Existing double-tap, cancellation, corrupt journal, repeat metadata, BOLT11 amount
and expiry, RN fixtures and fee-unit coverage remain intact.

Not exercised live: external LNURL/BOLT12 providers, camera hardware, real network
loss during a submitted payment, and a Rust persistence failure. Those outcome
branches use fault-injected tests. No prepared-transaction, broadcast-status, or
operation-ID FFI capability was invented; items 14–16 remain SDK work.
