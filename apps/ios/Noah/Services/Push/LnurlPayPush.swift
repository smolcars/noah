import CryptoKit
import Foundation
import UserNotifications

/// The payer data an LNURL-pay payment's push carries in `lnurl_pay_receive`, sealed with this app's
/// `NotificationKey` (docs/lnurl_pay.md, apps/fixtures/lnurl-pay-receive-notification.json). Payer-provided and
/// unverified. Built into the app and its notification service extension.
enum LnurlPayPush {
    struct PayerData: Codable, Equatable, Sendable {
        var name: String?
        var identifier: String?
    }

    /// The JSON inside `sealed`, decoded with `decoder` (snake_case keys).
    struct Content: Codable, Equatable, Sendable {
        var amountSat: UInt64
        var payerData: PayerData?
        var comment: String?
    }

    static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }

    /// The sealed content in a push's data (`body`: a JSON string, or an object), or nil when there is none or it
    /// doesn't open with `key`.
    static func open(userInfo: [AnyHashable: Any], key: SymmetricKey?) -> Content? {
        let field = field(userInfo)
        guard let key, (field["v"] as? Int) == 1, let id = field["id"] as? String, let sealed = field["sealed"] as? String
        else { return nil }
        return open(id: id, sealed: sealed, key: key)
    }

    /// `lnurl_pay_receive` in the push's data; empty when there is none.
    private static func field(_ userInfo: [AnyHashable: Any]) -> [String: Any] {
        let text = userInfo["body"] as? String
        let body = text.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
            ?? userInfo["body"] as? [String: Any] ?? [:]
        return body["lnurl_pay_receive"] as? [String: Any] ?? [:]
    }

    /// AES-256-GCM: base64 of nonce (12) || ciphertext || tag (16), CryptoKit's combined box, bound to the record `id`.
    static func open(id: String, sealed: String, key: SymmetricKey) -> Content? {
        guard let combined = Data(base64Encoded: sealed),
              let box = try? AES.GCM.SealedBox(combined: combined),
              let plaintext = try? AES.GCM.open(box, using: key,
                                                authenticating: Data("noah-lnurl-pay-receive-notification:v1:\(id)".utf8))
        else { return nil }
        return try? decoder.decode(Content.self, from: plaintext)
    }

    /// The payer as the title, as a message's sender would be; their comment under the amount.
    static func copy(_ content: Content, unit: BitcoinAmountUnit, defaults: UserDefaults) -> NotificationCopy {
        let payer = LnurlPayReceive.checked(name: content.payerData?.name, identifier: content.payerData?.identifier,
                                            comment: content.comment)
        return NotificationCopy.lnurlPayReceived(sats: Int64(clamping: content.amountSat), unit: unit, payer: payer.payerLabel,
                                                 comment: payer.comment, defaults: defaults)
    }

    /// The push with its payer named, or nil to show it as it came. `group` is the app group's defaults: the language
    /// and amount unit the widget snapshot mirrors.
    static func rewritten(_ original: UNNotificationContent, key: SymmetricKey?, group: UserDefaults?,
                          variant: AppVariant) -> UNNotificationContent? {
        guard let content = open(userInfo: original.userInfo, key: key),
              let rewritten = original.mutableCopy() as? UNMutableNotificationContent
        else { return nil }
        let unit = group.flatMap { WidgetSnapshot.read(from: $0, variant: variant.rawValue) }?.amountUnit ?? .bip177
        let copy = copy(content, unit: unit, defaults: group ?? .standard)
        rewritten.title = copy.title
        rewritten.body = copy.body
        return rewritten
    }
}
