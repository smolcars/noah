import BarkBoltFFI
import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

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
            SendMotion.phase(rail: rail, outcome: outcome, outcomeAt: outcomeAt, at: $0, confirmedAt: confirmedAt)
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
        #expect(SendMotion.phase(rail: .onchainSwap, outcome: .succeeded, outcomeAt: 2, at: 29, confirmedAt: 30) == .waiting)
        #expect(SendMotion.phase(rail: .onchainSwap, outcome: .succeeded, outcomeAt: 2, at: 31, confirmedAt: 30) == .confirmed)
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
                var last = SendMotion.phase(rail: rail, outcome: outcome, outcomeAt: outcomeAt, at: 0, confirmedAt: confirmedAt)
                for t in stride(from: 0.0, through: 40, by: 0.005) {
                    let phase = SendMotion.phase(rail: rail, outcome: outcome, outcomeAt: outcomeAt, at: t, confirmedAt: confirmedAt)
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
        #expect(SendMotion.phase(rail: rail, outcome: .succeeded, outcomeAt: outcomeAt, at: t, confirmedAt: clock.since(clock.confirmedAt)) == end)
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
            _ = confirm()
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
            #"{"id":"fixture","txid":"\#(String(repeating: "cd", count: 32))","vsize":141,"inputCount":1,"createdAt":\#(Date().timeIntervalSinceReferenceDate)}"#
                .utf8))
        let wallet = SendPayment(destination: SendReceiveShotTests.bitcoin, method: "onchain", amount: 50_000, source: .onchain)
        func controller(_ result: Result<SendReceipt, Error>?, read: (() throws -> Attempt?)? = nil) -> SendPaymentController {
            let storage = SendPaymentTests.Storage()
            return SendPaymentController(dependencies: .init(
                prepare: { _, _ in .init(movementIDs: []) }, pay: { _, _, _ in try #require(result).get() },
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
        let sheet = RecurringPaymentsView.approval(
            review: .init(payment: SendPaymentTests().payment, quote: SendPaymentTests().quote),
            controller: controller,
            pay: { Issue.record("Close paid") },
            dismiss: { dismissed += 1 },
            done: { Issue.record("Close acknowledged") })
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
            model.stage = .amount // Back from To, as SendView's goBack does for an amount-first entry.
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
        // Each row: its label, the row itself (one line only, or wrapping) and its value alone.
        struct DetailRow { let label: String; let row: (Bool) -> AnyView; let value: AnyView }
        let column: CGFloat = 335
        var stacked = 0
        for locale in LegacyLocale.supported {
            let amount = BitcoinAmount.format(sats: 152_742, unit: .bip177, locale: Locale(identifier: locale))
            func text(_ label: String, _ value: String, verbatim: Bool = false, glyph: String? = nil) -> DetailRow {
                .init(label: label, row: { AnyView(SendDetailRow(LocalizedStringKey(label), text: value, verbatim: verbatim, glyph: glyph, wraps: $0)) },
                      value: AnyView(Group { if verbatim { Text(verbatim: value) } else { Text(LocalizedStringKey(value)) } }
                          .noahText(NoahTokens.Typography.label)))
            }
            func number(_ label: String) -> DetailRow {
                .init(label: label, row: { AnyView(SendDetailRow(LocalizedStringKey(label), number: amount, wraps: $0)) },
                      value: AnyView(Text(verbatim: amount).noahText(SendText.rowNumber).monospacedDigit()))
            }
            let rows = [SendMotion.Rail.ark, .lightning, .onchainSwap].map { text("Via", SendOutcomeView.railName($0), glyph: SendOutcomeView.railGlyph($0)) }
                + SendSource.allCases.map { text("From", $0.label) }
                + ["Fee", "Estimated fee"].map { text($0, "Free") }
                + ["Fee", "Estimated fee", "Total", "Estimated total", "Total (reviewed coins)"].map(number)
                + [text("Note", "Coffee and a croissant, thanks!", verbatim: true)]
            for type in Self.textSizes {
                for item in rows {
                    let label = item.label, row = item.row, value = item.value
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
