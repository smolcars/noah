import Foundation

/// The view cancels observation when its receipt, tab, scene or wallet becomes unavailable.
/// One cadence for the status check and the block row (send-receive.md): on appear, then
/// every 30 s while visible, reading the chain tip just before each check.
@MainActor enum SendReceiptRefresh {
    static let interval: Duration = .seconds(30)

    static func observe(tip: () async -> Void = {}, check: () async -> Void,
                        sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) async {
        while !Task.isCancelled {
            await tip()
            await check()
            do { try await sleep(interval) } catch { return }
        }
    }
}
