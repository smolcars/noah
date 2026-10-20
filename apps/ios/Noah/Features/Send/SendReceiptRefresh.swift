import Foundation

/// The view cancels observation when its receipt, tab, scene or wallet becomes unavailable.
@MainActor enum SendReceiptRefresh {
    static func observe(check: () async -> Void,
                        sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) async {
        while !Task.isCancelled {
            await check()
            do { try await sleep(.seconds(10)) } catch { return }
        }
    }
}
