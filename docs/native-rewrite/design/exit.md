# Emergency exit redesign: build spec

The user approved this on 2026-10-24, with the decisions below. The interactive prototype, `exit-v1.html`, was removed once built; `git log --diff-filter=D -- docs/native-rewrite/design/exit-v1.html` finds it. [`exit-v1-notes.md`](exit-v1-notes.md) covers how bark 0.8.0 exits work (with file:line references), the mapping from bark states to the screen, today's features per platform and the presets. Where this spec, the notes and the prototype differ, this spec wins, then the notes.

**Useful presets:**
- screens and phases: `#scr=warn|choose|confirm|progress|claim|list|done&phase=lift|publish|mempool|bump|confirm|wait|claimable|claiming|claimed`;
- devices and modes: `&dev=iphone|duo|android|fold&theme=light|dark&rm=1&slow=3&still=1`;
- failure states, e.g. `&state=nofunds|feesrose|stuck|shared|stale|cancel`;
- the full run: `#demo=1`.

The principles, tokens, springs, haptics and copy rules are those in [`onboarding.md`](onboarding.md#principles). Send's block row, hold to confirm, the person and server icons, and the orange VTXO ring come from [`send-receive.md`](send-receive.md#motion-language). The page sits under Settings ([`settings.md`](settings.md)), which links to it.

## Scope

**In scope**, replacing today's exit screens on both platforms:
- the warning;
- choosing VTXOs;
- starting;
- the exit in progress;
- the exits list;
- claim and done;
- the failure and attention states.

**Not changed:** the exit logic underneath. Bark drives exits; the apps keep today's calls and safety:
- the estimate (`estimateEmergencyExitFee` or the platform's equivalent) and "Continue without estimate";
- starting all eligible VTXOs or a selection;
- the repeat-protection journal and its override;
- automatic progression plus "Check the chain";
- cancelling while bark allows it;
- `listAllExits` for the list and history;
- the claim call.

No new polling, and no amount matching.

## Decisions

The user made these on 2026-10-24:
1. **The picture is your branch.** Only your path from the round's transaction down to your leaf, with faint stubs for other branches. Not the full onboarding tree, and not the rings alternative.
2. **Hold to confirm** for both start and claim, like Send. Accessibility keeps a standard action plus a system confirmation, as Send does.
3. **"Needs on-chain funds" is inferred now.** bark 0.8.0 never reports it; it leaves the transaction in `AwaitingCpfpBroadcast` and logs a warning. Show "Needs on-chain funds" when a transaction has waited in that state past one progress cycle while the confirmed on-chain balance is below that package's fee. Offer "Add on-chain funds", the deposit QR. Don't match error strings.
4. **"Fees more than it's worth"** shows on each VTXO whose estimated exit fees (its own share, deduplicated across shared transactions) are at least its amount. These VTXOs aren't included in "All". You can still choose them one by one, and they keep the label. Today's dust rule (too small to exit) is unchanged.
5. **The Settings row** shows "In progress" only while an exit is running, from start to claimed or cancelled. Otherwise it shows no value.
6. **The exits list** draws a shared-path picture only when two or more of your exits share a transaction.
7. **Progress** keeps automatic progression plus "Check the chain", the default today.
8. **The claim goes to another wallet by default.** The user worried that claiming to Noah's own on-chain wallet would hand those coins straight back to Ark, since auto-board treats any new confirmed incoming coin as a deposit (iOS `AutoBoardFunding.confirmed`, and Android's equivalent).
   - **Default, "Another wallet":** an address field with paste and scan. It accepts only on-chain Bitcoin addresses for the current network, through today's destination parser. Claim stays disabled until the address is valid. The line under it reads "Check the address. This can't be undone."
   - **Second option, "This phone's on-chain wallet":** with the line "Noah won't move these back to Ark."
   - **When you pick the second option:** the claimed outputs are added to auto-board's handled ledger (iOS `AutoBoardFunding`'s handled outpoints; the Android equivalent) as soon as the claim transaction is known. Auto-board then never offers them. The rest of auto-board is unchanged.

## Screens

- **Warning,** entered from Settings → Emergency exit:
  - "Emergency use only. It takes your bitcoin out of Ark without Ark's server."
  - Four facts: takes a day or more (with the real `exit_delta` in blocks); costs on-chain fees (the per-VTXO estimate); can't be undone once the last transaction is sent; Ark is faster and cheaper.
  - Today's consent is kept.
  - **Choose VTXOs** continues.
- **Choose VTXOs:**
  - All or Choose; amounts and expiry;
  - the "Fees more than it's worth" label (Decision 4), and today's dust exclusion;
  - the total plus the fee estimate;
  - "Add on-chain funds" when the confirmed on-chain balance can't pay the broadcast fees.
  - Review exit.
- **Start:** a summary (amount, fees, transactions, about when it becomes claimable), then hold to start.
- **The exit in progress** (the hero). The motion follows real state from bark's progress, never timers:
  1. **lift:** your VTXO lifts off the Ark line, and the server icon greys out;
  2. **publish:** your path unrolls down to the round's block;
  3. **mempool:** the next transaction drops into the blinking "next" block;
  4. **fee:** a fee dot flies in from the on-chain wallet, whose balance ticks down, showing "Anchor + ₿X fee · n of N";
  5. **confirm:** the block is mined at its real height and the path steps down. Repeat for each transaction;
  6. **wait:** once the leaf confirms, the VTXO gets a padlock, and a bar counts `exit_delta` blocks ("Claimable in N blocks · about X h");
  7. **claimable:** the padlock opens, and the line reads "Ready to claim".

  Rows: Transactions (n of N in blocks), Fees paid, Claimable (block), Left (blocks), and Details (txids with explorer links, states, the timeline). Below them: "Check the chain", and "Cancel exit" only while bark allows it.
- **Claim:**
  - "To" defaults to Another wallet (Decision 8), with the claim fee taken from the amount and what you'll receive;
  - hold to claim;
  - claiming: the VTXO drops into a last block, and the line reads "Claimed in block N".
- **Done:** amount received, network fees, claim fee, how long it took.
- **Exits list:** each exit's phase on one line, the shared-path picture (Decision 6), and today's filters.
- **Failure and attention states.** Each changes the same picture rather than opening a new screen:
  - needs on-chain funds (Decision 3);
  - fees rose (re-bumped);
  - stuck (rebroadcasting);
  - already sent by another wallet (adopted, no fee);
  - stale exit;
  - cancel.

  Wording and rules for each come from bark's states (see the notes). Keep today's "Already spent" and "Swept" details and the repeat-protection override.

**Layout:** the bottom actions must never cover content. The prototype's buttons overlap the Details row on progress screens; don't copy that. The content scrolls above a fixed action area.

## Wide and foldable

- **iPhone Duo open, iPad, landscape:** the explanation and animation sit in the leading pane, and the details and actions in the trailing pane.
- **Android book:** the same split, with nothing across the hinge. Tabletop: the animation on top, details below.
- **Pose changes** keep the screen, the selection and any running phase. They never restart or repeat a start or a claim.

## Platform

- **iOS:** Liquid Glass through `GlassCompat`, and Send's hold button.
- **Android:** Compose, Send's hold, and predictive back.
- **Reduce Motion:** every move becomes a 180 ms fade to the end state, and phases still update.
- **Haptics:** as Send. Light on each block, success on claimed, error on failure.

## Copy

- Hand-translate new strings into es, ja, ko and sv in `apps/i18n/<locale>/native.json`, matching sibling wording.
- Then run `bun apps/tools/translations.mjs extract`, then `bun apps/tools/translations.mjs`, then `--check` (0 untranslated).
- Never run `translate`; it's paid.

## Testing

- **Unit tests:**
  - every bark exit state maps to the right phase and picture;
  - the inferred needs-funds rule (it shows only after a full progress cycle, and clears when funds arrive);
  - the per-VTXO "fees more than it's worth" rule, with shared transactions deduplicated;
  - "All" excludes uneconomic VTXOs;
  - the Settings row value only while live;
  - the shared-path picture only when exits share a transaction;
  - claim to another wallet: address validation by network, Claim disabled until valid;
  - claim to this phone: the claimed outpoints land in auto-board's handled ledger, and auto-board never offers them;
  - hold-to-confirm start and claim can't double-submit;
  - the cancel button appears only while allowed.
- **Device tests and screenshots:** phone, Duo or iPad open, Android book and tabletop, light and dark, Reduce Motion.
- **Live check on the Bark 0.8 regtest stack (`noah-bark08`):**
  - exit one VTXO end to end;
  - mine the few blocks the path needs. The stack's `exit_delta` is 144. Don't mine that many on the shared stack unless the user approves; otherwise stop at "Waiting for the timelock" and cover claimable/claim with fixtures;
  - claim to another wallet (an LND address);
  - one exit with on-chain funds removed, to see "Needs on-chain funds".

## Accepted deviations

Both platforms were built and reviewed to this spec, with these deviations. The user's later decisions and the reviews on 2026-10-24 settled them:

1. **"Fees more than it's worth" uses each VTXO's standalone estimate,** not its deduplicated share (Decision 4). bark-bolt-ffi 0.6.0's estimate returns only set totals. Standalone errs on the safe side: "All" never includes an uneconomic VTXO, and at worst leaves out siblings that are only economic together, which the user can still choose. "All" can't be reviewed until every VTXO in the current set is classified. Replace this once the FFI gives per-transaction estimate data. (still open after 0.7.0: bark keeps the per-transaction breakdown private, so it needs a bark change).
2. **"Needs on-chain funds" evidence (Decision 3).** bark's daemon reports nothing back, and Noah adds no progress calls of its own, because two callers of `progress_exits_with_cpfp` can each build a fee child (`bark/src/exit/bdk.rs:33-84`).
   - **The rule:** the label shows when the confirmed balance is below the parked package's fee (its own weight plus the fee child, at bark's exit fee rate).
   - **Required evidence:** a completed Check the chain, or one daemon sync interval (60 s) plus a margin within the current parking episode.
   - **Resets:** the waiting time restarts when the app returns from the background, and starting or cancelling an exit clears that exit's evidence.
   - **Typed evidence (0.7.0):** when progress reports `exitInsufficientConfirmedFunds`, its `neededSats` is used directly. bark lib-0.8.0 never emits it during CPFP (bark write-up item 1), so the inference above is what users see today.
3. **Waiting and "stuck"** count from the wallet tip at which Noah first saw each transaction in its current status, keyed by exit run and status episode. bark keeps a state's `tip_height` frozen while nothing changes (`bark/src/exit/progress/states.rs:169-173`), so its height can't be used.
4. **"Stale"** shows only after a failed Check the chain, or when the wallet is open without the daemon (no path does that today). "Fees rose" counts only fee children from the transaction's current stay in the mempool.
5. **Claim to this phone and auto-board (Decision 8).**
   - **The claimed set:** the claimed outpoints go into the handled ledger and into a separate claimed set, both written before broadcast. If the write fails, nothing is sent.
   - **What auto-board offers:** its threshold and amount leave out the value of claimed outpoints that are still unspent and confirmed.
   - **Exclusions (bark-bolt-ffi 0.7.0, 2026-10-24):** auto-board passes the claimed outpoints to `prepareBoard(excludedOutpoints:)`, so bark's coin selection never spends them, and it prepares once. This replaced the earlier discard-and-retry-lower loop. A final input guard and the re-check at submit stay as defence.
   - **Unchanged:** manual Move to Ark.
6. **A batched claim** splits its one fee evenly across the exits it covers, so the parts add up. The receipt (txid, actual fee, exits, destination) is written before broadcast, so a broadcast whose answer is lost still shows the net amount. bark's state still decides "Claimed".
7. **Layout and missing data:**
   - **Platform choices:** Android's "To" is two radio rows with the address field inline, and its Cancel and Check the chain are side-by-side pills (Check the chain is a link beside the primary action where space is short). iOS's Cancel confirmation is a sheet, so it stays in the Duo's trailing pane.
   - **What bark doesn't provide:** there's no round-block height label, leaf number or node names. "Took" is measured in blocks.
   - **Details:** Android labels bark's height "Recorded at", and iOS shows its own last Check the chain tip as "Last checked".
   - **Activity:** "Exit details" opens the exit's own page.
