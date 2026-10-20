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
