import Foundation
import WidgetKit

/// Writes what the balance widget reads (`updateWidget` in useWidget.ts):
/// app-group `UserDefaults["widgetBalance"]`, numbers as doubles.
enum WidgetDataWriter {
    static func write(balance: WalletBalance, closestExpiryBlocks: Int?, variant: AppVariant) {
        guard let defaults = UserDefaults(suiteName: variant.appGroup) else { return }
        defaults.set(
            [
                "totalBalance": Double(balance.totalSats),
                "onchainBalance": Double(balance.onchainSats),
                "offchainBalance": Double(balance.offchainSats),
                "pendingBalance": Double(balance.pendingSats),
                "closestExpiryBlocks": Double(closestExpiryBlocks ?? -999),
                "expiryThreshold": Double(WalletConfiguration.base(for: variant).vtxoRefreshExpiryThreshold),
            ],
            forKey: "widgetBalance")
        WidgetCenter.shared.reloadAllTimelines()
    }
}
