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
                    ScrollView {
                        Text(LocalizedStringKey(message ?? "Requesting camera access…")).padding().readableWidth()
                    }
                }
            }
            .overlay(alignment: .bottom) {
                if authorized, let message { Text(LocalizedStringKey(message)).padding().background(.regularMaterial) }
            }
            .navigationTitle("Scan QR code")
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
        context.coordinator.start { [weak view] in view?.setNeedsLayout() }
        return view
    }
    func updateUIView(_: CameraPreview, context _: Context) { /* The preview layer follows its session. */ }
    static func dismantleUIView(_: CameraPreview, coordinator: CameraDriver) { coordinator.stop() }
}
enum ScannerPreviewLayout {
    static func rotation(for orientation: UIInterfaceOrientation) -> CGFloat {
        switch orientation {
        case .landscapeRight: 0
        case .landscapeLeft: 180
        case .portraitUpsideDown: 270
        default: 90
        }
    }
}

private final class CameraPreview: UIView {
    override static var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var preview: AVCaptureVideoPreviewLayer {
        guard let preview = layer as? AVCaptureVideoPreviewLayer else { preconditionFailure("layerClass is AVCaptureVideoPreviewLayer") }
        return preview
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let scene = window?.windowScene, let connection = preview.connection else { return }
        let orientation: UIInterfaceOrientation
        if #available(iOS 26, *) { orientation = scene.effectiveGeometry.interfaceOrientation }
        else { orientation = scene.interfaceOrientation }
        let angle = ScannerPreviewLayout.rotation(for: orientation)
        if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
    }
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
    func start(configured: @escaping @MainActor @Sendable () -> Void) {
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
            Task { @MainActor in configured() }
            session.startRunning()
        }
    }
    func stop() { queue.async { self.session.stopRunning() } }
    func metadataOutput(
        _: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
        from _: AVCaptureConnection) {
        guard !delivered, let value = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue
        else { return }
        delivered = true
        session.stopRunning()
        Task { @MainActor in self.scanned(value) }
    }
}
