import Foundation
import Security

/// The installation UUID expo-notifications registered this device with.
/// Expo push tokens are issued per (installation ID, app ID, project), so the
/// native app must send the same ID to keep the Expo token every user already
/// registered with the Noah server.
///
/// Storage mirrors expo-notifications 58 `ServerRegistrationModule`:
/// Keychain generic password, service = bundle ID, account and generic =
/// UTF-8 "EXDeviceInstallationUUIDKey", `AfterFirstUnlockThisDeviceOnly`,
/// with a legacy `UserDefaults.standard` fallback under the same key.
enum ExpoInstallationID {
    enum Failure: Error, Equatable {
        case keychain(OSStatus)
    }

    static let key = "EXDeviceInstallationUUIDKey"

    /// Returns the stored ID, migrating the legacy UserDefaults value or
    /// creating a new one exactly like Expo does. Throws instead of creating a
    /// replacement when the Keychain is unreadable (e.g. before first unlock).
    static func getOrCreate(
        bundleIdentifier: String = Bundle.main.bundleIdentifier ?? "",
        defaults: UserDefaults = .standard
    ) throws(Failure) -> String {
        if let existing = try read(bundleIdentifier: bundleIdentifier) {
            return existing
        }
        if let legacy = defaults.string(forKey: key) {
            try store(legacy, bundleIdentifier: bundleIdentifier)
            defaults.removeObject(forKey: key)
            return legacy
        }
        let created = UUID().uuidString
        try store(created, bundleIdentifier: bundleIdentifier)
        return created
    }

    private static func read(bundleIdentifier: String) throws(Failure) -> String? {
        var query = searchQuery(bundleIdentifier: bundleIdentifier)
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnData as String] = true

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { return nil }
            return String(data: data, encoding: .utf8)
        case errSecItemNotFound:
            return nil
        default:
            throw .keychain(status)
        }
    }

    private static func store(_ value: String, bundleIdentifier: String) throws(Failure) {
        let search = searchQuery(bundleIdentifier: bundleIdentifier)
        SecItemDelete(search as CFDictionary)
        var attributes = search
        attributes[kSecValueData as String] = Data(value.utf8)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw .keychain(status) }
    }

    private static func searchQuery(bundleIdentifier: String) -> [String: Any] {
        let encodedKey = Data(key.utf8)
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: bundleIdentifier,
            kSecAttrGeneric as String: encodedKey,
            kSecAttrAccount as String: encodedKey,
        ]
    }
}
