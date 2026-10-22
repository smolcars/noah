import SwiftUI

/// Blocks the wallet while notifications are off, with the onboarding
/// Notifications step's required state: the conveyor frozen and grey.
/// AppModel re-checks the permission whenever the app returns to the foreground.
struct PushNotificationsRequiredView: View {
    @Environment(\.openURL) private var openURL
    @State private var layout = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @State private var scene = TreeScene.State(mode: .conveyor, start: .now, frozenAt: 1)

    var body: some View {
        ZStack {
            NoahTokens.Colors.background.ignoresSafeArea()
            TreeSceneView(state: scene, visible: true, placement: layout.scene(.conveyor))
                .ignoresSafeArea()
            NotificationsScreen(layout: layout, required: true, busy: false) {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
        }
        .onboardingWindow($layout)
    }
}

/// "Keep Noah awake", or once denied "Notifications are required". The
/// conveyor scene sits behind it, drawn by the caller.
struct NotificationsScreen: View {
    let layout: OnboardingLayout
    let required: Bool
    let busy: Bool
    let action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        OnboardingPanes(wide: layout.wide, column: .leading(layout.chainY(.conveyor) + 46),
                        sideInsets: (layout.insets.leading, layout.insets.trailing)) {
            Color.clear
            FitOrScroll {
                VStack(spacing: 0) {
                    Spacer(minLength: 0).frame(maxHeight: layout.wide ? .infinity : 0)
                    ZStack {
                        if required {
                            OnboardingHeadline(title: Text("Notifications are required"),
                                               subtitle: Text("Without them, Noah can’t receive payments or renew your bitcoin before it expires."),
                                               large: layout.wide, centered: true)
                                .transition(.opacity)
                        } else {
                            OnboardingHeadline(title: Text("Keep Noah awake"),
                                               subtitle: Text("Notifications let Noah receive payments and renew your bitcoin before it expires."),
                                               large: layout.wide, centered: true)
                                .transition(.opacity)
                        }
                    }
                    .animation(NoahTokens.Motion.travel(reduceMotion), value: required)
                    Spacer(minLength: NoahTokens.Space.screen)
                        .frame(maxHeight: layout.wide ? NoahTokens.Space.xxxl : .infinity)
                    Button(action: action) {
                        ZStack {
                            Text(required ? "Open Settings" : "Turn on notifications").opacity(busy ? 0 : 1)
                            if busy { ProgressView().tint(NoahTokens.Colors.background) }
                        }
                    }
                    .buttonStyle(OnboardingPrimaryButtonStyle())
                    .allowsHitTesting(!busy)
                    .accessibilityIdentifier(required ? "notifications-open-settings" : "notifications-enable")
                    .onboardingAppear(2)
                    .padding(.bottom, NoahTokens.Space.l)
                    Spacer(minLength: 0).frame(maxHeight: layout.wide ? .infinity : 0)
                }
                .onboardingPane()
            }
        }
    }
}

extension View {
    /// Keeps `layout` in step with the window: its size and safe area,
    /// ignoring the keyboard so nothing moves when it appears.
    func onboardingWindow(_ layout: Binding<OnboardingLayout>) -> some View {
        modifier(OnboardingWindow(layout: layout))
    }
}

private struct OnboardingWindow: ViewModifier {
    @Binding var layout: OnboardingLayout
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(dynamicTypeSize))
        let textScale = UIFontMetrics(forTextStyle: .body).scaledValue(for: 17, compatibleWith: traits) / 17
        content.background {
            Color.clear
                .ignoresSafeArea(.keyboard)
                .onGeometryChange(for: OnboardingLayout.self) { proxy in
                    let insets = proxy.safeAreaInsets
                    return OnboardingLayout(size: CGSize(width: proxy.size.width + insets.leading + insets.trailing,
                                                         height: proxy.size.height + insets.top + insets.bottom),
                                            insets: insets, textScale: textScale)
                } action: { layout = $0 }
        }
    }
}
