import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

struct OnchainRecoveryTests {
    private func quote(_ action: OnchainAction, _ ids: [String] = ["a"]) -> OnchainQuote {
        var quote = OnchainJournalTests.quote
        quote.id = UUID()
        quote.action = action
        quote.ids = ids
        return quote
    }
    private actor CachedReads {
        var calls: [String] = []
        func transactions() -> Set<String> { calls.append("transactions"); return ["exact-txid"] }
        func vtxo(_ id: String) -> String { calls.append("vtxo:\(id)"); return "Locked" }
        func exit(_ id: String) -> String? { calls.append("exit:\(id)"); return nil }
    }
    @Test func statusCheckUsesOnlyCachedReadsAndPreservesMissingOrLockedEvidence() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let cache = CachedReads()
        let reads = WalletSession.OnchainAttemptReads(
            walletID: "wallet", transactionIDs: { await cache.transactions() },
            vtxoState: { await cache.vtxo($0) }, exitState: { await cache.exit($0) })
        let board = quote(.board, [])
        await #expect(throws: OnchainFailure.uncertain) {
            try await journal.perform(board) { throw OnchainFailure.uncertain }
        }
        #expect(try await !WalletSession.checkOnchainAttempt(board.id, journal: journal, reads: reads))
        try await journal.recordTransaction("exact-txid", for: board.id)
        #expect(try await WalletSession.checkOnchainAttempt(board.id, journal: journal, reads: reads))
        let refresh = quote(.refresh)
        await #expect(throws: OnchainFailure.uncertain) {
            try await journal.perform(refresh) { throw OnchainFailure.uncertain }
        }
        #expect(try await !WalletSession.checkOnchainAttempt(refresh.id, journal: journal, reads: reads))
        #expect(await cache.calls == ["transactions", "transactions", "transactions", "vtxo:a", "exit:a"])
        #expect(try await journal.loadAll().first(where: { $0.id == refresh.id })?.receipt == nil)
        var wrong = reads
        wrong.walletID = "other"
        await #expect(throws: OnchainFailure.changed) {
            try await WalletSession.checkOnchainAttempt(refresh.id, journal: journal, reads: wrong)
        }
        #expect(await cache.calls.count == 5)
    }
    @Test func uncertaintyBlocksOnlyDuplicateOperationAndOverlappingItems() {
        for action in [OnchainAction.board, .refresh, .startExit, .progress, .claim, .cancel] {
            let attempt = OnchainOperationJournal.Attempt(quote: quote(action))
            for next in [OnchainAction.board, .refresh, .startExit, .progress, .claim, .cancel] {
                #expect(attempt.blocks(next, ids: ["a", "b"]) ==
                    (action == next && ![.startExit, .cancel].contains(action)))
                #expect(attempt.blocks(next, ids: ["b"]) == (action == .board && next == .board))
            }
        }
    }
    @Test func narrowlyClassifiesTypedPreBroadcastErrors() {
        for action in [OnchainAction.board, .claim, .refresh, .startExit, .cancel, .progress] {
            for code in BarkErrorCode.allCases {
                let result = OnchainSubmission.preBroadcast(BarkError(message: "diagnostic", code: code), action: action)
                #expect((result != nil) == (action == .board && [
                    .insufficientFunds,
                    .dust,
                    .invalidAmount,
                    .invalidFeeRate,
                    .preparedTransactionNotFound,
                    .preparedInputsUnavailable,
                    .unsupportedOperation,
                    .belowMinimumBoardAmount,
                    .tooManyInputs,
                    .preparedBoardChanged,
                ].contains(code)))
            }
        }
        #expect(OnchainSubmission.preBroadcast(BarkError(message: "Drain exits failed: failure"), action: .claim) == nil)
    }
    @Test func preBroadcastFailureClearsButUnknownSurvivesRelaunchAndAllowsRecovery() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let claim = quote(.claim)
        await #expect(throws: OnchainFailure.claimFeeChanged) {
            try await journal.perform(claim) { throw OnchainFailure.claimFeeChanged }
        }
        #expect(try await OnchainOperationJournal(file: file).loadAll().isEmpty)
        let board = quote(.board, [])
        await #expect(throws: OnchainFailure.uncertain) {
            try await journal.perform(board) { throw BarkError(message: "timeout") }
        }
        let restarted = OnchainOperationJournal(file: file)
        for action in [OnchainAction.startExit, .claim, .cancel, .refresh, .progress] {
            let q = quote(action)
            _ = try await restarted.perform(q) { "recovery" }
            try await restarted.acknowledge(q.id)
        }
        #expect(try await restarted.loadAll().map(\.id) == [board.id])
        let duplicate = quote(.board, [])
        await #expect(throws: OnchainFailure.busy) {
            try await restarted.perform(duplicate) { Issue.record("duplicate board"); return "bad" }
        }
        try await restarted.discardUncertain(board.id)
        let retained = try #require(try await OnchainOperationJournal(file: file).load())
        #expect(retained.id == board.id && retained.overrideAudit != nil)
        #expect(!retained.blocks(.board, ids: []))
    }
    @Test func checkStatusRequiresConclusiveEvidenceAndNeverMatchesAmount() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        for action in [OnchainAction.board, .refresh, .startExit, .progress, .claim, .cancel] {
            let journal = OnchainOperationJournal(file: file)
            let q = quote(action)
            await #expect(throws: OnchainFailure.uncertain) {
                try await journal.perform(q) { throw OnchainFailure.uncertain }
            }
            var evidence = OnchainOperationJournal.Evidence(walletID: "wallet")
            #expect(try await journal.reconcile(q.id, evidence: evidence) == false)
            evidence.vtxoStates = ["a": "Spendable"]
            evidence.exitStates = ["a": "Processing"]
            if action != .startExit { #expect(try await journal.reconcile(q.id, evidence: evidence) == false) }
            switch action {
            case .board:
                try await journal.recordTransaction("exact-txid", for: q.id)
                evidence.transactionIDs = ["different-txid"]
                #expect(try await journal.reconcile(q.id, evidence: evidence) == false)
                evidence.transactionIDs = ["exact-txid"]
            case .refresh: evidence.vtxoStates = ["a": "Spent"]
            case .startExit: break
            case .progress: evidence.exitStates = ["a": "Claimable"]
            case .claim: evidence.exitStates = ["a": "ClaimInProgress"]
            case .cancel: evidence.exitStates = ["a": "Canceled"]
            }
            var wrong = evidence
            wrong.walletID = "other"
            #expect(try await journal.reconcile(q.id, evidence: wrong) == false)
            let restarted = OnchainOperationJournal(file: file)
            #expect(try await restarted.reconcile(q.id, evidence: evidence))
            #expect(try await OnchainOperationJournal(file: file).load()?.receipt != nil)
            try await restarted.acknowledge(q.id)
        }
    }
    @Test func migratesLegacySingleAttemptAndPreservesItWhenAddingRecovery() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let legacy = OnchainOperationJournal.Attempt(quote: quote(.board, []))
        try JSONEncoder().encode(legacy).write(to: file)
        let journal = OnchainOperationJournal(file: file)
        let cancel = quote(.cancel)
        _ = try await journal.perform(cancel) { "canceled" }
        #expect(try await OnchainOperationJournal(file: file).loadAll().count == 2)
        try await journal.acknowledge(cancel.id)
        #expect(try await journal.load()?.id == legacy.id)
    }
}
