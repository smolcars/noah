import SwiftUI

/// Creating and Restoring: the tree tells the story, with one phase line
/// below it (and, when restoring, the balance counting up).
struct OnboardingProgressScreen: View {
    struct Count: Equatable {
        var total: Int64
        var lit: [Int]
        var reveal: Date?
        var unit: BitcoinAmountUnit
    }

    let layout: OnboardingLayout
    let mode: TreeScene.Mode
    let phase: String
    var count: Count?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.locale) private var locale

    var body: some View {
        OnboardingPanes(wide: layout.wide, column: .leading(layout.chainY(mode) + 30),
                        sideInsets: (layout.insets.leading, layout.insets.trailing)) {
            Color.clear
            VStack(spacing: NoahTokens.Space.l) {
                ZStack {
                    Text(LocalizedStringKey(phase))
                        .id(phase)
                        .transition(reduceMotion ? .opacity : .asymmetric(
                            insertion: .offset(y: 10).combined(with: .opacity),
                            removal: .offset(y: -10).combined(with: .opacity)))
                }
                .noahText(NoahTokens.Typography.label)
                .foregroundStyle(NoahTokens.Colors.foreground)
                .multilineTextAlignment(.center)
                .animation(NoahTokens.Motion.travel(reduceMotion), value: phase)
                .accessibilityAddTraits(.updatesFrequently)
                if let count {
                    TimelineView(.animation(paused: count.reveal == nil || reduceMotion)) { timeline in
                        Text(verbatim: BitcoinAmount.format(sats: amount(count, at: timeline.date), unit: count.unit, locale: locale))
                    }
                    .noahText(NoahTokens.Typography.amount)
                    .monospacedDigit()
                    .foregroundStyle(NoahTokens.Colors.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .onboardingAppear(0, base: 0.5, distance: 8)
                    .accessibilityHidden(count.reveal == nil)
                }
            }
            .padding(.top, layout.wide ? 0 : 22)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: layout.wide ? .center : .top)
            .onboardingPane()
        }
    }

    private func amount(_ count: Count, at date: Date) -> Int64 {
        guard let reveal = count.reveal else { return 0 }
        if reduceMotion { return count.total }
        return TreeScene.restoredAmount(total: count.total, lit: count.lit, at: date.timeIntervalSince(reveal))
    }
}
