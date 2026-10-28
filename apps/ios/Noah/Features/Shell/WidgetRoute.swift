import Foundation
import Observation

/// A widget's tap: `com.noahwallet.<variant>://widget/home`, `/receive`, `/scan` or `/send`
/// (docs/native-rewrite/design/widgets.md, "Opening the app from a widget"). Like a payment link it lives in
/// memory only: held through launch and the app lock, ignored behind onboarding or a launch gate, and handed
/// over once. It only picks a tab; nothing pays from a widget.
@MainActor
@Observable
final class WidgetRoute {
    enum Destination: String { case home, receive, scan, send }

    private(set) var pending: Destination?

    static func destination(_ url: URL, variant: AppVariant) -> Destination? {
        guard url.scheme?.lowercased() == "com.noahwallet.\(variant.rawValue)", url.host()?.lowercased() == "widget" else { return nil }
        return Destination(rawValue: url.path().trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased())
    }

    func receive(_ url: URL, gate: PaymentLinkRoute.Gate, variant: AppVariant) {
        guard gate != .ignore, let destination = Self.destination(url, variant: variant) else { return }
        pending = destination
    }

    /// The held route once the gate opens; an ignoring gate drops it.
    func take(_ gate: PaymentLinkRoute.Gate) -> Destination? {
        guard gate != .hold else { return nil }
        defer { pending = nil }
        return gate == .open ? pending : nil
    }

    /// Scan opens Send's camera only when Send could take a new request; otherwise Send stays as it is.
    static func scans(_ send: SendModel) -> Bool {
        !send.controller.locked && !send.busy && send.stage != .review && !send.showReview
    }
}
