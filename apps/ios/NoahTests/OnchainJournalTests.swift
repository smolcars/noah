import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

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
                        [OnchainOperationJournal.Attempt].self, from: Data(contentsOf: file)).first?.quote.amount == 20000)
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
    @Test func confirmedBoardKeepsReceiptButAllowsNewReviewWithoutDone() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let first = Self.quote
        _ = try await journal.perform(first) {
            try await journal.recordTransaction("tx", for: first.id)
            return .init(message: "submitted", txid: "tx")
        }
        #expect(try #require(try await journal.load()).blocks(.board, ids: []))
        try await journal.observe(.confirmed(100), for: first.id)
        let restarted = OnchainOperationJournal(file: file)
        #expect(try !#require(try await restarted.load()).blocks(.board, ids: []))
        _ = try await restarted.perform(Self.quote) { .init(message: "second", txid: "tx2") }
        let attempts = try await restarted.loadAll()
        #expect(attempts.count == 2)
        #expect(attempts.first?.receipt?.txid == "tx")
        #expect(attempts.first?.receipt?.chainStatus == .confirmed(100))
    }
    @Test func uncertainRetryUnknownAndMismatchedReceiptStillBlock() {
        var retry = OnchainReceipt(message: "retry", txid: "tx")
        retry.broadcastRetryPending = true
        var mempool = OnchainReceipt(message: "mempool", txid: "tx")
        mempool.chainStatus = .inMempool
        var mismatch = OnchainReceipt(message: "mismatch", txid: "other")
        mismatch.chainStatus = .confirmed(100)
        for receipt in [nil, retry, .init(message: "unknown", txid: "tx"), mempool, mismatch] as [OnchainReceipt?] {
            let attempt = OnchainOperationJournal.Attempt(quote: Self.quote, receipt: receipt, txid: "tx")
            #expect(attempt.blocks(.board, ids: []))
        }
    }
    @Test func unreadableJournalFailsClosedBeforeSubmitting() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        try Data("unknown journal".utf8).write(to: file)
        let journal = OnchainOperationJournal(file: file)
        await #expect(throws: (any Error).self) {
            try await journal.perform(Self.quote) { Issue.record("Must not submit"); return "bad" }
        }
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
    @Test func quotePinsWalletSelectionDestinationAndBothFees() throws {
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
        changed.chainFee = try #require(changed.chainFee) + 1
        #expect(!original.matches(changed))
        changed = original
        changed.fee = try #require(changed.fee) + 1
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
        #expect(!ExitRules.current(elapsed: .seconds(30)))
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

    /// The wallet's reads, numbered by the overview's tip; a submission moves it on. One read or submission can be held
    /// mid-way; a held submission finishes even if its caller is canceled, as the wallet's does, and a canceled caller's
    /// read fails.
    actor Reads {
        var version: UInt32 = 1
        var sawEarlierRead = false
        var held: CheckedContinuation<Void, Never>?
        private var holdRead = false, holdSubmission = false
        func holdNext() { holdRead = true }
        func holdNextSubmission() { holdSubmission = true }
        func read(walletID: String) async throws -> OnchainOverview {
            try Task.checkCancellation()
            let seen = OnchainOverview(walletID: walletID, vtxos: [], exits: [], confirmed: 40000, tip: version, pendingTotal: 0)
            if holdRead {
                holdRead = false
                await withCheckedContinuation { held = $0 }
            }
            return seen
        }
        func release() { held?.resume(); held = nil }
        func submit(showing: OnchainOverview?) async {
            sawEarlierRead = sawEarlierRead || showing != nil
            if holdSubmission {
                holdSubmission = false
                await withCheckedContinuation { held = $0 }
            }
            version += 1
        }
    }

    /// A page's model on the wallet `reads` stands for, sharing `shared` with the others.
    func model(_ shared: SharedOverview, _ reads: Reads, walletID: String = "wallet") -> OnchainModel {
        var dependencies = OnchainModel.Dependencies(
            ready: { true }, overview: { try await reads.read(walletID: walletID) },
            quote: { _, _, _, _ in OnchainJournalTests.quote },
            execute: { _ in
                await reads.submit(showing: await shared.overview)
                return "submitted"
            }, attempt: { [] }, acknowledge: { _ in })
        dependencies.shared = shared
        return OnchainModel(dependencies: dependencies)
    }

    /// exit-v2-notes.md, Approved item 4: Back to the Emergency exit page shows the newest read since your last cancel,
    /// claim or start, never one from before it.
    @Test func theSharedOverviewIsTheNewestReadSinceTheLastSubmission() async {
        let shared = SharedOverview(), reads = Reads()
        func model(walletID: String = "wallet") -> OnchainModel { self.model(shared, reads, walletID: walletID) }
        let warning = model()
        await warning.load()
        #expect(shared.overview?.tip == 1)
        // The exit's page cancels it: no page shows the read from before while that runs, and the exit page's read after
        // it is what the Emergency exit page shows when Back returns there, until its own read.
        let exit = model()
        await exit.review(.cancel, ids: ["a"])
        #expect(await exit.confirm())
        #expect(await !reads.sawEarlierRead)
        #expect(shared.overview?.tip == 2)
        #expect(warning.overview?.tip == 1 && warning.freshest?.tip == 2)
        // Never another wallet's.
        let other = model(walletID: "other")
        await other.load()
        #expect(other.freshest?.walletID == "other" && warning.freshest?.walletID == "wallet")
        // A read that began before a submission and ends after it may have missed it: dropped.
        await reads.holdNext()
        let slow = model()
        let reading = Task { await slow.load() }
        while await reads.held == nil { await Task.yield() }
        await exit.review(.cancel, ids: ["b"])
        #expect(await exit.confirm())
        await reads.release()
        await reading.value
        #expect(slow.overview?.tip == 2 && shared.overview?.tip == 3)
    }

    /// Back cancels the submitting page's task while the wallet still finishes the submission: a read made meanwhile may
    /// predate it, so it isn't what the page Back returns to shows.
    @Test func aSubmissionWhoseCallerIsCanceledStillDropsReadsMadeDuringIt() async {
        let shared = SharedOverview(), reads = Reads()
        let exit = model(shared, reads)
        await exit.review(.cancel, ids: ["a"])
        await reads.holdNextSubmission()
        let confirming = Task { await exit.confirm() }
        while await reads.held == nil { await Task.yield() }
        // Another page reads while the cancel is still being sent: what it reads is from before it.
        await model(shared, reads).load()
        #expect(shared.overview?.tip == 1)
        confirming.cancel()
        await reads.release()
        _ = await confirming.value
        #expect(shared.overview == nil)
        #expect(!exit.busy)
    }

    /// Back to Settings after you cancel an exit: its Emergency exit row shows the exit page's read from after the
    /// cancel at once, and Settings reads again however recently it last did.
    @Test func settingsShowsTheExitsAsYouLeftThemAfterASubmission() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "settings-shared-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let shared = SharedOverview(), reads = Reads()
        let summary = SettingsSummary(dependencies: .init(overview: { try await reads.read(walletID: "wallet") }, notificationsOn: { true },
                                                          storage: try SettingsStorage(rootPath: root.path), shared: shared))
        await summary.refresh(suspended: false)
        #expect(summary.overview?.tip == 1)
        let exit = model(shared, reads)
        await exit.review(.cancel, ids: ["a"])
        #expect(await exit.confirm())
        #expect(summary.overview?.tip == 2)
        // The wallet moves on without a submission: only a read of Settings' own sees it.
        await reads.submit(showing: nil)
        await summary.refresh(suspended: false)
        #expect(summary.overview?.tip == 3)
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
        #expect(model.message?.contains("Noah checks again automatically as your wallet syncs.") == true)
        #expect(await calls.mutations == 0)
        await calls.setReady(false)
        await model.checkStatus(board.id)
        #expect(model.error == OnchainFailure.unavailable.errorDescription)
        await model.discardUncertain(board.id)
        #expect(model.attempts.first?.overrideAudit != nil)
        #expect(try await OnchainOperationJournal(file: file).load()?.overrideAudit != nil)
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

@MainActor
struct ExitFallbackModelTests {
    actor Calls {
        var estimates = 0
        var submissions = 0
        func quote(_ action: OnchainAction, _ ids: [String], _ destination: String?, expired: Bool, fails: Bool) throws -> OnchainQuote {
            estimates += 1
            if !fails { throw OnchainFailure.unavailable }
            throw ExitEstimateUnavailable(review: .init(action: action, walletID: "wallet", ids: ids,
                                                        destination: destination, amount: 30000, fee: nil, chainFee: nil, confirmed: 0,
                                                        createdAt: expired ? .distantPast : Date()))
        }
        func execute(_ quote: OnchainQuote) -> String {
            #expect(quote.hasUnknownFeeConsent)
            submissions += 1; return "submitted"
        }
    }
    func model(_ calls: Calls, expired: Bool = false, fails: Bool = true) -> OnchainModel {
        .init(dependencies: .init(ready: { true }, overview: { throw OnchainFailure.unavailable },
                                  quote: { action, ids, _, destination in try await calls.quote(action, ids, destination, expired: expired, fails: fails) },
                                  execute: { await calls.execute($0) }, attempt: { [] }, acknowledge: { _ in }))
    }
    @Test func fallbackNeedsSeparateConfirmationAndInvalidatesWithInputs() async {
        for action in [OnchainAction.startExit, .claim] {
            let calls = Calls(); let model = model(calls)
            await model.review(action, ids: ["a"], destination: "address")
            #expect(model.quote == nil && model.estimateFallback != nil)
            await model.confirm(); await model.load()
            #expect(await calls.submissions == 0)
            #expect(await calls.estimates == 1)
            model.editClaimDestination("other")
            await model.continueWithoutEstimate()
            #expect(model.quote == nil)
            await model.review(action, ids: ["b"], destination: "other")
            await model.continueWithoutEstimate()
            #expect(model.quote?.ids == ["b"] && model.quote?.destination == "other")
            await model.confirm(); await model.confirm()
            #expect(await calls.submissions == 1)
            #expect(await calls.estimates == 2)
        }
    }
    @Test(arguments: [OnchainAction.startExit, .claim]) func confirmationFallbackKeepsOriginalReview(action: OnchainAction) async {
        var original = OnchainJournalTests.quote
        original.action = action; original.createdAt = Date().addingTimeInterval(-29)
        let reviewed = original
        let model = OnchainModel(dependencies: .init(ready: { true }, overview: { throw OnchainFailure.unavailable },
                                                     quote: { _, _, _, _ in reviewed }, execute: { quote in
                                                         var unknown = quote
                                                         unknown.id = UUID(); unknown.createdAt = Date()
                                                         unknown.fee = nil; unknown.chainFee = nil; unknown.feeRate = nil
                                                         throw ExitEstimateUnavailable(review: unknown)
                                                     }, attempt: { [] }, acknowledge: { _ in }))
        await model.review(action, ids: original.ids)
        await model.confirm()
        #expect(model.estimateFallback?.id == original.id)
        #expect(model.estimateFallback?.createdAt == original.createdAt)
        await model.continueWithoutEstimate()
        #expect(model.quote?.hasUnknownFeeConsent == true)
    }
    @Test func expiredFallbackOrOtherErrorsNeverOpenReview() async {
        for (expired, fails) in [(true, true), (false, false)] {
            let calls = Calls(); let model = model(calls, expired: expired, fails: fails)
            await model.review(.startExit, ids: ["a"])
            if !fails { #expect(model.estimateFallback == nil) }
            await model.continueWithoutEstimate(); await model.confirm()
            #expect(model.quote == nil)
            #expect(await calls.submissions == 0)
        }
    }
}

struct ExitFallbackDeadlineTests {
    @Test(arguments: [OnchainAction.startExit, .claim]) func conversionRetainsScopeAndOriginalDeadline(action: OnchainAction) throws {
        var original = OnchainJournalTests.quote
        original.action = action
        // The wall clock is far from the review (1970): only the continuous clock times it.
        original.createdAt = Date(timeIntervalSince1970: 1000)
        let opened = ContinuousClock.now
        original.reviewedAt = opened
        var unknown = original
        unknown.id = UUID(); unknown.createdAt = original.createdAt.addingTimeInterval(29)
        unknown.reviewedAt = opened + .seconds(29)
        unknown.fee = nil; unknown.chainFee = nil; unknown.feeRate = nil
        let failure = ExitEstimateUnavailable(review: unknown)
        let converted = try failure.preserving(original, now: opened + .seconds(29)).review
        #expect(converted.id == original.id && converted.createdAt == original.createdAt && converted.reviewedAt == opened)
        #expect(!converted.hasUnknownFeeConsent)
        #expect(throws: OnchainFailure.self) { try failure.preserving(original, now: opened + .seconds(30)) }
        for change in ["wallet", "ids", "amount", "destination", "action", "confirmed"] {
            var changed = unknown
            switch change {
            case "wallet": changed.walletID = "other"
            case "ids": changed.ids = ["other"]
            case "amount": changed.amount += 1
            case "destination": changed.destination = "other"
            case "action": changed.action = action == .claim ? .startExit : .claim
            default: changed.confirmed += 1
            }
            #expect(throws: OnchainFailure.self) {
                try ExitEstimateUnavailable(review: changed).preserving(original, now: opened + .seconds(29))
            }
        }
    }
}
