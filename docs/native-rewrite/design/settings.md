# Settings redesign: build spec

Approved by the user on 2026-10-23, with the decisions below. The interactive prototype, `settings-v1.html`, was removed once built; `git log --diff-filter=D -- docs/native-rewrite/design/settings-v1.html` finds it. The feature inventory, presets and design choices are in [`settings-v1-notes.md`](settings-v1-notes.md). Where this spec, the notes and the prototype differ, this spec wins, then the notes.

Useful URL presets: `#scr=<screen>&dev=iphone|duo|android|fold&theme=light|dark&state=<state>&still=1`. The full list of screens and states is in the notes. Examples:
- `scr=settings&state=attention`;
- `scr=recovery&state=gated`;
- `scr=backup&state=failed`;
- `scr=vtxos&state=select`;
- `scr=recedit&state=custom`;
- `scr=delete&state=typed`;
- `scr=unifiedpush&gms=0`.

The principles, tokens, springs, haptics and copy rules are those in [`onboarding.md`](onboarding.md#principles). Activity's patterns carry over ([`activity.md`](activity.md)): detail rows, collapsed Details, the ⋯ menu with a filter chip, sheets and wide panes. So do Home's glass, toasts and hide-amounts flag ([`home.md`](home.md)).

## Scope

**In scope:** Settings and every page it opens:
- profile, Lightning address, email;
- security, recovery phrase;
- backup;
- VTXOs, on-chain/boarding;
- recurring payments (list, schedule page, editor);
- display;
- notifications, UnifiedPush, Android battery;
- advanced (Ark server, Esplora, logs, reset registration, suspend, debug);
- feedback, About;
- delete wallet.

**Out of scope:** the emergency exit (start, choose VTXOs, progress, claim). It gets its own design later (user, 2026-10-23). Settings → Emergency exit opens **today's** exit screens unchanged. Don't restyle or rewire them, and don't add Send's hold to them yet.

**Not changed:** the behaviour behind every page. The redesign is UI on top of today's models and services (settings storage, wallet lock, backup, VTXO refresh, boarding/auto-board, the recurring journal and editor rules, push and UnifiedPush registration, Esplora, logs, feedback, delete). Every gate stays:
- authentication before the phrase;
- typed "delete" for the wallet and for destructive debug actions;
- today's confirmations (reset registration, delete a backup, VTXO renew review, recurring resume steps).

## Decisions

The user decided these on 2026-10-23:
1. **Suspend wallet** gets a confirmation sheet (Advanced → Suspend wallet). Today it switches instantly.
2. **They're called "VTXOs", not "Coins".** The Settings row, the list title, the detail ("VTXO"), Details ("VTXO ID") and Activity's links say VTXO. Keep the prototype's new expiry line and grouping. The action is **"Renew"**, matching Home's approved renew nudge; it's today's refresh.
3. **Privacy policy:** About gets a "Privacy policy" row that opens https://github.com/smolcars/noah/blob/master/PRIVACY.md in the browser. There are no terms, so no Terms row.
4. **Backup:** the page is called "Backup", with one line on how to restore ("To restore, enter your recovery phrase when you set up Noah on a phone"). There's no restore action in Settings; restoring stays in onboarding.
5. **The emergency exit** is out of scope (see Scope).
6. **Android battery:** Notifications → Battery shows the status as its value, and tapping it opens the system battery setting. The explanation screen goes.
7. **Debug mode can be turned off:** Advanced shows a "Debug mode" switch once debug is unlocked (version tapped 5 times). Turning it off hides the Debug screen again; tapping the version 5 times turns it back on. Same on both platforms.
8. **Hide amounts:** a switch under Display, the same persisted flag as Home's long-press, which stays.
9. **Copying the recovery phrase stays:**
   - "Copied · clears in 2 minutes".
   - **iOS:** the pasteboard item is local-only and expires after 2 minutes.
   - **Android:** the clip is marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`) and cleared after 2 minutes while the app is alive.
   - Keep today's implementation where it already does this.

## Root

- **Entry:** from Home's gear, with a zoom out of the gear. Your orange-ringed initial moves from Home's name chip into the header. The chip itself still opens My code (`home.md`). Settings isn't a tab.
- **Order:**
  1. you (initial, name, Lightning address, the network badge off mainnet);
  2. Security (Backup, Recovery phrase, Face ID / App lock);
  3. Bitcoin (VTXOs, On-chain, Recurring payments, Emergency exit, Spend bitcoin nearby). "Spend bitcoin nearby" opens today's BTC Map as Home did (iOS pushes it in Home's stack; Android opens it as its own screen in one pane); Back returns to Settings;
  4. Display (Bitcoin unit, Currency, Language, Hide amounts);
  5. App (Notifications, Advanced);
  6. Help (Feedback, About Noah);
  7. Delete wallet, on its own.
- **Rows** are a title plus a live value ("On", "Not saved", "12", "₿10,000", "2 active"). The one-line explanation lives on the page each row opens. A small orange dot marks rows that need you: an unsaved phrase, VTXOs to renew, a payment to approve.
- **Bottom:** the "Noah" wordmark and version; tapping the version 5 times unlocks debug.
- **Deep links** keep landing on their page with Settings beneath:
  - Home's Renew, backup and suspended nudges;
  - Activity's VTXO, schedule and exit links;
  - recurring notifications.

## Pages

Build each page as the prototype and the notes' "Every current feature" tables describe, with these rules:
- **Profile:**
  - Name opens a sheet.
  - Lightning address uses onboarding's live name field, with availability and suggestions, plus the Nostr npub and the nsec warning.
  - Email uses onboarding's code flow.
  - Wallet key with copy; My code.
  - No "Skip" in Settings.
- **Recovery phrase:**
  - **Before authentication,** the words aren't in the view: the grid shows bars and a Show button.
  - **Authentication is always required,** on both platforms.
  - **The "No screen lock" warning** stays as a sheet.
  - **The words** reveal softly, and clear when the app is backgrounded.
  - **"I wrote it down"** shows while the phrase isn't confirmed, and clears Home's nudge.
  - **Copy** per Decision 9.
  - Never log or snapshot the words.
- **Backup:**
  - **Status mark:** up to date, backing up, failed (with Try again), off, never.
  - **Controls:** automatic switch, Back up now, and "Backups N", which expands with date and size. Deleting a backup asks for confirmation.
  - **Export file:** moved here from Danger Zone; a sheet names the file, then the share sheet.
  - **The restore line** from Decision 4.
- **VTXOs:**
  - big total, "In N VTXOs", and the expiry line (orange rings within a day);
  - grouped Expiring / Spendable / Locked;
  - the filter in ⋯ with a chip;
  - ⋯ → Select VTXOs (expiring preselected, locked can't be picked), then "Renew N";
  - the Renew review sheet has today's rows and an explicit confirmation;
  - "Renew ₿X" shows only when VTXOs expire within a day;
  - the VTXO page has amount, status, lifetime bar, State, Expires, Blocks left, VTXO ID and Anchor with explorer, plus Details, with Renew at the bottom;
  - the missing-VTXO message as today.
- **On-chain:**
  - "Move to Ark" sheet: amount, Max, today's fee rows. While it confirms, Send's block row.
  - "Move automatically": auto-board, with one line. Home's auto-board prompt is unchanged.
  - Receipts as today.
- **Recurring payments:**
  - **List:** Activity-style rows (name, "Monthly · next Nov 20" or a status word, amount); "Needs approval" carries the red badge; Run due payments; shown on every device.
  - **Schedule page:** what Activity's "Repeats ›" opens on both platforms. Pause / Resume / "Review and pay" at the bottom, the rest as rows. Both resume steps stay.
  - **Editor:** one screen, with today's fields, rules, limits and errors.
- **Display:** language, currency and bitcoin unit as sheets; the Hide amounts switch.
- **Notifications:**
  - status (On or Off, with Open Settings);
  - "Claim payments in the background" (the mailbox toggle);
  - Battery (Android, Decision 6);
  - UnifiedPush, only without Google Play: distributor radio list, endpoint with copy, Register.
  - The launch push gate is unchanged.
- **Advanced:**
  - Ark server (every row today's Ark info shows, with plainer labels; bitcoind RPC only on regtest);
  - Esplora (test, save, reset, as today);
  - Logs (share, refresh);
  - Reset registration (today's confirmation);
  - Suspend wallet (Decision 1);
  - the Debug mode switch and Debug, once unlocked (Decision 7). Debug keeps today's actions and gates and bark's `debugInfo()`.
- **Feedback:** as today, restyled; Android's screenshot crop stays.
- **About Noah:** wordmark, version, the Noah's Ark story player, Telegram, GitHub, Privacy policy (Decision 3).
- **Delete wallet:**
  - **The sheet** shows the balance on this phone, whether the phrase is saved (with a link to it) and the last backup.
  - **Confirming:** type "delete" (trimmed, any case); the button turns red only when it matches.
  - **While it deletes,** the sheet locks and shows "Deleting". Errors and the return to onboarding work as today.

**Dropped:** "Made with ❤️ from Noah team", and the on-screen "code expires in 10 minutes" line (the expiry itself is unchanged).

## Wide and foldable

- **The iPhone Duo open, iPad and landscape:**
  - the Settings list sits in the leading pane and the chosen page in the trailing pane;
  - sheets open in the trailing pane;
  - Back in the list pane returns to Home.
- **Android book pose:** the list and the rail on one side of the fold, the page on the other, with nothing crossing the hinge. Tabletop: list on top, page below.
- **Pose changes** keep the open page, scroll, typed text, and the revealed or hidden state of the phrase. A pose change never re-shows the phrase without authentication.

## Platform

- **iOS:** Liquid Glass through `GlassCompat` (buttons, sheets), iOS switches, the Face ID prompt. Reuse Activity's detail rows and sheets.
- **Android:** a Material top bar, shared-axis transitions, the biometric prompt, Material 3 switches and radios, the rail on the fold, predictive back (already on app-wide).
- **Reduce Motion:** every move becomes a 180 ms fade.

## Copy

- New strings are hand-translated into es, ja, ko and sv in `apps/i18n/<locale>/native.json`, matching sibling wording.
- Then run `bun apps/tools/translations.mjs extract`, `bun apps/tools/translations.mjs` and `--check` (0 untranslated). Never run `translate`; it's paid.
- Reuse existing strings where the meaning is identical; give a string its own key where the context differs (e.g. "Refresh" vs "Renew").

## Testing

- **Unit tests:**
  - debug mode on/off and the 5-tap unlock;
  - the suspend confirmation;
  - the Hide amounts switch sharing Home's flag;
  - phrase copy marked local-only / sensitive with a 2-minute expiry;
  - the phrase never present before authentication, and cleared on background;
  - the VTXO grouping, filter and renew selection (locked excluded);
  - the delete checklist values;
  - the Android battery row's status;
  - every deep link landing on its page.
- **Device tests and screenshots:** phone, Duo or iPad open, Android book and tabletop, light and dark. Back and predictive back on every page; the phrase gate and the delete sheet; pose changes.
- **Live check on the Bark 0.8 regtest stack (`noah-bark08`):**
  - a VTXO renew from Settings;
  - Move to Ark;
  - a recurring payment approve / pay;
  - backup now and export;
  - delete wallet on a throwaway wallet, back to onboarding.
