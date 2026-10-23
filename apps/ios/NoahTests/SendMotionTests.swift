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
                payment: payment, quote: quote, controller: controller, cancel: {}, done: {}, gate: hold))
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

    /// The card as drawn decodes to exactly the shown format, in both themes, with the logo.
    @Test(arguments: [ColorScheme.light, .dark])
    func theShownQRDecodesToTheChosenFormat(scheme: ColorScheme) throws {
        for request in [Self.plain, Self.priced] {
            for format in ReceiveFormat.allCases {
                guard let payload = format.payload(request: request, address: Self.address) else { continue }
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
