import AVFoundation
import Observation

@MainActor
@Observable
final class QRHubModel {
    enum Mode: String { case myCode, scan }
    /// One per hub: Home's scan button opens the scanner alone, Profile's My code the code alone.
    let mode: Mode
    private(set) var scannerPresented = false
    var permissionError = false
    var copied = false
    private let requestCamera: () async -> Bool
    private var generation = 0

    init(mode: Mode = .myCode, requestCamera: @escaping () async -> Bool = { await AVCaptureDevice.requestAccess(for: .video) }) {
        self.mode = mode
        self.requestCamera = requestCamera
    }

    func openScanner() async {
        guard mode == .scan, !scannerPresented else { return }
        let token = generation
        let allowed = await requestCamera()
        guard token == generation, !Task.isCancelled else { return }
        if allowed { scannerPresented = true } else { permissionError = true }
    }

    func closeScanner() {
        generation += 1
        scannerPresented = false
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
        model.recipientFirst() // A code that isn't a destination shows on To, as today.
        await model.importRecipient(value)
        return true
    }
}

/// `bitcoin:`, `lightning:` and `lnurlp:` links from the OS take `QRScanRoute`. A link lives in memory only:
/// held through launch and the app lock, ignored behind onboarding or a launch gate, and handed over once.
@MainActor
@Observable
final class PaymentLinkRoute {
    enum Gate: Equatable {
        case hold, ignore, open

        /// A known gate decides on arrival, even while launching or locked. `settling`: a gate's
        /// answer isn't in yet, so Send mustn't start on a link that gate may still take back.
        init(phase: AppModel.Phase, onboarding: Bool, gated: Bool, settling: Bool, locked: Bool) {
            guard !gated else { self = .ignore; return }
            switch phase {
            case .launching: self = .hold
            case .wallet where !onboarding: self = locked || settling ? .hold : .open
            default: self = .ignore
            }
        }
    }

    private(set) var pending: String?
    /// A recurring approval's sheet is open. It shows the alert for a link refused over it:
    /// one from Send's tabs would dismiss the sheet.
    var approvalOpen = false
    var approvalBlocked = false

    /// The URL's own string, so its case and percent-encoding reach Send as a scanned code's would.
    func receive(_ url: URL, gate: Gate) {
        guard gate != .ignore, ["bitcoin", "lightning", "lnurlp"].contains(url.scheme?.lowercased()) else { return }
        pending = url.absoluteString
    }

    /// The held link once the gate opens; an ignoring gate or an open approval drops it.
    func take(_ gate: Gate) -> String? {
        guard gate != .hold else { return nil }
        defer { pending = nil }
        guard gate == .open, pending != nil else { return nil }
        if approvalOpen { approvalBlocked = true; return nil }
        return pending
    }

    /// Send takes a link as a scan, but never over a review it is preparing or showing.
    static func apply(_ link: String, to send: SendModel) async -> Bool {
        guard send.stage != .review, !send.showReview else { return false }
        send.scanning = false // The camera would cover the request.
        return await QRScanRoute.apply(link, to: send)
    }
}
