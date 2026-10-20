import Foundation
import Testing
@testable import Noah

struct SettingsNoticeTests {
    @Test func noticeNamesOnlyPreferencesThatWereDefaulted() throws {
        for walletLost in [false, true] { for privacyLost in [false, true] { for profileLost in [false, true] {
            let directory = FileManager.default.temporaryDirectory.appending(path: "notice-\(UUID())")
            defer { try? FileManager.default.removeItem(at: directory) }
            let settings = try SettingsStorage(rootPath: directory.appending(path: "mmkv").path)
            let state = SettingsStorage.importValues([
                "wallet-storage": walletLost ? "broken" : #"{"state":{"isBiometricsEnabled":true},"version":0}"#,
                "privacy-storage": privacyLost ? "broken" : #"{"state":{"isHomeBalanceHidden":true},"version":0}"#,
                "profile-storage": profileLost ? "broken" : #"{"state":{"preferredCurrency":"EUR"},"version":0}"#,
            ])
            try settings.nativeFile.save(state)
            let messages = try settings.takeUpgradeNotices()
            #expect(messages.isEmpty == !(walletLost || privacyLost || profileLost))
            if let message = messages.first {
                #expect(message.contains("biometric lock is off") == walletLost)
                #expect(message.contains("balances are visible") == privacyLost)
            }
            #expect(try settings.takeUpgradeNotices().isEmpty)
            #expect(try SettingsStorage(rootPath: directory.appending(path: "mmkv").path).takeUpgradeNotices().isEmpty)
        } } }
    }
}
