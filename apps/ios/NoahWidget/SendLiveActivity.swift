import ActivityKit
import SwiftUI
import WidgetKit

/// The Lock Screen and Dynamic Island for a Lightning payment (async-payments.md, "What the user sees"):
/// monochrome, with orange only for your coin. The app starts, updates and ends it (`SendActivityCenter`).
struct SendLiveActivity: Widget {
    /// The app's language: Settings' choice (mirrored by `WidgetDataWriter`), else the device's.
    private static var language: String {
        let group = Bundle.main.object(forInfoDictionaryKey: "NoahAppGroup") as? String
        return LegacyLocale.resolved(from: group.flatMap(UserDefaults.init(suiteName:)) ?? .standard)
    }

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SendActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 10) {
                SendActivityText(phase: context.state.phase, stale: context.isStale, language: Self.language)
                SendActivityTrack(phase: context.state.phase)
            }
            .padding(16)
        } dynamicIsland: { context in
            let phase = context.state.phase
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { SendActivityCoin(size: 28).padding(.leading, 4) }
                DynamicIslandExpandedRegion(.center) {
                    SendActivityText(phase: phase, stale: context.isStale, language: Self.language)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                DynamicIslandExpandedRegion(.bottom) { SendActivityTrack(phase: phase).padding(.horizontal, 4) }
            } compactLeading: {
                SendActivityCoin(size: 20)
            } compactTrailing: {
                Image(systemName: Self.glyph(phase)).font(.system(size: 13, weight: .semibold))
                    .accessibilityLabel(phase.title(locale: Self.language))
            } minimal: {
                SendActivityCoin(size: 20)
            }
            .keylineTint(NoahTokens.Colors.orange)
        }
    }

    private static func glyph(_ phase: SendActivityPhase) -> String {
        switch phase {
        case .paying, .stillPaying: "bolt.fill"
        case .sent: "checkmark"
        case .failed, .refunded: "arrow.uturn.backward"
        }
    }
}

/// The title and its muted line.
private struct SendActivityText: View {
    let phase: SendActivityPhase
    let stale: Bool
    let language: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: phase.title(locale: language))
                .font(.custom("SpaceGrotesk-SemiBold", size: 17, relativeTo: .headline))
            if let line = phase.line(locale: language, stale: stale) {
                Text(verbatim: line).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
            }
        }
    }
}

/// Your coin: the only orange.
private struct SendActivityCoin: View {
    let size: CGFloat

    var body: some View {
        Circle().strokeBorder(NoahTokens.Colors.orange, lineWidth: max(1.5, size / 11))
            .overlay { Text(verbatim: "₿").font(.system(size: size * 0.52, weight: .bold)) }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// A thin track with the coin where the payment is: locked, paying, done.
private struct SendActivityTrack: View {
    let phase: SendActivityPhase
    private let coin: CGFloat = 22

    var body: some View {
        GeometryReader { geometry in
            let travel = max(0, geometry.size.width - coin)
            ZStack(alignment: .leading) {
                Capsule().fill(.secondary.opacity(0.3)).frame(height: 3)
                Capsule().fill(.primary).frame(width: coin / 2 + travel * phase.progress, height: 3)
                SendActivityCoin(size: coin).background(Circle().fill(.background)).offset(x: travel * phase.progress)
            }
            .frame(maxHeight: .infinity)
        }
        .frame(height: coin)
        .accessibilityHidden(true)
    }
}
