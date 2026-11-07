# Siri and Gemini

How Noah works with voice assistants. Both apps expose the same actions with the same replies;
`apps/fixtures/assistant-replies.json` pins the English reply text both platforms build.

## What the platforms allow (checked 2026-11-06)

- **iOS 27.1 SDK** has no finance or wallet App Schema domain. Siri AI does not route loose wording
  to our App Shortcuts: on a real iPhone, near-literal variants matched ("Give me a bitcoin address
  from Noah", "Use Noah to request some sats"); differently built sentences fell to Siri's model,
  which answered "I can't … in the Noah app" ("How much bitcoin do I have in Noah", "I want to get
  paid with Noah", an amount inside the sentence). "Check my Noah balance" asked "Noah or Wallet?".
  So: wide phrase sets are the main lever, and Spotlight-indexed content is the only way into
  Siri's own answers ("How much bitcoin do I have" searched notes, files and other apps).
- App Shortcut phrases may only interpolate `AppEntity`/`AppEnum` parameters (an `Int` amount is a
  build error), so amounts are asked as a follow-up. An App Shortcuts provider holds at most 10
  shortcuts.
- **Android**: Google deprecated the finance App Actions built-in intents on 2024-02-26 and Gemini
  ignores `shortcuts.xml` capabilities. AppFunctions (`androidx.appfunctions`, Android 16+) is the
  Gemini path; Gemini access is a private preview, so functions are built and tested over
  `adb shell cmd app_function` until Google opens it. Launcher shortcuts work everywhere today.

## Actions

| Action | Siri (iOS) | Gemini (Android AppFunction) | Runs |
|---|---|---|---|
| Balance | App Shortcut | `getBalance` | in the background |
| Request a payment (amount, optional note) | App Shortcut, asks the amount | `createPaymentRequest` | in the background |
| New Ark address | App Shortcut | `createArkAddress` | in the background |
| My Lightning address | App Shortcut | `getLightningAddress` | in the background |
| Last payment | App Shortcut | `getRecentActivity` (newest first, limit) | in the background |
| Open Receive / Scan | App Shortcut (opens the app) | launcher shortcut | opens the app |
| Pay someone (recipient, optional amount) | App Shortcut with a recipient entity | `openSend` + launcher shortcuts for recents | opens Send filled in |

Rules:

- **Nothing sends money from a voice assistant.** Pay opens Send at the review with the recipient
  and amount filled in; the user still holds to send. Recurring approvals are untouched.
- Every action needs an open, unlocked device (`requiresAuthentication` on iOS; AppFunctions are
  only callable by holders of `EXECUTE_APP_FUNCTIONS`). Wallet work goes through the existing
  background wallet path (`withBackgroundWallet` / the Android background pass); never open a
  second wallet.
- Keep the existing iOS intent type names: Shortcuts the user built in the React Native app refer
  to them.
- Spoken replies always say sats, whatever display unit the user picked; the snippet or app UI may
  show the user's unit. Fiat is added only when the app already has a price and fiat is not hidden.
- Copy is English in code and goes through the translation tooling like any other string.

## Replies

The fixture's `input.kind` selects a template; amounts are whole sats formatted for `en` with
grouping. `fiat` is the already formatted fiat string or null.

- balance: `You have {sats} sats.` / with fiat: `You have {sats} sats, about {fiat}.`
  (1 → `1 sat`)
- request: `Here's your Lightning invoice for {sats} sats.`
- arkAddress: `Here's a new Ark address.`
- lightningAddress: `Your Lightning address is {address}.` / none:
  `You don't have a Lightning address yet. Open Noah to pick one.`
- lastPayment incoming: `You received {sats} sats {when}.`; outgoing:
  `You sent {sats} sats to {counterparty} {when}.` (no counterparty: `You sent {sats} sats {when}.`);
  none: `You don't have any payments yet.` `{when}` is the platform's relative date ("2 hours ago").
- pay: `Opening Send to {recipient}.`
- Errors say what to do next: `Noah couldn't reach the Ark server. Check your connection and try again.`

## Siri on iOS (Phase 1)

- **Phrases** (`NoahAppShortcuts.swift`): every shortcut lists the ways people ask, with the app's name in
  several places ("… in Noah", "Use Noah to …", "Ask Noah …"). Balance leads with phrases that avoid the word
  "balance" ("How many sats do I have in Noah") to dodge Apple Wallet. The React Native phrases all stay. Seven
  shortcuts; Pay makes eight.
- **Translations** (`Resources/AppShortcuts.xcstrings`): hand-translated into es, ja, ko and sv, outside
  `apps/tools/translations.mjs` (phrases are iOS-only, a different table, and must never go to General
  Translation). `AppShortcutPhraseTests` fails when a phrase lacks a locale, loses `${applicationName}`, or two
  phrases read the same.
- **No alternative app names.** "Noah Wallet" would invite the Wallet clash; script names for ja/ko ("ノア",
  "노아") wait for a test on a device set to those languages.
- **Open Receive and Scan** open the app (`openAppWhenRun`) and hand it the widgets' own URL, so `WidgetRoute`
  applies the same gates and app lock. `OpenURLIntent` refuses the app's custom scheme.
- **Fiat** comes from the quote Home last published for the widgets (never fetched by Siri), in the chosen
  currency; Hide amounts drops it.
- **Last payment** is the newest row in Activity's order that is a settled payment: renewals, moves to Ark or
  on-chain, exits and their fees, and pending, failed or canceled rows don't count. "Who" is the Lightning
  address an outgoing payment went to.
- **Settings → Siri** lists a few phrases, Siri's tip and the system's link to Noah's shortcuts.

## Siri index (iOS)

Noah adds its balance, activity and recipients to the on-device index (`IndexedEntity`) so Siri
and Spotlight can answer questions like "How much bitcoin do I have" or "When did I last pay Sam".

- On by default. Settings has a "Siri & Search" switch; turning it off deletes everything Noah
  indexed and stops indexing.
- It stays on while the wallet lock is on (user decision 2026-11-06): Siri on device may read it.
- The index is refreshed after each successful sync and payment, and cleared when the wallet is
  deleted.
