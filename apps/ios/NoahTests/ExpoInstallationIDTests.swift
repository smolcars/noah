import Foundation
@testable import Noah
import Security
import Testing

@Suite(.serialized)
struct ExpoInstallationIDTests {
    /// Fake bundle ID so the real Expo item is never touched.
    private let bundleID = "com.noah.tests.expo.\(UUID().uuidString)"
    private let defaults: UserDefaults

    init() throws {
        defaults = try #require(UserDefaults(suiteName: "com.noah.tests.expo.\(UUID().uuidString)"))
    }

    private func deleteItem() {
        let key = Data(ExpoInstallationID.key.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: bundleID,
            kSecAttrGeneric as String: key,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }

    @Test func createsOnceAndReturnsSameID() throws {
        defer { deleteItem() }
        let first = try ExpoInstallationID.getOrCreate(bundleIdentifier: bundleID, defaults: defaults)
        let second = try ExpoInstallationID.getOrCreate(bundleIdentifier: bundleID, defaults: defaults)
        #expect(first == second)
        #expect(UUID(uuidString: first) != nil)
    }

    @Test func migratesLegacyUserDefaultsValue() throws {
        defer { deleteItem() }
        let legacy = "9F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0"
        defaults.set(legacy, forKey: ExpoInstallationID.key)
        #expect(try ExpoInstallationID.getOrCreate(bundleIdentifier: bundleID, defaults: defaults) == legacy)
        #expect(defaults.string(forKey: ExpoInstallationID.key) == nil)
        #expect(try ExpoInstallationID.getOrCreate(bundleIdentifier: bundleID, defaults: defaults) == legacy)
    }

    @Test func readsItemWrittenByExpo() throws {
        defer { deleteItem() }
        let key = Data(ExpoInstallationID.key.utf8)
        let existing = "0C2A6F0E-1B8D-4C7A-9E55-3F1D2B4A6C8E"
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: bundleID,
            kSecAttrGeneric as String: key,
            kSecAttrAccount as String: key,
            kSecValueData as String: Data(existing.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        #expect(SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess)
        #expect(try ExpoInstallationID.getOrCreate(bundleIdentifier: bundleID, defaults: defaults) == existing)
    }

    @Test func concurrentFirstUseAgreesOnOneID() async throws {
        defer { deleteItem() }
        let bundleID = bundleID
        let suite = "com.noah.tests.expo.\(UUID().uuidString)"
        let ids = try await withThrowingTaskGroup(of: String.self) { group in
            for _ in 0..<8 {
                group.addTask {
                    try ExpoInstallationID.getOrCreate(
                        bundleIdentifier: bundleID, defaults: #require(UserDefaults(suiteName: suite)))
                }
            }
            return try await group.reduce(into: Set<String>()) { $0.insert($1) }
        }
        #expect(ids.count == 1)
        #expect(try ExpoInstallationID.getOrCreate(bundleIdentifier: bundleID, defaults: defaults) == ids.first)
    }
}
