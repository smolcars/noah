# iOS adaptive layouts

Implementation base: `184c39c6` on `t3/ios-adaptive-layouts`. This is a presentation and input-focus pass using the existing SwiftUI controls, Inter fonts, Noah colors and cards. Wallet, payment submission, recurring execution, backup/restore data handling, Esplora, logging, and lifecycle policy are unchanged. `client/`, `server/`, and Android production code are untouched.

## RN comparison and gaps

Read `PLAN.md`, `TODO.md`, `apps/ios/README.md`, `apps/tools/TRANSLATIONS.md`, all four `client/tests/maestro/adaptive-*.yml` flows, `client/app.config.ts`, the RN adaptive helper, and the corresponding RN screens. RN enables tablets and all iOS orientations. Its pane threshold is two 260-point readable panes plus 24 points, scaled up for larger text. Native previously used 700 points only in History and BTC Map.

The baseline was built before presentation edits and preserved separately. Captures of that original binary continued while source changes were prepared; no updated build was installed before its device's baseline pass. Images are exclusively under `/private/tmp/noah-adaptive-ios-shots/{before,after}/{duo,phone,pad}`. No images are committed. Recovery phrases were never captured; onboarding automation continued past the phrase without taking a screenshot or accessibility dump.

| Screen | Baseline gap | Change |
| --- | --- | --- |
| Home | Balance, details, banners and activity stayed in one column on wide windows. | RN-style balance/status and activity panes; the same mounted balance disclosure survives changes. Both panes remain in a scroll view. |
| Receive | The fixed 270-point QR extended beneath the iPhone landscape tab bar. Amount/note sheets could hide the focused field behind pinned actions. | Square QR bounded by the available height and pane width; QR/control panes; readable details and amount sheets; scrollable actions in short keyboard layouts and focus tracking after viewport changes. |
| Send | Keypad and forms stretched; no RN summary pane; review and receipts stretched in wide presentations. | Readable composer, RN summary pane, bounded review/action/receipt content; focused recipient/note tracking. Submission/model ownership stays outside layout changes. |
| Scanner | Preview connection did not follow window orientation. | Preview-layer rotation follows the window scene after capture configuration and layout; detection/session/submission behavior unchanged. |
| History | Hard-coded 700-point threshold; automatic dismissal on expansion cleared the compact presentation flag. | RN threshold with larger-text scaling and a readable maximum; rotation dismissal preserves selection/presentation intent. |
| BTC Map | Hard-coded threshold; full-width search/overlays and merchant forms. | Scaled pane threshold; bounded overlays and details. Existing map/detail presentation and selected merchant remain owned by the screen. |
| QR hub | One column and fixed QR size. | Code/details panes and a square, height-bounded QR. |
| Settings and account/tools | Lists and forms stretched edge to edge on iPad. | Shared readable width for every settings destination; native grouped columns for Settings. Screen-owned secondary routes and full-row hit areas survive pane moves. Existing preference/profile/backup/log/export/feedback/story/debug controls remain. |
| Welcome / beta | Fixed vertical stacks had no overflow path in short windows or at larger text sizes. | Scroll containers retain the centered composition where it fits. |
| Restore | Landscape keyboard left too little room for a focused word; iOS 27.0 hung after responder resignation during a SwiftUI graph update. | Focus reconciliation is deferred, checks the latest binding, and avoids redundant begin-edit writes. Viewport/focus scrolling and scrollable short-layout actions; phrase/restore logic unchanged. |
| Recovery phrase | Already scrollable, bounded and shielded; reveal state is screen-owned. | Existing protection and reveal ownership retained. No phrase screenshots. |
| Recurring | Save action disappeared during editing; list/editor did not adapt. | Save remains reachable in the landscape keyboard toolbar, focused fields track the viewport, readable grouped editor panes and two list columns; all draft fields remain screen-owned. |
| VTXOs | Wide list lacked the RN companion detail. | List/detail pane with compact in-hierarchy detail and retained selected ID; existing filters/selections/actions retained. The parent owns the model and review presenter through layout changes. |
| Boarding / exits | Full-width forms; boarding's number pad had no Done control; deposit QR had no bounded white quiet area. | Native grouped panes, readable details/reviews, keyboard Done and bounded square deposit QR on white. |
| Lock / app-switcher cover / launch messages | Full-width fixed stacks could overflow. | Readable, scrollable cover/message content; overlay/window and protection policy unchanged. |
| Alerts / sheets / popovers | Short-keyboard action/field competition; remaining native surfaces needed verification. | Existing native alerts, sheets, share and date-picker behavior retained; custom form content bounded and scrollable. |

## Implementation locations

All paths below are relative to `apps/ios/Noah/`.

| Screen / shared surface | File:line |
| --- | --- |
| Readable width, scaled RN threshold, identity-preserving columns, viewport focus scrolling | `DesignSystem/AdaptiveLayout.swift:3`, `:19`, `:38`, `:69` |
| Home balance/status/activity | `Features/Home/HomeView.swift:27` |
| Receive QR and details/actions | `Features/Receive/ReceiveView.swift:61`, `ReceiveAmountSheet.swift:79`, `ReceiveCopySheet.swift:20` |
| Send composer, summary, confirmation, receipt | `Features/Send/SendView.swift:93`, `:166`, `:321`, `:386` |
| Scanner preview orientation / unavailable-camera text | `Features/Send/SendScanner.swift:14`, `:47` |
| History selection and split pane | `Features/History/TransactionsView.swift:15` |
| BTC Map controls and merchant detail | `Features/BtcMap/BtcMapView.swift:25`, `:52`; `BtcMapDetailView.swift:77` |
| QR hub | `Features/QRHub/QRHubView.swift:25` |
| Every settings destination's width | `Features/Settings/SettingsPresentation.swift:22` |
| Settings panes, stable secondary navigation, delete sheet | `Features/Settings/SettingsView.swift:22`, `:108`, `:218` |
| Welcome / beta; existing protected phrase | `Features/Onboarding/OnboardingFlow.swift:47`, `:96`, `:151` |
| Restore scrolling / deferred responder | `Features/Onboarding/RestoreWalletScreen.swift:54`, `:221` |
| Emergency email / Lightning keyboard forms | `Features/Onboarding/EmergencyEmailView.swift:100`; `LightningAddressView.swift:94` |
| Recurring list/routes and editor | `Features/Recurring/RecurringPaymentsView.swift:14`, `:54`; `RecurringPaymentEditorView.swift:44`, `:93`, `:98` |
| Boarding / exits / deposit | `Features/Onchain/BoardArkView.swift:10`; `UnilateralExitView.swift:17`, `:219` |
| VTXO list/detail and review ownership | `Features/Onchain/VtxosView.swift:14`, `:137` |
| Lock and app-switcher cover body | `Features/Settings/WalletLock.swift:124` (cover policy below it unchanged) |
| Launch/error messages | `Features/Shell/RootView.swift:154` |

Independent review found and corrected two issues before the final suite: secondary Settings / odd recurring edit destinations are now owned by screen state outside the changing panes, and VTXO detail renders from the parent model. History uses the same current width state for sheet presentation and dismissal, avoiding a delayed callback captured before rotation. The compact VTXO detail stays in the hierarchy with Done, leaving a single review presenter and the existing lifecycle invalidation intact.

## Owned devices and simulator limits

Only these created simulators are used:

| Device | Runtime | UDID |
| --- | --- | --- |
| Noah Adaptive Duo 1020 | iOS 27.1 | `A81A3243-C1C7-44CE-A33E-CA06640A1825` |
| Noah Adaptive iPhone 1020 (iPhone 17) | iOS 27.0 | `5CD32C46-21FD-4CA9-8833-8B26FDC0EEFB` |
| Noah Adaptive iPad 1020 (11-inch M5) | iOS 27.0 | `1F218BEE-C927-40C9-BA7B-EC0DD9175DE4` |

The Duo fold command sends its HID pose change but reports `CoreDevice reported no hinge angle sample: Unknown option '--device'`. This Xcode's `devicectl device` command has no `motion hinge-angle` subcommand. Active panel geometry changes are observable, but hinge angle confirmation is unavailable. Folded windows observed 466×678 points; half-open/open windows observed 951×669. Portrait and both landscape physical orientations were requested through the supported native orientation command; the Duo kept these natural panel dimensions. Its resizable-session command also refused with CoreDevice error 24001 ("not in a valid orientation"), including after reporting portrait and after folding closed. This limits acceptance of a distinct Duo portrait inner viewport or arbitrary resized Duo window; it is not recorded as a successful test.

CoreDevice also once returned no display table during a fold request; the History pass was repeated and completed. The automation runner sometimes timed out acknowledging orientation or rejected an accessibility node's clip. Native `devicectl device orientation set` and screenshot/keyboard interaction provided the fallback. No protected simulator, emulator, physical device or Docker lifecycle was controlled. Wallets were created through onboarding, remained unfunded, and no payment, recurring schedule, restore, backup export or destructive action was submitted.

## Before / after evidence

Captures use the original and updated binaries. `fixture-*` files use the updated test host and are after-only populated evidence. Native screenshot fallback on Duo explicitly selects display 1 when folded and display 3 when open/half-open; the inactive display can be black. Inventory: Duo **165 before / 177 after** (59 screen/states per after pose), iPhone **94 / 135** (67 per orientation plus the scrolled deposit QR), iPad **96 / 96** (48 per orientation). Counts include separate keyboard/draft states and after-only fixtures; they are not a count of successful tests. A private inventory and QR/bounds evidence remain in the screenshots directory’s `evidence/` folder.

| Device / requested posture | Before | After |
| --- | --- | --- |
| iPhone 17 portrait | Original screen captures, drafts and native modals. | Readable column; after captures include onboarding, empty restore, wallet tabs, all settings destinations and populated fixtures. Draft/navigation round trips retained. |
| iPhone 17 landscape | QR crossed the tab bar; custom-form fields/actions competed with keyboard; stretched forms/keypad; missing companion panes. | Fully visible, decoded square QR; Home/Receive/Send panes; bounded review/receipt; focused Receive and recurring fields visible with native keyboard actions. History and VTXO selection/review retained. |
| iPad portrait / landscape | Stretched settings/forms; fixed single-column Home/Send/Receive. | Readable settings/forms and companion panes; populated review/receipt/history/onchain/cover fixtures; square QR decoded in both orientations. Arbitrary resizable-session API is unsupported (CoreDevice 1001); synthetic scrolling briefly moved its system window partly offscreen; restarting the owned simulator restored the full window. Those failed driver attempts are excluded. |
| Duo folded, portrait / requested landscape | 466×678 active panel; baseline captures use both orientation-request tags where supported, and `system` where the runner could not acknowledge rotation. | Readable compact wallet/forms; empty restore and keyboard remained responsive; all settings/detail/cover fixtures, Map/detail, QR hub and scanner surfaces captured. Nested Settings draft retained across all three poses. |
| Duo half-open / open, portrait / requested landscape | 951×669 active panel; state and layout captured across pose changes. Distinct portrait inner geometry unavailable. | Companion panes and bounded forms; populated review/receipt/history/onchain/cover fixtures, Map/detail, QR hub and scanner surfaces captured across both wide poses. Drafts retained through the folded → wide → folded round trip. Distinct inner portrait remains unavailable. |

Populated payment/history/onchain detail and cover fixtures use `AdaptivePresentationTests`, an opt-in test-host UI with in-memory payment storage and fake read-only data. Its confirm callbacks do not submit wallet operations. Set `TEST_RUNNER_NOAH_ADAPTIVE_UI=1`, run only `NoahTests/AdaptivePresentationTests`, then write a screen name to the app's `Documents/adaptive-screen`: `review`, `receipt`, `history`, `transaction`, `receive-success`, `board-review`, `vtxos`, `vtxo-detail`, `exit-detail`, `lock`, `push-permission`, `onboarding`, `email`, `lightning`, `debug`, `settings`, `profile`, `backup`, `boarding`, `exits`, `recurring-editor`, `recurring-list`, `feedback`, `export`, `story`, `ark-info`, `esplora`, `language`, `currency`, `unit`, or `delete`. Wait for the matching `adaptive-ready`. Write `stop` to restore the original app controller and finish. Never use this harness to capture a recovery phrase. Fixture captures are distinguished from the unfunded wallet captures; populated before images are not claimed.

## Reproduction

From the repository root, after waiting for other heavy builds on the shared machine:

```sh
xcodegen generate --spec apps/ios/project.yml
xcodebuild -project apps/ios/Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=<owned-iPhone-17-UDID>' \
  -derivedDataPath /private/tmp/noah-adaptive-ios-dd \
  -resultBundlePath /private/tmp/noah-adaptive-ios-full-final.xcresult \
  -disableAutomaticPackageResolution -skipPackageUpdates \
  -parallel-testing-enabled NO -collect-test-diagnostics never -jobs 2 \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -only-testing:NoahTests test
bun apps/tools/translations.mjs --check
```

An initial full run after `c080e8d2` passed. Final device acceptance then found two presentation gaps (blank row hit areas and deposit QR height); the final full run passed after their correction in `2a6addf1`, with `xcodegen generate` immediately before it. No production or test source changed afterward. Other runs were focused layout/security checks, `build-for-testing`, or the opt-in UI fixture alone.

## Verification and commits

- Initial focused run: **13 definitions / 20 expanded passes / 0 failed / 0 skipped**, on the owned iPhone 17. Width/large-text decisions, square QR sizing, mounted draft identity, deferred recovery focus, scanner mapping, in-flight Send/receipt retention and existing recovery/protection tests.
- All nine automated adaptive test definitions passed in the full run (16 expanded executions); the opt-in presentation host is one of the 27 skipped tests.
- `bun apps/tools/translations.mjs --check`: passes; five existing RN-translated keys are newly used, with only the coverage inventory changing.
- First commit: `354b86a8` — readable forms, overflow handling, recovery focus. Hooks passed (148 RN tests / 24 files, lint/typecheck and Rust formatting check).
- Follow-up focused run: **14 definitions / 21 expanded passes / 0 failed / 0 skipped**, including the viewport/focused-field regression.
- Second commit: `74ede3fc` — wallet companion panes, square QR sizing, preview orientation and focus/state tests. Hooks passed: 148 RN tests / 24 files, lint/typecheck and formatting.
- Live empty restore: iPhone and iPad portrait → landscape → portrait, and Duo closed → half-open → open → closed, with the keyboard, followed by Done, stayed responsive. No words were entered.
- Live iPhone Receive note: portrait field frame y=287.67, h=42.33, keyboard top=590; landscape field y=92.67, h=42.33, keyboard top=222. Draft remained `Rotation draft`. The iPad note also remained visible after rotation; its captured metadata had no software keyboard, so this is not claimed as an occluding-keyboard check.
- Vision decoded zero QR codes in the original iPhone landscape Receive screenshot and one square QR in the after image (detected aspect ratio 1.001). All six additional after Receive captures (phone portrait/landscape, iPad portrait/landscape, Duo folded/open) decoded one QR each, with detected aspect ratios 0.993–1.005. Both portrait and landscape QR-hub codes on the iPhone also decoded (aspect ratios 0.993 and 0.995), as did the iPad codes (0.990 and 0.991). All three Duo QR-hub poses also decoded one code (detected bounding-box ratios 1.018–1.031; the layout enforces a square).
- Third commit: `36ec729c` — details, stable navigation and populated fixtures. Hooks passed: 148 RN tests / 24 files, lint/typecheck and formatting. Independent final source review found no remaining issues.
- Live iPhone Settings → Feedback retained `Rotation draft` through rotation, and the odd recurring row opened from the companion list retained `Rotation schedule` in the unsaved editor. No feedback or recurring schedule was saved.
- Fourth commit: `bbaf144b` — recurring landscape keyboard keeps the focused Name field below navigation and above the keyboard, with Save reachable in the keyboard toolbar. Live bounds assertions passed after portrait/landscape rotation. Hooks passed.
- Populated VTXO selection and refresh review survived iPhone portrait/landscape rotation and Duo closed/half-open/open poses, followed by Cancel; the test backend never executes an onchain operation.
- Fifth commit: `c080e8d2` — current sheet-width state and screen-owned optional destinations. History compact → wide → compact and explicit close → rotate checks passed on the iPhone and across all Duo poses; final independent review found no remaining findings. Hooks passed.
- Initial full NoahTests on `c080e8d2`: **813 definitions / 167 suites; 786 passed / 27 opt-in skipped / 0 failed; 1,626 expanded passes** on the owned arm64 iPhone 17 / iOS 27.0. Result: Passed. The only runtime warning was the existing main-thread AVAudioSession warning in `StoryPlaybackTests.swift`.
- Live Duo recurring editor: the unsaved `Rotation schedule` draft, focused field and reachable Save survived all three poses; no schedule was saved.
- Live Duo nested Settings → Feedback retained `Rotation draft` through closed → half-open → open → closed; Map places/detail, QR hub and scanner fallback captures completed at each pose. A clipped accessibility row was scrolled fully into view before tapping; failed driver attempts are excluded.
- Live iPhone deposit sheet: the height-bounded QR fits after scrolling its explanatory text, and Vision decodes one square code (aspect ratio 1.001). Boarding retained its unsaved `12345` amount through rotation.
- Sixth commit: `2a6addf1` — full-row hit areas for plain Settings / recurring navigation buttons, and deposit QR height bounded to the sheet viewport. Hooks passed: 148 RN tests / 24 files, lint/typecheck and formatting. Final independent source review found no issues.
- **Final full NoahTests after `2a6addf1`: 813 definitions / 167 suites; 786 passed / 27 opt-in skipped / 0 failed; 1,626 expanded passes.** Owned arm64 iPhone 17 / iOS 27.0; exit 0; 528.304 seconds. All nine adaptive definitions passed (16 expanded executions). The existing `StoryPlaybackTests.swift` AVAudioSession warning remained. The sanitized final summary and test tree are retained under `/private/tmp/noah-adaptive-ios-shots/evidence/noah-adaptive-ios-full-final-{summary,tests}.json`; result bundles were deleted as requested.

## Remaining acceptance limits

- Duo: two observable panel geometries were verified across the three requested hinge poses. A distinct inner portrait viewport, hinge-angle readback, and an arbitrary resized app session could not be obtained from this simulator/Xcode combination. No hinge inset was reported in the active app geometry; layouts use the system safe area and do not invent a fixed hinge gutter.
- Scanner: simulated permission/failure surfaces and orientation mapping are covered. A simulator cannot verify the orientation or scanning quality of a real camera feed.
- Recovery reveal: its existing bounded scroll view, screen-owned reveal state, authentication, background clearing and secret shielding were retained. Protection/state tests run with nonsecret content. There are no screenshots of a revealed phrase and no claim of photographed phrase acceptance.
- Populated Send/onchain screenshots use fakes; actual in-flight Send retention and duplicate-submit protection are exercised by the mounted controller test. No funded or real network submission is claimed.
- Native alerts, picker/share sheet sizing, grouped Form scrolling and safe areas remain system-owned. Failed driver interactions or partially offscreen system windows are excluded from successful acceptance.

## Cleanup

Confirmed each owned UDID, device name, runtime and **Shutdown** state before deleting it. All three owned simulators are absent. Removed `/private/tmp/noah-adaptive-ios-dd`, the preserved baseline app binary and all 29 owned `.xcresult` bundles. No owned build/test/fixture runner remains. Screenshots and sanitized evidence stay under `/private/tmp/noah-adaptive-ios-shots`; no images are committed. Protected devices and Docker lifecycle were untouched.

Android remains open in the parent adaptive-layout checklist.
