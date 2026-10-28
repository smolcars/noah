import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

struct ReceiveCompletionTests {
    let request = ReceiveRequest(arkAddress: "ark-current", onchainAddress: "chain", amountSat: 500,
                                 lightningInvoice: "invoice", paymentHash: "current-hash")

    private func movement(subsystem: String = "bark.arkoor", status: String = "successful",
                          hash: String? = nil, destinations: [MovementDestination] = []) -> Movement {
        Movement(id: 1, status: status, subsystemName: subsystem, subsystemKind: "receive", metadataJson: "{}",
                 intendedBalanceSats: 500, effectiveBalanceSats: 500, offchainFeeSats: 0,
                 sentToAddresses: [], receivedOnAddresses: destinations, inputVtxoIds: [], outputVtxoIds: [],
                 exitedVtxoIds: [], createdAt: "", updatedAt: "", completedAt: nil,
                 paymentHash: hash, lightningInvoice: nil, lightningOffer: nil)
    }
    private func destination(_ address: String, _ sats: UInt64) -> MovementDestination {
        .init(paymentMethod: .init(methodType: "ark", value: address), amountSats: sats)
    }

    @Test func arkSumsOnlyCurrentDestination() {
        let event = movement(destinations: [destination("ark-current", 100), destination("other", 900), destination("ark-current", 200)])
        #expect(ReceiveCompletion.matching(event, request: request) == .received(300, rail: .ark))
        #expect(ReceiveCompletion.matching(movement(destinations: [destination("other", 500)]), request: request) == nil)
        #expect(ReceiveCompletion.matching(movement(status: "pending", destinations: [destination("ark-current", 500)]), request: request) == nil)
    }

    @Test func zeroDestinationAmountUsesRequestedAmountOnlyIfPresent() {
        let event = movement(destinations: [destination("ark-current", 0)])
        #expect(ReceiveCompletion.matching(event, request: request) == .received(500, rail: .ark))
        var amountless = request
        amountless.amountSat = nil
        #expect(ReceiveCompletion.matching(event, request: amountless) == nil)
    }

    @Test func lightningRequiresCurrentHashAndTerminalStatus() {
        for status in ["pending", "successful", "failed", "canceled"] {
            #expect(ReceiveCompletion.matching(movement(subsystem: "bark.lightning_receive", status: status, hash: "old"), request: request) == nil)
        }
        #expect(ReceiveCompletion.matching(movement(subsystem: "bark.lightning_receive", hash: "current-hash"), request: request) == .received(
            500,
            rail: .lightning))
        #expect(ReceiveCompletion
            .matching(movement(subsystem: "bark.lightning_receive", status: "failed", hash: "current-hash"), request: request) ==
            .lightningFailed(canceled: false))
        #expect(ReceiveCompletion
            .matching(movement(subsystem: "bark.lightning_receive", status: "canceled", hash: "current-hash"), request: request) ==
            .lightningFailed(canceled: true))
    }

    @Test func closedAndReservedSessionsRejectReceiveOperations() async throws {
        let session = WalletSession()
        await #expect(throws: WalletSession.SessionError.notOpen) { try await session.receiveRequest() }
        let reservation = try await session.reserve()
        await #expect(throws: WalletSession.SessionError.notOpen) { try await session.receiveRequest() }
        await #expect(throws: WalletSession.SessionError.notOpen) {
            try await session.receiveInvoice(for: request, amountSat: 500, description: "")
        }
        await #expect(throws: WalletSession.SessionError.notOpen) { try await session.receiveCompletions(for: request) }
        await session.release(reservation)
    }
}
