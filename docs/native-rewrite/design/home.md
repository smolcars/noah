# Home redesign: build spec

Approved by the user on 2026-10-22. The interactive prototype, `home-v2.html`, was removed once built; `git log --diff-filter=D -- docs/native-rewrite/design/home-v2.html` finds it.
The principles, tokens,
springs, haptics and copy rules are the ones in
[`onboarding.md`](onboarding.md#principles). Where this spec and the
prototype differ (copy, data rules), the spec wins.

Only the Home tab and the tab bar change in this round. Receive, Send,
Activity and Settings keep their current screens; they are redesigned later.

## States

Home has one layout whose content depends on the wallet:

| State | When | Content |
| --- | --- | --- |
| **Fresh** | Balance is zero and history is empty (and loaded) | Balance (₿0), fiat, nudge, the **get-paid card**. No "No activity yet" list: the card is the empty state. |
| **Everyday** | Anything else | Balance, fiat, any status line, nudge, **Recent** (up to five rows, "See all" opens Activity). |
| **Suspended** | `isWalletSuspended` | Today's suspended state only, restyled. No balance, nudge or list. |

**Busy** in the prototype is Everyday with a pending line, pending and failed rows, and the renew nudge.

**First payment.** When the wallet is Fresh, Home is on screen, and the first incoming payment lands (the first incoming history row appears or the balance goes above zero):
- the card's QR blurs out, an orange ring and check draw, the amount and "from …" (only when the record names a sender) appear, and the balance rolls up, with a success haptic;
- after about 2.4 s the card folds away and Recent appears with the new row marked new, with a light haptic.

If the payment lands while Home isn't on screen, there's no animation: Home simply shows Everyday with the new row marked.

## Layout (compact)

From the top: top bar, balance, fiat, status line, nudge, card or Recent, tab bar.

- **Top bar:**
  - **Leading: the name chip.** It shows an orange-ringed initial and the local part of your Lightning address, plus a small network badge on signet and regtest, which replaces `VariantBadge`. With no address yet, it shows a person glyph and no name. Tap opens Profile, whose My code row shows your Lightning address QR alone.
  - **Trailing:** the activity glyph, scan and settings. Scan opens today's scanner alone (no My code), with today's Send import; closing it or refusing the camera returns to Home.
  - **Activity glyph:** a quiet, fixed-width slot left of scan, so nothing moves. While a backup or another background task runs, a muted arrows glyph turns (it stays still under Reduce Motion); when a backup completes, a green check scales in and fades after about 2 s (a fade only under Reduce Motion). Otherwise the slot is empty. It isn't tappable; VoiceOver and TalkBack read "Backing up wallet", "Background task in progress..." or "Backup completed".
  - The map moves out of the toolbar into Settings → Bitcoin ("Spend bitcoin nearby"); Home has no map row.
  - Scrolling tucks the balance into the centre of the top bar (prototype `onScroll`), and content fades under an edge.
- **Balance:**
  - **Display:** Space Grotesk 600, 50 pt, formatted with today's `BitcoinAmount` and the persisted unit (BIP-177 ₿ or sats).
  - **Changes:** digits roll with the snappy spring and a light blur. On iOS, `.contentTransition(.numericText(value:))` is enough.
  - **Tap:** opens the balance sheet.
  - **Long-press:** toggles today's persisted hide-balance flag, morphing digits into dots with the bouncy spring and a medium haptic. Keep an accessibility action for it, since long-press isn't discoverable.
  - **Hidden:** the fiat value, row amounts, the pending line and the tucked balance all show dots too.
- **Fiat:** today's 5-minute quote and rounding, in the muted colour. It's display-only this round.
- **Status line:** at most one, under the fiat, with a small pulsing dot, in this order:
  1. today's wallet error, in the failure colour, with no dot; not tappable, since it says what to do;
  2. `+{amount} arriving` for today's pending balance, which opens the balance sheet;
  3. today's `pendingRoundMessage`, which opens today's Pending Rounds sheet (the round and its funding transaction) while a round is active, otherwise the balance sheet;
  4. "Round in progress", which opens today's Pending Rounds sheet.

  Backups and background tasks are the top bar's activity glyph, never a line. With no line there's no gap: the nudge, the card or Recent sit at their usual spacing under the fiat. A line appearing or going fades while what's below glides with snappy (at once under Reduce Motion); a new line in its place just replaces the text.
- **Balance sheet:**
  - **Content:** "Balance", the total, then today's `balance.details` rows with their existing labels. No new explanatory copy.
  - **Compact:** a medium-detent sheet.
  - **iOS:** Liquid Glass.
- **Get-paid card (Fresh only):**
  - **Card:** always white with a near-black QR, in both themes.
  - **QR:** the **styled QR** of your Lightning address (the same content as today's QR hub My code): error correction H, round dot modules, rounded finder eyes and the longship app icon in a white tile at the centre (`docs/native-rewrite/design/logo/qr-logo.png`). The QR draws in on appear (prototype `playQR`).
  - **Under the QR:** the address, then "Scan with any Lightning wallet".
  - **Tap and buttons:** tapping the card copies the address. **Copy** (outlined, Liquid Glass on iOS) and **Share** (black pill) sit below it.
  - **No address yet:** the card shows today's "Lightning address unavailable" message with its Profile action instead of a QR.
- **Recent rows** (66 pt; prototype `rowHtml`/`avatar`):
  - **Avatar:** a 42 pt circle on the soft surface.
    - **People and addresses:** the lowercase initial in Space Grotesk. This means a Lightning address or a Noah user, i.e. an address on your own address's domain.
    - **Icons otherwise:** a bolt for Lightning without a name; a link for on-chain (deposits, boarding, offboarding, on-chain sends, exits); a repeat arrow for recurring payments, but only if the app can tell from records it already keeps; and ↓/↑ for anything else.
  - **Title:** the counterparty, which is the local part for Noah users and the full address otherwise. With no counterparty, today's `displayLabel`. Never invent merchant names.
  - **Subtitle:** kind · time, for example "Noah user · 9:12", "Lightning · 18:05" or "On-chain · Oct 12".
  - **Amount:** monochrome Space Grotesk with tabular digits, prefixed "+" in, "−" out and no sign for internal transfers. No green or red amounts any more.
  - **Pending:** a dashed ring turns slowly around the avatar (8 s a turn, still under Reduce Motion). The amount is muted and the subtitle says "Sending" or "Confirming".
  - **Failed:** a red "!" badge on the avatar, the subtitle "Failed" in the failure colour, and a muted, struck-through amount. Add "· nothing left your wallet" only when the record shows no balance change.
  - **Canceled:** like failed, without the badge.
  - **New:** a small orange dot on incoming rows that arrived since Home was last shown. This is kept in memory only, and clears when Home disappears.
  - **Tap:** opens today's transaction detail.
- **Tab bar:** Home, Receive, Send, **Activity** (renamed from History, with a list icon).
  - **iOS 26:** the system floating Liquid Glass tab bar, minimising on scroll as today.
  - **Android:** a Material 3 navigation bar, restyled monochrome with a hairline-tint indicator pill.

Pull-to-refresh stays. The Bitcoin facts line is dropped: v2 has no place for it.

## Nudges

There is one nudge card at a time, in this order:

| Nudge | Shows when | Copy | Action |
| --- | --- | --- | --- |
| Upgrade notice | today's one-time Home notices (lost settings, RN recurring not imported) | today's notice text | × dismisses it, as today |
| **Renew** | the wallet holds refreshable VTXOs that `getExpiringVtxos(thresholdBlocks: vtxoRefreshExpiryThreshold)` reports. That threshold is 144 blocks (about 24 h) on mainnet, which is the maintenance window: the nudge appears only when automatic renewal hasn't happened in time. No earlier warning. | "Renew {amount}" (the sum of those VTXOs) / "Expires in {n} hours" from the soonest expiry at 10 min a block, or "Expires in under an hour" | "Renew" opens today's VTXO refresh review for exactly those VTXOs, with fees shown and explicit confirmation. Never refresh in one tap. Expired or locked VTXOs are excluded. |
| Backup failed | today's failed cloud-backup status | "Backup failed" / today's message | "Retry", as today |
| **Back up** | `needsRecoveryPhraseBackup` | "Back up your wallet" / "Save your recovery phrase" | "Back up" opens today's phrase view; it clears only on the existing confirmation |
| **Email** | email not verified and the prompt not dismissed (today's server state) | "Add your email" / "For urgent wallet alerts" | "Add" opens today's email flow; × dismisses it with today's `dismissEmailPrompt` |
| Update | today's `updateRequired` | "Update available" / "Get the latest fixes" | "Update" opens today's update link |

Resolving or dismissing a nudge folds it away with the soft spring, and the next one slides in with snappy. The prototype's "Backed up" and "Email added" toasts appear only after real success. There is no "Renewed" toast: submitting a renewal only hands the coins to a round, so the app can't confirm it at that moment; the nudge folds away once nothing is due. Today's automatic-boarding completion becomes a 5-second toast instead of a card, keeping today's expiry rules; "Backup completed" is the activity glyph's check instead.

The renew check reuses today's refresh moments: Home appearing, wallet change events, round checks and pull-to-refresh. Don't add a faster timer.

## Wide and foldable

Design for the iPhone Duo first, then make Android foldables match. Geometry and posture rules are the same as [onboarding](onboarding.md#foldables-tablets-and-landscape).

- **Compact** (phones, Duo closed, Pixel Fold closed): the layout above.
- **Wide** (Duo open or half-open, Pixel Fold open, tablets, wide landscape): two panes split at the fold, with nothing crossing it.
  - **Leading pane:** top bar, balance, fiat, status line and nudge.
  - **Trailing pane:**
    - Fresh: the get-paid card, centred;
    - otherwise the full activity list, titled "Activity" and grouped by day ("Today", "Yesterday", dates), scrolling on its own.
  - **No tucking:** the balance doesn't tuck into the top bar on wide layouts.
  - **iOS tab bar:** hide the system tab bar on wide windows and draw a Liquid Glass bar of the same four tabs at the bottom of the leading pane (prototype `.ph.duo .tabs`). Keep each tab's navigation state. Other tabs' content must stay clear of it.
  - **Android tab bar:** keep the existing navigation rail on wide windows (it never crosses the fold), restyled monochrome.
  - **Sheets:**
    - iOS: the balance sheet and Home's own sheets open inside the trailing pane on wide windows.
    - Android: bottom sheets are bounded to the trailing pane.
- **Android tabletop** (horizontal fold):
  - Top half: balance, fiat, status line and nudge.
  - Bottom half: the card or the list, plus the navigation bar.
- **Android hinge:** like onboarding, Home must get the full window and split at the reported fold bounds, not the single region from `avoidHinge()`. Other tabs keep their current hinge handling until their redesign.
- **iOS hinge:** lay out from the window size alone, split at the centre.
- **Changing pose** keeps scroll position, the open sheet, hide state and any running first-payment animation. It never repeats an action.

## Platform

- **iOS 26+ Liquid Glass:**
  - where: the name chip, scan and settings buttons, the nudge card, Copy, the balance sheet and the wide tab bar;
  - how: use `GlassEffectContainer` around grouped controls;
  - fallback: through `GlassCompat` (materials on iOS 18–25, opaque under Reduce Transparency).
- **Android:** the same layout with flat tonal surfaces (the prototype's `--soft`), Roboto for text and Space Grotesk for amounts.
- **Motion:** the prototype's springs and timings: entrance rise 40 ms apart, rows 40 ms apart, nudge fold and slide, check ring, card fold, digit roll, hide morph and pending ring. Reduce Motion makes every one a 180 ms fade; the status pulse and pending ring stop.

## Existing and migrated wallets

The redesign only changes how Home looks and which existing actions it links to. No wallet, payment, backup, notification or storage behaviour changes.

- **Launch:** keychain seed + wallet DB still opens Home, after today's gates (notifications required, Android battery prompt). Missing, cleared or damaged settings never block Home.
- **Storage:** no new persisted keys. Hide-balance, unit, currency, backup flag, email-prompt dismissal and Home notices keep their current keys. The "new" dot is in memory only.
- **Existing Home features stay reachable:**
  - upgrade notices, the update warning and the wallet error;
  - backup progress, failure and retry;
  - pending rounds and their sheet, automatic-boarding completion and the background-task status (the activity glyph);
  - the email prompt and the backup prompt;
  - the suspended state, the BTC Map (from Settings), the QR hub and the scanner;
  - transaction detail with Pay again, and pull-to-sync.
- **Tests:**
  - an RN-migrated fixture with history opens Everyday with the backup nudge;
  - a restored wallet opens without it;
  - a new empty wallet opens Fresh;
  - a suspended wallet shows only the suspended state;
  - keep today's Home rule, banner-expiry, round-observer and parity tests green, updating only assertions about removed UI.

## Copy

Add every new string to `apps/i18n/en/native.json`, hand-translate it into es, ja, ko and sv, and regenerate with `bun apps/tools/translations.mjs`. Never run the paid `translate` command.

New strings:
- "Renew {amount}", "Expires in {n} hours" (plural), "Expires in under an hour", "Renew";
- "Save your recovery phrase", "Back up", "Backed up";
- "Add your email", "For urgent wallet alerts", "Add", "Email added";
- "Update available", "Get the latest fixes", "Update";
- "{amount} arriving";
- "Spend bitcoin nearby";
- "Scan with any Lightning wallet";
- "Recent", "See all", "Activity", "Today", "Yesterday", "Balance", "from {name}";
- "Noah user", "Sending", "Confirming", "· nothing left your wallet".

Reuse existing strings where they exist.

## Testing

Test every state in light and dark, hidden and shown, on all of these:
- an iPhone simulator (iPhone 17, iOS 27.0);
- an iPhone Duo simulator (iOS 27.1), closed, half-open and open;
- an Android phone emulator;
- an Android foldable emulator (Pixel Fold), closed, half-open and open.

States to cover:
- Fresh, Everyday, Busy (pending, failed and renew) and Suspended, from fixtures;
- a live first payment into a fresh regtest wallet on each platform, with Home on screen.

Decode the rendered get-paid QR in a test (Vision or CoreImage on iOS, ZXing on Android), in both themes and with the logo.

Record one phone video per platform covering Fresh → first payment → Everyday → scroll → hide → balance sheet, and one foldable video per platform showing pose changes.
