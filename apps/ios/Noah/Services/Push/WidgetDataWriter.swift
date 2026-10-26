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
        mirrorLanguage(defaults)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Copies Settings' language choice where the widget can read it (it can't read the app's
    /// own defaults), redrawing the widget only when it changed.
    static func publishLanguage(variant: AppVariant) {
        guard let defaults = UserDefaults(suiteName: variant.appGroup), mirrorLanguage(defaults) else { return }
        WidgetCenter.shared.reloadAllTimelines()
    }

    @discardableResult private static func mirrorLanguage(_ defaults: UserDefaults) -> Bool {
        let language = LegacyLocale.read()
        guard defaults.string(forKey: LegacyLocale.key) != language else { return false }
        defaults.set(language, forKey: LegacyLocale.key)
        return true
    }
}
