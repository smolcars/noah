import BarkBoltFFI
import Foundation
import Testing
import SwiftUI
import UIKit

@testable import Noah

/// Opt-in, real regtest payment on a dedicated funded simulator. The resolver alone is faked;
/// import, review, durable submission, the SDK send and history verification are production code.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SEND_LIVE_STALE"] == "1"))
struct SendLiveTests {
    @Test func resolvedLightningRecipientThenImportedArkPaysOnlyImportedAddress() async throws {
        #expect(AppVariant.current == .regtest)
        guard AppVariant.current == .regtest else { return }
        let environment = ProcessInfo.processInfo.environment
        let routeA = try #require(environment["NOAH_SEND_ROUTE_A"])
        let recipientB = try #require(environment["NOAH_SEND_ROUTE_B"])
        #expect(routeA != recipientB)
        let session = WalletSession.shared
        guard case .found(let seed) = KeychainPassword.mnemonic(for: .regtest).read() else {
            Issue.record("Create and fund a dedicated regtest simulator wallet first")
            return
        }
        try await session.ensureOpen(mnemonic: seed, runDaemon: true, appVersion: "send-live-test")
        try await session.sync()
        let controller = SendPaymentController(
            dependencies: .live(session: session, variant: .regtest))
        #expect(!controller.locked)
        let model = SendModel(
            session: session, controller: controller,
            resolveRecipient: { recipient in
                .init(
                    destination: recipient, minMsat: 1000, maxMsat: 1_000_000_000,
                    commentAllowed: 100,
                    arkAddress: routeA)
            })
        await model.load()
        model.amount = "1000"
        model.destination = "stale-route@example.com"
        await model.acceptRecipient()
        #expect(model.matchingRoute?.arkAddress == routeA)
        await model.importRecipient(recipientB)  // Same import as the scanner callback.
        let payment = try #require(model.payment)
        let quote = try #require(model.quote)
        #expect(payment.destination == recipientB && payment.method == "ark")
        #expect(model.route == nil && quote.amount == 1000)
        controller.submit(payment, quote: quote)
        let deadline = ContinuousClock.now.advanced(by: .seconds(60))
        while controller.state == .submitting, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(100))
        }
        guard case .succeeded = controller.state else {
            Issue.record(
                "Live payment did not settle; inspect the persisted attempt before any further send"
            )
            return
        }
        let saved = try #require(controller.attempt)
        let destinations = try await session.withOpenWallet { handles in
            try await handles.wallet.history().filter {
                !saved.snapshot.movementIDs.contains($0.id)
            }
            .flatMap(\.sentToAddresses).map { $0.paymentMethod.value }
        }
        #expect(destinations == [recipientB])
        controller.acknowledge()
        #expect(!controller.locked)
    }
}

/// Disposable simulator only. The host funds the published receive address once; this test
/// then spends 1,000 Ark sats and pays the supplied fresh 2,000-sat BOLT11 invoice once each.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SEND_ROUND2_LIVE"] == "1"))
struct SendRoundTwoLiveTests {
    @Test func normalArkAndBolt11UseRealWalletAndJournal() async throws {
        try #require(AppVariant.current == .regtest)
        let environment = ProcessInfo.processInfo.environment
        let recipient = try #require(environment["NOAH_SEND_ARK_RECIPIENT"])
        let invoice = try #require(environment["NOAH_SEND_INVOICE"])
        let session = WalletSession(variant: .regtest)
        try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: true, appVersion: "send-round2-live")
        let request = try await session.receiveRequest()
        let addressFile = URL.documentsDirectory.appending(path: "send-round2-funding-address.txt")
        try request.arkAddress.write(to: addressFile, atomically: true, encoding: .utf8)
        let fundingDeadline = ContinuousClock.now.advanced(by: .seconds(120))
        while ContinuousClock.now < fundingDeadline {
            try await session.sync()
            if let summary = try await session.summary(), summary.spendableSats >= 10000 { break }
            try await Task.sleep(for: .seconds(1))
        }
        try #require(try await session.summary()?.spendableSats ?? 0 >= 10000)
        let controller = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
        try #require(!controller.locked)
        for payment in [SendPayment(destination: recipient, method: "ark", amount: 1000),
                        SendPayment(destination: invoice, method: "lightning", amount: 2000)] {
            let quote = try await session.quoteSend(payment)
            controller.submit(payment, quote: quote)
            let deadline = ContinuousClock.now.advanced(by: .seconds(60))
            while controller.state == .submitting, ContinuousClock.now < deadline {
                try await Task.sleep(for: .milliseconds(50))
            }
            guard case .succeeded(let receipt) = controller.state else {
                Issue.record("Live send did not settle; inspect the journal, never retry")
                try await session.close()
                return
            }
            #expect(receipt.amount == payment.amount)
            let snapshot = try #require(controller.attempt?.snapshot)
            let movements = try await session.withOpenWallet { handles in
                try await handles.wallet.history().filter { !snapshot.movementIDs.contains($0.id) }
            }
            try #require(movements.count == 1)
            #expect(movements[0].status == "successful")
            #expect(movements[0].sentToAddresses.reduce(0) { $0 + $1.amountSats } == payment.amount)
            if payment.method == "lightning" {
                #expect(receipt.fee == movements[0].offchainFeeSats)
                #expect(receipt.paymentHash == PaymentEncoding.invoice(invoice)?.hash)
            }
            controller.acknowledge()
            try #require(!controller.locked)
        }
        try await session.close()
    }
}

/// Disposable simulator only; the host funds addresses once and drives the real confirmation view.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SEND_ROUND3_LIVE"] == "1"))
struct SendRoundThreeLiveTests {
    @Test func amountlessInvoiceReuseAndEnteredOnchainFee() async throws {
        try #require(AppVariant.current == .regtest)
        let environment = ProcessInfo.processInfo.environment
        let invoice = try #require(environment["NOAH_SEND_INVOICE"])
        let recipient = try #require(environment["NOAH_SEND_ONCHAIN_RECIPIENT"])
        try #require(PaymentEncoding.invoice(invoice)?.sats == nil)
        let session = WalletSession(variant: .regtest)
        try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: false, appVersion: "send-round3-live")
        let request = try await session.receiveRequest()
        let documents = URL.documentsDirectory
        try JSONEncoder().encode(["ark": request.arkAddress, "onchain": request.onchainAddress])
            .write(to: documents.appending(path: "send-round3-funding.json"), options: .atomic)
        let fundingDeadline = ContinuousClock.now.advanced(by: .seconds(180))
        while ContinuousClock.now < fundingDeadline {
            try await session.sync()
            if let summary = try await session.summary(), summary.spendableSats >= 10000,
               summary.balance.onchain.confirmed >= 30000 { break }
            try await Task.sleep(for: .seconds(1))
        }
        let summary = try #require(try await session.summary())
        try #require(summary.spendableSats >= 10000 && summary.balance.onchain.confirmed >= 30000)
        let controller = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
        try #require(!controller.locked)
        let payment = SendPayment(destination: invoice, method: "lightning", amount: 2000)
        controller.submit(payment, quote: try await session.quoteSend(payment))
        try await waitUntil { controller.state != .submitting }
        guard case .succeeded(let first) = controller.state else {
            Issue.record("First Lightning payment did not settle; do not retry")
            try await session.close()
            return
        }
        #expect(first.amount == 2000 && first.proof == .actualMovement)
        controller.acknowledge()
        let before = try await session.withOpenWallet { try await $0.wallet.history() }
        let reviewed = SendPayment(destination: invoice, method: "lightning", amount: 1000)
        controller.submit(reviewed, quote: try await session.quoteSend(reviewed))
        try await waitUntil { controller.state != .submitting }
        guard case .alreadyPaid(let existing) = controller.state else {
            Issue.record("Reused invoice must show its actual existing payment")
            try await session.close()
            return
        }
        #expect(existing.amount == 2000 && existing.fee == first.fee)
        let after = try await session.withOpenWallet { try await $0.wallet.history() }
        #expect(Set(before.map(\.id)) == Set(after.map(\.id)))
        controller.acknowledge()
        let onchain = SendPayment(destination: recipient, method: "onchain", amount: 5000, source: .onchain)
        let quote = try await session.quoteSend(onchain)
        #expect(quote.approximate && quote.amount == 5000)
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let keyWindow = scene.windows.first { $0.isKeyWindow }
        let window = try #require(keyWindow)
        window.rootViewController = UIHostingController(rootView: SendConfirmation(
            payment: onchain, quote: quote, controller: controller, cancel: {}, done: {}, onLeave: {}))
        window.makeKeyAndVisible()
        try Data().write(to: documents.appending(path: "send-round3-review-ready"))
        try await waitUntil { controller.state != .ready && controller.state != .submitting }
        guard case .succeeded(var receipt) = controller.state else {
            Issue.record("Onchain send did not return a txid; inspect journal, never retry")
            try await session.close()
            return
        }
        if receipt.fee == nil {
            await controller.checkStatus()
            receipt = try #require(controller.attempt?.receipt)
        }
        #expect(receipt.amount == 5000 && receipt.proof == .submitReturnedTxid)
        #expect(receipt.fee != nil)
        struct Evidence: Encodable { let invoice: SendReceipt; let reused: SendReceipt; let quote: SendQuote; let onchain: SendReceipt }
        try JSONEncoder().encode(Evidence(invoice: first, reused: existing, quote: quote, onchain: receipt))
            .write(to: documents.appending(path: "send-round3-evidence.json"), options: .atomic)
        let finish = documents.appending(path: "send-round3-finish")
        try await waitUntil { FileManager.default.fileExists(atPath: finish.path) }
        try await session.close()
    }
    func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(180))
        while !condition(), ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(100)) }
        try #require(condition(), "Live check timed out; inspect journal before any further send")
    }
}
