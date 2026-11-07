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
    /// `sats`: Siri's Pay amount, filled in before the recipient so Send goes on as it would for a request with one.
    static func apply(_ value: String, to model: SendModel, sats: UInt64? = nil) async -> Bool {
        guard !model.controller.locked, !model.busy else { return false }
        model.reset()
        model.recipientFirst() // A code that isn't a destination shows on To, as today.
        if let sats { model.amount = String(sats) }
        await model.importRecipient(value)
        return true
    }
}

/// `bitcoin:`, `lightning:` and `lnurlp:` links from the OS, and Siri's Pay, take `QRScanRoute`. A link lives in memory
/// only: held through launch and the app lock, ignored behind onboarding or a launch gate, and handed over once.
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

    /// Siri's Pay someone: an address Send fills in, with an amount or nil for Send to ask.
    struct Pay: Equatable, Sendable {
        var recipient: String
        var sats: UInt64?
    }

    private(set) var pending: String?
    /// Siri's Pay amount for `pending`; a link carries its own.
    private(set) var pendingSats: UInt64?
    /// A recurring approval's sheet is open. It shows the alert for a link refused over it:
    /// one from Send's tabs would dismiss the sheet.
    var approvalOpen = false
    var approvalBlocked = false

    /// The URL's own string, so its case and percent-encoding reach Send as a scanned code's would.
    func receive(_ url: URL, gate: Gate) {
        guard gate != .ignore, ["bitcoin", "lightning", "lnurlp"].contains(url.scheme?.lowercased()) else { return }
        pending = url.absoluteString
        pendingSats = nil
    }

    /// Siri's Pay, held and handed over exactly like a link.
    func receive(_ pay: Pay, gate: Gate) {
        guard gate != .ignore else { return }
        pending = pay.recipient
        pendingSats = pay.sats
    }

    /// The held link once the gate opens; an ignoring gate or an open approval drops it.
    func take(_ gate: Gate) -> String? { takeRequest(gate)?.link }

    /// The held link, with Siri's Pay amount, once the gate opens.
    func takeRequest(_ gate: Gate) -> (link: String, sats: UInt64?)? {
        guard gate != .hold else { return nil }
        defer { pending = nil; pendingSats = nil }
        guard gate == .open, let pending else { return nil }
        if approvalOpen { approvalBlocked = true; return nil }
        return (pending, pendingSats)
    }

    /// Send takes a link as a scan, but never over a review it is preparing or showing.
    static func apply(_ link: String, to send: SendModel, sats: UInt64? = nil) async -> Bool {
        guard send.stage != .review, !send.showReview else { return false }
        send.scanning = false // The camera would cover the request.
        return await QRScanRoute.apply(link, to: send, sats: sats)
    }
}
