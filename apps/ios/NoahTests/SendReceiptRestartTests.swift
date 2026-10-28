import Foundation
@testable import Noah
import Testing

@MainActor struct SendReceiptRestartTests {
    @Test(arguments: ["ark", "lightning", "lnurl", "offer", "onchain", "offboard"])
    func closeBeforeDoneThenRelaunchPreservesSuccessfulReceipt(rail: String) async throws {
        let storage = SendPaymentTests.Storage()
        let invoice = try SendLnurlSafetyTests().invoice()
        let hash = try #require(PaymentEncoding.invoice(invoice)?.hash)
        let onchain = ["onchain", "offboard"].contains(rail)
        let payment = SendPayment(destination: rail == "lightning" ? invoice : "reviewed-recipient",
                                  method: onchain ? "onchain" : rail, amount: 1000,
                                  source: rail == "onchain" ? .onchain : rail == "offboard" ? .offchain : nil,
                                  max: rail == "offboard")
        let receipt: SendReceipt
        if onchain {
            receipt = .init(amount: 1000, txid: "SDK-returned-txid", amountIsPending: false, status: .submitted,
                            fee: 100, proof: .submitReturnedTxid)
        } else if rail == "ark" {
            receipt = .init(amount: 1000, proof: .submitReturnedArkSuccess)
        } else {
            receipt = .init(amount: 1000, paymentHash: hash, movementID: 2, fee: 83, proof: .actualMovement)
        }
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: [], resolvedInvoice: rail == "lnurl" ? invoice : nil) },
            pay: { _, _, _ in receipt }, reconcile: { _ in Issue.record("No reconciliation needed for a saved success"); return .uncertain },
            read: { storage.attempt }, write: { try storage.write($0) }))
        subject.submit(payment, quote: .init(amount: 1000, fee: 100, total: 1100))
        try await SendPaymentTests.waitUntil { subject.state != .submitting }
        #expect(subject.state == .succeeded(receipt))
        #expect(subject.locked && !subject.canOverride)
        if rail == "lnurl" { #expect(subject.attempt?.paymentHash == hash) }
        // Close without Done: serialize the journal exactly as the next process reads it.
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        let restarted = SendSafetyTests().controller(storage) { _ in
            Issue.record("Restart must not need a history match for a proven SDK result")
            return .uncertain
        }
        #expect(restarted.state == .succeeded(receipt))
        #expect(restarted.attempt?.receipt == receipt && restarted.locked && !restarted.canOverride)
        restarted.submit(payment, quote: .init(amount: 1000, fee: 100, total: 1100))
        #expect(restarted.state == .succeeded(receipt))
        restarted.acknowledge()
        #expect(!restarted.locked && storage.attempt == nil)
    }
    @Test func arkSuccessProvenanceCannotProveOtherRailsOrAmounts() {
        let receipt = SendReceipt(amount: 1000, proof: .submitReturnedArkSuccess)
        for rail in ["lightning", "lnurl", "offer", "onchain"] {
            #expect(!receipt.isProven(for: .init(destination: "recipient", method: rail, amount: 1000)))
        }
        #expect(!receipt.isProven(for: .init(destination: "recipient", method: "ark", amount: 999)))
        #expect(!SendReceipt(amount: 1000, movementID: 2, proof: .actualMovement)
            .isProven(for: .init(destination: "recipient", method: "ark", amount: 1000)))
    }
}
