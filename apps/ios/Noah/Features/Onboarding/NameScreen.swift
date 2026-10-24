import SwiftUI

/// "Pick a name": a large inline `name@domain`, a debounced availability
/// hint, and suggestions when it's taken.
struct NameScreen: View {
    let layout: OnboardingLayout
    @Bindable var name: NameAvailability
    let domain: String
    let saving: Bool
    let error: String?
    let haptics: OnboardingHaptics
    let onContinue: () -> Void
    let onSkip: () -> Void

    @FocusState private var focused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.locale) private var locale

    var body: some View {
        let column = OnboardingColumn(wide: layout.wide, keyboard: focused)
        OnboardingPanes(wide: layout.wide, column: .leadingFits, sideInsets: (layout.insets.leading, layout.insets.trailing)) {
            OnboardingHeadline(title: Text("Pick a name"), subtitle: Text("This is how people pay you."), large: layout.wide)
                .onboardingHeadlinePane(column)
            FitOrScroll {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0).frame(maxHeight: column.lead)
                    NameHandleField(name: name, domain: domain, focused: $focused, label: "Pick a name") { if name.canContinue { onContinue() } }
                        .padding(.top, column.firstTop(compact: NoahTokens.Space.xxxl)).onboardingAppear(2)
                    NameStatusLine(status: name.status).padding(.top, 14).onboardingAppear(3)
                    NameSuggestionChips(name: name, haptics: haptics).padding(.top, 14)
                    if let error {
                        Text(LocalizedStringKey(error))
                            .noahText(NoahTokens.Typography.sub)
                            .foregroundStyle(NoahTokens.Colors.failure)
                            .padding(.top, NoahTokens.Space.s)
                    }
                    Spacer(minLength: NoahTokens.Space.screen).frame(maxHeight: column.gap)
                    Button(action: onContinue) {
                        ZStack {
                            Text("Continue").opacity(saving ? 0 : 1)
                            if saving { ProgressView().tint(NoahTokens.Colors.background) }
                        }
                    }
                    .buttonStyle(OnboardingPrimaryButtonStyle())
                    .disabled(!name.canContinue && !saving)
                    .allowsHitTesting(!saving)
                    .accessibilityIdentifier("onboarding-name-continue")
                    .padding(.bottom, NoahTokens.Space.l)
                    Spacer(minLength: 0).frame(maxHeight: column.trail)
                }
                .onboardingPane()
            }
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: layout.wide && focused)
        .overlay(alignment: .topTrailing) {
            Button("Skip", action: onSkip)
                .buttonStyle(OnboardingBarButtonStyle())
                .disabled(saving)
                .accessibilityIdentifier("onboarding-name-skip")
                .padding(.trailing, 12)
                .padding(.top, 4)
        }
        .onChange(of: name.text) { _, _ in name.textChanged() }
        .onChange(of: name.status) { _, status in
            switch status {
            case .available: haptics.play(.selection)
            case .taken: haptics.play(.warning)
            default: break
            }
            if status == .available || status == .taken {
                AccessibilityNotification.Announcement(L10n.text(status.text, locale: locale.identifier)).post()
            }
        }
        .task {
            try? await Task.sleep(for: .milliseconds(380))
            focused = true
        }
    }
}

/// Grey for hints, a pulsing orange dot while checking, solid orange when
/// available, hollow when taken.
struct AvailabilityDot: View {
    let status: NameAvailability.Status
    @State private var pulse = false
    @State private var pop = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            switch status {
            case .taken:
                Circle().strokeBorder(NoahTokens.Colors.muted, lineWidth: 1.5)
            case .checking, .available:
                Circle().fill(NoahTokens.Colors.orange)
                    .opacity(status == .checking && pulse ? 0.25 : 1)
            case .hint, .tooShort:
                Circle().fill(NoahTokens.Colors.hairline)
            }
        }
        .frame(width: 8, height: 8)
        .scaleEffect(pop ? 2 : 1)
        .accessibilityHidden(true)
        .onChange(of: status, initial: true) { _, status in
            pulse = false
            if status == .checking {
                withAnimation(.easeInOut(duration: 0.4).repeatForever(autoreverses: true)) { pulse = true }
            }
            if status == .available && !reduceMotion {
                pop = true
                withAnimation(NoahTokens.Motion.bouncy) { pop = false }
            }
        }
    }
}

/// The large inline `name@domain` field: as wide as what's typed, so the domain follows it.
/// Onboarding's "Pick a name" and Settings → Lightning address.
struct NameHandleField: View {
    @Bindable var name: NameAvailability
    let domain: String
    var focused: FocusState<Bool>.Binding
    let label: LocalizedStringKey
    let onSubmit: () -> Void
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            WrapLayout(spacing: 3, lineSpacing: 0) {
                Text(verbatim: name.text.isEmpty ? L10n.text("name", locale: locale.identifier) : name.text)
                    .noahText(NoahTokens.Typography.handle)
                    .lineLimit(1)
                    .padding(.trailing, 3)
                    .hidden()
                    .overlay(alignment: .leading) {
                        TextField("name", text: $name.text)
                            .noahText(NoahTokens.Typography.handle)
                            .foregroundStyle(NoahTokens.Colors.foreground)
                            .tint(NoahTokens.Colors.orange)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.asciiCapable)
                            .submitLabel(.continue)
                            .focused(focused)
                            .onSubmit(onSubmit)
                            .accessibilityLabel(Text(label))
                            .accessibilityValue(Text(verbatim: name.text.isEmpty ? "" : "\(name.text)@\(domain)"))
                            .accessibilityIdentifier("lightning-username-input")
                    }
                Text(verbatim: "@\(domain)")
                    .noahText(NoahTokens.Typography.label)
                    .foregroundStyle(NoahTokens.Colors.muted)
                    .accessibilityHidden(true)
            }
            OnboardingUnderline(focused: focused.wrappedValue)
        }
        .contentShape(Rectangle())
        .onTapGesture { focused.wrappedValue = true }
    }
}

extension NameAvailability.Status {
    var text: String {
        switch self {
        case .hint: "Letters and numbers"
        case .tooShort: "At least 3 characters"
        case .checking: "Checking"
        case .available: "Available"
        case .taken: "Taken"
        }
    }
}

/// The availability dot and its word; Settings puts its own message there instead when it has one.
struct NameStatusLine: View {
    let status: NameAvailability.Status
    var message: String?
    var failure = false

    var body: some View {
        HStack(spacing: NoahTokens.Space.s) {
            if failure {
                Circle().fill(NoahTokens.Colors.failure).frame(width: 8, height: 8).accessibilityHidden(true)
            } else {
                AvailabilityDot(status: status)
            }
            Group {
                if let message { Text(verbatim: message) } else { Text(LocalizedStringKey(status.text)) }
            }
            .noahText(NoahTokens.Typography.sub)
            .foregroundStyle(failure ? NoahTokens.Colors.failure : status == .available ? NoahTokens.Colors.foreground : NoahTokens.Colors.muted)
            .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 22)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("onboarding-name-status")
    }
}

/// Suggestions when a name is taken.
struct NameSuggestionChips: View {
    let name: NameAvailability
    let haptics: OnboardingHaptics

    var body: some View {
        GlassGroup(spacing: NoahTokens.Space.s) {
            WrapLayout(spacing: NoahTokens.Space.s, lineSpacing: 0) {
                ForEach(Array(name.suggestions.enumerated()), id: \.element) { index, suggestion in
                    Button(suggestion) {
                        name.use(suggestion)
                        haptics.play(.selection)
                    }
                    .buttonStyle(OnboardingChipStyle())
                    .onboardingAppear(index, base: 0, distance: 8)
                }
            }
        }
        .frame(minHeight: NoahTokens.Size.hitTarget, alignment: .topLeading)
    }
}
