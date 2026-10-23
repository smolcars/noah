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
        while !Task.isCancelled {
            // Asked again after waiting: the entry check may have run meanwhile.
            let due = wait()
            if due > .zero { do { try await sleep(due) } catch { return }; continue }
            await tip()
            // Send was left, or the entry check ran, while the tip was read: no check now.
            guard !Task.isCancelled else { return }
            if wait() > .zero { continue }
            await check()
            // A check that recorded nothing still waits a full interval.
            if wait() == .zero { do { try await sleep(.seconds(interval)) } catch { return } }
        }
    }
}
