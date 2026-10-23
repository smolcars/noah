import BarkBoltFFI
import Foundation

/// Persisted before checkLightningPayment; it can only track this reviewed checkpoint, never create a payment.
struct SendLightningCheckpoint: Codable, Equatable, Sendable {
    var paymentHash: String
    var invoice: String
    var amount: UInt64
    var fee: UInt64
    var movementID: UInt32?
    var hasFailedRevocation: Bool

    init(_ send: LightningSend) {
        paymentHash = send.paymentHash
        invoice = SendDestination.cleaned(send.invoice).lowercased()
        amount = send.amountSats
        fee = send.feeSats
        movementID = send.movementId
        hasFailedRevocation = send.hasFailedRevocation
    }

    static func prepared(reviewed: SendQuote, inspected: LightningSendStatus) -> Self? {
        // Once observed in review, disappearance can never turn tracking into a fresh payment.
        if let checkpoint = reviewed.checkpoint { return checkpoint }
        if case .inProgress(let send) = inspected { return Self(send) }
        return nil
    }

    func matches(invoice reviewedInvoice: String, payment: SendPayment, quote: SendQuote) -> Bool {
        let (total, overflow) = amount.addingReportingOverflow(fee)
        let (_, reviewedOverflow) = quote.amount.addingReportingOverflow(quote.fee)
        return amount > 0 && !hasFailedRevocation && !overflow && !reviewedOverflow
            && invoice == SendDestination.cleaned(reviewedInvoice).lowercased()
            && paymentHash == PaymentEncoding.invoice(reviewedInvoice)?.hash
            && amount == payment.amount && amount == quote.amount && fee <= quote.fee && total <= quote.total
    }

    func advance(
        invoice: String, payment: SendPayment, quote: SendQuote, state: LightningSendStatus,
        check: @Sendable (String) async throws -> LightningSendStatus
    ) async throws -> LightningSendStatus {
        guard matches(invoice: invoice, payment: payment, quote: quote) else {
            throw SendExistingLightning(progress: .init(amount: amount, fee: fee))
        }
        switch state {
        case .inProgress(let send):
            let current = Self(send)
            guard current.matches(invoice: invoice, payment: payment, quote: quote),
                  movementID == nil || current.movementID == movementID else {
                throw SendExistingLightning(progress: .init(amount: send.amountSats, fee: send.feeSats))
            }
        case .paid(let hash, let preimage):
            guard hash == paymentHash, !preimage.isEmpty else { throw SendFailure.unresolved }
        case .unknown: throw SendFailure.unresolved
        }
        // Never call payLightningInvoice here: a vanished checkpoint must not create a new payment.
        return try await check(paymentHash)
    }

    func receipt(status: LightningSendStatus, movements: [Movement], payment: SendPayment, quote: SendQuote) throws -> SendReceipt {
        guard case .paid(let hash, let preimage) = status, hash == paymentHash, !preimage.isEmpty else {
            throw SendFailure.unresolved
        }
        let matching = movements.filter { $0.paymentHash == hash && $0.subsystemName == "bark.lightning_send" && $0.subsystemKind == "send" }
        guard case .paid(let receipt) = SendReconciliation.lightning(
            matching, hash: hash, amount: payment.amount, snapshot: .init(movementIDs: []), fixed: SendReconciliation.fixesAmount(invoice)),
              receipt.isProven(for: payment, knownHash: hash), let fee = receipt.fee else { throw SendFailure.unresolved }
        let (total, overflow) = receipt.amount.addingReportingOverflow(fee)
        guard !overflow, fee <= quote.fee, total <= quote.total else { throw SendFailure.unresolved }
        return receipt
    }
}
