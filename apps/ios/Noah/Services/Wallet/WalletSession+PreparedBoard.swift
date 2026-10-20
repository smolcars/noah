import BarkBoltFFI
import Foundation

extension WalletSession {
    struct BoardPreparation {
        var quote: OnchainQuote
        var discard: @Sendable () async throws -> Void
        var submit: @Sendable () async throws -> BoardResult
        var timeout: Task<Void, Never>?
        var discardTask: Task<Void, Never>?
        var claimed = false
        var discardRequested = false
    }

    func retainBoardReview(_ quote: OnchainQuote, wallet: Wallet) {
        guard let id = quote.prepared?.id else { return }
        retainBoardReview(quote, discard: { try await wallet.discardPreparedBoard(id: id) },
                          submit: { try await wallet.submitPreparedBoard(id: id) })
    }

    func retainBoardReview(_ quote: OnchainQuote, discard: @escaping @Sendable () async throws -> Void,
                           submit: @escaping @Sendable () async throws -> BoardResult) {
        guard let id = quote.prepared?.id else { return }
        let timeout = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(120)) } catch { return }
            await self?.discardBoard(quote)
        }
        boardPreparations[id] = .init(quote: quote, discard: discard, submit: submit, timeout: timeout)
    }

    func discardBoard(_ quote: OnchainQuote, includingClaimed: Bool = false) async {
        guard let id = quote.prepared?.id, var held = boardPreparations[id],
              includingClaimed || !held.claimed else { return }
        if let task = held.discardTask { await task.value; return }
        held.discardRequested = true
        let task = Task { await self.performBoardDiscard(id) }
        held.discardTask = task
        boardPreparations[id] = held
        await task.value
    }

    private func performBoardDiscard(_ id: String) async {
        guard let held = boardPreparations[id] else { return }
        for retry in 0..<3 {
            do {
                do { try await held.discard() }
                catch let error as BarkError where error.code == .persistence && held.claimed {
                    // Acceptance already happened. Retry only this ID to cache the same bytes.
                    _ = try await held.submit()
                    try await held.discard()
                }
                held.timeout?.cancel()
                boardPreparations.removeValue(forKey: id)
                return
            } catch {
                AppLog("board").w("Retaining prepared board for cleanup retry")
                if retry < 2 { try? await Task.sleep(for: .milliseconds(100)) }
            }
        }
        boardPreparations[id]?.discardTask = nil
    }

    func retryBoardDiscards() async {
        for held in Array(boardPreparations.values) where held.discardRequested {
            await discardBoard(held.quote, includingClaimed: true)
        }
    }

    func discardBoardReviews() async throws {
        for held in Array(boardPreparations.values) {
            await discardBoard(held.quote, includingClaimed: true)
        }
        guard boardPreparations.isEmpty else { throw OnchainFailure.uncertain }
    }

    func submitBoard(_ quote: OnchainQuote, walletID: String, journal: OnchainOperationJournal,
                     confirmed: @Sendable () async throws -> UInt64) async throws -> String {
        guard let id = quote.prepared?.id, var held = boardPreparations[id],
              !held.claimed, !held.discardRequested, held.quote == quote, quote.walletID == walletID,
              quote.isCurrent else { throw OnchainFailure.changed }
        if quote.automatic {
            do {
                // Unselected reserve coins are not reserved. Fixed costs require at least the
                // reviewed confirmed balance to preserve the exact reserve shown.
                let balance = try await confirmed()
                guard balance >= quote.confirmed, quote.isCurrent,
                      let current = boardPreparations[id], !current.claimed, !current.discardRequested,
                      current.quote == quote else { throw OnchainFailure.changed }
                held = current
            } catch {
                await discardBoard(quote)
                throw OnchainFailure.changed
            }
        }
        held.claimed = true
        held.timeout?.cancel()
        boardPreparations[id] = held
        do {
            let submit = held.submit
            let message = try await journal.perform(quote) {
                try await journal.recordTransaction(quote.prepared!.txid, for: quote.id)
                let result = try await submit()
                return try OnchainSubmission.boardReceipt(result, quote: quote)
            }
            await discardBoard(quote, includingClaimed: true)
            return message
        } catch {
            await discardBoard(quote, includingClaimed: true)
            throw error
        }
    }
}
