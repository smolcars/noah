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
/// Reads are identical to Expo; creation is serialized and never replaces
/// an existing ID.
enum ExpoInstallationID {
    enum Failure: Error, Equatable {
        case keychain(OSStatus)
    }

    static let key = "EXDeviceInstallationUUIDKey"

    private static let lock = NSLock()

    /// Inspect continuity without manufacturing an ID on unsupported devices.
    static func existing(
        bundleIdentifier: String = Bundle.main.bundleIdentifier ?? "",
        defaults: UserDefaults = .standard
    ) throws -> String? {
        try lock.withLock { try read(bundleIdentifier: bundleIdentifier) ?? defaults.string(forKey: key) }
    }

    /// Returns the stored ID, migrating the legacy UserDefaults value or
    /// creating a new one exactly like Expo does. Throws instead of creating a
    /// replacement when the Keychain is unreadable (e.g. before first unlock).
    static func getOrCreate(
        bundleIdentifier: String = Bundle.main.bundleIdentifier ?? "",
        defaults: UserDefaults = .standard
    ) throws(Failure) -> String {
        lock.lock()
        defer { lock.unlock() }

        if let existing = try read(bundleIdentifier: bundleIdentifier) {
            return existing
        }
        if let legacy = defaults.string(forKey: key) {
            let stored = try addIfAbsent(legacy, bundleIdentifier: bundleIdentifier)
            defaults.removeObject(forKey: key)
            return stored
        }
        return try addIfAbsent(UUID().uuidString, bundleIdentifier: bundleIdentifier)
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

    /// Adds the ID unless one already exists, and returns whichever is
    /// stored. Unlike Expo (delete + add) this never replaces an ID another
    /// caller may already have registered.
    private static func addIfAbsent(_ value: String, bundleIdentifier: String) throws(Failure) -> String {
        var attributes = searchQuery(bundleIdentifier: bundleIdentifier)
        attributes[kSecValueData as String] = Data(value.utf8)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        switch status {
        case errSecSuccess:
            return value
        case errSecDuplicateItem:
            guard let existing = try read(bundleIdentifier: bundleIdentifier) else {
                throw .keychain(status)
            }
            return existing
        default:
            throw .keychain(status)
        }
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
