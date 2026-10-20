import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

struct OnchainJournalTests {
    static var quote: OnchainQuote {
        .init(
            action: .board, walletID: "wallet", ids: [], amount: 20000, fee: 100, chainFee: 208,
            confirmed: 40000)
    }
    @Test func persistsBeforeCallAndDoesNotRetryAfterFailureOrRestart() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appending(path: "attempt.json")
        let journal = OnchainOperationJournal(file: file)
        await #expect(throws: OnchainFailure.self) {
            try await journal.perform(Self.quote) {
                #expect(
                    try JSONDecoder().decode(
                        OnchainOperationJournal.Attempt.self, from: Data(contentsOf: file)
                    ).quote.amount == 20000)
                throw OnchainFailure.unavailable
            }
        }
        let restarted = OnchainOperationJournal(file: file)
        #expect(try await restarted.load()?.receipt == nil)
        await #expect(throws: OnchainFailure.self) {
            try await restarted.perform(Self.quote) {
                Issue.record("Must not repeat possibly broadcast operation")
                return "duplicate"
            }
        }
        await #expect(throws: OnchainFailure.self) { try await restarted.acknowledge() }
    }
    @Test func failedPersistencePreventsNativeCall() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString).appending(
            path: "missing/attempt.json")
        let journal = OnchainOperationJournal(file: file)
        await #expect(throws: (any Error).self) {
            try await journal.perform(Self.quote) {
                Issue.record("Must not submit without durable marker")
                return "bad"
            }
        }
    }
    @Test func successfulReceiptSurvivesRestartUntilAcknowledged() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        #expect(try await journal.perform(Self.quote) { "submitted txid" } == "submitted txid")
        let restarted = OnchainOperationJournal(file: file)
        #expect(try await restarted.load()?.receipt?.message == "submitted txid")
        try await restarted.acknowledge()
        #expect(try await restarted.load() == nil)
    }
    @Test func boardingReceiptRecordsActualFeeAndReadsOldJournals() throws {
        let receipt = OnchainReceipt(message: "Submitted", txid: "txid", onchainFee: 1543)
        let restored = try JSONDecoder().decode(OnchainReceipt.self, from: JSONEncoder().encode(receipt))
        #expect(restored.onchainFee == 1543)
        let old = try JSONDecoder().decode(OnchainReceipt.self, from: Data(#"{"message":"Submitted","txid":"txid"}"#.utf8))
        #expect(old.onchainFee == nil)
        var json = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(Self.quote)) as? [String: Any])
        json.removeValue(forKey: "boardAll")
        let quote = try JSONDecoder().decode(OnchainQuote.self, from: JSONSerialization.data(withJSONObject: json))
        #expect(quote.boardAll == nil)
    }
    @Test func quotePinsWalletSelectionDestinationAndBothFees() {
        let original = Self.quote
        var changed = original
        changed.walletID = "another-wallet"
        #expect(!original.matches(changed))
        changed = original
        changed.ids = ["vtxo"]
        #expect(!original.matches(changed))
        changed = original
        changed.destination = "new"
        #expect(!original.matches(changed))
        changed = original
        changed.chainFee += 1
        #expect(!original.matches(changed))
        changed = original
        changed.fee += 1
        #expect(!original.matches(changed))
        changed = original
        changed.confirmed += 1
        #expect(!original.matches(changed))
        changed = original
        changed.boardAll = true
        #expect(!original.matches(changed))
        changed = original
        changed.automatic = true
        #expect(!original.matches(changed))
        #expect(!ExitRules.current(reviewedAt: Date().addingTimeInterval(-30)))
    }
}

@MainActor
struct OnchainModelTests {
    actor Calls {
        var reads = 0
        var mutations = 0
        var ready = true
        var continuation: CheckedContinuation<OnchainQuote, Never>?
        func setReady(_ value: Bool) { ready = value }
        func read() -> OnchainOverview {
            reads += 1
            return .init(
                walletID: "wallet", vtxos: [], exits: [], confirmed: 40000, tip: 100, pendingTotal: 0)
        }
        func mutate() -> String {
            mutations += 1
            return "submitted"
        }
        func delayedQuote() async -> OnchainQuote {
            await withCheckedContinuation { continuation = $0 }
        }
        func finishQuote() {
            continuation?.resume(returning: OnchainJournalTests.quote)
            continuation = nil
        }
    }
    func dependencies(_ calls: Calls) -> OnchainModel.Dependencies {
        .init(
            ready: { await calls.ready }, overview: { await calls.read() },
            quote: { _, _, _, _ in OnchainJournalTests.quote },
            execute: { _ in await calls.mutate() }, attempt: { [] }, acknowledge: { _ in })
    }
    @Test func unavailableAndStaleRefreshNeverReadOrMutate() async {
        let calls = Calls()
        let model = OnchainModel(dependencies: dependencies(calls))
        await calls.setReady(false)
        await model.load()
        await model.review(.startExit, ids: ["a"])
        await model.confirm()
        #expect(await calls.reads == 0)
        #expect(await calls.mutations == 0)
        await calls.setReady(true)
        await model.load()
        #expect(await calls.reads == 1)
        #expect(await calls.mutations == 0)
        await calls.setReady(false)
        await model.load()
        #expect(model.overview == nil)
        #expect(await calls.reads == 1)
    }
    @Test func overviewAndEstimateNeverExecuteAndBackgroundInvalidatesReview() async {
        let calls = Calls()
        let model = OnchainModel(dependencies: dependencies(calls))
        await model.load()
        await model.review(.board, amount: 20000)
        #expect(model.quote != nil && model.error == nil)
        #expect(await calls.mutations == 0)
        model.invalidate()
        await model.confirm()
        #expect(await calls.mutations == 0)
    }
    @Test func lateEstimateCannotReplaceChangedInputs() async {
        let calls = Calls()
        var d = dependencies(calls)
        d.quote = { _, _, _, _ in await calls.delayedQuote() }
        let model = OnchainModel(dependencies: d)
        let work = Task { await model.review(.startExit, ids: ["old"]) }
        for _ in 0..<100 where await calls.continuation == nil { await Task.yield() }
        model.invalidate()
        await calls.finishQuote()
        await work.value
        #expect(model.quote == nil)
        #expect(await calls.mutations == 0)
    }
    @Test func exitReviewRequiresConfirmationAndSuspensionPreventsSubmission() async {
        let calls = Calls()
        var d = dependencies(calls)
        d.quote = { action, ids, _, _ in
            var quote = OnchainJournalTests.quote
            quote.action = action
            quote.ids = ids
            return quote
        }
        let model = OnchainModel(dependencies: d)
        for action in [OnchainAction.startExit, .progress] {
            await model.review(action, ids: ["vtxo"])
            #expect(await calls.mutations == (action == .startExit ? 0 : 1))
            await model.confirm()
        }
        #expect(await calls.mutations == 2)
        await model.review(.startExit, ids: ["vtxo"])
        await calls.setReady(false)
        await model.confirm()
        #expect(await calls.mutations == 2)
    }
    @Test func unresolvedBoardAllowsExitReviewAndStatusChecksNeverSubmit() async throws {
        let calls = Calls()
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let board = OnchainJournalTests.quote
        await #expect(throws: OnchainFailure.uncertain) {
            try await journal.perform(board) { throw OnchainFailure.uncertain }
        }
        var d = dependencies(calls)
        d.attempt = { try await journal.loadAll() }
        d.quote = { action, ids, _, _ in
            var q = OnchainJournalTests.quote
            q.action = action
            q.ids = ids
            return q
        }
        d.check = { id in
            try await journal.reconcile(id, evidence: .init(walletID: "wallet"))
        }
        d.discard = { try await journal.discardUncertain($0) }
        let model = OnchainModel(dependencies: d)
        await model.load()
        #expect(!model.locked)
        await model.review(.startExit, ids: ["a"])
        #expect(model.quote?.action == .startExit)
        await model.review(.board)
        #expect(model.quote == nil && model.error != nil)
        await model.checkStatus(board.id)
        #expect(model.attempts.count == 1)
        #expect(await calls.mutations == 0)
        await calls.setReady(false)
        await model.checkStatus(board.id)
        #expect(model.error == OnchainFailure.unavailable.errorDescription)
        await model.discardUncertain(board.id)
        #expect(model.attempts.isEmpty)
        #expect(try await OnchainOperationJournal(file: file).loadAll().isEmpty)
        #expect(await calls.mutations == 0)
    }
    @Test func expiredQuoteRequiresReviewAgain() async {
        let calls = Calls()
        var d = dependencies(calls)
        d.quote = { _, _, _, _ in
            var q = OnchainJournalTests.quote
            q.createdAt = Date().addingTimeInterval(-30)
            return q
        }
        let model = OnchainModel(dependencies: d)
        await model.review(.board)
        await model.confirm()
        #expect(await calls.mutations == 0)
        #expect(model.error != nil)
    }
}

struct ExitSDKPolicyTests {
    @Test func startAndProgressUseTheWalletBoundary() async {
        let session = WalletSession()
        for action in [OnchainAction.startExit, .progress] {
            var quote = OnchainJournalTests.quote
            quote.action = action
            await #expect(throws: WalletSession.SessionError.notOpen) {
                _ = try await session.executeOnchain(quote)
            }
        }
    }
}
