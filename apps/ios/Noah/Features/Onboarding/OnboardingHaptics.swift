import SwiftUI

/// The prototype's haptic log: selection, light, medium, heavy, success, warning, error.
@MainActor
@Observable
final class OnboardingHaptics {
    enum Kind { case selection, light, medium, heavy, success, warning, error }

    private(set) var tick = 0
    private(set) var kind = Kind.selection

    func play(_ kind: Kind) {
        self.kind = kind
        tick += 1
    }

    var feedback: SensoryFeedback {
        switch kind {
        case .selection: .selection
        case .light: .impact(weight: .light)
        case .medium: .impact(weight: .medium)
        case .heavy: .impact(weight: .heavy)
        case .success: .success
        case .warning: .warning
        case .error: .error
        }
    }
}

extension View {
    func onboardingHaptics(_ haptics: OnboardingHaptics) -> some View {
        sensoryFeedback(trigger: haptics.tick) { _, _ in haptics.feedback }
    }

    /// The prototype's `[data-s]` entrance: rise and fade in, staggered.
    /// Runs once per view, so a pose change doesn't replay it. `animated: false` starts shown.
    func onboardingAppear(_ order: Int, base: Double = 0.09, step: Double = 0.06, distance: CGFloat = 14,
                          animated: Bool = true) -> some View {
        modifier(OnboardingAppear(delay: base + Double(order) * step, distance: distance, shown: !animated))
    }

    /// Centres content in its pane, at most `OnboardingLayout.paneContentWidth` wide.
    func onboardingPane(_ inset: CGFloat = NoahTokens.Space.screen) -> some View {
        padding(.horizontal, inset)
            .frame(maxWidth: OnboardingLayout.paneContentWidth + 2 * inset)
            .frame(maxWidth: .infinity)
    }
}

extension NoahTokens.Motion {
    /// Travel and scale become a short fade under Reduce Motion.
    static func travel(_ reduceMotion: Bool, _ spring: Animation = snappy) -> Animation {
        reduceMotion ? reduceMotionFade : spring
    }
}

private struct OnboardingAppear: ViewModifier {
    let delay: Double
    let distance: CGFloat
    @State private var shown: Bool

    init(delay: Double, distance: CGFloat, shown: Bool) {
        self.delay = delay
        self.distance = distance
        _shown = State(initialValue: shown)
    }
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : distance)
            .onAppear {
                guard !shown else { return }
                withAnimation(reduceMotion ? NoahTokens.Motion.reduceMotionFade.delay(delay * 0.3)
                    : NoahTokens.Motion.snappy.delay(delay)) { shown = true }
            }
    }
}

/// The black pill: prominent glass tinted to the foreground colour, so it
/// stays monochrome (white on black, reversed in dark).
struct OnboardingPrimaryButtonStyle: ButtonStyle {
    var glassID: String?
    var namespace: Namespace.ID?

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, glassID: glassID, namespace: namespace)
    }

    private struct Styled: View {
        let configuration: Configuration
        let glassID: String?
        let namespace: Namespace.ID?
        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            configuration.label
                .noahText(NoahTokens.Typography.button)
                .foregroundStyle(NoahTokens.Colors.background)
                .multilineTextAlignment(.center)
                .padding(.horizontal, NoahTokens.Space.xl)
                .frame(maxWidth: .infinity, minHeight: NoahTokens.Size.button)
                .contentShape(Capsule())
                // Disabled is a 20% tint, not view opacity: grouped glass ignores opacity.
                .glassCompat(in: Capsule(), interactive: isEnabled,
                             tint: isEnabled ? NoahTokens.Colors.foreground : NoahTokens.Colors.foreground.opacity(0.2))
                .glassCompatID(glassID ?? "", in: glassID == nil ? nil : namespace)
                .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
                .animation(NoahTokens.Motion.tap, value: configuration.isPressed)
        }
    }
}

/// Secondary action: clear glass with foreground text.
struct OnboardingSecondaryButtonStyle: ButtonStyle {
    var height: CGFloat = 52

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, height: height)
    }

    private struct Styled: View {
        let configuration: Configuration
        let height: CGFloat
        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            configuration.label
                .noahText(NoahTokens.Typography.label)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .multilineTextAlignment(.center)
                .padding(.horizontal, NoahTokens.Space.xl)
                .frame(maxWidth: .infinity, minHeight: height)
                .contentShape(Capsule())
                .glassCompat(in: Capsule(), interactive: isEnabled)
                .opacity(isEnabled ? 1 : 0.35)
                .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
                .animation(NoahTokens.Motion.tap, value: configuration.isPressed)
        }
    }
}

/// Glass chip: 36 pt tall, with a 44 pt hit target.
struct OnboardingChipStyle: ButtonStyle {
    var bold = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .noahText(NoahTokens.Typography.control)
            .bold(bold)
            .foregroundStyle(NoahTokens.Colors.foreground)
            .lineLimit(1)
            .padding(.horizontal, 14)
            .frame(minHeight: NoahTokens.Size.chip)
            .glassCompat(in: Capsule(), interactive: true)
            .opacity(configuration.isPressed ? 0.7 : 1)
            .frame(minHeight: NoahTokens.Size.hitTarget)
            .contentShape(Rectangle())
    }
}

/// Top-bar controls (language, Skip, back): glass capsules, 44 pt.
struct OnboardingBarButtonStyle: ButtonStyle {
    var muted = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .noahText(NoahTokens.Typography.control)
            .foregroundStyle(muted ? NoahTokens.Colors.muted : NoahTokens.Colors.foreground)
            .padding(.horizontal, 14)
            .frame(minWidth: NoahTokens.Size.hitTarget, minHeight: NoahTokens.Size.hitTarget)
            .contentShape(Capsule())
            .glassCompat(in: Capsule(), interactive: true)
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// Hairline under a field; orange grows in from the leading edge on focus.
struct OnboardingUnderline: View {
    var focused: Bool
    var failed = false
    var thickness: CGFloat = 1.5

    var body: some View {
        ZStack(alignment: .leading) {
            Rectangle().fill(failed ? NoahTokens.Colors.failure : NoahTokens.Colors.hairline)
            Rectangle().fill(NoahTokens.Colors.orange)
                .scaleEffect(x: focused && !failed ? 1 : 0, anchor: .leading)
        }
        .frame(height: thickness)
        .animation(NoahTokens.Motion.soft, value: focused)
        .accessibilityHidden(true)
    }
}

/// Headline and its one short line. Wide layouts move it to the leading pane, larger.
struct OnboardingHeadline<Detail: View>: View {
    let title: Text
    var subtitle: Text?
    var large = false
    var centered = false
    @ViewBuilder var detail: Detail

    var body: some View {
        VStack(alignment: centered ? .center : .leading, spacing: NoahTokens.Space.s) {
            title
                .noahText(large ? NoahTokens.Typography.titleLarge : NoahTokens.Typography.title)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .accessibilityAddTraits(.isHeader)
                .onboardingAppear(0)
            if let subtitle {
                subtitle
                    .noahText(NoahTokens.Typography.body)
                    .foregroundStyle(NoahTokens.Colors.muted)
                    .onboardingAppear(1)
            }
            detail
        }
        .multilineTextAlignment(centered ? .center : .leading)
        .frame(maxWidth: .infinity, alignment: centered ? .center : .leading)
        .fixedSize(horizontal: false, vertical: true)
    }
}

extension OnboardingHeadline where Detail == EmptyView {
    init(title: Text, subtitle: Text? = nil, large: Bool = false, centered: Bool = false) {
        self.init(title: title, subtitle: subtitle, large: large, centered: centered) { EmptyView() }
    }
}

/// Places subviews left to right and wraps; rows align their first baselines.
struct WrapLayout: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    private struct Row { var indices: [Int] = []; var width: CGFloat = 0; var ascent: CGFloat = 0; var descent: CGFloat = 0 }

    private func rows(_ width: CGFloat, _ subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(ProposedViewSize(width: width, height: nil))
            let fitted = min(size.width, width)
            if !rows[rows.count - 1].indices.isEmpty && rows[rows.count - 1].width + spacing + fitted > width {
                rows.append(Row())
            }
            let dimensions = subviews[index].dimensions(in: ProposedViewSize(width: fitted, height: nil))
            let baseline = dimensions[.firstTextBaseline]
            rows[rows.count - 1].indices.append(index)
            rows[rows.count - 1].width += (rows[rows.count - 1].indices.count > 1 ? spacing : 0) + fitted
            rows[rows.count - 1].ascent = max(rows[rows.count - 1].ascent, baseline)
            rows[rows.count - 1].descent = max(rows[rows.count - 1].descent, dimensions.height - baseline)
        }
        return rows
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache _: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = rows(width, subviews)
        let height = rows.reduce(0) { $0 + $1.ascent + $1.descent } + CGFloat(max(0, rows.count - 1)) * lineSpacing
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal _: ProposedViewSize, subviews: Subviews, cache _: inout ()) {
        var y = bounds.minY
        for row in rows(bounds.width, subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
                let width = min(size.width, bounds.width)
                let dimensions = subviews[index].dimensions(in: ProposedViewSize(width: width, height: nil))
                subviews[index].place(at: CGPoint(x: x, y: y + row.ascent - dimensions[.firstTextBaseline]),
                                      proposal: ProposedViewSize(width: width, height: nil))
                x += width + spacing
            }
            y += row.ascent + row.descent + lineSpacing
        }
    }
}

/// A bottom sheet kept inside one pane of a wide window, so it never
/// crosses the fold. Narrow windows use the system sheet instead.
struct OnboardingPaneSheet<Content: View>: View {
    let pane: CGRect
    let onDismiss: (() -> Void)?
    @ViewBuilder var content: Content
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(shown ? 0.38 : 0)
                .ignoresSafeArea()
                .onTapGesture { onDismiss?() }
                .accessibilityHidden(true)
            VStack(spacing: 0) {
                Capsule().fill(NoahTokens.Colors.hairline).frame(width: 36, height: 5)
                    .padding(.top, 10).padding(.bottom, 22)
                    .accessibilityHidden(true)
                content
            }
            .padding(.horizontal, NoahTokens.Space.screen)
            .padding(.bottom, 26)
            .frame(maxWidth: OnboardingLayout.paneContentWidth + 48)
            .background(NoahTokens.Colors.sheet, in: RoundedRectangle(cornerRadius: NoahTokens.Radius.sheet, style: .continuous))
            .padding(8)
            .frame(width: pane.width)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .offset(y: shown || reduceMotion ? 0 : pane.height)
            .opacity(reduceMotion && !shown ? 0 : 1)
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape) { onDismiss?() }
        }
        .onAppear { withAnimation(NoahTokens.Motion.travel(reduceMotion)) { shown = true } }
    }
}
