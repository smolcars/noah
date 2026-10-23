import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Noah

/// Opt-in screenshot fixtures for Send and Receive: the real tab shell with a scripted
/// payment controller, a fake receive model and stand-ins for wallet reads (no wallet,
/// server writes or recovery phrase). Write a state name to Documents/sr-shot; "stop" ends
/// the session. Send: amount, amount-typed, amount-over, amount-recipient, to, to-typing, to-error, to-nomatch,
/// method, source, review-ark, review-lightning, review-onchain, sending-ark, sent-ark,
/// sent-lightning, slow-lightning, pending-lightning, refunded, failed, sent-swap,
/// confirm-swap, sent-wallet, uncertain, already-paid, unavailable, broadcast-pending.
/// Receive: receive, receive-noaddress, receive-amount, receive-invoice, receive-ark,
/// receive-bitcoin, receive-formats, receive-amount-sheet, receive-error, arrive-ark,
/// arrive-lightning, arrive-onchain, arrive-failed.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SR_SHOTS"] == "1"))
struct SendReceiveShotTests {
    nonisolated static let invoice = "lnbcrt15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvtgjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwkwcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsqjqjd37"
    nonisolated static let bitcoin = "bcrt1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdku202"
    nonisolated static let ark = "tark1pwh9vsmezqqpjy9akejayl2vvcse6he97rn40g84xrlvrlnhayuuyefrp9nse2yspqqkm2rpd"
    nonisolated static let tip: UInt32 = 921_345
    static var request: String { "bitcoin:\(bitcoin)?amount=0.000015&lightning=\(invoice)&ark=\(ark)" }

    @Test(.timeLimit(.minutes(60))) func presentStates() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.windows.first { $0.isKeyWindow })
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
                let (view, root, after) = try await fixture(state)
                roots.append(root)
                host.rootView = view
                try await Task.sleep(for: .milliseconds(700))
                await after()
                try state.write(to: ready, atomically: true, encoding: .utf8)
                current = state
            }
            try await Task.sleep(for: .milliseconds(150))
        }
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

    private func fixture(_ state: String) async throws -> (AnyView, URL, () async -> Void) {
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

        var fixture = SendReceiveFixture(summary: Self.summary(), tip: Self.tip, refunded: state == "refunded" ? true : nil,
                                         history: [], fees: [.ark: 0, .lightning: 12, .onchain: 420])
        let receive = receiveModel(state)
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
            {
                try? await Task.sleep(for: .seconds(delay))
                if let payment = send.payment, let quote = send.quote { send.controller.submit(payment, quote: quote) }
            }
        }
        let arkPayment = SendPayment(destination: Self.ark, method: "ark", amount: 2_500,
                                     repeatMetadata: try? SendPayment.metadata(destination: "maya@localhost.com", comment: "", amount: "2500", fiatCurrency: nil))
        let lightningPayment = SendPayment(destination: Self.invoice, method: "lightning", amount: 1_500)
        let swapPayment = SendPayment(destination: Self.bitcoin, method: "onchain", amount: 50_000, source: .offchain)
        let walletPayment = SendPayment(destination: Self.bitcoin, method: "onchain", amount: 50_000, source: .onchain)
        let hash = PaymentEncoding.invoice(Self.invoice)?.hash
        let lightningReceipt = SendReceipt(amount: 1_500, paymentHash: hash, movementID: 7, fee: 3, proof: .actualMovement)
        let swapReceipt = SendReceipt(amount: 50_000, txid: String(repeating: "ab", count: 32), status: .submitted, fee: 420, proof: .submitReturnedTxid)

        switch state {
        case "amount": break
        case "amount-typed": send.amount = "2500"
        case "amount-over": send.amount = "250000"
        case "amount-recipient":
            // 700 typed, then a pasted destination without an amount: the keypad keeps its turn, recipient shown.
            send.amount = "700"
            after = { await send.importRecipient(Self.ark) }
        case "to", "to-typing", "to-error", "to-nomatch":
            send.amount = "2500"
            send.progress.amountConfirmed = true
            send.stage = .recipient
            send.destination = ["to-typing": "maya@localhost.com", "to-error": "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                                "to-nomatch": "zz"][state] ?? ""
        case "method":
            after = { await send.importRecipient(Self.request) }
        case "source":
            after = { send.maxSend() }
        case "review-ark", "sending-ark", "sent-ark":
            review(arkPayment, SendQuote(amount: 2_500, fee: 0, total: 2_500), destination: "maya@localhost.com")
            if state != "review-ark" {
                await script.configure(delay: state == "sent-ark" ? 0.8 : 1, result: state == "sent-ark"
                                       ? .success(SendReceipt(amount: 2_500, fee: 0, proof: .submitReturnedArkSuccess)) : nil)
                after = submit()
            }
        case "review-lightning", "sent-lightning", "slow-lightning", "pending-lightning", "refunded":
            review(lightningPayment, SendQuote(amount: 1_500, fee: 12, total: 1_512), destination: Self.invoice)
            fixture.history = [{
                var row = HomeShotTests.row("movement-7", "Bolt11", 1_500, "outgoing", HomeShotTests.at(0, 9, 30), to: Self.invoice)
                row.movementId = 7
                row.preimage = "9f2c6b0e7d4a1c3e5f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4a41e"
                return row
            }()]
            switch state {
            case "sent-lightning":
                await script.configure(delay: 1.6, result: .success(lightningReceipt))
                after = submit()
            case "slow-lightning":
                await script.configure(delay: 1, result: nil)
                after = submit()
            case "pending-lightning":
                await script.configure(delay: 3, result: .failure(URLError(.timedOut)), resolution: .inProgress(.init(amount: 1_500, fee: 12)))
                after = submit()
            case "refunded":
                await script.configure(delay: 3, result: .failure(URLError(.timedOut)), resolution: .failed)
                after = submit()
            default: break
            }
        case "review-onchain", "sent-wallet", "broadcast-pending":
            var quote = SendQuote(amount: 50_000, fee: 420, total: 50_420, feeRate: 2)
            quote.prepared = try JSONDecoder().decode(SendPreparedTransaction.self, from: Data(
                #"{"id":"fixture","txid":"\#(String(repeating: "cd", count: 32))","vsize":141,"inputCount":1,"createdAt":\#(Date().timeIntervalSinceReferenceDate)}"#.utf8))
            review(walletPayment, quote, destination: Self.bitcoin)
            if state != "review-onchain" {
                var receipt = SendReceipt(amount: 50_000, txid: String(repeating: "cd", count: 32), status: .submitted, fee: 420, proof: .preparedTxid)
                if state == "broadcast-pending" { receipt.broadcastRetryPending = true }
                await script.configure(delay: 0.6, result: .success(receipt), resolution: .paid(receipt))
                after = submit()
            }
        case "sent-swap", "confirm-swap":
            review(swapPayment, SendQuote(amount: 50_000, fee: 420, total: 50_420), destination: Self.bitcoin)
            var confirmed = swapReceipt
            confirmed.chainStatus = .confirmed(Self.tip + 1)
            confirmed.status = .confirmed
            await script.configure(delay: 1, result: .success(swapReceipt), resolution: state == "confirm-swap" ? .paid(confirmed) : .paid(swapReceipt))
            if state == "confirm-swap" { fixture.tip = Self.tip + 1 }
            after = submit()
        case "failed":
            review(arkPayment, SendQuote(amount: 2_500, fee: 0, total: 2_500), destination: "maya@localhost.com")
            await script.configure(delay: 1, result: .failure(SendNotSubmitted(reason: .fundsChanged)))
            after = submit()
        case "uncertain":
            review(arkPayment, SendQuote(amount: 2_500, fee: 0, total: 2_500), destination: "maya@localhost.com")
            await script.configure(delay: 1, result: .failure(URLError(.timedOut)), resolution: .candidates([]))
            after = submit()
        case "already-paid":
            review(lightningPayment, SendQuote(amount: 1_500, fee: 12, total: 1_512), destination: Self.invoice)
            await script.configure(delay: 1, result: .failure(SendAlreadyPaid(receipt: lightningReceipt)))
            after = submit()
        case "unavailable": break
        default:
            tab = .receive
            switch state {
            case "receive-invoice": fixture.receiveFormat = .invoice
            case "receive-ark": fixture.receiveFormat = .ark
            case "receive-bitcoin": fixture.receiveFormat = .bitcoin
            case "receive-formats": fixture.receiveSheet = "formats"
            case "receive-amount-sheet", "receive-saving": fixture.receiveSheet = "amount"
            case "arrive-onchain": fixture.chain = Self.chain()
            default: break
            }
            if ["receive-amount", "receive-invoice", "arrive-lightning"].contains(state) {
                after = { _ = await receive.save(amountSat: 1_500, description: "") }
            }
        }
        let view = WalletTabs(history: history, home: home, send: send, receive: receive, tab: tab)
            .environment(app)
            .environment(app.walletLock)
            .environment(\.homeFixture, HomeFixture(summary: Self.summary(), walletError: nil, needsRecoveryPhraseBackup: false))
            .environment(\.sendReceiveFixture, fixture)
            .environment(\.locale, Locale(identifier: app.localeIdentifier))
            .environment(\.scenePhase, .active)
        return (AnyView(view), root, after)
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
            if elapsed < 11 { return ReceiveChainSnapshot(tip: tip, incoming: [.init(txid: txid, toAddress: 100_000, received: 100_000, confirmedHeight: nil)]) }
            return ReceiveChainSnapshot(tip: tip + 1, incoming: [.init(txid: txid, toAddress: 100_000, received: 100_000, confirmedHeight: tip + 1)])
        }
    }
}
