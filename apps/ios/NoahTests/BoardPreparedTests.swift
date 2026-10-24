import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

struct BoardPreparedTests {
    struct FundingInput: Decodable {
        var walletID: String
        var handled: [String: Set<String>]
        var outpoints: Set<String>
    }
    @Test func changedFundingRejectsBeforeClaimAndCanReleaseForFreshReview() async throws {
        for state in ["changed", "read-error", "same"] {
            let session = WalletSession()
            let quote = Self.automaticQuote
            let (journal, file) = journal()
            defer { try? FileManager.default.removeItem(at: file) }
            let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: ["deposit:0"])
            await session.retainBoardReview(quote, discard: {}, submit: {
                .init(txid: "exact", broadcast: .accepted, vtxoIds: [])
            })
            do {
                _ = try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { quote.confirmed },
                    funding: funding, readFunding: {
                        if state == "read-error" { throw URLError(.notConnectedToInternet) }
                        return state == "same" ? funding : .init(walletID: "wallet", outpoints: ["other:0"])
                    })
                #expect(state == "same")
            } catch { #expect(state != "same") }
            #expect(try await session.abandonUnsubmittedAutoBoard(quote, journal: journal) == (state != "same"))
            #expect(try await journal.loadAll().isEmpty == (state != "same"))
        }
    }
    @MainActor @Test func retryDeadlineSavedUnderAFastClockIsCappedToOneBackoff() async throws {
        var disk: String?
        var now = Date(timeIntervalSince1970: 1_000_000)
        var store = AutoBoardFunding()
        store.read = { disk }; store.write = { disk = $0 }
        let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: ["deposit:0"])
        try store.release(funding, retryAfter: now.addingTimeInterval(365 * 24 * 3600)) // Clock ran a year ahead.
        #expect(try store.retryDeadline(walletID: "wallet", now: now) == now.addingTimeInterval(30))
        // The cap is durable, so later reads keep it instead of opening a fresh window.
        #expect(try store.retryDeadline(walletID: "wallet", now: now.addingTimeInterval(10)) == now.addingTimeInterval(30))
        let review = AutoBoardReview(store: store, now: { now })
        now = now.addingTimeInterval(29)
        #expect(try await review.prepare(allowed: { true }, funding: { funding }, quote: { Self.quote }, discard: { _ in }) == nil)
        now = now.addingTimeInterval(1)
        #expect(try await review.prepare(allowed: { true }, funding: { funding }, quote: { Self.quote }, discard: { _ in }) != nil)
    }

    @MainActor @Test func failedConfirmReleasesOnlyNewFundingAndBacksOffUnlessFundsChanged() async throws {
        for changed in [false, true] {
            var disk: String?
            var now = Date(timeIntervalSince1970: 1000)
            var store = AutoBoardFunding()
            store.read = { disk }; store.write = { disk = $0 }
            let review = AutoBoardReview(store: store, now: { now })
            try store.markHandled(.init(walletID: "wallet", outpoints: ["old:0"]))
            let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: ["old:0", "new:0"])
            let quote = try #require(try await review.prepare(allowed: { true }, funding: { funding },
                quote: { Self.quote }, discard: { _ in }))
            try await review.failedConfirm(quote, changed: changed) { true }
            #expect(try store.hasUnhandled(funding))
            #expect(try !store.hasUnhandled(.init(walletID: "wallet", outpoints: ["old:0"])))
            var reopened = AutoBoardFunding()
            reopened.read = { disk }; reopened.write = { disk = $0 }
            let recreated = AutoBoardReview(store: reopened, now: { now })
            #expect(try reopened.retryDeadline(walletID: "wallet") == now.addingTimeInterval(changed ? 0 : 30))
            #expect(try reopened.retryDeadline(walletID: "other-wallet") == .distantPast)
            if !changed {
                now = now.addingTimeInterval(29)
                #expect(try await recreated.prepare(allowed: { true }, funding: { funding }, quote: { Self.quote }, discard: { _ in }) == nil)
                now = now.addingTimeInterval(1)
            }
            #expect(try await recreated.prepare(allowed: { true }, funding: { funding }, quote: { Self.quote }, discard: { _ in }) != nil)
        }
    }
    @Test func failedRetryWriteKeepsFundingHandledAndOtherWalletIndependent() throws {
        var disk: String?
        var store = AutoBoardFunding()
        store.read = { disk }; store.write = { disk = $0 }
        let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: ["deposit:0"])
        try store.markHandled(funding)
        let before = disk
        store.write = { _ in throw OnchainFailure.unavailable }
        #expect(throws: OnchainFailure.self) { try store.release(funding, retryAfter: Date().addingTimeInterval(30)) }
        #expect(disk == before)
        #expect(try !store.hasUnhandled(funding))
        #expect(try store.hasUnhandled(.init(walletID: "other", outpoints: funding.outpoints)))
    }
    @MainActor @Test func uncertainSubmittedInterruptedAndDismissedConfirmationsKeepFundingHandled() async throws {
        for ending in ["uncertain", "submitted", "interrupted", "dismissed"] {
            var disk: String?
            var store = AutoBoardFunding()
            store.read = { disk }; store.write = { disk = $0 }
            let review = AutoBoardReview(store: store)
            let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: ["deposit:0"])
            let quote = try #require(try await review.prepare(allowed: { true }, funding: { funding }, quote: { Self.quote }, discard: { _ in }))
            if ending != "dismissed" {
                try? await review.failedConfirm(quote, changed: false) {
                    if ending == "interrupted" { throw CancellationError() }
                    return false
                }
            }
            #expect(try !store.hasUnhandled(funding))
        }
    }
    @Test func preparationProofRequiresNoClaimOrJournalAndRejectsMissingEvidence() async throws {
        for ending in ["unclaimed", "discarded", "claimed", "journal", "missing"] {
            let session = WalletSession()
            var quote = Self.quote
            quote.automatic = true
            let (journal, file) = journal()
            defer { try? FileManager.default.removeItem(at: file) }
            if ending != "missing" {
                await session.retainBoardReview(quote, discard: {}, submit: { throw OnchainFailure.uncertain })
            }
            if ending == "discarded" { await session.discardBoard(quote) }
            if ending == "claimed" {
                let balance = quote.confirmed
                _ = try? await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: { balance })
            }
            if ending == "journal" { _ = try? await journal.perform(quote) { throw OnchainFailure.uncertain } }
            #expect(try await session.abandonUnsubmittedAutoBoard(quote, journal: journal) == ["unclaimed", "discarded"].contains(ending))
            #expect(try await !session.abandonUnsubmittedAutoBoard(quote, journal: journal))
            try await session.discardBoardReviews()
        }
    }
    @Test func fundingDecisionsMatchSharedFixture() throws {
        for test in try Fixtures.load("auto-board-funding", input: FundingInput.self, expected: Bool.self) {
            var ledger = AutoBoardFunding()
            ledger.read = { String(decoding: try! JSONEncoder().encode(test.input.handled), as: UTF8.self) }
            let funding = AutoBoardFunding.Snapshot(walletID: test.input.walletID, outpoints: test.input.outpoints)
            #expect(try ledger.hasUnhandled(funding) == test.expected, "\(test.name)")
        }
    }
    @Test func threeDepositsPromptOnceAcrossAllReviewEndingsAndRestart() throws {
        for ending in ["confirm", "dismiss", "expiry", "background-after-presentation", "navigation-after-presentation", "submit-failure"] {
            let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: root) }
            let path = root.appending(path: "mmkv").path
            let storage = try SettingsStorage(rootPath: path)
            for deposit in 0..<3 {
                let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: Set((0...deposit).map { "deposit-\($0):0" }))
                #expect(try AutoBoardFunding(storage: storage).hasUnhandled(funding), "\(ending)")
                #expect(try AutoBoardFunding(storage: storage).markHandled(funding))
                let reopened = AutoBoardFunding(storage: try SettingsStorage(rootPath: path))
                #expect(try !reopened.hasUnhandled(funding), "\(ending)")
                #expect(try !reopened.markHandled(funding))
            }
        }
    }
    @Test func confirmedFundingExcludesPendingExitUnknownAndBoardChange() {
        let funding = AutoBoardFunding.confirmed(walletID: "wallet", utxos: [
            .local(outpoint: .init(txid: "deposit", vout: 1), amountSats: 150_529, confirmationHeight: 100),
            .local(outpoint: .init(txid: "pending", vout: 0), amountSats: 150_529, confirmationHeight: nil),
            .local(outpoint: .init(txid: "change", vout: 0), amountSats: 5_000, confirmationHeight: 100),
            .local(outpoint: .init(txid: "unknown", vout: 0), amountSats: 150_529, confirmationHeight: 100),
            .exit(vtxoId: "exit", amountSats: 100_000, height: 100)
        ], transactions: [
            .init(txid: "deposit", txHex: "", onchainFeeSats: nil, balanceChangeSats: 150_529, confirmation: nil, isCpfp: false),
            .init(txid: "change", txHex: "", onchainFeeSats: 155, balanceChangeSats: -140_000, confirmation: nil, isCpfp: false)
        ])
        #expect(funding.outpoints == ["deposit:1"])
    }
    @MainActor @Test func preparationFailureLeavesFundingUnhandledAndRetriesAfterBackoff() async throws {
        var disk: String?
        var now = Date(timeIntervalSince1970: 100)
        var store = AutoBoardFunding()
        store.read = { disk }; store.write = { disk = $0 }
        let review = AutoBoardReview(store: store, now: { now })
        let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: ["deposit:0"])
        var preparations = 0
        var rejected = true
        func check() async throws -> OnchainQuote? {
            try await review.prepare(allowed: { true }, funding: { funding }, quote: {
                preparations += 1
                if rejected { throw OnchainFailure.belowMinimum }
                return Self.quote
            }, discard: { _ in })
        }
        await #expect(throws: OnchainFailure.belowMinimum) { try await check() }
        #expect(try store.hasUnhandled(funding)); #expect(disk == nil)
        now = now.addingTimeInterval(29)
        #expect(try await check() == nil); #expect(preparations == 1)
        now = now.addingTimeInterval(1); rejected = false
        #expect(try await check() != nil); #expect(preparations == 2)
        #expect(try !store.hasUnhandled(funding))
    }
    @MainActor @Test func backgroundDisabledAndInvalidatedReadsNeverEvaluateOrClaimFunding() async throws {
        for stopAt in ["before", "funding", "quote"] {
            for _ in ["background", "disabled", "navigation"] {
                var allowed = stopAt != "before"
                var reads = 0, preparations = 0, discards = 0
                var store = AutoBoardFunding()
                store.read = {
                    if stopAt != "quote" { Issue.record("Funding must not be evaluated after admission ends") }
                    return nil
                }
                store.write = { _ in Issue.record("Funding must not be consumed") }
                let review = AutoBoardReview(store: store)
                let quote = try await review.prepare(allowed: { allowed }, funding: {
                    reads += 1
                    if stopAt == "funding" { allowed = false }
                    return .init(walletID: "wallet", outpoints: ["deposit:0"])
                }, quote: {
                    preparations += 1; allowed = false
                    return Self.quote
                }, discard: { _ in discards += 1 })
                #expect(quote == nil)
                #expect(reads == (stopAt == "before" ? 0 : 1))
                #expect(preparations == (stopAt == "quote" ? 1 : 0))
                #expect(discards == (stopAt == "quote" ? 1 : 0))
            }
        }
    }
    @MainActor @Test func summaryRefreshKeepsVisibleReviewWithoutReadingOrConsumingFunding() async throws {
        var disk: String?
        var store = AutoBoardFunding()
        store.read = { disk }; store.write = { disk = $0 }
        let review = AutoBoardReview(store: store)
        var plan: OnchainQuote?
        let held = try #require(try await review.prepare(allowed: { plan == nil }, funding: {
            .init(walletID: "wallet", outpoints: ["deposit:0"])
        }, quote: { Self.quote }, discard: { _ in }, present: { plan = $0 }))
        #expect(plan == held)
        let saved = disk
        for _ in 0..<3 {
            let result = try await review.prepare(allowed: { plan == nil }, funding: {
                Issue.record("Open review must survive refresh"); return nil
            }, quote: { Issue.record("Must not reprepare"); return nil }, discard: { _ in Issue.record("Must not discard") }, present: { plan = $0 })
            #expect(result == nil); #expect(plan == held); #expect(held.isCurrent); #expect(disk == saved)
        }
    }
    @Test func unreadableFundingOrFailedPersistenceCannotAdmitAReview() throws {
        let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: ["a:0"])
        var ledger = AutoBoardFunding()
        for raw in ["broken", "[]", #"{"wallet":[1]}"#, #"{"wallet":null}"#] {
            ledger.read = { raw }
            #expect(throws: (any Error).self) { try ledger.hasUnhandled(funding) }
        }
        ledger.read = { nil }
        ledger.write = { _ in throw CocoaError(.fileWriteUnknown) }
        #expect(throws: (any Error).self) { try ledger.markHandled(funding) }
    }
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
        var balanceReads = 0
        func confirmed(_ balance: UInt64) -> UInt64 { balanceReads += 1; return balance }
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
    static var automaticQuote: OnchainQuote {
        var quote = Self.quote
        quote.automatic = true
        quote.confirmed = quote.amount + quote.chainFee! + 5_000
        return quote
    }
    @Test func overlappingManualAndAutomaticReviewsOnlySubmitOnceThenConfirmedReceiptAllowsNewReview() async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let manual = Self.quote
        var automatic = Self.automaticQuote
        automatic.prepared?.id = "automatic"
        automatic.prepared?.txid = "auto-exact"
        for quote in [manual, automatic] {
            await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
                await calls.submit(.init(txid: quote.prepared!.txid, broadcast: .accepted, vtxoIds: []))
            })
        }
        _ = try await session.submitBoard(manual, walletID: "wallet", journal: journal, confirmed: { manual.confirmed })
        let reviewed = automatic
        await #expect(throws: OnchainFailure.busy) {
            try await session.submitBoard(reviewed, walletID: "wallet", journal: journal, confirmed: { reviewed.confirmed })
        }
        #expect(await calls.submitted == 1)
        try await journal.observe(.confirmed(100), for: manual.id)
        var next = Self.quote
        next.prepared?.id = "new-deposit"
        next.prepared?.txid = "next-exact"
        let fresh = next
        await session.retainBoardReview(fresh, discard: { try await calls.discard(fresh) }, submit: {
            await calls.submit(.init(txid: "next-exact", broadcast: .accepted, vtxoIds: []))
        })
        _ = try await session.submitBoard(fresh, walletID: "wallet", journal: journal, confirmed: { fresh.confirmed })
        #expect(await calls.submitted == 2)
        #expect(try await journal.loadAll().count == 2)
    }
    @Test(arguments: [false, true])
    func rejectedBoardAllowsLaterManualOrAutomaticBoard(automatic: Bool) async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let first = Self.quote
        await session.retainBoardReview(first, discard: {}, submit: {
            .init(txid: "exact", broadcast: .notAttempted(reason: "server rejected cosign"), vtxoIds: [])
        })
        await #expect(throws: OnchainFailure.boardNotAttempted) {
            try await session.submitBoard(first, walletID: "wallet", journal: journal, confirmed: { first.confirmed })
        }
        #expect(try await journal.loadAll().isEmpty)
        #expect(await session.boardPreparations.isEmpty)
        var next = automatic ? Self.automaticQuote : Self.quote
        next.prepared?.id = "new-review"
        let fresh = next
        await session.retainBoardReview(fresh, discard: {}, submit: {
            .init(txid: "exact", broadcast: .accepted, vtxoIds: [])
        })
        _ = try await session.submitBoard(fresh, walletID: "wallet", journal: journal, confirmed: { fresh.confirmed })
        #expect(try await journal.loadAll().count == 1)
        #expect(try await journal.load()?.receipt != nil)
    }
    @Test(arguments: [UInt64(0), 31_542, 32_543, 36_542, 36_543, 37_543])
    func automaticConfirmationRevalidatesTheExactReviewedReserve(confirmed: UInt64) async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let quote = Self.automaticQuote
        #expect(quote.confirmed - quote.amount - quote.chainFee! == 5_000)
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            await calls.submit(.init(txid: "exact", broadcast: .accepted, vtxoIds: []))
        })
        let submit = {
            try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: {
                #expect(await session.boardPreparations["board"]?.claimed == false)
                return await calls.confirmed(confirmed) // 32,543 leaves only 1,000 sats after an unselected coin is spent.
            })
        }
        let enoughReserve = confirmed >= quote.confirmed
        if enoughReserve {
            _ = try await submit()
            let saved = try #require(try await journal.load())
            #expect(saved.txid == "exact" && saved.receipt?.txid == "exact")
        } else {
            await #expect(throws: OnchainFailure.changed) { try await submit() }
            #expect(try await journal.loadAll().isEmpty)
        }
        #expect(await calls.balanceReads == 1)
        #expect(await calls.submitted == (enoughReserve ? 1 : 0))
        #expect(await calls.discarded == [quote.id])
        #expect(await session.boardPreparations.isEmpty)
    }
    @Test(arguments: [false, true])
    func automaticBalanceReadFailureDiscardsWithoutClaimingOrSubmitting(failCleanup: Bool) async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let quote = Self.automaticQuote
        await calls.failCleanup(failCleanup)
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            Issue.record("Balance read failure must not submit"); throw OnchainFailure.invalid
        })
        await #expect(throws: OnchainFailure.unavailable) {
            try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: {
                #expect(await session.boardPreparations["board"]?.claimed == false)
                throw URLError(.notConnectedToInternet)
            })
        }
        #expect(try await journal.loadAll().isEmpty)
        if failCleanup {
            #expect(await session.boardPreparations["board"]?.claimed == false)
            #expect(await session.boardPreparations["board"]?.discardRequested == true)
            await calls.failCleanup(false)
            try await session.discardBoardReviews()
        }
        #expect(await session.boardPreparations.isEmpty)
        #expect(await calls.discarded == [quote.id])
    }
    actor BalanceRead {
        var continuation: CheckedContinuation<UInt64, Never>?
        func confirmed() async -> UInt64 { await withCheckedContinuation { continuation = $0 } }
        func finish(_ value: UInt64) { continuation?.resume(returning: value); continuation = nil }
    }
    @Test func invalidationDuringBalanceReadCannotClaimOrSubmit() async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let balance = BalanceRead()
        let quote = Self.automaticQuote
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            Issue.record("Invalidated review must not submit"); throw OnchainFailure.invalid
        })
        let work = Task {
            try await session.submitBoard(quote, walletID: "wallet", journal: journal,
                confirmed: { await balance.confirmed() })
        }
        for _ in 0..<1000 where await balance.continuation == nil { await Task.yield() }
        #expect(await balance.continuation != nil)
        #expect(await session.boardPreparations["board"]?.claimed == false)
        await session.discardBoard(quote)
        await balance.finish(quote.confirmed)
        await #expect(throws: OnchainFailure.changed) { try await work.value }
        #expect(await session.boardPreparations.isEmpty)
        #expect(await calls.discarded == [quote.id])
        #expect(try await journal.loadAll().isEmpty)
    }
    @Test func manualBoardDoesNotReadAnAutomaticReserve() async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let quote = Self.quote
        await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
            await calls.submit(.init(txid: "exact", broadcast: .accepted, vtxoIds: []))
        })
        _ = try await session.submitBoard(quote, walletID: "wallet", journal: journal, confirmed: {
            Issue.record("Manual boards have no automatic reserve"); throw OnchainFailure.unavailable
        })
        #expect(await calls.submitted == 1)
        #expect(await calls.discarded == [quote.id])
    }
    @Test func exactValuesAndTwoMinuteExpirySurviveJournalRoundTrip() throws {
        var quote = try JSONDecoder().decode(OnchainQuote.self, from: JSONEncoder().encode(Self.quote))
        #expect(quote.amount == 30_000 && quote.net == 29_900 && quote.fee == 100 && quote.chainFee == 1543)
        #expect(quote.amount + quote.chainFee! == 31_543)
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

    func uncertainJournal() throws -> (OnchainOperationJournal, URL, UUID) {
        let (journal, file) = journal()
        var attempt = OnchainOperationJournal.Attempt(quote: Self.quote)
        attempt.txid = "exact"
        attempt.uncertainBoard = true
        try JSONEncoder().encode([attempt]).write(to: file)
        return (journal, file, attempt.id)
    }

    func check(_ journal: OnchainOperationJournal, _ id: UUID, ids: Set<String> = [], vtxoAnchors: Set<String> = [], chain: @escaping @Sendable () throws -> ChainTransactionStatus? = { .notFound },
               unspent: @escaping @Sendable () throws -> Set<String> = { ["funding:0", "other:1"] }) async throws -> Bool {
        try await WalletSession.checkOnchainAttempt(id, journal: journal, reads: .init(
            walletID: "wallet", transactionIDs: { ids }, vtxoState: { _ in "Spendable" }, exitState: { _ in nil },
            transactionStatus: { _ in try chain() }, vtxoAnchors: { vtxoAnchors }, unspent: { try unspent() }))
    }

    /// Nothing boarded: no pending board or wallet transaction names the txid, the chain source says it isn't
    /// found and the input is still an unspent wallet coin. Check status reads no movements, so an orphan BOARD
    /// movement can't hold it (bark never had the signed bytes). It goes, and a fresh board may follow.
    @Test func uncertainBoardProvenNeverBoardedIsReleased() async throws {
        let (journal, file, id) = try uncertainJournal()
        defer { try? FileManager.default.removeItem(at: file) }
        #expect(try await check(journal, id))
        #expect(try await journal.loadAll().isEmpty)
        _ = try await journal.perform(Self.quote) { "fresh board" }
    }

    /// Any doubt keeps it locked and reads as "Status remains uncertain": an unreachable chain source, no answer,
    /// an input spent or no longer the wallet's, coins that can't be read.
    @Test(arguments: [0, 1, 2, 3])
    func uncertainBoardStaysLockedOnAnyDoubt(doubt: Int) async throws {
        let (journal, file, id) = try uncertainJournal()
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
        let (journal, file, id) = try uncertainJournal()
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
        let (journal, file, id) = try uncertainJournal()
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
        edited.chainFee! += 1
        var expired = quote
        expired.prepared?.createdAt = Date().addingTimeInterval(-121)
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
        #expect(quote.confirmed - quote.amount - quote.chainFee! == 5_000)
    }
    @Test(arguments: [UInt64(12_000), 12_001])
    func exactReserveIsCheckedBeforeReview(fee: UInt64) async throws {
        let calls = AutoBoardCalls(reviewExtraFee: fee - 12_000)
        let result = try await calls.review()
        #expect((result != nil) == (fee == 12_000))
        #expect(await calls.discarded.count == (result == nil ? 2 : 1))
        #expect(await calls.active.count == (result == nil ? 0 : 1))
        if let result { #expect(result.confirmed - result.amount - result.chainFee! == 5_000) }
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
