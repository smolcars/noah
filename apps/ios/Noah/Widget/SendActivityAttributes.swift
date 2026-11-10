import ActivityKit
import Foundation

/// A Lightning payment's Live Activity (async-payments.md, "What the user sees"). It carries no amount, recipient
/// or identifier: iOS can keep an ended activity on the Lock Screen for hours after Noah last ran, whatever
/// hide-balance says by then.
struct SendActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable, Sendable {
        var phase: SendActivityPhase
    }
}

/// What the activity says. Only `sent`, `failed` and `refunded` are results, each from the send journal's proof.
enum SendActivityPhase: String, Codable, Sendable {
    // swiftlint:disable:next raw_value_for_camel_cased_codable_enum - case names are the wire strings; SwiftFormat strips equal raw values
    case paying, stillPaying, sent, failed, refunded

    var ended: Bool { self == .sent || self == .failed || self == .refunded }

    /// How far along the track the coin sits: paying is the middle, paid the end, and a failure puts it back.
    var progress: Double {
        switch self {
        case .paying, .stillPaying: 0.5
        case .sent: 1
        case .failed, .refunded: 0
        }
    }

    func title(locale: String) -> String {
        switch self {
        case .paying, .stillPaying: L10n.text("Sending", locale: locale)
        case .sent: L10n.text("Sent", locale: locale)
        case .failed, .refunded: L10n.text("Payment failed", locale: locale)
        }
    }

    /// `stale`: Noah hasn't updated the activity for a while, so a payment still shown as paying may have ended.
    func line(locale: String, stale: Bool = false) -> String? {
        if stale, !ended { return L10n.text("Open Noah to check", locale: locale) }
        return switch self {
        case .paying: L10n.text("Paying over Lightning", locale: locale)
        case .stillPaying: L10n.text("Still paying. This can take a few minutes.", locale: locale)
        case .sent: L10n.text("Paid over Lightning.", locale: locale)
        case .refunded: L10n.text("Back in your wallet", locale: locale)
        case .failed: nil
        }
    }
}
