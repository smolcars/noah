# iPhone Duo readiness and implementation plan

Latest implementation and real-regtest verification: [whole-app coverage plan and results](iphone-duo/coverage-plan.md). The readiness audit and initial sample-data results below are historical.

Checked October 6, 2026. This note distinguishes published platform behavior, inspected local source, and work that still needs runtime verification. The initial audit below preceded application changes; the execution report at the end records the subsequent implementation.

## Conclusion

The installed development stack is sufficient to begin native Duo testing. Expo 58 supplies the necessary scene lifecycle foundation; it does not make Noah's custom layouts automatically fold-aware. First build the regtest app against iOS 27.1, verify resizing and native tab placement, then adapt shared spacing and the wallet's highest-value screens. Partial-fold support needs special attention because safe-area edge insets do not describe the interior folding region.

Apple announced the device already, but its October 5 developer notice gives **October 23, 2026** as customer availability. Simulator work can start now. [Apple release preparation notice](https://developer.apple.com/news/?id=kkphp5qo)

## Platform behavior verified in primary sources

- **Build SDK controls compatibility.** An iOS 26 build uses a compatibility-sized window when open. iOS 27 expands to most of the inner display while reserving the status-bar strip. iOS 27.1 enables the full display and vertical system bars. Apple's documented opt-in is recompilation with the new SDK; these instructions do not identify an additional Duo-specific Info.plist flag. Use live view/window sizes, avoid cached main-screen bounds, and do not infer device type from size classes. [Apple preparation guide](https://developer-mdn.apple.com/iphone-duo/prepare/)
- **Compact and expanded layouts should remain the same product.** Apple recommends adapting existing content, retaining functionality across poses, and avoiding drastic rearrangement. Split views can collapse to a single pane when closed and expand when open. Standard components handle many camera and folding-region adjustments. [Apple Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/designing-for-iphone-duo)
- **Native navigation bars can move to the side.** Container-managed SwiftUI and UIKit bars participate automatically; manually constructed standalone bars do not. Preserve accessible titles even when symbols are displayed. The vertical edge and overflow behavior are system-driven, so do not hard-code a right rail for every orientation or multitasking placement. [Apple vertical bars session](https://developer.apple.com/videos/play/tech-talks/111462/)
- **Safe areas can be asymmetric.** Handle left, right, top and bottom independently. Device Hub's Duo controls can open, close, rotate and partially fold the simulator; also test Split View on both sides. The inner display can resize even with `UIRequiresFullScreen`. [Apple preparation session](https://developer.apple.com/videos/play/tech-talks/111461/)
- **Interior regions have their own APIs.** `UIView.reservedRegions(kind:)` / SwiftUI geometry queries expose `.division` for the fold and `.occlusion` for obstructed areas such as an active inner camera. Regions have frames and active state. Use them, or system arrangement/split containers, to keep critical controls clear of the fold. `UIArrangementViewController` and SwiftUI `ArrangementView` provide adaptive two-view layouts. [Apple adaptive layout session](https://developer.apple.com/videos/play/tech-talks/111463/)
- **Hinge angle is not a layout substitute.** `UIHingeInteraction` and SwiftUI `onHingeChange` expose status and angle for interactions/effects; Apple directs layout work to regions and arrangements. Simultaneous outer-display content is a separate scene-accessory feature: the documented camera accessory requires a fullscreen inner-display camera session. Do not assume a wallet may freely mirror its receive QR to the outside while open. [Apple displays and scenes session](https://developer.apple.com/videos/play/tech-talks/111464/)

## Expo and React Native

Expo's SDK 58 announcement documents scene-based lifecycle support, scene-aware geometry, Device Hub integration, and React Native 0.88 release-candidate use. It also says deeper Duo integration would follow during the beta. This is evidence of the platform foundation, not a guarantee for every third-party native dependency. The announcement's EAS image information is from its publication and should be rechecked before release. [Expo SDK 58 announcement](https://expo.dev/changelog/sdk-58-beta)

The migration guide requires an `ExpoAppSceneDelegate`, a scene manifest, and an `ExpoReactNativeFactoryProvider` AppDelegate for hand-managed native projects. Scene callbacks affect cold-start URLs, lifecycle events and library hooks, so these need regression coverage after an upgrade. [Expo scene migration guide](https://github.com/expo/fyi/blob/main/ios-scene-lifecycle.md)

On resizable iOS 27 windows, orientation is a preference; an orientation lock may be refused and reported orientation may not describe window shape. `requireFullScreen` cannot reliably prevent resizing. Noah's portrait setting is therefore a layout/rotation audit item, **not proof that full-screen Duo support is blocked**. [Versioned Expo orientation documentation](https://docs.expo.dev/versions/v58.0.0/sdk/screen-orientation/)

`useWindowDimensions` updates with window size and font-scale changes; use container `onLayout` when a pane's size matters. [React Native API](https://reactnative.dev/docs/usewindowdimensions) Keep payment state above responsive layout branches and preserve component identity/keys: replacing a component tree resets its local state. [React state preservation](https://react.dev/learn/preserving-and-resetting-state)

## Inspected local evidence

The main readiness audit reported Xcode **27.1 (27A9275)**, simulator SDK/runtime **27.1**, an installed Duo device type, and a booted Duo simulator opened in the Device panel. Its Nix environment has Bun 1.3.13, Node 22.14 and CocoaPods 1.16.2; `expo install --check` passed. `xcodebuild -workspace client/ios/noah.xcworkspace -scheme Noah-Regtest -showdestinations` also succeeded and listed the Duo simulator as compatible. Installed packages and Pods both resolve Expo 58.0.6 / React Native 0.88.0-rc.3. These observations establish tool availability, not an app build result. The Device-panel capture was black and the automation session had no active app, so neither establishes working Noah UI.

- [Client manifest](../client/package.json): Expo `^58.0.6`, React 19.3, React Native `0.88.0-rc.3`.
- [SceneDelegate](../client/ios/Noah/SceneDelegate.swift) subclasses `ExpoAppSceneDelegate`; [AppDelegate](../client/ios/noah/AppDelegate.swift) supplies `ExpoReactNativeFactoryProvider`. The [Info.plist](../client/ios/noah/Info.plist) declares scenes, disables multiple app scenes, sets `UIRequiresFullScreen` false, and declares portrait/upside-down phone orientations. Multiple app windows are not necessary for the initial closed/open experience.
- [App configuration](../client/app.config.ts) declares portrait orientation and tablet support. Change the generating config if expanding orientations, rather than only editing generated native values.
- Installed `react-native-safe-area-context` **5.9.1** exposes four edge insets plus a frame. Its Fabric iOS provider reads live `safeAreaInsets`. No reserved-region API references were found in its source, installed React Native core, React Native Screens, Expo, or native-bottom-tabs. This inspected scope is not proof that no third-party solution exists.
- Installed `react-native-bottom-tabs` **1.3.1** uses SwiftUI `TabView`, but `ios/TabViewImpl.swift:77` reports `tabBar.frame.size.height` as a scalar. `ios/TabView/NewTabView.swift` hosts React Native content with container safe areas ignored. Noah consumes `useBottomTabBarHeight` as bottom spacing in numerous screens. **Inference:** system vertical bars may appear automatically while the existing padding contract remains wrong. Measure this before selecting a library update or a small shared fix.
- The installed SDK's `UIKit.framework/Headers/UIViewReservedRegion.h` and `UIView.h` declare reserved-region queries available from **iOS 27.1**, with frame, margins and active status. This confirms that the native APIs exist locally if a bridge is required.
- `xcrun simctl help`, `help ui`, and `help io` exposed no documented fold/pose command. Device Hub's documented controls remain the reliable manual testing route. Do not invent a `simctl fold` command.

## Remaining verification and smallest next step

1. Build and launch a fresh **Noah-regtest** development build on Duo with Expo running. It was not installed during the readiness audit. Record open/closed/partial-fold snapshots and verify live dimensions, safe insets and native tabs; no build or launch success is claimed here.
2. Resolve native tab/edge spacing first. Then prototype one useful expanded screen: wallet overview beside activity/detail. Preserve the active route, draft recipient/amount, selected invoice, keyboard focus and scroll position while resizing.
3. Test a QR and payment confirmation against actual division regions in book and tabletop poses. Prefer a system container or a compatible installed/upstream API. If the chosen custom layout still lacks region data, expose only that geometry through the existing native module; do not create a hinge-angle-driven layout framework.
4. Verify cold and warm deep links, pending payments, camera scanning, native sheets, large text, keyboard visibility, and existing iPhone/iPad layouts. Enable landscape deliberately after inspecting these flows. Keep multiwindow wallet sessions, simultaneous display accessories and decorative hinge effects outside the initial scope.

Runtime questions still open: whether this exact native dependency set builds with Xcode 27.1; how side-bar safe areas propagate into Noah's nested native/React views; whether partial folds need a bridge; and whether folding interrupts camera, authentication or payment flows. Installed versions alone do not answer these.

## Proposed designs

[Interactive screen concepts](iphone_duo_concepts.html) show Home, History, Send and Receive with illustrative dimensions and sample data. These are proposals, not simulator screenshots or finished app UI. Native bar placement belongs to iOS; the depicted right rail is schematic.

- **Closed / narrow window:** retain Noah's four destinations and current staged payment flow. Keep forms and keypads at comfortable widths, use scrolling for short windows, and keep the current compact detail presentation.
- **Open / sufficient content width:** Home places balance and actions beside recent activity. History places its existing `TransactionDetailContent` beside the list. Send adds a read-only summary beside the current stage. Receive puts the existing QR beside amount, description and copy/share actions.
- **Partially folded:** keep the QR, amount keypad and confirmation controls within a usable region. Explore QR above / request controls below in tabletop pose only after verifying native division geometry; use the same request. In book pose, arrange companion content across the usable regions. No design depends on a fabricated hinge angle or hardcoded fold position.
- **Narrow Split View / large text:** return to one column based on measured available space. An open phone does not always mean two columns. Measure after navigation and safe-area allocation; choose breakpoints from minimum usable pane widths and actual simulator observations, not the device name.

## Implementation sequence and acceptance gates

### 1. Establish a working regtest baseline

Use the existing Xcode workspace and `Noah-Regtest` scheme, built against simulator SDK 27.1 through Nix. Run Expo's development server, install the fresh development build on the existing Duo simulator, and use Device Hub for open/close/rotate/partial-fold controls. If Pods need regeneration, use `nix develop --command just ios-prebuild`. Do not use Expo Go for Noah's custom native modules.

Measure current window/container dimensions, all four safe-area insets and native bar placement without logging wallet data. Check `Navigators.tsx`, `NoahSafeAreaView.tsx`, and all `useBottomTabBarHeight` callers. Prefer a compatible native-library solution; change shared layout handling only where measured behavior requires it. Audit `AppBottomSheet` height, width and keyboard calculations, which are currently designed around a full-width phone sheet. Audit the portrait configuration and add supported rotations consistently in the Expo config/native project when the affected screens pass.

**Pass:** a fresh build launches; all four tabs remain reachable; foreground controls avoid system UI; open/close and rotation update layout without a blank screen or navigator restart. An inactive display's black capture is not a passing screenshot. This phase determines whether a dependency patch/update or a small reserved-region bridge is necessary.

### 2. Implement Home and History first

Keep one navigation tree. Use live container layout and font scale to switch layout within stable screen components. Reuse existing balance/activity rendering, `useTransactions`, and `TransactionDetailContent`; keep selection/filter state above any pane/sheet presentation branch. Do not add a new global layout store or identify Duo by model name.

**Pass:** opening reveals the companion pane; closing retains the selected transaction, filter, list scroll position and hidden-balance preference. Text remains readable at accessibility sizes. A narrow open window still works as one column.

### 3. Adapt Send and Receive, then secondary screens

Retain a single mounted `useSendScreen` owner and a single `useReceiveRequest` owner. Layout changes must not rerun payment submission or regenerate an invoice. Size the QR from its pane rather than the whole window. Keep final payment confirmation explicit, fees current and pending-payment UI visible. Constrain sheet/form widths on the open display and handle keyboard space using live bounds. Add reserved-region geometry to the existing native bridge only if standard containers or current dependencies cannot keep important content clear in partial folds.

Follow with settings/onboarding/backup/restore, camera scanning and QR utilities. BTC Map can later use a merchant list/detail pane, reusing its current sheet content; it is not a prerequisite for the first Home/History prototype. Preserve existing biometric gating and background-wallet coordination.

**Pass:** fold/unfold at each send stage and while confirmation, keyboard, pending-payment and success UI are visible. Recipient, amount, comment, rail and source survive. A confirmed payment is submitted once. The receive URI/request and expiry survive. No wallet reload, auth bypass or duplicate background job is introduced by layout changes. Critical controls and QR codes avoid interior reserved regions.

### 4. Add repeatable coverage and release validation

Run `nix develop --command just check`. Reuse the existing Maestro/regtest infrastructure for one transition smoke flow; use Device Hub pose controls manually until a supported automation API is confirmed. Assert state before and after each transition. Exercise light/dark, large text/VoiceOver, keyboard, camera, lock/background/resume, cold/warm payment links, repeated folds, offline and pending-payment states. Confirm an ordinary iPhone and iPad still work.

The current iOS Maestro workflow selects **iPhone 17 Pro** by name; add explicit Duo coverage while retaining a conventional iPhone run. Verify Xcode 27.1 and the runtime on the actual `macOS` CI runner; local availability is not evidence about the runner. Continue using the existing GitHub Actions native-build pipelines. Before calling this production-ready, validate camera scanning, biometrics and physical fold transitions on hardware when available.

**Pass:** client checks and native CI builds succeed, the fold-transition smoke flow preserves state, screenshots cover supported poses, and no payment or navigation regressions remain.

## Initial scope

Begin with the runtime/inset spike, then a Home/History prototype. Keep one wallet session and the current payment APIs. Defer simultaneous-display accessories, multiple wallet windows, decorative hinge effects and a custom navigation rewrite. Revisit them only for a concrete user flow after the core adaptive experience works.

## Engineering execution checklist

Implementation started October 6, 2026. The sections above record the initial audit; this checklist tracks implementation and validation separately.

- [x] Build and launch the existing regtest app on Duo; measure native navigation behavior before changing its spacing contract.
- [x] Introduce one measured-container layout hook shared by the four main screens. Use minimum usable pane widths and font scale; keep screen and hook identity stable.
- [x] Adapt Home's balance/activity layout and History's list/detail layout, preserving selection and filters when the detail moves between a pane and sheet.
- [x] Adapt Send's current stage plus read-only summary and Receive's QR plus request controls. Keep existing send/receive hooks and submission logic unchanged.
- [x] Constrain wide sheets, allow short-window scrolling, and enable iOS rotations consistently in native and Expo configuration.
- [x] Fix the measured native bar spacing error at its shared source.
- [ ] Handle interior reserved regions in partial folds; edge safe areas alone are insufficient.
- [x] Add a focused breakpoint regression check and adaptive Maestro flow; create isolated CI simulators and offer a manual Duo run while retaining required ordinary-iPhone coverage.
- [ ] Confirm the updated workflows on the actual CI runner and resolve Duo Maestro/XCTest automation limitations before gating PRs on Duo.
- [x] Run client checks, Android simulator smoke checks, and manual Duo transitions; record limitations requiring CI or physical hardware.


## Implementation report — October 7, 2026

### Shipped in the working tree

- Four stable screen trees share `useAdaptiveLayout`: companion panes appear at a measured content width of `2 × 260 × max(1, fontScale) + 24` points. Narrow windows and larger text return to one column. There is no device-model detection, new wallet session, or new payment submission path.
- Home shows balance beside recent activity; History moves existing transaction details between a compact sheet and a side pane; Send adds a read-only summary; Receive sizes the QR from its own pane and places request controls alongside it.
- Sheets constrain their content to 560 points, respect asymmetric edge insets, and cap numeric detents to available height. Send keypads/choices and Receive amount/note content can scroll in short windows. Receive's note editor accounts for the keyboard.
- Both Expo's generating configuration and the native phone orientation list support iOS landscape. Android retains its existing portrait configuration; Android foldable/tablet windows still use the same width-driven layout. Android hinge, separating-fold, and tabletop handling are not implemented or verified.

### Native compatibility fixes

1. **Vertical native tab bar:** the installed bottom-tabs library reported the Duo rail's full 678-point height as bottom padding, leaving Home blank. Its shared measurement now detects a vertical bar, reports the window's bottom safe inset instead, and remeasures after layout. The rebuilt Duo app reported 34 points and displayed all four screens. Horizontal bars retain their measured height.
2. **Bottom-sheet Fabric initialization:** opening Send exposed a React Native 0.88 assertion because the sheet surface view lacked default Fabric props. Initialize that component's props in its constructor, matching the library's other component. Send and transaction sheets then opened successfully.
3. **ExpoJSI Swift interface filtering:** removing a private constraint declaration left an unavailable attribute attached to the next public declaration. The build-script patch removes the paired attribute too. `scripts/test_expo_swift_interface.py` reproduces the filter case and protects unrelated public availability declarations. This is an SDK/toolchain compatibility fix, not Duo-specific. Xcode 27.1 / Swift 6.4 was used; the first affected Xcode release has not been established.

All three fixes are pinned Bun patches; package versions were not upgraded as part of this work. Existing Xcode-project edits and unrelated plist formatting were preserved.

### Validation boundaries

The iOS regtest simulator app built successfully with Xcode 27.1 (27A9275), and the Android regtest debug app built and launched on Pixel 9 Pro / Android 16. The visual checks used temporary in-memory sample balances, transactions, and a non-payable Receive QR because the local regtest services could not finish startup. These checks establish layout and local draft behavior, not successful invoice generation, wallet synchronization, or payment settlement. The temporary navigation/hook overrides are removed from the delivered source.

On Duo, the entered Send amount survived switching between inner and outer displays. History retained its Ark filter and selected transaction while details moved between sheet and pane. The Receive note survived an orientation request with the keyboard visible. Active-display screenshots show real native layouts with sample data; they are distinct from the earlier HTML design concepts.

The device CLI dispatched fold transitions, but its hinge-angle verification uses a CoreDevice command unavailable in this Xcode build. Display dimensions (951 × 669 inner, 466 × 678 outer), screenshots, and retained UI state independently verified the transitions. No passing hinge-angle telemetry check is claimed. Maestro also encountered stale tap coordinates after rotation and a native-tab hierarchy exposing only the selected tab on one run; the full Duo automated flow is not yet a confirmed pass.

Regtest recovered on October 7 after enabling OrbStack access to Documents. Reading the Postgres init directory through its bind mount had stalled a VM CPU; the same files outside Documents worked, and the original mount read completed in 0.26 seconds after permission was enabled. Empty Bitcoin `settings.json` and `blocks/xor.dat` files were backed up and repaired. `just setup-everything` then completed.

A concurrent self-hosted Maestro run also reused the local `scripts` Compose project. Its teardown removed the shared regtest volumes before a preservation guard took effect; the replacement stack was initialized and funded again. CI now uses a project per run, serializes Maestro jobs sharing fixed localhost ports, and recreates Bark wallets through the selected Compose service instead of hard-coding `scripts_bark`. Local regtest must be stopped with `just stop` before a Maestro CI job can bind those same ports.

Final local checks from the original checkout passed: `just up`, Bitcoin/CLN/LND synchronization, an active funded Lightning channel, funded Bark, initialized Barkd, Electrs, and Noah HTTP health on port 3000. A 1,000-sat Bark → Ark/CLN → LND payment settled; the receiver reported `SETTLED` and 1,000 sats paid (payment hash `64b205b8adab87a1e029c239335e7193f4d31916a4ee0c370ffbed4cbfba919c`). This verifies the local payment stack; funded simulator payment flows remain a separate release check.

[Actual simulator screenshots](iphone-duo/README.md) document open/closed layouts and the Android smoke run. The [unedited dark-mode video](iphone-duo/noah-iphone-duo-dark-open-raw.mp4) is one continuous native Simulator recording of the inner display, copied without post-processing; it uses the same sample wallet data.

### Completed local checks

- `nix develop --command just check`: lint, 128 unit tests (348 assertions), and TypeScript checking passed after removing the temporary probes.
- `python3 scripts/test_expo_swift_interface.py`: passed against the installed patched build script.
- `python3 scripts/test_regtest_isolation.py`: the previous cleanup deleted the simulated local wallet; the corrected cleanup preserves it and recreates only the selected CI wallet. Compose configuration also resolves every named volume under the CI project prefix.
- Android `adaptive-layout-checks.yml`: passed on Pixel 9 Pro / Android 16, covering Home, Send amount 500, Receive note/keyboard and amount 500, sheet dismissal, and History. This used the sample-data harness described above. The test exposed a missing Android `testID` forwarding path in the shared native button; it now uses the same Compose modifier as the secondary button.
- iOS regtest simulator build and Android regtest debug build: passed. Build success does not establish funded-wallet behavior.
- `bun install --frozen-lockfile` passed with all patches applied; installed tab-library source matched the native fix used in the successful build.
- Changed workflow/flow YAML parsed, and the native plist passed `plutil -lint`. Full Duo Maestro did not pass because its native-tab hierarchy omitted unselected tabs; the workflow keeps ordinary-iPhone PR gating and adds a manual simulator selector for experimental Duo runs.

### Remaining release work

- Run the existing funded-wallet/payment suite and the new adaptive smoke flow against a healthy regtest stack; confirm the ordinary-iPhone CI run and manually selected Duo run on the runner's installed SDK/runtime.
- Verify partial folds and camera occlusion before claiming complete fold-aware support. The installed `@expo/ui` already exports iOS 27.1 `ArrangementView` and arrangement modifiers; evaluate that native container before adding a bridge. Width-only panes do not avoid an interior reserved region.
- Exercise large text and VoiceOver on devices, existing iPhone/iPad layouts, cold/warm links, pending-payment and confirmation states, camera scanning, biometrics, and background/resume. Breakpoint unit coverage is not an accessibility UI test.
- Test an actual Android foldable and its hinge geometry before claiming hinge-aware Android support. Physical Duo testing remains necessary before production sign-off.
