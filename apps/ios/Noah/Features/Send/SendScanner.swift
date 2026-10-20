import AVFoundation
import SwiftUI

struct SendScanner: View {
    @Environment(\.dismiss) private var dismiss
    let scanned: @MainActor @Sendable (String) -> Void
    @State private var authorized = false
    @State private var message: String?
    var body: some View {
        NavigationStack {
            Group {
                if authorized {
                    ScannerCamera(scanned: scanned, failed: { message = $0 })
                } else {
                    Text(LocalizedStringKey(message ?? "Requesting camera access…")).padding()
                }
            }
            .overlay(alignment: .bottom) {
                if authorized, let message { Text(LocalizedStringKey(message)).padding().background(.regularMaterial) }
            }
            .navigationTitle("Scan payment QR")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .task {
                authorized = await AVCaptureDevice.requestAccess(for: .video)
                if !authorized { message = "Allow camera access in Settings to scan a payment QR code." }
            }
        }
    }
}

private struct ScannerCamera: UIViewRepresentable {
    let scanned: @MainActor @Sendable (String) -> Void
    let failed: @MainActor @Sendable (String) -> Void
    func makeCoordinator() -> CameraDriver { CameraDriver(scanned: scanned, failed: failed) }
    func makeUIView(context: Context) -> CameraPreview {
        let view = CameraPreview()
        view.preview.session = context.coordinator.session
        view.preview.videoGravity = .resizeAspectFill
        context.coordinator.start()
        return view
    }
    func updateUIView(_ view: CameraPreview, context: Context) {}
    static func dismantleUIView(_ view: CameraPreview, coordinator: CameraDriver) { coordinator.stop() }
}
private final class CameraPreview: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}
/// Capture session configuration and start/stop stay on one serial queue.
private final class CameraDriver: NSObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "noah.send.camera")
    private let scanned: @MainActor (String) -> Void
    private let failed: @MainActor (String) -> Void
    private var delivered = false
    init(scanned: @escaping @MainActor (String) -> Void, failed: @escaping @MainActor (String) -> Void) {
        self.scanned = scanned
        self.failed = failed
    }
    func start() {
        queue.async { [self] in
            guard let camera = AVCaptureDevice.default(for: .video),
                let input = try? AVCaptureDeviceInput(device: camera)
            else {
                Task { @MainActor in self.failed("No camera is available. You can paste the payment request instead.") }
                return
            }
            session.beginConfiguration()
            let output = AVCaptureMetadataOutput()
            guard session.canAddInput(input), session.canAddOutput(output) else {
                session.commitConfiguration()
                return
            }
            session.addInput(input)
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: queue)
            output.metadataObjectTypes = [.qr]
            session.commitConfiguration()
            session.startRunning()
        }
    }
    func stop() { queue.async { self.session.stopRunning() } }
    func metadataOutput(
        _ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !delivered, let value = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue
        else { return }
        delivered = true
        session.stopRunning()
        Task { @MainActor in self.scanned(value) }
    }
}
