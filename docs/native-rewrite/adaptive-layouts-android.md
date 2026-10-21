# Android adaptive layouts

Base: `18c171bb`, on `t3/android-adaptive-layouts` (the same starting commit as `feat/native-rewrite`). This is a presentation pass using the existing Compose controls, Inter typography and Noah theme. Native Android keeps rotation support. No manifest `configChanges` override was added. `client/`, `server/`, wallet/payment services, recurring execution, backup/restore services, Esplora switching, logging and app lifecycle coordination are unchanged.

Read `AGENTS.md`, `PLAN.md`, `TODO.md`, the Android README, translation instructions and all four RN `adaptive-*.yml` flows. RN Android's portrait lock was not carried over. The iOS agent's completed report was also read from its completed documentation commit (`7fa532a0`); both platforms use a 640 dp/point readable form width. Android uses its native navigation rail at medium window width rather than changing the app's screen structure or theme.

## Gaps and fixes

Paths below are relative to `apps/android/app/src/main/java/com/noahwallet/`.

| Surface | Baseline gap / existing protection | Result and source |
| --- | --- | --- |
| Shell / every screen | Bottom navigation consumed scarce landscape height; wide content stretched. | Current-window size classes select a rail from 600 dp; the bottom bar yields to the IME. The same content remains mounted as navigation changes shape. `ui/AdaptiveLayout.kt:21`, `ui/AdaptiveNavigation.kt:14`, `ui/NoahApp.kt:73`. |
| Insets / fold hinge | System safe-drawing insets existed; separating hinges were not reserved. | Jetpack WindowManager reports separating/occluding features. The largest unobscured region hosts the UI, including short tabletop layouts; controls and QR codes do not span the hinge. Dialog/sheet content also reserves a usable region. `ui/AdaptiveLayout.kt:39`, `:59`, `:73`; `ui/LaunchGateView.kt:138`; `ui/WalletPrivacy.kt:42`, `:59`. |
| Home | Balance, banners, activity and action links stretched across tablets. | Readable content width and wrapping links; existing balance disclosure and scrolling retained. `ui/HomeScreen.kt:70`. |
| Receive / payment details | A fixed large QR could extend below a landscape viewport. Open amount-sheet intent was not saveable. | Height-bounded square QR with white quiet area; capped body/sheet width; amount sheet and its amount/note survive saved-state restoration. `ui/ReceiveScreen.kt:68`, `:133`, `:188`, `:234`. |
| QR hub | Code and controls competed for the same short vertical stack. | Wide windows place the code beside its controls and identity; compact windows reserve height for the controls above the code so the entire square is visible without scrolling. Scanner intent survives configuration recreation. `ui/QRHubScreen.kt:30`, `:52`. |
| Send amount / recipient | Keypad and input stretched; pinned controls could leave almost no scroll viewport. `remember` discarded the model on recreation. | Capped, fully scrollable composer, including its action. A presentation ViewModel retains amount, recipient, note, stage and review. `ui/SendScreen.kt:45`, `:95`, `:125`, `:292`. |
| Send review / confirm / receipt | Review was discarded on every pause/disposal, including recreation. | Configuration recreation retains the review and UI scope; actual background/navigation still performs the existing cleanup. The process-owned `SendRuntime`/controller and journal remain authoritative. Real Send UI tests rotate during review and submission, then recreate after the receipt: one fake payment call, one retained receipt. `ui/SendScreen.kt:101`, `:117`, `:292`. |
| Scanner | Camera use cases did not explicitly track display rotation; close/action controls could overflow denied-permission layouts. | Preview and analysis target the actual display rotation, including 180-degree changes through a display listener. Header wraps and permission controls scroll. The existing frame decoder is unchanged. `ui/SendScanner.kt:30`, `:51`. |
| History list / detail | A fixed 700 dp split threshold ignored font scale. | Size-class/readable-pane decision accounts for larger text; existing selected transaction ID, list/detail split, compact sheet and detail scrolling remain. Detail width is capped. `ui/TransactionDetail.kt:35`, `:63`. |
| BTC Map | A short window could consume the map canvas with fixed controls; search had no explicit IME padding. | Short-window controls scroll around a usable canvas; search respects the IME. Existing map/detail split follows the shared pane decision and keeps its ViewModel/selection. `btcmap/BtcMapScreen.kt:99`. |
| All Settings destinations | Full-width lists/forms, including profile, preferences, Ark info, backup/export/logs, feedback/story/debug, email/Lightning, battery/UnifiedPush and wallet tools. | The destination host caps every page, including custom/lazy screens; shared forms retain scrolling and IME padding. `ui/settings/SettingsScreen.kt:46`, `:74`; `ui/NoahTheme.kt:57`. |
| Settings drafts / feedback image crop | Text drafts, attachment and pending crop could disappear on rotation. | Saveable text/action choices; image bytes/bitmap remain in a presentation ViewModel rather than the saved-instance Bundle; crop bounds restore separately; its existing inner scroll is removed so the alert supplies one scroll surface. `ui/settings/OtherScreens.kt:28`, `:182`; `ui/settings/ScreenshotCrop.kt:65`; endpoint text only at `ui/settings/EsploraSettingsScreen.kt:25`. |
| Welcome / beta / onboarding | Buttons and explanations stretched; the language sheet lacked hinge handling. | Shared readable scrollable form; language uses the existing wallet sheet wrapper. `ui/NoahTheme.kt:57`, `ui/Onboarding.kt:36`. |
| Restore input | Fields already scrolled and were secure, but rotation cleared entered words. | Input survives configuration recreation in memory only; screen removal/background still clears it, clipboard clearing is retained, and Android EditText state saving remains disabled. Restore services and submission are unchanged. `ui/RestoreWalletScreen.kt:39`, `:50`, `:135`; existing `ui/RecoveryWordField.kt:20`. |
| Recovery phrase reveal | Existing secure flag and re-authentication; no images permitted. | Readable scrolling form; reveal/authentication behavior unchanged. Tests assert `FLAG_SECURE` before and after recreation without screenshots or semantic dumps. `ui/Onboarding.kt:94`; shared form at `ui/NoahTheme.kt:57`. |
| Recurring list / editor | Editor route, all draft fields and open picker were ephemeral; chip rows could overflow. | Saveable editor identity/fields and date/time selection; wrapping chips. Native pickers reopen and retain a date after multiple changes. Executor/schedule/save logic is unchanged. `recurring/RecurringPaymentsScreen.kt:30`; `recurring/RecurringPaymentEditorScreen.kt:32`, `:67`, `:96`. |
| VTXOs / exits / boarding | Wide bodies stretched; existing forms and details already scrolled. | Shared onchain body and Settings host bound widths, preserving the existing controls/review lifecycle. Boarding's existing saveable amount remains. `onchain/OnchainScreen.kt:89`, `ui/settings/SettingsScreen.kt:46`, `onchain/BoardArkScreen.kt:16`. |
| Lock / Recents protection | Full-width lock/launch content and a full-screen cover without explicit inset padding. | Readable, scrollable cover content respects safe drawing and hinge regions. Existing secure-window/Recents policy and opaque privacy shielding are preserved. `ui/LaunchGateView.kt:167`, `:199`; `ui/WalletPrivacy.kt:23`. |
| Alerts / sheets / native pickers | Long dialog text could exceed short windows; sheets/pickers needed explicit acceptance. | Alert text scrolls and actions stay in Material's action region. Full-window dialog coordinates reserve one unobscured pane. Sheets cap at 640 dp; fields/handles stay beside a vertical hinge, and their fixed maximum height keeps them below a horizontal hinge. Native picker configuration/window bounds stay inside a pane. Privacy covers remain mounted. `ui/WalletPrivacy.kt:37`, `:59`; recurring picker above. |

The rail and pane decisions use [Android window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes), not device names. Separating hinges conservatively use one unobscured pane; flat, non-occluding creases keep the full window. This preserves the existing History/Map split on ordinary wide windows without placing fields or scannable codes across a physical divider.

## Device evidence (2026-10-20–21)

All AVDs were created for this task using `system-images;android-35;google_apis;arm64-v8a`, launched with explicit ports, `-no-snapshot -no-audio`, and used one at a time after initial boot setup. A disposable wallet was created through onboarding on each device. No funding, payment, exit or recurring schedule was submitted to the regtest stack. Container lifecycle was never controlled.

The original regtest debug APK and capture APK were preserved separately before production edits were installed. Baseline captures continued against that original binary while source edits were prepared. PNGs stayed under `/private/tmp/noah-adaptive-android-shots`, outside git. Revealed recovery phrases were never captured; the gallery skips restore/phrase captures and asserts secure flags instead. Initial blank-restore captures contained no entered words.

| AVD / posture | Pixel geometry | Before | After |
| --- | --- | --- | --- |
| `NoahAdaptiveTablet5592`, landscape | 2560×1600 | Full-window forms and bottom bar; large QR pushed actions down. | 640 dp forms, rail, bounded square QR, readable Settings and existing detail panes. |
| Same tablet, portrait | 1600×2560 | Wide input rows and buttons. | Readable forms/controls, rail, retained drafts; scrolling preserved. |
| `NoahAdaptiveFold5594`, open | 2208×1840 | Stretched content and fixed split thresholds. | Readable body, rail, shared pane decisions. |
| Same Fold, closed | 1080×2092 | Compact bodies; unsaved editor/review state lost on recreation. | Compact navigation and restored editor/review state. |
| Same Fold, half-open | 2208×1840 | No separating-fold reservation. | Unobscured pane reserved; short content remains scrollable. |
| `NoahAdaptivePhone5596`, landscape | 2400×1080 | QR codes clipped; fixed Send/footer stacks left a tiny viewport; Map canvas collapsed. | Rail frees height, complete square codes, scrollable Send actions and short Map controls. |

Baseline gallery: 35 nonsecret screens per configuration. Final gallery: 40, adding scanner, Receive amount-sheet, explicit debug tools, a synthetic populated History detail and a selected bundled Map merchant. Synthetic Send review/receipt captures are separate and span the rotation test's two orientations. The gallery traverses every Settings destination except the revealed phrase, plus Home, Receive, QR hub, Send, History, Map, onboarding, recurring editor, confirmation, a long alert and lock cover. It uses a static screen title and inert navigation callbacks around individual page fixtures; Home additionally captures the real app shell. Fixture chrome is not a navigation end-to-end test.

Capture geometry is recorded per image. An early closed-Fold baseline process interruption/startup race and an early after-gallery orientation mismatch were excluded and rerun. The closed-Fold baseline rerun used a temporary capture-only class with startup waiting against the preserved original app. The tablet portrait baseline was also rerun after three invalid capture files were found. The temporary class was removed and the final capture APK restored. A picker test initially lacked accessibility view IDs; its final test enables that service flag. Early timing/driver failures are not counted as successful acceptance. Late visual checks exposed a sheet crossing the vertical hinge and an alert crossing the horizontal hinge; targeted tests reproduced both, and the final tests/captures include their fixes. A sizing loop during the horizontal-sheet experiment was replaced by a fixed bottom-pane height.

ZXing independently decodes the screenshot pixels, without printing QR payloads. The baseline phone landscape Receive and QR hub captures decoded **0/2**; final captures decoded **2/2**, both with measured finder-point aspect ratio **1.000**. The final QR test also decodes the actual square Compose raster with ZXing PURE_BARCODE mode. Tablet QR-hub codes require the pure-code detector (the general finder did not detect that raster size); the raster decodes successfully. This is pixel verification, not a real-camera claim.

## Verification

- Focused JVM tests: window class/pane/font-scale decisions, QR bounds, separating/edge hinges, existing modal privacy regressions and all four camera-frame rotations.
- Compose instrumentation: Receive amount/note restoration and reachable Save; recurring label/recipient/amount/interval/end-condition restoration; stable content across rail/bar resize; native date/time confirmation bounds and reopening with the selected date; secure phrase recreation; in-memory restore input retention plus background clearing; actual Send screen review/submission/receipt retention with one fake payment call. Fold acceptance additionally changes live posture during review. Five further tests decode the actual square QR raster, verify the feedback crop dialog has one reachable scroll surface, keep the compact QR hub fully visible, and assert that sheet fields/actions and alert text/actions avoid reported hinges. Horizontal-hinge checks use physical screen bounds for the alert, and secure native-picker action bounds.
- `bun apps/tools/translations.mjs --check`: clean; no new production strings.
- All four implementation commits ran the hooks normally: RN lint, **148 tests / 24 files**, typecheck, and Rust formatting passed. No hooks were bypassed.

Required final command, run once after the final source changes:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

The first full pass completed in 2m 41s. Visual/hinge checks then found and fixed sheet/alert placement and compact QR-hub bounds, so the required command was rerun once after those final source changes. Final result: **passed in 1m 56s**. **3,621 JVM tests / 152 suites**, zero failures, errors or skips; APK assembled and instrumentation Kotlin compiled. The capture-only layout latch was also compiled and packaged successfully. Final device results:

| Device | Final state tests | Gallery captures |
| --- | --- | --- |
| Pixel Tablet | 12/12; real rotation during Send review/submission and receipt recreation | 40 landscape + 40 portrait |
| Pixel Fold | 12/12, including live closed/open transitions during Send review; 3/3 modal tests with a vertical hinge and 3/3 with a horizontal hinge | 40 open + 40 closed + 40 half-open |
| Pixel 8 landscape | 12/12; real rotation during Send review/submission and receipt recreation | 40 landscape |

That is **36 state-test passes**, **6 additional hinge-test passes**, and **6 successful gallery runs** on the final app. All **210 baseline / 240 final gallery PNGs** passed signature, dimensions and CRC integrity checks. Old duplicate Send fixture images and invalid/interrupted captures were excluded; receipts/reviews have their own fresh state-test captures.

Live tablet freeform resize also retained the actual 500-sat Send draft and selected tab through **730 → 430 → 730 dp** widths, switching rail/bar without replacing the screen. The real Receive payment-details sheet was opened through app navigation: phone landscape scroll reached Ark/on-chain copy actions, and the tablet sheet remained open through landscape → portrait. The driver failed one transient snapshot after tablet rotation; the actual retained sheet was verified from its screenshot, not that failed snapshot.

Final QR pixels: **12/12 decoded** with the pure-code detector, every measured raster exactly square (439–793 px per side). The general detector decoded **10/12**; both tablet QR-hub codes required pure mode. No payloads were printed.

The real Fold Receive sheet also retained its open intent through open → closed → half-open, keeping the narrow form beside the hinge. A gesture inside that pane reached the on-chain copy action; the driver's default center-window gesture had landed outside the pane. Cleanup is recorded below.

## Cleanup

Confirmed each owned name with `adb -s emulator-N emu avd name` before shutdown, waited for its serial and QEMU process to disappear, then deleted `NoahAdaptiveTablet5592`, `NoahAdaptiveFold5594` and `NoahAdaptivePhone5596` with `avdmanager`. Their AVD directories/INI files are absent. Deleted `/private/tmp/noah-adaptive-android-shots`, contact sheets, temporary screenshot crops and preserved baseline/final APK copies. No images are committed. No owned emulator or build/test runner remains. Protected devices, simulators, Docker and the other agents' work were untouched.

## Limits

- Layout captures use unfunded wallets. Populated Send confirmation/receipt use an in-memory fake; no real payment submission or funded-wallet acceptance is claimed. VTXO/exit empty states and their shared scrolling/detail code were inspected; History detail uses a public synthetic record. Populated wallet records were not generated by changing the read-only stack.
- Camera preview/display rotation and the existing four-orientation decoder test are covered. Emulator imagery does not establish real-camera scanning quality.
- Native date/time pickers are secure windows; their button bounds/state are tested without capturing their protected pixels. Recovery reveal is verified by secure-flag/state tests, never screenshots.
- Recovery input is intentionally not process-restored. Background/screen removal clears it; only configuration recreation retains its in-memory input. Existing quote expiry, wallet admission, background invalidation and payment journals remain authoritative.

## Commits

- `b536f227` — adaptive shell, content/QR bounds, hinge avoidance, scanner display orientation, layout JVM tests and opt-in capture gallery.
- `6b5b5d05` — presentation state restoration, native picker state, recovery input in memory, and seven Compose restoration/safety tests.
- `9c3a752e` — native picker pane bounds, actual-pixel QR rendering, crop scroll regression and populated fixtures.
- `cc0709c1` — complete compact QR hub, modal constraints for vertical/horizontal folds, and three additional modal/QR tests.
- The documentation/checklist commit contains this report and closes the adaptive-layout item after the completed iOS half.
