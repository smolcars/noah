import Foundation

/// Summary and the widget's facts share the caller's foreground admission, never payment settlement.
@MainActor enum ForegroundWidgetRefresh {
    static func read(summary: () async throws -> WalletSession.Summary?,
                     widget: () async throws -> WidgetDataWriter.Facts,
                     checkStage: () throws -> Void,
                     publish: (WalletBalance, WidgetDataWriter.Facts) -> Void) async throws -> WalletSession.Summary? {
        try checkStage()
        let latest = try await summary()
        try checkStage()
        guard let latest else { return nil }
        do {
            let facts = try await widget()
            try checkStage()
            publish(latest.balance, facts)
        } catch is CancellationError { throw CancellationError() }
        catch { try checkStage() } // An unreadable wallet keeps the widget as it is and the balance for Home.
        return latest
    }
}
