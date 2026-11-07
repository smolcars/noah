import Foundation
import Observation

/// The payment a "Payment received" notification opens in Activity: a Lightning receive by its payment hash, or an Ark
/// receive by the coins the server saw arrive (apps/fixtures/notification-tap.json). A server push carries it as the
/// JSON in its `body`; the app's own Lightning notification puts the same fields in its `userInfo`.
struct PaymentTap: Equatable, Sendable {
    var paymentHash: String?
    var vtxoIDs: [String] = []

    /// Received `outputVtxos`, not spent ones: a later payment that spends the coins isn't this one.
    func matches(paymentHash: String?, outputVtxos: [String]?) -> Bool {
        (self.paymentHash != nil && paymentHash == self.paymentHash) || (outputVtxos ?? []).contains(where: vtxoIDs.contains)
    }

    func matches(_ row: HistoryTransaction) -> Bool { matches(paymentHash: row.paymentHash, outputVtxos: row.outputVtxos) }

    static func lightningUserInfo(paymentHash: String) -> [String: String] {
        ["notification_type": "lightning_payment_received", "payment_hash": paymentHash]
    }

    /// Nil for anything that names no payment: a background command, older servers' `{}` or malformed data.
    private static func of(record: [AnyHashable: Any]) -> PaymentTap? {
        switch record["notification_type"] as? String {
        case "lightning_payment_received":
            guard let hash = record["payment_hash"] as? String, !hash.isEmpty else { return nil }
            return PaymentTap(paymentHash: hash)
        case "ark_payment_received":
            let ids = (record["vtxo_ids"] as? [Any] ?? []).compactMap { $0 as? String }
            return ids.isEmpty ? nil : PaymentTap(vtxoIDs: ids)
        default:
            return nil
        }
    }

    static func of(data: String) -> PaymentTap? {
        (try? JSONSerialization.jsonObject(with: Data(data.utf8))).flatMap { $0 as? [AnyHashable: Any] }.flatMap { of(record: $0) }
    }

    /// A tapped notification's `userInfo`: the app's own fields, or the push's `body` (a JSON string from APNs, an
    /// object from Expo).
    static func of(userInfo: [AnyHashable: Any]) -> PaymentTap? {
        if let tap = of(record: userInfo) { return tap }
        if let body = userInfo["body"] as? [AnyHashable: Any] { return of(record: body) }
        if let body = userInfo["body"] as? String { return of(data: body) }
        return nil
    }
}

/// A tapped payment notification, held in memory like a widget route: through launch and the app lock, dropped behind
/// onboarding or a launch gate. `pending` is the tap the wallet's tabs haven't taken; `activity` is the one Activity
/// opens once its history has the payment (an Ark receive arrives with the resume sync).
@MainActor
@Observable
final class PaymentTapRoute {
    static let shared = PaymentTapRoute()
    var pending: PaymentTap?
    var activity: PaymentTap?

    func receive(_ userInfo: [AnyHashable: Any]) {
        if let tap = PaymentTap.of(userInfo: userInfo) { pending = tap }
    }

    func drop() { pending = nil; activity = nil }
}
