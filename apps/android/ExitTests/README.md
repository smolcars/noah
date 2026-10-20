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
