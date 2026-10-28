import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

/// Opt-in screenshot fixtures for Send and Receive: the real tab shell with a scripted
/// payment controller, a fake receive model and stand-ins for wallet reads (no wallet,
/// server writes or recovery phrase). Write a state name to Documents/sr-shot; "stop" ends
/// the session. Send: amount, amount-typed, amount-over, amount-recipient, to, to-typing, to-error, to-nomatch,
/// method, source, review-ark, review-lightning, review-onchain, review-note, review-max, sending-ark, sent-ark,
/// sent-lightning, slow-lightning, pending-lightning, refunded, failed, sent-swap,
/// confirm-swap, sent-wallet, uncertain, already-paid, unavailable, broadcast-pending.
/// Receive: receive, receive-noaddress, receive-amount, receive-invoice, receive-ark,
/// receive-bitcoin, receive-formats, receive-amount-sheet, receive-error, arrive-ark,
/// arrive-lightning, arrive-onchain, arrive-failed. A "-down" suffix scrolls the screen down a page.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SR_SHOTS"] == "1"))
struct SendReceiveShotTests {
    nonisolated static let invoice = "lnbcrt15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzj"
        + "csp5f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvtgjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2"
        + "zzmwrmrl70fyj9hvj0rewhzj7jfyuwkwcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsqjqjd37"
    nonisolated static let bitcoin = "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202"
    nonisolated static let ark = "tark1pwh9vsmezqqpjy9akejayl2vvcse6he97rn40g84xrlvrlnhayuuyefrp9nse2yspqqkm2rpd"
    nonisolated static let tip: UInt32 = 921_345
    static var request: String { "bitcoin:\(bitcoin)?amount=0.000015&lightning=\(invoice)&ark=\(ark)" }

    @Test(.timeLimit(.minutes(60))) func presentStates() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.keyWindow)
        let original = window.rootViewController
        let folder = URL.documentsDirectory
        let request = folder.appending(path: "sr-shot"), ready = folder.appending(path: "sr-shot-ready")
        let host = UIHostingController(rootView: AnyView(Color.clear))
        window.rootViewController = host
        var roots: [URL] = []
        defer {
            window.rootViewController = original
            try? FileManager.default.removeItem(at: ready)
            try? FileManager.default.removeItem(at: request)
            for root in roots { try? FileManager.default.removeItem(at: root) }
        }
        var current = ""
        let deadline = ContinuousClock.now.advanced(by: .seconds(3500))
        while ContinuousClock.now < deadline {
            let state = (try? String(contentsOf: request, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if state == "stop" { return }
            if !state.isEmpty && state != current {
                host.rootView = AnyView(Color.clear)
                try await Task.sleep(for: .milliseconds(150))
                let down = state.hasSuffix("-down")
                let shown = try await fixture(down ? String(state.dropLast("-down".count)) : state)
                roots.append(shown.root)
                host.rootView = shown.view
                try await Task.sleep(for: .milliseconds(700))
                await shown.after()
                if down { Self.pageDown(host.view) }
                try state.write(to: ready, atomically: true, encoding: .utf8)
                current = state
            }
            try await Task.sleep(for: .milliseconds(150))
        }
    }

    /// Scrolls every scroll view under `view` down a page (at most to its end), for rows below the fold at large text sizes.
    static func pageDown(_ view: UIView) {
        if let scroll = view as? UIScrollView {
            let end = scroll.contentSize.height + scroll.adjustedContentInset.bottom - scroll.bounds.height
            let page = scroll.contentOffset.y + scroll.bounds.height * 0.8
            scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: max(scroll.contentOffset.y, min(page, end))), animated: false)
        }
        view.subviews.forEach(pageDown)
    }

    /// Plays one payment: waits, then returns its receipt or throws.
    actor Script {
        var delay: Double = 1
        var result: Result<SendReceipt, Error>?
        var resolution: SendPaymentController.Resolution = .uncertain
        var resolutionAfterCheck: SendPaymentController.Resolution?
        var checks = 0
        func configure(delay: Double, result: Result<SendReceipt, Error>?, resolution: SendPaymentController.Resolution = .uncertain,
                       later: SendPaymentController.Resolution? = nil) {
            self.delay = delay
            self.result = result
            self.resolution = resolution
            resolutionAfterCheck = later
        }
        func pay() async throws -> SendReceipt {
            try await Task.sleep(for: .seconds(delay))
            guard let result else {
                // Never settles: the call stays in flight.
                try await Task.sleep(for: .seconds(3600))
                throw CancellationError()
            }
            return try result.get()
        }
        func reconcile() async -> SendPaymentController.Resolution {
            checks += 1
            try? await Task.sleep(for: .seconds(1.2))
            return checks > 1 ? resolutionAfterCheck ?? resolution : resolution
        }
    }

    final class Storage: @unchecked Sendable {
        var attempt: SendPaymentController.Attempt?
        var blocked = false
    }

    static func summary(ark: UInt64 = 152_742, onchain: UInt64 = 60_000) -> WalletSession.Summary {
        .init(network: .regtest, fingerprint: "fixture", balance: HomeRulesTests.balance(ark: ark, onchain: onchain), movementCount: 0)
    }

    private struct Shown {
        let view: AnyView
        let root: URL
        let after: () async -> Void
    }

    private func fixture(_ state: String) async throws -> Shown {
        let root = FileManager.default.temporaryDirectory.appending(path: "sr-shot-\(UUID())")
        let settings = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        var server = PersistedServerState()
        server.lightningAddress = state == "receive-noaddress" ? nil : "maya@localhost.com"
        server.isEmailVerified = true
        try settings.setPersistedState(server, key: PersistedServerState.key, version: PersistedServerState.version)
        let app = AppModel(settings: settings)
        let rows = [
            HomeShotTests.row("m1", "Lnurl", 2_500, "outgoing", HomeShotTests.at(0, 9, 12), to: "lucas@ln.tips"),
            HomeShotTests.row("m2", "Lnurl", 500, "outgoing", HomeShotTests.at(1, 18, 5), to: "nitesh@localhost.com"),
            HomeShotTests.row("m3", "Arkoor", 1_200, "outgoing", HomeShotTests.at(2, 8, 0), to: "bob@getalby.com"),
            HomeShotTests.row("m4", "Onchain", 50_000, "outgoing", HomeShotTests.at(3, 14, 0), to: Self.bitcoin, kind: "send-onchain"),
            HomeShotTests.row("m5", "Bolt11", 4_200, "outgoing", HomeShotTests.at(4, 10, 0), to: Self.invoice),
        ]
        let history = HistoryModel(dependencies: .init(
            load: { rows }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } },
            blockTime: { _ in nil }, price: { _, _ in nil }))
        await history.refresh(currency: app.preferredCurrency)
        let home = HomeModel(dependencies: .init(
            price: { _ in 112_000 }, rounds: { [] }, backup: { PersistedBackupState() }, retryBackup: {},
            autoBoards: { [] }, backgroundSnapshot: { .init(isRunning: false, completionRevision: 0) }, renewal: { nil }))

        let script = Script()
        let storage = Storage()
        if state == "unavailable" { storage.blocked = true }
        let controller = SendPaymentController(dependencies: .init(
            prepare: { _, _ in SendSnapshot(movementIDs: []) },
            pay: { _, _, _ in try await script.pay() },
            reconcile: { _ in await script.reconcile() },
            read: {
                if storage.blocked { throw CocoaError(.fileReadCorruptFile) }
                return storage.attempt
            },
            write: { storage.attempt = $0 }))
        let send = SendModel(controller: controller, resolveRecipient: { destination in
            SendRecipientRoute(destination: destination, minMsat: 1000, maxMsat: 100_000_000_000, commentAllowed: 140,
                               arkAddress: Self.ark, callback: URL(string: "https://localhost.com/cb"), metadata: nil)
        }, quotePayment: { payment in SendQuote(amount: payment.amount, fee: 0, total: payment.amount) }, discardQuote: { _ in })

        let fixture = SendReceiveFixture(summary: Self.summary(), tip: Self.tip, refunded: state == "refunded" ? true : nil,
                                         history: [], fees: [.ark: 0, .lightning: 12, .onchain: 420])
        var staging = Staging(send: send, script: script, receive: receiveModel(state), fixture: fixture)
        try await Self.stage(state, &staging)
        let view = WalletTabs(history: history, home: home, send: send, receive: staging.receive, tab: staging.tab)
            .environment(app)
            .environment(app.walletLock)
            .environment(\.homeFixture, HomeFixture(summary: Self.summary(), walletError: nil, needsRecoveryPhraseBackup: false))
            .environment(\.sendReceiveFixture, staging.fixture)
            .environment(\.locale, Locale(identifier: app.localeIdentifier))
            .environment(\.scenePhase, .active)
        return Shown(view: AnyView(view), root: root, after: staging.after)
    }

    /// A fixture state's models, and what the state changes beyond the defaults.
    @MainActor private struct Staging {
        let send: SendModel
        let script: Script
        let receive: ReceiveModel
        var fixture: SendReceiveFixture
        var tab = WalletTabs.Tab.send
        var after: () async -> Void = {}

        func review(_ payment: SendPayment, _ quote: SendQuote, destination: String) {
            send.amount = String(payment.amount)
            send.destination = destination
            send.parsed = SendDestination.parse(destination)
            send.payment = payment
            send.quote = quote
            send.stage = .review
            send.showReview = true
        }
        func submit(after delay: Double = 0.4) -> () async -> Void {
            let send = send
            return {
                try? await Task.sleep(for: .seconds(delay))
                if let payment = send.payment, let quote = send.quote { send.controller.submit(payment, quote: quote) }
            }
        }
    }

    static let arkPayment = SendPayment(destination: ark, method: "ark", amount: 2_500,
                                        repeatMetadata: try? SendPayment.metadata(
                                            destination: "maya@localhost.com",
                                            comment: "",
                                            amount: "2500",
                                            fiatCurrency: nil))
    static let lightningPayment = SendPayment(destination: invoice, method: "lightning", amount: 1_500)
    static let swapPayment = SendPayment(destination: bitcoin, method: "onchain", amount: 50_000, source: .offchain)
    static let walletPayment = SendPayment(destination: bitcoin, method: "onchain", amount: 50_000, source: .onchain)
    static let lightningReceipt = SendReceipt(amount: 1_500, paymentHash: PaymentEncoding.invoice(invoice)?.hash, movementID: 7, fee: 3,
                                              proof: .actualMovement)
    static let swapReceipt = SendReceipt(amount: 50_000, txid: String(repeating: "ab", count: 32), status: .submitted, fee: 420,
                                         proof: .submitReturnedTxid)

    private static func stage(_ state: String, _ staging: inout Staging) async throws {
        if stageEntry(state, &staging) { return }
        if try await stageReview(state, &staging) { return }
        if await stageOutcome(state, &staging) { return }
        stageReceive(state, &staging)
    }

    /// Amount and recipient entry.
    private static func stageEntry(_ state: String, _ staging: inout Staging) -> Bool {
        let send = staging.send
        switch state {
        case "amount": break
        case "amount-typed": send.amount = "2500"
        case "amount-over": send.amount = "250000"
        case "amount-recipient":
            // 700 typed, then a pasted destination without an amount: the keypad keeps its turn, recipient shown.
            send.amount = "700"
            staging.after = { await send.importRecipient(Self.ark) }
        case "to", "to-typing", "to-error", "to-nomatch":
            send.amount = "2500"
            send.progress.amountConfirmed = true
            send.stage = .recipient
            send.destination = ["to-typing": "maya@localhost.com", "to-error": "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                                "to-nomatch": "zz"][state] ?? ""
        case "method":
            staging.after = { await send.importRecipient(Self.request) }
        case "source":
            staging.after = { send.maxSend() }
        default: return false
        }
        return true
    }

    /// Review, and the sends that start from it.
    private static func stageReview(_ state: String, _ staging: inout Staging) async throws -> Bool {
        switch state {
        case "review-ark", "sending-ark", "sent-ark":
            staging.review(arkPayment, SendQuote(amount: 2_500, fee: 0, total: 2_500), destination: "maya@localhost.com")
            if state != "review-ark" {
                await staging.script.configure(delay: state == "sent-ark" ? 0.8 : 1, result: state == "sent-ark"
                    ? .success(SendReceipt(amount: 2_500, fee: 0, proof: .submitReturnedArkSuccess)) : nil)
                staging.after = staging.submit()
            }
        case "review-lightning", "sent-lightning", "slow-lightning", "pending-lightning", "refunded":
            await stageLightning(state, &staging)
        case "review-onchain", "sent-wallet", "broadcast-pending":
            var quote = SendQuote(amount: 50_000, fee: 420, total: 50_420, feeRate: 2)
            quote.prepared = try JSONDecoder().decode(SendPreparedTransaction.self, from: Data(
                #"{"id":"fixture","txid":"\#(String(repeating: "cd", count: 32))","vsize":141,"inputCount":1,"createdAt":\#(Date().timeIntervalSinceReferenceDate)}"#
                    .utf8))
            staging.review(walletPayment, quote, destination: bitcoin)
            if state != "review-onchain" {
                var receipt = SendReceipt(amount: 50_000, txid: String(repeating: "cd", count: 32), status: .submitted, fee: 420, proof: .preparedTxid)
                if state == "broadcast-pending" { receipt.broadcastRetryPending = true }
                await staging.script.configure(delay: 0.6, result: .success(receipt), resolution: .paid(receipt))
                staging.after = staging.submit()
            }
        case "review-note":
            // A Lightning-address send with a note: Via, Arrives, Fee, Total and Note.
            staging.review(SendPayment(destination: "maya@localhost.com", method: "lightning", amount: 1_500, comment: "Coffee and a croissant, thanks!"),
                           SendQuote(amount: 1_500, fee: 12, total: 1_512), destination: "maya@localhost.com")
        case "review-max":
            // An Ark-funded on-chain Max send: the longest labels (From, Estimated fee, Total (reviewed coins)).
            staging.review(SendPayment(destination: bitcoin, method: "onchain", amount: 152_100, source: .offchain, max: true),
                           SendQuote(amount: 152_100, fee: 642, total: 152_742, vtxoIDs: ["fixture:0"]), destination: bitcoin)
        default: return false
        }
        return true
    }

    private static func stageLightning(_ state: String, _ staging: inout Staging) async {
        staging.review(lightningPayment, SendQuote(amount: 1_500, fee: 12, total: 1_512), destination: invoice)
        staging.fixture.history = [{
            var row = HomeShotTests.row("movement-7", "Bolt11", 1_500, "outgoing", HomeShotTests.at(0, 9, 30), to: invoice)
            row.movementId = 7
            row.preimage = "9f2c6b0e7d4a1c3e5f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4a41e"
            return row
        }()]
        switch state {
        case "sent-lightning":
            await staging.script.configure(delay: 1.6, result: .success(lightningReceipt))
            staging.after = staging.submit()
        case "slow-lightning":
            await staging.script.configure(delay: 1, result: nil)
            staging.after = staging.submit()
        case "pending-lightning":
            await staging.script.configure(delay: 3, result: .failure(URLError(.timedOut)), resolution: .inProgress(.init(amount: 1_500, fee: 12)))
            staging.after = staging.submit()
        case "refunded":
            await staging.script.configure(delay: 3, result: .failure(URLError(.timedOut)), resolution: .failed)
            staging.after = staging.submit()
        default: break
        }
    }

    /// Sends that end somewhere other than a plain success.
    private static func stageOutcome(_ state: String, _ staging: inout Staging) async -> Bool {
        switch state {
        case "sent-swap", "confirm-swap":
            staging.review(swapPayment, SendQuote(amount: 50_000, fee: 420, total: 50_420), destination: bitcoin)
            var confirmed = swapReceipt
            confirmed.chainStatus = .confirmed(tip + 1)
            confirmed.status = .confirmed
            await staging.script.configure(delay: 1, result: .success(swapReceipt),
                                           resolution: state == "confirm-swap" ? .paid(confirmed) : .paid(swapReceipt))
            if state == "confirm-swap" { staging.fixture.tip = tip + 1 }
            staging.after = staging.submit()
        case "failed":
            staging.review(arkPayment, SendQuote(amount: 2_500, fee: 0, total: 2_500), destination: "maya@localhost.com")
            await staging.script.configure(delay: 1, result: .failure(SendNotSubmitted(reason: .fundsChanged)))
            staging.after = staging.submit()
        case "uncertain":
            staging.review(arkPayment, SendQuote(amount: 2_500, fee: 0, total: 2_500), destination: "maya@localhost.com")
            await staging.script.configure(delay: 1, result: .failure(URLError(.timedOut)), resolution: .candidates([]))
            staging.after = staging.submit()
        case "already-paid":
            staging.review(lightningPayment, SendQuote(amount: 1_500, fee: 12, total: 1_512), destination: invoice)
            await staging.script.configure(delay: 1, result: .failure(SendAlreadyPaid(receipt: lightningReceipt)))
            staging.after = staging.submit()
        case "unavailable": break
        default: return false
        }
        return true
    }

    private static func stageReceive(_ state: String, _ staging: inout Staging) {
        staging.tab = .receive
        switch state {
        case "receive-invoice": staging.fixture.receiveFormat = .invoice
        case "receive-ark": staging.fixture.receiveFormat = .ark
        case "receive-bitcoin": staging.fixture.receiveFormat = .bitcoin
        case "receive-formats": staging.fixture.receiveSheet = "formats"
        case "receive-amount-sheet", "receive-saving": staging.fixture.receiveSheet = "amount"
        case "arrive-onchain": staging.fixture.chain = chain()
        default: break
        }
        if ["receive-amount", "receive-invoice", "arrive-lightning"].contains(state) {
            let receive = staging.receive
            staging.after = { _ = await receive.save(amountSat: 1_500, description: "") }
        }
    }

    private func receiveModel(_ state: String) -> ReceiveModel {
        let addresses = ReceiveRequest(arkAddress: Self.ark, onchainAddress: Self.bitcoin)
        let completion: ReceiveCompletion? = switch state {
        case "arrive-ark": .received(1_500, rail: .ark)
        case "arrive-lightning": .received(1_500, rail: .lightning)
        case "arrive-failed": .lightningFailed(canceled: false)
        default: nil
        }
        let delay: Double = state == "arrive-lightning" ? 4 : 3
        return ReceiveModel(dependencies: .init(
            addresses: {
                if state == "receive-error" { throw WalletSession.SessionError.notOpen }
                return addresses
            },
            invoice: { request, sats, description in
                // Creating the invoice takes a while, so the saving lock can be seen.
                if state == "receive-saving" { try await Task.sleep(for: .seconds(20)) }
                var next = request
                next.amountSat = sats
                next.description = description
                next.lightningInvoice = Self.invoice
                next.paymentHash = PaymentEncoding.invoice(Self.invoice)?.hash
                return next
            },
            completions: { _ in
                AsyncThrowingStream { continuation in
                    guard let completion else { return }
                    Task {
                        try? await Task.sleep(for: .seconds(delay))
                        continuation.yield(completion)
                        continuation.finish()
                    }
                }
            }))
    }

    /// The chain as Receive's watch sees it: nothing, then your unconfirmed transaction, then it confirms.
    nonisolated static func chain() -> @Sendable (String) async throws -> ReceiveChainSnapshot {
        let start = Date()
        return { _ in
            let elapsed = Date().timeIntervalSince(start)
            let txid = String(repeating: "ef", count: 32)
            if elapsed < 3 { return ReceiveChainSnapshot(tip: tip, incoming: []) }
            if elapsed <
                11 { return ReceiveChainSnapshot(tip: tip, incoming: [.init(txid: txid, toAddress: 100_000, received: 100_000, confirmedHeight: nil)]) }
            return ReceiveChainSnapshot(tip: tip + 1, incoming: [.init(txid: txid, toAddress: 100_000, received: 100_000, confirmedHeight: tip + 1)])
        }
    }
}
