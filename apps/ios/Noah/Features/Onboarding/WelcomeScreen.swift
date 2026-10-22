import SwiftUI

/// Welcome: the tree grows behind it (`TreeSceneView`), the wordmark rises,
/// and the language control replaces the old language step.
struct WelcomeScreen: View {
    let layout: OnboardingLayout
    /// A sheet covers the column; the wordmark fades rather than peek out cut in half.
    var sheetUp = false
    let languageCode: String
    let onLanguage: () -> Void
    let onCreate: () -> Void
    let onRestore: () -> Void

    var body: some View {
        OnboardingPanes(wide: layout.wide, column: .leading(layout.chainY(.welcome) + 30),
                        sideInsets: (layout.insets.leading, layout.insets.trailing)) {
            Color.clear
            FitOrScroll {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0).frame(maxHeight: layout.wide ? .infinity : 14)
                    VStack(alignment: .leading, spacing: NoahTokens.Space.s) {
                        NoahWordmark()
                        Text("Bitcoin, simply.")
                            .noahText(NoahTokens.Typography.body)
                            .foregroundStyle(NoahTokens.Colors.muted)
                            .onboardingAppear(0, base: 1.5, distance: 10)
                    }
                    .opacity(sheetUp ? 0 : 1)
                    .animation(NoahTokens.Motion.snappy, value: sheetUp)
                    Spacer(minLength: NoahTokens.Space.screen)
                        .frame(maxHeight: layout.wide ? NoahTokens.Space.xxxl : .infinity)
                    VStack(spacing: NoahTokens.Space.s) {
                        Button("Create wallet", action: onCreate)
                            .buttonStyle(OnboardingPrimaryButtonStyle())
                            .accessibilityIdentifier("onboarding-create")
                            .onboardingAppear(6)
                        Button("I already have a wallet", action: onRestore)
                            .buttonStyle(OnboardingSecondaryButtonStyle())
                            .accessibilityIdentifier("onboarding-restore")
                            .onboardingAppear(7)
                    }
                    Spacer(minLength: 0).frame(maxHeight: layout.wide ? .infinity : 0)
                }
                .padding(.bottom, layout.insets.bottom > 0 ? 0 : NoahTokens.Space.l)
                .onboardingPane()
            }
        }
        .overlay(alignment: .topTrailing) {
            Button(action: onLanguage) {
                HStack(spacing: 6) {
                    Image(systemName: "globe").imageScale(.medium)
                    Text(verbatim: languageCode.uppercased())
                }
            }
            .buttonStyle(OnboardingBarButtonStyle())
            .accessibilityLabel(Text("Language"))
            .accessibilityValue(Text(verbatim: LanguageSheet.name(languageCode)))
            .accessibilityIdentifier("onboarding-language")
            .padding(.trailing, 12)
            .padding(.top, 4)
        }
    }
}

/// "Noah", its letters rising with the bouncy spring after the tree grows.
struct NoahWordmark: View {
    var delay = 1.2
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array("Noah".enumerated()), id: \.offset) { index, letter in
                Text(verbatim: String(letter))
                    .accessibilityHidden(true)
                    .opacity(shown ? 1 : 0)
                    .offset(y: shown || reduceMotion ? 0 : 52)
                    .animation(reduceMotion ? NoahTokens.Motion.reduceMotionFade.delay(delay * 0.3)
                                            : NoahTokens.Motion.bouncy.delay(delay + Double(index) * 0.065), value: shown)
            }
        }
        .noahText(NoahTokens.Typography.wordmark)
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .foregroundStyle(NoahTokens.Colors.foreground)
        .lineLimit(1)
        .minimumScaleFactor(0.6)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "Noah"))
        .accessibilityAddTraits(.isHeader)
        .onAppear { shown = true }
    }
}

struct LanguageSheet: View {
    let selected: String
    let select: (String) -> Void

    static func name(_ code: String) -> String {
        Locale(identifier: code).localizedString(forLanguageCode: code)?.localizedCapitalized ?? code
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Language")
                .noahText(NoahTokens.Typography.sheetTitle)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .accessibilityAddTraits(.isHeader)
                .padding(.bottom, 6)
            ForEach(Array(LegacyLocale.supported.enumerated()), id: \.element) { index, code in
                Button { select(code) } label: {
                    HStack {
                        Text(verbatim: Self.name(code)).noahText(NoahTokens.Typography.body)
                        Spacer()
                        Image(systemName: "checkmark")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(NoahTokens.Colors.orange)
                            .opacity(code == selected ? 1 : 0)
                    }
                    .foregroundStyle(NoahTokens.Colors.foreground)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(code == selected ? .isSelected : [])
                .accessibilityIdentifier("preference-\(code)")
                if index < LegacyLocale.supported.count - 1 {
                    Rectangle().fill(NoahTokens.Colors.hairline).frame(height: 1)
                }
            }
        }
    }
}

struct BetaSheet: View {
    let busy: Bool
    let error: String?
    let onConfirm: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Beta")
                .noahText(NoahTokens.Typography.caption)
                .bold()
                .foregroundStyle(NoahTokens.Colors.orangeText)
                .padding(.horizontal, 11)
                .frame(minHeight: 26)
                .background(NoahTokens.Colors.orangeTint, in: Capsule())
                .padding(.bottom, 14)
            Text("Before you start")
                .noahText(NoahTokens.Typography.sheetTitle)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .accessibilityAddTraits(.isHeader)
            Text("Noah is in beta. Only keep what you’re comfortable losing.")
                .noahText(NoahTokens.Typography.body)
                .foregroundStyle(NoahTokens.Colors.muted)
                .padding(.top, NoahTokens.Space.s)
            if let error {
                Text(LocalizedStringKey(error))
                    .noahText(NoahTokens.Typography.sub)
                    .foregroundStyle(NoahTokens.Colors.failure)
                    .padding(.top, NoahTokens.Space.m)
            }
            Button(action: onConfirm) {
                ZStack {
                    Text("Got it").opacity(busy ? 0 : 1)
                    if busy { ProgressView().tint(NoahTokens.Colors.background) }
                }
            }
            .buttonStyle(OnboardingPrimaryButtonStyle())
            .allowsHitTesting(!busy)
            .accessibilityIdentifier("onboarding-beta-confirm")
            .padding(.top, NoahTokens.Space.screen)
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}
