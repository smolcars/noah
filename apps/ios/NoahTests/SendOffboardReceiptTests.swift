import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

@MainActor struct SendOffboardReceiptTests {
    let fixtures = SendSafetyTests()
    func attempt() -> SendPaymentController.Attempt {
        var saved = fixtures.attempt("onchain", source: .offchain, max: true)
        saved.quote = .init(amount: 900, fee: 100, total: 1000, vtxoIDs: ["reviewed"])
        saved.receipt = .init(amount: 0, txid: "returned", amountIsPending: true, status: .submitted, proof: .submitReturnedTxid)
        return saved
    }
    func movement(txid: String = "returned", amount: UInt64 = 850) throws -> BarkBoltFFI.Movement {
        var movement = fixtures.movement("onchain", status: "pending", amount: amount)
        movement.subsystemKind = "offboard"
        movement.offchainFeeSats = 150
        movement.metadataJson = String(decoding: try JSONEncoder().encode([
            "offboard_txid": txid, "offboard_tx": try fixtures.transaction(amount: amount).txHex,
        ]), as: UTF8.self)
        return movement
    }
    @Test func reviewEstimatesOnlyRecipientAndFeeWithinExactReviewedTotal() throws {
        let quote = try #require(attempt().quote)
        #expect(quote.recipientIsEstimated)
        #expect(quote.recipientDescription == "Recipient receives about 900 sats")
        #expect(!quote.approximate && quote.total == 1000)
        #expect(SendQuote.recipientEstimateExplanation.contains("exactly the reviewed coins"))
        #expect(!SendQuote(amount: 900, fee: 100, total: 1000).recipientIsEstimated)
    }
    @Test func receiptUsesActualOutputAndFeeInsteadOfQuote() throws {
        let saved = attempt()
        let receipt = SendReconciliation.offboardReceipt(try #require(saved.receipt), payment: saved.payment, movements: [try movement()])
        #expect(receipt.amount == 850 && receipt.fee == 150)
        #expect(receipt.amount != saved.quote?.amount && receipt.fee != saved.quote?.fee)
        #expect(receipt.amountIsPending == false && receipt.amountDescription == "850 sats")
        #expect(receipt.isSubmitted && receipt.isProven(for: saved.payment))
    }
    @Test func absentWrongOrAmbiguousEvidenceKeepsActualsPending() throws {
        let saved = attempt()
        let receipt = try #require(saved.receipt)
        var malformed = try movement()
        malformed.metadataJson = "{}"
        for history in [[], [try movement(txid: "other")], [malformed], [try movement(), try movement()]] {
            let result = SendReconciliation.offboardReceipt(receipt, payment: saved.payment, movements: history)
            #expect(result == receipt && result.amountDescription == "Recipient amount pending")
            #expect(result.fee == nil && result.isProven(for: saved.payment))
        }
    }
    @Test func refreshAndRestartPreservePendingThenActualReceipt() async throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = try fixtures.roundTrip(attempt())
        let actual = try movement()
        let pending = fixtures.controller(storage) { saved in
            .paid(SendReconciliation.offboardReceipt(saved.receipt!, payment: saved.payment, movements: []))
        }
        await pending.checkStatus()
        #expect(pending.attempt?.receipt?.amountIsPending == true)
        #expect(pending.locked && !pending.canOverride)
        storage.attempt = try fixtures.roundTrip(#require(storage.attempt))
        let refreshed = fixtures.controller(storage) { saved in
            .paid(SendReconciliation.offboardReceipt(saved.receipt!, payment: saved.payment, movements: [actual]))
        }
        await refreshed.checkStatus()
        #expect(refreshed.attempt?.receipt?.amount == 850 && refreshed.attempt?.receipt?.fee == 150)
        storage.attempt = try fixtures.roundTrip(#require(storage.attempt))
        let restarted = fixtures.controller(storage) { _ in .uncertain }
        #expect(restarted.state == refreshed.state && restarted.attempt?.receipt?.amountIsPending == false)
    }
    @Test func olderQuoteBasedMaxReceiptReopensWithPendingAmountsAndSameTxid() throws {
        var saved = attempt()
        saved.receipt?.amount = 900
        saved.receipt?.amountIsPending = nil
        let storage = SendPaymentTests.Storage()
        storage.attempt = try fixtures.roundTrip(saved)
        let restarted = fixtures.controller(storage) { _ in .uncertain }
        #expect(restarted.attempt?.receipt?.amountIsPending == true && restarted.attempt?.receipt?.amount == 0)
        #expect(restarted.attempt?.receipt?.txid == "returned")
        #expect(restarted.locked && !restarted.canOverride)
        #expect(storage.attempt?.receipt?.amountIsPending == true)
    }
}
