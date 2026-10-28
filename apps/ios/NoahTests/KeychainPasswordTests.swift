import Foundation
@testable import Noah
import Security
import Testing

/// Uses throwaway service names; never touches the real seed items.
@Suite(.serialized)
struct KeychainPasswordTests {
    private let item = KeychainPassword(service: "com.noah.tests.\(UUID().uuidString)", account: "noah")

    /// Writes an item exactly like react-native-keychain 10
    /// `setGenericPassword("noah", value, { service })` on iOS.
    private func addLikeReactNativeKeychain(_ value: String) -> OSStatus {
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: item.service,
            kSecAttrAccount as String: "noah",
            kSecAttrSynchronizable as String: false,
            kSecValueData as String: Data(value.utf8),
            kSecUseDataProtectionKeychain as String: true,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        return SecItemAdd(attributes as CFDictionary, nil)
    }

    private func deleteItem() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: item.service,
            kSecUseDataProtectionKeychain as String: true,
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func accessibility() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: item.service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: true,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let attributes = result as? [String: Any]
        else { return nil }
        return attributes[kSecAttrAccessible as String] as? String
    }

    @Test func readsItemWrittenByReactNativeKeychain() {
        defer { deleteItem() }
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        #expect(addLikeReactNativeKeychain(mnemonic) == errSecSuccess)
        #expect(item.read() == .found(mnemonic))
    }

    @Test func twentyFourWordSeedRoundTripsWithoutRewriting() throws {
        let seedItem = KeychainPassword(service: "com.noah.mnemonic.test-\(UUID().uuidString)", account: "noah")
        defer { try? seedItem.deleteWalletSeed() }
        let phrase = (Array(repeating: RecoveryPhraseInput.wordList[0], count: 23) + [RecoveryPhraseInput.wordList[102]]).joined(separator: " ")
        try seedItem.save(phrase)
        let matches = seedItem.read() == .found(phrase)
        #expect(matches)
        try StoredSeed.requireUsable(seedItem.read())
        let normalized = try StoredSeed.normalized(phrase)
        let unchanged = normalized == phrase
        #expect(unchanged)
    }

    @Test func saveUpdatesInPlaceAndKeepsAttributes() throws {
        defer { deleteItem() }
        #expect(addLikeReactNativeKeychain("first") == errSecSuccess)
        try item.save("second")
        #expect(item.read() == .found("second"))
        #expect(accessibility() == (kSecAttrAccessibleAfterFirstUnlock as String))
    }

    @Test func saveCreatesWithReactNativeAttributes() throws {
        defer { deleteItem() }
        try item.save("value")
        #expect(item.read() == .found("value"))
        #expect(accessibility() == (kSecAttrAccessibleAfterFirstUnlock as String))
    }

    @Test func variantServiceNamesMatchReactNative() {
        #expect(KeychainPassword.mnemonic(for: .mainnet).service == "com.noah.mnemonic.mainnet")
        #expect(KeychainPassword.mnemonic(for: .regtest).account == "noah")
        #expect(KeychainPassword.serverAuthToken(for: .signet).service == "com.noah.auth-token.signet")
    }
}
