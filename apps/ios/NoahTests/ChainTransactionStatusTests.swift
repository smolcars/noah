import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

@MainActor struct ChainTransactionStatusTests {
    @Test func offboardTracksMempoolConfirmationAndReorgWithoutChangingIdentity() {
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
    @Test func offboardAbsenceOrMempoolKeepsUnresolvedReceiptsLocked() {
        for state in [SendReceipt.Status.unlocated, .conflicted] {
            let original = SendReceipt(amount: 850, txid: "exact", status: state, fee: 150, proof: .submitReturnedTxid)
            for status in [ChainTransactionStatus.notFound, .inMempool] {
                let result = SendReconciliation.chainEvidence(status, receipt: original, source: .offchain)
                #expect(result.status == state && result.isUnresolved)
            }
        }
        let unlocated = SendReceipt(amount: 850, txid: "exact", status: .unlocated, fee: 150, proof: .submitReturnedTxid)
        #expect(SendReconciliation.chainEvidence(.confirmed(123), receipt: unlocated, source: .offchain).status == .confirmed)
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
    @Test func failedTransportPreservesLastKnownOffboardReceiptAndLock() async {
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
    @Test(arguments: [false, true], [false, true])
    func bdkDowngradeSurvivesStatusErrorInEitherRefreshOrder(conflict: Bool, errorFirst: Bool) async throws {
        let fixtures = SendSafetyTests()
        let original = try fixtures.transaction(id: "exact", confirmed: true)
        let competitor = try fixtures.transaction(id: "competitor", confirmed: true)
        var quote = SendPreparedTests.quote
        quote.prepared?.inputs = try SendTransaction.parse(hex: original.txHex).inputs
        var saved = fixtures.attempt("onchain", source: .onchain)
        saved.quote = quote
        saved.receipt = .init(amount: 1000, txid: "exact", status: .confirmed, fee: 100,
                              proof: .preparedTxid, chainStatus: .confirmed(100))
        let storage = SendPaymentTests.Storage()
        storage.attempt = saved
        if errorFirst {
            let before = fixtures.controller(storage) { attempt in
                try await SendReconciliation.chainEvidence(
                    SendReconciliation.onchain(attempt, transactions: [original]), source: .onchain,
                    lookup: { _ in throw URLError(.notConnectedToInternet) })
            }
            await before.checkStatus()
            #expect(before.attempt?.receipt?.status == .confirmed)
            #expect(before.message != nil)
        }
        let transactions = conflict ? [competitor] : []
        let controller = fixtures.controller(storage) { attempt in
            try await SendReconciliation.chainEvidence(
                SendReconciliation.onchain(attempt, transactions: transactions), source: .onchain,
                lookup: { txid in
                    #expect(txid == "exact")
                    if !errorFirst { throw URLError(.notConnectedToInternet) }
                    return .confirmed(100) // Even stale confirmation cannot defeat BDK's downgrade.
                })
        }
        await controller.checkStatus()
        #expect(controller.attempt?.receipt?.status == (conflict ? .conflicted : .unlocated))
        #expect(controller.attempt?.receipt?.chainStatus == .confirmed(100))
        #expect(controller.attempt?.receipt?.confirmedConflictTxid == (conflict ? "competitor" : nil))
        #expect(controller.attempt?.receipt?.chainStatusError == nil)
        #expect((controller.message != nil) == !errorFirst)
        #expect(controller.state == .uncertain)
        controller.acknowledge()
        #expect(controller.locked)
        storage.attempt = try fixtures.roundTrip(#require(storage.attempt))
        let restarted = fixtures.controller(storage) { attempt in
            try await SendReconciliation.chainEvidence(
                SendReconciliation.onchain(attempt, transactions: transactions), source: .onchain,
                lookup: { _ in throw URLError(.notConnectedToInternet) })
        }
        #expect(restarted.message == storage.attempt?.receipt?.chainStatusError)
        await restarted.checkStatus()
        #expect(restarted.state == .uncertain && restarted.message != nil)
        restarted.acknowledge()
        #expect(restarted.locked)
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
    @Test(arguments: [false, true])
    func exactCachedBoardRecoverySurvivesOptionalStatusFailure(known: Bool) async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let quote = BoardPreparedTests.quote
        await #expect(throws: OnchainFailure.uncertain) {
            try await journal.perform(quote) {
                try await journal.recordTransaction("exact", for: quote.id)
                throw OnchainFailure.uncertain // Crash after submission, before saving its receipt.
            }
        }
        let restarted = OnchainOperationJournal(file: file)
        let reads = WalletSession.OnchainAttemptReads(walletID: "wallet",
                                                      transactionIDs: { known ? ["exact"] : ["lookalike"] },
                                                      vtxoState: { _ in Issue.record("Never match boards by amount or VTXO"); return "Spendable" },
                                                      exitState: { _ in nil }, transactionStatus: { txid in
                                                          #expect(txid == "exact")
                                                          throw URLError(.notConnectedToInternet)
                                                      })
        #expect(try await WalletSession.checkOnchainAttempt(quote.id, journal: restarted, reads: reads) == known)
        let saved = try #require(try await OnchainOperationJournal(file: file).load())
        #expect((saved.receipt != nil) == known)
        #expect(saved.txid == "exact" && saved.blocks(.board, ids: []))
        #expect(saved.chainStatusError == nil, "Lookup failures are not persisted evidence")
        #expect(try await restarted.load()?.chainStatusError == ChainTransactionStatus.errorText)
        if known {
            #expect(saved.receipt?.txid == "exact" && saved.receipt?.onchainFee == 1543)
            #expect(saved.receipt?.arkFee == 100 && saved.receipt?.broadcastRetryPending == true)
        }
        await #expect(throws: OnchainFailure.uncertain) { try await restarted.acknowledge(quote.id) }
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.attempt = { try await restarted.loadAll() }
        dependencies.check = { try await WalletSession.checkOnchainAttempt($0, journal: restarted, reads: reads) }
        let model = OnchainModel(dependencies: dependencies)
        await model.checkStatus(quote.id)
        #expect(model.attempts.first?.receipt?.txid == (known ? "exact" : nil))
        #expect(model.attempts.first?.chainStatusError == ChainTransactionStatus.errorText)
        #expect(model.message == (known ? "Status resolved. Review the receipt."
                : "Status remains uncertain. Noah checks again automatically as your wallet syncs."))
        #expect(await calls.mutations == 0)
        var recovered = reads
        recovered.transactionStatus = { _ in .notFound }
        #expect(try await WalletSession.checkOnchainAttempt(quote.id, journal: restarted, reads: recovered) == known)
        let checked = try #require(try await OnchainOperationJournal(file: file).load())
        #expect(checked.chainStatusError == nil && (checked.receipt != nil) == known)
        await #expect(throws: OnchainFailure.uncertain) { try await restarted.acknowledge(quote.id) }
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
            #expect(try await WalletSession.checkOnchainAttempt(quote.id, journal: journal, reads: failed))
            let saved = try #require(try await OnchainOperationJournal(file: file).load())
            #expect(saved.receipt?.chainStatus == .confirmed(123))
            #expect(saved.receipt?.txid == "exact" && saved.receipt?.onchainFee == 1543)
            #expect(saved.chainStatusError == nil)
            #expect(try await journal.load()?.chainStatusError == ChainTransactionStatus.errorText)
            #expect(try await WalletSession.checkOnchainAttempt(quote.id, journal: journal, reads: reads))
            #expect(try await OnchainOperationJournal(file: file).load()?.chainStatusError == nil)
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
