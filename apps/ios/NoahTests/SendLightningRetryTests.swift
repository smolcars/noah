import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// Paying a failed BOLT11 invoice again: bark 0.8 reuses the hash's finished movement, flipping it from failed to
/// successful with its first fee field (async-payments.md, "Reused hashes"). The attempt is bound by bark's paid
/// record from before it began and by the movement finishing after it started, never by the old movement alone.
@MainActor struct SendLightningRetryTests {
    let payment: SendPayment
    let hash: String
    let quote = SendQuote(amount: 1000, fee: 79, total: 1079)
    let longAgo = Date().addingTimeInterval(-3600)

    init() throws {
        payment = try SendLightningSafetyTests().payment()
        hash = try #require(PaymentEncoding.invoice(payment.destination)?.hash)
    }

    struct Died: Error {}

    /// The hash's one movement, as bark keeps it across attempts.
    func reused(_ status: String, effective: Int64, completedAt: Date?, fee: UInt64 = 0,
                outputs: [String] = ["change:0", "refund:0"], amount: UInt64 = 1000) -> Movement {
        var movement = SendSafetyTests().movement("lightning", status: status, id: 5, amount: amount, hash: hash)
        movement.effectiveBalanceSats = effective
        movement.offchainFeeSats = fee
        movement.outputVtxoIds = outputs
        movement.completedAt = completedAt.map(HistoryRules.isoDate)
        return movement
    }

    /// WalletSession's Lightning path (prepareSend's snapshot, submitSend's guard and receipt, reconcileSend) over a
    /// scripted bark, since WalletSession itself needs the native wallet. `onPay` is what bark does once paying starts.
    @MainActor final class Bark {
        let hash: String
        var movement: Movement?
        var paid = false
        var checkpoint: LightningSend?
        var calls = 0
        var onPay: (Bark) throws -> Void = { _ in }
        let template: Movement

        init(hash: String, template: Movement) { self.hash = hash; self.template = template }

        var state: LightningSendStatus {
            if paid { return .paid(paymentHash: hash, preimage: "proof") }
            return checkpoint.map { .inProgress(send: $0) } ?? .unknown
        }
        var movements: [Movement] { movement.map { [$0] } ?? [] }

        func prepare() -> SendSnapshot { SendSnapshot(movementIDs: Set(movements.map(\.id)), invoicePaid: paid) }

        func pay(_ payment: SendPayment, _ quote: SendQuote, _ snapshot: SendSnapshot) async throws -> SendReceipt {
            let status = try await SendLightningSubmission.submit(
                payment: payment, state: state, movements: movements, checkpoint: snapshot.checkpoint, quote: quote,
                check: { [state] _ in state }) { @MainActor in
                    self.calls += 1
                    try self.onPay(self)
                    return self.state
                }
            guard case .paid(let paidHash, let preimage) = status, !preimage.isEmpty else { throw SendFailure.unresolved }
            switch SendReconciliation.lightningAttempt(
                movements,
                paying: .init(hash: paidHash, amount: payment.amount),
                snapshot: snapshot,
                startedAt: nil,
                fixed: SendReconciliation.fixesAmount(payment.destination)) {
            case .paid(let receipt): return receipt
            case .alreadyPaid(let receipt): throw SendAlreadyPaid(receipt: receipt)
            default: throw SendFailure.unresolved
            }
        }

        func reconcile(_ attempt: SendPaymentController.Attempt) -> SendPaymentController.Resolution {
            if case .inProgress(let send) = state { return .inProgress(.init(amount: send.amountSats, fee: send.feeSats)) }
            if let existing = attempt.existingLightning, existing.amount != attempt.payment.amount { return .inProgress(existing) }
            var tracked = attempt
            if attempt.existingLightning != nil { tracked.snapshot.movementIDs.formUnion(movements.map(\.id)) }
            return SendReconciliation.ark(tracked, movements: movements)
        }

        /// bark finishing this attempt a moment after it started, as a real payment does.
        func finish(_ status: String, effective: Int64) {
            var next = movement ?? template
            next.status = status
            next.effectiveBalanceSats = effective
            // bark's revocation records the fee it took: none.
            if status == "failed" { next.offchainFeeSats = 0 }
            next.completedAt = HistoryRules.isoDate(Date().addingTimeInterval(1))
            movement = next
            paid = paid || status == "successful"
        }
        func failsAgain() {
            onPay = { bark in
                bark.finish("failed", effective: 0)
                bark.movement?.outputVtxoIds.append("refund:\(bark.calls)")
            }
        }
        func pays() { onPay = { $0.finish("successful", effective: -1079) } }
    }

    func bark(_ movement: Movement? = nil) -> Bark {
        let bark = Bark(hash: hash, template: reused("pending", effective: -1079, completedAt: nil, fee: 79, outputs: []))
        bark.movement = movement
        return bark
    }

    func controller(_ bark: Bark, _ storage: SendPaymentTests.Storage) -> SendPaymentController {
        SendPaymentController(dependencies: .init(
            prepare: { _, _ in await bark.prepare() },
            pay: { payment, quote, snapshot in try await bark.pay(payment, quote, snapshot) },
            reconcile: { attempt in await bark.reconcile(attempt) },
            read: { storage.attempt }, write: { try storage.write($0) }))
    }

    func send(_ subject: SendPaymentController, _ paying: SendPayment? = nil) async throws {
        subject.submit(paying ?? payment, quote: quote)
        try await SendPaymentTests.waitUntil { subject.state != .submitting && !subject.checking }
    }

    func restart(_ bark: Bark, _ storage: SendPaymentTests.Storage) throws -> SendPaymentController {
        storage.attempt = try SendSafetyTests().roundTrip(#require(storage.attempt))
        return controller(bark, storage)
    }

    @Test func aRetryAfterOneFailurePaysWithItsRealFee() async throws {
        let bark = bark(reused("failed", effective: 0, completedAt: longAgo))
        bark.pays()
        let subject = controller(bark, .init())
        try await send(subject)
        guard case .succeeded(let receipt) = subject.state else { Issue.record("Expected Sent, got \(subject.state)"); return }
        #expect(subject.attempt?.alreadyPaid == nil)
        #expect(receipt.amount == 1000 && receipt.movementID == 5)
        // bark's numbers: 1,079 left the wallet for 1,000 sent, while the reused fee field still says 0.
        #expect(receipt.fee == 79)
        #expect(bark.movement?.offchainFeeSats == 0 && bark.movement?.effectiveBalanceSats == -1079)
        #expect(receipt.isProven(for: payment, knownHash: hash))
        #expect(bark.calls == 1)
    }

    @Test func aRetryThatFailsAgainIsAFailureWithoutTheRefundLine() async throws {
        let bark = bark(reused("failed", effective: 0, completedAt: longAgo))
        bark.failsAgain()
        let subject = controller(bark, .init())
        try await send(subject)
        #expect(subject.state == .failed)
        #expect(subject.attempt?.receipt == nil)
        // Its coins came back on the reused movement, which can't be tied to this attempt.
        let attempt = try #require(subject.attempt)
        #expect(SendRefundEvidence.refund(bark.movements, paymentHash: hash, before: attempt.snapshot.movementIDs) == .untied)
        #expect(SendMotion.outcome(state: subject.state, attempt: attempt, checking: false, refund: false) == .stopped)
    }

    @Test func twoFailuresThenSuccess() async throws {
        let bark = bark()
        let subject = controller(bark, .init())
        // The first attempt makes the movement and fails with a recorded refund.
        bark.onPay = { bark in
            bark.finish("failed", effective: 0)
            bark.movement?.outputVtxoIds = ["change:0", "refund:0"]
        }
        try await send(subject)
        #expect(subject.state == .failed)
        #expect(SendRefundEvidence.refund(bark.movements, paymentHash: hash, before: try #require(subject.attempt).snapshot.movementIDs) == .recorded)
        subject.acknowledge()
        #expect(!subject.locked)
        // The second reuses it and fails again.
        bark.failsAgain()
        try await send(subject)
        #expect(subject.state == .failed)
        #expect(SendRefundEvidence.refund(bark.movements, paymentHash: hash, before: try #require(subject.attempt).snapshot.movementIDs) == .untied)
        subject.acknowledge()
        #expect(!subject.locked)
        // The third pays.
        bark.pays()
        try await send(subject)
        guard case .succeeded(let receipt) = subject.state else { Issue.record("Expected Sent, got \(subject.state)"); return }
        #expect(receipt.fee == 79 && receipt.amount == 1000)
        #expect(bark.calls == 3)
    }

    @Test func anInvoicePaidBeforeThisAttemptIsStillAlreadyPaid() async throws {
        let fixed = SendPayment(destination: try SendLnurlSafetyTests().invoice(), method: "lightning", amount: 1000)
        let bark = bark(reused("successful", effective: -1083, completedAt: longAgo, fee: 83))
        bark.paid = true
        bark.onPay = { _ in Issue.record("Never pays twice") }
        let subject = controller(bark, .init())
        try await send(subject, fixed)
        guard case .alreadyPaid(let receipt) = subject.state else { Issue.record("Expected already paid, got \(subject.state)"); return }
        #expect(receipt.fee == 83 && receipt.amount == 1000)
        #expect(bark.calls == 0)
        // Bound by the paid record from before the attempt, even with the movement completing afterwards.
        let later = reused("successful", effective: -1083, completedAt: Date().addingTimeInterval(1), fee: 83)
        #expect(SendReconciliation.lightningAttempt(
            [later],
            paying: .init(hash: hash, amount: 1000),
            snapshot: .init(movementIDs: [5], invoicePaid: true),
            startedAt: longAgo,
            fixed: false) == .alreadyPaid(.init(
            amount: 0,
            paymentHash: hash,
            movementID: 5,
            proof: .actualMovement)))
    }

    @Test func aFixedAmountInvoicePaidByARetryIsAlreadyPaidWithTheRetrysFee() async throws {
        // Every attempt on an invoice with its own amount sent that amount, so the effective balance gives the fee.
        let fixed = SendPayment(destination: try SendLnurlSafetyTests().invoice(), method: "lightning", amount: 1000)
        #expect(PaymentEncoding.invoice(fixed.destination)?.hash == hash && SendReconciliation.fixesAmount(fixed.destination))
        let bark = bark(reused("failed", effective: 0, completedAt: longAgo))
        bark.pays()
        let subject = controller(bark, .init())
        try await send(subject, fixed)
        guard case .succeeded(let paid) = subject.state else { Issue.record("Expected Sent, got \(subject.state)"); return }
        #expect(paid.amount == 1000 && paid.fee == 79)
        subject.acknowledge()
        #expect(!subject.locked)
        // The same invoice again: the pre-pay guard blocks it, with the fee bark's effective balance took, not the fee field's 0.
        bark.onPay = { _ in Issue.record("Never pays twice") }
        try await send(subject, fixed)
        guard case .alreadyPaid(let receipt) = subject.state else { Issue.record("Expected already paid, got \(subject.state)"); return }
        #expect(receipt.amount == 1000 && receipt.fee == 79)
        #expect(bark.movement?.offchainFeeSats == 0)
        #expect(bark.calls == 1)
        // Reconciliation reads it the same way.
        guard case .alreadyPaid(let reconciled) = SendReconciliation.lightningAttempt(
            bark.movements,
            paying: .init(hash: hash, amount: 1000),
            snapshot: .init(movementIDs: [5], invoicePaid: true),
            startedAt: longAgo,
            fixed: true)
        else { Issue.record("Expected already paid"); return }
        #expect(reconciled.amount == 1000 && reconciled.fee == 79)
    }

    @Test func anAmountlessInvoicePaidOnceIsAlreadyPaidWithoutAmountOrFee() async throws {
        #expect(!SendReconciliation.fixesAmount(payment.destination))
        let bark = bark()
        bark.pays()
        let storage = SendPaymentTests.Storage()
        let subject = controller(bark, storage)
        try await send(subject)
        // The paying attempt has its own receipt.
        guard case .succeeded(let paid) = subject.state else { Issue.record("Expected Sent, got \(subject.state)"); return }
        #expect(paid.amount == 1000 && paid.fee == 79)
        subject.acknowledge()
        #expect(!subject.locked)
        // Submitted again: already paid, with nothing on bark's movement to prove which attempt's numbers it holds.
        bark.onPay = { _ in Issue.record("Never pays twice") }
        try await send(subject)
        guard case .alreadyPaid(let receipt) = subject.state else { Issue.record("Expected already paid, got \(subject.state)"); return }
        #expect(receipt.amount == 0 && receipt.fee == nil)
        #expect(bark.calls == 1)
        guard case .alreadyPaid(let reconciled) = SendReconciliation.lightningAttempt(
            bark.movements,
            paying: .init(hash: hash, amount: 1000),
            snapshot: .init(movementIDs: [5], invoicePaid: true),
            startedAt: longAgo,
            fixed: false)
        else { Issue.record("Expected already paid"); return }
        #expect(reconciled.amount == 0 && reconciled.fee == nil)
        // A restart keeps "already paid", and such a receipt never proves a payment of its own.
        let restarted = try restart(bark, storage)
        #expect(restarted.state == .alreadyPaid(receipt))
        #expect(!receipt.isProven(for: payment, knownHash: hash) && receipt.isProven(for: payment, knownHash: hash, alreadyPaid: true))
    }

    @Test(arguments: [(UInt64(1000), Int64(-2079)), (2000, -1079), (1000, -1000)])
    func anAmountlessInvoiceRetriedForAnotherAmountShowsNeitherAmountNorFee(first: UInt64, effective: Int64) async throws {
        // Failed at the first amount, paid at another plus 79: bark's reused movement keeps the first amount and fee field.
        // 1,000 then 921 + 79 leaves an effective balance whose fee agrees with the fee field's 0 by coincidence.
        let bark = bark(reused("successful", effective: effective, completedAt: longAgo, amount: first))
        bark.paid = true
        bark.onPay = { _ in Issue.record("Never pays twice") }
        let storage = SendPaymentTests.Storage()
        let subject = controller(bark, storage)
        // The pre-pay guard still blocks it as already paid, without an amount or fee it can't prove.
        try await send(subject)
        guard case .alreadyPaid(let receipt) = subject.state else { Issue.record("Expected already paid, got \(subject.state)"); return }
        #expect(receipt.amount == 0 && receipt.fee == nil)
        #expect(bark.calls == 0)
        // And after a restart.
        #expect(try restart(bark, storage).state == .alreadyPaid(receipt))
        // Reconciliation reads it the same way.
        guard case .alreadyPaid(let reconciled) = SendReconciliation.lightningAttempt(
            bark.movements,
            paying: .init(hash: hash, amount: 1000),
            snapshot: .init(movementIDs: [5], invoicePaid: true),
            startedAt: longAgo,
            fixed: false)
        else { Issue.record("Expected already paid"); return }
        #expect(reconciled.amount == 0 && reconciled.fee == nil)
        // Never this attempt's success: the movement's amount isn't the one it sent.
        let amount: UInt64 = first == 1000 ? 2000 : 1000
        #expect(SendReconciliation.lightningAttempt(
            bark.movements,
            paying: .init(hash: hash, amount: amount),
            snapshot: .init(movementIDs: [5], invoicePaid: false),
            startedAt: nil,
            fixed: false) == .uncertain)
    }

    @Test func aJournalFromBeforeThePaidFlagGoesByTheMovementsCompletion() async throws {
        let startedAt = Date().addingTimeInterval(-60)
        let current = SendPaymentController.Attempt(payment: payment, snapshot: .init(movementIDs: [5], invoicePaid: false),
                                                    startedAt: startedAt, paymentHash: hash, quote: quote)
        var json = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(current)) as? [String: Any])
        var snapshot = try #require(json["snapshot"] as? [String: Any])
        #expect(snapshot["invoicePaid"] as? Bool == false)
        snapshot["invoicePaid"] = nil
        json["snapshot"] = snapshot
        let old = try JSONDecoder().decode(SendPaymentController.Attempt.self, from: JSONSerialization.data(withJSONObject: json))
        #expect(old.snapshot.invoicePaid == nil)
        func resolve(_ movement: Movement) -> SendPaymentController.Resolution { SendReconciliation.ark(old, movements: [movement]) }
        // Completed after the attempt started: this attempt paid it, with the fee from the effective balance.
        guard case .paid(let paid) = resolve(reused("successful", effective: -1079, completedAt: startedAt.addingTimeInterval(5)))
        else { Issue.record("Expected Paid"); return }
        #expect(paid.fee == 79)
        // Completed before: paid before this attempt.
        guard case .alreadyPaid = resolve(reused("successful", effective: -1079, completedAt: startedAt.addingTimeInterval(-5)))
        else { Issue.record("Expected already paid"); return }
        // A failure counts only once the movement finished after the attempt started.
        #expect(resolve(reused("failed", effective: 0, completedAt: startedAt.addingTimeInterval(-5))) == .uncertain)
        #expect(resolve(reused("failed", effective: 0, completedAt: startedAt.addingTimeInterval(5))) == .failed)
        // No completion time to go by: nothing is assumed.
        #expect(resolve(reused("successful", effective: -1079, completedAt: nil)) == .uncertain)
        // Through the controller, after a restart.
        let storage = SendPaymentTests.Storage()
        storage.attempt = old
        let bark = bark(reused("successful", effective: -1079, completedAt: startedAt.addingTimeInterval(5)))
        let restarted = controller(bark, storage)
        #expect(restarted.state == .uncertain)
        await restarted.checkStatus()
        guard case .succeeded(let receipt) = restarted.state else { Issue.record("Expected Sent, got \(restarted.state)"); return }
        #expect(receipt.fee == 79 && storage.attempt?.receipt?.fee == 79)
    }

    @Test func aRestartMidRetryWhileTheOldMovementIsStillFailedStaysUnresolved() async throws {
        let bark = bark(reused("failed", effective: 0, completedAt: longAgo))
        let storage = SendPaymentTests.Storage()
        // The payment call dies before bark touches the movement: the journal keeps the attempt.
        bark.onPay = { _ in throw Died() }
        try await send(controller(bark, storage))
        let saved = try #require(storage.attempt)
        #expect(saved.receipt == nil && !saved.failed && saved.snapshot.invoicePaid == false)
        #expect(saved.snapshot.movementIDs.contains(5))

        let restarted = try restart(bark, storage)
        #expect(restarted.state == .uncertain)
        // Nothing yet: the old failure is not this attempt's.
        await restarted.checkStatus()
        #expect(restarted.state == .uncertain && storage.attempt?.failed == false)
        // bark resumes the retry: still paying, never "failed" or "already paid".
        bark.checkpoint = LightningSend(paymentHash: hash, htlcVtxoIds: [], movementId: 5, invoice: payment.destination,
                                        amountSats: 1000, feeSats: 79, htlcVtxoCount: 1, hasFailedRevocation: false)
        await restarted.checkStatus()
        #expect(restarted.state == .uncertain)
        #expect(storage.attempt?.existingLightning == .init(amount: 1000, fee: 79))
        #expect(SendMotion.outcome(state: restarted.state, attempt: restarted.attempt, checking: false, refund: nil) == .pending)
        // It pays: Sent, with this attempt's receipt and fee.
        bark.checkpoint = nil
        bark.finish("successful", effective: -1079)
        await restarted.checkStatus()
        guard case .succeeded(let receipt) = restarted.state else { Issue.record("Expected Sent, got \(restarted.state)"); return }
        #expect(storage.attempt?.alreadyPaid == nil)
        #expect(receipt.fee == 79)
        #expect(bark.calls == 1, "Checking status never pays")
    }

    @Test func aResumedRetryCheckpointShowsItsOwnFee() throws {
        let checkpoint = SendLightningCheckpoint(LightningSend(paymentHash: hash, htlcVtxoIds: [], movementId: 5, invoice: payment.destination,
                                                               amountSats: 1000, feeSats: 79, htlcVtxoCount: 1, hasFailedRevocation: false))
        let paid = LightningSendStatus.paid(paymentHash: hash, preimage: "proof")
        // The reused movement's fee field says 0; its effective balance took exactly this checkpoint's fee.
        #expect(try checkpoint.receipt(status: paid, movements: [reused("successful", effective: -1079, completedAt: longAgo)], payment: payment, quote: quote)
            .fee == 79)
        // The effective balance decides, whatever the fee field says.
        #expect(try checkpoint.receipt(
            status: paid,
            movements: [reused("successful", effective: -1079, completedAt: longAgo, fee: 79)],
            payment: payment,
            quote: quote).fee == 79)
        #expect(try checkpoint.receipt(status: paid, movements: [reused("successful", effective: -1000, completedAt: longAgo)], payment: payment, quote: quote)
            .fee == 0)
    }

    @Test func theReusedMovementMustMatchTheAmountAndShowARealFee() {
        let snapshot = SendSnapshot(movementIDs: [5], invoicePaid: false)
        // An amountless invoice retried for another amount: bark keeps the first attempt's destination amount.
        #expect(SendReconciliation.lightningAttempt(
            [reused("successful", effective: -2079, completedAt: longAgo)],
            paying: .init(hash: hash, amount: 2000),
            snapshot: snapshot,
            startedAt: nil,
            fixed: false) == .uncertain)
        // An effective balance that doesn't cover the amount can't give a fee.
        #expect(SendReconciliation.lightningAttempt(
            [reused("successful", effective: -900, completedAt: longAgo)],
            paying: .init(hash: hash, amount: 1000),
            snapshot: snapshot,
            startedAt: nil,
            fixed: false) == .uncertain)
        // A movement made by this attempt keeps today's reading.
        let fresh = reused("successful", effective: -1083, completedAt: longAgo, fee: 83)
        guard case .paid(let receipt) = SendReconciliation.lightningAttempt(
            [fresh],
            paying: .init(hash: hash, amount: 1000),
            snapshot: .init(movementIDs: [], invoicePaid: false),
            startedAt: nil,
            fixed: false)
        else { Issue.record("Expected Paid"); return }
        #expect(receipt.fee == 83)
        // A still-pending reused movement is nothing yet.
        #expect(SendReconciliation.lightningAttempt(
            [reused("pending", effective: -1079, completedAt: nil)],
            paying: .init(hash: hash, amount: 1000),
            snapshot: snapshot,
            startedAt: longAgo,
            fixed: false) == .uncertain)
        // Overflowing or positive effective balances fall back to the fee field.
        #expect(SendReconciliation.lightningFee(reused("successful", effective: .min, completedAt: nil), actual: 1000) == nil)
        #expect(SendReconciliation.lightningFee(reused("successful", effective: 5, completedAt: nil), actual: 1000) == nil)
    }
}
