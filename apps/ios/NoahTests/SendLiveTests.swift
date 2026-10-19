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
        for _ in 0..<100 where controller.state == .submitting {
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
