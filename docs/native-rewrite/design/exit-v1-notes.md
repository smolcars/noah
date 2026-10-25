# Emergency exit v1: notes

The prototype, `exit-v1.html`, was removed once built; `git log --diff-filter=D -- docs/native-rewrite/design/exit-v1.html` finds it.

## Presets

**URL presets:**
- `#scr=settings|warn|choose|confirm|progress|claim|list|done`
- `&phase=lift|publish|mempool|bump|confirm|wait|claimable|claiming|claimed` (progress)
- `&state=…`:
  - progress: `nofunds|feesrose|stuck|shared|stale|cancel`
  - choose: `pick|all|unfunded`
  - confirm: `one|all`
  - claim: `wallet|other`
- `&dev=iphone|duo|android|fold&theme=light|dark`
- `&concept=branch|rings`, `&confirm=hold|dialog`
- `&still=1&rm=1&slow=3`, `&details=1`, `&sheet=deposit|check|to`, `&dlg=start|claim|cancel`, `&play=1`

**Walkthroughs:**
- `#demo=1` plays Settings → warning → choose one VTXO → hold → the whole exit → claim → "Claimed in block 921,494". It takes about 42 s, and works on every device.
- The status-bar clock is the simulated wall clock: about 10 minutes a block. It runs from Fri 9:41 to Sat 10:32. The side panel shows the day and the tip height.

**Side panel:** the same choices as chips, plus "Play from here" and the haptic log.

**Reduce Motion** was checked in a real browser:
- every move becomes a 180 ms fade between rest states;
- the phase line still steps through every phase;
- the blink, the pulse dot and the claim ripples stop.

## How bark exits work (bark `lib-0.8.0`)

Paths below are relative to `~/.cargo/git/checkouts/bark-f3369c4cebbcdc63/959a489`.

### The exit chain

- Your VTXO is a leaf of the round's transaction tree.
- The exit publishes the tree's transactions on the path from the round's output down to your leaf, **root first**:
  - the root node tx;
  - the internal node txs;
  - the leaf tx;
  - for each arkoor hop, a checkpoint tx then the arkoor tx. A hop may also add a dust-isolation tx.

  Sources: `lib/src/tree/signed.rs:1112-1132`, `lib/src/vtxo/mod.rs:1632-1643`, `lib/src/arkoor/mod.rs:66-100`.
- The round's own transaction is already on-chain. The exit never publishes it; it only waits for it to confirm (`bark/src/exit/progress/mod.rs:94-122`).
- **Path length.** `RADIX = 4` (`lib/src/tree/mod.rs:7`). A 64-leaf round has 16 + 4 + 1 internal nodes, so each leaf has 3 ancestors and the path is **4 transactions**: root → level 2 → level 1 → leaf.
- **Leaf vs. VTXO.** The leaf tx's outputs are `[VTXO, P2A]`, and the VTXO id is `leaf_txid:0` (`signed.rs:327-336, 1135-1139`).
- **The VTXO output** is a taproot whose user clause is a CSV delay of `exit_delta` (`lib/src/vtxo/policy/mod.rs:264-270`).
- **Every exit tx** is version 3, pays zero fee, has one input, and has a P2A fee anchor as its last output (`lib/src/vtxo/genesis.rs:589-614`, `signed.rs:1050`).

### Getting each one mined

- **One block per transaction.** A tx is fee-bumped only once all its inputs are confirmed, because its CPFP child may spend only confirmed coins. So each tx in the chain needs its own block.
  - Status order: `VerifyInputs` → `AwaitingInputConfirmation` → `AwaitingCpfpBroadcast` (`progress/states.rs:183-198`).
  - Child construction: `bitcoin-ext/src/bdk.rs:417-418`.
  - bark's own test says it: "Each exit tx is broadcast only once its parent confirms" (`testing/tests/bark_sdk/exit.rs:236-237`).
- **The fee** is a CPFP child built from your on-chain (BDK) wallet and broadcast as the package `[exit, child]` (`bitcoin-ext/src/bdk.rs:390-460`, `bark/src/exit/transaction_manager.rs:491-528`).
- **The rate** is the 1-block "fast" estimate (`bark/src/exit/mod.rs:450-452`). If estimation fails, it falls back to 2 sat/vB on mainnet and 1 elsewhere (`config.rs:272, 284`).
- **When fees rise.** On every progress, a tx still `AwaitingConfirmation` is re-bumped if the fast rate is now strictly higher than the package rate (`exit/mod.rs:865-882`, `exit/bdk.rs:12-14, 54-60`).
  - The new fee is `max(new rate × weight, old fee + 1 sat/vB × weight)` (`bitcoin-ext/src/cpfp.rs:56-67`).
- **Stuck or missing packages:**
  - Every sync rebroadcasts any package the node can't find (`transaction_manager.rs:227-239`).
  - An evicted or vanished child goes back to `AwaitingCpfpBroadcast` and gets a fresh child (`states.rs:202-243`).
  - There's no timer of its own; the cadence is whoever calls progress (the daemon: every 60 s).
- **Published by someone else.** The sync watches each tx's anchor. If another wallet (a sibling's exit, or the server) already broadcast the tx with its own child, bark adopts that child with origin `Mempool` or `Block` and pays nothing (`transaction_manager.rs:571-589, 670-716`).

### The waiting period

- When every exit tx has confirmed, the exit becomes `AwaitingDelta` with `claimable_height = highest confirmation height + CSV` (`states.rs:129-143`, `models/mod.rs:168-180`).
- "Claimable" means `tip >= claimable_height` (`states.rs:292-295`).
- `exit_delta` is set by the server, `vtxo_exit_delta`:

  | Where | Value | Source |
  |---|---|---|
  | captaind default | 144 blocks (about a day) | `server/captaind.default.toml:36` |
  | Noah's regtest config | 144 blocks | `scripts/captaind.toml:15` |
  | bark's own tests | 12 blocks | `testing/src/context/mod.rs:426` |
  | Client minimum on mainnet | 96 blocks | `bark/src/lib.rs:503-506` |

- **Why there's a wait:** it gives the server time to use a forfeit if this VTXO was already spent in a round.
- **The whole exit:** at least 4 blocks for the path, then 144 blocks, then the claim's block. That's a little over a day.

### Claiming

- `drain_exits` puts **all** claimable exits into one transaction with a single output (`exit/mod.rs:967-1065`).
- Its fee comes out of the recovered amount, at the 3-block "regular" rate (`1043-1052`).
- It fails with `ClaimFeeExceedsOutput` if the fee leaves less than dust (`1055-1060`).
- The claim is never fee-bumped. If it drops out of the mempool, the exit goes back to `Claimable` (`states.rs:383-388`).
- The end state is `Claimable` → `ClaimInProgress{claim_txid}` → `Claimed{txid, block}`. Any spend of the output counts as claimed (`states.rs:344-382`).

### Funding and the estimate

- **Why on-chain funds are needed:** every unconfirmed exit tx pays zero fee, so its CPFP child must be paid from **confirmed** on-chain coins.
- `estimate_emergency_exit_fee` returns `ExitFeeEstimate{exit_broadcast_fee, claim_fee, fee_rate, txs_to_broadcast}` (`bark/src/exit/estimate.rs:41-66, 93-154`):
  - the broadcast leg is each unconfirmed tx's real weight plus a canonical child (about 152 vB), times the fast rate, times a 1.2 margin;
  - transactions already confirmed, or already well paid in the mempool, are skipped;
  - the claim leg is a signed dummy drain at the regular rate;
  - it never checks that the wallet can afford it.
- **Not enough funds is silent.** `progress_exits_with_cpfp` catches `InsufficientConfirmedFunds`, logs a warning, and the tx simply stays `AwaitingCpfpBroadcast` (`exit/bdk.rs:69-74`).
  - `ExitError::InsufficientConfirmedFunds` is never created in 0.8.0.
  - So the app has to infer "needs funds" from a tx parked in `AwaitingCpfpBroadcast` while the confirmed balance is below that package's fee.

### Shared transactions

- `ExitTransactionManager` keys packages by txid with a refcount, so a tree node shared by several of your exits is tracked, broadcast and paid **once** (`transaction_manager.rs:27-77, 117-165`).
- The estimate dedupes shared txids too (`exit/mod.rs:1078, 1131, 1178`).
- Each exit still reports its own `Processing.transactions` list.
- Across wallets, whoever broadcasts a shared tx first wins, and the others adopt it.

### Starting, driving and cancelling

- **Start.** `start_exit_for_vtxos` and `start_exit_for_entire_wallet` only mark VTXOs (state `Start`); nothing is broadcast (`exit/mod.rs:572-630`).
- **Driving.** The app must call `progress_exits_with_cpfp` again and again: the daemon every 60 s, or Noah's "Progress" (`exit/bdk.rs:33-85`, `daemon/mod.rs:357-379`).
- **Cancelling** is allowed in `Start`, or in `Processing` while the **last** tx is still `VerifyInputs`, `AwaitingInputConfirmation` or `AwaitingCpfpBroadcast` (`models/mod.rs:251-262`).
  - Before cancelling, bark checks the network that the last tx really isn't there (`exit/mod.rs:672-678`).
  - Ancestors already on-chain stay there, and the VTXO can exit again.
- **History** is a `Vec<ExitState>` with block heights but no wall-clock times (`vtxo.rs:241-254`).

### States

There are two layers: the exit's `ExitState` (`models/mod.rs:26-52`, JSON in `bark-json/src/exit/states.rs`) and each transaction's `ExitTxStatus` (`models/states.rs:16-34`). The prototype maps them like this:

| bark | What it means | What the screen shows | What the picture does |
|---|---|---|---|
| `Start` | VTXO marked, nothing sent | "Starting" · "Leaving Ark" | The coin lifts off the Ark line, the server greys out |
| `Processing` (first pass) | Path known | "4 transactions to the chain" | The orange path unrolls from the coin to the round's block, with faint sibling stubs |
| tx `VerifyInputs` / `AwaitingInputConfirmation` | Waiting for its parent's block | the next transaction waits in the branch | the bottom node hangs above "next" |
| tx `AwaitingCpfpBroadcast` | Ready, needs a fee child | "Sending n of 4" | the bottom node drops into the blinking "next" block |
| tx `AwaitingConfirmation{origin: Wallet}` | Package in the mempool | "Anchor + ₿1,215 fee · n of 4", then "Waiting for block N" | a fee dot flies from your on-chain wallet (whose balance ticks down) and docks on the transaction |
| tx `AwaitingConfirmation{origin: Mempool}` | Someone else sent it | "Already sent by another exit" (`state=shared`) | a hollow, not-yours fee dot; your balance doesn't move |
| RBF of the child | The fast rate rose | "Fees rose. Paying more", then "Paying 9 sat/vB" (`state=feesrose`) | a block is mined without it; the old fee dot fades and a bigger one flies in |
| `AwaitingConfirmation`, still unmined | Blocks full at this rate | "Not in a block yet · 3 blocks" (`state=stuck`) | blocks pass, the dot keeps blinking |
| `AwaitingCpfpBroadcast` + low confirmed balance | Silent `InsufficientConfirmedFunds` | "Waiting for on-chain funds", with "Add on-chain funds" (`state=nofunds`) | the node waits under a red dashed ring; the wallet shows ₿310 with "!" |
| tx `Confirmed` | Mined | "Confirmed in block 921,346 · 1 of 4" | the block fills, the row slides, the branch steps down; the next node is tethered to its parent's block |
| last tx `Confirmed` | The VTXO output is on-chain | "On-chain in block 921,349" | your coin lands in a block |
| `AwaitingDelta{claimable_height}` | The CSV wait | "Waiting for the timelock" · "Claimable in 99 blocks · about 17 h" | the coin grows, padlocked; a bar counts 144 blocks with hourly ticks and the tip height |
| `Claimable{claimable_since}` | `tip >= claimable_height` | "Ready to claim" · "Unlocked at block 921,493"; the button becomes "Claim ₿30,738" | the padlock springs open, with orange ripples |
| `ClaimInProgress{claim_txid}` | Claim in the mempool | "Claiming · waiting for block 921,494" | the coin drops into "next"; the destination (your wallet, or the address) waits on the right |
| `Claimed{txid, block}` | Done | the amount becomes ₿30,738, "Claimed in block 921,494", Done | the coin flies into your wallet; the orange ring draws and the check pops |
| `Canceled` | Cancel while allowed | "Canceled · your VTXO stays in Ark" (`state=cancel` opens the dialog) | the coin rises back to the Ark line and the server returns |
| `VtxoAlreadySpent`, `VtxoSwept` | Terminal | list rows only ("Canceled · still in Ark" is drawn; "Already spent" and "Swept" use the same muted row) | none |
| Stale (the exit's tip lags the chain) | Noah hasn't progressed it | today's stale text, "Check the chain" as the main button (`state=stale`) | the last known picture |

The picture moves only when the wallet reaches a state. While a call is in flight, the current phase holds (the "next" block blinks). The demo compresses time; the app wouldn't.

## The numbers (synthetic, coherent)

- **Tip and VTXOs:** the tip is 921,345, on Fri Oct 23 2026 at 9:41, as in Settings v1. The VTXOs are Settings v1's set, still adding up to Home's ₿142,742; the locked ₿1,000 is split into ₿710 (locked) and ₿290 (below the 330-sat dust limit).
- **The exit followed:** ₿31,000 from the round in block 921,201, leaf 41 of 64 (base-4 digits 2, 2, 1, which is how the sibling stubs fan out).
- **Package sizes:**

  | Package | vB |
  |---|---|
  | Tree node + child | 405 |
  | Leaf + child | 322 |
  | Checkpoint + child | 300 |
  | Arkoor + child | 292 |
  | Board + child | 276 |
  | Claim | 54 + 77 per input |

- **Rates:** fast 3 sat/vB, regular 2 sat/vB, with the 1.2 margin.
- **One VTXO:** 4 transactions, an estimate of ~₿5,533 (actual ₿4,611), and a claim fee of ~₿262. You get ₿30,738.
- **All 11 eligible VTXOs:** 45 transactions, not 49, because 3 of them share a round. Estimated fees are ~₿59,018 against ₿18,400 on-chain, so "All" shows the add-funds warning.
- **Uneconomic VTXOs:** ₿7,500 and below cost more than they're worth at today's rate, and the list says so.
- **Timeline:** blocks 921,346 to 921,349 for the path, claimable at 921,493, claimed in 921,494 ("Took 1 day, 51 min").
- These numbers are asserted in the script (`console.assert`).

## Today's exit features, and where they are in the prototype

Sources:
- iOS: `apps/ios/Noah/Features/Onchain/UnilateralExitView.swift`, `OnchainViews.swift`, `OnchainRules.swift`, `Services/Wallet/WalletSession+Onchain.swift`.
- Android: `apps/android/.../onchain/UnilateralExitScreen.kt`, `OnchainScreen.kt`, `VtxoSelectionRow.kt`.
- React Native: `client/src/screens/UnilateralExitScreen.tsx`, `ExitVtxoDetailScreen.tsx`, `hooks/useUnilateralExit.ts`.

| Today | iOS | And | RN | In the prototype |
|---|:-:|:-:|:-:|---|
| Settings row "Emergency exit", "In progress" value | ✓ | ✓ | ✓ | `scr=settings`. The value shows only while an exit is live (today it stays after they finish). |
| Warning: "Emergency use only…" / intro copy | text | text | banner | `scr=warn`: one line, four facts (a day or more, on-chain fees, can't be undone once the last tx is sent, Ark is faster and cheaper), and a picture of the path. |
| Consent: the review sheet / dialog | ✓ | ✓ | ✓ | `scr=confirm` keeps today's explanation as one paragraph, then the hold or today's dialog (`confirm=dialog`). |
| All eligible vs. selected | buttons | buttons | picker | All / Choose segmented control. |
| VTXO rows | ID + amount | ID + status + amount | amount, expiry, ID | Amount and "Expires in N days"; "Fees more than it's worth" in red; dust and locked rows dimmed. |
| Dust (330 sats) | hidden, counted | hidden | shown disabled | Shown disabled ("Too small to exit · under ₿330"); "All" lists what it leaves out. |
| Estimate: broadcast fees, claim fee, amount after fee | on review | on review | live | Live rows, plus the transaction count (shared ones counted once) and the rate. |
| Not enough confirmed funds; start tracking anyway | ✓ | ✓ | ✓ | A red line with "Add on-chain funds" (the deposit sheet: address, Copy, Share); review still allowed, with the warning repeated. |
| Fee warnings (claim eats the value; fees ≥ value) | ✓ | ✓ | | "Fees are more than these VTXOs are worth." |
| Estimate unavailable / continue without | ✓ | ✓ | ✓ | Not drawn; keep today's flow and copy. |
| Automatic progress, manual "Progress Exits" with review | ✓ | ✓ | manual | "Check the chain" with today's review sheet. Tapping Check advances the picture. |
| Stale-exit warning | ✓ | ✓ | ✓ | `state=stale`: today's text, Check the chain promoted. |
| Exit detail: state, heights, txs, history | ✓ | ✓ | ✓ | Progress rows (transactions in blocks, fees paid, claimable, blocks left); Details lists each transaction root-first with its status, the round and leaf, the fee rate, the timelock and the last checked block. |
| Cancel while allowed | detail | detail | card | "Cancel exit" link until the last tx is sent; dialog; the canceled state. |
| Claim: own wallet pre-filled; custom address with the warning | ✓ | ✓ | empty field | `scr=claim`: "To: Your on-chain wallet", a sheet to switch, today's red warning; claims every ready exit at once (as today). |
| Claim receipt / submitted card | ✓ | ✓ | alert | "Claiming", then "Claimed in block N" and the finale. |
| Exits list, status filter | ✓ | ✓ | cards | `scr=list`: grouped Ready / Exiting / Finished, a 5-step mini rail, and the shared tree drawn once. No filter drawn. |
| Operation journal, override repeat protection | ✓ | ✓ | | Not drawn; unchanged. |
| Activity "Exit details ›" link | ✓ | ✓ | | Lands on `scr=progress`. |

## Design choices

1. **The motion is your path, not the tree.**
   - The Ark line with your coin is at the top, and the mempool block row is at the bottom.
   - Between them hang the 4 transactions that lead to your coin, in onboarding's orange branch style, with three faint stubs per node: other people's branches, never drawn out.
   - The bottom transaction drops into "next", takes a fee from your wallet, is mined, and the branch steps down until your coin itself lands.
   - Cause and effect stay legible: one tx per block, the fee comes from on-chain, and the coin arrives last.
2. **Circles are off-chain, blocks are on-chain.** A transaction is a hollow circle until it's in a block. The round's old block sits at the left, labelled "round", and the path is tethered to it.
3. **Fees are visible as money leaving.** The fee dot flies from the on-chain wallet badge and its balance ticks down. That makes "costs on-chain fees" concrete, and makes the out-of-funds state obvious.
4. **The wait is the coin, locked, plus a bar.** 144 blocks is too long for a row of blocks. A padlock on the coin, and a bar with hour ticks and the live tip, say "a day" without a spinner.
5. **One authored moment.** Everything else (pushes, sheets, holds, the segmented control) is the house's snappy springs. Orange stays small: your coin, your path, the timelock's tip marker, the claim ripples and the finale ring. Blocks, fee dots and buttons stay monochrome.
6. **Words follow the picture.** On phones there's one status word under the amount, and the phase line under the picture. On the Duo and fold, a one-sentence explanation per phase sits under the picture in the leading pane; the amount, rows and actions are in the trailing pane.
7. **"VTXOs", per the Settings decision.** "Coin" is only the picture.
8. **Copy is plain and short.** Today's meaning is kept for consent, the stale warning, the custom-address warning and cancel.

## Open questions

1. **The picture: your branch (tree) or rings (no tree)?**
   - Recommended: **your branch.** An exit is literally your branch going on-chain. Drawing only your path, with faint stubs, keeps it from becoming a second onboarding scene, and it explains why it takes several blocks. Onboarding's own notes proposed it.
   - Alternative: rings (`concept=rings`). Your coin is wrapped in one ring per transaction, and each ring peels into a block. There's no tree, but "why this order" and "shared with others" are lost.
2. **Start and claim confirmation: hold, or today's dialog?**
   - Recommended: **hold** ("Hold to start exit", "Hold to claim ₿30,738"). It matches Send, and the review page already carries today's consent text.
   - Alternative: today's destructive dialog (`confirm=dialog`). Either way, VoiceOver and TalkBack get a standard action with a system confirmation.
3. **"Needs on-chain funds" has to be inferred.** bark 0.8 never raises it; the tx just waits.
   - Recommended: show it when a tx is `AwaitingCpfpBroadcast` and confirmed on-chain funds are below that package's fee, and ask for an FFI field later.
   - Alternative: show nothing until the SDK reports it.
4. **Per-VTXO "Fees more than it's worth".**
   - Recommended: show it, using the standalone estimate. Four of the 11 sample VTXOs fail at 3 sat/vB.
   - Alternative: keep only today's total-level warning.
5. **The Settings row value.**
   - Recommended: "In progress" only while an exit is live, and "Ready to claim" when one is. Today it says "In progress" forever.
6. **The list's shared-tree picture.**
   - Recommended: keep it, for the case that saves money (several exits from one round). Show it only when at least two live exits share a transaction.
   - Alternative: rows only.
7. **The exit-progress policy (TODO:424, `exitProgressPolicy: manual | automatic`).** This design assumes automatic progress plus "Check the chain".
   - Recommended: keep automatic as the default. A manual mode would make the picture wait at "Sending n of 4" until the user checks.

## Not drawn, unchanged

- "Continue without estimate", the 30-second quote expiry and the "changed" error.
- The operation journal, "Override repeat protection" and durable operation IDs (TODO:429, FFI 19).
- The suspended-wallet message.
- The explorer links in Details (the rows are there; the icons aren't drawn).
- The deposit QR (today's sheet has one; the prototype shows the address with Copy and Share).
- The status filter in the list.
- `VtxoAlreadySpent` and `VtxoSwept` detail pages: list rows only.
- Reorg fallbacks (`AwaitingDelta` back to `Processing`) keep today's handling.
