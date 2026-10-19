import Foundation
import Security

/// Generic-password Keychain item stored exactly the way react-native-keychain
/// 10 stores `setGenericPassword(username, password, { service })`:
/// UTF-8 password data, `AfterFirstUnlock`, not synchronizable, default
/// access group. Reading and writing the same item keeps the seed phrase
/// readable across the React Native → native update.
struct KeychainPassword: Sendable {
    enum ReadResult: Equatable, Sendable {
        case found(String)
        case notFound
        /// The item exists or may exist but cannot be read right now, e.g.
        /// `errSecInteractionNotAllowed` before first unlock. Never treat this
        /// as "no wallet".
        case unavailable(OSStatus)
    }

    struct WriteError: Error, Equatable {
        let status: OSStatus
    }

    let service: String
    let account: String

    func read() -> ReadResult {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data, let value = String(data: data, encoding: .utf8)
            else {
                return .unavailable(errSecDecode)
            }
            return .found(value)
        case errSecItemNotFound:
            return .notFound
        default:
            return .unavailable(status)
        }
    }

    /// Updates the item in place when it exists, otherwise adds it. Never
    /// deletes first, so a failed write cannot lose the existing value.
    func save(_ value: String) throws(WriteError) {
        let data = Data(value.utf8)
        let updateStatus = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            var attributes = baseQuery
            attributes[kSecValueData as String] = data
            attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            let addStatus = SecItemAdd(attributes as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw WriteError(status: addStatus) }
        default:
            throw WriteError(status: updateStatus)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }
}

extension KeychainPassword {
    /// Username react-native-keychain items were written with (`KEYCHAIN_USERNAME`).
    private static let reactNativeAccount = "noah"

    /// The wallet's BIP39 mnemonic (`client/src/lib/crypto.ts`).
    static func mnemonic(for variant: AppVariant) -> KeychainPassword {
        KeychainPassword(service: "com.noah.mnemonic.\(variant.rawValue)", account: reactNativeAccount)
    }

    /// Server JWT (`AUTH_TOKEN_KEYCHAIN_SERVICE`). Losing it only forces a re-login.
    static func serverAuthToken(for variant: AppVariant) -> KeychainPassword {
        KeychainPassword(service: "com.noah.auth-token.\(variant.rawValue)", account: reactNativeAccount)
    }
}
