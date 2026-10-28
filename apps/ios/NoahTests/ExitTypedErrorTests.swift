import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// bark-bolt-ffi 0.7.0's typed exit errors: never-exitable VTXOs, an unavailable chain source, and too
/// few confirmed funds. No message is ever read.
struct ExitTypedErrorTests {
    static let never: [BarkErrorCode] = [.exitNonStandardVtxo, .exitDustLimit, .exitVtxoAlreadySpent, .exitVtxoAlreadyExited,
                                         .exitVtxoSwept, .exitUnknownVtxo, .exitCyclicTransactions, .exitMissingAnchorOutput]
    static let chain: [BarkErrorCode] = [.exitTransactionRetrievalFailure, .exitTipRetrievalFailure, .exitBlockRetrievalFailure,
                                         .exitAncestorRetrievalFailure]

    static func error(_ code: BarkErrorCode, vtxo: String? = nil) -> BarkError {
        BarkError(message: "private diagnostic", code: code, vtxoId: vtxo)
    }

    @Test func onlyRetryableFailuresOfferContinueWithoutEstimate() {
        for code in BarkErrorCode.allCases {
            // Transport failures stay Other; a fee-rate overflow was Other before 0.7.0.
            #expect(ExitEstimateUnavailable.allowsFallback(Self.error(code)) == ([.other, .invalidFeeRate] + Self.chain).contains(code))
            #expect((ExitRefusal(Self.error(code)) != nil) == Self.never.contains(code))
        }
        #expect(Self.never.filter { ExitRefusal(code: $0, vtxoID: nil).renewFirst } == [.exitNonStandardVtxo, .exitDustLimit])
        // A shortfall isn't a refusal but still says so, never in bark's words; other failures say nothing.
        let short = BarkError(message: "private", code: .exitInsufficientConfirmedFunds, neededSats: 1_215, availableSats: 310)
        #expect(ExitRefusal(short) == nil && ExitRefusal.message(short).map { !$0.contains("private") } == true)
        for other in [Self.error(.exitTipRetrievalFailure), Self.error(.other)] as [any Error] + [URLError(.timedOut)] {
            #expect(ExitRefusal.message(other) == nil)
        }
    }

    static func access(_ estimate: @escaping @Sendable ([String]) async throws -> EmergencyExitFeeEstimate) -> WalletSession.OnchainStartAccess {
        .init(walletID: "wallet", confirmed: { 50_000 }, spendable: { ["a": 20_000, "b": 30_000] }, estimate: estimate,
              start: { _ in Issue.record("A review never starts an exit") })
    }

    @Test func aNeverExitableSelectionThrowsItsRefusalButAnUnavailableChainFallsBack() async throws {
        let refusal = Self.error(.exitVtxoAlreadySpent, vtxo: "b")
        await #expect(throws: refusal) {
            try await WalletSession.startQuote(ids: ["a", "b"], access: Self.access { _ in throw refusal })
        }
        for code in Self.chain {
            do {
                _ = try await WalletSession.startQuote(ids: ["a", "b"], access: Self.access { _ in throw Self.error(code) })
                Issue.record("estimated")
            } catch let failure as ExitEstimateUnavailable {
                #expect(failure.review.feesUnknown && failure.review.ids == ["a", "b"])
            }
        }
    }

    @MainActor @Test func theReviewShowsTheReasonWithoutContinueWithoutEstimate() async {
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.quote = { _, _, _, _ in throw Self.error(.exitVtxoSwept, vtxo: "a") }
        let model = OnchainModel(dependencies: dependencies)
        await model.review(.startExit, ids: ["a"])
        #expect(model.error == "Swept" && model.estimateFallback == nil && model.quote == nil)
        dependencies.quote = { _, _, _, _ in throw Self.error(.exitInsufficientConfirmedFunds) }
        let funds = OnchainModel(dependencies: dependencies)
        await funds.review(.startExit, ids: ["a"])
        #expect(funds.error == "Needs on-chain funds")
        #expect(await calls.mutations == 0)
    }

    @MainActor @Test func anEstimateRefusalNamesTheVtxoToTakeOut() async {
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.estimate = { ids in
            if ids.contains("b") { throw Self.error(.exitNonStandardVtxo, vtxo: "b") }
            if ids.contains("c") { throw Self.error(.exitTipRetrievalFailure) }
            return .init(exitBroadcastFeeSats: 900, claimFeeSats: 300, totalFeeSats: 1_200, feeRateSatPerVb: 2, txsToBroadcast: 2, excludedVtxos: [])
        }
        let model = OnchainModel(dependencies: dependencies)
        #expect(await model.exitEstimate(["a", "b"]) == nil)
        #expect(model.exitRefusal == ExitRefusal(code: .exitNonStandardVtxo, vtxoID: "b") && model.exitRefusal?.renewFirst == true)
        // A chain source that's down is no refusal: Choose keeps "Fee estimate unavailable".
        #expect(await model.exitEstimate(["a", "c"]) == nil)
        #expect(model.exitRefusal == nil)
        #expect(await model.exitEstimate(["a"])?.totalFeeSats == 1_200)
        #expect(model.exitRefusal == nil)
    }

    @Test func refusedVtxosAreClassifiedButNeverInAll() async {
        let vtxos = [ExitProgressTests.vtxo("a", 31_000), ExitProgressTests.vtxo("b", 24_500), ExitProgressTests.vtxo("c", 18_000)]
        let costs = await ExitEconomics.classify(vtxos) { ids in
            if ids == ["b"] { throw Self.error(.exitNonStandardVtxo, vtxo: "b") }
            if ids == ["c"] { throw Self.error(.exitBlockRetrievalFailure) }
            return 2_100
        }
        #expect(costs == ["a": .worthIt, "b": .refused(.init(code: .exitNonStandardVtxo, vtxoID: "b")), "c": .failed])
        // The chain-source failure holds All (Try again); the refusal doesn't.
        #expect(!ExitEconomics.classified(vtxos, costs: costs))
        #expect(ExitEconomics.classified(vtxos, costs: costs.merging(["c": .worthIt]) { $1 }))
        #expect(ExitEconomics.all(vtxos, costs: costs.merging(["c": .worthIt]) { $1 }).map(\.id) == ["a", "c"])
    }

    /// FFI 0.7.0's "All" estimate lists what bark's whole-wallet exit leaves out instead of failing.
    @MainActor @Test func wholeWalletExclusionsRenewFirstAndStayOutOfAll() async throws {
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.exclusions = {
            [.init(vtxoId: "b", amountSats: 24_500, code: .exitNonStandardVtxo, message: "private"),
             .init(vtxoId: "x", amountSats: 900, code: .other, message: "private")]
        }
        let model = OnchainModel(dependencies: dependencies)
        let excluded = await model.exitExclusions()
        #expect(Set(excluded.keys) == ["b", "x"] && excluded.values.allSatisfy(\.renewFirst))
        let vtxos = [ExitProgressTests.vtxo("a", 31_000), ExitProgressTests.vtxo("b", 24_500)]
        let costs: [String: ExitEconomics.Cost] = try ["a": .worthIt, "b": .refused(#require(excluded["b"]))]
        #expect(ExitEconomics.classified(vtxos, costs: costs))
        #expect(ExitEconomics.all(vtxos, costs: costs).map(\.id) == ["a"])
        // Unreadable: nothing is marked, and each VTXO's own estimate still refuses it.
        dependencies.exclusions = { throw Self.error(.exitTipRetrievalFailure) }
        #expect(await OnchainModel(dependencies: dependencies).exitExclusions().isEmpty)
        await calls.setReady(false)
        #expect(await model.exitExclusions().isEmpty)
    }

    static let fee = EmergencyExitFeeEstimate(exitBroadcastFeeSats: 900, claimFeeSats: 300, totalFeeSats: 1_200, feeRateSatPerVb: 2,
                                              txsToBroadcast: 2, excludedVtxos: [])

    /// bark refused the start itself: nothing was broadcast, so no attempt is kept, and the reason shows.
    /// Any other failure of the start stays uncertain.
    @Test func aRefusedStartDropsItsAttemptButOtherFailuresStayUncertain() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let refusal = Self.error(.exitVtxoAlreadyExited, vtxo: "b")
        var access = Self.access { _ in Self.fee }
        access.start = { _ in throw refusal }
        let quote = try await WalletSession.startQuote(ids: ["a", "b"], access: access)
        await #expect(throws: refusal) { try await WalletSession.executeStart(quote, journal: journal, access: access) }
        #expect(try await journal.loadAll().isEmpty && ExitRefusal.message(refusal) == "This VTXO can't be exited.")
        access.start = { _ in throw Self.error(.other) }
        let again = try await WalletSession.startQuote(ids: ["a", "b"], access: access)
        await #expect(throws: OnchainFailure.uncertain) { try await WalletSession.executeStart(again, journal: journal, access: access) }
        #expect(try await journal.loadAll().map(\.id) == [again.id])
    }

    /// bark marks the VTXOs it can, in order, and refuses B: A is exiting, B is never exitable.
    actor PartlyRefused {
        var spendable: [String: UInt64] = ["p3-a": 20_000, "p3-b": 30_000, "p3-c": 25_000]
        var exits: [String] = []
        func start(_ ids: [String]) throws {
            for id in ids {
                if id == "p3-b" { throw ExitTypedErrorTests.error(.exitNonStandardVtxo, vtxo: id) }
                guard spendable.removeValue(forKey: id) != nil else { continue } // bark skips an active exit
                exits.append(id)
            }
        }
    }

    @MainActor @Test func aPartlyRefusedStartRetriesWithoutDuplicatesAfterAReload() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = PartlyRefused()
        var access = Self.access { _ in Self.fee }
        access.spendable = { await wallet.spendable }
        access.start = { try await wallet.start($0) }
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.quote = { [access] _, ids, _, _ in try await WalletSession.startQuote(ids: ids, access: access) }
        dependencies.execute = { [access] in try await WalletSession.executeStart($0, journal: OnchainOperationJournal(file: file), access: access) }
        let model = OnchainModel(dependencies: dependencies)
        await model.review(.startExit, ids: ["p3-a", "p3-b"])
        #expect(await !model.confirm())
        #expect(model.error == "This VTXO can't be exited.")
        #expect(await wallet.exits == ["p3-a"])
        // After a reload: no attempt is kept, B stays refused for the run, and A is no longer spendable.
        let reloaded = OnchainOperationJournal(file: file)
        #expect(try await reloaded.loadAll().isEmpty)
        #expect(OnchainModel.refused["p3-b"]?.renewFirst == true)
        await #expect(throws: OnchainFailure.changed) { try await WalletSession.startQuote(ids: ["p3-a", "p3-b"], access: access) }
        // What Choose offers now: neither A (not spendable) nor B (refused).
        let vtxos = await wallet.spendable.map { ExitProgressTests.vtxo($0.key, $0.value) }
        let costs = Dictionary(vtxos.map { ($0.id, OnchainModel.refused[$0.id].map(ExitEconomics.Cost.refused) ?? .worthIt) },
                               uniquingKeysWith: { a, _ in a })
        let retry = ExitEconomics.all(vtxos, costs: costs).map(\.id)
        #expect(retry == ["p3-c"])
        let quote = try await WalletSession.startQuote(ids: retry, access: access)
        _ = try await WalletSession.executeStart(quote, journal: reloaded, access: access)
        #expect(await wallet.exits == ["p3-a", "p3-c"])
    }

    @MainActor @Test func refusalsStayForTheRestOfTheAppRun() async {
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.estimate = { _ in throw Self.error(.exitVtxoSwept, vtxo: "run-swept") }
        _ = await OnchainModel(dependencies: dependencies).exitEstimate(["run-swept"])
        // Another screen's model, and a later estimate that works, don't forget it.
        dependencies.estimate = { _ in Self.fee }
        _ = await OnchainModel(dependencies: dependencies).exitEstimate(["run-swept"])
        #expect(OnchainModel.refused["run-swept"] == .init(code: .exitVtxoSwept, vtxoID: "run-swept"))
        // A one-VTXO price refused without a name is that VTXO's; a review's named refusal is kept too.
        dependencies.estimate = { _ in throw Self.error(.exitCyclicTransactions) }
        _ = await OnchainModel(dependencies: dependencies).exitCosts([ExitProgressTests.vtxo("run-cyclic", 30_000)])
        #expect(OnchainModel.refused["run-cyclic"]?.code == .exitCyclicTransactions)
        dependencies.quote = { _, _, _, _ in throw Self.error(.exitDustLimit, vtxo: "run-dust") }
        await OnchainModel(dependencies: dependencies).review(.startExit, ids: ["run-dust"])
        #expect(OnchainModel.refused["run-dust"]?.renewFirst == true)
        // A chain source that's down refuses nothing.
        dependencies.estimate = { _ in throw Self.error(.exitTipRetrievalFailure) }
        _ = await OnchainModel(dependencies: dependencies).exitCosts([ExitProgressTests.vtxo("run-chain", 30_000)])
        #expect(OnchainModel.refused["run-chain"] == nil)
    }

    /// Compact All hides the VTXO list, so it lists what it leaves out to renew first, an `.other` exclusion
    /// too: unselectable, "Renew first" with the line on how. Choose and wide windows list every VTXO.
    @MainActor @Test func compactAllListsExcludedCoinsToRenewFirst() async {
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.exclusions = {
            [.init(vtxoId: "ca-other", amountSats: 24_500, code: .other, message: "private"),
             .init(vtxoId: "ca-standard", amountSats: 18_000, code: .exitNonStandardVtxo, message: "private")]
        }
        let excluded = await OnchainModel(dependencies: dependencies).exitExclusions()
        let vtxos = [ExitProgressTests.vtxo("ca-a", 31_000), ExitProgressTests.vtxo("ca-other", 24_500),
                     ExitProgressTests.vtxo("ca-standard", 18_000), ExitProgressTests.vtxo("ca-dust", 290),
                     ExitProgressTests.vtxo("ca-swept", 20_000)]
        var costs: [String: ExitEconomics.Cost] = ["ca-a": .worthIt, "ca-swept": .refused(.init(code: .exitVtxoSwept, vtxoID: "ca-swept"))]
        for (id, refusal) in excluded { costs[id] = .refused(refusal) }
        #expect(ExitEconomics.rows(vtxos, costs: costs, compactAll: true).map(\.id) == ["ca-other", "ca-standard"])
        #expect(ExitEconomics.rows(vtxos, costs: costs, compactAll: false).count == vtxos.count)
        #expect(ExitEconomics.all(vtxos, costs: costs).map(\.id) == ["ca-a"])
    }

    @Test func progressReadsEachExitsErrorByItsCode() {
        func status(_ id: String, _ error: BarkError?) -> ExitProgressStatus {
            .init(vtxoId: id, state: .processing(tipHeight: 101, transactions: []), error: error)
        }
        let outcome = WalletSession.progressOutcome([
            status("ok", nil),
            status("short", BarkError(message: "private", code: .exitInsufficientConfirmedFunds, neededSats: 1_215, availableSats: 310)),
            status("unpriced", BarkError(message: "private", code: .exitInsufficientConfirmedFunds)),
            status("chain", Self.error(.exitTipRetrievalFailure)),
            status("spent", Self.error(.exitVtxoAlreadySpent, vtxo: "spent")),
        ])
        // Too few funds is evidence, not a failure: tried, with bark's figure when it gave one.
        #expect(outcome.tried == ["ok", "short", "unpriced"])
        #expect(outcome.needed == ["short": 1_215])
        #expect(outcome.failures == 2)
        #expect(WalletSession.progressOutcome([]).failures == 0)
    }
}
