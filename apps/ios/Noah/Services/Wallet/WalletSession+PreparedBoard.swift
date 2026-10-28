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
                if !held.claimed && held.quote.automatic { unsubmittedBoardReviews[held.quote.id] = held.quote }
                boardPreparations.removeValue(forKey: id)
                return
            } catch {
                AppLog("board").w(#"Retaining prepared board for cleanup retry"#)
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

    /// A missing preparation alone is never evidence. Retire a known unclaimed one first.
    func abandonUnsubmittedAutoBoard(_ quote: OnchainQuote,
                                     journal: OnchainOperationJournal = .shared) async throws -> Bool {
        guard quote.automatic, quote.action == .board, let id = quote.prepared?.id else { return false }
        if let held = boardPreparations[id] {
            guard held.quote == quote, !held.claimed else { return false }
            await discardBoard(quote)
        }
        guard unsubmittedBoardReviews[quote.id] == quote else { return false }
        let attempts = try await journal.loadAll()
        guard !attempts.contains(where: { $0.id == quote.id || $0.quote.prepared?.id == id ||
                $0.txid == quote.prepared?.txid || $0.quote.prepared?.txid == quote.prepared?.txid
        }) else { return false }
        unsubmittedBoardReviews.removeValue(forKey: quote.id)
        return true
    }

    func submitBoard(_ quote: OnchainQuote, walletID: String, journal: OnchainOperationJournal,
                     confirmed: @Sendable () async throws -> UInt64,
                     claims: @Sendable () async throws -> AutoBoardFunding.Claims = { .init() },
                     funding: AutoBoardFunding.Snapshot? = nil,
                     readFunding: @Sendable () async throws -> AutoBoardFunding.Snapshot? = { nil }) async throws -> String {
        guard let prepared = quote.prepared, var held = boardPreparations[prepared.id],
              !held.claimed, !held.discardRequested, held.quote == quote, quote.walletID == walletID,
              quote.isCurrent else { throw OnchainFailure.changed }
        let id = prepared.id
        if quote.automatic {
            do {
                // Unselected reserve coins are not reserved. Fixed costs require at least the
                // reviewed confirmed balance to preserve the exact reserve shown.
                if let funding {
                    guard funding.walletID == walletID, try await readFunding() == funding else { throw OnchainFailure.changed }
                }
                let balance = try await confirmed()
                // Exit claims to this phone stay on-chain (Decision 8): rechecked against the board as signed.
                let claimed = try await claims()
                guard balance >= quote.confirmed, quote.isCurrent, !quote.spends(claimed.outpoints),
                      let chainFee = quote.chainFee, quote.amount + chainFee + BoardingRules.reserve + claimed.held <= balance,
                      let current = boardPreparations[id], !current.claimed, !current.discardRequested,
                      current.quote == quote else { throw OnchainFailure.changed }
                held = current
            } catch {
                await discardBoard(quote)
                throw (error as? OnchainFailure) ?? OnchainFailure.unavailable
            }
        }
        held.claimed = true
        held.timeout?.cancel()
        boardPreparations[id] = held
        do {
            let submit = held.submit
            let message = try await journal.perform(quote) {
                try await journal.recordTransaction(prepared.txid, for: quote.id)
                let result: BoardResult
                // FFI 0.7.0 throws only before Bark records the board (after that it returns an outcome): a
                // confirmed-only parent reorged out is typed (unavailable inputs), an unreachable chain source isn't.
                do { result = try await submit() }
                catch let error as BarkError where OnchainSubmission.boardFailure(error) == nil {
                    throw OnchainFailure.boardNotAttempted
                }
                if case .uncertain = result.broadcast { try await journal.markBoardUncertain(quote.id) }
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
