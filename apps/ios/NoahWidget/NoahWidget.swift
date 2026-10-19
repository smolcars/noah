import SwiftUI
import WidgetKit

/// Balance widget. `kind` and the app group come from the variant's
/// xcconfig and must keep the React Native values (`NoahWidgetMainnet`,
/// `NoahWidgetSignet`, `NoahWidgetRegtest`) so widgets users already placed
/// keep working after the update.
struct NoahWidget: Widget {
    private static let kind = infoValue("NoahWidgetKind")
    private static let appGroup = infoValue("NoahAppGroup")

    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: Self.kind,
            provider: BalanceProvider(appGroup: Self.appGroup)
        ) { entry in
            NoahBalanceWidgetView(entry: entry)
                .containerBackground(Color(red: 0.05, green: 0.05, blue: 0.05), for: .widget)
        }
        .configurationDisplayName("Noah Balance")
        .description("View your Bitcoin balance")
        .supportedFamilies([.systemSmall, .systemMedium])
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

#Preview(as: .systemSmall) {
    NoahWidget()
} timeline: {
    BalanceEntry(
        date: .now, totalBalance: 100000, onchainBalance: 50000, offchainBalance: 50000,
        pendingBalance: 0, closestExpiryBlocks: 500, expiryThreshold: 288)
    BalanceEntry(
        date: .now, totalBalance: 250000, onchainBalance: 100000, offchainBalance: 150000,
        pendingBalance: 10000, closestExpiryBlocks: 150, expiryThreshold: 288)
}
