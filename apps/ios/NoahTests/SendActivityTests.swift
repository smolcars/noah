import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// What a Lightning payment's Live Activity may say: only what the send journal's state proves, and never an amount.
@MainActor struct SendActivityTests {
    let pending: SendLightningPendingTests
    let started = Date()

    init() throws { pending = try SendLightningPendingTests() }

    func attempt(_ payment: SendPayment? = nil, pending tracked: Bool = true, recurring: String? = nil) -> SendPaymentController.Attempt {
        var attempt = SendPaymentController.Attempt(
            recurringOccurrenceKey: recurring, payment: payment ?? pending.payment, snapshot: .init(movementIDs: []),
            pending: tracked ? SendLightningCheckpoint(pending.send()) : nil)
        attempt.startedAt = started
        return attempt
    }

    func phase(_ state: SendPaymentController.State, _ attempt: SendPaymentController.Attempt?, refunded: Bool = false,
               after seconds: Double = 1) -> SendActivityPhase? {
        SendActivityCenter.phase(state: state, attempt: attempt, refunded: refunded, now: started.addingTimeInterval(seconds))
    }

    @Test func aPaymentInFlightIsPayingThenStillPaying() {
        #expect(phase(.submitting, attempt(pending: false)) == .paying)
        #expect(phase(.uncertain, attempt()) == .paying)
        #expect(phase(.uncertain, attempt(), after: 60) == .stillPaying)
    }

    @Test func onlyProvenResultsAreShown() {
        let receipt = SendReceipt(amount: 1000, paymentHash: pending.hash, movementID: 5, fee: 79, proof: .actualMovement)
        #expect(phase(.succeeded(receipt), attempt(pending: false)) == .sent)
        #expect(phase(.failed, attempt(pending: false)) == .failed)
        #expect(phase(.failed, attempt(pending: false), refunded: true) == .refunded)
        // "May have been sent", an invoice paid before, unreadable storage, or nothing in flight: no activity.
        #expect(phase(.uncertain, attempt(pending: false)) == nil)
        #expect(phase(.alreadyPaid(receipt), attempt(pending: false)) == nil)
        #expect(phase(.storageBlocked, attempt()) == nil)
        #expect(phase(.ready, nil) == nil)
    }

    @Test func otherRailsAndRecurringPaymentsShowNothing() {
        let ark = SendPayment(destination: "ark1", method: "ark", amount: 1000)
        #expect(phase(.submitting, attempt(ark, pending: false)) == nil)
        #expect(phase(.submitting, attempt(pending: false, recurring: "weekly")) == nil)
    }

    @Test func aStaleActivityStopsClaimingThePaymentIsPaying() {
        #expect(SendActivityPhase.paying.line(locale: "en") == "Paying over Lightning")
        #expect(SendActivityPhase.paying.line(locale: "en", stale: true) == "Open Noah to check")
        #expect(SendActivityPhase.stillPaying.line(locale: "en", stale: true) == "Open Noah to check")
        // A result is a result however old.
        #expect(SendActivityPhase.sent.line(locale: "en", stale: true) == "Paid over Lightning.")
        #expect(SendActivityPhase.failed.line(locale: "en") == nil && SendActivityPhase.refunded.line(locale: "en") == "Back in your wallet")
        #expect(SendActivityPhase.sent.progress == 1 && SendActivityPhase.paying.progress == 0.5 && SendActivityPhase.failed.progress == 0)
    }
}
