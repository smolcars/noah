import Foundation

/// `wallet-storage` from `client/src/store/walletStore.ts` (partialized
/// fields only). All optional: older installs may lack newer fields.
struct PersistedWalletState: Codable, Equatable, Sendable {
    static let key = "wallet-storage"
    static let version = 0

    // swiftlint:disable discouraged_optional_boolean - RN's persisted JSON omits unset flags
    var isInitialized: Bool?
    var isWalletLoaded: Bool?
    // swiftlint:enable discouraged_optional_boolean
    var staticVtxoPubkey: String?
    // swiftlint:disable discouraged_optional_boolean - RN's persisted JSON omits unset flags
    var isBiometricsEnabled: Bool?
    var isDebugModeEnabled: Bool?
    var isWalletSuspended: Bool?
    var hasSeenBatteryOptimizationPrompt: Bool?
    // swiftlint:enable discouraged_optional_boolean
}
