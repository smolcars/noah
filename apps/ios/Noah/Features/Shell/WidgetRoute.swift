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

    /// The tab a route opens, or nil to leave the app as it is. Leaving Send discards a review it is preparing or
    /// showing, so while Send has a payment under way (preparing, reviewing or sending) Home and Receive stay put,
    /// and Send and Scan open Send as it is. Nothing moves under a recurring approval.
    static func tab(for destination: Destination, send: SendModel, approvalOpen: Bool) -> WalletTabs.Tab? {
        guard !approvalOpen else { return nil }
        switch destination {
        case .home: return scans(send) ? .home : nil
        case .receive: return scans(send) ? .receive : nil
        case .send, .scan: return .send
        }
    }
}
