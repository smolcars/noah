import BarkBoltFFI
import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Noah

/// The Send motion follows the payment controller's real state (send-receive.md).
@MainActor
struct SendMotionTests {
    typealias Attempt = SendPaymentController.Attempt
    static let lightning = SendPayment(destination: SendReceiveShotTests.invoice, method: "lightning", amount: 1_500)
    static let ark = SendPayment(destination: SendReceiveShotTests.ark, method: "ark", amount: 2_500)

    static func attempt(_ payment: SendPayment, failureMessage: String? = nil, existing: SendLightningProgress? = nil,
                        receipt: SendReceipt? = nil) -> Attempt {
        var attempt = Attempt(payment: payment, snapshot: .init(movementIDs: []))
        attempt.failureMessage = failureMessage
        attempt.existingLightning = existing
        attempt.receipt = receipt
        attempt.paymentHash = PaymentEncoding.invoice(payment.destination)?.hash
        return attempt
    }

    /// Every phase the stage shows over a minute, sampled every 50 ms.
    static func phases(_ rail: SendMotion.Rail, _ outcome: SendMotion.Outcome, outcomeAt: Double, confirmedAt: Double? = nil) -> [SendMotion.Phase] {
        stride(from: 0.0, through: 60, by: 0.05).map {
            SendMotion.phase(rail: rail, outcome: outcome, outcomeAt: outcomeAt, confirmedAt: confirmedAt, at: $0)
        }
    }

    @Test func outcomesComeOnlyFromTheControllersState() {
        let refundable = Self.attempt(Self.lightning)
        #expect(SendMotion.outcome(state: .submitting, attempt: refundable, checking: false, refund: nil) == .inFlight)
        // A failed Lightning send holds until its refund evidence is read, then shows it only if recorded.
        #expect(SendMotion.outcome(state: .failed, attempt: refundable, checking: false, refund: nil) == .inFlight)
        #expect(SendMotion.outcome(state: .failed, attempt: refundable, checking: false, refund: true) == .refunded)
        #expect(SendMotion.outcome(state: .failed, attempt: refundable, checking: false, refund: false) == .stopped)
        // Nothing was submitted: no refund to show.
        let notSubmitted = Self.attempt(Self.lightning, failureMessage: "The available funds changed. Review the payment again.")
        #expect(SendMotion.outcome(state: .failed, attempt: notSubmitted, checking: false, refund: true) == .stopped)
        #expect(SendMotion.outcome(state: .failed, attempt: Self.attempt(Self.ark), checking: false, refund: true) == .stopped)
        // Unknown outcomes keep today's handling.
        #expect(SendMotion.outcome(state: .uncertain, attempt: refundable, checking: false, refund: nil) == .stopped)
        #expect(SendMotion.outcome(state: .uncertain, attempt: refundable, checking: true, refund: nil) == .inFlight)
        #expect(SendMotion.outcome(state: .uncertain, attempt: Self.attempt(Self.lightning, receipt: .init(amount: 1, txid: "a")),
                                   checking: true, refund: nil) == .stopped)
        #expect(SendMotion.outcome(state: .uncertain, attempt: Self.attempt(Self.lightning, existing: .init(amount: 1_500, fee: 3)),
                                   checking: false, refund: nil) == .pending)
        #expect(SendMotion.outcome(state: .storageBlocked, attempt: nil, checking: false, refund: nil) == .stopped)
        #expect(SendMotion.outcome(state: .alreadyPaid(.init(amount: 1_500)), attempt: refundable, checking: false, refund: nil) == .stopped)
        var awaiting = SendReceipt(amount: 1, txid: "a")
        awaiting.broadcastRetryPending = true
        #expect(SendMotion.outcome(state: .succeeded(awaiting), attempt: refundable, checking: false, refund: nil) == .stopped)
        #expect(SendMotion.outcome(state: .succeeded(.init(amount: 1)), attempt: refundable, checking: false, refund: nil) == .succeeded)
    }

    @Test(arguments: [SendMotion.Rail.ark, .lightning, .onchainSwap, .onchainWallet])
    func aFailureNeverShowsTheKeyOrTheFinale(rail: SendMotion.Rail) {
        for outcome in [SendMotion.Outcome.inFlight, .pending, .stopped, .refunded] {
            for outcomeAt in [0.2, 1.0, 2.6, 8.0, 20.0] {
                let seen = Self.phases(rail, outcome, outcomeAt: outcomeAt)
                let success = seen.filter(\.isSuccessEnding)
                #expect(success.isEmpty, "\(rail) \(outcome) at \(outcomeAt)")
                #expect(!seen.contains(.confirmed) && !seen.contains(.waiting) && !seen.contains(.drop))
                let haptics = SendMotion.haptics(rail: rail, outcome: outcome, outcomeAt: outcomeAt, confirmations: 2, required: 2,
                                                 confirmedAt: outcomeAt + 1)
                let endings = haptics.filter { $0.key == "finale" || $0.key == "unlock" || $0.key.hasPrefix("confirmed") }
                #expect(endings.isEmpty)
            }
        }
    }

    @Test func backInYourWalletAppearsOnlyWithARecordedRefund() {
        for outcome in [SendMotion.Outcome.inFlight, .pending, .stopped, .succeeded] {
            let refund = Self.phases(.lightning, outcome, outcomeAt: 3).filter(\.isRefundEnding)
            #expect(refund.isEmpty, "\(outcome)")
        }
        let refunded = Self.phases(.lightning, .refunded, outcomeAt: 3)
        #expect(refunded.contains(.fizzle) && refunded.contains(.signBack) && refunded.last == .refunded)
        // The result reads "Payment failed" with the refund only for that outcome.
        #expect(SendResultKind.of(state: .failed, outcome: .refunded, slow: false) == .refunded)
        #expect(SendResultKind.of(state: .failed, outcome: .stopped, slow: false) == .failed)
        #expect(SendResultKind.of(state: .failed, outcome: .inFlight, slow: false) == .sending)
        #expect(SendMotion.haptics(rail: .lightning, outcome: .refunded, outcomeAt: 3).map(\.key).contains("fizzle"))
    }

    @Test func phasesNeverRunAheadOfTheWallet() {
        // Whatever the timer says, an in-flight call holds at its last in-flight phase.
        #expect(SendMotion.phase(rail: .ark, outcome: .inFlight, outcomeAt: .infinity, at: 600) == .sign)
        #expect(SendMotion.phase(rail: .onchainSwap, outcome: .inFlight, outcomeAt: .infinity, at: 600) == .swap)
        #expect(SendMotion.phase(rail: .onchainWallet, outcome: .inFlight, outcomeAt: .infinity, at: 600) == .start)
        #expect(SendMotion.phase(rail: .lightning, outcome: .inFlight, outcomeAt: .infinity, at: 5) == .paying)
        #expect(SendMotion.phase(rail: .lightning, outcome: .inFlight, outcomeAt: .infinity, at: 13) == .stillPaying)
        // A fast success still plays the earlier phases in order before its ending.
        let ark = Self.phases(.ark, .succeeded, outcomeAt: 0.3)
        let order: [SendMotion.Phase] = [.start, .split, .sign, .deliver, .delivered]
        #expect(ark.reduce(into: [SendMotion.Phase]()) { if $0.last != $1 { $0.append($1) } } == order)
        // A slow success starts its ending only when the wallet reports it.
        let late = Self.phases(.lightning, .succeeded, outcomeAt: 20)
        #expect(late[Int(19.9 / 0.05)] == .stillPaying)
        #expect(late[Int(20.1 / 0.05)] == .unlock)
        // On-chain confirms only once the confirmation is seen.
        #expect(SendMotion.phase(rail: .onchainSwap, outcome: .succeeded, outcomeAt: 2, at: 10) == .waiting)
        #expect(SendMotion.phase(rail: .onchainSwap, outcome: .succeeded, outcomeAt: 2, confirmedAt: 30, at: 29) == .waiting)
        #expect(SendMotion.phase(rail: .onchainSwap, outcome: .succeeded, outcomeAt: 2, confirmedAt: 30, at: 31) == .confirmed)
        #expect(SendMotion.confirmations(height: 101, tip: 102) == 2)
    }

    @Test func aRestoredStageShowsItsEndQuietlyAndHapticsPlayOnce() {
        let clock = SendMotionClock()
        clock.observe(.succeeded)
        #expect(clock.elapsed(Date()) >= 59)
        #expect(!clock.shouldPlay("finale", at: 2.75))
        // A status check never takes a shown outcome back to in flight.
        clock.observe(.inFlight)
        #expect(clock.outcome == .succeeded)
        let live = SendMotionClock()
        live.start()
        #expect(live.shouldPlay("split", at: 0.42))
        #expect(!live.shouldPlay("split", at: 0.42))
    }

    /// The phase line redraws only at its boundaries, so every change of phase falls on one.
    @Test(arguments: [SendMotion.Rail.ark, .lightning, .onchainSwap, .onchainWallet])
    func everyPhaseChangeFallsOnABoundary(rail: SendMotion.Rail) {
        for outcome in [SendMotion.Outcome.inFlight, .succeeded, .refunded, .pending, .stopped] {
            for seen in [0.5, 2.0, 7.3] {
                let outcomeAt = outcome == .inFlight ? Double.infinity : seen
                let confirmedAt: Double? = rail.isOnchain ? 20.4 : nil
                let boundaries = SendMotion.phaseBoundaries(rail: rail, outcomeAt: outcomeAt, confirmedAt: confirmedAt)
                var last = SendMotion.phase(rail: rail, outcome: outcome, outcomeAt: outcomeAt, confirmedAt: confirmedAt, at: 0)
                for t in stride(from: 0.0, through: 40, by: 0.005) {
                    let phase = SendMotion.phase(rail: rail, outcome: outcome, outcomeAt: outcomeAt, confirmedAt: confirmedAt, at: t)
                    guard phase != last else { continue }
                    last = phase
                    #expect(boundaries.contains { $0 <= t + 1e-9 && t - $0 < 0.006 }, "\(outcome) after \(seen) s changes at \(t)")
                }
            }
        }
    }

    /// Under Reduce Motion nothing loops: once settled, in flight or still paying, the stage
    /// draws the same frame, so its clock can stop.
    @Test(arguments: [SendMotion.Rail.ark, .lightning, .onchainSwap, .onchainWallet])
    func aReducedMotionStageHoldsStillOnceSettled(rail: SendMotion.Rail) throws {
        let label = SendRecipientLabel(name: "maya", nameIsVerbatim: true, avatar: .initial("m"))
        for (outcome, outcomeAt) in [(SendMotion.Outcome.inFlight, nil), (.pending, 3.0)] {
            let state = SendStagePainter.State(rail: rail, outcome: outcome, submittedAt: nil, outcomeAt: outcomeAt,
                                               confirmedAt: nil, confirmedHeight: nil, tip: 100, recipient: label)
            let end = try #require(SendStagePainter.settleTime(state, reduceMotion: true))
            #expect(SendStagePainter.settleTime(state, reduceMotion: false) == nil)
            #expect(try Self.frame(state, at: end) == Self.frame(state, at: end + 17.3), "\(outcome)")
            // The frames are real: the first fade differs from the settled frame.
            #expect(try Self.frame(state, at: 0.05) != Self.frame(state, at: end))
        }
    }

    static func frame(_ state: SendStagePainter.State, at t: Double) throws -> Data {
        let view = Canvas { context, size in
            var painter = SendStagePainter(
                canvas: MotionCanvas(context: context, size: size, viewBox: CGRect(x: 20, y: 10, width: 320, height: 230), colors: .theme(.light)),
                state: state, reduceMotion: true, nextLabel: "next", locale: Locale(identifier: "en"))
            painter.draw(at: t)
        }
        .frame(width: 320, height: 230)
        return try #require(ImageRenderer(content: view).uiImage?.pngData())
    }

    /// A saved attempt seen after relaunch: its ending has already played, so nothing
    /// replays and no haptic in its computed schedule fires (including the late Lightning finale).
    @Test(arguments: [SendMotion.Rail.ark, .lightning, .onchainSwap, .onchainWallet])
    func aRestoredSuccessKeepsItsWholeHapticScheduleSilent(rail: SendMotion.Rail) {
        let clock = SendMotionClock(), now = Date()
        clock.observe(.succeeded, at: now)
        if rail.isOnchain { clock.confirm(height: 101, at: now) }
        let t = clock.elapsed(now), outcomeAt = clock.since(clock.outcomeAt) ?? .infinity
        let schedule = SendMotion.haptics(rail: rail, outcome: .succeeded, outcomeAt: outcomeAt, confirmations: rail.isOnchain ? 1 : 0,
                                          confirmedAt: clock.since(clock.confirmedAt))
        #expect(schedule.contains { $0.kind == .success })
        #expect(schedule.allSatisfy { $0.at <= t && !clock.shouldPlay($0.key, at: $0.at) })
        #expect((SendMotion.resultAt(rail: rail, outcome: .succeeded, outcomeAt: outcomeAt) ?? .infinity) <= t)
        let end: SendMotion.Phase = switch rail { case .ark: .delivered; case .lightning: .paid; default: .confirmed }
        #expect(SendMotion.phase(rail: rail, outcome: .succeeded, outcomeAt: outcomeAt, confirmedAt: clock.since(clock.confirmedAt), at: t) == end)
        // A confirmation that arrives later is news, and plays.
        let later = SendMotionClock()
        later.observe(.succeeded, at: now)
        later.confirm(height: 101, at: now.addingTimeInterval(30))
        #expect((later.since(later.confirmedAt) ?? 0) > 61)
    }

    // MARK: Refund evidence

    static func send(status: String, hash: String = "hash", outputs: [String] = ["v1"], exited: [String] = [],
                     effective: Int64 = 0, fee: UInt64 = 0) -> Movement {
        Movement(id: 7, status: status, subsystemName: "bark.lightning_send", subsystemKind: "send", metadataJson: "{}",
                 intendedBalanceSats: -1_500, effectiveBalanceSats: effective, offchainFeeSats: fee,
                 sentToAddresses: [], receivedOnAddresses: [], inputVtxoIds: ["in"], outputVtxoIds: outputs,
                 exitedVtxoIds: exited, createdAt: "", updatedAt: "", completedAt: nil,
                 paymentHash: hash, lightningInvoice: nil, lightningOffer: nil)
    }

    @Test func aRefundIsARevokedSendThatLeftNothing() {
        #expect(SendRefundEvidence.recorded([Self.send(status: "failed")], paymentHash: "hash"))
        // Escalated to an emergency exit, still pending, not zero, or not this payment: no refund.
        #expect(!SendRefundEvidence.recorded([Self.send(status: "failed", outputs: [], exited: ["v1"])], paymentHash: "hash"))
        #expect(!SendRefundEvidence.recorded([Self.send(status: "pending")], paymentHash: "hash"))
        #expect(!SendRefundEvidence.recorded([Self.send(status: "failed", effective: -12, fee: 12)], paymentHash: "hash"))
        #expect(!SendRefundEvidence.recorded([Self.send(status: "failed", hash: "other")], paymentHash: "hash"))
        #expect(!SendRefundEvidence.recorded([Self.send(status: "failed"), Self.send(status: "failed")], paymentHash: "hash"))
        #expect(!SendRefundEvidence.recorded([], paymentHash: "hash"))
        // A retried invoice reuses a movement from before the attempt: what came back can't be tied to it.
        #expect(SendRefundEvidence.refund([Self.send(status: "failed")], paymentHash: "hash", before: [7]) == .untied)
        #expect(SendRefundEvidence.refund([Self.send(status: "failed")], paymentHash: "hash", before: [1]) == .recorded)
        #expect(SendRefundEvidence.refund([Self.send(status: "pending")], paymentHash: "hash", before: []) == .notRecorded)
    }

    // MARK: Hold to send

    actor Gate {
        var calls = 0
        private var continuation: CheckedContinuation<SendReceipt, Never>?
        func pay() async -> SendReceipt {
            calls += 1
            return await withCheckedContinuation { continuation = $0 }
        }
        func finish() { continuation?.resume(returning: .init(amount: 2_500, proof: .submitReturnedArkSuccess)); continuation = nil }
    }

    @Test func holdToSendCannotDoubleSubmitAcrossPoses() async throws {
        let gate = Gate()
        let storage = SendPaymentTests.Storage()
        let controller = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: []) }, pay: { _, _, _ in await gate.pay() },
            reconcile: { _ in .uncertain }, read: { storage.attempt }, write: { try storage.write($0) }))
        let payment = Self.ark, quote = SendQuote(amount: 2_500, fee: 0, total: 2_500)
        let hold = SendHoldGate()
        var confirms = 0
        let confirm = {
            hold.confirm(generation: controller.generation) {
                confirms += 1
                controller.submit(payment, quote: quote)
            }
        }
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        func show() {
            // A pose change rebuilds the review; Send's gate and the controller outlive it.
            window.rootViewController = UIHostingController(rootView: SendConfirmation(
                payment: payment, quote: quote, controller: controller, cancel: {}, done: {}, onLeave: {}, gate: hold))
        }
        window.isHidden = false
        defer { window.isHidden = true; window.rootViewController = nil }
        show()
        let generation = controller.generation
        // Two completions racing from the same review: one submission.
        #expect(hold.confirm(generation: generation) { confirms += 1; controller.submit(payment, quote: quote) })
        #expect(!hold.confirm(generation: generation) { confirms += 1; controller.submit(payment, quote: quote) })
        try await SendPaymentTests.waitUntil { controller.state == .submitting }
        for size in [CGSize(width: 466, height: 678), .init(width: 951, height: 669), .init(width: 466, height: 678),
                     .init(width: 951, height: 669)] {
            window.frame.size = size
            show()
            window.layoutIfNeeded()
            confirm()
            try await Task.sleep(for: .milliseconds(20))
            #expect(controller.locked && controller.attempt?.payment == payment)
        }
        try await SendPaymentTests.waitUntil { controller.attempt != nil }
        #expect(await gate.calls == 1)
        await gate.finish()
        try await SendPaymentTests.waitUntil { controller.state != .submitting }
        #expect(controller.state == .succeeded(.init(amount: 2_500, proof: .submitReturnedArkSuccess)))
        #expect(await gate.calls == 1)
    }

    /// Unresolved results hide the tab bar, so each offers Close. Leaving keeps the attempt, its
    /// state and the send lock exactly as they were.
    @Test func unresolvedResultsOfferAWayOutThatKeepsThePayment() async throws {
        var receipt = SendReceipt(amount: 50_000, txid: String(repeating: "cd", count: 32), status: .submitted, fee: 420, proof: .preparedTxid)
        receipt.broadcastRetryPending = true
        var quote = SendQuote(amount: 50_000, fee: 420, total: 50_420, feeRate: 2)
        quote.prepared = try JSONDecoder().decode(SendPreparedTransaction.self, from: Data(
            #"{"id":"fixture","txid":"\#(String(repeating: "cd", count: 32))","vsize":141,"inputCount":1,"createdAt":\#(Date().timeIntervalSinceReferenceDate)}"#.utf8))
        let wallet = SendPayment(destination: SendReceiveShotTests.bitcoin, method: "onchain", amount: 50_000, source: .onchain)
        func controller(_ result: Result<SendReceipt, Error>?, read: (() throws -> Attempt?)? = nil) -> SendPaymentController {
            let storage = SendPaymentTests.Storage()
            return SendPaymentController(dependencies: .init(
                prepare: { _, _ in .init(movementIDs: []) }, pay: { _, _, _ in try result!.get() },
                reconcile: { _ in .uncertain }, read: read ?? { storage.attempt }, write: { try storage.write($0) }))
        }
        struct Unreadable: Error {}
        let uncertain = controller(.failure(URLError(.timedOut)))
        uncertain.submit(Self.ark, quote: SendQuote(amount: 2_500, fee: 0, total: 2_500))
        let broadcast = controller(.success(receipt))
        broadcast.submit(wallet, quote: quote)
        let blocked = controller(nil, read: { throw Unreadable() })
        try await SendPaymentTests.waitUntil { uncertain.state == .uncertain && !uncertain.checking && broadcast.state != .submitting }
        for (subject, kind) in [(uncertain, SendResultKind.uncertain), (blocked, .unavailable), (broadcast, .awaitingBroadcast)] {
            #expect(SendResultKind.of(state: subject.state, outcome: .stopped, slow: false) == kind)
            for shown in [false, true] {
                #expect(SendOutcomeView.actions(kind: kind, uncertain: subject.state == .uncertain, resultShown: shown).last == .close, "\(kind)")
            }
            // Close is onLeave: Send's tab changes, and Send's own clean-up on leaving refuses a locked payment.
            let model = SendModel(controller: subject)
            let before = (subject.state, subject.attempt?.payment, subject.attempt?.receipt, subject.generation, model.stage)
            model.reset()
            model.discardReview()
            #expect(subject.locked && subject.state == before.0 && subject.generation == before.3 && model.stage == before.4)
            #expect(subject.attempt?.payment == before.1 && subject.attempt?.receipt == before.2)
        }
        // Results that resolve or already leave don't offer Close.
        for kind in [SendResultKind.sent, .refunded, .failed, .alreadyPaid, .stillSending, .sending] {
            #expect(!SendOutcomeView.actions(kind: kind, uncertain: false, resultShown: true).contains(.close))
        }
    }

    /// An uncertain recurring payment's approval sheet offers Close, which only dismisses the
    /// sheet: the attempt, its state and the send lock stay as they are.
    @Test func closingAnUncertainRecurringApprovalDismissesIt() async throws {
        let backend = SendPaymentTests.Backend()
        let controller = SendPaymentTests().make(backend, SendPaymentTests.Storage())
        controller.submit(SendPaymentTests().payment, quote: SendPaymentTests().quote)
        try await SendPaymentTests.waitUntil { controller.state == .uncertain && !controller.checking }
        let kind = SendResultKind.of(state: controller.state, outcome: .stopped, slow: false)
        #expect(SendOutcomeView.actions(kind: kind, uncertain: true, resultShown: true).last == .close)
        let before = (controller.attempt?.payment, controller.attempt?.receipt, controller.generation)
        var dismissed = 0
        let sheet = RecurringPaymentsView.approval(payment: SendPaymentTests().payment, quote: SendPaymentTests().quote,
                                                   controller: controller, pay: { Issue.record("Close paid") },
                                                   dismiss: { dismissed += 1 }, done: { Issue.record("Close acknowledged") })
        sheet.onLeave()
        #expect(dismissed == 1)
        #expect(controller.locked && controller.state == .uncertain && controller.generation == before.2)
        #expect(controller.attempt?.payment == before.0 && controller.attempt?.receipt == before.1)
        #expect(await backend.calls == 1)
    }

    /// 2,500 typed, then a scan straight from Amount brings an invoice for another amount:
    /// the amount is kept before the import, so the review notes the invoice's amount.
    @Test func scanningFromAmountKeepsTheInvoiceAmountNote() async {
        let model = SendModel(controller: SendPaymentTests().make(.init(), .init()))
        model.amount = "2500"
        #expect(model.stage == .amount && model.sats == 2_500)
        // What the scan now captures before importing (only Next used to).
        let typed = SendView.typedAmount(model, keeping: nil)
        await model.importRecipient(SendReceiveShotTests.invoice)
        #expect(model.parsed.amount != nil && model.parsed.amount != 2_500)
        #expect(SendView.showsInvoiceNote(typed: typed, fixed: model.parsed.amount))
        #expect(!SendView.showsInvoiceNote(typed: nil, fixed: model.parsed.amount))
        // Captured again once the invoice set the amount, the typed one is kept.
        #expect(SendView.typedAmount(model, keeping: typed) == 2_500)
    }

    /// Paste on Amount (the old app's send-paste-from-amount) takes today's import from there.
    private func pasteModel() -> SendModel {
        let model = SendModel(controller: SendPaymentTests().make(.init(), .init()),
                              quotePayment: { .init(amount: $0.amount, fee: 0, total: $0.amount) })
        model.arkBalance = 100_000
        return model
    }

    /// An invoice with an amount fills it and goes on to Review.
    @Test func pastingAnInvoiceOnAmountReachesReview() async {
        let model = pasteModel()
        await model.importRecipient(SendReceiveShotTests.invoice)
        #expect(model.stage == .review && model.sats == 1_500 && model.progress.recipientConfirmed && model.quote?.amount == 1_500)
        #expect(model.payment?.destination == SendReceiveShotTests.invoice)
    }

    /// A typed amount survives an amountless recipient from Amount, by Scan or Paste (both take
    /// `importRecipient`): the keypad keeps its turn with the recipient shown, and Next goes on.
    @Test func pastingAnAddressOnAmountKeepsTheTypedAmount() async {
        let model = pasteModel()
        model.amount = "700"
        await model.importRecipient(SendReceiveShotTests.bitcoin)
        #expect(model.stage == .amount && model.sats == 700 && model.progress.recipientConfirmed && !model.progress.amountConfirmed)
        #expect(model.destination == SendReceiveShotTests.bitcoin && model.error == nil)
        await model.continueStage()
        #expect(model.stage == .review && model.payment?.amount == 700 && model.payment?.destination == SendReceiveShotTests.bitcoin)
    }

    /// 2,500 typed, Next, Back, then an amountless recipient scanned or pasted on Amount: the
    /// earlier Next doesn't count, so the keypad keeps its turn with 2,500 and Next goes on.
    /// Scan and Paste share `importRecipient`; a scanned code is often a BIP-21 URI.
    @Test func anAmountlessImportAfterNextAndBackKeepsTheKeypad() async {
        for value in [SendReceiveShotTests.ark, "bitcoin:\(SendReceiveShotTests.bitcoin)"] {
            let model = pasteModel()
            model.amount = "2500"
            await model.continueStage()
            #expect(model.stage == .recipient && model.progress.amountConfirmed)
            model.stage = .amount  // Back from To, as SendView's goBack does for an amount-first entry.
            await model.importRecipient(value)
            #expect(model.stage == .amount && model.sats == 2_500 && model.progress.recipientConfirmed, "\(value)")
            #expect(!model.progress.amountConfirmed && model.error == nil, "\(value)")
            await model.continueStage()
            #expect(model.stage == .review && model.payment?.amount == 2_500, "\(value)")
        }
    }

    /// An Ark-funded on-chain review shows the round's confirmations (today's setting) in its
    /// Arrives row, so a Max quote's estimate note or an invoice note can't crowd them out.
    @Test func anArkFundedOnchainReviewShowsItsConfirmations() {
        let max = SendPayment(destination: SendReceiveShotTests.bitcoin, method: "onchain", amount: 50_000, source: .offchain)
        let maxQuote = SendQuote(amount: 49_500, fee: 500, total: 50_000, vtxoIDs: ["vtxo"])
        let invoiceNote = "The invoice sets the amount to ₿1,500"
        for (payment, quote, note) in [(max, maxQuote, nil), (max, SendQuote(amount: 1_500, fee: 300, total: 1_800), invoiceNote)] {
            #expect(SendMotion.Rail(payment) == .onchainSwap)
            #expect(SendConfirmation.arrives(.init(payment), required: 2, locale: "en") == "After 2 confirmations")
            // The fine print keeps its own note.
            #expect(SendConfirmation.fine(invoiceNote: note, quote: quote, locale: "en")
                    == note ?? SendQuote.recipientEstimateExplanation)
        }
    }

    /// A view's size in a column `width` wide, in `locale` at text size `type`.
    static func fitted(_ view: some View, width: CGFloat, _ locale: String, _ type: DynamicTypeSize) -> CGSize {
        UIHostingController(rootView: view.environment(\.locale, Locale(identifier: locale)).environment(\.dynamicTypeSize, type))
            .sizeThatFits(in: CGSize(width: width, height: 10_000))
    }

    /// The smallest and default text sizes, the largest before the accessibility sizes, and the largest.
    static let textSizes: [DynamicTypeSize] = [.xSmall, .large, .xxxLarge, .accessibility5]

    /// The Arrives row never cuts its value off on an SE-width phone (a 335-pt review column):
    /// too long for one line (Swedish at xxxLarge, every language at accessibility sizes), its label
    /// and value stack and wrap. A row that fits keeps its one line.
    @Test func arrivesStaysReadableAtEveryTextSize() {
        let column: CGFloat = 335
        var stacked = 0
        for locale in LegacyLocale.supported {
            let rows = [SendMotion.Rail.ark, .lightning, .onchainWallet].map { SendConfirmation.arrives($0, required: 2, locale: locale) }
                + [1, 2].map { SendConfirmation.arrives(.onchainSwap, required: $0, locale: locale) }
            for type in Self.textSizes {
                for text in rows {
                    let row = Self.fitted(SendDetailRow("Arrives", text: text, verbatim: true, wraps: true), width: column, locale, type)
                    let line = Self.fitted(SendDetailRow("Arrives", text: text, verbatim: true).fixedSize(), width: 10_000, locale, type)
                    if line.width <= column {
                        #expect(row.height == line.height, "\(locale) \(type) \(text)")
                        continue
                    }
                    stacked += 1
                    let label = Self.fitted(Text("Arrives").noahText(NoahTokens.Typography.body), width: column, locale, type)
                    let value = Self.fitted(Text(verbatim: text).noahText(NoahTokens.Typography.label), width: column, locale, type)
                    #expect(row.height >= label.height + value.height, "\(locale) \(type) \(text)")
                }
            }
        }
        #expect(stacked > 0)
    }

    /// Review's other rows (Via, From, Fee, Total and Note) do as Arrives does in the same SE-width column:
    /// a row too long for one line stacks its label above its value and both wrap, so no label or amount is cut off.
    @Test func reviewRowsStayReadableAtEveryTextSize() {
        let column: CGFloat = 335
        var stacked = 0
        for locale in LegacyLocale.supported {
            let amount = BitcoinAmount.format(sats: 152_742, unit: .bip177, locale: Locale(identifier: locale))
            // Each row: its label, the row itself (one line only, or wrapping) and its value alone.
            func text(_ label: String, _ value: String, verbatim: Bool = false, glyph: String? = nil) -> (String, (Bool) -> AnyView, AnyView) {
                (label, { AnyView(SendDetailRow(LocalizedStringKey(label), text: value, verbatim: verbatim, glyph: glyph, wraps: $0)) },
                 AnyView(Group { if verbatim { Text(verbatim: value) } else { Text(LocalizedStringKey(value)) } }.noahText(NoahTokens.Typography.label)))
            }
            func number(_ label: String) -> (String, (Bool) -> AnyView, AnyView) {
                (label, { AnyView(SendDetailRow(LocalizedStringKey(label), number: amount, wraps: $0)) },
                 AnyView(Text(verbatim: amount).noahText(SendText.rowNumber).monospacedDigit()))
            }
            let rows = [SendMotion.Rail.ark, .lightning, .onchainSwap].map { text("Via", SendOutcomeView.railName($0), glyph: SendOutcomeView.railGlyph($0)) }
                + SendSource.allCases.map { text("From", $0.label) }
                + ["Fee", "Estimated fee"].map { text($0, "Free") }
                + ["Fee", "Estimated fee", "Total", "Estimated total", "Total (reviewed coins)"].map(number)
                + [text("Note", "Coffee and a croissant, thanks!", verbatim: true)]
            for type in Self.textSizes {
                for (label, row, value) in rows {
                    let wrapping = Self.fitted(row(true), width: column, locale, type)
                    let line = Self.fitted(row(false).fixedSize(), width: 10_000, locale, type)
                    if line.width <= column {
                        #expect(wrapping.height == line.height, "\(locale) \(type) \(label)")
                        continue
                    }
                    stacked += 1
                    let title = Self.fitted(Text(LocalizedStringKey(label)).noahText(NoahTokens.Typography.body), width: column, locale, type)
                    #expect(wrapping.height >= title.height + Self.fitted(value, width: column, locale, type).height, "\(locale) \(type) \(label)")
                }
            }
        }
        #expect(stacked > 0)
    }

    /// Hold to send keeps all its words at every text size: they wrap inside a taller pill rather
    /// than truncate.
    @Test func holdToSendWrapsAtEveryTextSize() {
        let column: CGFloat = 335
        var wrapped = 0
        for locale in LegacyLocale.supported {
            for type in Self.textSizes {
                let pill = Self.fitted(SendHoldButton(amount: "₿1,500") {}, width: column, locale, type)
                let words = Self.fitted(Text("Hold to send").noahText(NoahTokens.Typography.button), width: column - 40, locale, type)
                let line = Self.fitted(Text("Hold to send").noahText(NoahTokens.Typography.button).fixedSize(), width: 10_000, locale, type)
                if words.height > line.height { wrapped += 1 }
                #expect(pill.height >= words.height + 20, "\(locale) \(type)")
            }
        }
        #expect(wrapped > 0)
    }

    /// A first finger presses, a second lands 500 ms later and the first lifts at 600 ms:
    /// no single finger held for 0.8 s, so the hold can't confirm until every finger lifts.
    @Test func aSecondFingerCancelsTheHold() {
        var press = SendHoldPress()
        press.touches(1)
        #expect(!press.spoiled)
        press.touches(2)
        press.touches(1)
        #expect(press.spoiled)
        press.touches(0)
        press.touches(1)
        #expect(!press.spoiled)
    }

    @Test func aRefusedReservationLetsYouHoldAgain() {
        let hold = SendHoldGate()
        var submitted = 0
        #expect(hold.confirm(generation: 4) { submitted += 1 })
        // The controller bumped its generation for the attempt; no payment was made, so a new hold is a new attempt.
        #expect(hold.confirm(generation: 5) { submitted += 1 })
        #expect(!hold.confirm(generation: 5) { submitted += 1 })
        #expect(submitted == 2)
    }

    // MARK: Recent recipients

    @Test func recentsComeFromHistoryWithoutInvoices() {
        let rows = [
            HomeShotTests.row("a", "Lnurl", 1, "outgoing", HomeShotTests.at(0, 9, 0), to: "Maya@LocalHost.com"),
            HomeShotTests.row("b", "Bolt11", 1, "outgoing", HomeShotTests.at(0, 8, 0), to: SendReceiveShotTests.invoice),
            HomeShotTests.row("c", "Onchain", 1, "outgoing", HomeShotTests.at(0, 7, 0), to: SendReceiveShotTests.bitcoin),
            HomeShotTests.row("d", "Lnurl", 1, "outgoing", HomeShotTests.at(0, 6, 0), to: "maya@localhost.com"),
            HomeShotTests.row("e", "Lnurl", 1, "outgoing", HomeShotTests.at(0, 5, 0), to: "bob@getalby.com", status: "failed"),
            HomeShotTests.row("f", "Arkoor", 1, "incoming", HomeShotTests.at(0, 4, 0), to: "lucas@ln.tips"),
        ]
        let recents = SendRecent.from(rows, ownDomain: "localhost.com")
        #expect(recents.map(\.destination) == ["maya@localhost.com", SendReceiveShotTests.bitcoin])
        #expect(recents.first?.label == SendRecipientLabel(name: "maya", nameIsVerbatim: true, avatar: .initial("m")))
        #expect(recents.last?.label.avatar == .chain)
        #expect(SendRecent.matching(recents, query: "ma").map(\.destination) == ["maya@localhost.com"])
    }

    /// Next on an empty amount goes to To, recipient-first; on 0 it asks for an amount, and a valid
    /// amount clears that at once (send-receive.md, Amount).
    @Test func amountErrorShowsOnlyForZeroAndClearsWhenTheAmountIsValid() async {
        let empty = pasteModel()
        await empty.nextFromAmount()
        #expect(empty.stage == .recipient && empty.progress.entry == .recipientFirst && empty.error == nil)

        let model = pasteModel()
        model.amount = "0"
        await model.nextFromAmount()
        #expect(model.stage == .amount && model.error == SendModel.zeroAmountError)
        model.amount = "00"
        #expect(model.error == SendModel.zeroAmountError)
        model.amount = "5"
        #expect(model.error == nil)
        model.amount = ""
        #expect(model.error == nil) // Back to empty doesn't ask again until Next.
    }

    /// An invalid paste keeps its error on Amount, and it isn't about the amount, so typing one keeps it.
    @Test func typingAnAmountKeepsAnInvalidPasteError() async {
        let model = pasteModel()
        await model.importRecipient("not a destination")
        #expect(model.stage == .amount && !model.progress.recipientConfirmed)
        let error = model.error
        #expect(error != nil && error != SendModel.zeroAmountError)
        model.amount = "1200"
        #expect(model.error == error)
    }
}

/// Receive shows exactly the chosen format, and its arrivals follow real events.
@MainActor
struct ReceiveFormatTests {
    static let address = "maya@noahwallet.io"
    static let plain = ReceiveRequest(arkAddress: SendReceiveShotTests.ark, onchainAddress: SendReceiveShotTests.bitcoin)
    static var priced: ReceiveRequest {
        var request = plain
        request.amountSat = 1_500
        request.lightningInvoice = SendReceiveShotTests.invoice
        request.paymentHash = PaymentEncoding.invoice(SendReceiveShotTests.invoice)?.hash
        return request
    }

    @Test func eachFormatCarriesExactlyItsPayload() throws {
        #expect(ReceiveFormat.lightningAddress.payload(request: nil, address: Self.address) == Self.address)
        #expect(ReceiveFormat.lightningAddress.payload(request: Self.plain, address: nil) == nil)
        #expect(ReceiveFormat.request.payload(request: Self.plain, address: Self.address) == (try Self.plain.uri()))
        #expect(ReceiveFormat.request.payload(request: Self.priced, address: Self.address) == (try Self.priced.uri()))
        #expect(try Self.priced.uri().contains("lightning="))
        #expect(ReceiveFormat.invoice.payload(request: Self.plain, address: Self.address) == nil)
        #expect(ReceiveFormat.invoice.payload(request: Self.priced, address: Self.address) == SendReceiveShotTests.invoice)
        #expect(ReceiveFormat.ark.payload(request: Self.priced, address: Self.address) == SendReceiveShotTests.ark)
        #expect(ReceiveFormat.bitcoin.payload(request: Self.priced, address: Self.address) == SendReceiveShotTests.bitcoin)
        #expect(ReceiveFormat.allCases.count == 5)
        // An amount shows the unified request; removing it returns to the Lightning address from any format.
        #expect(ReceiveFormat.after(amountChange: true) == .request)
        #expect(ReceiveFormat.after(amountChange: false) == .lightningAddress)
        // A fresh request keeps only what it can show: no invoice without an amount.
        for format in ReceiveFormat.allCases {
            let kept = ReceiveFormat.reconciled(format, hasAmount: false)
            #expect(kept.payload(request: Self.plain, address: Self.address) != nil, "\(format)")
        }
        #expect(ReceiveFormat.reconciled(.ark, hasAmount: false) == .ark && ReceiveFormat.reconciled(.bitcoin, hasAmount: false) == .bitcoin)
        #expect(ReceiveFormat.reconciled(.invoice, hasAmount: true) == .invoice)
        #expect(ReceiveFormat.request.showsBand(hasAmount: true) && !ReceiveFormat.ark.showsBand(hasAmount: true))
        #expect(QRMatrix.correction(for: try Self.priced.uri()) == "M" && QRMatrix.correction(for: Self.address) == "H")
    }

    /// The card as drawn decodes to exactly the shown format, with the logo: every format once, and the
    /// densest (the priced request) in the dark theme too.
    @Test func theShownQRDecodesToTheChosenFormat() throws {
        let cards: [(ReceiveFormat, ReceiveRequest, ColorScheme)] = ReceiveFormat.allCases.map { ($0, Self.priced, ColorScheme.light) }
            + [(.request, Self.plain, .light), (.request, Self.priced, .dark)]
        for (format, request, scheme) in cards {
            let payload = try #require(format.payload(request: request, address: Self.address))
            let card = ReceiveCard(payload: payload, title: format.name, caption: format.caption(hasAmount: request.amountSat != nil),
                                   band: format.showsBand(hasAmount: request.amountSat != nil) ? ("₿ 1,500", nil) : nil, qrSide: 226,
                                   reveal: false)
                .frame(width: 300)
                .padding(24)
                .background(NoahTokens.Colors.background)
                .environment(\.colorScheme, scheme)
            #expect(try StyledQRCodeTests.decode(card) == [payload], "\(format) in \(scheme)")
        }
    }

    /// Fiat amounts stay canonical whatever the locale's separator, so the keypad's "." never
    /// makes "2,80." (not an amount, which used to read as zero and remove the amount).
    @Test func receiveAmountsStayCanonicalAndOnlyAnEmptyAmountClears() {
        var amount = FiatCurrency.satsToFiat(2_800, btcPrice: 100_000, currency: "EUR")
        #expect(amount == "2.80")
        SendKeypad.apply(".", to: &amount)
        SendKeypad.apply("5", to: &amount)
        #expect(amount == "2.805")
        #expect(ReceiveAmountSheet.sats(amount, fiat: true, btcPrice: 100_000) == 2_805)
        #expect(ReceiveAmountSheet.sats("2,80.", fiat: true, btcPrice: 100_000) == nil)
        #expect(ReceiveAmountSheet.sats("1.5", fiat: true, btcPrice: nil) == nil)
        #expect(ReceiveAmountSheet.sats("", fiat: true, btcPrice: 100_000) == 0)
        #expect(ReceiveAmountSheet.sats("1500", fiat: false, btcPrice: nil) == 1_500)
    }

    /// Switching currency keeps a zero an invalid zero: only an emptied amount removes it.
    @Test func switchingCurrencyNeverTurnsAZeroIntoAClear() {
        for zero in ["0", "0.0001", "0.00"] {
            let switched = ReceiveAmountSheet.switched(zero, fromFiat: true, btcPrice: 100_000, currency: "USD")
            #expect(switched == "0" && ReceiveAmountSheet.sats(switched, fiat: false, btcPrice: nil) == 0, "\(zero)")
        }
        #expect(ReceiveAmountSheet.switched("0", fromFiat: false, btcPrice: 100_000, currency: "USD") == "0")
        #expect(ReceiveAmountSheet.switched("1", fromFiat: false, btcPrice: 100_000, currency: "USD") == "0.00")
        #expect(ReceiveAmountSheet.switched("", fromFiat: true, btcPrice: 100_000, currency: "USD").isEmpty)
        #expect(ReceiveAmountSheet.switched("2.80", fromFiat: true, btcPrice: 100_000, currency: "EUR") == "2800")
        #expect(ReceiveAmountSheet.switched("2800", fromFiat: false, btcPrice: 100_000, currency: "EUR") == "2.80")
    }

    @Test func onchainArrivalsAreEveryNewDepositThisRequestsAddressFirst() {
        let snapshot = ReceiveChainSnapshot(tip: 100, incoming: [
            .init(txid: "old", toAddress: 5, received: 5, confirmedHeight: nil),
            .init(txid: "other", toAddress: 0, received: 9, confirmedHeight: nil),
            .init(txid: "mine", toAddress: 7, received: 7, confirmedHeight: nil),
            .init(txid: "mined", toAddress: 8, received: 8, confirmedHeight: 99),
        ])
        // Later checks: every new unconfirmed deposit, the address's first; never an already-confirmed one.
        let later = snapshot.look(known: ["old"], announced: [])
        #expect(later.arrivals.map(\.txid) == ["mine", "other"])
        #expect(later.known == ["old", "other", "mine", "mined"])
        #expect(snapshot.look(known: ["old"], announced: ["mine"]).arrivals.map(\.txid) == ["other"])
        // The first check: a payment to this address that landed while it synced is an
        // arrival; what was already there (unrelated or confirmed) isn't.
        #expect(snapshot.look(known: nil, announced: []).arrivals.map(\.txid) == ["old", "mine"])
        #expect(snapshot.look(known: nil, announced: ["old"]).arrivals.map(\.txid) == ["mine"])
        // With what the wallet knew before the first sync, a deposit that synced in meanwhile to
        // an address you shared earlier is news too; one it already had isn't.
        let synced = ReceiveChainSnapshot(tip: 100, incoming: snapshot.incoming, before: ["other", "mined", "mine"])
        #expect(synced.look(known: nil, announced: []).arrivals.map(\.txid) == ["old", "mine"])
        let older = ReceiveChainSnapshot(tip: 100, incoming: snapshot.incoming, before: ["old", "mined"])
        #expect(older.look(known: nil, announced: []).arrivals.map(\.txid) == ["old", "mine", "other"])
        // After the first arrival, a second deposit still arrives.
        let next = ReceiveChainSnapshot(tip: 100, incoming: snapshot.incoming + [.init(txid: "again", toAddress: 4, received: 4, confirmedHeight: nil)])
        #expect(next.look(known: later.known, announced: ["mine", "other"]).arrivals.map(\.txid) == ["again"])
    }

    /// Two arrivals in one check: the second plays as soon as the first has flipped home,
    /// with nothing else changing; an on-chain arrival then stays for its block row.
    @Test func simultaneousArrivalsPlayInTurn() {
        let start = Date()
        var queue = ReceiveArrivalQueue()
        let deposit = ReceiveArrival(rail: .onchain, sats: 100_000, start: start, txid: "a")
        let lightning = ReceiveArrival(rail: .lightning, sats: 1_500, start: start)
        queue.announce(deposit, at: start)
        queue.announce(lightning, at: start)
        #expect(queue.current?.id == deposit.id && queue.waiting.map(\.id) == [lightning.id])
        let home = start.addingTimeInterval(deposit.settledAt)
        let next = queue.finish(at: home)
        #expect(next)
        #expect(queue.current?.id == lightning.id && queue.current?.start == home && queue.waiting.isEmpty)
        let after = queue.finish(at: home.addingTimeInterval(lightning.settledAt))
        #expect(!after && queue.current == nil)
        // A settled on-chain arrival gives way at once, and stays when nothing follows it.
        queue.announce(deposit, at: start)
        let stays = !queue.finish(at: home)
        #expect(stays && queue.current?.id == deposit.id)
        let second = ReceiveArrival(rail: .onchain, sats: 5_000, start: start, txid: "b")
        queue.announce(second, at: home.addingTimeInterval(1))
        #expect(queue.current?.id == second.id && queue.waiting.isEmpty)
    }

    /// The deposit being followed is replaced (RBF) and the replacement confirms before the
    /// next check: the block row confirms with it instead of waiting for the old one.
    @Test func aConfirmedReplacementConfirmsTheFollowedDeposit() {
        let spent: Set<SendTransaction.Input> = [.init(txid: "funding", vout: 1)]
        var queue = ReceiveArrivalQueue()
        queue.announce(ReceiveArrival(rail: .onchain, sats: 100_000, start: Date(), txid: "a", inputs: spent))
        let replaced = ReceiveChainSnapshot(tip: 101, incoming: [
            .init(txid: "b", toAddress: 100_000, received: 100_000, confirmedHeight: 101, inputs: spent.union([.init(txid: "more", vout: 0)])),
        ])
        let followed = queue.follow(replaced)
        #expect(followed.confirmed && followed.txids == ["b"])
        #expect(queue.current?.txid == "b" && queue.current?.confirmedHeight == 101)
        // Announced as followed, the replacement isn't news either.
        #expect(replaced.look(known: ["a"], announced: followed.txids).arrivals.isEmpty)
        // An unrelated confirmed deposit is not a replacement.
        var other = ReceiveArrivalQueue()
        other.announce(ReceiveArrival(rail: .onchain, sats: 100_000, start: Date(), txid: "a", inputs: spent))
        let unrelated = ReceiveChainSnapshot(tip: 101, incoming: [
            .init(txid: "a", toAddress: 100_000, received: 100_000, confirmedHeight: nil, inputs: spent),
            .init(txid: "c", toAddress: 0, received: 7, confirmedHeight: 101, inputs: [.init(txid: "x", vout: 0)]),
        ])
        let still = other.follow(unrelated)
        #expect(!still.confirmed && !still.ended && other.current?.txid == "a" && other.current?.confirmedHeight == nil)
    }

    /// Deposits A and B arrive and the card moves on to B; then A is fee-bumped. A's
    /// replacement spends A's inputs, so it's not announced as another deposit.
    @Test func aReplacedEarlierDepositIsNotAnnouncedAgain() {
        let start = Date()
        let a: Set<SendTransaction.Input> = [.init(txid: "fundA", vout: 0)], b: Set<SendTransaction.Input> = [.init(txid: "fundB", vout: 1)]
        let first = ReceiveChainSnapshot(tip: 100, incoming: [
            .init(txid: "A", toAddress: 5_000, received: 5_000, confirmedHeight: nil, inputs: a),
            .init(txid: "B", toAddress: 7_000, received: 7_000, confirmedHeight: nil, inputs: b),
        ])
        var queue = ReceiveArrivalQueue(), announced: Set<String> = [], spent: Set<SendTransaction.Input> = []
        let look = first.look(known: [], announced: announced, spent: spent)
        #expect(look.arrivals.map(\.txid) == ["A", "B"])
        for transaction in look.arrivals {
            announced.insert(transaction.txid)
            spent.formUnion(transaction.inputs)
            queue.announce(ReceiveArrival(rail: .onchain, sats: transaction.arrivingSats, start: start, txid: transaction.txid,
                                          inputs: transaction.inputs), at: start)
        }
        queue.finish(at: start.addingTimeInterval(10))
        #expect(queue.current?.txid == "B" && queue.waiting.isEmpty)
        let bumped = ReceiveChainSnapshot(tip: 100, incoming: [
            .init(txid: "A2", toAddress: 5_000, received: 5_000, confirmedHeight: nil, inputs: a.union([.init(txid: "fee", vout: 0)])),
            .init(txid: "B", toAddress: 7_000, received: 7_000, confirmedHeight: nil, inputs: b),
        ])
        let followed = queue.follow(bumped)
        announced.formUnion(followed.txids)
        #expect(!followed.ended && queue.current?.txid == "B")
        #expect(bumped.look(known: look.known, announced: announced, spent: spent).arrivals.isEmpty)
        // Tracking only the current and waiting arrivals, A2 would have arrived again.
        #expect(bumped.look(known: look.known, announced: announced).arrivals.map(\.txid) == ["A2"])
        // A deposit spending other coins still arrives.
        let other = ReceiveChainSnapshot(tip: 100, incoming: bumped.incoming + [.init(txid: "C", toAddress: 1, received: 1, confirmedHeight: nil, inputs: [.init(txid: "fundC", vout: 0)])])
        #expect(other.look(known: look.known, announced: announced, spent: spent).arrivals.map(\.txid) == ["C"])
    }

    /// B takes the card from A. A2 spends A's coin and one more and is held back as A's
    /// replacement; A3 then spends only that new coin. A2's inputs were kept, so A3 is a
    /// replacement too, not new money. A chain of three replacements stays quiet the same way.
    @Test func aReplacementOfAReplacementIsNotAnnouncedAgain() {
        typealias Input = SendTransaction.Input
        var known: Set<String>? = [], announced: Set<String> = [], spent: Set<Input> = []
        // One check of Receive's watch, with nothing followed (the card has moved on).
        func check(_ incoming: [ReceiveChainSnapshot.Transaction]) -> [String] {
            let look = ReceiveChainSnapshot(tip: 100, incoming: incoming).look(known: known, announced: announced, spent: spent)
            known = look.known
            spent = look.spent
            announced.formUnion(look.arrivals.map(\.txid))
            return look.arrivals.map(\.txid)
        }
        func deposit(_ txid: String, _ inputs: Set<Input>) -> ReceiveChainSnapshot.Transaction {
            .init(txid: txid, toAddress: 5_000, received: 5_000, confirmedHeight: nil, inputs: inputs)
        }
        let x = Input(txid: "x", vout: 0), y = Input(txid: "y", vout: 0), b = deposit("B", [Input(txid: "fundB", vout: 0)])
        #expect(check([deposit("A", [x]), b]) == ["A", "B"])
        #expect(check([deposit("A2", [x, y]), b]).isEmpty)
        #expect(check([deposit("A3", [y]), b]).isEmpty)
        // A chain of three: D, then D2, D3 and D4, each spending only the coin the one before added.
        let d = (0..<4).map { Input(txid: "d\($0)", vout: 0) }
        #expect(check([deposit("D", [d[0]]), b]) == ["D"])
        #expect(check([deposit("D2", [d[0], d[1]]), b]).isEmpty)
        #expect(check([deposit("D3", [d[1], d[2]]), b]).isEmpty)
        #expect(check([deposit("D4", [d[2], d[3]]), b]).isEmpty)
        // A deposit spending other coins still arrives.
        #expect(check([deposit("D4", [d[2], d[3]]), b, deposit("C", [Input(txid: "fundC", vout: 0)])]) == ["C"])
    }

    /// The followed deposit disappears and a new one pays the same address without spending
    /// any of its inputs: no replacement is assumed. The old wait ends and the new deposit
    /// arrives as usual. Without recorded inputs nothing can replace it either.
    @Test func aNewDepositIsNeverAssumedToReplaceAGoneOne() {
        for spent in [Set([SendTransaction.Input(txid: "funding", vout: 1)]), []] {
            var queue = ReceiveArrivalQueue()
            queue.announce(ReceiveArrival(rail: .onchain, sats: 100_000, start: Date(), txid: "a", inputs: spent))
            let snapshot = ReceiveChainSnapshot(tip: 101, incoming: [
                .init(txid: "b", toAddress: 100_000, received: 100_000, confirmedHeight: nil, inputs: [.init(txid: "other", vout: 0)]),
            ])
            let followed = queue.follow(snapshot)
            #expect(followed.ended && !followed.confirmed && followed.txids.isEmpty && queue.current == nil)
            #expect(snapshot.look(known: ["a"], announced: Set(["a"]).union(followed.txids)).arrivals.map(\.txid) == ["b"])
        }
        // A waiting arrival that's gone leaves the queue; the next one plays.
        var queue = ReceiveArrivalQueue()
        let start = Date()
        queue.announce(ReceiveArrival(rail: .onchain, sats: 1, start: start, txid: "a"), at: start)
        queue.announce(ReceiveArrival(rail: .onchain, sats: 2, start: start, txid: "gone"), at: start)
        queue.announce(ReceiveArrival(rail: .onchain, sats: 3, start: start, txid: "c"), at: start)
        let both = ReceiveChainSnapshot(tip: 101, incoming: [
            .init(txid: "a", toAddress: 1, received: 1, confirmedHeight: nil), .init(txid: "c", toAddress: 3, received: 3, confirmedHeight: nil),
        ])
        let followed = queue.follow(both, at: start)
        #expect(!followed.ended && queue.current?.txid == "a" && queue.waiting.map(\.txid) == ["c"])
    }

    /// Receive's phase line redraws only at its boundaries, so every change falls on one.
    @Test func everyReceivePhaseChangeFallsOnABoundary() {
        let start = Date(), english = Locale(identifier: "en")
        for rail in [ReceiveArrival.Rail.ark, .lightning, .onchain] {
            var arrival = ReceiveArrival(rail: rail, sats: 1_500, start: start, txid: "tx")
            if rail == .onchain {
                arrival.confirmedHeight = 101
                arrival.confirmedAt = start.addingTimeInterval(12.3)
            }
            var last = arrival.phase(at: 0, tip: 100, locale: english).text
            for t in stride(from: 0.0, through: 30, by: 0.005) {
                let text = arrival.phase(at: t, tip: 100, locale: english).text
                guard text != last else { continue }
                last = text
                #expect(arrival.phaseBoundaries.contains { $0 <= t + 1e-9 && t - $0 < 0.006 }, "\(rail) changes at \(t)")
            }
        }
    }

    /// The block row's clock stops once nothing moves: after the drop under Reduce Motion,
    /// and after a confirmation either way. Only the waiting "next" block blinks.
    @Test func theBlockRowHoldsStillOnceSettled() throws {
        let start = Date()
        var arrival = ReceiveArrival(rail: .onchain, sats: 100_000, start: start, txid: "tx")
        #expect(ReceiveBlockRow.settleTime(arrival, reduceMotion: false) == nil)
        let waiting = try #require(ReceiveBlockRow.settleTime(arrival, reduceMotion: true))
        #expect(try Self.row(arrival, at: waiting, reduceMotion: true) == Self.row(arrival, at: waiting + 13.7, reduceMotion: true))
        #expect(try Self.row(arrival, at: 0.05, reduceMotion: true) != Self.row(arrival, at: waiting, reduceMotion: true))
        arrival.confirmedHeight = 101
        arrival.confirmedAt = start.addingTimeInterval(20)
        for reduceMotion in [false, true] {
            let end = try #require(ReceiveBlockRow.settleTime(arrival, reduceMotion: reduceMotion))
            #expect(try Self.row(arrival, at: end, reduceMotion: reduceMotion) == Self.row(arrival, at: end + 13.7, reduceMotion: reduceMotion))
        }
    }

    static func row(_ arrival: ReceiveArrival, at t: Double, reduceMotion: Bool) throws -> Data {
        let view = Canvas { context, size in
            ReceiveBlockRow.draw(MotionCanvas(context: context, size: size, viewBox: CGRect(x: 0, y: 0, width: 300, height: 58), colors: .theme(.light)),
                                 arrival: arrival, tip: 101, time: MotionTime(t: t, reduceMotion: reduceMotion), nextLabel: "next",
                                 locale: Locale(identifier: "en"))
        }
        .frame(width: 300, height: 58)
        return try #require(ImageRenderer(content: view).uiImage?.pngData())
    }

    @Test func arrivalsFlipOnlyAfterTheirPhasesAndPlayEachHapticOnce() {
        let start = Date()
        let lightning = ReceiveArrival(rail: .lightning, sats: 1_500, start: start)
        let english = Locale(identifier: "en")
        #expect(lightning.phase(at: 0.1, tip: nil, locale: english).text == "Incoming over Lightning")
        #expect(lightning.phase(at: 1.0, tip: nil, locale: english).text == "Ark holds it for you")
        #expect(lightning.phase(at: 2.0, tip: nil, locale: english).text == "Claiming with your key")
        #expect(lightning.phase(at: 2.6, tip: nil, locale: english) == ("Received", true))
        #expect(lightning.angle(at: 2.7) == 0 && lightning.angle(at: 3.5) > 90 && lightning.angle(at: 20) == 0)
        #expect(Set(lightning.haptics.map(\.key)).count == lightning.haptics.count)
        var onchain = ReceiveArrival(rail: .onchain, sats: 100_000, start: start, txid: "tx")
        #expect(onchain.phase(at: 2, tip: 100, locale: english).text == "Waiting for block 101")
        onchain.confirmedHeight = 101
        onchain.confirmedAt = start.addingTimeInterval(10)
        #expect(onchain.phase(at: 9, tip: 101, locale: english).text == "Waiting for block 102")
        #expect(onchain.phase(at: 11, tip: 101, locale: english) == ("Confirmed in block 101", true))
    }
}
