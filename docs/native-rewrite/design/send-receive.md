# Send and Receive redesign: build spec

Approved by the user on 2026-10-22. The interactive prototype, `send-receive-v3.html`, was removed once built; `git log --diff-filter=D -- docs/native-rewrite/design/send-receive-v3.html` finds it. Where this spec and the prototype differ, the spec wins.

Useful URL presets:
- Send: `#v=amount|to|method|review|sending&to=maya|lucas|bob|inv|chain|req&out=ok|slow|fail`
- Receive: `#scr=receive&rcv=2100&fmt=lnaddr|request|invoice|ark|onchain&sheet=copy|amount`
- Common: `&dev=iphone|android|duo&theme=dark&rm=1&slow=3`
- Full walkthroughs: `#demo=1`, or `#demo=duo` on the Duo.

The principles, tokens, springs, haptics and copy rules are those in [`onboarding.md`](onboarding.md#principles). Home's patterns carry over: [`home.md`](home.md) covers glass, the styled QR, toasts and Reduce Motion.

## Scope

**In scope:**
- the Send tab and the Receive tab;
- the hold-to-send confirm;
- the sending motion and result;
- the Receive card, formats and arrivals.

**Out of scope:**
- background and asynchronous payments (Live Activities, continued processing, Android progress notifications);
- the bark 0.8 Lightning `retry_for` timeout. That is a separate design ([`../async-payments.md`](../async-payments.md)) and needs a bark-bolt-ffi update first;
- Activity and Settings.

**Not changed:** payment logic. The redesign is UI on top of today's `SendModel`, `SendPayment`, `SendReconciliation`, `SendLightningCheckpoint` and `ReceiveModel` (and their Android equivalents):
- quotes, fees, prepared sends, checkpoints, reconciliation, consent, override audit, journaling and errors all stay as they are;
- every state today's Send can reach must still be reachable and readable.

The only model change allowed is adding which rail a receive arrived on (see Receive).

## Motion language

Ark money is coins:
- **your coin** has an orange ring;
- **the piece you pay** is a solid coin with ₿;
- **you** are an orange person icon, and **the Ark server** is a server icon;
- the tree stays in onboarding only.

**Motion follows real state, not timers.** Each phase starts when the wallet actually reaches it, and the success or failure ending plays only on the real outcome. While a call is in flight, the current phase holds (and may loop subtly); it never runs ahead of the wallet.

| Rail | Phases (phase line in muted text under the stage) | Driven by |
| --- | --- | --- |
| **Ark** | "Splitting your coin": your coin splits into the payment and your change, and the change rolls back. → "Signing with Ark": you and the server each draw a signature line onto the payment. → "Delivering", then the finale (the recipient glides to the centre, the orange ring draws, the check pops). | Today's Ark send call. Show split and sign while it runs; deliver and finale on success. |
| **Lightning** | Split → "Locking ₿X": a padlock on the payment. → "Ark holds it while Lightning pays": the server icon appears, with a dotted tether from the locked coin. → "Paying over Lightning": an orange bolt runs from the server to the recipient, looping while pending. → On success, "Their receipt unlocks it": an orange key (the preimage) travels from the recipient through the server to the lock. The lock opens, the piece goes into the server, then the finale. | Today's Lightning submission and checkpoint. The key plays only when the payment is settled with a preimage. |
| **Lightning failed** | The bolt fizzles to grey ("That payment didn't go through"). → "Ark signs it back to you": the lock opens and a fresh orange-ring coin settles in the centre. | Only a failed state whose refund (revocation) is recorded. If the outcome is unknown, show today's "Payment may have been sent" or "status unavailable" handling instead. |
| **Lightning slow** | The bolt keeps looping. After about 10 s: "Still paying. This can take a few minutes." The result shows a pending sentence, and Done is allowed. | Pending state. |
| **On-chain via Ark** (offboard / send-onchain) | Split → "Swapping with Ark": the server icon appears and your coin goes into the server, which drops an orange transaction dot into the "next" block. → the block row (below). | Today's send. The block row needs the txid. |
| **On-chain from the on-chain wallet** | No swap: the transaction drops straight into the "next" block. | Today's on-chain send. |

**Block row** (on-chain send and receive), mempool.space style:
- **Layout:**
  - **mined blocks:** the three most recent, as solid rounded squares growing darker towards the newest, each with its height under it;
  - **"next":** then a dashed "next" block holding your transaction as an orange dot, blinking slowly.
- **Phase line:** "Waiting for block {tip + 1}".
- **When the transaction confirms:**
  - the dashed block fills, its height label appears and the dot turns into the background colour;
  - the row slides one block along, the oldest fades, and a new dashed "next" block appears;
  - the phase line reads "Confirmed in block {height}", plus "· 1 of 2" when a send needs more confirmations.
- **Data:**
  - **tip:** from the wallet's existing chain source (the SDK's tip height, the same Esplora the wallet uses). Don't add mempool.space.
  - **confirmation:** from today's transaction status / history confirmation height.
  - **refresh:** re-check when the tip changes, or at most every 30 s while the screen is visible.
  - **leaving:** fine at any time. Activity keeps the record.

**Reduce Motion:** every travel, split, flip and bolt becomes a 180 ms fade of the end state. The blink and the listening light stop. Phases and results still update.

**Haptics:** the prototype logs every haptic (light on split and signatures, success on the finale, error on failure).

## Send

### Entry and flow

**Flow:** Amount → To → (Method, if a request offers several rails) → (Balance, for on-chain when both balances can pay) → Review → Sending. These are today's `SendStage` steps and entries, restyled:
- amount-first from the tab;
- recipient-first from a scan, paste, the QR hub, Home's scan or Pay again;
- Max.

Invoices that carry an amount skip the keypad. If you had already typed an amount, the review says "The invoice sets the amount to ₿X".

On phones the tab bar hides on every step after Amount and returns on Done. On the open Duo it stays in the leading pane.

### Screens

| Screen | Layout and behaviour |
| --- | --- |
| **Amount** | "₿152,742 available" above a big centred amount (Space Grotesk 600, about 68 pt, grey "₿0" when empty), fiat with the swap icon below it, and a Max chip (only where today's `canMax` allows). The keypad digits use Space Grotesk; a pressed key grows briefly and a new digit fades in from grey. Over balance: the amount shakes, the line turns to the failure colour, "Only ₿152,742 available", error haptic, Next disabled. Full-width Next pill. Scan and Paste are at the top right (iOS uses the system paste button). Paste and scan from here use the same import as the RN app's `handleImportedDestination`: a request that carries an amount fills amount and recipient and skips the keypad (Method if several rails apply, else Review); one without an amount fills the recipient and stays on the keypad; an invalid one shows the inline error and stays. An empty amount plus Next still goes to To, recipient-first. |
| **To** | Back, "Send" title, scan. The amount small and bold. A glass "To" field with a Paste chip. **Before typing:** "Recent": circles with initials or rail glyphs, derived from recent outgoing history (no new storage). **While typing:** results with their type ("Lightning address", "Noah user", "Bitcoin address", "Lightning invoice", "Payment request"), using today's `SendDestination` parsing and recipient resolution ("No matches" otherwise). Today's errors (network mismatch, invalid invoice, sub-satoshi, and so on) show inline in the failure colour. The note field shows when today's rules allow a comment, with its counter and today's "Adding a note will send this payment over Lightning." |
| **Method** | "How to send ₿X" plus one line. Glass cards per rail: Ark ("Instant · free"), Lightning ("Seconds · ₿12 fee"), On-chain ("About 20 min · ₿420 fee"), each with today's estimate. The recommended rail (today's `recommend`) gets an orange-tint "Best" badge. Unavailable rails are dimmed with today's reason. |
| **Balance** (source) | The same card style: "Ark balance" / "On-chain wallet" with available amounts. |
| **Review** | "Send ₿X" (big, left), the recipient with avatar, then rows: Via (rail glyph + name), Arrives ("Instantly", "In seconds", "In about 20 minutes"; an Ark-funded on-chain send reads "After 2 confirmations", from `roundTxRequiredConfirmations`), Fee ("Free" for Ark, otherwise today's fee, labelled an estimate when it is one), Total. One line of fine print at most: today's notes, such as prepared-transaction expiry, estimated-fee explanation, and the invoice amount note. Override audit, consent and expired-quote flows stay as they are. |
| **Hold to send** | A black pill reading "Hold to send". Holding for 0.8 s traces an orange stroke around the pill, with haptic ticks at 25/50/75% (selection, light, medium) and heavy on completion, which confirms exactly as today's confirm. Letting go early rewinds the stroke. Accessibility: VoiceOver and Switch Control get a standard "Send ₿X" action with a system confirmation instead of holding. Android TalkBack the same. |
| **Sending / result** | "Sending ₿X" (big) and "to {recipient}" above the motion stage; the phase line under it. When it ends, the title cross-fades to the result and rows appear below. Done returns to Amount. |

### Results

| Outcome | Title / line | Rows |
| --- | --- | --- |
| Ark sent | "Sent ₿X" / "It arrived instantly. Ark sends are free." | Via, Fee |
| Lightning sent | "Sent ₿X" / "Paid over Lightning. Their secret is your proof of payment." | Via, Fee (actual), Proof of payment (preimage, shortened, copyable) |
| Lightning pending | "Still sending ₿X" / "Lightning is still finding a route. You can leave this screen; Noah finishes it in the background." | Via, Fee ("up to ₿X") |
| Lightning failed with refund | "Payment failed" / "₿X is back in your wallet. Lightning couldn't reach {recipient}." | Via, Fee "None" |
| On-chain sent | "Sent ₿X" / "It confirms in about 20 minutes. Activity shows when." The line updates to "Confirmed in block N." when the block row confirms. | Via, Fee |
| Today's edge states | Already paid, payment may have been sent, status unavailable, actual fee pending, broadcast retry: keep today's meaning and wording, restyled as a result title, line and rows. | as today |

The background claim in the pending line is true today: bark's server keeps paying, and Noah's existing checkpoint, reconciliation and background sync finish it.

## Receive

### The card

**Default:**
- **QR:** your **Lightning address**, using the styled QR from Home (error correction H, dot modules, rounded eyes, the longship app icon), always on a white card;
- **under it:** the address in bold, then "Scan with any Lightning wallet";
- **no address:** today's "Lightning address unavailable" with its Profile action.

**Add amount:**
- **Sheet:** the same keypad as Send, plus an "Add a note" chip (today's invoice description).
- **Done:** runs today's `ReceiveModel` invoice creation. The QR redraws (radial reveal) as the **unified payment request**: today's BIP-321 URI with the Bitcoin and Ark addresses, the amount and the Lightning invoice. Use error correction M for long payloads, keeping the logo.
- **Band:** an inverted band grows at the bottom of the card with the amount and fiat.
- **Caption:** "Scan with any bitcoin or Lightning wallet".
- **Change amount:** reopens the sheet. Clearing the amount returns to the Lightning address.

**Ways to pay you:**
- **Where:** Copy at the top right opens the "Ways to pay you" sheet. On the open Duo it's the trailing pane instead, with no sheet.
- **Rows:** Lightning address, Payment request, Lightning invoice (dimmed until an amount exists), Ark address, Bitcoin address, each with a value preview, a one-line subtitle and its own copy button.
- **Showing:** tapping a row shows that format on the card (radio dot, a small tilt, the QR redraws).

**Buttons:**
- **Share:** shares the format being shown.
- **Pills under the card:** "Add amount" / "Change amount" (glass) and "Share" (black).

**Listening light:** while Receive is visible and the wallet can receive, a thin orange segment slowly circles the card (7 s a lap). It hides under Reduce Motion.

### Arrivals

Driven by real events on the visible Receive screen. Then the card flips (bouncy) to the back, which shows the orange ring and check, "+₿X" and the rail ("via Ark · just now"), with a success haptic. It flips back after about 3 s. Home's first-payment rules are unchanged.

| Rail | Motion | Event |
| --- | --- | --- |
| **Ark** | The server icon appears above the card and drops a solid coin into the QR centre (your mailbox delivering). An orange ripple, then the flip. | Today's arkoor receive completion for this request's Ark address. |
| **Lightning** | "Incoming over Lightning": a bolt hits the server icon. "Ark holds it for you": a locked coin drops into the card. "Claiming with your key": your orange key rises from below and opens it. "Received", then the flip. | Today's Lightning receive completion for this request's payment hash. If the claim fails, show today's failure message and no flip. |
| **On-chain** | The block row appears under the card: "Seen on the network", your orange dot in the "next" block, "Waiting for block N", then the flip with "On-chain · waiting for a block". When mined: "Confirmed in block N". | A new unconfirmed incoming transaction paying this request's Bitcoin address (or, if it can't be tied to the address, any new unconfirmed incoming on-chain transaction while Receive is open), from today's on-chain sync/history. Confirmation as in the block row. |

**Model change:** add the arrival rail to `ReceiveCompletion` (Ark or Lightning; both are already known in `matching`). On-chain arrival is new observation of existing data; don't change sync cadence beyond "re-check on tip change or every 30 s while visible".

## Wide and foldable

The geometry and posture rules are the same as [onboarding](onboarding.md#foldables-tablets-and-landscape) and [Home](home.md#wide-and-foldable).

**Send, wide:**
- **Leading pane:** the big amount or title, the recipient and the motion stage.
- **Trailing pane:** the keypad, To results, method cards, review rows plus hold, and the result rows plus Done.
- **Fixed:** the tab bar stays in the leading pane, and nothing crosses the fold.

**Receive, wide:**
- **Leading pane:** the card is centred, with the listening light, arrivals and block row. Copy and the pills hide in this pane.
- **Trailing pane:** "Show" + the format list, then Add/Change amount and Share at the bottom. The amount sheet opens over the trailing pane.

**Android tabletop:**
- Send: the stage on top, controls and keypad below.
- Receive: the card on top, the formats below.

**Pose changes** keep the typed amount, recipient, current step, the shown format and any running animation or wait. They never repeat a submission.

## Platform

- **iOS 26:** Liquid Glass on the To field, chips, Scan/Copy/back buttons, method and balance cards, the Add amount pill, the sheets and the wide tab bar, through `GlassCompat` fallbacks. The hold pill stays solid.
- **Android:** the same layout with flat tonal surfaces, Roboto text, Space Grotesk amounts, and Material 3 sheets and navigation.

## Existing behaviour that must keep working

- **Entry points:** scan, paste (from Amount and from To), QR hub, Home scan and Pay again all land in the right step with today's prefill rules.
- **Send's existing rules:** BIP-321 method choice, on-chain balance choice, Max, LNURL comments and the Noah-user Ark shortcut, recurring-payment hand-offs, reconciliation of interrupted sends, Lightning checkpoints, the "already in progress, do not pay again" guard, the override audit, prepared-transaction expiry and fee-estimate disclosure.
- **Receive's existing rules:** invoice creation and failure, the description limit, completion subscriptions, request regeneration and Copy/Share.
- **No storage changes.** Recent recipients come from history.
- **Tests:** keep today's Send/Receive model, parity and device tests green, updating only assertions about removed UI. Add tests that:
  - the motion phases follow real states, so a failure never shows the key or the finale;
  - "back in your wallet" appears only with a recorded refund;
  - the shown QR encodes exactly the chosen format;
  - hold-to-send can't double-submit, including across pose changes.

## Copy

Add new strings to `apps/i18n/en/native.json`, hand-translate them into es, ja, ko and sv, regenerate them, and run `--check`. Never run the paid `translate`.

New strings:
- **Amount, To and Method:** "Hold to send", "Only {amount} available", "Recent", "Results", "No matches", "Name, address or invoice", "How to send {amount}", "Best", "Instant · free", "Seconds · {fee} fee", "About 20 min · {fee} fee".
- **Sending phases:** "Splitting your coin", "Signing with Ark", "Delivering", "Delivered", "Locking {amount}", "Ark holds it while Lightning pays", "Paying over Lightning", "Their receipt unlocks it", "Paid", "That payment didn’t go through", "Ark signs it back to you", "Back in your wallet", "Swapping with Ark", "Waiting for block {height}", "Confirmed in block {height}", "{n} of {total}".
- **Results:** "Sending {amount}", "Sent {amount}", "Still sending {amount}", "Payment failed", the result lines above, "Proof of payment".
- **Receive:** "Ways to pay you", "Show", "Payment request", "Scan with any bitcoin or Lightning wallet", "Scan with an Ark wallet", "Instant and free from Ark wallets", "Bitcoin, Ark and Lightning for {amount}", "Bitcoin and Ark in one code. Add an amount for Lightning", "Change amount", "Add a note", "Incoming over Lightning", "Ark holds it for you", "Claiming with your key", "Received", "Seen on the network", "via Ark · just now", "via Lightning · just now", "On-chain · waiting for a block".

Reuse existing strings where they exist.

## Testing

Test in light and dark, with Reduce Motion on and off, on:
- an iPhone simulator (iPhone 17, iOS 27.0);
- an iPhone Duo simulator (iOS 27.1), closed, half-open and open;
- an Android phone emulator;
- a Pixel Fold emulator, closed, open, book and tabletop.

Live, against the regtest stack:
- **Sends:** an Ark send to another wallet, a Lightning send (pay a CLN or LND invoice), a Lightning failure (an unpayable or expired invoice, or one the server can't route), and on-chain sends from both balances. Mining one or two blocks for confirmations is fine.
- **Receives:**
  - **Ark:** from the stack's bark;
  - **Lightning:** pay this wallet's invoice from CLN or LND;
  - **On-chain:** `just bcli sendtoaddress`, then mine a block.

Decode the shown QR for every format in both themes.

Record per platform:
- **one phone video:** an Ark send, a Lightning send, an on-chain send with its confirmation, then Receive: the Lightning address, add amount → payment request, and Lightning and on-chain arrivals;
- **one foldable video:** the Duo or Pixel Fold open, with a send and a receive, plus a pose change mid-wait.
