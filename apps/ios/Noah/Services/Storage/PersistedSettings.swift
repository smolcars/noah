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

    var currency: String {
        preferredCurrency.flatMap { FiatCurrency.supported.contains($0) ? $0 : nil } ?? "USD"
    }

    var amountUnit: BitcoinAmountUnit {
        bitcoinAmountUnit.flatMap(BitcoinAmountUnit.init(rawValue:)) ?? .bip177
    }
}

/// RN transaction-storage v1. The legacy attempted flag is retained for upgrade compatibility only.
struct PersistedTransactionState: Codable, Equatable, Sendable {
    static let key = "transaction-storage"
    static let version = 1
    var isAutoBoardingEnabled = true
    var hasAttemptedAutoBoarding = false

    mutating func setAutoBoarding(_ enabled: Bool) {
        isAutoBoardingEnabled = enabled
        if enabled { hasAttemptedAutoBoarding = false }
    }
}

extension PersistedTransactionState {
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        isAutoBoardingEnabled = (try? values.decode(Bool.self, forKey: .isAutoBoardingEnabled)) ?? true
        hasAttemptedAutoBoarding = (try? values.decode(Bool.self, forKey: .hasAttemptedAutoBoarding)) ?? false
    }

    static func load(from storage: SettingsStorage) -> Self {
        guard let json = storage.string(forKey: key),
              let checked = try? RNStoreCodec.decode(json, key: key).json(),
              let envelope = try? JSONDecoder().decode(PersistedEnvelope<Self>.self, from: Data(checked.utf8)) else { return .init() }
        var state = envelope.state
        if (envelope.version ?? 0) < version { state.hasAttemptedAutoBoarding = false }
        return state
    }
}
