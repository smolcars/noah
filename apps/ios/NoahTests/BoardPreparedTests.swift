import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

struct BoardPreparedTests {
    static var quote: OnchainQuote {
        .init(action: .board, walletID: "wallet", ids: [], amount: 30_000, fee: 100,
              chainFee: 1543, confirmed: 40_000, feeRate: 1,
              prepared: .init(PreparedBoard(id: "board", txid: "exact", grossSats: 30_000,
                  netSats: 29_900, arkFeeSats: 100, onchainFeeSats: 1543, vsize: 1543,
                  inputs: [.init(txid: "funding", vout: 0)])))
    }
    actor Calls {
        var discarded: [UUID] = []
        var submitted = 0
        var persistence = false
        var failDiscard = false
        func discard(_ quote: OnchainQuote) throws {
            if persistence { throw BarkError(message: "cache", code: .persistence) }
            if failDiscard { throw URLError(.notConnectedToInternet) }
            discarded.append(quote.id)
        }
        func submit(_ result: BoardResult) -> BoardResult { submitted += 1; persistence = false; return result }
        func setPersistence() { persistence = true }
        func failCleanup(_ value: Bool) { failDiscard = value }
    }
    func journal() -> (OnchainOperationJournal, URL) {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        return (.init(file: file), file)
    }
    @Test func exactValuesAndTwoMinuteExpirySurviveJournalRoundTrip() throws {
        var quote = try JSONDecoder().decode(OnchainQuote.self, from: JSONEncoder().encode(Self.quote))
        #expect(quote.amount == 30_000 && quote.net == 29_900 && quote.fee == 100 && quote.chainFee == 1543)
        #expect(quote.amount + quote.chainFee == 31_543)
        #expect(quote.prepared?.vsize == 1543 && quote.prepared?.inputCount == 1)
        #expect(quote.prepared?.inputs == [.init(txid: "funding", vout: 0)])
        quote.createdAt = Date().addingTimeInterval(-60)
        #expect(quote.isCurrent)
        quote.prepared?.createdAt = Date().addingTimeInterval(-121)
        #expect(!quote.isCurrent)
        #expect(!quote.matches(Self.quote))
    }
    @Test(arguments: [BroadcastOutcome.accepted, .failed(reason: "private diagnostic"), .notAttempted(reason: "private diagnostic")])
    func journaledBeforeSubmitAndOutcomes(broadcast: BroadcastOutcome) async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let quote = Self.quote
        let calls = Calls()
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            let saved = try #require(try await OnchainOperationJournal(file: file).load())
            #expect(saved.txid == "exact" && saved.receipt == nil && saved.quote == quote)
            return await calls.submit(.init(txid: "exact", broadcast: broadcast, vtxoIds: ["vtxo"]))
        })
        if case .notAttempted = broadcast {
            await #expect(throws: OnchainFailure.boardNotAttempted) {
                try await session.submitBoard(quote, walletID: "wallet", journal: journal)
            }
            #expect(try await journal.loadAll().isEmpty)
        } else {
            _ = try await session.submitBoard(quote, walletID: "wallet", journal: journal)
            let saved = try #require(try await OnchainOperationJournal(file: file).load())
            #expect(saved.receipt?.txid == "exact" && saved.receipt?.onchainFee == 1543 && saved.receipt?.arkFee == 100)
            #expect(saved.blocks(.board, ids: []))
            if case .failed = broadcast {
                #expect(saved.receipt?.broadcastRetryPending == true)
                #expect(saved.receipt?.message.contains("keep trying") == true)
                await #expect(throws: OnchainFailure.uncertain) { try await journal.acknowledge(quote.id) }
                try await journal.observe(.notFound, for: quote.id)
                await #expect(throws: OnchainFailure.uncertain) { try await journal.acknowledge(quote.id) }
                try await journal.observe(.confirmed(123), for: quote.id)
                try await journal.acknowledge(quote.id)
            }
        }
        #expect(await calls.submitted == 1)
        #expect(await calls.discarded == [quote.id])
        #expect(await session.boardPreparations.isEmpty)
    }
    @Test func persistenceFailureRetriesOnlyTheAcceptedIDBeforeDiscard() async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let quote = Self.quote
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            let result = await calls.submit(.init(txid: "exact", broadcast: .failed(reason: "cache"), vtxoIds: []))
            if await calls.submitted == 1 { await calls.setPersistence() }
            return result
        })
        _ = try await session.submitBoard(quote, walletID: "wallet", journal: journal)
        #expect(await calls.submitted == 2)
        #expect(await calls.discarded == [quote.id])
        #expect(await session.boardPreparations.isEmpty)
        #expect(try await journal.load()?.receipt?.broadcastRetryPending == true)
    }
    @Test func cleanupRetainsOwnershipAfterFailureAndCanRetry() async throws {
        let session = WalletSession()
        let calls = Calls()
        let quote = Self.quote
        await calls.failCleanup(true)
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            Issue.record("Unsubmitted cleanup must not submit"); throw OnchainFailure.invalid
        })
        await session.discardBoard(quote)
        #expect(await session.boardPreparations.count == 1)
        await #expect(throws: OnchainFailure.uncertain) { try await session.discardBoardReviews() }
        await calls.failCleanup(false)
        try await session.discardBoardReviews()
        #expect(await session.boardPreparations.isEmpty)
        #expect(await calls.submitted == 0)
    }
    @Test func staleEditedAndForeignReviewsCannotSubmit() async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let quote = Self.quote
        await session.retainBoardReview(quote, discard: {}, submit: {
            Issue.record("Invalid review submitted"); throw OnchainFailure.invalid
        })
        var edited = quote
        edited.chainFee += 1
        var expired = quote
        expired.prepared?.createdAt = Date().addingTimeInterval(-121)
        for invalid in [edited, expired] {
            await #expect(throws: OnchainFailure.changed) {
                try await session.submitBoard(invalid, walletID: "wallet", journal: journal)
            }
        }
        await #expect(throws: OnchainFailure.changed) {
            try await session.submitBoard(quote, walletID: "other", journal: journal)
        }
        await session.discardBoard(quote)
        await #expect(throws: OnchainFailure.changed) {
            try await session.submitBoard(quote, walletID: "wallet", journal: journal)
        }
        #expect(try await journal.loadAll().isEmpty)
    }
    @Test(arguments: [false, true]) func reserveRepreparesOnceThenKeepsOrDiscardsExactQuote(secondFeeRises: Bool) async throws {
        let calls = Calls()
        var quote = Self.quote
        quote.amount = 34_792 // Seeded with 208 sats, exact fee is 1,543.
        quote.automatic = true
        let original = quote
        let result = try await WalletSession.reserveBoardReview(quote, minimum: 20_000,
            discard: { try? await calls.discard($0) }, prepare: { reduced in
                #expect(await calls.discarded == [original.id])
                #expect(reduced == 33_457)
                var replacement = original
                replacement.id = UUID()
                replacement.amount = reduced
                if secondFeeRises { replacement.chainFee += 1 }
                return replacement
            })
        if secondFeeRises {
            #expect(result == nil)
            #expect(await calls.discarded.count == 2)
        } else {
            let result = try #require(result)
            #expect(result.confirmed - result.amount - result.chainFee == 5_000)
        }
    }
}

@MainActor struct BoardReviewLifetimeTests {
    actor Readiness {
        var count = 0
        var holdAt: Int
        var continuation: CheckedContinuation<Bool, Never>?
        init(holdAt: Int) { self.holdAt = holdAt }
        func ready() async -> Bool {
            count += 1
            if count == holdAt { return await withCheckedContinuation { continuation = $0 } }
            return true
        }
        func finish() { continuation?.resume(returning: true); continuation = nil }
    }
    @Test(arguments: [2, 3]) func backgroundDuringReadinessCannotPresentOrSubmit(holdAt: Int) async {
        let calls = OnchainModelTests.Calls()
        let readiness = Readiness(holdAt: holdAt)
        let cleanup = BoardPreparedTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.ready = { await readiness.ready() }
        dependencies.quote = { _, _, _, _ in BoardPreparedTests.quote }
        dependencies.discardReview = { try? await cleanup.discard($0) }
        let model = OnchainModel(dependencies: dependencies)
        let work = Task {
            await model.review(.board)
            if holdAt == 3 { await model.confirm() }
        }
        for _ in 0..<1000 where await readiness.continuation == nil { await Task.yield() }
        #expect(await readiness.continuation != nil)
        model.invalidate()
        await readiness.finish()
        await work.value
        #expect(model.quote == nil)
        #expect(await calls.mutations == 0)
        #expect(await cleanup.discarded.count == 1)
    }
    @Test func latePreparationIsDiscardedAfterNavigationAndSuspension() async {
        let calls = OnchainModelTests.Calls()
        let cleanup = BoardPreparedTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.quote = { _, _, _, _ in await calls.delayedQuote() }
        dependencies.discardReview = { try? await cleanup.discard($0) }
        let model = OnchainModel(dependencies: dependencies)
        let task = Task { await model.review(.board) }
        for _ in 0..<100 where await calls.continuation == nil { await Task.yield() }
        model.invalidate()
        await calls.finishQuote()
        await task.value
        #expect(model.quote == nil)
        #expect(await cleanup.discarded.count == 1)
        #expect(await calls.mutations == 0)
    }
}
