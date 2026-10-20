import Foundation

/// Intent type names/parameters stay identical to RN. Only this adapter changes:
/// one process session, read-only seed admission, no create/restore/send APIs.
struct NoahIntentWallet: Sendable {
    struct Invoice: Sendable { let paymentRequest: String }
    @TaskLocal static var current = live()
    var newAddress: @Sendable () async throws -> String
    var bolt11Invoice: @Sendable (_ amountSat: UInt64, _ description: String?) async throws -> Invoice
    var refreshBalance: @Sendable () async throws -> UInt64

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
        })
    }
}
