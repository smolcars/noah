# Settings v1: notes

The prototype, `settings-v1.html`, was removed once built; `git log --diff-filter=D -- docs/native-rewrite/design/settings-v1.html` finds it.

It is built on Activity v1's frame, tokens, glass, springs, rows, detail rows, sheets, ⋯ menu and wide panes. It also reuses onboarding's name field, availability dot, code boxes and segmented control, and Send's hold to confirm and block row.

## Presets

`#scr=<screen>&dev=iphone|duo|android|fold&theme=light|dark&state=<state>&still=1`

| `scr` | States (`state=`) |
| --- | --- |
| `home` | (the entry: Home's top bar; the gear zooms into Settings) |
| `settings` | `default`, `clean`, `attention`, `suspended` |
| `profile` | `verified`, `unverified` |
| `lnaddr` | `edit`, `taken`, `nsec` |
| `email` | `view`, `code`, `wrong`, `done` |
| `backup` | `on`, `running`, `failed`, `off`, `never`, `list` |
| `recovery` | `gated`, `shown`, `saved`, `nolock` |
| `locked` | (the lock cover) |
| `coins` (alias `vtxos`) | `default`, `renew`, `select`, `filter`, `empty` |
| `coin` (alias `vtxo`) | `spendable`, `expiring`, `locked` |
| `onchain` | `default`, `review`, `moving` |
| `recurring` | `list`, `empty` |
| `recdetail` | `active`, `approval`, `paused` |
| `recedit` | `new`, `edit`, `custom` |
| `exit` | `warn`, `progress` |
| `exitsel` | `all`, `pick`, `unfunded` |
| `exitprog` | `waiting`, `claim` |
| `notif` | `on`, `off` |
| `unifiedpush` | `registered`, `none` |
| `advanced` | `default`, `debug` |
| `arkinfo`, `logs`, `about` | (none) |
| `esplora` | `default`, `fail`, `testing` |
| `debug` | `default`, `result`, `confirm` |
| `feedback` | `empty`, `filled`, `sent` |
| `delete` | `empty`, `typed`, `deleting` |
| `unit`, `currency`, `language` | (sheets over Settings) |

You can add any of these:
- `sheet=renew|board|export|reset|suspend|…` opens a sheet;
- `hidden=1` hides amounts, `details=1` opens Details, and `scroll=<px>` scrolls the page;
- `net=signet` shows the network badge, and `gms=0` is an Android phone without Google Play (UnifiedPush);
- `rm=1` turns on Reduce Motion, and `slow=3` slows everything down;
- `still=1` freezes motion, hides the side panel and waits for fonts.

In the prototype, codes that start with 0 fail, the name "satoshi" is taken, and the recovery phrase is a fake sample ("sample phrase only never use this example test word copy nothing real"). All data is synthetic. The 12 coins add up to Home v2's Ark balance (₿142,742), with ₿10,000 on-chain, as in Activity; a console assertion checks it.

## Every current feature, and where it lives now

Sources:
- **iOS:** `apps/ios/Noah/Features/Settings`, `Recurring`, `Onchain` and `Push`, plus Home's gear and `Shell/RootView.swift`.
- **Android:** `ui/settings/*`, `recurring/*`, `onchain/*`, `ui/launch/PlatformScreens.kt` and `ui/home/*`.
- **React Native:** `client/src/screens/SettingsScreen.tsx` and every screen it opens.

✓ means the platform has it today. **Bold** marks a change. Nothing is dropped except where the last column says **Dropped**.

### Entry and root

| Today | iOS | And | RN | In the prototype |
| --- | :-: | :-: | :-: | --- |
| Gear on Home opens Settings | ✓ | ✓ | ✓ | Kept. It opens with a zoom out of the gear, and your initial moves from the chip into the header. |
| Name chip on Home | | ✓ | | Kept as `home.md` says: the chip opens My code. Settings' header repeats the same orange-ringed initial; Profile links back to My code. |
| Logo header opens the Noah story | ✓ | ✓ | ✓ | **Moved** to About Noah. The root starts with you (initial, name, address) instead. |
| Sections Account / Info / Wallet / Debug / Danger Zone | ✓ | ✓ | ✓ | **Regrouped:** you, Security, Bitcoin, Display, App, Help, then Delete wallet on its own. |
| Row subtitles (one sentence per row) | ✓ | ✓ | ✓ | **Changed:** rows are a title and a live value ("On", "Not saved", "12", "₿10,000"). Explanations move one level down, one line each. |
| Network badge off mainnet | | ✓ | | Kept, next to your name (`net=signet`). |
| Version; 5 taps turns on debug | ✓ | ✓ | ✓ | Kept under a small "Noah" wordmark at the bottom of the list, and in About. |
| "Made with ❤️ from Noah team" | ✓ | ✓ | ✓ | **Dropped:** decorative, and an emoji. |
| Telegram and GitHub links | ✓ | ✓ | ✓ | **Moved** to About (Telegram also in Feedback). |
| Wide: two-column form | ✓ | | ✓ | **Changed** to Activity's panes: the list leading, the chosen page trailing. |
| Deep links (Home Renew, backup and suspended nudges, Activity's coin, schedule and exit links, recurring notifications) | ✓ | ✓ | ✓ | Kept. Each lands on its page with Settings beneath it. |

### Profile

| Today | iOS | And | RN | In the prototype |
| --- | :-: | :-: | :-: | --- |
| Name (max 80), Save, "Saved" | ✓ | ✓ | ✓ | Profile → Name opens a small sheet. |
| Lightning address row with copy; Edit | ✓ | ✓ | ✓ | Profile → Lightning address. **Restyled** with onboarding's live name field and availability ("Checking", "Available", "Taken" with suggestions). |
| Validation messages; reserved-name "Use {name}" chip | ✓ | ✓ | ✓ | Messages kept, in the availability line. The reserved-name chip stays as today (not drawn). |
| Nostr key (npub, optional) and the nsec warning | ✓ | ✓ | ✓ | Kept, on the same screen. Pasting an nsec shows "Never paste a private key. Use your npub." |
| "Skip" on Edit Lightning address (onboarding leak) | ✓ | ✓ | | **Dropped** in Settings. Onboarding keeps it. |
| Emergency email, verified or not; Change | ✓ | ✓ | ✓ | Profile → Email. **Restyled** with onboarding's flow: email, six boxes with autofill, resend countdown, wrong-code shake, verified ring. "Already verified" skips the code. |
| "The verification code will expire in 10 minutes." | ✓ | ✓ | ✓ | **Dropped** as on-screen copy, matching onboarding. The expiry itself is unchanged. |
| Wallet public key (copy) | ✓ | ✓ | ✓ | Kept. |
| Duplicate titles (bar plus in-page heading) | | ✓ | | **Fixed:** one large title. |

### Security

| Today | iOS | And | RN | In the prototype |
| --- | :-: | :-: | :-: | --- |
| Biometric authentication toggle (auth before change) | ✓ | ✓ | ✓ | Root → Face ID (iOS) / App lock (Android). The switch flips only after Face ID or the biometric prompt. Passcode or screen-lock fallback as today. |
| Lock cover "Noah is Locked" / "Unlock" | ✓ | ✓ | | Kept, restyled (`scr=locked`). |
| Android: screenshots blocked while the lock is on | | ✓ | | Kept (no visual). |
| Show seed phrase | ✓ | ✓ | ✓ | Root → Recovery phrase, with "Not saved" and an orange dot until confirmed. |
| Authentication before the phrase | ✓ | ✓ | only with biometrics on | Kept and **always required** (RN skipped it when biometrics were off). The words aren't in the screen until then: the grid shows bars and a Show button. |
| "No screen lock" warning | ✓ | ✓ | | Kept as a sheet: Cancel / Show anyway. |
| Copy phrase, cleared after 2 minutes | ✓ | ✓ | ✓ | Kept: "Copied · clears in 2 minutes". |
| "I've written it down" | ✓ | ✓ | | Kept while the phrase isn't confirmed. It clears Home's nudge, as today. |
| Words cleared when backgrounded; secret shielding | ✓ | ✓ | | Kept (no visual). |

### Backup

| Today | iOS | And | RN | In the prototype |
| --- | :-: | :-: | :-: | --- |
| Automatic backups toggle | ✓ | ✓ | ✓ | Kept. |
| Last backup and status (up to date, backing up, completed, failed, pending) | ✓ | ✓ | ✓ | A status mark at the top: orange check ring, spinner, red "!" or the cloud. |
| Back up now (also with automatic off) | ✓ | ✓ | ✓ | Kept; becomes "Try again" after a failure. The check draws on success. |
| List backups, with date and size | ✓ | ✓ | ✓ | "Backups 3" expands in place. |
| Delete a backup | ✓ confirm | ✓ confirm | no confirm | Kept with the confirmation on every platform. |
| Export database (.noahbackup, share sheet, "Export Complete") | ✓ | ✓ | ✓ | **Moved** from Danger Zone to Backup → Export file, with a sheet that names the file. |
| "Backup & Restore" without a restore action | ✓ | ✓ | ✓ | Renamed "Backup". One line says how to restore (enter the phrase on any phone). See open question 4. |

### Bitcoin: coins, on-chain, recurring, exit

| Today | iOS | And | RN | In the prototype |
| --- | :-: | :-: | :-: | --- |
| Show VTXOs | ✓ | ✓ | ✓ | **Renamed** Coins (Send's coin language). "VTXO" moves into the coin's Details as "Coin ID". |
| Total and count | ✓ | ✓ | ✓ | A big total, "In 12 coins", and a **new expiry line** that places every coin by when it expires (orange rings mark the ones within a day). |
| Filter All / Active / Expiring / Expired / Locked | ✓ | ✓ | ✓ | **Moved** to ⋯ (as Activity), with a chip next to the title while it's on. Rows group by Expiring, Spendable and Locked. |
| Select mode: select visible / expiring, clear, Refresh N | ✓ | ✓ | ✓ | ⋯ → Select coins (expiring preselected). Locked coins can't be picked. "Renew N" at the bottom. |
| Refresh review: amount, fee, after fee | ✓ | ✓ | ✓ | **Renamed** Renew (Home's word). Same rows in a sheet, explicit confirmation. A "Renew ₿X" button appears only when coins expire within a day. |
| VTXO detail (all fields, explorer, Refresh) | ✓ | ✓ | ✓ | Coin page: amount, status, a lifetime bar, State, Expires, Blocks left, Coin ID, Anchor (with explorer). Everything else under Details. Renew at the bottom. |
| Missing-VTXO message | ✓ | ✓ | | Kept as today's text (not drawn). |
| Board to Ark: balance, minimum, amount, MAX, review, receipt | ✓ | ✓ | ✓ | Root → On-chain. "Move to Ark" opens a sheet with the amount, Max and today's fee rows. While it confirms, Send's block row. |
| Auto-board toggle and its threshold text | ✓ | ✓ | ✓ | On-chain → Move automatically, one line ("Asks when you have ₿20,000 or more"). Home's auto-board prompt is unchanged. |
| Recurring payments list, statuses, amounts, next, last attempt, errors | ✓ | ✓ | ✓ (hidden on UnifiedPush) | Activity's rows: name, "Monthly · next Nov 20" or a status word, amount. "Needs approval" carries the red badge. Shown on every device, as native Android does today. |
| Card actions: review and pay, check status, resume after checking (two steps), edit, pause / resume / approve, cancel / remove | ✓ | ✓ | partly | **Moved** into a schedule page (also what Activity's "Repeats ›" opens). Pause or Resume or "Review and pay" at the bottom; the rest as rows. Both resume steps stay; the prototype draws the second ("You could pay twice"). |
| Run due payments now; sync note | ✓ | ✓ | ✓ | Run due payments kept; the sync note stays as today's text (not drawn). |
| Editor: name, recipient and type, amount, note (Lightning address), repeat weekly / monthly / custom with interval, first payment, ends never / date / after N, preview of 3 dates, limits and errors | ✓ | ✓ | create only | Kept, one screen: a big amount, fields, segmented controls with steppers, three date chips, one line of fine print. Unsupported recipients show today's errors inline. |
| Emergency exit warning ("Emergency use only") | | | ✓ | Kept on every platform as the screen's line, plus three facts (takes a day or more, costs on-chain fees, can't be undone). |
| Start: all eligible or selected, dust limit, fee estimate and warnings, unfunded → deposit, start tracking anyway, continue without estimate | ✓ | ✓ | ✓ | "Choose coins": All / Choose, the fee rows, today's warnings, "Add on-chain funds" sheet. **Changed:** Send's hold to confirm replaces the confirm dialog (open question 5). |
| Summary counts, block status, stale-exit warning | ✓ | ✓ | ✓ | In-progress exits list on the exit page; the stale warning stays as today's text (not drawn). |
| Progress exits (may broadcast or fee-bump) | ✓ | ✓ | ✓ | "Check the chain" with its confirmation sheet. |
| Exit detail and timeline; cancel exit while allowed | ✓ | ✓ | ✓ | Exit page: phase rail (Started, Sent, Waiting, Ready, Claimed), the claimable block, transactions, Details with the timeline. Cancel exit appears only while today's rules allow it (not in the drawn state). |
| Claim to an address, own-wallet default, external warning | ✓ | ✓ | ✓ | "To: Your Noah wallet" with an address sheet; hold to claim. |
| Operation receipts and override repeat protection | ✓ | ✓ | | Kept as today (not drawn). |

### Display

| Today | iOS | And | RN | In the prototype |
| --- | :-: | :-: | :-: | --- |
| Language (en, es, ja, ko, sv) | ✓ | ✓ | ✓ | A sheet with native names. |
| Currency (12) | ✓ | ✓ | ✓ | A sheet with names and codes. |
| Bitcoin unit (BIP-177 / sats) | ✓ | ✓ | ✓ | A sheet with two cards showing your balance both ways. |
| Hide balance (long-press on Home only) | ✓ | ✓ | | **New row:** Hide amounts, the same persisted flag. Home's long-press stays. |

### App, advanced and support

| Today | iOS | And | RN | In the prototype |
| --- | :-: | :-: | :-: | --- |
| Mailbox notifications toggle | ✓ | ✓ | ✓ | **Moved** to Notifications → "Claim payments in the background". |
| Push permission (no Settings row; launch gate) | ✓ | ✓ | ✓ | **New** Notifications page shows On or Off, with Open Settings. The launch gate is unchanged. |
| Battery optimization screen | | ✓ | | Notifications → Battery, with the status as the value; opens system settings. See open question 6. |
| UnifiedPush (no Google Play): distributors, endpoint, register | | ✓ | ✓ | Notifications → UnifiedPush, only without Google Play. Radio list, endpoint with copy, Register. |
| Ark info (keys, limits, rounds, Lightning, endpoints, explorer APIs) | ✓ | ✓ | ✓ | Advanced → Ark server. Every row, with plainer labels (e.g. "Smallest move", "Coin lifetime"). Bitcoind RPC stays on regtest. |
| Esplora: active, test and save, reset, regtest disabled, warning | ✓ | ✓ | ✓ | Advanced → Esplora. Same behaviour; the warning is one line. |
| Logs: share, refresh, empty and error | ✓ | ✓ | ✓ | Advanced → Logs. |
| Reset server registration | ✓ | ✓ | ✓ | Advanced → Reset registration, with today's confirmation. |
| Suspend wallet (no confirmation) | ✓ | ✓ | ✓ | Advanced → Suspend wallet. **Adds** a confirmation sheet (open question 1). |
| Debug screen and its typed "delete" gates | ✓ | ✓ | ✓ | Advanced → Debug once unlocked. Destructive actions still need "delete". |
| Send feedback: fields, screenshot (Android crop), limits, success, Telegram | ✓ | ✓ | ✓ | Help → Feedback. |
| Noah's Ark story audio | ✓ | ✓ | ✓ | Help → About Noah: the wordmark, version and a small player. |
| Legal, terms, privacy | | | | None today; none added (open question 3). |

### Delete wallet

| Today | iOS | And | RN | In the prototype |
| --- | :-: | :-: | :-: | --- |
| Type "delete" (trimmed, any case), then Delete | ✓ | ✓ | ✓ | Kept, in a sheet. The button turns red only when the word matches. |
| Can't dismiss while deleting; errors; back to onboarding | ✓ | ✓ | ✓ | Kept ("Deleting", locked sheet). |
| What you'd need to restore | | | | **New** in the sheet: the balance on this phone, whether the phrase is saved (with a link to it) and the last backup. |

## Design choices

1. **Settings is you first.** The root opens with the same orange-ringed initial as Home's name chip; the gear's zoom moves it there. Orange stays "you": your initial, and a small dot on rows that need you (an unsaved phrase, coins to renew, a payment to approve). Red is only for failures and Delete wallet.
2. **A row is a title and its state.** Today's rows carry a sentence each; here each row shows a live value instead, and the one-line explanation lives on the page it opens. The root reads like a status board.
3. **Coins, not VTXOs.** Send already teaches Ark money as coins. The expiry line makes the one fact that matters visible at a glance: when they need renewing. Renewal stays an explicit review, never one tap.
4. **Safety gates got stronger, never weaker.** Authentication before the phrase on every platform, the "No screen lock" warning, typed "delete" with a restore checklist, Send's hold for exits and claims, and confirmation for Suspend.
5. **Activity's structure, everywhere.** The same detail hero, key-value rows, collapsed Details, ⋯ menu, sheets and wide panes, so Settings feels like the same app. Sub-pages use one large title and at most one line.
6. **Native per platform.** iOS: Liquid Glass buttons and sheets, the slide with parallax, the Face ID HUD, iOS switches. Android: Material top bar, shared-axis transitions, the biometric prompt, Material 3 switches and radios, the navigation rail on the fold.
7. **Motion is the four springs.** Push and back, switches, chips and sheets are snappy. The phrase reveal (words un-blur in sequence), the backup check and Details are soft. Checks and counters are bouncy. Reduce Motion makes every move a 180 ms fade.

## Open questions

1. **Suspend wallet:** add a confirmation sheet (recommended; one stray tap closes the wallet), or keep today's instant switch?
2. **"Coins" on primary screens:** call them Coins and keep "VTXO" only in Details (recommended), or keep "VTXOs" for existing users?
3. **Legal links:** add Terms and Privacy to About if they exist on noahwallet.io (recommended), or keep none, as today?
4. **Restore in Settings:** keep one line on how to restore (recommended; restoring needs a fresh install), or add a "Restore from backup" action?
5. **Exit and claim confirmation:** Send's hold to confirm (recommended; it matches sending money), or today's confirm dialog?
6. **Android battery:** show the status as the row's value and open system settings (recommended), or keep today's explanation screen?
7. **Debug mode:** add "Turn off debug" in Debug (recommended; Android can't turn it off today), or keep it one-way?
8. **Hide amounts row:** add it to Display (recommended; long-press isn't discoverable), or keep Home's long-press only?

## Not drawn, but unchanged

These keep today's behaviour and wording, restyled to match:
- on-chain operation receipts and "override repeat protection";
- the stale-exit warning and "Continue without estimate";
- the recurring sync note and the first resume step ("Have you checked?");
- the missing-coin message;
- Android's feedback screenshot crop dialog;
- Esplora's launch fallback ("Use default server").
