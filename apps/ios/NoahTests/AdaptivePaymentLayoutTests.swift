import Observation
import SwiftUI
import Testing
import UIKit
@testable import Noah

@Suite(.serialized)
@MainActor
struct AdaptivePaymentLayoutTests {
    actor PaymentGate {
        var calls = 0
        private var continuation: CheckedContinuation<SendReceipt, Never>?
        func pay() async -> SendReceipt {
            calls += 1
            return await withCheckedContinuation { continuation = $0 }
        }
        func finish() { continuation?.resume(returning: .init(amount: 500)); continuation = nil }
    }

    @Test func resizingReviewAndInFlightConfirmationRetainsOnePaymentAndReceipt() async throws {
        let gate = PaymentGate()
        let storage = SendPaymentTests.Storage()
        let controller = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: []) }, pay: { _, _, _ in await gate.pay() },
            reconcile: { _ in .uncertain }, read: { storage.attempt }, write: { try storage.write($0) }))
        let payment = SendPayment(destination: "layout@example.com", method: "ark", amount: 500)
        let quote = SendQuote(amount: 500, fee: 0, total: 500)
        let host = UIHostingController(rootView: SendConfirmation(
            payment: payment, quote: quote, controller: controller, cancel: {}, done: {}, onLeave: {}))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        window.rootViewController = host
        window.isHidden = false
        defer { window.isHidden = true; window.rootViewController = nil }
        try await Task.sleep(for: .milliseconds(100))
        window.frame.size = .init(width: 951, height: 669)
        host.view.layoutIfNeeded()
        #expect(controller.state == .ready && controller.attempt == nil)
        controller.submit(payment, quote: quote)
        try await SendPaymentTests.waitUntil { controller.state == .submitting }
        for size in [CGSize(width: 874, height: 402), .init(width: 669, height: 951),
                     .init(width: 402, height: 874), .init(width: 951, height: 669)] {
            window.frame.size = size
            host.view.setNeedsLayout()
            host.view.layoutIfNeeded()
            controller.submit(payment, quote: quote)
            try await Task.sleep(for: .milliseconds(30))
            #expect(controller.locked && controller.attempt?.payment == payment)
        }
        #expect(await gate.calls == 1)
        await gate.finish()
        try await SendPaymentTests.waitUntil { controller.state != .submitting }
        let receipt = controller.attempt?.receipt
        window.frame.size = .init(width: 402, height: 874)
        host.view.layoutIfNeeded()
        try await Task.sleep(for: .milliseconds(50))
        #expect(controller.state == .succeeded(.init(amount: 500)))
        #expect(controller.attempt?.receipt == receipt)
        #expect(await gate.calls == 1)
    }
}
