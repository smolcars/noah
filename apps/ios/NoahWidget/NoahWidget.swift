import SwiftUI
import WidgetKit

/// Balance widget (docs/native-rewrite/design/widgets.md). `kind` and the app group come from the
/// variant's xcconfig and must keep the React Native values (`NoahWidgetMainnet`, `NoahWidgetSignet`,
/// `NoahWidgetRegtest`) so widgets users already placed keep working after the update.
struct NoahWidget: Widget {
    private static let kind = infoValue("NoahWidgetKind")
    private static let appGroup = infoValue("NoahAppGroup")
    private static let variant = infoValue("NoahAppVariant")

    private static var description: String {
        switch variant {
        case "signet": L10n.text("View your Bitcoin balance on signet", locale: language)
        case "regtest": L10n.text("View your Bitcoin balance on regtest", locale: language)
        default: L10n.text("View your Bitcoin balance", locale: language)
        }
    }

    /// The app's language: Settings' choice (mirrored by `WidgetDataWriter`), else the device's.
    private static var language: String {
        LegacyLocale.resolved(from: UserDefaults(suiteName: appGroup) ?? .standard)
    }

    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: Self.kind,
            provider: BalanceProvider(appGroup: Self.appGroup, variant: Self.variant)) { entry in
                NoahBalanceWidgetView(entry: entry, language: Self.language, links: WidgetLinks(variant: Self.variant))
            }
            .configurationDisplayName(L10n.text("Noah Balance", locale: Self.language))
            .description(Self.description)
            .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryCircular, .accessoryInline])
            // Padded by the views themselves, so the wave can bleed to the edges.
            .contentMarginsDisabled()
    }

    private static func infoValue(_ key: String) -> String {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String, !value.isEmpty
        else {
            preconditionFailure("\(key) missing from the widget Info.plist")
        }
        return value
    }
}

@main
struct NoahWidgetBundle: WidgetBundle {
    var body: some Widget {
        NoahWidget()
    }
}

#if DEBUG
    /// The spec's states, for previews.
    private enum PreviewSnapshots {
        static let month: [Int64] = [222600, 230600, 228100, 228100, 232942, 232942, 228742, 228742, 243742, 233742, 233742,
                                     230542, 251542, 251542, 245542, 240542, 240542, 250042, 251542, 251542, 243742, 243242,
                                     243242, 255242, 261242, 261242, 257242, 247242, 250642, 252742, 252742]

        // swiftlint:disable:next discouraged_optional_collection - nil: calm water
        static func make(balance: UInt64 = 252_742, arriving: UInt64 = 0, daily: [Int64]? = month, unit: BitcoinAmountUnit = .bip177,
                         hidden: Bool = false, renewHours: Double? = nil, variant: String = "mainnet") -> WidgetSnapshot {
            WidgetSnapshot(writtenAt: .now, balance: balance, arriving: arriving, dailyBalances: daily, amountUnit: unit, hidden: hidden,
                           fiat: .init(currency: "USD", btcPrice: 112_000), renewBy: renewHours.map { .now.addingTimeInterval($0 * 3600 + 60) },
                           variant: variant)
        }

        static func entry(_ snapshot: WidgetSnapshot?) -> BalanceEntry { BalanceEntry(date: .now, snapshot: snapshot) }
        static let everyday = entry(make()), arriving = entry(make(arriving: 100_000)), renew = entry(make(renewHours: 18))
        static let fresh = entry(make(balance: 0, daily: Array(repeating: 0, count: 31)))
        static let hidden = entry(make(arriving: 100_000, hidden: true)), signet = entry(make(variant: "signet"))
        static let large = entry(make(balance: 12_480_315, unit: .sats)), noWallet = entry(nil)
    }

    #Preview("Small", as: .systemSmall) { NoahWidget() } timeline: {
        PreviewSnapshots.everyday; PreviewSnapshots.arriving; PreviewSnapshots.renew; PreviewSnapshots.fresh
        PreviewSnapshots.hidden; PreviewSnapshots.signet; PreviewSnapshots.large; PreviewSnapshots.noWallet
    }
    #Preview("Medium", as: .systemMedium) { NoahWidget() } timeline: {
        PreviewSnapshots.everyday; PreviewSnapshots.renew; PreviewSnapshots.hidden; PreviewSnapshots.signet; PreviewSnapshots.noWallet
    }
    #Preview("Rectangular", as: .accessoryRectangular) { NoahWidget() } timeline: {
        PreviewSnapshots.everyday; PreviewSnapshots.arriving; PreviewSnapshots.renew; PreviewSnapshots.hidden
    }
    #Preview("Circular", as: .accessoryCircular) { NoahWidget() } timeline: { PreviewSnapshots.everyday }
    #Preview("Inline", as: .accessoryInline) { NoahWidget() } timeline: { PreviewSnapshots.everyday; PreviewSnapshots.hidden }
#endif
