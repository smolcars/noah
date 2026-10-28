import SwiftUI

/// Optional email: address entry, then six code boxes that fold into a
/// check once verified.
struct EmailScreen: View {
    let layout: OnboardingLayout
    @Bindable var email: EmailVerification
    let haptics: OnboardingHaptics
    let onSkip: () -> Void

    @FocusState private var focus: Field?
    @Namespace private var glass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.locale) private var locale

    private enum Field { case email, code }

    private var codeStage: Bool { email.stage != .entry && email.stage != .sending }
    private var column: OnboardingColumn { OnboardingColumn(wide: layout.wide, keyboard: focus != nil) }

    var body: some View {
        OnboardingPanes(wide: layout.wide, column: .leadingFits, sideInsets: (layout.insets.leading, layout.insets.trailing)) {
            headline.onboardingHeadlinePane(column)
            FitOrScroll {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0).frame(maxHeight: column.lead)
                    ZStack(alignment: .topLeading) {
                        if codeStage { codeEntry.transition(stageTransition(incoming: true)) }
                        else { addressEntry.transition(stageTransition(incoming: false)) }
                    }
                    .animation(NoahTokens.Motion.travel(reduceMotion), value: codeStage)
                    Spacer(minLength: NoahTokens.Space.screen).frame(maxHeight: column.gap)
                    if !codeStage { sendButton.padding(.bottom, NoahTokens.Space.l) }
                    Spacer(minLength: 0).frame(maxHeight: column.trail)
                }
                .onboardingPane()
            }
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: layout.wide && focus != nil)
        .overlay(alignment: .topTrailing) {
            Button("Skip", action: onSkip)
                .buttonStyle(OnboardingBarButtonStyle())
                .opacity(email.stage == .verified ? 0 : 1)
                .disabled(email.stage == .verified || email.stage == .sending || email.stage == .verifying)
                .animation(NoahTokens.Motion.snappy, value: email.stage == .verified)
                .accessibilityIdentifier("onboarding-email-skip")
                .padding(.trailing, 12)
                .padding(.top, 4)
        }
        .onChange(of: email.text) { _, _ in email.textChanged() }
        .onChange(of: email.code) { _, _ in Task { await email.codeChanged() } }
        .onChange(of: email.stage) { _, stage in
            switch stage {
            case .code: focus = .code
            case .entry: focus = .email
            case .verified: focus = nil
            default: break
            }
        }
        .task {
            try? await Task.sleep(for: .milliseconds(380))
            if focus == nil && email.stage != .verified { focus = codeStage ? .code : .email }
        }
    }

    private func stageTransition(incoming code: Bool) -> AnyTransition {
        reduceMotion ? .opacity : .asymmetric(insertion: .offset(y: code ? 18 : -18).combined(with: .opacity),
                                              removal: .offset(y: code ? 24 : -24).combined(with: .opacity))
    }

    // MARK: Headline

    private var headline: some View {
        ZStack(alignment: .topLeading) {
            switch email.stage {
            case .entry, .sending:
                OnboardingHeadline(title: Text("Add your email"), subtitle: Text("Only for urgent alerts about your wallet."), large: layout.wide)
                    .transition(stageTransition(incoming: false))
            case .code, .verifying, .wrongCode:
                OnboardingHeadline(title: Text("Check your email"),
                                   subtitle: Text(verbatim: L10n.text("Enter the code we sent to {email}", email.email, locale: locale.identifier)),
                                   large: layout.wide) {
                    Button("Edit") { email.edit() }
                        .buttonStyle(OnboardingBarButtonStyle(muted: false))
                        .disabled(email.stage == .verifying || email.isSending)
                        .accessibilityLabel(Text("Edit email address"))
                        .accessibilityIdentifier("onboarding-email-edit")
                }
                .transition(stageTransition(incoming: true))
            case .verified:
                OnboardingHeadline(title: Text("Email verified"), large: layout.wide)
                    .transition(.opacity)
            }
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: email.stage)
    }

    // MARK: Address

    private var addressEntry: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(spacing: 12) {
                addressField
                OnboardingUnderline(focused: focus == .email)
            }
            .padding(.top, column.firstTop(compact: 36))
            .onboardingAppear(2)
            EmailHints(email: email, haptics: haptics).padding(.top, 14)
            if let error = email.error {
                Text(LocalizedStringKey(error))
                    .noahText(NoahTokens.Typography.sub)
                    .foregroundStyle(NoahTokens.Colors.failure)
                    .padding(.top, NoahTokens.Space.s)
            }
        }
        .onChange(of: email.isValid) { _, valid in if valid { haptics.play(.selection) } }
    }

    private var addressField: some View {
        HStack(spacing: 10) {
            TextField("you@example.com", text: $email.text)
                .noahText(NoahTokens.Typography.field)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .tint(NoahTokens.Colors.orange)
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.send)
                .focused($focus, equals: .email)
                .onSubmit(send)
                .disabled(email.stage == .sending)
                .accessibilityLabel(Text("Email Address"))
                .accessibilityIdentifier("email-address-input")
            CheckStroke(drawn: email.isValid)
                .frame(width: 24, height: 24)
                .accessibilityHidden(true)
        }
    }

    /// "Send code" morphs into a spinner (one glass shape on iOS 26).
    private var sendButton: some View {
        GlassGroup {
            ZStack {
                if email.stage == .sending {
                    ProgressView()
                        .tint(NoahTokens.Colors.background)
                        .frame(width: NoahTokens.Size.button, height: NoahTokens.Size.button)
                        .glassCompat(in: Circle(), tint: NoahTokens.Colors.foreground)
                        .glassCompatID("send", in: glass)
                        .accessibilityLabel(Text("Send code"))
                } else {
                    Button("Send code", action: send)
                        .buttonStyle(OnboardingPrimaryButtonStyle(glassID: "send", namespace: glass))
                        .disabled(!email.isValid)
                        .accessibilityIdentifier("onboarding-email-send")
                }
            }
            .frame(maxWidth: .infinity)
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: email.stage == .sending)
    }

    private func send() {
        guard email.isValid, email.stage == .entry else { return }
        haptics.play(.light)
        Task { await email.sendCode() }
    }

    // MARK: Code

    private var codeEntry: some View {
        VStack(alignment: .leading, spacing: 0) {
            CodeBoxes(email: email, focused: focus == .code, namespace: glass)
                .overlay {
                    // One invisible field drives the boxes, so one-time-code autofill works.
                    TextField("", text: $email.code)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .focused($focus, equals: .code)
                        .foregroundStyle(.clear)
                        .tint(.clear)
                        // Never disabled while verifying: that would drop the keyboard.
                        .disabled(email.stage == .verified)
                        .accessibilityLabel(Text("Verification code"))
                        .accessibilityValue(Text(verbatim: email.code.map(String.init).joined(separator: " ")))
                        .accessibilityIdentifier("verification-code-input")
                }
                .padding(.top, column.firstTop(compact: 34))
            if let error = email.error {
                Text(LocalizedStringKey(error))
                    .noahText(NoahTokens.Typography.sub)
                    .foregroundStyle(NoahTokens.Colors.failure)
                    .padding(.top, 18)
                    .accessibilityIdentifier("onboarding-email-error")
            }
            if email.stage != .verified {
                EmailResend(email: email, haptics: haptics).padding(.top, email.error == nil ? 18 : NoahTokens.Space.s)
            }
        }
    }
}

/// Six glass boxes with an orange active box. A wrong code turns them red
/// and shakes; success folds them into the orange check ring.
struct CodeBoxes: View {
    let email: EmailVerification
    let focused: Bool
    let namespace: Namespace.ID
    @State private var rowWidth: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var verified: Bool { email.stage == .verified }
    private var failed: Bool { email.stage == .wrongCode }

    var body: some View {
        GlassGroup(spacing: NoahTokens.Space.s) {
            ZStack {
                HStack(spacing: NoahTokens.Space.s) {
                    ForEach(0..<EmailVerification.codeLength, id: \.self) { index in
                        if !(verified && index == 2) { box(index) }
                        else { Color.clear.frame(maxWidth: .infinity) }
                    }
                }
                if verified {
                    VerifiedCheck()
                        .frame(width: NoahTokens.Size.otpBox, height: NoahTokens.Size.otpBox)
                        .glassCompat(in: Circle())
                        .glassCompatID("otp-2", in: namespace)
                        .transition(reduceMotion ? .opacity : .scale(scale: 0.6).combined(with: .opacity))
                }
            }
        }
        .frame(height: NoahTokens.Size.otpBox)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { rowWidth = $0 }
        .keyframeAnimator(initialValue: CGFloat.zero, trigger: email.failures) { [still = reduceMotion] content, offset in
            content.offset(x: still ? 0 : offset)
        } keyframes: { _ in
            LinearKeyframe(-10, duration: 0.1)
            LinearKeyframe(9, duration: 0.1)
            LinearKeyframe(-5, duration: 0.1)
            LinearKeyframe(0, duration: 0.1)
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: verified)
        .accessibilityHidden(true)
    }

    private func box(_ index: Int) -> some View {
        let digits = Array(email.code)
        let active = focused && index == digits.count && !verified && email.stage != .verifying
        let stroke: Color = failed ? NoahTokens.Colors.failure
            : active || email.stage == .verifying ? NoahTokens.Colors.orange : NoahTokens.Colors.hairline
        let step = (rowWidth - 5 * NoahTokens.Space.s) / 6 + NoahTokens.Space.s
        return ZStack {
            Text(verbatim: index < digits.count ? String(digits[index]) : "")
                .noahText(NoahTokens.Typography.code)
                .foregroundStyle(failed ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
                .contentTransition(.opacity)
            if active { Caret() }
        }
        .frame(maxWidth: .infinity, minHeight: NoahTokens.Size.otpBox)
        .glassCompat(in: RoundedRectangle(cornerRadius: NoahTokens.Radius.field, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: NoahTokens.Radius.field, style: .continuous)
                .strokeBorder(stroke, lineWidth: active || failed || email.stage == .verifying ? 2 : 1.5)
        }
        .glassCompatID("otp-\(index)", in: namespace)
        .scaleEffect(verified && !reduceMotion ? 0.35 : 1)
        .offset(x: verified && !reduceMotion ? (2.5 - Double(index)) * step : 0)
        .opacity(verified ? 0 : 1)
        .animation(NoahTokens.Motion.travel(reduceMotion).delay(verified ? abs(Double(index) - 2.5) * 0.035 : 0), value: verified)
        .animation(NoahTokens.Motion.snappy, value: stroke)
    }
}

private struct Caret: View {
    @State private var on = true
    var body: some View {
        RoundedRectangle(cornerRadius: 1).fill(NoahTokens.Colors.orange)
            .frame(width: 2, height: 26)
            .opacity(on ? 1 : 0)
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(500))
                    on.toggle()
                }
            }
    }
}

/// The orange ring draws, then the check.
struct VerifiedCheck: View {
    @State private var drawn = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle().trim(from: 0, to: drawn ? 1 : 0)
                .stroke(NoahTokens.Colors.orange, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .padding(1.25)
            CheckShape(points: [CGPoint(x: 19, y: 31), CGPoint(x: 26.5, y: 38.5), CGPoint(x: 42, y: 23)], box: 60)
                .trim(from: 0, to: drawn ? 1 : 0)
                .stroke(NoahTokens.Colors.foreground, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        }
        .onAppear {
            if reduceMotion { drawn = true; return }
            withAnimation(NoahTokens.Motion.soft.delay(0.18)) { drawn = true }
        }
    }
}

/// A polyline in a square design box, scaled to the frame.
struct CheckShape: Shape {
    let points: [CGPoint]
    let box: CGFloat

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / box
        var path = Path()
        for (index, point) in points.enumerated() {
            let p = CGPoint(x: rect.minX + point.x * scale, y: rect.minY + point.y * scale)
            if index == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        return path
    }
}

/// The orange check beside a valid address.
struct CheckStroke: View {
    let drawn: Bool
    var body: some View {
        CheckShape(points: [CGPoint(x: 5, y: 12.5), CGPoint(x: 9.5, y: 17), CGPoint(x: 19, y: 7.5)], box: 24)
            .trim(from: 0, to: drawn ? 1 : 0)
            .stroke(NoahTokens.Colors.orange, style: StrokeStyle(lineWidth: 2.6, lineCap: .round, lineJoin: .round))
            .opacity(drawn ? 1 : 0)
            .animation(NoahTokens.Motion.snappy, value: drawn)
    }
}

/// Domain completions and "Did you mean" under the address field.
struct EmailHints: View {
    @Bindable var email: EmailVerification
    let haptics: OnboardingHaptics

    var body: some View {
        GlassGroup(spacing: NoahTokens.Space.s) {
            WrapLayout(spacing: NoahTokens.Space.s, lineSpacing: 0) {
                switch email.hint {
                case .hidden:
                    EmptyView()
                case .didYouMean(let fixed):
                    Text("Did you mean")
                        .noahText(NoahTokens.Typography.sub)
                        .foregroundStyle(NoahTokens.Colors.muted)
                    chip(fixed, label: fixed)
                case .complete(let addresses):
                    ForEach(addresses, id: \.self) { address in
                        chip(address, label: address.firstIndex(of: "@").map { String(address[$0...]) } ?? address)
                    }
                }
            }
        }
        .frame(minHeight: NoahTokens.Size.hitTarget, alignment: .topLeading)
    }

    private func chip(_ address: String, label: String) -> some View {
        Button {
            email.text = address
            haptics.play(.selection)
        } label: { Text(verbatim: label) }
            .buttonStyle(OnboardingChipStyle())
            .accessibilityLabel(Text(verbatim: address))
    }
}

/// "Resend code in 0:30", then "Didn’t get it? Resend".
struct EmailResend: View {
    let email: EmailVerification
    let haptics: OnboardingHaptics
    @Environment(\.locale) private var locale

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { timeline in
            let remaining = email.resendRemaining(at: timeline.date)
            if remaining > 0 {
                Text(verbatim: L10n.text("Resend code in {time}", String(format: "%d:%02d", remaining / 60, remaining % 60), locale: locale.identifier))
                    .noahText(NoahTokens.Typography.sub)
                    .foregroundStyle(NoahTokens.Colors.muted)
                    .monospacedDigit()
                    .frame(minHeight: NoahTokens.Size.hitTarget, alignment: .leading)
            } else {
                HStack(spacing: 4) {
                    Text("Didn’t get it?")
                        .noahText(NoahTokens.Typography.sub)
                        .foregroundStyle(NoahTokens.Colors.muted)
                    Button {
                        haptics.play(.light)
                        Task { await email.resend() }
                    } label: {
                        Text("Resend").noahText(NoahTokens.Typography.sub).bold()
                            .foregroundStyle(NoahTokens.Colors.foreground)
                            .frame(minWidth: NoahTokens.Size.hitTarget, minHeight: NoahTokens.Size.hitTarget)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(email.stage == .verifying || email.isSending)
                    .accessibilityIdentifier("onboarding-email-resend")
                }
            }
        }
    }
}
