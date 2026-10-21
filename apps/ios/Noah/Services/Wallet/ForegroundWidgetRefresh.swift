import Foundation

/// Summary and expiry share the caller's foreground admission, never payment settlement.
@MainActor enum ForegroundWidgetRefresh {
    static func read(summary: () async throws -> WalletSession.Summary?,
                     expiry: () async throws -> Int?,
                     checkStage: () throws -> Void,
                     publish: (WalletBalance, Int?) -> Void) async throws -> WalletSession.Summary? {
        try checkStage()
        let latest = try await summary()
        try checkStage()
        guard let latest else { return nil }
        do {
            let blocks = try await expiry()
            try checkStage()
            publish(latest.balance, blocks)
        } catch is CancellationError { throw CancellationError() }
        catch { try checkStage() } // An unavailable tip must not hide a known expiry or the balance.
        return latest
    }
}
