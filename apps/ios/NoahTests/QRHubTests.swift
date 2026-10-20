import Foundation
import Testing
@testable import Noah

@MainActor
struct QRHubTests {
    struct Input: Decodable { let value: String; let pasted: Bool }
    @Test func scanAndPasteMatchRN() throws {
        let cases = try Fixtures.load("qr-hub", input: Input.self, expected: String?.self)
        #expect(cases.count == 8)
        for fixture in cases {
            let hub = QRHubModel(requestCamera: { true })
            hub.mode = .scan
            #expect(hub.receive(fixture.input.value, pasted: fixture.input.pasted) == fixture.expected)
            #expect(hub.mode == (fixture.expected == nil ? .scan : .myCode))
            #expect(!hub.scannerPresented)
        }
        #expect(QRHubModel.displayAddress("alice@pay.example.com") == "alice@\u{200B}pay.\u{200B}example.\u{200B}com")
    }

    @Test func deniedCameraResetsModeAndShowsRNCopy() async {
        let hub = QRHubModel(requestCamera: { false })
        hub.mode = .scan
        await hub.openScanner()
        #expect(hub.mode == .myCode)
        #expect(hub.permissionError)
        #expect(!hub.scannerPresented)
    }

    @Test func scannerClosesAndResetsOnCancelOrFocusLoss() async {
        let hub = QRHubModel(requestCamera: { true })
        hub.mode = .scan
        await hub.openScanner()
        #expect(hub.scannerPresented)
        hub.closeScanner()
        #expect(!hub.scannerPresented)
        #expect(hub.mode == .myCode)
    }

    @Test func lateCameraApprovalCannotReopenAfterLeavingHub() async {
        var continuation: CheckedContinuation<Bool, Never>?
        let hub = QRHubModel(requestCamera: { await withCheckedContinuation { continuation = $0 } })
        hub.mode = .scan
        let request = Task { await hub.openScanner() }
        while continuation == nil { await Task.yield() }
        hub.closeScanner()
        continuation?.resume(returning: true)
        await request.value
        #expect(!hub.scannerPresented)
        #expect(hub.mode == .myCode)
    }

    @Test func scanRoutingReusesImporterAndClearsPreviousDraftWithoutSubmitting() async {
        let controller = SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage())
        var resolutions: [String] = []
        let send = SendModel(controller: controller, resolveRecipient: { value in
            resolutions.append(value)
            return .init(destination: value, minMsat: 1000, maxMsat: 1_000_000_000, commentAllowed: 0)
        })
        send.destination = "old@example.com"
        send.amount = "999"
        send.comment = "old note"
        #expect(await QRScanRoute.apply("alice@example.com", to: send))
        #expect(send.destination == "alice@example.com")
        #expect(send.amount.isEmpty)
        #expect(send.comment.isEmpty)
        #expect(resolutions == ["alice@example.com"])
        #expect(send.quote == nil)
        #expect(!send.controller.locked)
    }

    @Test func scanCannotReplaceAnUncertainPayment() async throws {
        let backend = SendPaymentTests.Backend()
        let storage = SendPaymentTests.Storage()
        let controller = SendPaymentTests().make(backend, storage)
        controller.submit(SendPaymentTests().payment, quote: SendPaymentTests().quote)
        try await SendPaymentTests.waitUntil { controller.state == .uncertain }
        let send = SendModel(controller: controller)
        send.destination = "original@example.com"
        #expect(!(await QRScanRoute.apply("new@example.com", to: send)))
        #expect(send.destination == "original@example.com")
        #expect(controller.state == .uncertain)
        #expect(await backend.calls == 1)
    }

    @Test func scanCannotReplaceBusyPaymentDraft() async {
        let controller = SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage())
        let send = SendModel(controller: controller)
        send.destination = "old@example.com"
        send.busy = true
        #expect(!(await QRScanRoute.apply("new@example.com", to: send)))
        #expect(send.destination == "old@example.com")
    }
}
