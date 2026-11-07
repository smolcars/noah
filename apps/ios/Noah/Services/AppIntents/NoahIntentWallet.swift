import Foundation

/// Intent type names/parameters stay identical to RN. Only this adapter changes:
/// one process session, read-only seed admission, no create/restore/send APIs.
struct NoahIntentWallet: Sendable {
    struct Invoice: Sendable { let paymentRequest: String }
    @TaskLocal static var current = live()
    var newAddress: @Sendable () async throws -> String
    var bolt11Invoice: @Sendable (_ amountSat: UInt64, _ description: String?) async throws -> Invoice
    var refreshBalance: @Sendable () async throws -> UInt64
    /// Activity's rows after a sync.
    var history: @Sendable () async throws -> [HistoryTransaction] = { [] }
    /// The Lightning address the server gave this wallet, as Settings shows it.
    var lightningAddress: @Sendable () async -> String? = { nil }
    /// The balance in the user's currency, only when the app already has a price and amounts aren't hidden.
    var fiat: @Sendable (_ sats: UInt64, _ locale: String) -> String? = { _, _ in nil }

    static func live(services: AppServices = .shared) -> Self {
        Self(newAddress: {
            try await services.withBackgroundWallet {
                try await services.ensureWalletOpen()
                return try await services.session.withOpenWallet { try await $0.wallet.newAddress() }
            }
        }, bolt11Invoice: { amount, description in
            try await services.withBackgroundWallet {
                try await services.ensureWalletOpen()
                return try await services.session.withOpenWallet {
                    Invoice(paymentRequest: try await $0.wallet.bolt11Invoice(amountSats: amount, description: description).invoice)
                }
            }
        }, refreshBalance: {
            try await services.withBackgroundWallet {
                try await services.ensureWalletOpen()
                try await services.session.sync()
                try Task.checkCancellation()
                guard let summary = try await services.session.summary() else { throw AppServices.SeedUnavailable() }
                return summary.balance.totalSats
            }
        }, history: {
            try await services.withBackgroundWallet {
                try await services.ensureWalletOpen()
                try await services.session.sync()
                try Task.checkCancellation()
                return try await services.session.transactionHistory()
            }
        }, lightningAddress: {
            SettingsStorage.shared.persistedState(PersistedServerState.self, key: PersistedServerState.key)?.lightningAddress
        }, fiat: { sats, locale in
            // Home's latest quote, which the widgets also show; Siri never fetches one.
            let display = WidgetDataWriter.Display(settings: .shared), variant = AppVariant.current
            guard !display.hidden, let defaults = UserDefaults(suiteName: variant.appGroup),
                  let quote = WidgetSnapshot.read(from: defaults, variant: variant.rawValue)?.fiat, quote.currency == display.currency else { return nil }
            return FiatCurrency.display(sats: Int64(clamping: sats), btcPrice: quote.btcPrice, currency: quote.currency, locale: Locale(identifier: locale))
        })
    }
}
