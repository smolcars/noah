# Native Android M6 verification

Verified 2026-10-19 (local time), against `feat/native-rewrite` at `cb802af7`,
with bark-bolt-ffi **0.4.0**, minSdk 24, Kotlin/Compose Material 3, and unchanged
Gradle dependency verification. No iOS, server, RN or Android Send files changed.

## Behavior and safety

Android follows the reviewed iOS M6 rules and RN copy. Settings opens VTXOs,
Board to Ark and Emergency Exit. VTXOs have filters, selection, details and
reviewed delegated refresh. Boarding supports an amount and RN's `boardAll` MAX.
Auto-Board is a persisted, one-shot **prompt**, never a background submission:
net Ark value must cover `max(minBoard, 20,000)` after estimated fees, retaining
an estimated 5,000-sat reserve. Both the Settings toggle and readiness are checked
again before confirmation.

Every mutation needs a quote younger than 30 seconds, with wallet identity,
items, destination, amounts, confirmed balance and both fee legs revalidated.
Native calls use the existing counted, cancellation-shielded session. User
execution and journal acknowledgements/overrides also use the wallet mutation
gate. Overlapping pushes are counted; suspended wallets perform no M6 native
operations, including minimum/address queries and Home refresh.

Starting explicitly authorizes Bark's normal daemon to broadcast/fee-bump exits
automatically using onchain funds. Manual Progress Exits has RN's confirmation
copy. Estimates, overview, polling and Check status never call sync/progression/
broadcast. Cancel is available only before the **final** exit transaction is
broadcast. Canceled is terminal and retained.

Attempts are atomically written and fsynced before submission, scoped to operation
and overlapping items. An uncertain board/refresh does not block exits/claims/
cancels. Repeated unresolved starts and cancels follow Bark's harmless-repeat
policy. No automatic resubmission, amount matching or diagnostic-string error
classification exists. Only typed board `INSUFFICIENT_FUNDS`/`DUST`, verified before
recording/broadcast, release an attempt; unknown errors remain uncertain. Claim
PSBT fees are checked before broadcast; cancellation refusals remain errors.

Check status has a narrow cached-read seam: exact transaction IDs, VTXO state and
persisted exit status only. It does **not** call BDK sync, which can rebroadcast
already-authorized transactions. Missing IDs/locked inputs/inconclusive evidence
stay unresolved with “Noah checks again automatically as your wallet syncs.”
Destructive overrides retain the quote, IDs, timestamp and exact displayed consent,
stop blocking new attempts and still reconcile late originals. Done acknowledges
retained audit receipts. Saves retain the newest 100 overrides without pruning
ordinary attempts; single-object/array journals migrate on write. Attempt IDs
cannot be reused, even after override.

Exit discovery is persisted inside the wallet datadir, keyed by fingerprint and
removed by the existing delete/restore directory swap. Legacy `bark.exit` movement
IDs seed discovery. Details read `getExitStatus(id)` directly, preserving canceled/
claimed timelines even with an empty active list after reopen. Boarding receipts
persist the returned txid and actual fee; missing fees reconcile by that exact txid
without another submission.

## Tests

JUnit consumes the same five files in `apps/fixtures` directly: **187 RN-derived
cases** (82 fee/review, 56 cancellation, 12 timeline, 16 overview coordination,
21 auto-boarding). Nothing was copied or regenerated. Additional tests cover
quote correspondence/expiry, MAX intent, claim fee checking, typed error allowlist,
durable-before-call ordering/save failure, scoped blocking, cached-only checks,
override/relaunch/pruning, fee reconciliation, terminal discovery, suspension,
late UI estimates and background activity counts.

The final full JVM suite was run once after implementation, using exactly:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

**786 tests passed in 46 suites, zero failures/errors/skips**. The four M6 suites
contain **216 tests**: 187 fixtures + 13 journal + 8 operation + 8 model tests;
there is also an M6 suspension regression in `WalletSessionTest`. APK assembly and
Android instrumentation-source compilation both passed. Full build: **56 seconds**.
[JUnit totals](evidence/junit-summary.json), [Gradle result](evidence/gradle-result.txt).
Afterward, one status-message-only copy correction was compiled/assembled separately,
and an additional cached active-list assertion passed in the opt-in reopen test.
No additional full-suite run was made.

Live builds used port 3100 through the emulator host alias:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest -PnoahRegtestServerURL=http://10.0.2.2:3100'
```

`ExitLiveTest.regtestAcceptance` is opt-in. Each stage requires
`-e noahM6Live <stage> -e noahM6Owner 2f7e-throwaway`; `create` refuses existing
wallet data or a seed. Every later stage checks the owned-wallet fingerprint.
Successful stages: create, funded, estimate, status (three snapshots), progress,
claim, claimed, board, cancel-cycle, cancel-reopen (twice): **13 instrumented passes**,
zero failures/skips. Board/refresh/start were also submitted through their native
UI reviews; progression, claim and the cancellation cycle used fresh production
session reviews in the harness. The normal app daemon stays enabled; only the
explicit cancellation test opens without the daemon so cancellation can win
before final broadcast, as in the iOS acceptance harness.

## Live evidence

Owned AVD: **NoahM6Android2f7e**, Pixel 6 profile, Android 15/API 35 arm64,
serial **emulator-5588**. Wallet fingerprint **0eb559eb**. Server was already
listening on port 3100; no server source/config or Docker lifecycle was changed.
The initial T3 open automatically assigned the empty newly-created AVD to reserved
serial 5554; it was immediately closed/shut down before installing an app or
creating a wallet, then relaunched explicitly on 5588. No pre-existing AVD at
5554, emulator-5556, physical Pixel, or iOS simulator was operated.

**Funding constraint deviation:** initial `just send-to` used the existing Core
`dev-wallet`, rather than a newly-created funding wallet. It sent **100,000 regtest
sats** to this owned wallet and mined one block, reaching **457**; txid
`2a0c0d51e3e2a7d93e8a30e4f96911df8190f411dcd0f881c5763320c3843294`.
No further existing-wallet spending occurred. All app-side broadcasts used only
the throwaway wallet created by this task. No seed was printed/exported.

- Native manual boarding review: **30,000 sats gross**, **330 sats Ark fee**,
  **208 sats estimated chain fee**, **29,670 sats net**. Submitted txid
  `ac7e596924eaa579766be3bc81240a917a5119e1fb3f2bf51dc876bc7b01a5b4`;
  receipt actual fee **1,543 sats**. Mined exactly three board-confirmation blocks,
  reaching **460**. [Review](evidence/board-review.png),
  [receipt](evidence/board-receipt.json), [receipt UI](evidence/board-receipt.png).
- VTXO detail showed Active, **29,670 sats**, expiry **937**, exit delta **144**,
  depth **1**, weight **496 WU**, and matching anchor. VTXO
  `985e933547fdd2883d8f96125ed6946dec4559ea1352354af60c138959502272:0`.
  Reviewed refresh charged **269 sats** and submitted delegated round **1**.
  Mined one block to **461**. [Detail](evidence/vtxo-detail.png),
  [refresh receipt](evidence/refresh-receipt.json).
- Read-only estimate covered refreshed VTXO
  `6f5bc20cd42758d9595bcbf222eb9d2b08974568843c490a7e7539ffab59dd93:0`,
  **29,401 sats**; broadcast estimate **7,630 sats**, claim **1,295 sats**, proceeds
  **28,106 sats**. Exit entries and cached transaction IDs were unchanged.
  [Estimate](evidence/estimate.json), [deposit sheet](evidence/deposit.png).
- Native start review explicitly showed estimates and automatic progression consent.
  Without manual progress, the daemon broadcast parent
  `f273402a19de72335595d407d338a40fdb65cb0e801602cb787a1f5bc7d15c4a`
  and CPFP child `24a1167e6c368c2cee8a18b52b57b81797988114dc14ac1bc6d8bcdc61f84a8b`.
  Mined one block to **462**. It then broadcast final exit tx
  `6f5bc20cd42758d9595bcbf222eb9d2b08974568843c490a7e7539ffab59dd93`
  with child `22c7ba7c1dd1bc1fe6b9a8a59e28a19af8ccabccddc857dd569b3f90e2b61645`.
  Mined one more block to **463**. [Start review](evidence/start-review.png),
  [receipt](evidence/start-receipt.json), [automatic progression](evidence/status-processing.json).
- Waiting status persisted claimable height **607**. Mined exactly **144** blocks,
  then ran reviewed manual Progress Exits. State became Claimable at **607**.
  [Waiting](evidence/status-waiting.json), [claimable](evidence/status-claimable.json).
- Claimed to a newly-generated address in the same wallet, with built/reviewed fee
  **1,295 sats**, txid
  `3c4528a69d512f3544ee215615aff72becc8dc01227071304b60ee1e749c09d6`.
  Mined one block to **608**. Reopened direct status was Claimed with retained
  Started → Processing → Waiting → Claimable → Claimed timeline.
  [Claim](evidence/claim.json), [terminal status](evidence/claimed.json).
- A second reviewed board used **30,000 gross / 29,670 net** sats; txid
  `306f0ffd487108a16bde8817b990b095a4616a81a28730c843f391a339efbffd`.
  Actual chain fee **2,120 sats**, again versus the **208-sat** estimate.
  Mined three blocks to **611**. Started then canceled VTXO
  `964a66817e359bc11407f1ea088814fdcba94ca6a43965e742b2a78085d98cdf:0`
  with the test daemon disabled. Separate-process reopen retained Canceled and
  Started → Canceled at **611**. [Board](evidence/cancel-board.json),
  [reopen](evidence/cancel-reopen.json).

The relaunched native Emergency Exit overview displayed both Claimed and Canceled;
the canceled detail visibly retained **Started → Canceled · Current**, with no
Cancel action. [Canceled detail](evidence/canceled-detail.png). An additional cached
reopen assertion confirmed the canceled ID was absent from Bark's active list.

Enabled Auto-Board in Settings, returned Home, and observed its confirmation sheet.
Chose **No, turn off**. The journal's bytes/hash and attempt count were unchanged;
a newly-composed Settings page showed Auto-Board unchecked. No board was submitted.
[Prompt](evidence/auto-board-prompt.png), [unchanged journal](evidence/auto-board-decline.json),
[off state](evidence/auto-board-off.png).

Cleanup: immediately before shutdown, mapped `emulator-5588` with
`adb -s emulator-5588 emu avd name` and verified **NoahM6Android2f7e**. Closed its
Device panel with shutdown enabled, deleted only that AVD, and verified both its
`.avd` directory and `.ini` were absent. [Cleanup](evidence/device-cleanup.txt).
Task-owned temporary SDK/build scratch files outside the worktree were removed;
public evidence is retained here. Existing Core/server state and unrelated devices
were left running; the funding deviation above remains recorded.

## Differences and limits

Android uses Material 3 sheets, system keyboard, selectable technical details and
native lists, following iOS's behavior and RN wording. Amounts use the stored
bitcoin unit. As on iOS, fiat annotations and a wide-screen split pane are not
ported here; Swept is shown explicitly. Canceled/claimed terminal discovery and
scoped durable safety intentionally improve on the RN overview's active-list
assumption. MAX and automatic daemon progression follow RN.

FFI 17 (optional manual-daemon policy), 18 (prepared boarding), 19 (durable pre-ID
operation lookup) and 20 (all-exits discovery) remain SDK enhancements. The 5,000-
sat reserve is estimated, not guaranteed. A crash before any correlatable ID
remains uncertain; imported terminal exits without known IDs/movements cannot be
discovered. Restart/uncertainty, override and delayed-fee scenarios have unit tests;
no process was killed mid-broadcast, and delayed initial fee availability was not
forced live. Pending-fund inclusion in MAX follows `boardAll` and was not separately
funded live. Physical hardware, external networks and a complete device/UI test
suite were not exercised. Only the JVM full suite and the opt-in live stages above
were run; Android instrumentation sources were compiled.

## M6 destination, accessibility and reserve fixes (2026-10-20)

Based on `b46fc2e2`. Claims retain RN's ability to sweep to any valid Bitcoin
address on the wallet's network, including cold storage. Emergency Exit prefills
a fresh Noah wallet address and provides **Use my Noah wallet address**. The field
checks native onchain `isMine`; external destinations show **This address isn't
in your Noah wallet. The claimed funds will be sent there.** both beside the field
and in the explicit claim review. The quote persists the ownership flag and
execution revalidates the address, network and ownership alongside the existing
wallet/items/fee checks before recording or broadcasting. Invalid addresses and
changed ownership require another review. Legacy journals still decode.

VTXO selection and exit-start selection now use one toggleable checkbox row.
Its merged semantics include shortened VTXO ID, formatted amount, status and
checked state; disabled rows retain their disabled semantics. Auto-Board review
shows **Estimated onchain reserve** as confirmed balance minus boarding amount
minus estimated chain fee, matching iOS. The reserve remains an estimate.

Targeted verification: **20 tests in 3 suites passed**, zero failures/errors/skips.
The additional regtest address-format assertions passed **10 operation tests**.
The full JVM suite was run **once** with the requested command:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**1,334 tests passed in 61 suites, zero failures/errors/skips**; the five M6 suites
contain **220 tests**, including the unchanged 187 RN fixtures. APK assembly
passed; Gradle took **3m 59s**. After replacing the row's nested Material ListItem
with plain text children so it cannot introduce another merged semantics node,
the final targeted operation/semantics run passed **11 tests in 2 suites**, zero
failures/errors/skips, and APK assembly passed again. No second full-suite run
was made. The semantics test inspects role, action, checked/disabled state and
merged spoken label for both checked states and both enabled states.

Evidence: [full JUnit totals](evidence/m6-fix-junit-summary.json),
[full Gradle result](evidence/m6-fix-gradle-result.txt),
[final targeted totals](evidence/m6-fix-final-targeted-summary.json).
No emulator or physical device was used; no Docker command or funded live
broadcast was run. Task-owned temporary logs/build scratch outside the worktree
were deleted. TalkBack/device UI and external destinations were not exercised
with live funds; ownership/execution use production seams with fake-wallet tests.

2026-10-20 claim-destination regressions (base `1aa9a618`): deferred prefill success/failure preserves edits, failed-fetch retry and empty-review/execution rejection, exact reviewed/drained/journaled destinations, and zero drain/broadcast calls for both ownership flips, ownership errors and invalid/wrong-network addresses; targeted **23 passed** per run (3 runs), full JVM suite once **1,358 passed in 61 suites**, zero failures/errors/skips; no application bug or live-funded claim.

## bark-bolt-ffi 0.5.0 adoption (2026-10-20)

This section supersedes the estimated-boarding and app-discovery limitations above.
Based on native-rewrite `211ecdf2`; Android pins release tag `68b88c73`.
Published AAR/POM hashes match the supplied SHA256SUMS; every LOAD segment in all
three ABIs is `0x4000`. [Artifact verification](../SendTests/ffi050/artifact-verification.txt).

Manual amount boarding now prepares `Amount(gross)`; MAX prepares confirmed-only
`DrainAll`. Review and submission use the same signed transaction. The review
shows gross, net Ark receives, both fee legs, total leaving the onchain wallet,
signed vsize and input count. Auto-Board remains a persisted, one-shot confirmation
prompt with a 5,000-sat reserve. Its exact reserve is confirmed minus prepared gross
minus signed chain fee. The round-1 fix below replaces estimate-based sizing.
No hidden or expired review retains its preparation. The two-minute lifetime,
pause/back/disposal, wallet-change cleanup and late-result generations follow Send.

M6 fsyncs the prepared txid before submit. Accepted receipts retain exact fees and
identity; failed delivery says Noah will keep retrying and cannot be acknowledged
to clear the lock. Explicit notAttempted and audited first-submit invalidated-ID/
input/parameter errors allow another review. Unknown outcomes remain uncertain.
If accepted-board cache persistence fails, discard retains the handle and retries
submission with that same process-local ID before discarding. It never creates a
replacement board. Typed minimum, small-coin/input-count, unavailable/reserved,
changed and not-yet-synced failures have fixed user copy.

Read-only Check status uses exact txids, transactionStatus, Bark pending boards,
board movement output/anchor identity and cached transactions. No amount matching,
sync or broadcast is added to Check status. Missing network evidence never proves
non-delivery. Board and claim receipts keep last known status on transport errors.
Legacy journals still decode; prepared offboards and durable generic operation
keys remain unavailable (handoff 19), so existing conservative workarounds remain.

The exit overview/history now comes from `listAllExits(includeHistory = true,
includeTransactions = false)`, including exact SDK amounts. Direct details use
`getExitStatus` and its amount, with no separate VTXO lookup. Noah's old index is
not read for discovery. Round 1 also removes pre-mutation index writes and the
unused store; existing discovery files are ignored and need no migration or deletion.

Live device: owned **NoahFfi050Android4978**, Android 15/API 35 arm64, explicitly
launched as **emulator-5592**. Disposable wallet fingerprint **a9efc44c**. Funded
**100,000 sats** through `just bcli sendtoaddress` and one block, txid
`a78b668588a5a56955f794302d71b984c99806b3cc20bbc7e9a5afed8c673d48`.
No Lightning funding, container lifecycle changes or other devices were used.
Six blocks were explicitly mined by this task: funding 1, amount-board change 1,
MAX-board completion 3, offboard confirmation 1. Other agents also advanced the
shared chain; its complete height increase is not attributed to this task.

Both boards were approved through the real Compose review, driven with the
Device-panel agent-device session. Bitcoin Core independently agrees:

| Board | Gross / funding output | Ark fee | Ark receives | Onchain fee (review / Core) | vsize (review / Core) | Inputs | Total leaving |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Amount | 30,000 | 330 | 29,670 | 155 / 155 | 154 / 154 | 1 | 30,155 |
| MAX | 69,733 | 330 | 69,403 | 112 / 112 | 111 / 111 | 1 | 69,845 |

Amount txid `fd9bba351987a5b6ce3c8de8f8f4a158535baae8515b1c7e797a8286f5da6f5e`;
outputs 30,000 funding + 69,845 change. MAX txid
`cc91a96b393faec7db1edf63b60cedc3181c1543edd0181638b8a4d05b61ff86`;
one 69,733-sat funding output, no change. Both receipts said In mempool and retained
the prepared txid/fee. Public evidence: [amount review](ffi050/amount-review.png),
[journal](ffi050/amount-board.json), [Core mempool](ffi050/amount-getmempoolentry.json),
[outputs](ffi050/amount-getrawtransaction.json); [MAX review](ffi050/max-review.png),
[journal](ffi050/max-board.json), [Core mempool](ffi050/max-getmempoolentry.json),
[outputs](ffi050/max-getrawtransaction.json). Core evidence omits signed bytes.

Auto-Board's real Home prompt showed **64,637 gross / 64,307 net**, **330 Ark fee**,
**155 chain fee**, **154 vB / 1 input**, **64,792 total**, **5,053 reserve** from
69,845 confirmed. Chose **No, turn off**; Settings was unchecked and the operation
journal stayed empty. A separate opt-in production preparation verified that same
reserve and discarded without submission. [Review](ffi050/auto-review.png),
[prepared values](ffi050/auto-review.json), [unchanged journal](ffi050/auto-declined-journal.json).

The opt-in `Ffi050LiveTest` canceled both boarded VTXOs without a daemon (to win
before broadcast), asserted their removal from the active list, then reopened in
a separate instrumentation invocation. It removed Noah's discovery index before
reading the production overview. Both persisted SDK rows remained: **29,670** and
**69,403 sats**, **Canceled**, each with **Started → Canceled**. No exit blocks were
needed. [Cancel cycle](ffi050/cancel-cycle.json), [reopen listing](ffi050/cancel-reopen.json).
Claimed terminal rows, exact amounts without a VTXO lookup, and claim-receipt chain
statuses are tested in JUnit; a fresh live 144-block claim cycle was not repeated.


Verification: **1,376 JVM tests / 63 suites, zero failures/errors/skips**, required
full test + APK build once in **59 seconds**; [full totals](../SendTests/ffi050/junit-summary.json).
Targeted Send/M6/session tests previously passed 820 cases. Android instrumentation
sources compiled. Real preparation/board/receipt/exit evidence above uses an
explicitly opted-in, fingerprint-checked disposable wallet; fault/crash outcomes
are deterministic tests rather than induced shared-stack failures.

The fingerprint-scoped live harness passed **6 stage executions**, zero failures/skips: create, auto-review, cancel-cycle, cancel-reopen, offboard and offboard-status. The last stage reopened the final APK and retained the same txid / Confirmed at block 629 receipt. The two board submissions and Auto-Board decline also ran through the real Compose UI.

Cleanup completed: verified `adb -s emulator-5592 emu avd name` was **NoahFfi050Android4978**, shut down that Device-panel device, confirmed its serial absent and ports closed, then deleted its AVD. Both AVD directory and ini are absent. Removed the task-owned SDK download/extraction and scratch files outside this worktree; shared Gradle/Android caches and all other devices/containers remain intact. [Cleanup evidence](ffi050/device-cleanup.txt).

Final chain-status guard verification: **3 targeted SendChainStatusTest cases passed**, including absent/mempool evidence preserving unresolved locks; APK rebuilt successfully in 6 seconds. The full suite was not repeated. [Build log](../SendTests/ffi050/gradle-chain-guard.log).

## 0.5.0 review fixes, round 1 — fundable Auto-Board sizing

Auto-Board first prepares confirmed-only DrainAll to learn the fee for the entire
confirmed input set. It always discards that probe before preparing one reduced
fixed gross, subtracting the 5,000-sat reserve and 43 vB at the probe's fee rate
for Taproot change. Only a final signed draft with net ≥ the boarding threshold
and exact reserve ≥ 5,000 sats may reach review. Errors, cancellation, lost
readiness and rejected drafts release their preparations; existing review
lifecycle/expiry cleanup remains in force.

The fake now rejects gross + chain fee above its balance and includes the change
output fee. Regressions cover initially insufficient funds, 100,000 sats with
15 inputs / 9,000-sat fixed fee at 10 sat/vB, exact-reserve and net-threshold
edges, construction failures, cancellation at both preparations and readiness
loss. Targeted OnchainOperationsTest, BoardPreparationsTest and OnchainModelTest:
**40 tests passed, zero failures/errors/skips**. No live device was used.

### Retired exit index

Start/claim/cancel no longer read or write Noah's legacy exit index. The unused
store and session wiring are removed; no read-only migration bridge is needed
because SDK listAllExits already includes terminal history and exact amounts.
Legacy files are left untouched. A corrupt-index regression executes all three
operations and reads the SDK overview without parsing or modifying that file.
The obsolete index-storage unit test was replaced by operation coverage.
OnchainOperationsTest, OnchainJournalTest and WalletSessionTest: **61 tests passed**,
zero failures/errors/skips.

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
