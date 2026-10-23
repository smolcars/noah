import Foundation

/// The view cancels observation when its receipt, tab, scene or wallet becomes unavailable.
/// One cadence for the status check and the block row (send-receive.md): every 30 s while
/// visible, reading the chain tip just before each check. `wait` says how long until the
/// next check is due; the model remembers the last automatic one, so returning to Send
/// doesn't add a check.
@MainActor enum SendReceiptRefresh {
    static let interval: TimeInterval = 30

    static func observe(wait: () -> Duration = { .zero }, tip: () async -> Void = {}, check: () async -> Void,
                        sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) async {
        var due = wait()
        while !Task.isCancelled {
            if due > .zero { do { try await sleep(due) } catch { return } }
            await tip()
            await check()
            // A check that recorded nothing still waits a full interval.
            due = wait()
            if due == .zero { due = .seconds(interval) }
        }
    }
}
