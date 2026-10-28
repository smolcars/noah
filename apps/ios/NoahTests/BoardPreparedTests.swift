import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

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
            let handled = try #require(String(bytes: JSONEncoder().encode(test.input.handled), encoding: .utf8))
            ledger.read = { handled }
            let funding = AutoBoardFunding.Snapshot(walletID: test.input.walletID, outpoints: test.input.outpoints)
            #expect(try ledger.hasUnhandled(funding) == test.expected, "\(test.name)")
        }
    }
    @Test func threeDepositsPromptOnceAcrossRestart() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let path = root.appending(path: "mmkv").path
        let storage = try SettingsStorage(rootPath: path)
        for deposit in 0..<3 {
            let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: Set((0...deposit).map { "deposit-\($0):0" }))
            #expect(try AutoBoardFunding(storage: storage).hasUnhandled(funding))
            #expect(try AutoBoardFunding(storage: storage).markHandled(funding))
            let reopened = AutoBoardFunding(storage: try SettingsStorage(rootPath: path))
            #expect(try !reopened.hasUnhandled(funding))
            #expect(try !reopened.markHandled(funding))
        }
    }
    @Test func confirmedFundingExcludesPendingExitUnknownAndBoardChange() {
        let funding = AutoBoardFunding.confirmed(walletID: "wallet", utxos: [
            .local(outpoint: .init(txid: "deposit", vout: 1), amountSats: 150_529, confirmationHeight: 100),
            .local(outpoint: .init(txid: "pending", vout: 0), amountSats: 150_529, confirmationHeight: nil),
            .local(outpoint: .init(txid: "change", vout: 0), amountSats: 5_000, confirmationHeight: 100),
            .local(outpoint: .init(txid: "unknown", vout: 0), amountSats: 150_529, confirmationHeight: 100),
            .exit(vtxoId: "exit", amountSats: 100_000, height: 100),
        ], transactions: [
            .init(txid: "deposit", txHex: "", onchainFeeSats: nil, balanceChangeSats: 150_529, confirmation: nil, isCpfp: false),
            .init(txid: "change", txHex: "", onchainFeeSats: 155, balanceChangeSats: -140_000, confirmation: nil, isCpfp: false),
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
    static let chainFee: UInt64 = 1543
    static var quote: OnchainQuote {
        .init(action: .board, walletID: "wallet", ids: [], amount: 30_000, fee: 100,
              chainFee: chainFee, confirmed: 40_000, feeRate: 1,
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
        quote.confirmed = quote.amount + chainFee + 5_000
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
            let txid = try #require(quote.prepared).txid
            await session.retainBoardReview(quote, discard: { try await calls.discard(quote) }, submit: {
                await calls.submit(.init(txid: txid, broadcast: .accepted, vtxoIds: []))
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
    @Test(arguments: [UInt64(32_543), 36_542, 36_543])
    func automaticConfirmationRevalidatesTheExactReviewedReserve(confirmed: UInt64) async throws {
        let (journal, file) = journal()
        defer { try? FileManager.default.removeItem(at: file) }
        let session = WalletSession()
        let calls = Calls()
        let quote = Self.automaticQuote
        #expect(try quote.confirmed - quote.amount - #require(quote.chainFee) == 5_000)
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
}
