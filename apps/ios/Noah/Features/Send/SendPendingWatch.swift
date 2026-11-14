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

    /// A push's one status check. A check asked for while another is under way returns at once, so this waits out a
    /// check under way before and after its own, until cancelled. True once the payment is no longer pending.
    static func checkOnce(controller: SendPaymentController,
                          sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) async throws -> Bool {
        func settled() async throws {
            while controller.checking { try Task.checkCancellation(); try await sleep(.milliseconds(100)) }
        }
        try await settled()
        await controller.checkStatus()
        try await settled()
        return !controller.lightningSendPending
    }
}
