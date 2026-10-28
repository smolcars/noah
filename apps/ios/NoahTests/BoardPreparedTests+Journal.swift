import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

extension BoardPreparedTests {
    @Test func exactValuesAndTwoMinuteExpirySurviveJournalRoundTrip() throws {
        var quote = try JSONDecoder().decode(OnchainQuote.self, from: JSONEncoder().encode(Self.quote))
        #expect(quote.amount == 30_000 && quote.net == 29_900 && quote.fee == 100 && quote.chainFee == 1543)
        #expect(try quote.amount + #require(quote.chainFee) == 31_543)
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
        // The journal keeps the review, never its process-local review-clock reading.
        let persisted = { var saved = quote; saved.reviewedAt = nil; saved.prepared?.reviewedAt = nil; return saved }()
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            let saved = try #require(try await OnchainOperationJournal(file: file).load())
            #expect(saved.txid == "exact" && saved.receipt == nil && saved.quote == persisted)
            return await calls.submit(.init(txid: "exact", broadcast: broadcast, vtxoIds: ["vtxo"]))
        })
        if case .notAttempted = broadcast {
            await #expect(throws: OnchainFailure.boardNotAttempted) {
                try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
            }
            #expect(try await journal.loadAll().isEmpty)
        } else {
            _ = try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
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
    /// FFI 0.7.0: Bark failed after it may have recorded the board. The attempt stays uncertain and locked
    /// with its funding txid, nothing resubmits it, and only evidence of that txid resolves it.
    @Test func uncertainOutcomeStaysLockedAndIsNeverResubmitted() async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let quote = Self.quote
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            await calls.submit(.init(txid: "exact", broadcast: .uncertain(reason: "private diagnostic"), vtxoIds: []))
        })
        await #expect(throws: OnchainFailure.uncertain) {
            try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
        }
        let saved = try #require(try await OnchainOperationJournal(file: file).load())
        #expect(saved.receipt == nil && saved.txid == "exact" && saved.blocks(.board, ids: []))
        // The spent preparation is released, and the same review can't be submitted again.
        #expect(await calls.discarded == [quote.id])
        #expect(await session.boardPreparations.isEmpty)
        await #expect(throws: OnchainFailure.changed) {
            try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
        }
        var other = Self.quote
        other.id = UUID()
        await #expect(throws: OnchainFailure.busy) { try await journal.perform(other) { "second board" } }
        #expect(await calls.submitted == 1)
        // Check status: no record of the funding txid leaves it uncertain; a pending board resolves it.
        func check(_ ids: Set<String>) async throws -> Bool {
            try await WalletSession.checkOnchainAttempt(quote.id, journal: journal, reads: .init(
                walletID: "wallet", transactionIDs: { ids }, vtxoState: { _ in "Spendable" }, exitState: { _ in nil },
                transactionStatus: { _ in .notFound }))
        }
        #expect(try await !check([]))
        #expect(try await journal.load()?.receipt == nil)
        #expect(try await check(["exact"]))
        #expect(try await journal.load()?.receipt?.broadcastRetryPending == true)
    }
    /// FFI 0.7.0 re-checks a confirmed-only board's parents at first submit. A parent reorged out and an
    /// unreachable chain source both throw before Bark records anything: not submitted, a new review allowed.
    /// Anything else thrown stays uncertain.
    @Test(arguments: [
        (BarkError(message: "prepared board parent is no longer confirmed", code: .preparedInputsUnavailable) as any Error, OnchainFailure.reservedInputs),
        (BarkError(message: "chain source unreachable"), .boardNotAttempted),
        (URLError(.networkConnectionLost), .uncertain),
    ])
    func submitThrowsBeforeBarkRecordsReadAsNotSubmitted(error: any Error, failure: OnchainFailure) async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let quote = Self.quote
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: { throw error })
        await #expect(throws: failure) {
            try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
        }
        #expect(try await journal.loadAll().map(\.id) == (failure == .uncertain ? [quote.id] : []))
        #expect(await calls.discarded == [quote.id])
    }
    /// An uncertain board may be partly recorded (a BOARD movement before its checkpoint): repeat protection
    /// can't be overridden into a second board, even after a reload, and any override is ignored. Only Check
    /// status resolves it.
    @Test func uncertainBoardCantBeOverriddenIntoASecondBoard() async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let quote = Self.quote
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            await calls.submit(.init(txid: "exact", broadcast: .uncertain(reason: "private diagnostic"), vtxoIds: []))
        })
        await #expect(throws: OnchainFailure.uncertain) {
            try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
        }
        var second = Self.quote
        second.id = UUID()
        for current in [journal, OnchainOperationJournal(file: file)] {
            #expect(try await current.load()?.uncertainBoard == true)
            await #expect(throws: OnchainFailure.busy) { try await current.discardUncertain(quote.id) }
            await #expect(throws: OnchainFailure.busy) { try await current.perform(second) { "second board" } }
        }
        #expect(OnchainFailure.busy.errorDescription?.hasPrefix("This operation overlaps an unresolved attempt") == true)
        // An override written anyway changes nothing; a crash before the SDK answered (no flag) keeps today's override.
        var overridden = try #require(try await journal.load())
        overridden.overrideAudit = .init(overriddenAt: Date())
        #expect(overridden.blocks(.board, ids: []))
        overridden.uncertainBoard = nil
        #expect(!overridden.blocks(.board, ids: []))
        #expect(await calls.submitted == 1)
    }

    struct UncertainJournal {
        let journal: OnchainOperationJournal
        let file: URL
        let id: UUID
    }
    func uncertainJournal() throws -> UncertainJournal {
        let (journal, file) = journal()
        var attempt = OnchainOperationJournal.Attempt(quote: Self.quote)
        attempt.txid = "exact"
        attempt.uncertainBoard = true
        try JSONEncoder().encode([attempt]).write(to: file)
        return UncertainJournal(journal: journal, file: file, id: attempt.id)
    }

    func check(
        _ journal: OnchainOperationJournal,
        _ id: UUID,
        ids: Set<String> = [],
        vtxoAnchors: Set<String> = [],
        chain: @escaping @Sendable () throws -> ChainTransactionStatus? = { .notFound },
        unspent: @escaping @Sendable () throws -> Set<String> = { ["funding:0", "other:1"] }) async throws -> Bool {
        try await WalletSession.checkOnchainAttempt(id, journal: journal, reads: .init(
            walletID: "wallet", transactionIDs: { ids }, vtxoState: { _ in "Spendable" }, exitState: { _ in nil },
            transactionStatus: { _ in try chain() }, vtxoAnchors: { vtxoAnchors }, unspent: { try unspent() }))
    }

    /// Nothing boarded: no pending board or wallet transaction names the txid, the chain source says it isn't
    /// found and the input is still an unspent wallet coin. Check status reads no movements, so an orphan BOARD
    /// movement can't hold it (bark never had the signed bytes). It goes, and a fresh board may follow.
    @Test func uncertainBoardProvenNeverBoardedIsReleased() async throws {
        let uncertain = try uncertainJournal(), journal = uncertain.journal, file = uncertain.file, id = uncertain.id
        defer { try? FileManager.default.removeItem(at: file) }
        #expect(try await check(journal, id))
        #expect(try await journal.loadAll().isEmpty)
        _ = try await journal.perform(Self.quote) { "fresh board" }
    }

    /// Any doubt keeps it locked and reads as "Status remains uncertain": an unreachable chain source, no answer,
    /// an input spent or no longer the wallet's, coins that can't be read.
    @Test(arguments: [0, 1, 2, 3])
    func uncertainBoardStaysLockedOnAnyDoubt(doubt: Int) async throws {
        let uncertain = try uncertainJournal(), journal = uncertain.journal, file = uncertain.file, id = uncertain.id
        defer { try? FileManager.default.removeItem(at: file) }
        let resolved = try await check(journal, id, chain: { () throws -> ChainTransactionStatus? in
            if doubt == 0 { throw URLError(.cannotConnectToHost) }
            return doubt == 1 ? nil : .notFound
        }, unspent: {
            if doubt == 3 { throw URLError(.cannotConnectToHost) }
            return doubt == 2 ? ["other:1"] : ["funding:0"]
        })
        #expect(!resolved)
        let saved = try #require(try await journal.load())
        #expect(saved.uncertainBoard == true && saved.receipt == nil && saved.blocks(.board, ids: []))
    }

    /// A pending board (or the chain) naming the funding txid: it was boarded. It gets a receipt that keeps
    /// "Do not board these funds again", and Done stays refused until it confirms.
    @Test func uncertainBoardFoundIsBoardedAndKeepsDeliveryProtection() async throws {
        let uncertain = try uncertainJournal(), journal = uncertain.journal, file = uncertain.file, id = uncertain.id
        defer { try? FileManager.default.removeItem(at: file) }
        #expect(try await check(journal, id, ids: ["exact"]))
        let saved = try #require(try await journal.load())
        #expect(saved.receipt?.broadcastRetryPending == true && saved.blocks(.board, ids: []))
        await #expect(throws: OnchainFailure.uncertain) { try await journal.acknowledge(id) }
        #expect(try await journal.loadAll().count == 1)
    }
    /// A stored board VTXO anchored to the funding txid, after its checkpoint and BDK transaction have gone
    /// (no pending board, no wallet transaction, chain not found, inputs unspent): boarded, never released.
    @Test func uncertainBoardWithOnlyAStoredBoardVtxoIsBoarded() async throws {
        let uncertain = try uncertainJournal(), journal = uncertain.journal, file = uncertain.file, id = uncertain.id
        defer { try? FileManager.default.removeItem(at: file) }
        #expect(try await check(journal, id, vtxoAnchors: ["exact", "round"]))
        let saved = try #require(try await journal.load())
        #expect(saved.receipt?.txid == "exact" && saved.receipt?.broadcastRetryPending == true && saved.blocks(.board, ids: []))
        await #expect(throws: OnchainFailure.uncertain) { try await journal.acknowledge(id) }
        // Checking again never releases it.
        _ = try await check(journal, id)
        #expect(try await journal.loadAll().map(\.id) == [id])
    }
    /// `notAttempted` still means nothing was recorded: the lock is released and a new review submits.
    @Test func notAttemptedOutcomeAllowsAFreshBoard() async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        var outcomes: [BroadcastOutcome] = [.notAttempted(reason: "private diagnostic"), .accepted]
        for _ in 0..<2 {
            var fresh = Self.quote
            fresh.id = UUID()
            let quote = fresh, outcome = outcomes.removeFirst()
            await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
                await calls.submit(.init(txid: "exact", broadcast: outcome, vtxoIds: []))
            })
            if case .notAttempted = outcome {
                await #expect(throws: OnchainFailure.boardNotAttempted) {
                    try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
                }
                #expect(try await journal.loadAll().isEmpty)
            } else {
                _ = try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
                #expect(try await journal.load()?.receipt?.txid == "exact")
            }
        }
        #expect(await calls.submitted == 2)
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
        _ = try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
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
        edited.chainFee = try #require(edited.chainFee) + 1
        var expired = quote
        expired.prepared?.reviewedAt = ContinuousClock.now - .seconds(121)
        for invalid in [edited, expired] {
            await #expect(throws: OnchainFailure.changed) {
                try await session.submitBoard(invalid, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
            }
        }
        await #expect(throws: OnchainFailure.changed) {
            try await session.submitBoard(quote, walletID: "other", journal: journal, confirmed: { quote.confirmed })
        }
        await session.discardBoard(quote)
        await #expect(throws: OnchainFailure.changed) {
            try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed })
        }
        #expect(try await journal.loadAll().isEmpty)
    }
    actor AutoBoardCalls {
        let balance: UInt64
        let fee: UInt64
        let arkFee: UInt64
        let reviewExtraFee: UInt64
        let failure: BarkErrorCode?
        let failAt: Int
        let cancelAt: Int
        let failDiscard: Bool
        var amounts: [UInt64] = []
        var active: [UUID] = []
        var discarded: [UUID] = []
        init(balance: UInt64 = 1_000_000, fee: UInt64 = 12_000, arkFee: UInt64 = 100,
             reviewExtraFee: UInt64 = 0, failure: BarkErrorCode? = nil, failAt: Int = 0,
             cancelAt: Int = 0, failDiscard: Bool = false) {
            self.balance = balance; self.fee = fee; self.arkFee = arkFee; self.reviewExtraFee = reviewExtraFee
            self.failure = failure; self.failAt = failAt; self.cancelAt = cancelAt; self.failDiscard = failDiscard
        }
        func prepare(_ amount: UInt64) throws -> OnchainQuote {
            #expect(active.isEmpty) // A probe must release its inputs before the review is built.
            amounts.append(amount)
            if amounts.count == failAt, let failure { throw BarkError(message: "private", code: failure) }
            let chainFee = amount == 0 ? fee - 430 : fee + reviewExtraFee // One extra 43-vB change output at 10 sat/vB.
            guard amount == 0 || amount + chainFee <= balance else {
                throw BarkError(message: "insufficient", code: .insufficientFunds)
            }
            var quote = BoardPreparedTests.quote
            quote.id = UUID(); quote.automatic = true; quote.confirmed = balance
            quote.amount = amount == 0 ? balance - chainFee : amount
            quote.chainFee = chainFee; quote.fee = arkFee; quote.feeRate = 10
            quote.prepared = .init(PreparedBoard(id: quote.id.uuidString, txid: "exact",
                                                 grossSats: quote.amount, netSats: quote.net, arkFeeSats: arkFee, onchainFeeSats: chainFee,
                                                 vsize: chainFee / 10, inputs: (0..<20).map { .init(txid: "funding", vout: UInt32($0)) }))
            active.append(quote.id)
            if amounts.count == cancelAt { withUnsafeCurrentTask { $0?.cancel() } }
            return quote
        }
        func discard(_ quote: OnchainQuote) throws {
            discarded.append(quote.id)
            if failDiscard { throw OnchainFailure.uncertain }
            active.removeAll { $0 == quote.id }
        }
        func review() async throws -> OnchainQuote? {
            try await WalletSession.reserveBoardReview(minimum: 20_000, feeRate: 10,
                                                       discard: { try await self.discard($0) }, prepare: { amount, _ in try await self.prepare(amount) })
        }
    }
    @Test func manyInputsAtHighFeeReachAnAffordableReview() async throws {
        // Twenty confirmed 50,000-sat inputs; the old 992,920-sat gross cannot pay the real fee.
        let calls = AutoBoardCalls()
        let quote = try #require(try await calls.review())
        #expect(await calls.amounts == [0, 983_000])
        #expect(await calls.discarded.count == 1)
        #expect(await calls.active == [quote.id])
        #expect(quote.prepared?.inputCount == 20 && quote.chainFee == 12_000)
        #expect(try quote.confirmed - quote.amount - #require(quote.chainFee) == 5_000)
    }
    @Test(arguments: [UInt64(12_000), 12_001])
    func exactReserveIsCheckedBeforeReview(fee: UInt64) async throws {
        let calls = AutoBoardCalls(reviewExtraFee: fee - 12_000)
        let result = try await calls.review()
        #expect((result != nil) == (fee == 12_000))
        #expect(await calls.discarded.count == (result == nil ? 2 : 1))
        #expect(await calls.active.count == (result == nil ? 0 : 1))
        if let result { #expect(try result.confirmed - result.amount - #require(result.chainFee) == 5_000) }
    }
    @Test(arguments: [BarkErrorCode.insufficientFunds, .tooManyInputs, .belowMinimumBoardAmount], [1, 2])
    func probeAndReviewTypedFailuresUseExistingCopy(code: BarkErrorCode, stage: Int) async {
        let calls = AutoBoardCalls(failure: code, failAt: stage)
        let expected: OnchainFailure = switch code {
        case .insufficientFunds: .insufficientFunds
        case .tooManyInputs: .tooManyInputs
        default: .belowMinimum
        }
        await #expect(throws: expected) { try await calls.review() }
        #expect(await calls.active.isEmpty)
        #expect(await calls.discarded.count == (stage == 1 ? 0 : 1))
    }
    @Test(arguments: [1, 2]) func cancellationDiscardsEveryReturnedPreparation(stage: Int) async throws {
        let calls = AutoBoardCalls(cancelAt: stage)
        let work = Task { try await calls.review() }
        await #expect(throws: CancellationError.self) { try await work.value }
        #expect(await calls.active.isEmpty)
        #expect(await calls.discarded.count == stage)
    }
    @Test(arguments: [UInt64(16_000), 36_999, 37_000])
    func belowGrossOrNetThresholdDiscardsEveryPreparation(balance: UInt64) async throws {
        let calls = AutoBoardCalls(balance: balance, arkFee: 101)
        #expect(try await calls.review() == nil)
        #expect(await calls.amounts == (balance == 37_000 ? [0, 20_000] : [0]))
        #expect(await calls.active.isEmpty)
        #expect(await calls.discarded.count == (balance == 37_000 ? 2 : 1))
    }
    @Test func failedProbeDiscardPreventsReviewPreparation() async {
        let calls = AutoBoardCalls(failDiscard: true)
        await #expect(throws: OnchainFailure.uncertain) { try await calls.review() }
        #expect(await calls.amounts == [0])
        #expect(await calls.discarded.count == 1)
    }
}
