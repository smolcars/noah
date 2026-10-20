import AVFoundation
import Observation

@MainActor
@Observable
final class QRHubModel {
    enum Mode: String { case myCode, scan }
    var mode = Mode.myCode
    private(set) var scannerPresented = false
    var permissionError = false
    var copied = false
    private let requestCamera: () async -> Bool
    private var generation = 0

    init(requestCamera: @escaping () async -> Bool = { await AVCaptureDevice.requestAccess(for: .video) }) {
        self.requestCamera = requestCamera
    }

    func openScanner() async {
        guard mode == .scan, !scannerPresented else { return }
        let token = generation
        let allowed = await requestCamera()
        guard token == generation, !Task.isCancelled, mode == .scan else { return }
        if allowed { scannerPresented = true }
        else { mode = .myCode; permissionError = true }
    }

    func closeScanner() {
        generation += 1
        scannerPresented = false
        mode = .myCode
    }

    func receive(_ value: String, pasted: Bool = false) -> String? {
        guard !(pasted ? value.trimmingCharacters(in: .whitespacesAndNewlines) : value).isEmpty else { return nil }
        closeScanner()
        return value // Send's existing importer validates the destination, just as in RN.
    }

    static func displayAddress(_ value: String) -> String {
        value.replacingOccurrences(of: "@", with: "@\u{200B}")
            .replacingOccurrences(of: ".", with: ".\u{200B}")
    }
}

/// Navigation boundary only: reuse Send's draft reset/import, never replace a live payment.
@MainActor
enum QRScanRoute {
    static func apply(_ value: String, to model: SendModel) async -> Bool {
        guard !model.controller.locked, !model.busy else { return false }
        model.reset()
        await model.importRecipient(value)
        return true
    }
}
