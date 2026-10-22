# Onboarding redesign: build spec

Approved by the user on 2026-10-21. The interactive prototype is
[`onboarding-v4.html`](onboarding-v4.html); open it in a browser. Its
script holds every timing and position: read it, don't guess.
Tokens live in [`apps/design/tokens.json`](../../../apps/design/tokens.json),
generated into `NoahTokens.swift` / `NoahTokens.kt` by
`bun apps/tools/design-tokens.mjs`.

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
- restored wallets count as backed up;
- wallets migrated from React Native count as backed up, because RN showed the phrase during onboarding.

**Resuming.** An interrupted onboarding (the wallet exists but `isInitialized == false`) resumes at Name instead of the old recovery-phrase step.

## Screens

| Screen | Behaviour |
| --- | --- |
| **Welcome** | **Tree scene** (below) behind the content. "Noah" wordmark letters rise with the bouncy spring, then the tagline "Bitcoin, simply." **Buttons:** "Create wallet" (black pill) and "I already have a wallet" (text). A globe and language code at the top right open the language sheet; this replaces the old language step. |
| **Beta sheet** | Bottom sheet with an orange-tint "Beta" badge, "Before you start", "Noah is in beta. Only keep what you’re comfortable losing." and a "Got it" button. "Got it" creates the wallet (today's `createWallet`) and fades to Creating; the tree stays in place. Creation errors show inline in the sheet using today's messages. |
| **Creating** | **Tree create sequence:** <ul><li>12 orange specks converge into your leaf;</li><li>the leaf fills;</li><li>your branch traces up from the root;</li><li>a ripple, then a wave runs through the other leaves.</li></ul> **Phase text:** "Creating your keys", then "Joining the Ark", then "Ready" (success haptic). About 3.6 s total, then Name. |
| **Name** | **Copy:** "Pick a name" / "This is how people pay you." **Input:** large inline `name@noahwallet.io`, normalized to `[a-z0-9_.-]`. **Availability:** debounced (~500 ms). States: "Letters and numbers", "At least 3 characters", "Checking" (pulsing orange dot), "Available" (orange dot pops), "Taken" (hollow dot plus three suggestion chips). Use the public LNURL lookup as a hint (taken if `/.well-known/lnurlp/<name>` answers 200), and treat `update_lightning_identity` as the authority. **Exits:** "Continue" saves the name. "Skip" keeps the address the server already assigned. The Nostr key field moves to Settings. |
| **Email** | **Optional;** Skip stays. **Entry:** "Add your email" / "Only for urgent alerts about your wallet." <ul><li>email field with an orange check when the address is valid;</li><li>domain completion chips;</li><li>typo fixes ("Did you mean maya@gmail.com").</li></ul> **Send code:** the button morphs into a spinner and the screen swaps to code entry. **Code entry:** "Check your email" / "Enter the code we sent to x Edit". <ul><li>six boxes with an orange active box, verifying automatically at six digits;</li><li>iOS one-time-code autofill (`.oneTimeCode`); Android SMS/autofill hints where available;</li><li>"Resend code in 0:30", then "Didn't get it? Resend";</li><li>a wrong code shakes, turns the boxes red, shows "That code didn’t work. Try again." and clears.</li></ul> **Success:** the boxes fold into the orange check ring, the title reads "Email verified", then Notifications. Uses today's `/email/send_verification` and `/email/verify` calls. |
| **Notifications** | **Required, no skip.** **Conveyor scene:** small trees; your leaf hops from an expiring tree into the next round's tree while the old one is swept. **Copy:** "Keep Noah awake" / "Notifications let Noah receive payments and renew your bitcoin before it expires." **Button:** "Turn on notifications" shows the system prompt. **If denied:** the scene freezes and greys out, the copy becomes "Notifications are required" / "Without them, Noah can’t receive payments or renew your bitcoin before it expires.", and the button becomes "Open Settings". On return to the foreground, re-check and continue automatically once granted. **Later:** if notifications are turned off after onboarding, the same required screen blocks the wallet at launch, as `PushNotificationsRequiredView` does today. |
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

## Out of scope for this round

- The Home QR redesign (dot modules with the longship logo), the Home and Receive redesign, and Settings changes, except the minimum needed for the backup prompt and the Nostr field move.
