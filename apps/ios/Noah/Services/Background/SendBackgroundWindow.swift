import UIKit

/// Keeps a payment moving for a short while after Noah leaves the front (async-payments.md, "Handoff"). While iOS
/// grants background time, bark's mailbox processor stays on and a Lightning send bark is still paying is checked
/// until it ends; a payment call that is still running gets the same time to return. Nothing here pays.
@MainActor final class SendBackgroundWindow {
    static let shared = SendBackgroundWindow()
    private(set) var isOpen = false
    private var work: Task<Void, Never>?
    private var identifier = UIBackgroundTaskIdentifier.invalid

    /// Opens when Noah enters the background with a payment in flight. iOS may grant no time at all.
    func open(controller: SendPaymentController = .shared, session: WalletSession = .shared) {
        guard !isOpen, controller.state == .submitting || controller.attempt?.pending != nil else { return }
        identifier = UIApplication.shared.beginBackgroundTask(withName: "pending payment") { [weak self] in self?.close() }
        guard identifier != .invalid else { return }
        isOpen = true
        work = Task {
            await SendPendingWatch.run(controller: controller, driveEvery: 10) { try await session.pendingSendLightningHashes() }
            self.close()
        }
    }

    /// Closes once: the payment ended, iOS's time ran out, or Noah is in front again. The mailbox processor then
    /// follows the app's state, and the background task ends after it has.
    func close(session: WalletSession = .shared) {
        guard isOpen else { return }
        isOpen = false
        work?.cancel()
        work = nil
        let ending = identifier
        identifier = .invalid
        Task {
            await session.reconcileMailboxProcessor()
            UIApplication.shared.endBackgroundTask(ending)
        }
    }
}
