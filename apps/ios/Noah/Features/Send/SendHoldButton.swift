import SwiftUI

/// "Hold to send" (send-receive.md): holding for 0.8 s traces an orange stroke around the
/// black pill, with haptic ticks at 25/50/75% and a heavy one at the end, then confirms
/// exactly as today's confirm button did. Letting go early rewinds the stroke. VoiceOver
/// and Switch Control get a "Send ₿X" action with a system confirmation instead. It
/// confirms at most once; the payment controller also refuses a second submission. Only a
/// single finger held for the whole 0.8 s confirms: a second finger cancels the hold.
struct SendHoldButton: View {
    /// The formatted amount, for the accessibility action and its confirmation.
    let amount: String
    var enabled = true
    /// Exit's holds reuse it: the pill's words, the action's title ("Send {amount}") and its confirm button.
    var title: LocalizedStringKey = "Hold to send"
    var action: String = "Send {amount}"
    var confirm: LocalizedStringKey = "Send"
    var id = "send-hold"
    let onConfirm: () -> Void

    static let duration = 0.8
    /// Haptic ticks at 25, 50 and 75% of the hold; the heavy one comes on completion.
    static let ticks: [(at: Double, kind: OnboardingHaptics.Kind)] = [(0.2, .selection), (0.4, .light), (0.6, .medium)]

    @State private var progress: CGFloat = 0
    @State private var pressing = false
    @State private var fired = false
    @State private var ticks: Task<Void, Never>?
    @State private var confirming = false
    @State private var press = SendHoldPress()
    @State private var haptics = OnboardingHaptics()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.locale) private var locale

    private var actionTitle: String { L10n.text(action, amount, locale: locale.identifier) }

    var body: some View {
        Text(title)
            .noahText(NoahTokens.Typography.button)
            // Large text wraps the words inside a taller pill rather than cutting them off.
            .multilineTextAlignment(.center)
            .padding(.horizontal, 20).padding(.vertical, 10)
            .foregroundStyle(NoahTokens.Colors.background)
            .frame(maxWidth: .infinity, minHeight: 58)
            .background(NoahTokens.Colors.foreground, in: Capsule())
            // Your finger signs: the stroke traces the pill from its top-left.
            .overlay {
                PillOutline()
                    .trim(from: 0, to: progress)
                    .stroke(NoahTokens.Colors.orange, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .padding(-4)
            }
            .scaleEffect(pressing && !reduceMotion ? 0.97 : 1)
            .animation(NoahTokens.Motion.tap, value: pressing)
            .opacity(enabled ? 1 : 0.28)
            .contentShape(Capsule())
            .onLongPressGesture(minimumDuration: Self.duration, maximumDistance: 40, perform: complete) { down in
                down ? begin() : release()
            }
            // Counts the fingers on the pill, which the long press doesn't report.
            .simultaneousGesture(SpatialEventGesture()
                .onChanged { events in
                    let spoiled = press.spoiled
                    press.touches(events.filter { $0.phase == .active }.count)
                    if press.spoiled && !spoiled { release() }
                }
                .onEnded { _ in press.touches(0) })
            .allowsHitTesting(enabled && !fired)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: actionTitle))
            .accessibilityHint(Text(title))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { if enabled && !fired { confirming = true } }
            .accessibilityAction(named: Text(verbatim: actionTitle)) { if enabled && !fired { confirming = true } }
            .accessibilityIdentifier(id)
            .alert(Text(verbatim: actionTitle), isPresented: $confirming) {
                Button("Cancel", role: .cancel) {}
                Button(confirm) { fire() }
            }
            .onboardingHaptics(haptics)
            .onDisappear { ticks?.cancel() }
    }

    private func begin() {
        guard enabled, !fired, !press.spoiled else { return }
        pressing = true
        withAnimation(.linear(duration: Self.duration)) { progress = 1 }
        ticks?.cancel()
        let start = ContinuousClock.now
        ticks = Task { @MainActor in
            for tick in Self.ticks {
                do { try await Task.sleep(until: start + .seconds(tick.at), clock: .continuous) } catch { return }
                haptics.play(tick.kind)
            }
        }
    }

    private func release() {
        pressing = false
        guard !fired else { return }
        ticks?.cancel()
        withAnimation(.easeOut(duration: 0.26)) { progress = 0 }
    }

    private func complete() {
        guard !press.spoiled else { release(); return }
        ticks?.cancel()
        progress = 1
        fire()
    }

    private func fire() {
        guard enabled, !fired else { return }
        fired = true
        haptics.play(.heavy)
        onConfirm()
    }
}

/// One press of Hold to send. It may confirm only while a single finger has held it
/// throughout: a second finger at any point spoils it until every finger lifts.
struct SendHoldPress: Equatable {
    private(set) var spoiled = false

    mutating func touches(_ active: Int) {
        if active > 1 { spoiled = true } else if active == 0 { spoiled = false }
    }
}

/// Confirms a reviewed payment at most once per controller attempt, however many hold
/// buttons ask (a pose change rebuilds the button). Owned by Send, so it outlives them; the
/// controller still refuses any submission while it isn't ready.
@MainActor final class SendHoldGate {
    private var used: Set<Int> = []

    /// Runs `submit` unless this attempt (the controller's generation) was already confirmed.
    @discardableResult func confirm(generation: Int, _ submit: () -> Void) -> Bool {
        guard used.insert(generation).inserted else { return false }
        submit()
        return true
    }
}

/// A capsule outline that starts at the top-left, like the prototype's SVG `rect`.
private struct PillOutline: Shape {
    func path(in rect: CGRect) -> Path {
        let r = rect.height / 2
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + r, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        path.addArc(center: CGPoint(x: rect.maxX - r, y: rect.midY), radius: r, startAngle: .degrees(-90), endAngle: .degrees(90), clockwise: false)
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addArc(center: CGPoint(x: rect.minX + r, y: rect.midY), radius: r, startAngle: .degrees(90), endAngle: .degrees(270), clockwise: false)
        path.closeSubpath()
        return path
    }
}
