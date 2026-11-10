import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// Paying without waiting (async-payments.md): bark returns the send still in progress, the attempt tracks it from
/// the journal, and only bark's own records end it. Nothing here ever pays twice.
@MainActor struct SendLightningPendingTests {
    let retry: SendLightningRetryTests
    var payment: SendPayment { retry.payment }
    var hash: String { retry.hash }

    init() throws { retry = try SendLightningRetryTests() }

    func send(amount: UInt64 = 1000, movement: UInt32? = 5) -> LightningSend {
        LightningSend(paymentHash: hash, htlcVtxoIds: [], movementId: movement, invoice: payment.destination, amountSats: amount,
                      feeSats: 79, htlcVtxoCount: 1, hasFailedRevocation: false)
    }

    /// bark takes the payment and returns while the server is still paying it.
    func paying() -> SendLightningRetryTests.Bark {
        let bark = retry.bark()
        let send = send()
        bark.onPay = { bark in
            bark.movement = bark.template
            bark.checkpoint = send
        }
        return bark
    }

    @Test func aSendStillPayingIsTrackedThenSent() async throws {
        let bark = paying(), storage = SendPaymentTests.Storage()
        let subject = retry.controller(bark, storage)
        try await retry.send(subject)
        #expect(subject.state == .uncertain && subject.locked)
        #expect(subject.attempt?.pending == SendLightningCheckpoint(send()))
        #expect(subject.attempt?.existingLightning == nil)
        #expect(storage.attempt?.pending?.paymentHash == hash)
        // Still paying says nothing new.
        await subject.checkStatus()
        #expect(subject.state == .uncertain && subject.attempt?.pending != nil)

        bark.checkpoint = nil
        bark.finish("successful", effective: -1079)
        await subject.checkStatus()
        guard case .succeeded(let receipt) = subject.state else { Issue.record("Expected Sent, got \(subject.state)"); return }
        #expect(receipt.amount == 1000 && receipt.fee == 79 && receipt.movementID == 5)
        #expect(receipt.isProven(for: payment, knownHash: hash))
        #expect(subject.attempt?.pending == nil && storage.attempt?.pending == nil)
        #expect(bark.calls == 1)
    }

    @Test func aSendStillPayingThatFailsIsFailed() async throws {
        let bark = paying()
        let subject = retry.controller(bark, .init())
        try await retry.send(subject)
        bark.checkpoint = nil
        bark.finish("failed", effective: 0)
        await subject.checkStatus()
        #expect(subject.state == .failed && subject.attempt?.pending == nil)
        #expect(bark.calls == 1)
    }

    @Test func aPendingSendIsStillTrackedAfterARestart() async throws {
        let bark = paying(), storage = SendPaymentTests.Storage()
        try await retry.send(retry.controller(bark, storage))
        let restarted = try retry.restart(bark, storage)
        #expect(restarted.state == .uncertain && restarted.attempt?.pending?.paymentHash == hash)
        bark.checkpoint = nil
        bark.finish("successful", effective: -1079)
        await restarted.checkStatus()
        guard case .succeeded = restarted.state else { Issue.record("Expected Sent, got \(restarted.state)"); return }
        #expect(bark.calls == 1)
    }

    /// A send bark ended without a record this attempt can read is "may have been sent", never "still sending".
    @Test func aSendThatEndedWithoutProofIsUncertain() async throws {
        let bark = paying()
        let subject = retry.controller(bark, .init())
        try await retry.send(subject)
        bark.checkpoint = nil
        bark.movement = nil
        await subject.checkStatus()
        #expect(subject.state == .uncertain && subject.attempt?.pending == nil)
    }

    @Test func aSendForAnotherAmountIsShownNotTracked() {
        #expect(SendLightningSubmission.handedOver(send(amount: 2000), amount: 1000) is SendExistingLightning)
        #expect(SendLightningSubmission.handedOver(send(), amount: 1000) is SendLightningPending)

        let checkpoint = SendLightningCheckpoint(send())
        let attempt = SendPaymentController.Attempt(payment: payment, snapshot: .init(movementIDs: []), pending: checkpoint)
        for other in [send(amount: 2000), send(movement: 6)] {
            #expect(SendReconciliation.pending(attempt, checkpoint: checkpoint, state: .inProgress(send: other), movements: [])
                == .inProgress(.init(amount: other.amountSats, fee: 79)))
            #expect(!checkpoint.drives(.inProgress(send: other)))
        }
    }

    @Test func statusChecksDriveOnlyThisAttemptsSend() {
        let checkpoint = SendLightningCheckpoint(send())
        #expect(checkpoint.drives(.inProgress(send: send())))
        // A drive finishes a settlement that was interrupted after bark recorded the payment.
        #expect(checkpoint.drives(.paid(paymentHash: hash, preimage: "proof")))
        #expect(!checkpoint.drives(.unknown))
    }

    /// An offer's hash is known only from the send bark made for it.
    @Test func aPendingOfferLearnsItsHash() async throws {
        let offer = SendPayment(destination: "lno1offer", method: "offer", amount: 1000)
        let checkpoint = SendLightningCheckpoint(send())
        let receipt = SendReceipt(amount: 1000, paymentHash: hash, movementID: 5, fee: 79, proof: .actualMovement)
        let storage = SendPaymentTests.Storage()
        let subject = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: []) },
            pay: { _, _, _ in throw SendLightningPending(checkpoint: checkpoint) },
            reconcile: { _ in .paid(receipt) },
            read: { storage.attempt }, write: { try storage.write($0) }))
        try await retry.send(subject, offer)
        #expect(subject.state == .uncertain && subject.attempt?.pending == checkpoint)
        #expect(storage.attempt?.paymentHash == hash)
        await subject.checkStatus()
        #expect(subject.state == .succeeded(receipt))
    }

    /// The stage keeps "Sending" while a fresh payment is paying, then shows it pending; a saved one is pending at once.
    @Test func aFreshPendingSendStillLooksInFlight() {
        let started = Date()
        var attempt = SendPaymentController.Attempt(payment: payment, snapshot: .init(movementIDs: []), pending: .init(send()))
        attempt.startedAt = started
        func outcome(after seconds: Double) -> SendMotion.Outcome {
            SendMotion.outcome(state: .uncertain, attempt: attempt, checking: false, refund: nil, now: started.addingTimeInterval(seconds))
        }
        #expect(outcome(after: 1) == .inFlight)
        #expect(outcome(after: SendMotion.payingStart + SendMotion.stillPayingAfter + 1) == .pending)
        #expect(SendResultKind.of(state: .uncertain, outcome: .inFlight, slow: false) == .sending)
        #expect(SendResultKind.of(state: .uncertain, outcome: .inFlight, slow: true) == .stillSending)
        #expect(SendResultKind.of(state: .uncertain, outcome: .pending, slow: false) == .stillSending)
    }
}
