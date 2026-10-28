import Foundation
@testable import Noah
import Testing

/// The widgets' routes (docs/native-rewrite/design/widgets.md, "Opening the app from a widget").
@MainActor
struct WidgetRouteTests {
    typealias Gate = PaymentLinkRoute.Gate

    private func send(_ controller: SendPaymentController? = nil) -> SendModel {
        let model = SendModel(controller: controller ?? SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage()),
                              resolveRecipient: { .init(destination: $0, minMsat: 1000, maxMsat: 1_000_000_000, commentAllowed: 0) },
                              quotePayment: { .init(amount: $0.amount, fee: 0, total: $0.amount) }, discardQuote: { _ in })
        model.arkBalance = 1_000_000
        return model
    }

    @Test func theFourRoutesOnTheVariantsScheme() throws {
        for (path, destination) in [("home", WidgetRoute.Destination.home), ("receive", .receive), ("scan", .scan), ("send", .send)] {
            let url = try #require(URL(string: "com.noahwallet.regtest://widget/\(path)"))
            #expect(WidgetRoute.destination(url, variant: .regtest) == destination)
            #expect(WidgetRoute.destination(url, variant: .mainnet) == nil) // Another variant's scheme.
        }
        for other in ["com.noahwallet.regtest://widget/pay", "com.noahwallet.regtest://open", "com.noahwallet.regtest://widget",
                      "bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22", "https://noahwallet.com/widget/send"] {
            #expect(WidgetRoute.destination(try #require(URL(string: other)), variant: .regtest) == nil, "\(other)")
        }
        // A widget's tap is not a payment link.
        let links = PaymentLinkRoute()
        links.receive(try #require(URL(string: "com.noahwallet.regtest://widget/send")), gate: .open)
        #expect(links.pending == nil)
    }

    @Test func heldWhileLockedThenAppliedOnce() throws {
        var lock = BiometricLockPolicy(enabled: true)
        var gate: Gate { Gate(phase: .wallet, onboarding: false, gated: false, settling: false, locked: lock.locked) }
        let route = WidgetRoute()
        route.receive(try #require(URL(string: "com.noahwallet.regtest://widget/receive")),
                      gate: Gate(phase: .launching, onboarding: false, gated: false, settling: false, locked: lock.locked), variant: .regtest)
        #expect(gate == .hold)
        #expect(route.take(gate) == nil)
        #expect(route.pending == .receive)
        lock.authenticated(generation: lock.generation)
        #expect(route.take(gate) == .receive)
        // Recreation, a fold or coming back from the background finds nothing to repeat.
        #expect(route.take(gate) == nil)
        lock.background()
        lock.authenticated(generation: lock.generation)
        #expect(route.take(gate) == nil)
    }

    @Test func ignoredWithoutAWalletOrBehindALaunchGate() async throws {
        SettingsStorage.bootstrap()
        let url = try #require(URL(string: "com.noahwallet.\(AppVariant.current.rawValue)://widget/send"))
        for state in [LaunchState.onboarding, .invalidSeed, .walletDataWithoutSeed] {
            let model = AppModel(readLaunchState: { state }, settleRestore: { .nothingPending })
            model.widgetRoute.receive(url, gate: model.paymentLinkGate, variant: model.variant) // Cold start: held while launching.
            #expect(model.widgetRoute.pending == .send)
            await model.start()
            #expect(model.paymentLinkGate == .ignore)
            #expect(model.widgetRoute.take(model.paymentLinkGate) == nil) // RootView drops it on the change.
            model.widgetRoute.receive(url, gate: model.paymentLinkGate, variant: model.variant)
            #expect(model.widgetRoute.pending == nil)
        }
        let route = WidgetRoute()
        route.receive(url, gate: .hold, variant: .current)
        #expect(route.take(.ignore) == nil) // Gone, not kept for after the gate clears.
        #expect(route.take(.open) == nil)
    }

    /// Scan opens the camera only over a draft; a payment in flight or a review stays exactly as it is.
    @Test func scanNeverDiscardsAPaymentInProgress() async throws {
        let draft = send()
        draft.destination = "draft@example.com"
        #expect(WidgetRoute.scans(draft))
        #expect(draft.destination == "draft@example.com")

        let backend = SendPaymentTests.Backend()
        let controller = SendPaymentTests().make(backend, SendPaymentTests.Storage())
        controller.submit(SendPaymentTests().payment, quote: SendPaymentTests().quote)
        try await SendPaymentTests.waitUntil { controller.state == .uncertain }
        let inFlight = send(controller)
        #expect(!WidgetRoute.scans(inFlight))

        let reviewing = send()
        #expect(await QRScanRoute.apply("lightning:\(PaymentLinkTests.invoice)", to: reviewing))
        #expect(reviewing.stage == .review)
        #expect(!WidgetRoute.scans(reviewing))
        #expect(reviewing.stage == .review && reviewing.quote != nil)
        #expect(await backend.calls == 1)
    }

    /// While Send has a payment under way, Home and Receive leave the app as it is; Send and Scan show Send as it
    /// is; an open recurring approval keeps everything.
    @Test func routesNeverLeaveAPaymentUnderWay() async throws {
        let draft = send()
        let backend = SendPaymentTests.Backend()
        let controller = SendPaymentTests().make(backend, SendPaymentTests.Storage())
        controller.submit(SendPaymentTests().payment, quote: SendPaymentTests().quote)
        try await SendPaymentTests.waitUntil { controller.state == .uncertain }
        let inFlight = send(controller)
        let preparing = send()
        preparing.busy = true
        preparing.stage = .review
        let reviewing = send()
        #expect(await QRScanRoute.apply("lightning:\(PaymentLinkTests.invoice)", to: reviewing))
        let destinations: [WidgetRoute.Destination] = [.home, .receive, .send, .scan]
        #expect(destinations.map { WidgetRoute.tab(for: $0, send: draft, approvalOpen: false) } == [.home, .receive, .send, .send])
        for model in [inFlight, preparing, reviewing] {
            #expect(destinations.map { WidgetRoute.tab(for: $0, send: model, approvalOpen: false) } == [nil, nil, .send, .send])
        }
        for model in [draft, inFlight, reviewing] {
            #expect(destinations.allSatisfy { WidgetRoute.tab(for: $0, send: model, approvalOpen: true) == nil })
        }
    }
}
