import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

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
    }

    @Test func refusalsUseTheSpecsWordsAndNameTheVtxo() {
        func reason(_ code: BarkErrorCode) -> String? { ExitRefusal(Self.error(code))?.errorDescription }
        #expect(reason(.exitVtxoAlreadySpent) == "Already spent")
        #expect(reason(.exitVtxoSwept) == "Swept")
        for code in Self.never where ![.exitVtxoAlreadySpent, .exitVtxoSwept].contains(code) {
            #expect(reason(code) == "This VTXO can't be exited.")
        }
        #expect(Self.never.filter { ExitRefusal(code: $0, vtxoID: nil).renewFirst } == [.exitNonStandardVtxo, .exitDustLimit])
        #expect(ExitRefusal(Self.error(.exitVtxoSwept, vtxo: "v1"))?.vtxoID == "v1")
        let short = BarkError(message: "private", code: .exitInsufficientConfirmedFunds, neededSats: 1_215, availableSats: 310)
        #expect(ExitRefusal(short) == nil && ExitRefusal.message(short) == "Needs on-chain funds")
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
    @MainActor @Test func wholeWalletExclusionsRenewFirstAndStayOutOfAll() async {
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
        let costs: [String: ExitEconomics.Cost] = ["a": .worthIt, "b": .refused(excluded["b"]!)]
        #expect(ExitEconomics.classified(vtxos, costs: costs))
        #expect(ExitEconomics.all(vtxos, costs: costs).map(\.id) == ["a"])
        // Unreadable: nothing is marked, and each VTXO's own estimate still refuses it.
        dependencies.exclusions = { throw Self.error(.exitTipRetrievalFailure) }
        #expect(await OnchainModel(dependencies: dependencies).exitExclusions().isEmpty)
        await calls.setReady(false)
        #expect(await model.exitExclusions().isEmpty)
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

    @Test func barksFigureProvesNeedsFundsAtOnceAndClearsWhenFundsArrive() {
        var txs = ExitProgressTests.path.map { ExitProgressTests.tx($0, "verify-inputs") }
        txs[0] = ExitProgressTests.tx("root", "awaiting-cpfp-broadcast")
        let exit = ExitProgressTests.exit("Processing", ExitProgressTests.processing(txs))
        let proof = ["v": exit.parkedEpisode!]
        // A Check the chain that reported the shortfall: no daemon cycle to wait for.
        #expect(ExitFunds.needsFunds(exit, confirmed: 310, packageFee: 1_215, proven: proof, parkedSince: nil, daemonSince: nil, now: Date()))
        #expect(!ExitFunds.needsFunds(exit, confirmed: 1_215, packageFee: 1_215, proven: proof, parkedSince: nil, daemonSince: nil, now: Date()))
    }
}
