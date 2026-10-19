import Foundation

/// `wallet-storage` from `client/src/store/walletStore.ts` (partialized
/// fields only). All optional: older installs may lack newer fields.
struct PersistedWalletState: Codable, Equatable, Sendable {
    static let key = "wallet-storage"
    static let version = 0

    var isInitialized: Bool?
    var isWalletLoaded: Bool?
    var staticVtxoPubkey: String?
    var isBiometricsEnabled: Bool?
    var isDebugModeEnabled: Bool?
    var isWalletSuspended: Bool?
    var hasSeenBatteryOptimizationPrompt: Bool?
}
