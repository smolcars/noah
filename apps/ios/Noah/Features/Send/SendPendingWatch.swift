import Foundation

/// Notices when bark ends a send it was still paying. Bark's mailbox settles a send without telling the app; its
/// pending list is a local read, so once the send leaves it a status check records the outcome. A caller with no
/// other periodic check (the background window) also asks for one every `driveEvery` seconds. Nothing here pays.
@MainActor enum SendPendingWatch {
    /// Runs until the attempt is no longer paying (or about to be: its pay call is still running).
    static func run(controller: SendPaymentController, driveEvery: Int? = nil,
                    pendingHashes: @Sendable () async throws -> Set<String>,
                    sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) async {
        var seconds = 0
        while !Task.isCancelled, controller.state == .submitting || controller.attempt?.pending != nil {
            if let hash = controller.attempt?.pending?.paymentHash {
                let ended = (try? await pendingHashes()).map { !$0.contains(hash) } ?? false
                if ended || driveEvery.map({ seconds.isMultiple(of: $0) }) == true { await controller.checkStatus() }
            }
            seconds += 1
            do { try await sleep(.seconds(1)) } catch { return }
        }
    }
}
