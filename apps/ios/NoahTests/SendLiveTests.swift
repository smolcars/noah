import BarkBoltFFI
import Foundation
import Testing

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
