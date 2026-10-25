# Activity redesign: build spec

Approved by the user on 2026-10-23 ("liked the whole thing"), with every open question answered as recommended (see [Decisions](#decisions)). The interactive prototype, `activity-v1.html`, was removed once built; `git log --diff-filter=D -- docs/native-rewrite/design/activity-v1.html` finds it. Where this spec and the prototype differ, the spec wins.

Useful URL presets:
- States: `#st=everyday|busy|fresh|empty|loading|error`
- Devices: `&dev=iphone|duo|android|fold&theme=light|dark`
- Detail: `&open=<rowId>` with `&details=1`. Useful rows:
  - `r13`: Lightning-address send;
  - `r03`: invoice;
  - `r09`: on-chain send from Ark;
  - `r17`: offboard;
  - `r11`: board;
  - `r12`: on-chain deposit;
  - `r08`: renewal;
  - `r06`: recurring;
  - `r26`: canceled;
  - `p1`–`p5` (busy): pending;
  - `f1`: failed;
  - `f2`: refunded.
- Sheets and list: `&sheet=csv|menu`, `&q=<search>`, `&f=ln|ark|chain`, `&hidden=1`, `&rm=1`, `&slow=3`, `&still=1`

The principles, tokens, springs, haptics and copy rules are those in [`onboarding.md`](onboarding.md#principles). Home's patterns carry over ([`home.md`](home.md): glass, toasts, the hide-amounts flag, the "new" dot, Reduce Motion), and so does Send's block row ([`send-receive.md`](send-receive.md#motion-language)).

## Scope

**In scope:** the Activity tab (today's Transactions/History screen): list, search, filter, refresh, CSV export, and the payment detail.

**Out of scope:**
- Settings, and the Settings VTXO and exit screens. Activity only links to them.
- Background payments ([`../async-payments.md`](../async-payments.md)).

**Not changed:**
- wallet sync and refresh semantics;
- the historical-price lookup;
- the CSV columns, escaping, file name and Android's one-day export cleanup;
- the Pay again (`canRepeat`) rule;
- per-network explorer links;
- copy semantics (iOS keeps its local-only, two-minute pasteboard expiry).

The mapping changes in [Data](#data) are the only logic changes.

## Decisions

The user decided these on 2026-10-23:
1. **Failed and refunded payments are shown,** as Home's spec draws them: "Failed · nothing left your wallet" or "Refunded · back in your wallet".
2. **The filter lives in the ⋯ menu** (All, Lightning, Ark, On-chain, as a checked list). While a filter is on, a chip next to the title names it; tapping the chip clears it.
3. **CSV exports what the filter shows,** as today, and the sheet says so.
4. **Lightning invoice and offer rows are titled** by the description stored in the invoice or offer, falling back to "Sent" or "Received".
5. **The detail has Share,** a plain-text receipt.
6. **Fee-paying renewals are shown** as Ark "Renewed" rows.
7. **There is no row of recent people** above the list. Send's To screen already has recents.

## List

- **Title:** "Activity", a large title that tucks into the bar on scroll. Top right: search, then ⋯ (Filter, Refresh, Export CSV).
- **Rows** use Home's Recent row component unchanged:
  - **Avatars:** initials for people and Lightning addresses. Icons for Lightning without a name, on-chain, recurring, renewals and plain Ark.
  - **Second line:** kind · time.
  - **Amounts:** monochrome and signed.
  - **Status word:** only when the payment isn't a plain success (Sending, Confirming · 1 of 2, In a round, Exiting, Failed, Refunded, Canceled).
  - **Pending:** a slowly turning ring on the avatar.
  - **Failed:** a red badge, with the amount struck through.

  Whatever Home shows for a record, Activity shows the same way, so share the mapping.
- **Grouping:**
  - A "Pending" group comes first and holds everything still moving: unconfirmed on-chain, waiting for a round, Lightning in flight, exits.
  - Then day groups (Today, Yesterday, then dates in the user's locale and time zone), with sticky headers.
  - Newest first within a group.
- **New dot:** Home's in-memory "new" set is shared with Activity. A row's dot shrinks away about 1.4 s after it's on screen, and the row then counts as seen on Home too.
- **Hide amounts:** follows Home's persisted flag.
- **Search:**
  - **Matches:** names, notes, kinds, amounts (with or without separators), txids, payment hashes and payment IDs, case-insensitively.
  - **No match:** "No matches".
  - **Opening:** search opens over the list with the keyboard. Clearing it or Cancel restores the list and its scroll position.
- **Refresh:** pull to refresh (platform style) and ⋯ → Refresh do today's refresh and sync.
- **States:**
  - **Loading:** skeleton rows that pulse; they stay still under Reduce Motion.
  - **Error:** "Couldn't load activity", today's message, and "Try again". A suspended wallet shows Android's existing message in the same layout, and iOS gets the same.
  - **Empty:** ghost rows showing the three avatar kinds, "No activity yet", and a Receive pill that opens the Receive tab.
  - **Filter with no rows:** e.g. "No Lightning payments" with "Show all".

## Detail

- **Presentation:**
  - **Phone:** a push with a shared-element transition. The row's avatar, amount and name move into the header, and the screen opens out of the row's rectangle. Back reverses it. The tab bar hides while the detail is open.
  - **Wide:** the trailing pane, with share and × at its top and "Select a payment" when nothing is selected.
- **Header:**
  - the 60 pt avatar, with the row's ring or badge;
  - the big signed amount (Space Grotesk; struck through when failed or canceled);
  - one status line: Sent, Received, Moved to Ark, Renewed N coins, Arriving, Sending · …, Exiting, Failed · nothing left your wallet, Refunded · back in your wallet, Canceled · …;
  - "$X at the time": the historical price for settled rows, the current price for today's and pending rows. The line is omitted when no price exists, and for failed and canceled rows. "Recorded" stays as the status line when the status is unknown.
- **Block row:** pending on-chain payments show Send's mempool-style block row (a dot waiting in "next", then "Confirmed in block N · 1 of 2"). The data comes from today's chain status only; no new polling.
- **Rows,** in order, each only when it applies:

| Row | Content |
| --- | --- |
| To / From | The recipient or sender, with copy. A Lightning address shows here. |
| Via | The rail glyph and name: Ark, Ark round, Lightning, Lightning offer, On-chain. |
| Fee | "Free" for zero on sends, "None" for failed, "Up to ₿X" while a Lightning send is pending. When both fees exist: "Ark fee" and "Network fee" rows. |
| Total | Amount plus fees, on sends only. |
| Date | The date; "Unconfirmed" before a block. |
| Confirmed | "Block N", on-chain only. |
| Entered as | The fiat amount, when entered in fiat (as today). |
| Note | The comment or description. |
| Proof of payment | The preimage, for successful Lightning sends only, with copy. |
| Transaction ID | Copy, plus the explorer icon. Android's "No browser available" toast stays. |
| Repeats | "Monthly ›" on recurring rows; opens the recurring schedule. |
| Progress | "Exit details ›" on exit rows; opens exit progress. |

- **Details** (collapsed; opens with the soft spring). Everything today's technical section shows:
  - payment ID;
  - payment hash: new, decoded from the stored invoice;
  - preimage, for receives;
  - invoice;
  - chain status;
  - balance change;
  - block height and block hash;
  - raw transaction;
  - movement type and ID;
  - subsystem;
  - intended and effective change;
  - chain anchor (hidden when it equals the txid, as in React Native);
  - metadata JSON;
  - "sent to" / "received on" lists (multi-destination keeps "destination · amount");
  - spent, new and exited coins. Each coin opens its coin details (Settings → VTXOs).
- **Actions:**
  - **Pay again:** a bottom pill, with the same `canRepeat` rule.
  - **Copy:** the icon turns into a check, with a "Copied" toast and a success haptic. Long-press text selection on values stays.
  - **Share:** a plain-text receipt: amount, date, To/From, rail, and the transaction ID (on-chain) or proof of payment (successful Lightning sends). Never Details, never the raw transaction, never a receive preimage. Don't log it.

## Export

⋯ → Export CSV opens a confirmation sheet. It shows:
- "Export activity";
- one sentence (e.g. "29 payments as a CSV file." or "12 Lightning payments as a CSV file.");
- the file name.

Export opens the system share sheet. While it works, the button reads "Exporting…". Today's export error shows as an inline line in the sheet.

**What's exported:** exactly the rows today's export includes, narrowed by the filter. The newly visible failed and refunded rows are **not** exported: no money moved.

## Data

These are the only logic changes. Make each one in the shared mapping (`HistoryMapping` on both platforms), so Home's Recent list gets them too.

1. **Failed and refunded movements are included.** `HistoryMapping.include` drops failed movements unless they're board or offboard; relax that. A refunded Lightning send is a failed send whose funds came back; label it from the movement, without guessing.
2. **Renewals are Ark "Renewed".** A fee-paying round refresh shows as an Ark "Renewed N coins" row with its fee. Today it's typed "Onchain". Free renewals stay hidden.
3. **Recurring rows** join the recurring journal by payment or movement ID to get the schedule's name and the repeat icon. When the join fails, the row is an ordinary send.
4. **Titles for invoices and offers** come from the description stored in the invoice or offer. Fall back to "Sent" or "Received", never empty.
5. **Payment hash** is decoded from the stored BOLT11 invoice.
6. **Senders on incoming rows** appear only when the record names them, as on Home.

Keep the existing rules unchanged:
- RN-migrated rows;
- older records without metadata;
- recurring records from React Native, which aren't imported.

## Wide and foldable

- **iPhone Duo open, iPad, landscape:**
  - The leading pane holds the list, with Home's glass tab bar.
  - The trailing pane holds the detail.
  - The selected row has a soft rounded highlight.
  - Sheets (the export sheet, the share sheet) open in the trailing pane.
- **Android book pose:** the list and Material rail go on one side of the fold, the detail on the other. Nothing crosses the hinge.
- **Android tabletop:** the list on top and the detail below, as on Home.
- **Pose changes** keep the selection, the search text, the filter, the scroll position (anchored to a row) and the Details expansion.

## Platform

- **iOS:**
  - **Glass:** Liquid Glass through `GlassCompat` for the bar buttons, the chip, the menu and the sheet.
  - **Push transition:** iOS 18+ zoom navigation transition from the row; a standard push on older versions.
  - **Search:** Noah's own field over the bar, with Cancel, not `.searchable`: in a tab on iOS 27 the system's minimized toolbar search reopened as soon as it was closed.
- **Android:**
  - Compose `SharedTransitionLayout` for the row → detail transition;
  - Material pull-to-refresh;
  - a predictive back gesture that previews the reverse transition.
- **Reduce Motion** (and Android's animator scale at 0): every move becomes a 180 ms fade. The ring, pulse, blink and skeleton pulse stop.

## Copy

New strings are hand-translated into es, ja, ko and sv in `apps/i18n/<locale>/native.json`, matching sibling wording. Then run `bun apps/tools/translations.mjs extract`, `bun apps/tools/translations.mjs` and `--check` (0 untranslated). Never run `translate`; it's paid.

## Testing

- **Unit tests for every [Data](#data) change:**
  - failed and refunded rows are included;
  - fee-paying vs free renewals;
  - the recurring join, including a miss;
  - invoice and offer descriptions, with the fallback;
  - payment-hash decoding;
  - RN-migrated and metadata-less records unchanged.
- **Unit tests for the list:**
  - Pending vs day grouping, across midnight and a time-zone change;
  - search matching;
  - the filter, with its empty state;
  - CSV rows: the filter applies and failed rows are excluded; the columns are unchanged against today's CSV fixtures;
  - the hide-amounts flag;
  - the new dot shared with Home.
- **Share text:** the fields per kind, and no preimage for receives.
- **Device tests and screenshots:** phone, Duo or iPad open, Android book and tabletop, light and dark. Detail transitions, the export sheet and pose changes.
- **Live check on the Bark 0.8 regtest stack (`noah-bark08`):**
  - a Lightning send that fails and one that refunds;
  - a pending on-chain send through 1 of 2 confirmations;
  - a renewal with a fee, if the stack allows it without disturbing it;
  - a recurring payment;
  - CSV export.
