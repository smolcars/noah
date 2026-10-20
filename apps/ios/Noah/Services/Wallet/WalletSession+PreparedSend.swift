import BarkBoltFFI
import Foundation

extension WalletSession {
    struct SendPreparation {
        var payment: SendPayment
        var quote: SendQuote
        var discard: @Sendable () async throws -> Void
        var timeout: Task<Void, Never>?
        var discardTask: Task<Void, Never>?
        var discardRequested = false
        var claimed = false
        var broadcasting = false
    }

    func retainSendReview(_ payment: SendPayment, quote: SendQuote, onchain: OnchainWallet) {
        retainSendReview(payment, quote: quote, discard: {
            if let id = quote.prepared?.id { try await onchain.discardPrepared(id: id) }
        })
    }

    func retainSendReview(_ payment: SendPayment, quote: SendQuote, discard: @escaping @Sendable () async throws -> Void) {
        guard let prepared = quote.prepared else { return }
        let timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(SendPreparedTransaction.reviewLifetime)) }
            catch { return }
            await self?.discardSend(quote)
        }
        sendPreparations[prepared.id] = .init(payment: payment, quote: quote, discard: discard, timeout: timeout)
    }

    func claimSendReview(_ payment: SendPayment, quote: SendQuote) throws {
        guard let prepared = quote.prepared, prepared.isCurrent,
              var held = sendPreparations[prepared.id], !held.claimed, !held.discardRequested,
              held.payment == payment, held.quote == quote else { throw SendFailure.feeChanged }
        held.timeout?.cancel()
        held.timeout = nil
        held.claimed = true
        sendPreparations[prepared.id] = held
    }

    func consumeSendReview(_ payment: SendPayment, quote: SendQuote) -> Bool {
        guard let id = quote.prepared?.id, var held = sendPreparations[id],
              held.claimed, !held.broadcasting, held.payment == payment, held.quote == quote else { return false }
        held.broadcasting = true
        sendPreparations[id] = held
        return true
    }

    /// UI cancellation cannot release a review after confirmation has claimed it.
    func discardSend(_ quote: SendQuote, includingClaimed: Bool = false) async {
        guard let id = quote.prepared?.id, var held = sendPreparations[id],
              includingClaimed || !held.claimed else { return }
        if let running = held.discardTask { await running.value; return }
        held.discardRequested = true
        // Session-owned work must outlive a cancelled review task, including FFI cancellation.
        let task = Task { await self.performSendDiscard(id) }
        held.discardTask = task
        sendPreparations[id] = held
        await task.value
    }

    private func performSendDiscard(_ id: String) async {
        guard let held = sendPreparations[id] else { return }
        for retry in 0..<3 {
            do {
                try await held.discard()
                held.timeout?.cancel()
                sendPreparations.removeValue(forKey: id)
                return
            } catch {
                AppLog("send").w("Prepared send handle could not be discarded; retaining it for retry")
                if retry < 2 { try? await Task.sleep(for: .milliseconds(100)) }
            }
        }
        sendPreparations[id]?.discardTask = nil
    }

    func retrySendDiscards() async {
        for held in Array(sendPreparations.values) where held.discardRequested {
            await discardSend(held.quote, includingClaimed: true)
        }
    }

    /// Called only after counted calls have drained and new calls are blocked by close.
    func discardSendReviews() async throws {
        for held in Array(sendPreparations.values) {
            await discardSend(held.quote, includingClaimed: true)
        }
        guard sendPreparations.isEmpty else { throw SendFailure.unresolved }
    }
}
