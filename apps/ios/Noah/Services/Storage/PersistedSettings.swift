import Foundation

/// `privacy-storage` (`client/src/store/privacyStore.ts`).
struct PersistedPrivacyState: Codable, Equatable, Sendable {
    static let key = "privacy-storage"
    static let version = 0

    var isHomeBalanceHidden: Bool?
}

/// `profile-storage` (`client/src/store/profileStore.ts`). Unknown values
/// fall back to the RN defaults (`USD`, `bip177`), as its `merge` does.
struct PersistedProfileState: Codable, Equatable, Sendable {
    static let key = "profile-storage"
    static let version = 0

    var displayName: String?
    var preferredCurrency: String?
    var bitcoinAmountUnit: String?

    var amountUnit: BitcoinAmountUnit {
        bitcoinAmountUnit.flatMap(BitcoinAmountUnit.init(rawValue:)) ?? .bip177
    }
}
