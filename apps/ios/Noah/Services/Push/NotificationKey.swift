import CryptoKit
import Foundation

/// The key the server seals an LNURL-pay payment's payer data with in its push (docs/lnurl_pay.md). Registered with
/// the push token and kept in the app group, where the notification service extension reads it. Readable after the
/// first unlock, since pushes arrive while the phone is locked; left out of device backups. One that can't be read is
/// replaced at the next registration.
struct NotificationKey: Sendable {
    private let url: URL

    init?(appGroup: String) {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
        else { return nil }
        url = container.appending(path: "notification-key")
    }

    /// The stored key, or nil when there is none or it can't be read.
    func current() -> SymmetricKey? {
        guard let data = try? Data(contentsOf: url), data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    /// The stored key, made and stored first when there is none (or it can't be read).
    func currentOrCreate() throws -> SymmetricKey {
        if let key = current() { return key }
        let key = SymmetricKey(size: .bits256)
        try key.withUnsafeBytes { Data($0) }
            .write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var stored = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? stored.setResourceValues(values)
        return key
    }

    /// Base64 for `register_push_token`; nil when it can't be stored, so the token still registers.
    static func registrationValue(variant: AppVariant = .current) -> String? {
        guard let key = try? Self(appGroup: variant.appGroup)?.currentOrCreate() else { return nil }
        return key.withUnsafeBytes { Data($0) }.base64EncodedString()
    }
}
