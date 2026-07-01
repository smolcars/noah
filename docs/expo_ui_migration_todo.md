# Expo UI Migration TODO

## Goal

Move Noah's high-value JS-rendered UI controls toward `@expo/ui` native components while keeping each migration small enough to verify manually before committing.

## Working Agreement

- User installs `@expo/ui` before implementation starts.
- Migrate one UI surface at a time.
- After each migration, user verifies the app behavior.
- After verification, commit that migration as its own commit.
- Do not start Android or iOS apps from the autonomous workflow.
- Prefer universal `@expo/ui` components first. Use `@expo/ui/swift-ui` or `@expo/ui/jetpack-compose` only when the universal API does not cover the needed behavior.
- Keep wallet, payment, backup, and auth flows behaviorally unchanged.

## Setup

- [x] User installed dependency:

```sh
cd client
npx expo install @expo/ui
```

- [x] `@expo/ui` is present in `client/package.json` and `bun.lock`.
- [x] `ExpoUI` is present in `client/ios/Podfile.lock`.
- [x] Run `bun --cwd client typecheck` after dependency install.
- [ ] Confirm CI/development builds include the native module.

## Progress So Far

- Dependency setup is complete for `@expo/ui` at `~57.0.2`.
- Bun dependency metadata has been updated.
- iOS pod resolution has been updated to include the `ExpoUI` pod.
- `bun --cwd client typecheck` passes with the dependency installed.
- Native switch code migration is implemented for Settings and Backup settings.

## Migration Tasks

### 1. Native Switches

- [x] Add a small local wrapper for `@expo/ui` native switch usage.
- [x] Migrate settings switches in `client/src/screens/SettingsScreen.tsx`.
- [x] Migrate backup switch in `client/src/screens/BackupSettingsScreen.tsx`.
- [x] Verify toggles still update persisted state and disabled/loading states work.
- [x] Commit after user verification.

Plan:

- Keep the screen-facing API close to React Native `Switch`: `value`, `onValueChange`, and
  `disabled`.
- Wrap switches in the universal `@expo/ui` `Host`, use the universal `Switch` on iOS, and use
  the Expo UI Jetpack Compose `Switch` on Android so Noah can set explicit active/inactive colors.
- Keep all wallet, backup, biometric, mailbox, and suspend-wallet callbacks unchanged.
- Leave manual verification for the user/device because autonomous agents should not start Android
  or iOS apps locally.

### 2. Native Button Wrapper

- [x] Prototype adapting `client/src/components/ui/button.tsx` or adding a parallel native button wrapper.
- [ ] Preserve current `Button` and `NoahButton` call-site behavior where practical.
- [x] Start with low-risk screens such as onboarding, push permission, settings actions, and
  recovery-phrase actions.
- [x] Keep send/receive payment action buttons for a later pass unless the wrapper is proven stable.
- [x] Verify disabled, loading, outline, destructive, and primary styles.
- [ ] Commit after user verification.

Finding:

- A simple parallel wrapper around Expo UI's universal `Button` was rejected in manual visual QA.
  It did not preserve Noah's current sizing, typography, or color treatment on onboarding.
- Keep the existing React Native `Button` and `NoahButton` wrappers until a native-button approach
  can match the app's current visual contract.

Second approach:

- Build `NativeNoahButton` as a branded wrapper with explicit Noah dimensions, colors, shape, and
  text treatment.
- Use SwiftUI `Button` on iOS and Jetpack Compose `Button` variants on Android inside Expo UI
  `Host`.
- Migrated primary actions on onboarding, push-permission, beta warning, restore wallet, mnemonic,
  emergency email, Lightning address, UnifiedPush, Backup settings, Ark info retry, biometric gate,
  QR utility, debug action, destructive/admin, board/exit, and send/receive success
  screens/components.
- Added a small native icon button wrapper for utility refresh/share controls.
- Manual QA rejected the first native outline/ghost variants. Added a separate
  `NativeNoahSecondaryButton` for secondary actions.
- Migrated secondary actions on beta warning, mnemonic copy, push-permission retry, UnifiedPush
  distributor selection/skip, Lightning address skip, and backup listing.
- Kept payment bottom action rows on the React Native `Button` wrapper because the iOS native
  wrappers produced unstable label/frame layout there.
- Removed the now-unused `NoahButton` wrapper after migrating all call sites.
- The settings `Export Database` secondary button was moved into a dedicated screen because the iOS
  SwiftUI secondary button layout was unstable in the lower Settings scroll area.

### 3. Simple Text Inputs

- [ ] Migrate `client/src/components/ui/input.tsx` for ordinary form fields.
- [ ] Verify profile, lightning address, debug input, board amount, and delete confirmation fields.
- [ ] Do not migrate hidden/ref-heavy amount inputs in send/receive during this pass.
- [ ] Commit after user verification.

### 4. Slider

- [ ] Replace `@react-native-community/slider` in `client/src/screens/NoahStoryScreen.tsx` with `@expo/ui/community/slider`.
- [ ] Adjust behavior because Expo's drop-in slider does not currently support `onSlidingComplete`.
- [ ] Verify seeking behavior, disabled state, and track styling on both platforms through CI/manual review.
- [ ] Remove `@react-native-community/slider` after verification if unused.
- [ ] Commit after user verification.

### 5. Collapsible

- [x] Evaluate the single `@rn-primitives/collapsible` usage in `client/src/screens/HomeScreen.tsx` against `@expo/ui` `Collapsible`.
- [x] Replace the primitive usage with local controlled state because `@expo/ui` `Collapsible` only supports a text-label trigger and does not fit the custom balance/header/privacy-toggle UI.
- [ ] Verify the balance/details expansion behavior and animation feel.
- [x] Remove local collapsible wrapper after it became unused.
- [ ] Commit after user verification.

Finding:

- Expo UI's universal `Collapsible` is not a trigger/content primitive. It owns the tappable
  header through a string `label`, so using it on Home would either add an extra native disclosure
  row or force a redesign of the balance header.
- Home now keeps the existing visible balance header and toggles the details panel directly with
  React Native state and the existing Reanimated enter/exit animation.

### 6. Alert And Confirmation Dialogs

- [x] Evaluate `@expo/ui` native dialog/alert APIs against `client/src/contexts/AlertProvider.tsx`.
- [x] Migrate global alert display if the native API supports the existing title/description/action model.
- [x] Migrate simple `client/src/components/ConfirmationDialog.tsx` confirmations after global alert is stable.
- [ ] Verify destructive confirmations, delete-wallet typed confirmation, and backup/export dialogs.
- [ ] Remove `@rn-primitives/alert-dialog` only after all references are gone.
- [ ] Commit after user verification.

Finding:

- Added a small native alert wrapper for simple title/description/action dialogs.
- `AlertProvider` now renders simple `showAlert` calls through native SwiftUI/Compose alert APIs
  without changing the `showAlert` API.
- `ConfirmationDialog` now sends simple title/description confirmations through the native wrapper.
- Confirmation dialogs with custom React Native children still use the existing RN Primitives dialog
  path because typed delete/drop confirmations and the auto-board details dialog need rich content.

### 7. Bottom Sheets

- [ ] Keep `client/src/components/ui/AppBottomSheet.tsx` initially because it already uses native `@swmansion/react-native-bottom-sheet`.
- [ ] Later evaluate `@expo/ui` BottomSheet for parity with current detents, scrim, close/dismiss callbacks, scroll handling, and safe-area behavior.
- [ ] Migrate only if parity is clear and send/receive sheets behave identically.
- [ ] Commit after user verification.

### 8. Feedback Modal

- [ ] Evaluate replacing `React Native` `Modal` in `client/src/components/FeedbackModal.tsx` with a native sheet/dialog.
- [ ] Verify attachment preview, upload progress, validation errors, and close confirmation.
- [ ] Commit after user verification.

### 9. Dependency Cleanup

- [x] Remove unused local UI wrappers after migrations:
  - `client/src/components/ui/select.tsx`
  - `client/src/components/ui/dropdown-menu.tsx`
  - `client/src/components/ui/dialog.tsx`
  - `client/src/components/ui/popover.tsx`
  - `client/src/components/ui/accordion.tsx`
  - `client/src/components/ui/separator.tsx`
  - `client/src/components/ui/collapsible.tsx`
- [x] Remove unused `@rn-primitives/*` packages only after import checks pass.
- [ ] Remove `@react-native-community/slider` only after slider migration is verified.
- [ ] Run `bun --cwd client lint`.
- [ ] Run `bun --cwd client typecheck`.
- [ ] Commit cleanup after user verification.

Finding:

- Removed unused wrappers for select, dropdown menu, dialog, popover, accordion, separator, and
  collapsible.
- Removed direct dependencies on `@rn-primitives/accordion`, `@rn-primitives/collapsible`,
  `@rn-primitives/dialog`, `@rn-primitives/dropdown-menu`, `@rn-primitives/popover`,
  `@rn-primitives/select`, and `@rn-primitives/separator`.
- Kept `@rn-primitives/alert-dialog`, `@rn-primitives/label`, `@rn-primitives/portal`, and
  `@rn-primitives/slot` because they still have active imports.

## Current Inventory

- `Button`: payment bottom action rows plus legacy custom alert-dialog internals.
- `NoahButton`: 0 usages. Removed after migration.
- `Input`: 9 usages.
- Direct `TextInput`: 14 usages.
- `Switch`: 5 usages.
- `AppBottomSheet`: 6 usages.
- `AlertDialog`: legacy custom confirmation path plus wrapper exports.
- `Collapsible`: 0 usages.
- `NoahActivityIndicator`: 20 usages.
- `React Native Modal`: 2 usages.
- `@react-native-community/slider`: 1 usage.

## Verification Commands

```sh
bun --cwd client typecheck
bun --cwd client lint
```
