# Appearance setting: build spec

Approved by the user on 2026-11-03 ("Approved"). Today Noah always follows the phone's light or dark mode. This adds a choice in Settings → Display: **Automatic** (the default, today's behaviour), **Light** or **Dark**.

The principles, tokens, springs, haptics and copy rules are the ones in [`onboarding.md`](onboarding.md#principles). The interactive prototype is [`appearance-v1.html`](appearance-v1.html) (open it in a browser and tap the tiles); where this spec and the prototype differ, the spec wins.

## Row

- Settings → Display gets a new row **Appearance** between Language and Hide amounts. The order becomes Bitcoin unit, Currency, Language, Appearance, Hide amounts.
- Icon: a half-filled circle. On iOS that's SF Symbol `circle.lefthalf.filled`; on Android, the nearest equivalent in the existing settings icon set, or a small vector of the same shape.
- The value reads "Automatic", "Light" or "Dark".
- A tap opens the Appearance sheet as a root sheet, like Bitcoin unit, Currency and Language (iOS `SettingsRootSheet`, Android's root `sheet` key). Wide windows (Duo open or half-open, Fold open, iPad) show it in the trailing pane, as the other root sheets do.

## Sheet

- Title **Appearance**, with the line "Follow your phone, or keep Noah light or dark."
- Three tiles in one row, in this order: Automatic, Light, Dark. Each tile is a tiny Home screen above its name and a check circle (`SettingsCheck` on iOS, its Android counterpart).
- **The tiny Home screen** always uses its own fixed palette (light tokens or dark tokens), never the current theme. It shows:
  - the name chip (an orange-ringed initial and a short bar for the name);
  - the balance in Space Grotesk with the persisted unit and the real total, or the hidden form (`₿••••` / `•••• sats`) when amounts are hidden;
  - a muted bar for the fiat line, three Recent rows (a soft circle and a bar) and a small tab-bar pill.

  Its shape is about 9:17, with a 16 pt corner radius and a hairline edge. It's drawn with shapes and text, not a snapshot of the real Home. Screen readers skip it.
- **The Automatic tile** is the light mini Home with the dark one clipped to the lower-right triangle and a hairline along the diagonal.
- **The selected tile** gets a 2 pt foreground outline, 3 pt outside the tile (the Bitcoin unit sheet's selected stroke), and its check circle fills.
- **A hint line** under the tiles, in the muted colour:
  - Automatic: "Now light · follows your phone" or "Now dark · follows your phone", updating live if the phone switches while the sheet is open.
  - Light: "Always light".
  - Dark: "Always dark".
- **Wide panes:** the tiles grow with the pane, up to about 150 pt wide each, and stay in one row.
- **Large text:** names and the hint wrap. The row of three stays.
- **Accessibility:** each tile is one button labelled with its name, with the selected trait or state on the chosen one.
- On iOS the sheet is Liquid Glass through `GlassCompat`, like the other root sheets. Android keeps Material styling.

## Picking

- A tap on an unselected tile:
  1. plays a selection haptic;
  2. saves the choice;
  3. re-themes the whole app at once.
- A tap on the selected tile does nothing.
- **The sheet stays open** after a pick so you can compare. This differs from the other pickers, which close themselves.
- **The circle reveal:** when the colours on screen actually change (for example Automatic → Light while the phone is light changes nothing), the new theme grows out of the tapped tile's centre in a circle until it covers the window. It takes about 560 ms with an ease-out curve (cubic-bezier(.3,.7,.2,1) in the prototype). Under it, the old appearance is a frozen snapshot of the window taken just before the change. The live UI underneath is already in the new theme and stays interactive. Ignore further taps while a reveal is running, or let a new one replace it cleanly. Never stack snapshots.
- **Reduce Motion** (iOS) or animations off (Android `ANIMATOR_DURATION_SCALE` = 0, or the platform's reduce-motion check the app already uses): a 200 ms cross-fade instead, or an instant change when animations are off.
- On wide windows, the reveal still starts at the tile and covers the whole window, both panes.

## What changes

- **The app:** the choice covers every screen, sheet, alert, menu, the status bar and (on iOS) the keyboard.
- **iOS:** set `overrideUserInterfaceStyle` (`.unspecified`, `.light`, `.dark`) on the app's windows, not SwiftUI's `preferredColorScheme`, so presented controllers, alerts and the keyboard follow. Apply the stored choice before the first frame, so a relaunch in Dark never flashes light. Apply it to windows created later too (new scenes on iPad, and any `UIWindow` the app makes).
- **Android:**
  - `NoahTheme` resolves dark from the stored choice: Automatic means `isSystemInDarkTheme()`.
  - Apply the choice to the platform too: `UiModeManager.setApplicationNightMode` on API 31+, and `AppCompatDelegate.setDefaultNightMode` if the app uses AppCompat. Use whichever the app's activity setup supports, so dialogs, system-bar icon colours and the splash on API 31+ match.
  - Apply it before `setContent`, so a relaunch doesn't flash. A configuration change from a pick must not lose wallet state, the open sheet or the scroll position. Prefer re-theming in place over recreating the activity.
- **What stays on the phone's setting:** home-screen widgets, notifications, the launch screen on iOS and pre-31 Android, and the share sheet's own chrome (the system draws them).
- **Automatic** keeps following the phone live, as today.

## Storage

- One new native-only value, with the same key and the same strings on both platforms: `automatic`, `light` or `dark`. Missing or unknown means `automatic`.
- Store it next to the other display preferences in each platform's settings store. Nothing to migrate: RN never had the setting, so RN-migrated and earlier native wallets open on Automatic, exactly as today.
- It's a phone preference like Language, so it **survives Delete wallet**. If the settings store is cleared on delete, keep this value or put it where Language lives.

## Copy

New English strings: "Appearance", "Automatic", "Light", "Dark", "Follow your phone, or keep Noah light or dark.", "Now light · follows your phone", "Now dark · follows your phone", "Always light", "Always dark". Add them the way other native-only strings are added (see `docs/native-rewrite/translations-design.md` and the catalog tooling). Machine translation only, as decided.

## Tests

- **Unit:** parsing (missing, unknown, each value) and resolution (Automatic + system dark → dark, and so on).
- **Screenshot fixtures:** the sheet in light and dark, with each choice selected, on a phone and a wide pane.
- **Device:**
  - Pick Dark with the phone light: Home's background is black.
  - Relaunch: still dark, with no light flash.
  - Pick Automatic, then switch the phone to dark and back: Noah follows.
  - Pick Light with the phone dark: alerts and the keyboard (iOS) are light.
- **Upgrade path:** an RN-migrated fixture and an existing native wallet both reach Home on Automatic.
- **Devices:** iPhone 17 (iOS 27.0), iPhone Duo (iOS 27.1) folded, half-open and open, an Android phone AVD and a Pixel Fold AVD. Light and dark on each. Pose changes keep the sheet and the choice.
