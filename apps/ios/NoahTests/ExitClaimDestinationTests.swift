import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

struct ExitClaimDestinationTests {
    @Test func validatesNetworkAndChecksOwnershipWithoutRejectingColdStorage() async throws {
        let mainnet = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
        for mine in [true, false] {
            let result = try await WalletSession.claimDestinationOwnership(mainnet, network: "mainnet") { address in
                #expect(address == mainnet)
                return mine
            }
            #expect(result == mine)
        }
        for address in [mainnet, "invalid", "bitcoin:\(mainnet)", "tb1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0l98cr"] {
            await #expect(throws: OnchainFailure.self) {
                try await WalletSession.claimDestinationOwnership(address, network: "regtest") { _ in
                    Issue.record("Invalid destination must not reach the wallet")
                    return false
                }
            }
        }
        #expect(try await WalletSession.claimDestinationOwnership(
            "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202", network: "regtest") { _ in false } == false)
        #expect(try await WalletSession.claimDestinationOwnership(
            "tb1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq0l98cr", network: "signet") { _ in false } == false)
        #expect(try await WalletSession.claimDestinationOwnership(
            "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn", network: "regtest") { _ in false } == false)
    }

    @Test func quotePinsOwnershipAndWarnsOnlyForExternalClaims() throws {
        var own = OnchainJournalTests.quote
        own.action = .claim
        own.destination = "wallet-address"
        own.destinationIsMine = true
        #expect(own.externalDestinationWarning == nil)
        var external = own
        external.destinationIsMine = false
        #expect(!own.matches(external))
        #expect(external.externalDestinationWarning == "This address isn't in your Noah wallet. The claimed funds will be sent there.")
        external.destination = "cold-storage"
        #expect(!own.matches(external))
        let decoded = try JSONDecoder().decode(OnchainQuote.self, from: JSONEncoder().encode(external))
        #expect(decoded.destinationIsMine == false)
        #expect(decoded.matches(external))
        external.destinationIsMine = nil
        #expect(!own.matches(external))
    }
}

@MainActor
struct ExitClaimDestinationModelTests {
    actor Ownership {
        var pending: CheckedContinuation<Bool, Never>?
        var entered = false
        func check(_ address: String) async -> Bool {
            if address == "cold-storage" {
                entered = true
                return await withCheckedContinuation { pending = $0 }
            }
            return true
        }
        func finish() { pending?.resume(returning: false); pending = nil }
    }

    @Test func prefillsRestoresAndIgnoresLateOwnership() async {
        let ownership = Ownership()
        var deps = OnchainModel.Dependencies(
            ready: { true }, overview: { throw OnchainFailure.unavailable },
            quote: { _, _, _, _ in throw OnchainFailure.unavailable },
            execute: { _ in Issue.record("Must not submit"); return "" }, attempt: { [] }, acknowledge: { _ in })
        deps.claimAddress = { "fresh-wallet-address" }
        deps.claimOwnership = { await ownership.check($0) }
        let model = OnchainModel(dependencies: deps)
        await model.useWalletClaimAddress()
        #expect(model.claimDestination == "fresh-wallet-address")
        await model.checkClaimDestination()
        #expect(model.claimDestinationIsMine == true)
        model.editClaimDestination("cold-storage")
        let pending = Task { await model.checkClaimDestination() }
        while await !ownership.entered { await Task.yield() }
        await model.useWalletClaimAddress()
        await ownership.finish()
        await pending.value
        #expect(model.claimDestination == "fresh-wallet-address")
        #expect(model.claimDestinationIsMine == nil)
        await model.checkClaimDestination()
        #expect(model.claimDestinationIsMine == true)
    }

    actor AddressFetch {
        var pending: CheckedContinuation<String, any Error>?
        var entered = false
        var calls = 0
        let deferred: Bool
        init(deferred: Bool = false) { self.deferred = deferred }
        func fetch() async throws -> String {
            calls += 1
            if deferred {
                entered = true
                return try await withCheckedThrowingContinuation { pending = $0 }
            }
            if calls <= 2 { throw OnchainFailure.unavailable }
            return "fresh-wallet-address"
        }
        func finish(fails: Bool) {
            if fails { pending?.resume(throwing: OnchainFailure.unavailable) }
            else { pending?.resume(returning: "fresh-wallet-address") }
            pending = nil
        }
    }

    func dependencies(_ fetch: AddressFetch) -> OnchainModel.Dependencies {
        var deps = OnchainModel.Dependencies(
            ready: { true }, overview: { throw OnchainFailure.unavailable },
            quote: { action, ids, _, destination in
                guard let destination, !destination.isEmpty else { throw OnchainFailure.invalid }
                var quote = OnchainJournalTests.quote
                quote.action = action; quote.ids = ids; quote.destination = destination
                return quote
            }, execute: { _ in Issue.record("Must not submit"); return "" },
            attempt: { [] }, acknowledge: { _ in })
        deps.claimAddress = { try await fetch.fetch() }
        return deps
    }

    @Test(.timeLimit(.minutes(1))) func delayedClaimAddressDoesNotOverwriteUserEdit() async {
        for fails in [false, true] {
            let fetch = AddressFetch(deferred: true)
            let model = OnchainModel(dependencies: dependencies(fetch))
            let pending = Task { await model.useWalletClaimAddress() }
            while await !fetch.entered { await Task.yield() }
            model.editClaimDestination("cold-storage")
            await fetch.finish(fails: fails)
            await pending.value
            #expect(model.claimDestination == "cold-storage")
            #expect((model.claimDestinationError != nil) == fails)
            #expect(model.quote == nil)
        }
    }

    @Test func failedClaimAddressPreservesInputBlocksEmptyReviewAndRetries() async {
        for typed in ["", "cold-storage"] {
            let fetch = AddressFetch()
            let model = OnchainModel(dependencies: dependencies(fetch))
            model.editClaimDestination(typed)
            for _ in 0..<2 {
                await model.useWalletClaimAddress()
                #expect(model.claimDestination == typed)
                #expect(model.claimDestinationError == "Couldn't load a Noah wallet address. Try again when the wallet is ready.")
                #expect(model.quote == nil)
                await model.confirm()
            }
            if typed.isEmpty {
                await model.review(.claim, ids: ["a"], destination: model.claimDestination)
                #expect(model.quote == nil)
                #expect(model.error != nil)
                await model.confirm()
            }
            await model.useWalletClaimAddress()
            #expect(await fetch.calls == 3)
            #expect(model.claimDestination == "fresh-wallet-address")
            #expect(model.claimDestinationError == nil)
        }
    }

    @Test func unavailableWalletDoesNotGenerateOrValidateDestination() async {
        var deps = OnchainModel.Dependencies(
            ready: { false }, overview: { throw OnchainFailure.unavailable },
            quote: { _, _, _, _ in throw OnchainFailure.unavailable }, execute: { _ in "" },
            attempt: { [] }, acknowledge: { _ in })
        deps.claimAddress = { Issue.record("Must not generate"); return "" }
        deps.claimOwnership = { _ in Issue.record("Must not query"); return true }
        let model = OnchainModel(dependencies: deps)
        await model.useWalletClaimAddress()
        #expect(model.claimDestinationError != nil)
        model.editClaimDestination("cold-storage")
        await model.checkClaimDestination()
        #expect(model.claimDestinationIsMine == nil)
        #expect(model.claimDestinationError != nil)
    }
}

struct ExitClaimExecutionTests {
    static let ownAddress = "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202"
    static let externalAddress = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"
    /// A real signed claim (regtest, noah-bark08 block 1,276), so its txid can be computed before broadcast.
    static let claimTransaction = "020000000001014e0022621458774a9eeedc9448622587e1bb35bb5245b98011eb9a0d39c2140a00000000009000000001b8cb010000000000160014501b11c7bd0b5b441e3c84165c028717c7aa35c003400711f56eaa28ae510b667d56393cf8d060a1f479c15af7ee485f89606f839fb0510c7cc0483668731fe0ce5173d37bbfb53c2fe83c5e0f7728777137278fd0d427029000b27520b66df7ecec3bcd274058b2989abf533f8857cdc03f4f5b69309b6436b95b50ecac21c0c477c505b62f93b1837ce023fb42ccb7f3b0c09cdb98e365e69a8e8e01293b8afb040000"
    static let claimTxid = "ba2d58faec28bc2f17f6b9faae7947c6ae92318b0390f62fbf3d59134d158fc7"

    actor Wallet {
        var estimateCalls = 0
        var estimatorFails = false
        var mine = true
        var ownershipError = false
        var ownershipChecks: [String] = []
        var drainDestinations: [String] = []
        var broadcasts: [String] = []
        func estimate() throws -> EmergencyExitFeeEstimate {
            estimateCalls += 1
            if estimatorFails { throw OnchainFailure.unavailable }
            return .init(exitBroadcastFeeSats: 7630, claimFeeSats: 1295, totalFeeSats: 8925, feeRateSatPerVb: 10, txsToBroadcast: 2, excludedVtxos: [])
        }
        func change(_ change: String) {
            switch change {
            case "own-external", "external-own": mine.toggle()
            case "throws": ownershipError = true
            case "estimator": estimatorFails = true
            default: break
            }
        }
        func isMine(_ address: String) throws -> Bool {
            ownershipChecks.append(address)
            if ownershipError { throw OnchainFailure.unavailable }
            return mine
        }
        func drain(_ ids: [String], _ address: String) -> ExitClaimTransaction {
            #expect(ids == ["a"])
            drainDestinations.append(address)
            return .init(psbtBase64: "claim-psbt", feeSats: 1295)
        }
        func broadcast(_ psbt: String) -> String {
            broadcasts.append(psbt)
            return ExitClaimExecutionTests.claimTxid
        }
        nonisolated func access() -> WalletSession.OnchainClaimAccess {
            .init(walletID: "wallet", network: "regtest", confirmed: { 100_000 }, exits: {
                [.init(vtxoId: "a", amountSats: 30_000,
                    state: .claimable(tipHeight: 100, claimableSince: .init(height: 100, hash: "hash"), lastScannedBlock: nil),
                    isClaimable: true)]
            }, isMine: { try await self.isMine($0) }, estimate: { _, _ in
                try await self.estimate()
            }, drain: { await self.drain($0, $1) }, transaction: { _ in ExitClaimExecutionTests.claimTransaction }, broadcast: { await self.broadcast($0) })
        }
    }

    @Test func unknownFeeClaimRequiresConsentExpiresAndNeverRetriesEstimation() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let wallet = Wallet()
        await wallet.change("estimator")
        let unknown: OnchainQuote
        do {
            _ = try await WalletSession.claimQuote(ids: ["a"], destination: Self.externalAddress, access: wallet.access())
            Issue.record("Estimator must fail"); return
        } catch let failure as ExitEstimateUnavailable { unknown = failure.review }
        #expect(unknown.fee == nil && unknown.chainFee == nil && unknown.feeRate == nil)
        await #expect(throws: OnchainFailure.self) { try await WalletSession.executeClaim(unknown, journal: journal, access: wallet.access()) }
        var reviewed = unknown
        reviewed.unknownFeeConsent = reviewed.id
        for change in ["expired", "ids", "wallet", "ownership"] {
            var invalid = reviewed
            if change == "expired" { invalid.createdAt = .distantPast }
            if change == "ids" { invalid.ids = ["missing"] }
            if change == "wallet" { invalid.walletID = "other" }
            if change == "ownership" { invalid.destinationIsMine = false }
            await #expect(throws: OnchainFailure.self) { try await WalletSession.executeClaim(invalid, journal: journal, access: wallet.access()) }
        }
        #expect(try await journal.loadAll().isEmpty)
        _ = try await WalletSession.executeClaim(reviewed, journal: journal, access: wallet.access())
        #expect(await wallet.estimateCalls == 1)
        #expect(await wallet.broadcasts == [Self.claimTransaction])
        let saved = try #require(try await OnchainOperationJournal(file: file).load())
        #expect(saved.quote.hasUnknownFeeConsent && saved.quote.ids == ["a"])
        #expect(saved.quote.fee == nil && saved.quote.chainFee == nil)
    }

    @Test func otherClaimErrorsDoNotCarryFallback() async throws {
        let wallet = Wallet()
        await wallet.change("estimator")
        await wallet.change("throws")
        do {
            _ = try await WalletSession.claimQuote(ids: ["a"], destination: Self.ownAddress, access: wallet.access())
            Issue.record("Ownership must fail")
        } catch { #expect(!(error is ExitEstimateUnavailable)) }
        #expect(await wallet.estimateCalls == 0)
    }

    @Test(arguments: BarkErrorCode.allCases) func estimatorCallClassifiesTypedErrorsAfterPrechecks(code: BarkErrorCode) async throws {
        let wallet = Wallet()
        var access = wallet.access()
        access.estimate = { _, _ in throw BarkError(message: "untrusted diagnostic", code: code) }
        var validated = OnchainJournalTests.quote
        validated.action = .startExit
        for action in [OnchainAction.startExit, .claim] {
            do {
                if action == .claim {
                    _ = try await WalletSession.claimQuote(ids: ["a"], destination: Self.externalAddress, access: access)
                } else {
                    _ = try await WalletSession.exitStartEstimate(validated) { throw BarkError(message: "untrusted diagnostic", code: code) }
                }
                Issue.record("Estimator must fail")
            } catch {
                // FFI 0.7.0: transport (Other), fee-rate overflow and chain-source failures fall back.
                let fallback = ([.other, .invalidFeeRate] + ExitTypedErrorTests.chain).contains(code)
                #expect((error is ExitEstimateUnavailable) == fallback)
                if !fallback { #expect((error as? BarkError)?.code == code) }
            }
        }
        #expect(await wallet.ownershipChecks == [Self.externalAddress])
        #expect(await wallet.drainDestinations.isEmpty)
        #expect(await wallet.broadcasts.isEmpty)
    }

    @Test func unavailableEstimatorAfterPrechecksOffersFallback() async throws {
        let wallet = Wallet()
        var access = wallet.access()
        access.estimate = { _, _ in throw URLError(.timedOut) }
        await #expect(throws: ExitEstimateUnavailable.self) {
            try await WalletSession.claimQuote(ids: ["a"], destination: Self.externalAddress, access: access)
        }
        #expect(await wallet.ownershipChecks == [Self.externalAddress])
        #expect(!ExitEstimateUnavailable.allowsFallback(URLError(.badURL)))
        #expect(!ExitEstimateUnavailable.allowsFallback(CancellationError()))
    }

    @Test func confirmationEstimatorFailureKeepsClaimReviewIdentityAndDeadline() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let wallet = Wallet()
        var reviewed = try await WalletSession.claimQuote(ids: ["a"], destination: Self.externalAddress, access: wallet.access())
        reviewed.createdAt = Date().addingTimeInterval(-29)
        await wallet.change("estimator")
        do {
            _ = try await WalletSession.executeClaim(reviewed, journal: journal, access: wallet.access())
            Issue.record("Estimator must fail")
        } catch let failure as ExitEstimateUnavailable {
            #expect(failure.review.id == reviewed.id && failure.review.createdAt == reviewed.createdAt)
            #expect(failure.review.inputs.matches(reviewed.inputs))
            #expect(!ExitRules.current(reviewedAt: failure.review.createdAt, now: reviewed.createdAt.addingTimeInterval(30)))
        }
        #expect(try await journal.loadAll().isEmpty)
        #expect(await wallet.drainDestinations.isEmpty)
    }

    @Test func startEstimatorFailureHasNoFeesAndSuccessKeepsNormalEstimate() async throws {
        var validated = OnchainJournalTests.quote
        validated.action = .startExit; validated.ids = ["a"]
        do {
            _ = try await WalletSession.exitStartEstimate(validated) { throw OnchainFailure.unavailable }
            Issue.record("Estimator must fail")
        } catch let failure as ExitEstimateUnavailable {
            #expect(failure.review.feesUnknown)
            #expect(failure.review.fee == nil && failure.review.chainFee == nil && failure.review.feeRate == nil)
            #expect(failure.review.ids == ["a"] && !failure.review.hasUnknownFeeConsent)
        }
        let normal = try await WalletSession.exitStartEstimate(validated) { try await Wallet().estimate() }
        #expect(normal.fee == 1295 && normal.chainFee == 7630 && !normal.feesUnknown)
        let skipped = try await WalletSession.exitStartEstimate(validated, estimate: false) {
            Issue.record("Explicit unknown-fee confirmation must not retry"); throw OnchainFailure.unavailable
        }
        #expect(skipped.feesUnknown)
    }

    @Test func reviewedDestinationIsDrainedAndJournaledForOwnAndExternalClaims() async throws {
        for mine in [true, false] {
            let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: file) }
            let journal = OnchainOperationJournal(file: file)
            let wallet = Wallet()
            if !mine { await wallet.change("own-external") }
            let destination = mine ? Self.ownAddress : Self.externalAddress
            let reviewed = try await WalletSession.claimQuote(ids: ["a"], destination: destination, access: wallet.access())
            #expect(reviewed.destinationIsMine == mine)
            #expect((reviewed.externalDestinationWarning == nil) == mine)
            _ = try await WalletSession.executeClaim(reviewed, journal: journal, access: wallet.access())
            #expect(await wallet.ownershipChecks == [destination, destination])
            #expect(await wallet.drainDestinations == [reviewed.destination!])
            #expect(await wallet.broadcasts == [Self.claimTransaction])
            let saved = try #require(try await OnchainOperationJournal(file: file).load())
            #expect(saved.quote.destination == (await wallet.drainDestinations).first)
            #expect(saved.receipt?.txid == Self.claimTxid)
        }
    }

    @Test func executionOwnershipChangesAndFailuresPreventClaimConstructionAndSubmission() async throws {
        for change in ["own-external", "external-own", "throws", "invalid", "wrong-network"] {
            let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: file) }
            let journal = OnchainOperationJournal(file: file)
            let wallet = Wallet()
            if change == "external-own" { await wallet.change("own-external") }
            var reviewed = try await WalletSession.claimQuote(ids: ["a"], destination: Self.ownAddress, access: wallet.access())
            await wallet.change(change)
            if change == "invalid" { reviewed.destination = "invalid" }
            if change == "wrong-network" { reviewed.destination = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT" }
            let access = wallet.access()
            await #expect(throws: OnchainFailure.self) {
                _ = try await WalletSession.executeClaim(reviewed, journal: journal, access: access)
            }
            #expect(await wallet.ownershipChecks.count == (["invalid", "wrong-network"].contains(change) ? 1 : 2))
            #expect(await wallet.drainDestinations.isEmpty)
            #expect(await wallet.broadcasts.isEmpty)
            #expect(try await journal.loadAll().isEmpty)
        }
    }

    @Test func emptyDestinationCannotBeReviewedOrExecuted() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let journal = OnchainOperationJournal(file: file)
        let wallet = Wallet()
        var reviewed = try await WalletSession.claimQuote(ids: ["a"], destination: Self.ownAddress, access: wallet.access())
        for destination in ["", "  "] {
            await #expect(throws: OnchainFailure.self) {
                try await WalletSession.claimQuote(ids: ["a"], destination: destination, access: wallet.access())
            }
            reviewed.destination = destination
            await #expect(throws: OnchainFailure.self) {
                try await WalletSession.executeClaim(reviewed, journal: journal, access: wallet.access())
            }
        }
        #expect(await wallet.drainDestinations.isEmpty)
        #expect(await wallet.broadcasts.isEmpty)
        #expect(try await journal.loadAll().isEmpty)
    }
}
