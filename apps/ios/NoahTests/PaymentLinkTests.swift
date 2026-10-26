import Foundation
import SwiftUI
import Testing
@testable import Noah

@MainActor
struct PaymentLinkTests {
    typealias Gate = PaymentLinkRoute.Gate
    static let invoice = "lnbcrt15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvtgjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwkwcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsqjqjd37"
    static let address = "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"
    static let ark = "tark1pwh9vsmezqqpjy9akejayl2vvcse6he97rn40g84xrlvrlnhayuuyefrp9nse2yspqqkm2rpd"

    /// Funds take a fixed-amount request straight to review, as with a single funded rail today.
    private func send(_ controller: SendPaymentController? = nil, funds: UInt64 = 0,
                      quote: @escaping (SendPayment) async -> SendQuote = { .init(amount: $0.amount, fee: 0, total: $0.amount) }) -> SendModel {
        let model = SendModel(controller: controller ?? SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage()),
                              resolveRecipient: { .init(destination: $0, minMsat: 1000, maxMsat: 1_000_000_000, commentAllowed: 0) },
                              quotePayment: { await quote($0) }, discardQuote: { _ in })
        model.arkBalance = funds
        return model
    }

    /// The link the OS hands over, through the route, as `WalletTabs` takes it.
    private func handOver(_ value: String) throws -> String? {
        let route = PaymentLinkRoute()
        route.receive(try #require(URL(string: value)), gate: .open)
        return route.take(.open)
    }

    @Test func everySchemeAndItsUppercaseFormReachSendAsAScanWould() async throws {
        let links = [
            "bitcoin:\(Self.address)?amount=0.0001",
            "bitcoin:\(Self.address)?amount=0.0001&label=Coffee%20shop",
            "BITCOIN:\(Self.address.uppercased())?AMOUNT=0.0001",
            "bitcoin:\(Self.address)?ark=\(Self.ark)&amount=0.0001",
            "lightning:\(Self.invoice)",
            "LIGHTNING:\(Self.invoice.uppercased())",
            "lnurlp://example.com/.well-known/lnurlp/alice",
            "LNURLP://example.com/.well-known/lnurlp/alice",
        ]
        for value in links {
            let link = try #require(try handOver(value))
            #expect(link == value) // Case and percent-encoding reach the parser untouched.
            let scanned = send(), linked = send()
            #expect(await QRScanRoute.apply(value, to: scanned))
            #expect(await QRScanRoute.apply(link, to: linked))
            #expect(linked.error == nil, "\(value): \(linked.error ?? "")")
            #expect(linked.destination == scanned.destination)
            #expect(linked.parsed == scanned.parsed)
            #expect(linked.stage == scanned.stage)
            #expect(linked.amount == scanned.amount)
        }
        // Not a payment request this route opens: Noah's own scheme, withdraw and auth links.
        for other in ["com.noahwallet.regtest://open", "lnurlw://example.com/w", "keyauth://example.com/a", "https://example.com"] {
            #expect(try handOver(other) == nil)
        }
    }

    @Test func heldThroughLaunchAndTheLockThenAppliedOnceAfterUnlock() async throws {
        var lock = BiometricLockPolicy(enabled: true)
        let route = PaymentLinkRoute()
        let value = "lightning:\(Self.invoice)"
        route.receive(try #require(URL(string: value)), gate: Gate(phase: .launching, onboarding: false, gated: false, settling: false, locked: lock.locked))
        #expect(route.pending == value)
        var gate: Gate { Gate(phase: .wallet, onboarding: false, gated: false, settling: false, locked: lock.locked) }
        #expect(gate == .hold)
        #expect(route.take(gate) == nil)
        #expect(route.pending == value)

        lock.authenticated(generation: lock.generation)
        #expect(gate == .open)
        let link = try #require(route.take(gate))
        let model = send()
        #expect(await QRScanRoute.apply(link, to: model))
        #expect(model.destination == value && model.error == nil)

        // The scene going to the background and coming back re-evaluates the gate; nothing is left to apply.
        lock.background()
        #expect(route.take(gate) == nil)
        lock.authenticated(generation: lock.generation)
        #expect(route.take(gate) == nil)
        #expect(route.pending == nil)
    }

    @Test func ignoredWithoutAWalletOrBehindALaunchGate() async throws {
        SettingsStorage.bootstrap()
        let url = try #require(URL(string: "bitcoin:\(Self.address)?amount=0.0001"))
        for state in [LaunchState.onboarding, .invalidSeed, .walletDataWithoutSeed] {
            let model = AppModel(readLaunchState: { state }, settleRestore: { .nothingPending })
            model.paymentLink.receive(url, gate: model.paymentLinkGate) // Cold start: held while launching.
            #expect(model.paymentLink.pending != nil)
            await model.start()
            #expect(model.paymentLinkGate == .ignore)
            #expect(model.paymentLink.take(model.paymentLinkGate) == nil) // RootView drops it on the change.
            model.paymentLink.receive(url, gate: model.paymentLinkGate)
            #expect(model.paymentLink.pending == nil)
        }
        // Suspended or notifications required (`gated`), restore's closing onboarding moment, and the other phases.
        for locked in [false, true] {
            #expect(Gate(phase: .wallet, onboarding: false, gated: true, settling: false, locked: locked) == .ignore)
            #expect(Gate(phase: .wallet, onboarding: true, gated: false, settling: false, locked: locked) == .ignore)
            #expect(Gate(phase: .restoreIncomplete, onboarding: false, gated: false, settling: false, locked: locked) == .ignore)
            #expect(Gate(phase: .blocked(.secureStorageUnavailable), onboarding: false, gated: false, settling: false, locked: locked) == .ignore)
        }
        let route = PaymentLinkRoute()
        route.receive(url, gate: .hold)
        #expect(route.take(.ignore) == nil) // A held link meeting a gate is gone, not kept for later.
        #expect(route.take(.open) == nil)
    }

    @Test func aPaymentInFlightBlocksALinkWithItsOwnMessage() async throws {
        let backend = SendPaymentTests.Backend()
        let controller = SendPaymentTests().make(backend, SendPaymentTests.Storage())
        controller.submit(SendPaymentTests().payment, quote: SendPaymentTests().quote)
        try await SendPaymentTests.waitUntil { controller.state == .uncertain }
        let model = send(controller)
        model.destination = "original@example.com"
        let link = try #require(try handOver("lightning:\(Self.invoice)"))
        #expect(!(await PaymentLinkRoute.apply(link, to: model)))
        #expect(model.destination == "original@example.com")
        #expect(await backend.calls == 1)
        #expect(WalletTabs.blockedMessage(link: true) != WalletTabs.blockedMessage(link: false))
    }

    @Test func aReviewBeingPreparedOrShownRefusesALinkAndStaysAsItIs() async throws {
        var quoting: CheckedContinuation<SendQuote, Never>?
        let model = send(funds: 1_000_000, quote: { payment in
            if quoting == nil { return await withCheckedContinuation { quoting = $0 } } // Only the first review waits.
            return .init(amount: payment.amount, fee: 0, total: payment.amount)
        })
        let invoice = "lightning:\(Self.invoice)", other = "bitcoin:\(Self.address)?amount=0.0001"
        let first = Task { await PaymentLinkRoute.apply(invoice, to: model) }
        while quoting == nil { await Task.yield() }
        #expect(model.stage == .review && model.busy) // Preparing the review.
        #expect(!(await PaymentLinkRoute.apply(other, to: model)))
        quoting?.resume(returning: .init(amount: 1500, fee: 3, total: 1503))
        #expect(await first.value)

        #expect(model.stage == .review && model.showReview) // Showing it.
        let quote = model.quote, payment = model.payment
        #expect(quote != nil && payment != nil)
        #expect(!(await PaymentLinkRoute.apply(other, to: model)))
        #expect(model.destination == invoice && model.quote == quote && model.payment == payment && model.showReview)
        // Scanning keeps today's rule: a scan still replaces a review that isn't sending.
        #expect(await QRScanRoute.apply(other, to: model))
        #expect(model.destination == other)
    }

    @Test func anOpenRecurringApprovalRefusesALinkAndItsSheetSaysWhy() throws {
        let route = PaymentLinkRoute()
        route.approvalOpen = true
        route.receive(try #require(URL(string: "lightning:\(Self.invoice)")), gate: .open)
        #expect(route.take(.open) == nil) // Send never sees it, so the tabs stay as they are.
        #expect(route.approvalBlocked)
        #expect(route.pending == nil) // Refused, not kept for after the approval.
        route.approvalOpen = false
        #expect(route.take(.open) == nil)
    }

    @Test func aLinkFromBehindAGateStaysGoneWhenTheGateClearsBeforeUnlock() throws {
        let url = try #require(URL(string: "bitcoin:\(Self.address)?amount=0.0001"))
        var lock = BiometricLockPolicy(enabled: true)
        var gated = true // Suspended or notifications required, behind the lock.
        var gate: Gate { Gate(phase: .wallet, onboarding: false, gated: gated, settling: false, locked: lock.locked) }
        let route = PaymentLinkRoute()
        route.receive(url, gate: gate)
        #expect(gate == .ignore && route.pending == nil)
        gated = false
        #expect(gate == .hold)
        lock.authenticated(generation: lock.generation)
        #expect(gate == .open)
        #expect(route.take(gate) == nil)
        // A launch gate (recovery) decides the same way while locked.
        route.receive(url, gate: Gate(phase: .blocked(.walletDataWithoutSeed), onboarding: false, gated: false, settling: false, locked: true))
        #expect(route.pending == nil)
    }

    @Test func twoLinksInARow() async throws {
        let invoice = "lightning:\(Self.invoice)", other = "bitcoin:\(Self.address)?amount=0.0001"
        // Held while locked: the later link replaces the earlier and is handed over once.
        let route = PaymentLinkRoute()
        route.receive(try #require(URL(string: invoice)), gate: .hold)
        route.receive(try #require(URL(string: other)), gate: .hold)
        #expect(route.take(.open) == other)
        #expect(route.take(.open) == nil)
        // A draft before review follows scanning: the second link replaces it.
        let draft = send()
        #expect(await PaymentLinkRoute.apply(invoice, to: draft))
        #expect(draft.stage == .method) // No funds: How to send.
        #expect(await PaymentLinkRoute.apply(other, to: draft))
        #expect(draft.destination == other)
        // The review the first link opened refuses the second.
        let funded = send(funds: 1_000_000)
        #expect(await PaymentLinkRoute.apply(invoice, to: funded))
        #expect(funded.showReview)
        #expect(!(await PaymentLinkRoute.apply(other, to: funded)))
        #expect(funded.destination == invoice && funded.showReview)
    }

    @Test func theCustomServerMessageDropsALinkEvenWhileLockedAndRecoveryDoesNotBringItBack() throws {
        let url = try #require(URL(string: "lightning:\(Self.invoice)"))
        var lock = BiometricLockPolicy(enabled: true)
        let route = PaymentLinkRoute()
        // "The custom server isn't responding" shows while launching; AppModel passes it as a known gate.
        let arrival = Gate(phase: .launching, onboarding: false, gated: true, settling: false, locked: lock.locked)
        #expect(arrival == .ignore) // A known gate wins over launching and the lock.
        route.receive(url, gate: arrival)
        #expect(route.pending == nil)
        // The server recovers and the wallet opens, still locked; then the user unlocks.
        var gate: Gate { Gate(phase: .wallet, onboarding: false, gated: false, settling: false, locked: lock.locked) }
        #expect(gate == .hold && route.take(gate) == nil)
        lock.authenticated(generation: lock.generation)
        #expect(gate == .open && route.take(gate) == nil)
        // Only the lock or an unanswered gate holds a link.
        #expect(Gate(phase: .wallet, onboarding: false, gated: false, settling: true, locked: false) == .hold)
    }
}
