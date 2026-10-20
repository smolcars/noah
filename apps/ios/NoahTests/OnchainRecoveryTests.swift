import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

struct OnchainRecoveryTests {
    private func quote(_ action: OnchainAction, _ ids: [String] = ["a"]) -> OnchainQuote {
        var quote = OnchainJournalTests.quote
        quote.id = UUID()
        quote.action = action
        quote.ids = ids
        return quote
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
    @Test func narrowlyClassifiesPreBroadcastErrors() {
        for (action, message) in [
            (OnchainAction.claim, "Drain exits failed: no signable clause"),
            (.claim, "Failed to get fee: missing input"),
            (.claim, "invalid PSBT: invalid base64"),
            (.claim, "Failed to extract transaction: missing witness"),
            (.claim, "No claimable exits found"),
            (.claim, "invalid bitcoin address: invalid checksum"),
            (.board, "Board failed: error building tx: insufficient funds"),
            (.board, "Board all failed: error building tx: insufficient funds"),
            (.refresh, "invalid VTXO id: malformed"),
            (.cancel, "invalid vtxo id: malformed")
        ] {
            #expect(OnchainSubmission.preBroadcast(BarkError(message: message), action: action) != nil)
            #expect(OnchainSubmission.preBroadcast(BarkError(message: "broadcast failed: " + message), action: action) == nil)
        }
        for message in ["unknown", "Board failed: database failed", "Progress exits failed: timeout",
                        "Board failed: broadcast failed: error building tx: insufficient funds"] {
            #expect(OnchainSubmission.preBroadcast(BarkError(message: message), action: .board) == nil)
        }
        #expect(OnchainSubmission.preBroadcast(BarkError(message: "Drain exits failed: failure"), action: .refresh) == nil)
    }
    @Test func preBroadcastFailureClearsButUnknownSurvivesRelaunchAndAllowsRecovery() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let claim = quote(.claim)
        await #expect(throws: OnchainFailure.constructionFailed) {
            try await journal.perform(claim) { throw BarkError(message: "Drain exits failed: failure") }
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
        #expect(try await OnchainOperationJournal(file: file).loadAll().isEmpty)
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
