# Noah native UI stack — 2026-10-19

**TL;DR:** Use **SwiftUI, iOS 18 minimum, Swift 6 mode**, with system Liquid Glass on iOS 26+ and material fallbacks. Standardize keyboard handling with native scroll views, focus, safe-area insets and sheets; **do not install IQKeyboardManager globally**. Use **Compose + stable Material 3 + Navigation 3**, retaining Android **minSdk 24**. Reuse existing native widgets. Keep MapLibre and Google-independent QR scanning.

## 1. iOS architecture and minimum version

Use `TabView` with a `NavigationStack` per tab, typed routes, `@MainActor @Observable` presentation models, and actors around wallet/network coordination. Enable Swift 6 language mode from inception; it enables complete concurrency checking independently of deployment target. Keep wallet operations outside views. [Swift concurrency](https://www.swift.org/migration/)

**Choose iOS 18.** iOS 17 already supplies Observation; 18 adds typed `Tab`, tab customization, `MeshGradient`, zoom navigation transitions, and ControlWidget. SwiftData adds history, indexes and compound uniqueness, but these do not justify replacing the wallet database. These are conveniences, not prerequisites for Liquid Glass. **`@Entry` is an Xcode 16-era macro that back-deploys to iOS 13, not an iOS 18 requirement.** [Observation](https://developer.apple.com/documentation/observation/observable()), [SwiftUI additions](https://developer.apple.com/videos/play/wwdc2024/10144/), [Entry availability](https://developer.apple.com/documentation/swiftui/entry()), [SwiftData](https://developer.apple.com/documentation/updates/swiftdata)

iOS 18 drops no iPhone hardware supported by 17: XR/XS and SE 2 remain eligible. It excludes people who have not upgraded; iPad compatibility differs. Apple's latest published sample, **June 7, 2026**, is 79% iOS 26, 14% iOS 18, 7% earlier across App Store-active iPhones. Thus 18 excludes **7% of that sample**; 17 excludes an unknown subset of those 7%. Recent-four-year iPhones: 3% below 18. These are not October or Noah-specific figures. Reconsider 17 only if Noah telemetry demonstrates meaningful need. [Hardware](https://support.apple.com/en-asia/guide/iphone/-iphe3fa5df43/ios), [Apple adoption](https://developer.apple.com/support/app-store/)

## 2. Liquid Glass

Building with SDK 26+ and running on iOS 26+ refreshes standard navigation/tab bars, toolbars, controls and sheet presentation automatically. Partial-height sheets get glass; expanded sheets adapt. Remove custom backgrounds that obscure system styling. Older systems retain their native appearance. [Adoption](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)

Use `glassEffect(_:in:)`, `.regular.tint(...).interactive()`, `GlassEffectContainer`, `glassEffectID`, `glassEffectUnion`, `glassEffectTransition`, and `.buttonStyle(.glass/.glassProminent)` for custom controls. Use `.tabBarMinimizeBehavior(.onScrollDown)`, `.tabViewBottomAccessory`, toolbar grouping/`ToolbarSpacer`, `.backgroundExtensionEffect()` and scroll-edge effects where appropriate. `Tab(role: .search)` exists since 18; its integrated search presentation changes in 26. Pair it with `.searchable`. [Custom glass](https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views), [System UI examples](https://developer.apple.com/videos/play/wwdc2025/323/)

Keep compatibility in one small modifier, applied after padding/sizing:

```swift
struct GlassCompat: ViewModifier {
    @Environment(\.accessibilityReduceTransparency) var reduceTransparency
    @ViewBuilder func body(content: Content) -> some View {
        if reduceTransparency {
            content.background(Color(uiColor: .systemBackground), in: Capsule())
        } else if #available(iOS 26, *) {
            content.glassEffect(.regular, in: Capsule())
        } else {
            content.background(.regularMaterial, in: Capsule())
        }
    }
}
```

Similarly gate glass button styles, falling back to `.bordered`/`.borderedProminent`. Keep glass on navigation/actions, with readable opaque payment details. Group nearby glass for rendering efficiency; avoid stacked glass and dozens of effects. Test Reduce Transparency, Increase Contrast, Reduce Motion and busy map backgrounds. **Do not use `UIDesignRequiresCompatibility` as a strategy:** it is temporary and ignored when building against SDK 27+. [Materials](https://developer.apple.com/design/human-interface-guidelines/materials), [Compatibility key](https://developer.apple.com/documentation/bundleresources/information-property-list/uidesignrequirescompatibility)

## 3. One keyboard convention

The remembered library is probably **IQKeyboardManagerSwift 8.0.3**. It targets UIKit text fields/views; its historical SwiftUI issue documents incomplete previous/next support. Current comprehensive SwiftUI support is **unverified**. Swift-language implementation does not imply SwiftUI integration. [Release](https://github.com/hackiftekhar/IQKeyboardManager/releases/tag/8.0.3), [Maintainer discussion](https://github.com/hackiftekhar/IQKeyboardManager/issues/1606)

**Standardize this exact policy:**

1. Every text-entry screen/sheet uses a flexible `ScrollView` or `Form`; retain keyboard safe-area avoidance. Never add global keyboard-height offsets or ignore the keyboard safe area on interactive content.
2. One optional enum `@FocusState` per form; unique `.focused` bindings; `.submitLabel`/`.onSubmit` advance fields; a `ToolbarItemGroup(placement: .keyboard)` provides Previous/Next/Done by setting focus or `nil`.
3. Apply `.scrollDismissesKeyboard(.interactively)` and `.safeAreaInset(edge: .bottom)` for the primary action, reserving space above the keyboard. Use focus-driven `ScrollViewReader.scrollTo` only where native scrolling demonstrably fails.
4. Use `.sheet` with `.presentationDetents([.medium, .large], selection: ...)`; select `.large` before focusing an editor. Keep content scrollable, clear focus on dismissal, and avoid fixed keyboard-relative heights.
5. Preserve Noah's **custom amount keypad as ordinary in-layout buttons** below an amount display, with text focus cleared. It must not simultaneously summon a decimal keyboard. Preserve locale separators, integer-satoshi arithmetic, accessible labels, paste and hardware-keyboard entry. Sources: [Safe areas](https://developer.apple.com/documentation/swiftui/safearearegions/keyboard), [FocusState](https://developer.apple.com/documentation/swiftui/focusstate/), [Dismissal](https://developer.apple.com/documentation/swiftui/view/scrolldismisseskeyboard(_:))

For unavoidable UIKit editors, constrain to `UIKeyboardLayoutGuide`; use tracking constraints and `followsUndockedKeyboard` for floating iPad keyboards. Validate small screens, sheet reopening, rotation, large text and hardware keyboards on devices. [UIKit guide](https://developer.apple.com/documentation/uikit/adjusting-your-layout-with-keyboard-layout-guide)

## 4. RN → native replacements

Inspected `client/package.json` and actual `client/src` imports, including `BtcMapScreen`, `AppBottomSheet`, scanner, keypad, storage, sharing and animation code. Recommendations below list **iOS / Android**:

- **React Navigation/native tabs:** `NavigationStack` + `TabView` / Navigation 3 + Material `NavigationBar`.
- **@swmansion/react-native-bottom-sheet:** native `.sheet`/presentation detents / Material 3 `ModalBottomSheet`.
- **MapLibre:** retain native SDKs **iOS 6.31.0 / Android 13.7.1**, wrapped in `UIViewRepresentable`/`AndroidView` as needed. Preserve BtcMap's OpenFreeMap styles, GeoJSON and attribution. Location: Core Location / framework `LocationManager`, avoiding mandatory Google fused location. [Releases](https://github.com/maplibre/maplibre-native/releases)
- **Vision Camera:** prefer AVFoundation `AVCaptureMetadataOutput` for Noah's custom QR/EAN-13 overlay. VisionKit `DataScannerViewController` is an easier stock alternative, but check `isSupported` and `isAvailable`. Android: **CameraX 1.6.2 + ZXing core 3.5.4**. Bundled ML Kit **17.3.0** is an alternative without Play-delivered model downloads; verify on AOSP. Avoid Google Code Scanner/Play Services as mandatory dependencies. [AVFoundation](https://developer.apple.com/documentation/avfoundation/machine-readable-object-types), [VisionKit](https://developer.apple.com/documentation/visionkit/scanning-data-with-the-camera), [CameraX](https://developer.android.com/jetpack/androidx/releases/camera), [ML Kit](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- **QR generation:** Core Image `CIFilter.qrCodeGenerator()` / ZXing encoder; retain quiet zones and crisp scaling. [Core Image](https://developer.apple.com/documentation/coreimage/cifilter-swift.class/qrcodegenerator()), [ZXing 3.5.4](https://github.com/zxing/zxing/releases/tag/zxing-3.5.4)
- **Share:** `ShareLink`, `UIActivityViewController` for richer exports / Android Sharesheet with `ACTION_SEND` and `FileProvider`.
- **Haptics:** `.sensoryFeedback`/`UIFeedbackGenerator` / Compose `LocalHapticFeedback`.
- **Biometrics/keychain:** `LAContext` + Keychain / `BiometricPrompt` + Android Keystore. Authentication gates and encrypted secret storage remain separate responsibilities.
- **Clipboard:** `UIPasteboard`/`PasteButton` / `ClipboardManager`; **image picker:** `PhotosPicker` / Activity Result `PickVisualMedia`, retaining document-picker fallback on older/AOSP devices. [PhotosPicker](https://developer.apple.com/documentation/photosui/photospicker)
- **Background tasks:** `BGTaskScheduler` / WorkManager; retain cancellation and wallet coordination. Neither promises exact payment timing. Keep APNs and existing UnifiedPush support alongside optional FCM. [Apple scheduling limits](https://developer.apple.com/documentation/backgroundtasks/bgtaskrequest/earliestbegindate)
- **Reanimated/gestures/SVG:** SwiftUI animations, gestures, shapes / Compose animation, gestures, vectors. **No Lottie dependency or usage found**; add none.
- **Sentry/logging:** **sentry-cocoa 9.30.2** + `Logger`/OSLog / **sentry-android 8.61.0** + `android.util.Log` behind Noah's redaction facade. Preserve exportable diagnostics without secrets. [Cocoa](https://github.com/getsentry/sentry-cocoa/releases/tag/9.30.2), [Android](https://github.com/getsentry/sentry-java/releases/tag/8.61.0)
- **MMKV/Zustand:** preferences → `UserDefaults`/`@AppStorage` / DataStore; durable records → existing wallet database, SQLite/Room as appropriate. Explicitly migrate `noah-wallet-storage`; retain native MMKV temporarily if needed. Never move secrets into preference stores. [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- **ky/HTTP:** `URLSession` + Codable / **OkHttp 5.5.0** + kotlinx.serialization; Ktor adds little without shared Kotlin networking. [Published OkHttp versions](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml)
- **TanStack Query:** repositories own cached values, keyed in-flight deduplication, freshness and explicit mutation invalidation; Swift observable state/async tasks / ViewModel + StateFlow + coroutines. Preserve retries, cancellation and foreground refresh; avoid recreating a generic query framework. FlashList → `List`/lazy stacks / `LazyColumn`.

**Widgets:** nearly reusable as-is. `client/ios/SharedWidget/NoahBalanceWidget.swift` uses WidgetKit and app-group `UserDefaults["widgetBalance"]`; Android providers use RemoteViews and per-variant SharedPreferences. Replace Nitro writers with native calls; preserve payload keys, widget kinds/provider class names, resources, extension IDs and reload/update signals. A Compose app does not require rewriting widgets in Glance.

**Translations:** actual catalogs are English source plus **es/ja/ko/sv**, configured in `client/gt.config.json`. `_gt/*.json` mixes hash-keyed strings and serialized React trees. Recover source/context→hash associations using the pinned GT extractor/export; hashes cannot recover English. Convert through a neutral manifest with semantic keys and typed placeholders, flatten rich text deliberately, translate plural branches, then generate `.xcstrings` and `values-*/strings.xml`/`<plurals>`. Validate counts, placeholder parity and all five locales. **No turnkey GT→native exporter verified.** [String Catalogs](https://developer.apple.com/documentation/xcode/localizing-and-varying-text-with-a-string-catalog), [Android resources](https://developer.android.com/guide/topics/resources/string-resource)

## 5. Android baseline

Use **Compose BOM 2026.09.00**, **Material 3 stable 1.4.0**, **Navigation 3 stable 1.2.0**. Expressive APIs remain on **1.5.0-beta01**; use expressive visual choices without adopting beta-only components. Navigation 2 **2.10.2** is in maintenance mode. [BOM](https://developer.android.com/develop/ui/compose/bom), [Material](https://developer.android.com/jetpack/androidx/releases/compose-material3), [Navigation 3](https://developer.android.com/jetpack/androidx/releases/navigation3), [Navigation 2](https://developer.android.com/jetpack/androidx/releases/navigation)

Retain **minSdk 24**: app Gradle inherits Expo's root setting, sourced from the installed RN version catalog. Raising to 26 excludes Android 7.x without an established requirement. Consume Scaffold/system/IME insets once; use scrollable forms and `imePadding`. Adopt predictive back through Navigation 3. API-36 targeting removes edge-to-edge opt-out; do not intercept back globally. [Android behavior](https://developer.android.com/about/versions/16/behavior-changes-16), [Insets](https://developer.android.com/develop/ui/compose/system/insets-ui)

## 6. Monorepo/build setup

Use `apps/ios/{App,Features,Services,Widgets,Config}` and `apps/android/{app,gradle}`; keep `client/` during migration. Start with folders, extracting modules only at meaningful boundaries. Choose **XcodeGen 2.46.0** + SwiftPM: declarative schemes/targets suit three variants and extensions. Plain `.xcodeproj` is viable; Tuist's broader machinery is unnecessary here. Android: Kotlin Gradle DSL, committed wrapper, `gradle/libs.versions.toml`. [XcodeGen](https://github.com/yonaskolb/XcodeGen/releases/tag/2.46.0), [Version catalogs](https://developer.android.com/build/migrate-to-catalogs)

Preserve schemes `Noah`, `Noah-Signet`, `Noah-Regtest`, xcconfigs, and Android mainnet/signet/regtest flavors. Keep `com.noahwallet.{mainnet,signet,regtest}`, corresponding `group.com.noahwallet.*` app groups, exact widget extension identifiers, Android provider identities, signing, push entitlements and URL schemes. Current app deployment target is 16.4; widget targets are already 17.6. Same identifiers alone do not migrate wallet files or Keychain services: test an installed-app upgrade.

**Limits:** Source/release checks only; no builds, installs or device tests. Examples are uncompiled. October-specific adoption and Noah's excluded-user percentages remain unverified. Repository unchanged.
