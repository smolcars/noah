import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

@MainActor struct ChainTransactionStatusTests {
    @Test func offboardTracksMempoolConfirmationAndReorgWithoutChangingIdentity() throws {
        let original = SendReceipt(amount: 850, txid: "exact", status: .submitted, fee: 150, proof: .submitReturnedTxid)
        var receipt = original
        for status in [ChainTransactionStatus.notFound, .inMempool, .confirmed(123), .notFound] {
            receipt = SendReconciliation.chainEvidence(status, receipt: receipt, source: .offchain)
            #expect(receipt.txid == original.txid && receipt.amount == original.amount && receipt.fee == original.fee)
            #expect(receipt.chainStatus == status)
            #expect(receipt.status == (status == .confirmed(123) ? .confirmed : .submitted))
        }
        #expect(ChainTransactionStatus(.confirmed(height: 123)).message == "Confirmed at block 123.")
        #expect(ChainTransactionStatus(.notFound).message.contains("Do not submit it again"))
    }
    @Test func chainStatusCannotWeakenBDKLocksOrConfirmedCompetingSpendEvidence() {
        for state in [SendReceipt.Status.unlocated, .conflicted, .submitted] {
            let original = SendReceipt(amount: 1000, txid: "exact", status: state, fee: 143,
                                       proof: .preparedTxid, broadcastRetryPending: true,
                                       confirmedConflictTxid: state == .conflicted ? "competitor" : nil)
            for status in [ChainTransactionStatus.notFound, .inMempool, .confirmed(123)] {
                let result = SendReconciliation.chainEvidence(status, receipt: original, source: .onchain)
                #expect(result.status == original.status && result.broadcastRetryPending == true)
                #expect(result.confirmedConflictTxid == original.confirmedConflictTxid)
            }
        }
    }
    @Test func failedTransportPreservesLastKnownOffboardReceiptAndLock() async throws {
        let fixtures = SendSafetyTests()
        let storage = SendPaymentTests.Storage()
        var saved = fixtures.attempt("onchain", source: .offchain)
        saved.receipt = .init(amount: saved.payment.amount, txid: "exact", status: .confirmed,
                             proof: .submitReturnedTxid, chainStatus: .confirmed(123))
        storage.attempt = saved
        let controller = fixtures.controller(storage) { _ in throw URLError(.notConnectedToInternet) }
        await controller.checkStatus()
        #expect(controller.attempt?.receipt == saved.receipt && controller.locked)
        #expect(controller.message?.contains("last known status") == true)
    }
    @Test(arguments: [false, true]) func crashRecoveryUsesExactNetworkTxidAndNotFoundNeverResolves(known: Bool) async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let quote = BoardPreparedTests.quote
        await #expect(throws: OnchainFailure.uncertain) {
            try await journal.perform(quote) {
                try await journal.recordTransaction("exact", for: quote.id)
                throw OnchainFailure.uncertain
            }
        }
        let reads = WalletSession.OnchainAttemptReads(walletID: "wallet", transactionIDs: { ["lookalike"] },
            vtxoState: { _ in Issue.record("No amount or VTXO matching for boards"); return "Spendable" },
            exitState: { _ in nil }, transactionStatus: { txid in
                #expect(txid == "exact")
                return known ? .inMempool : .notFound
            })
        let resolved = try await WalletSession.checkOnchainAttempt(quote.id, journal: journal, reads: reads)
        #expect(resolved == known)
        let saved = try #require(try await OnchainOperationJournal(file: file).load())
        #expect(saved.blocks(.board, ids: []))
        if known {
            #expect(saved.receipt?.chainStatus == .inMempool && saved.receipt?.onchainFee == 1543)
            #expect(saved.receipt?.broadcastRetryPending == true)
        } else { #expect(saved.receipt == nil) }
    }
    @Test func boardAndClaimStatusSurviveRestartAndTransportErrors() async throws {
        for action in [OnchainAction.board, .claim] {
            let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: file) }
            let journal = OnchainOperationJournal(file: file)
            var quote = BoardPreparedTests.quote
            quote.action = action
            _ = try await journal.perform(quote) {
                OnchainReceipt(message: "Submitted", txid: "exact", onchainFee: 1543)
            }
            let reads = WalletSession.OnchainAttemptReads(walletID: "wallet", transactionIDs: { [] },
                vtxoState: { _ in "" }, exitState: { _ in nil }, transactionStatus: { _ in .confirmed(123) })
            #expect(try await WalletSession.checkOnchainAttempt(quote.id, journal: journal, reads: reads))
            var failed = reads
            failed.transactionStatus = { _ in throw URLError(.notConnectedToInternet) }
            await #expect(throws: URLError.self) {
                try await WalletSession.checkOnchainAttempt(quote.id, journal: journal, reads: failed)
            }
            #expect(try await OnchainOperationJournal(file: file).load()?.receipt?.chainStatus == .confirmed(123))
        }
    }
    @Test func persistedTerminalExitsUseSDKAmountsAndTypedHistory() {
        for state in [ExitState.canceled(tipHeight: 100),
                      .claimed(tipHeight: 102, txid: "claim", block: .init(height: 101, hash: "block"))] {
            let entry = ExitTransactionStatus(vtxoId: "terminal", amountSats: 29_900, state: state,
                history: [.start(tipHeight: 90)], transactionCount: 0, transactions: [])
            let model = WalletSession.exitModel(entry)
            #expect(model.id == "terminal" && model.amount == 29_900)
            #expect(model.history == ["Start"] && model.timeline.count == 2)
            #expect(!model.cancelable && !model.claimable)
            #expect(["Canceled", "Claimed"].contains(model.state))
        }
    }
}
