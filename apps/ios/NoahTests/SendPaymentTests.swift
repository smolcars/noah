import Foundation
import Testing

@testable import Noah

@MainActor struct SendPaymentTests {
    actor Backend {
        var calls = 0
        var fail = true
        var resolution = SendPaymentController.Resolution.uncertain
        func pay() async throws -> SendReceipt {
            calls += 1
            try await Task.sleep(for: .milliseconds(25))
            if fail { throw URLError(.timedOut) }
            return SendReceipt(amount: 1000)
        }
        func reconcile() -> SendPaymentController.Resolution { resolution }
        func setResolution(_ value: SendPaymentController.Resolution) { resolution = value }
        func succeed() { fail = false }
    }
    final class Storage {
        var attempt: SendPaymentController.Attempt?
        var failWrite = false
        var failReceiptWrite = false
        func write(_ value: SendPaymentController.Attempt?) throws {
            if failWrite || (failReceiptWrite && value?.receipt != nil) { throw CocoaError(.fileWriteNoPermission) }
            attempt = value
        }
    }
    let payment = SendPayment(destination: "test", method: "ark", amount: 1000)
    let quote = SendQuote(amount: 1000, fee: 0, total: 1000)
    func make(_ backend: Backend, _ storage: Storage) -> SendPaymentController {
        SendPaymentController(
            dependencies: .init(
                prepare: { _, _ in SendSnapshot(movementIDs: [1]) },
                pay: { _, _, _ in try await backend.pay() }, reconcile: { _ in await backend.reconcile() },
                read: { storage.attempt }, write: { try storage.write($0) }))
    }
    static func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(15))
        while !condition(), ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(10))
        }
        try #require(condition(), "Timed out waiting for the asynchronous state transition")
    }
    @Test func duplicateTapsTimeoutAndRestartNeverResubmit() async throws {
        let backend = Backend()
        let storage = Storage()
        let subject = make(backend, storage)
        subject.submit(payment, quote: quote)
        subject.submit(payment, quote: quote)
        try await Self.waitUntil { subject.state != .submitting }
        #expect(await backend.calls == 1)
        #expect(subject.state == .uncertain)
        subject.submit(payment, quote: quote)
        subject.acknowledge()
        await subject.checkStatus()
        #expect(await backend.calls == 1)
        #expect(subject.state == .uncertain)
        let restarted = make(backend, storage)
        restarted.submit(payment, quote: quote)
        await restarted.checkStatus()
        #expect(await backend.calls == 1)
        #expect(restarted.state == .uncertain)
        await backend.setResolution(.paid(SendReceipt(amount: 1000)))
        await restarted.checkStatus()
        #expect(restarted.state == .succeeded(SendReceipt(amount: 1000)))
        #expect(await backend.calls == 1)
    }
    @Test func callerCancellationDoesNotAbandonSubmission() async throws {
        let backend = Backend()
        let storage = Storage()
        await backend.succeed()
        let subject = make(backend, storage)
        let caller = Task { subject.submit(payment, quote: quote) }
        await caller.value
        caller.cancel()
        try await Self.waitUntil { subject.state != .submitting }
        #expect(subject.state == .succeeded(SendReceipt(amount: 1000)))
        #expect(await backend.calls == 1)
    }
    @Test func cannotSubmitWithoutDurableMarker() async throws {
        let backend = Backend()
        let storage = Storage()
        storage.failWrite = true
        let subject = make(backend, storage)
        subject.submit(payment, quote: quote)
        try await Self.waitUntil { subject.state != .submitting }
        #expect(await backend.calls == 0)
        #expect(subject.state == .ready)
    }
    @Test func repeatMetadataUsesRNContract() throws {
        let patch = try SendPayment.metadata(
            destination: "LIGHTNING:Alice@Example.com", comment: "Coffee", amount: "1.25", fiatCurrency: "USD")
        let value = try #require(
            JSONSerialization.jsonObject(with: Data(patch.utf8)) as? [String: [String: [String: Any]]])
        let details = try #require(value["noah"]?["repeat_payment"])
        #expect(details["version"] as? Int == 1)
        #expect(details["destination"] as? String == "alice@example.com")
        #expect(details["amount_mode"] as? String == "fiat")
        #expect(details["fiat_currency"] as? String == "USD")
        #expect(details["amount_value"] as? String == "1.25")
        #expect(details["comment"] as? String == "Coffee")
        let sats = try SendPayment.metadata(destination: "a@b.com", comment: "", amount: "1000", fiatCurrency: nil)
        #expect(!sats.contains("fiat_currency"))
        #expect(sats.contains("sats"))
    }
    @Test func unreadableJournalBlocksSending() {
        let subject = SendPaymentController(
            dependencies: .init(
                prepare: { _, _ in
                    Issue.record("Must not prepare")
                    return SendSnapshot(movementIDs: [])
                },
                pay: { _, _, _ in
                    Issue.record("Must not pay")
                    return SendReceipt(amount: 1)
                },
                reconcile: { _ in .uncertain },
                read: { throw CocoaError(.fileReadCorruptFile) }, write: { _ in }))
        subject.submit(payment, quote: quote)
        #expect(subject.state == .storageBlocked)
    }
    @Test func savingReceiptFailureDoesNotTurnSuccessIntoRetry() async throws {
        let backend = Backend()
        let storage = Storage()
        await backend.succeed()
        let subject = make(backend, storage)
        storage.failReceiptWrite = true
        subject.submit(payment, quote: quote)
        try await Self.waitUntil { subject.state != .submitting }
        #expect(subject.state == .succeeded(SendReceipt(amount: 1000)))
        storage.failWrite = true
        subject.acknowledge()
        subject.submit(payment, quote: quote)
        #expect(await backend.calls == 1)
        #expect(subject.locked)
    }
    @Test func onlyReconciledFailureAllowsNewAttempt() async throws {
        let backend = Backend()
        let storage = Storage()
        let controller = make(backend, storage)
        controller.submit(payment, quote: quote)
        try await Self.waitUntil { controller.state != .submitting }
        await backend.setResolution(.failed)
        await controller.checkStatus()
        #expect(controller.state == .failed)
        controller.acknowledge()
        #expect(controller.state == .ready)
        #expect(storage.attempt == nil)
    }
}
