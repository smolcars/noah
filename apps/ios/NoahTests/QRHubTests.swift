import Foundation
@testable import Noah
import Testing

@MainActor
struct QRHubTests {
    struct Input: Decodable { let value: String; let pasted: Bool }
    @Test func scanAndPasteMatchRN() throws {
        let cases = try Fixtures.load("qr-hub", input: Input.self, expected: String?.self)
        #expect(cases.count == 8)
        for fixture in cases {
            let hub = QRHubModel(mode: .scan, requestCamera: { true })
            #expect(hub.receive(fixture.input.value, pasted: fixture.input.pasted) == fixture.expected)
            #expect(!hub.scannerPresented)
        }
        #expect(QRHubModel.displayAddress("alice@pay.example.com") == "alice@\u{200B}pay.\u{200B}example.\u{200B}com")
    }

    /// Home's scanner never turns into My code: a denial shows the RN copy, then the view goes back.
    @Test func deniedCameraShowsRNCopyAndStaysOnScan() async {
        let hub = QRHubModel(mode: .scan, requestCamera: { false })
        await hub.openScanner()
        #expect(hub.mode == .scan)
        #expect(hub.permissionError)
        #expect(!hub.scannerPresented)
    }

    @Test func scannerClosesOnCancelOrFocusLoss() async {
        let hub = QRHubModel(mode: .scan, requestCamera: { true })
        await hub.openScanner()
        #expect(hub.scannerPresented)
        hub.closeScanner()
        #expect(!hub.scannerPresented)
        #expect(hub.mode == .scan)
    }

    @Test func myCodeNeverAsksForTheCamera() async {
        var asked = false
        let hub = QRHubModel(requestCamera: { asked = true; return true })
        await hub.openScanner()
        #expect(hub.mode == .myCode && !asked && !hub.scannerPresented)
    }

    @Test func lateCameraApprovalCannotReopenAfterLeavingHub() async {
        var continuation: CheckedContinuation<Bool, Never>?
        let hub = QRHubModel(mode: .scan, requestCamera: { await withCheckedContinuation { continuation = $0 } })
        let request = Task { await hub.openScanner() }
        while continuation == nil { await Task.yield() }
        hub.closeScanner()
        continuation?.resume(returning: true)
        await request.value
        #expect(!hub.scannerPresented)
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
        #expect(send.stage == .amount) // Recipient first: on to the keypad.
        #expect(await QRScanRoute.apply("zz", to: send))
        #expect(send.stage == .recipient && send.error != nil)
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
