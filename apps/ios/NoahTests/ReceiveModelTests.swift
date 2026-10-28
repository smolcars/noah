import Foundation
@testable import Noah
import Testing

@Suite(.timeLimit(.minutes(1)))
@MainActor
struct ReceiveModelTests {
    private let request = ReceiveRequest(arkAddress: "ark", onchainAddress: "chain")
    private actor InvoiceGate {
        var pending: CheckedContinuation<ReceiveRequest, Never>?
        func wait() async -> ReceiveRequest { await withCheckedContinuation { pending = $0 } }
        var started: Bool { pending != nil }
        func finish(_ request: ReceiveRequest) { pending?.resume(returning: request); pending = nil }
    }

    @Test func leavingReceiveDiscardsLateInvoice() async {
        let gate = InvoiceGate()
        let request = request
        let model = ReceiveModel(dependencies: .init(addresses: { request }, invoice: { _, _, _ in await gate.wait() },
                                                     completions: { _ in AsyncThrowingStream { _ in } }))
        await model.start()
        let save = Task { await model.save(amountSat: 500, description: "test") }
        while !(await gate.started) { await Task.yield() }
        model.stop()
        var invoice = request
        invoice.amountSat = 500
        await gate.finish(invoice)
        #expect(await save.value == false)
        #expect(model.request == nil)
        #expect(!model.isSaving)
    }

    @Test func receiptDuringInvoiceGenerationWins() async {
        let gate = InvoiceGate()
        let request = request
        let (stream, continuation) = AsyncThrowingStream<ReceiveCompletion, Error>.makeStream()
        let model = ReceiveModel(dependencies: .init(addresses: { request }, invoice: { _, _, _ in await gate.wait() },
                                                     completions: { _ in stream }))
        await model.start()
        let save = Task { await model.save(amountSat: 500, description: "test") }
        while !(await gate.started) { await Task.yield() }
        continuation.yield(.received(1234, rail: .lightning))
        while model.receivedSats == nil { await Task.yield() }
        await gate.finish(request)
        #expect(await save.value == false)
        #expect(model.receivedSats == 1234)
        #expect(model.receivedRail == .lightning)
        #expect(model.request == nil)
    }

    @Test func removingAmountPreservesAddressesAndClearsInvoice() async {
        var initial = request
        initial.amountSat = 500
        initial.paymentHash = "old"
        initial.lightningInvoice = "invoice"
        initial.description = "note"
        let request = initial
        let model = ReceiveModel(dependencies: .init(addresses: { request }, invoice: { request, _, _ in request },
                                                     completions: { _ in AsyncThrowingStream { _ in } }))
        await model.start()
        model.removeAmount()
        #expect(model.request == self.request)
        model.stop()
    }

    @Test func canceledLightningClearsRequestAndShowsFailure() async {
        let request = request
        let model = ReceiveModel(dependencies: .init(addresses: { request }, invoice: { request, _, _ in request },
                                                     completions: { _ in AsyncThrowingStream {
                                                         $0.yield(.lightningFailed(canceled: true)); $0.finish()
                                                     } }))
        await model.start()
        while model.baseError == nil { await Task.yield() }
        #expect(model.request == nil)
        #expect(model.receivedSats == nil)
        #expect(model.baseErrorTitle == String(localized: "Lightning Receive Canceled"))
    }

    @Test func unavailableWalletShowsRetryError() async {
        let model = ReceiveModel(dependencies: .init(addresses: { throw WalletSession.SessionError.notOpen },
                                                     invoice: { request, _, _ in request },
                                                     completions: { _ in AsyncThrowingStream { _ in } }))
        await model.start()
        #expect(model.baseError != nil)
        #expect(model.request == nil)
        #expect(!model.isGenerating)
    }
}
