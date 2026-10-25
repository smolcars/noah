# Onboarding redesign: build spec

Approved by the user on 2026-10-21. The interactive prototype, `onboarding-v4.html`, was removed once built; `git log --diff-filter=D -- docs/native-rewrite/design/onboarding-v4.html` finds it.
Tokens live in [`apps/design/tokens.json`](../../../apps/design/tokens.json),
generated into `NoahTokens.swift` / `NoahTokens.kt` by
`bun apps/tools/design-tokens.mjs`. Exact line boxes need iOS 26. On iOS
18–25, single lines keep the font's natural height, which is an accepted
exception.

## Principles

- **Colour:** monochrome, white on light and true black on dark. Orange (`#F7931A`) means "you" and never fills a button or sits on an amount. The ₿ is black (foreground).
- **Wordmark:** "Noah" with a capital N, set in Space Grotesk 600.
- **Text:** one headline and at most one short line per screen.
- **Motion:** every motion is one of four springs (snappy, soft, bouncy, tap). Reduce Motion turns travel, scale and flips into 180 ms fades; spinners and progress still animate.
- **Haptics:** the prototype logs every haptic in its side panel. Match them: selection, light, medium, success, warning, error.
- **Copy:** must stay translatable. Add every new string to `apps/i18n/en/native.json` and hand-translate it into es, ja, ko and sv. Never run the paid `translate` command.

## Flow

```
Welcome ─ Create wallet → Beta sheet → Creating → Name → Email (optional) → Notifications (required) → Done → Home
        └ I already have a wallet → Restore → Restoring → Home
```

**Recovery phrase.** It is no longer shown during onboarding. Home shows a "Back up your wallet" prompt until the user has viewed the phrase and confirmed they wrote it down. That state is persisted per wallet:
- only restored wallets count as backed up, because typing the phrase proves the user has it;
- every other wallet keeps the prompt until the user confirms: new wallets, wallets migrated from React Native, and wallets made by earlier native builds.

React Native records no acknowledgement. A user who quit RN onboarding before seeing the phrase still gets `finishOnboarding()` on the next launch, so a migrated wallet's backup status is unknown.

**Resuming.** An interrupted onboarding (the wallet exists but `isInitialized == false`) resumes at Name instead of the old recovery-phrase step.

## Existing and migrated wallets

Wallets migrated from React Native and wallets made by earlier native builds must keep working exactly as they do today. The redesign only replaces the onboarding UI.

- **Launch:** a seed in the keychain plus a wallet database opens Home, never onboarding, the recovery phrase or a new blocking screen. This holds even when the imported settings are missing, cleared or damaged. Three existing rules still apply:
  - a readable RN store that explicitly says `isInitialized=false` resumes onboarding, now at Name;
  - the notifications-required screen still blocks a wallet whose notifications are off. RN does the same (`PushNotificationsRequiredScreen`), notifications are required to renew VTXOs, and turning them on clears it. Missing or damaged settings never cause it;
  - on Android, the battery-optimisation prompt (`PlatformGateRules.gate`) still shows once while battery optimisation is on and `hasSeenBatteryOptimizationPrompt` isn't set, as RN's `BatteryOptimizationScreen` does. It has Skip. Missing settings can make it show once more, which is acceptable.
- **Storage:** don't change keychain items, the wallet database, the RN settings import or any existing settings key. The backup flag is a new key. When it's missing, the wallet counts as not backed up.
- **Settings:** the language picker moves to Welcome, but an imported or previously chosen language still applies on first launch.
- **Notifications:** the push gate keeps today's rules for existing wallets. Only its look changes.
- **Tests:** an RN-migrated fixture and an existing native wallet both open Home (after any of the rules above) and show the backup prompt; a restored wallet opens Home without it; an interrupted onboarding resumes at Name. Keep the existing migration and upgrade tests green.

## Screens

| Screen | Behaviour |
| --- | --- |
| **Welcome** | **Tree scene** (below) behind the content. "Noah" wordmark letters rise with the bouncy spring, then the tagline "Bitcoin, simply." **Buttons:** "Create wallet" (black pill) and "I already have a wallet" (text). A globe and language code at the top right open the language sheet; this replaces the old language step. |
| **Beta sheet** | Bottom sheet with an orange-tint "Beta" badge, "Before you start", "Noah is in beta. Only keep what you’re comfortable losing." and a "Got it" button. "Got it" creates the wallet (today's `createWallet`) and fades to Creating; the tree stays in place. Creation errors show inline in the sheet using today's messages. |
| **Creating** | **Tree create sequence:** <ul><li>12 orange specks converge into your leaf;</li><li>the leaf fills;</li><li>your branch traces up from the root;</li><li>a ripple, then a wave runs through the other leaves.</li></ul> **Phase text:** "Creating your keys", then "Joining the Ark", then "Ready" (success haptic). About 3.6 s total, then Name. |
| **Name** | **Copy:** "Pick a name" / "This is how people pay you." **Input:** large inline `name@noahwallet.io`, normalized to `[a-z0-9_.-]`. **Availability:** debounced (~500 ms). States: "Letters and numbers", "At least 3 characters", "Checking" (pulsing orange dot), "Available" (orange dot pops), "Taken" (hollow dot plus three suggestion chips). Use the public LNURL lookup as a hint (taken if `/.well-known/lnurlp/<name>` answers 200), and treat `update_lightning_identity` as the authority. **Exits:** "Continue" saves the name. "Skip" keeps the address the server already assigned. The Nostr key field moves to Settings. |
| **Email** | **Optional;** Skip stays. **Entry:** "Add your email" / "Only for urgent alerts about your wallet." <ul><li>email field with an orange check when the address is valid;</li><li>domain completion chips;</li><li>typo fixes ("Did you mean maya@gmail.com").</li></ul> **Send code:** the button morphs into a spinner and the screen swaps to code entry. **Code entry:** "Check your email" / "Enter the code we sent to x Edit". <ul><li>six boxes with an orange active box, verifying automatically at six digits;</li><li>iOS one-time-code autofill (`.oneTimeCode`); Android SMS/autofill hints where available;</li><li>"Resend code in 0:30", then "Didn't get it? Resend";</li><li>a wrong code shakes, turns the boxes red, shows "That code didn’t work. Try again." and clears.</li></ul> **Success:** the boxes fold into the orange check ring, the title reads "Email verified", then Notifications. **Already verified:** `/email/send_verification` answers "already verified" without sending a code, for example when onboarding resumes after verification. The existing `sendEmail()` wrappers report this; go straight to the verified state and never ask for a code. Uses today's `/email/send_verification` and `/email/verify` calls. |
| **Notifications** | **Required, no skip.** **Conveyor scene:** small trees; your leaf hops from an expiring tree into the next round's tree while the old one is swept. **Copy:** "Keep Noah awake" / "Notifications let Noah receive payments and renew your bitcoin before it expires." **Button:** "Turn on notifications" shows the system prompt. **If denied:** the scene freezes and greys out, the copy becomes "Notifications are required" / "Without them, Noah can’t receive payments or renew your bitcoin before it expires.", and the button becomes "Open Settings". On return to the foreground, re-check and continue automatically once granted. **Later:** if notifications are turned off after onboarding, the same required screen blocks the wallet at launch, as `PushNotificationsRequiredView` does today. **Android without Google Play Services:** keep today's conditional UnifiedPush distributor setup (`Onboarding.kt`, around lines 63–67) inside this step. Granting the permission isn't enough without a distributor, so don't continue until the existing setup succeeds or reports it isn't needed. **Android battery optimisation:** keep today's skippable battery step (`PlatformGateRules.afterEmail` → "battery") after notifications and any UnifiedPush setup, restyled to match, with no tree. It sets `hasSeenBatteryOptimizationPrompt` as it does today. |
| **Done** | Orange ring draws, check draws, two ripples, success haptic. **Copy:** "You’re all set", then `name@noahwallet.io` (or "Your wallet is ready."). **Button:** "Open wallet" calls today's `finishOnboarding`. |
| **Restore** | **Copy:** "Welcome back" / "Enter your recovery phrase." **12 / 24 control:** sliding-pill segmented control. **Grid:** 2 columns for 12 words, 3 for 24, no scrolling at 24 on a 393-pt phone. **Cells:** <ul><li>BIP-39 prefix autocomplete in the keyboard accessory (up to three; the first is bold); space or return accepts the first;</li><li>an invalid word shows red text and underline plus "Check word N";</li><li>pasting a whole phrase into any cell fills every word with a stagger.</li></ul> **Paste button:** reads the clipboard through the existing `RestorePasteboard` / `RestoreClipboard` paths. **Restore:** enabled when every word is in the list. A bad checksum shakes the grid and shows "These words don’t form a valid phrase." The phrase never appears in logs or screenshots, and the screen keeps today's secret shielding. |
| **Restoring** | **Tree restore sequence:** an orange scan band sweeps the leaves; your leaves light up with their branches drawn to the root, and the balance counts up below. **Phase text:** "Restoring your wallet", then "Finding your bitcoin", then "Ready". **Progress:** drive it from the real restore step callbacks (`restoreStep`); it may hold on "Finding your bitcoin" while restore is still running. Errors use today's `RestoreIssue` messages. |

## Tree scene

The tree is drawn the way bark builds it (`lib/src/tree/mod.rs`, `RADIX = 4`):

- **Shape:** leaves first, then parents grouping up to four children, up to a single root. The welcome tree is depth 3 (64 leaves) with your slot at leaf 41.
- **Links:** cubic curves with vertical tangents.
- **Weights:** links thicken towards the root (1.3 / 1.0 / 0.7 pt). Orange strokes get a soft orange glow.
- **Chain line:** the Bitcoin line at the root has small block ticks that drift slowly.
- **Where it's drawn:** one canvas behind the onboarding screens (SwiftUI `Canvas` + `TimelineView`; Compose `Canvas` + `withFrameNanos`). It persists across Welcome → Creating → Name so the tree never jumps.
- **Where it appears:** only Welcome, Creating, Restoring and Notifications. Nowhere else in the app.

## Foldables, tablets and landscape

Design for the iPhone Duo first, then make Android foldables match. The Duo's closed panel is about 466×678 pt. Open and half-open, the window is about 951×669 pt with a vertical fold down the middle.

- **Compact (phones, Duo closed, Pixel Fold closed):** one column, as in the prototype. The Duo's closed panel is short, so every screen must fit in 678 pt without clipping: the tree scales to the space left, and scrolling is the last resort. Buttons must never be cut off.
- **Wide (Duo open or half-open, Pixel Fold open, tablets, wide landscape):** two panes split at the fold. The leading pane holds the picture and the trailing pane holds the words and controls.
  - **Welcome, Creating, Restoring, Notifications:** the tree scene fills and is centred in the leading pane. The wordmark or headline, copy and buttons go in the trailing pane.
  - **Name, Email, Restore:** the headline and its short line move to the leading pane at a larger size. The field, chips, code boxes, word grid and buttons go in the trailing pane, aligned with the headline.
  - **Done:** the ring and check fill the leading pane. The title, address and button sit together in the trailing pane.
  - **Beta and language sheets:** stay inside the trailing pane. They never cross the fold.
  - **Content width:** keep each pane's content at most about 420 pt wide and centred in its pane.
- **Bent (half-open):** nothing crosses the fold: no text, button, field, chip, sheet or tree.
  - **Book posture (vertical fold):** the two-pane split above, with the split on the hinge.
  - **Tabletop posture (horizontal fold), Android only:** the tree or headline sits on the top half; controls, input and the keyboard on the bottom half.
  - **iOS:** lay out from the window size and system safe area alone.
    - `UIHinge` (iOS 27.1) reports only status and angle, not the fold's orientation or position.
    - The Duo's fold is vertical down the middle in every recorded pose. So a wide window always gets the two-pane split at its centre, whether it's open or half-open, and a narrow one gets the single column.
    - There's no iOS tabletop layout and no hinge gutter. Don't add `UIHingeInteraction` unless it drives something visible.
  - **Android:** use the fold bounds from `LocalFoldingFeatures` in `ui/AdaptiveLayout.kt`, which only reports separating folds.
    - `LaunchGateView` wraps onboarding in `avoidHinge()`, which keeps only the larger side of a fold. Onboarding must not sit inside it. Give onboarding the full window and let it split at the reported fold bounds, keeping each pane clear of the hinge.
    - Every other launch state keeps `avoidHinge()`.
- **Changing pose:** folding, unfolding or rotating at any step keeps everything: the current step, the typed name, email and code digits, the resend countdown, restore words (in memory only, under the existing secret rules) and in-flight wallet creation or restore. The tree keeps its animation clock, so it neither restarts nor jumps. A pose change must never create a second wallet, restore twice or resend a code.

## Testing

Test every screen in light and dark on all of these:
- an iPhone simulator (iPhone 17, iOS 27.0);
- an iPhone Duo simulator (iOS 27.1, the only runtime that supports it), closed, half-open and open;
- an Android phone emulator;
- an Android foldable emulator (Pixel Fold), closed, half-open and open.

Use your own simulators and emulators. Also change pose in the middle of Creating, Email code entry and Restoring.

## Out of scope for this round

- The Home QR redesign (dot modules with the longship logo), the Home and Receive redesign, and Settings changes, except the minimum needed for the backup prompt and the Nostr field move.
