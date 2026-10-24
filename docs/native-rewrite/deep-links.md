# OS deep links: build spec

Started 2026-10-24 (TODO backlog: "OS deep links (`bitcoin:`, `lightning:`, `lnurl`)"). React Native never handled these links. It registers only `com.noahwallet.<variant>` and Expo's dev scheme, and has no handler. So this is new on both platforms, not parity. The native apps already parse every one of these requests: Send's destination parser has accepted the SDK's forms since phase B (BIP-321 `bitcoin:` with `lightning=`, `ark=`, `req-ark` and `req-amount`; `lightning:` BOLT11, BOLT12 offers and bech32 LNURL; LUD-17 `lnurlp://`). A deep link is one more way into that same path.

## Schemes
- **Registered:** `bitcoin:`, `lightning:` and `lnurlp:` (LUD-17), in every variant (mainnet, signet, regtest).
- **Case:** iOS schemes are case-insensitive. Android intent filters aren't, so Android also registers `BITCOIN`, `LIGHTNING` and `LNURLP`, which uppercase BIP-21 QR codes use.
- **Not registered:** `lnurlw:`, `lnurlc:` and `keyauth:`. Noah doesn't do withdraw, channel or auth requests, and the parser already refuses LNURL-auth.
- **Unchanged:** `com.noahwallet.<variant>` stays as it is.

## Behaviour
1. **Same as scanning.** A link takes exactly the path a scanned QR takes: Send opens on the To step, and the request is applied by the existing route (iOS `QRScanRoute.apply`, and Android's equivalent). Parsing, network checks, amount rules (fixed-amount offers, `req-amount`) and every error message are today's. There's no separate parser.
2. **Never pays by itself.** The user still reviews and holds to send.
3. **A payment in progress blocks it.** The link is rejected and the current payment is left exactly as it is when:
   - Send is locked by an in-flight payment;
   - Send is preparing or showing a review;
   - a recurring payment approval is open.

   Show "Payment in progress" with "Finish the current payment before opening another payment request." The message is the same on both platforms. A link from another app must never discard or replace something the user is reviewing (Sol review, 2026-10-24). Only a draft before review, such as the To or Amount step, follows what scanning does today.
4. **Gates:**
   - **App lock:** while the app is locked, hold the link in memory and apply it after unlock.
   - **No wallet:** with no wallet yet (onboarding), or behind a launch gate (suspended, recovery, notifications required), ignore it. Decide this when the link arrives, even if the app is also locked: a link that arrived behind a gate is dropped, and doesn't come back after the gate clears and the user unlocks.
   - **Nothing persisted:** a link is never saved to disk, so if the app is killed before unlock, it's gone.
5. **Applied once.** A cold start and a warm start both work. A link is applied once only: a rotation, fold, recreation or process restore must not apply it again.
6. **Privacy:** never log the link. It holds addresses, invoices and amounts. Logging the scheme alone is fine.
7. **Wide windows and folds:** as scanning (Send in its pane).

## Copy
The only new string is "Finish the current payment before opening another payment request." It uses the same English on both platforms. Hand-translate it into es, ja, ko and sv in `apps/i18n/<locale>/native.json`. Then run `bun apps/tools/translations.mjs extract`, then `bun apps/tools/translations.mjs`, then `--check`. Never run `translate`.

## Testing
- **Unit:**
  - each scheme reaches the same route as a scanned string;
  - the uppercase forms;
  - applied once across recreation;
  - held while locked and applied after unlock;
  - ignored with no wallet;
  - blocked while a payment is in flight.
- **Device:**
  - iOS: `xcrun simctl openurl <udid> '<link>'`, cold and warm.
  - Android: `adb -s emulator-N shell am start -a android.intent.action.VIEW -d '<link>'`, cold and warm, plus a rotation after opening.
- **Live, on `noah-bark08`:**
  - an LND BOLT11 invoice as `lightning:<invoice>`;
  - a `bitcoin:` address with `amount=`;
  - a BIP-321 URI with `ark=`;
  - each one opens Send with the request applied.

  `lnurlp://` needs HTTPS, which the regtest stack doesn't serve, so it's covered by fixtures.
