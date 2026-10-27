# Emergency exit v2: design notes

A visual and motion refresh of the approved v1 flow (`docs/native-rewrite/design/exit.md` and `exit-v1-notes.md`). The logic, screens, decisions 1–8 and accepted deviations are unchanged. What changed: the pictures, the motion, and the layout rules that caused truncation.

- Prototype: `exit-v2.html`, removed once built; `git log --diff-filter=D -- docs/native-rewrite/design/exit-v2.html` finds it. Self-contained, no network.
- Screenshots and recordings were sent to the user and are not committed.

## Approved (2026-10-26): what overrides the rest of this file

The user approved v2 with these changes. Where the sections below disagree, this list wins.

1. **Other people's branches:** one level of short faint stubs per transaction on your branch (three per node, each ending in a small ring), and one caption, "Other people's coins in this round", under the lowest stubs, left of the trunk. No forking canopy. Hide the stubs at any level packed tighter than 20 pt (deep trees), and hide the caption when it doesn't fit.
2. **Claim destination:** both "This phone" and "Another wallet" land on the bottom-right badge; for another wallet the badge swaps to a wallet icon with the address under its label.
3. **Exits list: option B.** Only exits that share transactions get a gutter path, drawn once as a sideways branch (the "From the round in block …" group). Every other row gets a narrow 44 pt gutter with just its coin and its state (glow when ready, dial when locked, check when claimed, red "!" when it needs funds, muted when canceled). Prototype: `#scr=list&exits=3&list=b`.
4. **Emergency exit page (revised 2026-10-26):** the explainer tree always shows. It grows once when the page opens and is never removed or swapped while you're on the page. Once exits exist, one card sits under the tree (in the leading pane on wide and foldable layouts). It fades in with a small upward slide on the `snappy` spring, and the facts below ease down. With Reduce Motion (animations off on Android) it only fades in, and exits already known when the page opens show at once. The card is light orange (`orangeTint`, with its icon and counts in `orangeText`) while any exit is in progress or ready to claim, and the soft surface once all have finished. Its title says what it holds: "Pending exits", "Past exits" or "Pending and past exits". The Exits list's counts go under it. It wraps and grows with the text. With one exit it opens that exit; with more it opens the Exits list. It is one button (`exit-entry` on iOS, `exit-all` on Android). This replaces the first version of this item, which hid the tree once exits existed.
5. **Timelock dial:** one tick per 6 blocks.
6. **Pending label:** "Next block".
7. **Fee dot:** kept.
8. **Waiting animation, always on while visible:** the transaction waiting for the next block (and the claim while it waits) glows and breathes at 1.6 s, sends out two expanding rings 0.8 s apart (rounded squares for transactions, circles for the coin), its dashed outline marches slowly, and the "Next block" pill's tint and outline pulse with the breath. It runs for as long as the screen is visible and the transaction waits; pause it off-screen and in the background. Reduce Motion: a steady glow only. Keep this out of the main Canvas redraw: draw the rings, glow and pill pulse in a small overlay at the node's position (see Motion), so the tree Canvas stays still between states.
9. **Copy:** as in "Copy changes", including "Already sent by someone else".

## Presets

v1's scheme, plus four new parameters:
- `#scr=settings|warn|choose|confirm|progress|claim|list|done`
- `&phase=lift|publish|mempool|bump|confirm|wait|claimable|claiming|claimed`
- `&state=`
  - progress: `nofunds|feesrose|stuck|shared|stale|cancel|canceled` (`cancel` opens the dialog; `canceled` is the result)
  - choose: `pick|all|unfunded`
  - confirm: `one|all`
  - claim: `other|otherempty|phone`
- `&dev=iphone|se|android|duoc|duo|fold&theme=light|dark`
- New:
  - `&exits=0|1|3`: no exits, one, or three sharing a path;
  - `&deep=1`: a 4,096-leaf round plus an arkoor hop, so 9 transactions;
  - `&text=xxxl|ax1`: large text;
  - `&t=<seconds>`: any moment of the timeline.
- `&still=1` (freeze), `&rm=1` (Reduce Motion), `&slow=3`, `&details=1`, `&play=1`, `&step=1`, `&demo=1`.

**Side panel:**
- the same choices as chips;
- **Next phase ▶** plays the animation from the current phase to the next one, at real speed;
- **◀ Previous phase**;
- **Play from here**, and **Play the whole exit** (Settings to claimed, about 45 s);
- a legend explaining each element.

## The user's complaints, and what changed

| # | Complaint | Change |
|---|---|---|
| 1a | While claiming, a big empty area sits above the block row | The tree stays in the picture through the claim, dimmed to 40 %. A claim stem runs from your coin to the destination, with the claim transaction on it. The picture is full in every phase. |
| 1b | The Claimed result is a small orange circle with a link icon: "what does that even mean?" | The link icon is gone. The destination is a labelled badge: a phone for "This phone", a wallet for "Another wallet" with the address under it. The coin travels along the stem into it, then a ring draws and a check pops. The tree above shows where the coin came from, and its old spot is a dashed "spent" ring. |
| 1c | On the Exits list, the shared-path tree with ₿ labels takes a lot of room and explains little | The separate picture is gone. Each row's gutter draws that exit's real path, lying on its side: ink for transactions in blocks, a glow for the one in the mempool, hollow for the rest, and the coin at the right. Exits that share transactions share one gutter, so the shared part is drawn once, like a branch. It costs no extra height. It replaces v1's 5-dash rail with the real transactions. |
| 1d | The Emergency exit page's picture (thin trunk, tiny dots, "round", two squares, a dashed "next") is hard to read | It's now an upright tree standing on a "Bitcoin" ground line. The round's block is the trunk's base, your branch climbs through the tree's transactions, and your VTXO is the leaf, sitting on the "Ark" line. Three labels: "Your VTXO, in Ark"; a bracket, "4 transactions, one block each, root first"; and "The round, already on Bitcoin". When the page opens, the branch grows once from the ground up (1.4 s). The block row and "next" square are gone from this picture. |
| 2 | Truncation: "0 in pro…to claim", "₿ 961 · ₿ 93…r other exits" | Nothing truncates (see Layout). The Exits row puts its counts on a wrapping subtitle and leaves out zeros. The fee row shows only the amount, with a wrapping note under it. |
| 3 | Show the real thing; the pending block should glow; a proper tree; better motion | The tree is your actual path in the round's tree (bark's genesis, root first). Every transaction shows its real status. Confirmed ones are ink blocks labelled with their height, so you can see which transaction is in which block. The one in the mempool glows and breathes (1.6 s) with a "Next block" pill. The rest are hollow, with "N more to send". The timelock is a 24-hour dial around the coin. Shared transactions are labelled "shared", and your other exits appear as small orange coins on the sibling branches. |
| 4 | Copy | Changed only where it fixed truncation, explained a picture, or was wrong (see Copy). |

## The picture: one tree, everywhere

- **Progress:**
  - the ground line is the chain;
  - the round's transaction is the ink block on it;
  - your branch climbs to your coin at the top;
  - the label column on the right says which block each transaction is in, or what it's waiting for.
- **The Emergency exit page:** the same tree, before anything is sent.
- **Exits list:** the same path, on its side, in each row's gutter.
- **Review:** the same path as one line: round, transactions, coin, then the dial, then the claim.

The colour rule stays v1's:
- monochrome UI;
- orange only for what's yours and still on its way;
- ink for what's on-chain.

As transactions confirm, the ink part of your branch grows up from the ground. That's the progress bar.

**Long paths** (a deep tree or arkoor hops, 6 or more transactions): when the branch doesn't fit at 26 pt spacing, confirmed transactions stack tighter, like a pile of blocks. Labels that don't fit fold into one range: "Blocks 921,346–921,349". The glowing transaction and your coin are never squeezed. The `deep=1` presets show 9 transactions on an iPhone SE.

## What every element means (bark `lib-0.8.0`)

Paths are relative to `~/.cargo/git/checkouts/bark-f3369c4cebbcdc63/959a489`.

| Element | Meaning | bark state or field |
|---|---|---|
| Ground line, "Bitcoin" | The chain | n/a |
| Ink block on the ground: "Round · 921,201" | The round's transaction, already confirmed. The exit never publishes it; it only waits for it (`bark/src/exit/progress/mod.rs:94-122`) | The VTXO's `anchor_point` / the round's block |
| Your branch, levels 1…N, root first | The exit's transactions: root, internal nodes, leaf, then any checkpoint and arkoor. Radix 4, so a 64-leaf round is 4 transactions | `ExitProcessingState.transactions` (`bark/src/exit/models/states.rs:105-108`); order from `lib/src/tree/signed.rs:1112-1132`, `lib/src/vtxo/mod.rs:1632-1643`; `RADIX` `lib/src/tree/mod.rs:7` |
| Hollow orange circle | Not sent yet: waiting for the one below to confirm | `ExitTxStatus::VerifyInputs`, `AwaitingInputConfirmation{txids}` (`models/states.rs:20-23`) |
| "N more to send" | The hollow ones, counted | The same statuses |
| Solid orange square, red dashed ring, red "Needs a fee" pill | Ready, but no confirmed funds for its fee child (Decision 3, inferred) | `AwaitingCpfpBroadcast` past a progress cycle with confirmed balance below the package fee (`bark/src/exit/bdk.rs:69-74`); iOS `ExitFunds.needsFunds` |
| Glowing dashed square, "Next block" pill | In the mempool with its fee child, waiting for a block | `AwaitingConfirmation{child_txid, origin}` (`models/states.rs:25-28`) |
| Pill subline "fee ₿1,215" | That child's fee | `child_txid` → `OnchainOverview.childFees` |
| Black dot docked on the transaction | The fee child (CPFP), from your on-chain wallet | `origin: Wallet` (`models/states.rs:61-69`) |
| Hollow dot, "sent by someone else"; a sibling branch lights up | Another wallet broadcast it with its own child; bark adopts it and you pay nothing | `origin: Mempool` / `Block` (`bark/src/exit/transaction_manager.rs:670-716`) |
| Pill subline "N blocks without it" | Blocks mined while it waits | Noah's sightings (accepted deviation 3; bark freezes `tip_height`, `progress/states.rs:169-173`) |
| Bigger dot; "a block came without it", then "fee raised · 9 sat/vB" | Re-bumped because the fast rate rose | Child replaced or gone (`progress/states.rs:201-217`); policy `bark/src/exit/mod.rs:860-885`; iOS `ExitProgress.bumped` |
| Grey pill, no glow, "last checked at 921,347" | Stale: Noah can't vouch that it's moving | iOS `ExitProgress.stale` (accepted deviation 4) |
| Ink square, "Block 921,346" | Mined in that block | `Confirmed{block}` (`models/states.rs:29-33`) |
| "· shared" after a label; small orange coins on sibling branches | Your other exits share this transaction; it's tracked, sent and paid once | `ExitTransactionManager` refcount (`transaction_manager.rs:27-77`); iOS `ExitSharing.groups`, `ExitFees.paid().shared` |
| Faint branches with forks; faint leaf rings at the top level | Everyone else's part of the round's tree. Schematic: three siblings per node, and no data is read | Radix 4 |
| Your coin, orange ring | Your VTXO, the last transaction's output (`leaf_txid:0`, `lib/src/tree/signed.rs:1136-1139`) | n/a |
| Coin filled ink | On-chain | The last transaction is `Confirmed` |
| 24-hour dial around the coin, and a padlock | The CSV timelock: `exit_delta` blocks (144 is about a day; a tick per 6 blocks). The arc fills as blocks arrive; an orange dot marks the tip | `AwaitingDelta{confirmed_block, claimable_height}` (`models/states.rs:111-115`, `models/mod.rs:168-180`; computed at `progress/states.rs:129-143`) |
| Dial turns orange, padlock opens, soft glow; "Unlocked · since block N" | Claimable | `Claimable{claimable_since}` once `tip >= claimable_height` (`progress/states.rs:292-295`, `models/states.rs:118-122`) |
| Orange stem from the coin to the destination, with a glowing node and "Next block · claim fee ₿262" | The claim transaction in the mempool | `ClaimInProgress{claim_txid}` (`models/states.rs:125-129`); built by `drain_exits` (`bark/src/exit/mod.rs:967-1065`) |
| Stem turns ink, node "Block 921,494", coin lands, ring and check | Claimed | `Claimed{txid, block}` (`models/states.rs:132-136`) |
| Phone badge, bottom right: "On-chain ₿18,400" | This phone's on-chain wallet: the fee source, and the claim destination for "This phone" | `OnchainOverview.confirmed`; Decision 8 |
| Wallet badge: "Another wallet · bc1qx7…m4k2" | The claim destination when claiming elsewhere | The claim record / destination |
| "Ark" line with the server, at the top | Where your VTXO sits before the exit; it fades as the exit starts, and returns on cancel | `Start` / `Canceled` (`models/mod.rs:28-52`) |
| Dashed ghost branch | `Start`: what will be sent, before bark lists the path | `ExitState::Start` |

Nothing here needs new bark data or new polling. iOS `ExitProgress` already has:
- `heights` per transaction;
- the status kinds and `origin`;
- `child_txid`, and the child fees;
- `stuck` and `bumped`;
- `claimableHeight` and `claimedHeight`;
- `stale`;
- the tip.

`ExitSharing` and `ExitFees` cover the shared parts. The picture needs a few more of those fields passed into `ExitStageScene`: each level's status, each height, the pending transaction's fee, and the shared flags. The list gutter needs each exit's path statuses, which `ExitModel.path` already carries.

## Screens

**Emergency exit (the warning).**
- One line, then the explainer tree, then the four facts and Choose VTXOs.
- With one exit, its row (with its path in the gutter) sits above the tree.
- With several, an "Exits" row with a wrapping subtitle ("6 in progress · 1 ready to claim").
- The explainer grows once when the page opens.

**Choose VTXOs.** v1, except that rows and sublines wrap.

**Review.**
- v1's amount and rows.
- New on phones: a one-line path under the amount (round, transactions, coin, dial, destination), with three captions: "4 transactions, one block each", "144 blocks, about a day", "Claim, to another wallet". It previews the progress picture.
- Wide panes show the explainer tree in the leading pane.

**Exit in progress (the hero).**
- The amount and status line, then the tree (300 pt; 236 on the SE), then the phase line, then the rows.
- Rows:
  - Transactions;
  - Network fees, with an optional note;
  - Claimable;
  - Left;
  - Details.
- Footer: Move exit forward and Cancel exit, or the phase's own action.
- In every state the picture fills its frame; there's no empty band.

**Claim** (Decision 8, as built).
- "To" offers Another wallet (the default) and This phone's on-chain wallet.
- The address field shows the whole address, wrapping in monospace, because the line under it says "Check the address".
- The hold stays disabled until the address is valid, with the red hint.

**Claimed / Done.**
- The received amount.
- The picture: the dimmed tree, the ink stem, and the destination with its check.
- Rows: Claimed in, Network fees (plus the note), Claim fee, Took.

**Exits list.**
- Ready to claim, In progress and Finished.
- The three exits from one round share a gutter, with the header "From the round in block 921,201" and a wrapping caption ("…10 transactions to send, not 14…").
- Other rows have their own path. Finished rows are muted, and the coins carry their state:
  - a check: claimed;
  - the dial: waiting;
  - a glow: ready;
  - a red "!": needs funds.

**Wide and foldable** (Duo open, Fold open):
- The tree and the one-sentence explanation are in the leading pane; the amount, rows and actions in the trailing pane. Nothing crosses the fold.
- Duo closed (466 × 678) uses the phone layout.

## Layout: nothing truncates

- **Rows** wrap: when the label and value don't fit on one line, the value moves under the label, right-aligned. At accessibility sizes (AX1 and up) the label and value stack, left-aligned. That's iOS `ViewThatFits`, or `LabeledContent` with `.labeledContentStyle` switching on `dynamicTypeSize.isAccessibilitySize`; on Compose, `FlowRow`. No `lineLimit(1)` on exit rows or list status lines; today's `ExitListRow` sets `.lineLimit(1)` (`ExitListView.swift:126`).
- **Notes under a row** (the fee split, needs-funds) are a separate wrapping line, not part of the value.
- **Picture labels** scale with Dynamic Type up to 1.25×. They're laid out by priority so they never overlap:
  - the glowing transaction and the claim go first;
  - then your coin;
  - then the round and the latest block;
  - other heights fold into a range when space runs out.
- **The phase line, headline and amount** wrap rather than truncate.
- **The fixed action area** measures its own height. The content's bottom padding follows it, so nothing sits under the buttons (exit.md's bottom-action rule), including two-line buttons at AX1.
- **Checked:** iPhone 393 pt, iPhone SE 375 × 667, Duo closed 466 × 678, Duo open and Fold open (two panes), Android 412 dp; xxxLarge and AX1; light and dark. The `text=ax1&dev=se` presets show the worst case.

## Motion

Calm by rule:
- the house springs only: snappy (0.30 / 0.85), bouncy (0.40 / 0.60) for one settle, soft (0.50 / 1.0), and calm (0.70 / 1.0) for travel;
- one breathing rhythm, 1.6 s, for everything that's waiting;
- no confetti, no bounce loops.

As in v1, the picture moves only when bark reports a new state, never on a timer.

| Moment | What moves | SwiftUI | Compose |
|---|---|---|---|
| Stage change (any) | Old still → new still: positions, colours, opacity | Today's `ExitStageView`: two `ExitStageLayout`s mixed by `MotionTime.spring(elapsed, .snappy)` in a `Canvas` under `TimelineView(.animation(paused:))`, running only for the ~1 s after `.onChange(of: scene)` | `Animatable(0f)` restarted on scene change with `spring(dampingRatio = 0.85f, stiffness = 439f)` (stiffness = (2π/response)²); `Canvas` reads both layouts |
| Start → publish | The Ark line and server fade; the dashed ghost branch turns into an orange branch growing from the ground up, a node popping as the stroke reaches it | `Path.trimmedPath(from:0,to:u)` per link, staggered 0.26 s; nodes scale with `.bouncy` | `PathMeasure.getSegment`, or `drawPath` with a trimmed path; `Animatable` per level with `delayMillis` |
| Into the mempool | Hollow circle morphs into a dashed square; the glow fades in; "Next block" pill appears; a fee dot arcs from the phone badge to the anchor; the balance rolls down | Morph in Canvas from the scene diff. Pill text is `context.resolve(Text)`. Balance uses `.contentTransition(.numericText())` | The same in `DrawScope`; balance with `AnimatedContent` or a rolling counter |
| Waiting in the mempool | Glow and pill outline breathe at 1.6 s | **Not in the Canvas.** A small overlay view at the node's point (from the layout) with `.phaseAnimator([0.45, 1])` on opacity, so Core Animation runs it and the Canvas stays still | `rememberInfiniteTransition().animateFloat`, read only inside `Modifier.graphicsLayer { alpha = … }` on a small overlay, so only the layer redraws |
| Mined | The square fills with ink from the bottom up (160 ms), settles from 1.22× (bouncy), and one thin ripple fades; the link below turns from orange to ink; the height label fades in; light haptic | Canvas, from the scene diff; `.sensoryFeedback(.impact(weight: .light), trigger: confirmed)` | Canvas; `HapticFeedbackConstants.CLOCK_TICK` (Send's) |
| A block without it (stuck, fees rose) | The pill outline flashes ink once and its count updates; for fees rose, the old dot fades and a bigger one arrives | Canvas plus `numericText` | Canvas |
| Timelock starts | The dial draws around the coin (soft); the padlock drops in (bouncy); the coin is ink | Canvas | Canvas |
| Each block of the wait | The arc steps one tick (snappy); the count rolls; the tip dot breathes | Canvas step; the dot is an overlay, as above | as above |
| Unlock | The shackle springs open (bouncy); the arc turns orange; one soft glow, then breathing; success haptic | Canvas plus overlay; `.sensoryFeedback(.success)` | `CONFIRM` |
| Claim sent | Tree labels fade, the tree dims to 40 %, the dial fades; the stem draws from the coin to the destination (calm, 0.7 s); the claim node glows with its pill | trimmed `Path` | `PathMeasure` |
| Claimed | The claim node is mined (as above); the coin travels along the stem and shrinks; the destination grows to 1.12×, a ring draws round it and the check pops; success haptic | Position from the stem's `Path` at `u` (sample the cubic); `.sensoryFeedback(.success)` | `PathMeasure.getPosTan` |
| Cancel | Unsent nodes fade to 35 %; confirmed ones stay ink; the Ark line and server return; the coin settles on it | Canvas | Canvas |
| List gutters | Static paths; only glowing nodes breathe | One `Canvas` per group as an `overlayPreferenceValue` reading each row's coin anchor (`anchorPreference`), so the tree stretches with Dynamic Type; breathing as overlays | `onGloballyPositioned` per row → coin centres → `drawBehind` on the group; breathing via `graphicsLayer` |
| Explainer | The branch grows once when the page opens (1.4 s), then labels fade in | Canvas, `TimelineView` running for 2 s after `onAppear` | `LaunchedEffect(Unit)` on an `Animatable` |
| Screen pushes | Unchanged: the platform push (iOS), shared axis X (Android), a rising cross-fade (wide) | as today | as today |

**Performance:**
- **The Canvas redraws only during a transition**, about 0.3–1.5 s after a state change. Between states it's static. A pending transaction can wait for hours, so the breathing glow must never drive a full Canvas redraw at 120 Hz. Today's `ExitStageView` keeps its `TimelineView` running whenever `animatesAtRest`; v2 moves the breathing into overlays and pauses the `TimelineView` at rest.
- **Pause off-screen:**
  - iOS: `.onScrollVisibilityChange(threshold: 0.1)` (iOS 18), plus `scenePhase == .active`, gate both the `TimelineView` and the overlays' phase animators.
  - Android: run infinite transitions only while the composable is visible (`Modifier.onLayoutRectChanged`, or the list's `visibleItemsInfo`) and the lifecycle is `RESUMED` (`LifecycleResumeEffect`).
  - The prototype draws only canvases that are on screen.
- **No blur or shadow in the Canvas.** v1 used `shadowBlur` on the path, which is expensive on Android. Glows are radial gradients.
- **A pose change** (Duo or Fold) keeps the scene; the layout is recomputed and there's no restart.

**Reduce Motion / remove animations:**
- Every move becomes a 180 ms cross-fade between the two stills (as built today).
- Nothing breathes: the glow is a static 60 %.
- The dial jumps; the coin doesn't travel (fade to the landed still); the explainer appears grown; the phase line still updates.
- iOS: `accessibilityReduceMotion`. Android: `ValueAnimator.areAnimatorsEnabled()` / `ANIMATOR_DURATION_SCALE == 0`. When the scale is 0, snap with no fade.
- Recorded in `exit-v2-reduce-motion-se-light.mp4`.

**Accessibility:** the picture is one element whose label is the phase line plus "n of N transactions in blocks". All numbers repeat in the rows. VoiceOver and TalkBack never read the labels inside the canvas.

## Copy changes

All new or changed strings get hand translations into es, ja, ko and sv, per exit.md.

| Where | Before | After | Why |
|---|---|---|---|
| Emergency exit, Exits row | value "{n} in progress · {count} ready to claim" | a card titled "Pending exits", "Past exits" or "Pending and past exits" (Approved item 4), subtitle "{n} in progress · {count} ready to claim", leaving out zero parts (two new plural strings, joined with " · ") | Truncation; "0 in progress" |
| Network fees row | "{amount} · {shared} shared with your other exits" | value "{amount}"; note "Includes {shared} for transactions your other exits share. Each is paid once." | Truncation; and it was easy to misread (the user read it as "own share plus others' share") |
| Network fees, needs funds | "{amount} · {left} left" | value "{amount}"; note "{left} left on-chain. The next fee needs about {need}." | Truncation |
| Phase line, shared state | "Already sent by another exit · n of N" | "Already sent by someone else · n of N" | Correctness: origin `Mempool` means another wallet; bark dedupes your own exits' shared transactions (`transaction_manager.rs:27-77`) |
| Wide explanation, shared | "Another exit from your round already sent this one…" | "Someone else exiting from your round already sent this one, with their own fee. You don't pay for it." | As above |
| Wide explanation, publish | "Your bitcoin sits at the end of a path of {n} transactions…" | "Your VTXO is a leaf of the round's tree. To reach it, the {n} transactions on your branch go to the chain, root first." | Explains the new picture |
| Exits list caption | "{n} exits from one round: {count} transactions, not {total}. …" | header "From the round in block {height}"; caption "These {n} share part of their path: {count} transactions to send, not {total}. Shared ones are sent and paid once." | Pairs with the gutter tree |
| Picture labels (new) | none | "Ark", "Bitcoin", "Your VTXO", "in Ark", "{n} transactions", "one block each,", "root first", "The round,", "already on Bitcoin", "Round · {height}", "Block {height}", "Blocks {from}–{to}", "Next block", "fee {amount}", "· shared", "{n} more to send", "{n} blocks without it", "a block came without it", "fee raised · {rate} sat/vB", "sent by someone else", "last checked at {height}", "Needs a fee", "add {amount} on-chain", "Locked · {n} blocks", "opens at {height}", "Unlocked", "since block {height}", "claim fee {amount}", "claim", "On-chain", "This phone", "Another wallet", "Back in Ark" | Each picture explains itself |
| Review captions (new) | none | "{n} transactions" / "one block each" (All: "shared ones once"); "144 blocks" / "about a day"; "Claim" / "to another wallet" | The one-line path |

Everything else is today's shipped copy, including "Move exit forward" (it replaced v1's "Check the chain").

## Open questions

1. **How much of the round's tree to draw.** Decided (2026-10-26): one level of short faint stubs per transaction (three per node, each ending in a small ring), with one caption, "Other people's coins in this round", in the space under the lowest one. Stubs are hidden where levels are packed tight (deep trees), and the caption is hidden when it doesn't fit.
2. **Where the claim lands.** Decided (2026-10-26): both destinations land at the bottom-right badge; for another wallet it swaps to a wallet icon with the address.
3. **Exits list: a path on every row, or only shared exits.** Open. Prototype: `#scr=list&exits=3` (A, every row) vs `#scr=list&exits=3&list=b` (B, only the shared group; other rows show just the coin and its state).
4. **The explainer when exits are running.** Decided (2026-10-26): it always shows, with one card to your exits under it (Approved item 4). This replaced the first decision, which hid it once any exit existed.
5. **Dial ticks for other `exit_delta` values.** Decided (2026-10-26): one tick per 6 blocks (an hour), between 12 and 48 ticks; below 72 blocks, one tick per 6 blocks with no hour reading. Regtest's 144 and mainnet's 96 or more both read as a clock.
6. **"Next block" or "In the mempool"** for the glowing pill. Decided (2026-10-26): "Next block", matching Send's blinking "next" block; Details says "In the mempool".
7. **The fee dot.** It's small and unlabelled (the pill says "fee ₿1,215"). Decided (2026-10-26): keep it, because it's the only thing showing that fees leave your on-chain wallet. Alternative: drop it and let the balance tick down.
8. **Animation while you watch for hours.** Decided (2026-10-26): it never stops. The waiting transaction (or the claim) breathes, sends out two rings 1.6 s apart, its dashed outline marches, and the "Next block" pill's tint pulses, for as long as it waits. Reduce Motion keeps only a steady glow. iOS: `TimelineView(.animation)` driving the canvas; Android: `rememberInfiniteTransition`.

## Prototype limits

- It follows one exit (₿31,000, leaf 41 of round 921,201). Tapping any list row opens that exit's timeline in the row's state.
- Numbers come from v1, synthetic but coherent: fees are 3 sat/vB, so the four packages cost ₿1,215 × 3 + ₿966 = ₿4,611, plus a ₿262 claim fee.
- The "ghost" branch at Start assumes a round's usual four transactions, as today's `ExitStageScene` does before bark lists the path.

## Screenshots and recordings

All in `/private/tmp/exit-v2/shots/`.
- `iphone/iphone-{light,dark}-NN-<state>.png`: 27 states, each in light and dark (iPhone, 393 pt):
  - the Emergency exit page with no exits, 1 exit and 3 exits;
  - Choose and Review;
  - the nine phases, `p1-lift` to `p9-claimed`;
  - nofunds, feesrose, stuck, shared, stale, the cancel dialog and canceled;
  - the hero with your three exits sharing a path;
  - the list with 3 and with 1;
  - claim to another wallet, claim to this phone, and Done.
- `more/`: iPhone SE at AX1 and xxxLarge, the deep tree (9 transactions) on SE and iPhone, Duo open, Duo closed, Fold open, and Android.
- Videos:
  - `exit-v2-phases-iphone-light.mp4` (34 s): the whole exit, from Start to the coin landing;
  - `exit-v2-states-iphone-dark.mp4` (28 s): fees rose, stuck, shared, needs funds, cancel;
  - `exit-v2-demo-android-light.mp4` (52 s): Settings → warning → choose → hold → exit → claim to this phone;
  - `exit-v2-list-iphone-dark.mp4`: the explainer growing, the list, then opening a row;
  - `exit-v2-phases-duo-open-light.mp4`: the two-pane layout;
  - `exit-v2-reduce-motion-se-light.mp4`: Reduce Motion on the SE.
