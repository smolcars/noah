import Foundation
import WidgetKit

/// Writes what the balance widget reads (`updateWidget` in useWidget.ts):
/// app-group `UserDefaults["widgetBalance"]`, numbers as doubles.
enum WidgetDataWriter {
    static func write(balance: WalletBalance, closestExpiryBlocks: Int?, variant: AppVariant, preserveExpiry: Bool = false) {
        guard let defaults = UserDefaults(suiteName: variant.appGroup) else { return }
        var values = [
            "totalBalance": Double(balance.totalSats),
            "onchainBalance": Double(balance.onchainSats),
            "offchainBalance": Double(balance.offchainSats),
            "pendingBalance": Double(balance.pendingSats),
            "closestExpiryBlocks": Double(closestExpiryBlocks ?? -999),
            "expiryThreshold": Double(WalletConfiguration.base(for: variant).vtxoRefreshExpiryThreshold),
        ]
        if preserveExpiry {
            values["closestExpiryBlocks"] = (defaults.dictionary(forKey: "widgetBalance")?["closestExpiryBlocks"] as? Double) ?? -999
        }
        defaults.set(values, forKey: "widgetBalance")
        publishLanguage(variant: variant)
    }

    /// Copies Settings' language choice where the widget can read it (it can't read the app's
    /// own defaults), then redraws the widget.
    static func publishLanguage(variant: AppVariant) {
        UserDefaults(suiteName: variant.appGroup)?.set(LegacyLocale.read(), forKey: LegacyLocale.key)
        WidgetCenter.shared.reloadAllTimelines()
    }
}
