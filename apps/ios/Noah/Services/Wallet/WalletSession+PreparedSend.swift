import BarkBoltFFI
import Foundation

extension WalletSession {
    struct SendPreparation {
        var payment: SendPayment
        var quote: SendQuote
        var onchain: OnchainWallet
        var timeout: Task<Void, Never>?
        var claimed = false
        var broadcasting = false
    }

    func retainSendReview(_ payment: SendPayment, quote: SendQuote, onchain: OnchainWallet) {
        guard let prepared = quote.prepared else { return }
        let timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(SendPreparedTransaction.reviewLifetime)) }
            catch { return }
            await self?.discardSend(quote)
        }
        sendPreparations[prepared.id] = .init(payment: payment, quote: quote, onchain: onchain, timeout: timeout)
    }

    func claimSendReview(_ payment: SendPayment, quote: SendQuote) throws {
        guard let prepared = quote.prepared, prepared.isCurrent,
              var held = sendPreparations[prepared.id], !held.claimed,
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
        guard let id = quote.prepared?.id, let held = sendPreparations[id],
              includingClaimed || !held.claimed else { return }
        sendPreparations.removeValue(forKey: id)
        held.timeout?.cancel()
        do { try await held.onchain.discardPrepared(id: id) }
        catch { AppLog("send").w("Prepared send handle could not be discarded") }
    }

    /// Called only after counted calls have drained and new calls are blocked by close.
    func discardSendReviews() async {
        for held in Array(sendPreparations.values) {
            await discardSend(held.quote, includingClaimed: true)
        }
    }
}
