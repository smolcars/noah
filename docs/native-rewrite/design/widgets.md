# Widgets redesign: build spec

Approved by the user on 2026-10-27 (v2, "the balance wave"). The interactive prototype is [`widgets-v2.html`](widgets-v2.html), with its assets in [`widgets-assets/`](widgets-assets/). Open it in a browser from this folder; the chips switch state, look, language, unit and fiat. Where this spec and the prototype differ, the spec wins.

The principles, tokens and copy rules are the ones in [`onboarding.md`](onboarding.md#principles) and [`home.md`](home.md). The widgets follow Home: the same balance, unit, language, hide setting, status line and Renew rule.

## User decisions (2026-10-27)

1. The wave shows **30 days**.
2. The medium widget (Android 4×2) has the **Actions** row: Receive · Scan · Send. There is no Recent layout.
3. The **fiat line** stays, under the balance.
4. **Lock Screen widgets** are added (iOS only): rectangular, circular and inline.
5. Android uses the **wallpaper colour** (Material You dynamic colour) on Android 12+, and the monochrome tokens below that.

## What every widget shows

From the top:

- **Change label** (small and medium): the signed 30-day change, then "30 days", for example **+₿ 30,142** · 30 days. Use today's `BitcoinAmount` with the app's unit and language, a "+" or "−" sign, and the existing "{n} days" plural. The amount is foreground Space Grotesk 600; the rest is muted. Hidden when the balance is hidden or the wave is calm. On signet and regtest, Home's network chip ("Signet", "Regtest") sits beside it. When space runs out the label drops " · 30 days" (then shrinks its text a little); the amount itself is never truncated.
- **The wave:** your balance over the last 30 days, drawn as the logo's wave, with the longship riding at today. See [The wave](#the-wave).
- **Balance:** Space Grotesk 600 with tabular digits, today's `BitcoinAmount`, the app's unit (BIP-177 ₿ or sats) and the app's language for digit grouping. All digits, shrinking to fit down to half size. No "K"/"M" abbreviations any more. In sats, "sats" is set at about half size after the number.
- **Fiat:** muted, under the balance. Home's latest quote in the user's currency, formatted for the app's language. No quote, no line.
- **Status line:** at most one, under the fiat:
  1. **Renew:** the renew-history glyph, **Renew** (semibold) and, muted, "Expires in {n} hours" (or "Expires in under an hour"). It shows only when Home would show its Renew card: refreshable VTXOs reported by `getExpiringVtxos(vtxoRefreshExpiryThreshold)`, the same rule as `HomeRules`. It never shows earlier. On the small widget it takes two lines (Renew, then the expiry); on the medium widget, one ("Renew · Expires in 18 hours").
  2. **Arriving:** a muted dot and "+{amount} arriving", with Home's arriving amount (`HomeRules` on both platforms): incoming money only, i.e. claimable Lightning receives plus incoming unconfirmed on-chain. Outgoing pending sends and boards never show here.

  Renew wins when both apply. There is no green "all fine" state, no block count and no colour coding any more.
- **Actions** (medium and Android 4×2 only): three equal soft-surface capsules, Receive · Scan · Send, with the Home tab bar's ↙ and ↗ glyphs and the scan glyph.

Gone: the "Noah Wallet" header (the system label under the widget says Noah), the Onchain/Offchain split (it stays in the app's balance sheet), the coloured expiry rows and the "REGTEST" yellow badge.

### States

| State | When | Shows |
| --- | --- | --- |
| Everyday | anything else | label, wave, balance, fiat |
| Arriving | pending balance > 0 | + the arriving line |
| Renew | Home's Renew rule | + the Renew line (wins over arriving) |
| New wallet | balance 0 and every daily balance equal | calm water, ₿ 0, fiat, no label |
| Hidden | the app's hide-balance setting is on | calm water, "₿ ••••••", fiat "••••", Home's hidden "•••• arriving", no label. The wave would reveal your history, so it never draws while hidden. |
| Test network | signet, regtest | Home's chip beside the label |
| Large | any balance | the balance shrinks to fit, to half size at most |
| No wallet | no snapshot at all (never set up, or deleted) | just the longship mark, centred, on calm water. Never ₿ 0. |

## The wave

- **Data:** 31 daily balances in sats, oldest first. `value[30]` is the current balance (the same number the widget shows). For `i < 30`, `value[i]` = current balance − the sum of every balance change whose **local date** (device time zone) is after day `i`, where day `i` is today − (30 − i). Changes older than the window cancel out; a change dated in the future (clock skew) counts as today. A change is anything the displayed balance already reflects: settled movements, and also pending items whose amounts bark's total already includes: unconfirmed on-chain transactions, a Lightning receive once it's claimable, a board once it's confirming (`pending_board`), an offboard once its transaction is broadcast. Lightning sends, arkoor sends, rounds and exits keep their sats in the total until they settle, so they count only when successful. Pending items count as today's change (an unconfirmed transaction keeps the date it was first seen), so earlier days never shift while a payment is in flight or when it confirms. Failed and canceled movements aren't changes. A movement is dated by its creation time (the date Activity shows). A confirmed on-chain transaction is dated by the board or offboard movement that names it and counts once that movement settles; otherwise by tip − height × 10 minutes. The writer reads the balance before and after the history and pending reads and collects again if anything settled in between; if it's still inconsistent it keeps the earlier days and updates only today. Known limit: bark-bolt-ffi has no per-offboard broadcast state, so two pending offboards started on different days can rewrite yesterday until one settles. If the app last wrote on an earlier day, the widget shifts the 31 days forward to today and fills the missing days with the last balance. The shared fixture [`apps/fixtures/widgets.json`](../../../apps/fixtures/widgets.json) has the reference values, including midnight, a non-UTC time zone, a daylight-saving change, a young wallet and clock skew. Both platforms must pass it.
- **Change label:** `value[30] − value[0]`, exact (not smoothed).
- **Calm water:** when every value is equal (a new wallet, or no activity for 30 days) or the balance is hidden. A flat line at about 72% of the band's height with a gentle ripple (amplitude 1.4 pt, `sin(x / 7)`), and no label.
- **Smoothing:** two passes of `(prev + 2·v + next) / 4` with clamped ends (fixture `swell`), then a monotone cubic through the points (Fritsch–Carlson, so it never overshoots). It reads as swell, not a stock ticker.
- **Scale:** y from the smoothed minimum (bottom of the band, 2 pt inset) to the maximum (top of the band, below the boat's headroom). x from the left edge to just before the boat; the line then runs flat at today's level under the boat to the right edge.
- **Stroke and sea:** a 2 pt line in the wave colour, and a fill from the line to the widget's bottom edge, fading from 28% to 0%. Text sits on top of the faded sea.
- **Boat:** the longship mask (ship only, no wave) in the foreground colour, 24 pt on the small widget and 26 pt on medium, sailing on top of the line at today: the lowest point of the hull rests on the line's upper edge (about 1 pt above the line's centre), so the line runs under the boat, never through it. The band's top headroom grows to match so a boat at the highest point stays inside. Its right edge sits 14–16 pt in from the widget's right edge.
- **Where:**
  - **Small:** full-bleed, edge to edge, from just under the label row (y ≈ 28 pt) down to 4 pt above the balance. The band height follows the text below it: tall in Everyday, shallow with a two-line Renew status.
  - **Medium:** the trailing ~52% of the width, from under the label (y ≈ 32 pt) to 8 pt above the Actions row, fading in from the left (transparent → opaque over its first third). The balance column keeps the leading side.
- **Colours:** the wave is Bitcoin orange `#F7931A` (it's "you", and it's never an amount). In iOS accented and vibrant rendering (Tinted, Clear, Lock Screen) it takes the system tint or white like everything else. The ₿ and every amount stay monochrome.
- **Accessibility:** the wave and boat are decorative; the label text carries the meaning. VoiceOver and TalkBack read one combined label per widget: balance, fiat, the change label, then the status line.

## Sizes

### iOS (WidgetKit, `apps/ios/NoahWidget`)

Keep the single `Widget` and its per-variant `kind` (`NoahWidgetMainnet` / `Signet` / `Regtest`) and app group, so widgets already placed (including RN-placed ones) keep working. Add families to it rather than adding a new kind.

- **systemSmall:** label row, wave, balance, fiat, status. Tap opens Home.
- **systemMedium:** balance column (balance 34 pt, fiat, status) on the leading side, label at the top trailing, wave in the trailing half, Actions row along the bottom. Each capsule is a `Link`; anywhere else opens Home.
- **accessoryRectangular (Lock Screen):** balance in Space Grotesk, then the status line, or fiat when there's none. Tap opens Home.
- **accessoryCircular (Lock Screen):** the scan glyph on `AccessoryWidgetBackground`. Tap opens Scan.
- **accessoryInline (Lock Screen):** the longship glyph and the balance.

Platform details:
- `containerBackground` is the background token (white / true black); use `.contentMarginsDisabled()` and pad 16 pt yourself so the wave can bleed.
- Rendering modes: `.fullColor` as designed; `.accented` (Tinted, and iOS 26 Clear) puts the balance and the wave in the accent group with `.widgetAccentable()`, everything else in the default group; `.vibrant` (Lock Screen) is monochrome white.
- Balances, fiat, the change label, the status amount and the wave are `.privacySensitive()`, so a locked Lock Screen or StandBy redacts them.
- Bundle Space Grotesk (Medium, SemiBold) in the widget extension and list it in the extension's `UIAppFonts` (XcodeGen `project.yml`).
- Timeline: one entry now, then hourly entries while a Renew is due so "Expires in {n} hours" counts down without the app; after the due time, drop the Renew line. Otherwise keep today's 15-minute policy.
- StandBy and iPad-class home screens (the Duo open) get the same families; check they render.

### Android (`NoahWidgetProvider`, RemoteViews)

Keep the receiver classes `com.noahwallet.widgets.NoahWidget{Mainnet,Signet,Regtest}Provider` and the `@xml/noah_widget_*_info` names.

One resizable widget, four layouts chosen by size:

| Layout | Roughly | Content |
| --- | --- | --- |
| 2×1 | narrow, short | longship, balance, one line (status, or fiat) |
| 4×1 | wide, short | longship, balance + one line, then three 44 dp icon buttons (Receive, Scan, Send) |
| 2×2 | narrow, tall | the small layout |
| 4×2 and larger | wide, tall | the medium layout (wave width scales with the widget, ~52%) |

- **Choosing layouts:** on Android 12+ pass a `Map<SizeF, RemoteViews>`; on Android 7–11 pick from `OPTION_APPWIDGET_MIN/MAX_WIDTH/HEIGHT` in `onUpdate` and `onAppWidgetOptionsChanged`. Update the provider info: `targetCellWidth/Height`, `minResizeWidth/Height`, `maxResizeWidth/Height` where useful, and a scalable `previewLayout` (Android 12+) with sample data.
- **The wave:** RemoteViews can't draw paths, so the provider renders the wave (and the boat) to a `Bitmap` for each layout's slot size and the current night mode, on the IO dispatcher, and sets it with `setImageViewBitmap`. Same geometry and maths as iOS. Keep the total under the RemoteViews bitmap budget (scale to the slot's pixel size, nothing bigger).
- **Wallpaper colour (Android 12+):** colours from the system dynamic palette, light and night:
  - surface: `system_accent2_50` / `system_neutral1_900`;
  - buttons: `system_accent2_100` / `system_neutral2_800`;
  - text: `system_neutral1_900` / `system_neutral1_50`;
  - muted text: `system_neutral2_600` / `system_neutral2_300`.

  The wave and the boat's wave stay Bitcoin orange; the boat is the text colour. Below Android 12, use the monochrome tokens (white / true black, soft `#F0F0F2` / `#1B1B1D`, muted `#707070` / `#8F8F8F`).
- **Shape:** `@android:dimen/system_app_widget_background_radius` on Android 12+ (28 dp otherwise), clipped, with `@android:id/background` on the root for launch transitions.
- **Type:** Space Grotesk via the existing font resources for amounts (fall back to the default font where RemoteViews can't load it), Roboto for text. Balance shrink-to-fit with `autoSizeTextType`.
- **Buttons:** explicit `PendingIntent`s to `MainActivity` (distinct request codes, `FLAG_IMMUTABLE`) for Receive, Scan and Send; the rest of the widget opens Home.
- No Lock Screen widgets on Android.

## Opening the app from a widget

New in-app routes for Home, Receive, Scan and Send, on the existing `com.noahwallet.<variant>` scheme (iOS `widgetURL`/`Link`) and explicit intents (Android). They follow the [deep-link](../deep-links.md) gates:

- held in memory while the app is locked and applied after unlock;
- ignored with no wallet or behind a launch gate;
- applied once (rotation, fold, recreation and process restore never repeat them) and never persisted;
- they never discard or replace a payment in progress, a review or a recurring approval: Send then just opens the Send tab as it is, and Scan opens the scanner only when Send could take a new request (otherwise it opens the Send tab as it is);
- nothing pays from a widget.

## Data the app writes

The widget never opens the wallet. The app writes one snapshot; the widget only reads it. Today's writers stay (foreground summary refresh, push jobs, hourly background refresh, recurring), and each one writes the whole new snapshot. Deleting the wallet clears it, as today.

- **New snapshot**, versioned: iOS app-group `UserDefaults` key `widgetSnapshot` (JSON); Android the same prefs file, new keys. It holds:
  - balance, Home's arriving amount, the 31 daily balances (see [The wave](#the-wave));
  - `amountUnit` (`bip177` / `sats`) and the hide-balance flag (Home's persisted keys);
  - Home's latest price and currency code (absent if none); the widget applies Home's fiat formula to the balance, and writers without a fresh price carry the last one forward;
  - Renew: the soonest refreshable-VTXO expiry as a date (now + blocks × 10 min), present only when Home's Renew rule holds;
  - the network variant (for the chip) and the app language (already mirrored).
- **Fallback:** the old keys are read until the app's first write, then removed. A widget that finds only the old keys (an RN-written or earlier native snapshot, before the updated app first runs) still renders the new design from them: balance, pending, ₿ unit, no fiat, calm water, no Renew line. No snapshot at all is the No wallet state.
- **Republish** when the hide setting, the unit or the fiat currency changes (like today's language mirror), and after history changes.
- Same protection as today's balance: app-group / app-private storage only, never logged.

## Existing and migrated wallets

- Widgets placed on RN or earlier native builds stay placed and pick up the new design: same iOS kinds and app groups, same Android receivers, provider info names and placed widget IDs.
- The upgrade tests that check placed widgets (`apps/android/UpgradeTests`, iOS widget identity checks) stay green.
- No wallet, payment, backup or notification behaviour changes. No server changes.

## Copy

No new strings are expected. Every word is in the catalogs: "Renew", "Expires in {n} hours", "Expires in under an hour", "{amount} arriving", "{n} days", "Receive", "Scan", "Send", "Signet", "Regtest", the gallery name and descriptions. Remove the widget-only keys that are no longer used ("Noah Wallet", "Pending: {amount}", "Onchain", "Offchain", "VTXO expires: {n} blocks", "{n} blocks" if nothing else uses it) and regenerate with `bun apps/tools/translations.mjs`. If a new string turns out to be needed, add it to `apps/i18n/en/native.json`, hand-translate es, ja, ko and sv, and regenerate. Never run the paid `translate` command.

## Testing

- **Unit (both platforms):** `apps/fixtures/widgets.json` (daily balances, change, calm, smoothing); the movement → balance-change mapping against existing history fixtures; snapshot write/read round trip; the old-keys fallback; hidden → no wave data drawn and dots everywhere; the Renew rule matches Home's for the same VTXOs; widget routes (held while locked, ignored with no wallet, applied once, never discarding a payment in progress).
- **Devices**, light and dark (plus iOS Tinted and Clear, Android with two different wallpapers):
  - iOS: an iPhone simulator (iPhone 17, iOS 27.0) and an iPhone Duo simulator (iOS 27.1) closed and open: small, medium, the three Lock Screen families, StandBy;
  - Android: a phone emulator and a Pixel Fold emulator (closed and open), all four layouts, resized live, on Android 12+ and one Android 9–11 image for the fallback colours.
- **States:** every row of the States table, in en and sv (long words), ₿ and sats, fiat on and off.
- **Live** on the `noah-bark08` regtest stack (exec wrappers only): a payment into the wallet updates the widget, the wave and the change label after the app's next write; Receive, Scan and Send open the right screens from a killed app, a backgrounded app and a locked app.
- **Upgrade:** a widget placed before the update renders the new design, from the old keys, before the app is opened.
- **Evidence:** screenshots of each family and state, and 1–2 screen recordings per platform (adding the widget, a payment updating it, hide on/off, the buttons). Copy recordings to `/private/tmp/noah-dev/videos`. Never record a recovery phrase.
