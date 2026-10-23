import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// Two separately launched processes on a fresh disposable simulator. No destinations or seeds in source.
@MainActor @Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SEND_ROUND5_LIVE"] == "1"))
struct SendRoundFiveLiveTests {
    private var documents: URL { .documentsDirectory }
    private func wait(_ condition: () async throws -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(180)
        while ContinuousClock.now < deadline {
            if try await condition() { return }
            try await Task.sleep(for: .seconds(1))
        }
        Issue.record("Live checkpoint deadline expired; inspect, never retry payment")
    }
    private func payment() throws -> SendPayment {
        .init(destination: try #require(ProcessInfo.processInfo.environment["NOAH_SEND_INVOICE"]), method: "lightning", amount: 2000)
    }
    @Test func createHeldCheckpoint() async throws {
        try #require(AppVariant.current == .regtest && !WalletDataLocation(variant: .regtest).hasAnyData)
        let session = WalletSession.shared
        try await WalletCreator(dependencies: .live(session: session, variant: .regtest, appVersion: "send-r5-live")).createOrResume()
        let address = try await session.receiveRequest().arkAddress
        try address.write(to: documents.appending(path: "send-r5-fund.txt"), atomically: true, encoding: .utf8)
        try await wait {
            try await session.sync()
            return try await session.summary()?.spendableSats ?? 0 >= 20000
        }
        let payment = try payment()
        let hash = try #require(PaymentEncoding.invoice(payment.destination)?.hash)
        // Simulate an inherited pre-Noah checkpoint; exactly one creating call, never retry.
        _ = try await session.withOpenWallet { try await $0.wallet.payLightningInvoice(invoice: payment.destination, amountSats: nil, options: .init(wait: false, retryForSecs: 60)) }
        try await wait {
            try await session.withOpenWallet {
                if case .inProgress = try await $0.wallet.lightningSendState(paymentHash: hash) { return true }
                return false
            }
        }
        try #require(!FileManager.default.fileExists(atPath: WalletDataLocation(variant: .regtest).directory.appending(path: "noah-send-attempt.json").path))
        try Data().write(to: documents.appending(path: "send-r5-checkpoint-ready"))
        try await session.close()
    }
    @Test func adoptHeldCheckpointAfterRelaunch() async throws {
        try #require(AppVariant.current == .regtest)
        let session = WalletSession.shared
        guard case .found(let mnemonic) = KeychainPassword.mnemonic(for: .regtest).read() else { Issue.record("No disposable seed"); return }
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "send-r5-live")
        let payment = try payment()
        let quote = try await session.quoteSend(payment)
        try #require(quote.checkpoint != nil, "Checkpoint no longer pending: never submit a fresh payment in this adoption test")
        let controller = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
        try #require(controller.state == .ready)
        controller.submit(payment, quote: quote)
        try await SendPaymentTests.waitUntil { controller.state != .submitting }
        try #require(controller.attempt?.snapshot.checkpoint != nil)
        try #require(controller.state == .uncertain)
        try JSONEncoder().encode(try #require(controller.attempt)).write(to: documents.appending(path: "send-r5-adopted.json"))
        try Data().write(to: documents.appending(path: "send-r5-adopted-ready"))
        try await wait {
            await controller.checkStatus()
            if case .succeeded = controller.state { return true }
            return false
        }
        guard case .succeeded(let receipt) = controller.state else { try await session.close(); return }
        #expect(receipt.amount == 2000 && receipt.proof == .actualMovement)
        let hash = try #require(controller.attempt?.paymentHash)
        let history = try await session.withOpenWallet { try await $0.wallet.history() }
        let sends = history.filter { $0.subsystemName == "bark.lightning_send" && $0.paymentHash == hash }
        #expect(sends.count == 1 && sends.first?.status == "successful")
        try JSONEncoder().encode(try #require(controller.attempt)).write(to: documents.appending(path: "send-r5-receipt.json"))
        try await session.close()
    }
}
