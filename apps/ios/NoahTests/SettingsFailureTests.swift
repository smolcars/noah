import Darwin
import Foundation
import Testing
@testable import Noah

struct SettingsFailureTests {
    @Test(arguments: [false, true])
    func unreadableSettingsAndUnwritableDirectoryKeepWalletAdmission(existing: Bool) throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "settings-failure-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let root = directory.appending(path: "mmkv")
        let file = URL(fileURLWithPath: root.path + ".native-settings.json")
        let prior = existing ? try SettingsStorage(rootPath: root.path) : nil
        if let prior {
            try prior.setPersistedState(PersistedWalletState(isInitialized: true, isBiometricsEnabled: true, isWalletSuspended: true), key: PersistedWalletState.key, version: 0)
        }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: directory.appending(path: "wallet"))
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try Data("existing wallet".utf8).write(to: location.database)
        try Data("unreadable".utf8).write(to: file)
        #expect(chmod(file.path, 0) == 0)
        #expect(chmod(directory.path, 0o500) == 0)
        defer {
            chmod(directory.path, 0o700); chmod(file.path, 0o600)
            try? FileManager.default.removeItem(at: directory)
        }
        let settings = try prior ?? SettingsStorage(rootPath: root.path)
        let wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
        #expect(wallet?.isInitialized != false) // Seed + DB still selects Home.
        #expect(wallet?.isBiometricsEnabled != true && wallet?.isWalletSuspended != true)
        #expect(settings.persistedState(PersistedPrivacyState.self, key: PersistedPrivacyState.key)?.isHomeBalanceHidden != true)
        #expect(try settings.takeUpgradeNotices() == [SettingsStorage.settingsNotice])
        #expect(try settings.takeUpgradeNotices().isEmpty)
        let stale = try SettingsStorage(rootPath: root.path)
        #expect(try stale.takeUpgradeNotices().isEmpty)
        // The headless gate must reject defaults before reading the seed, even with an existing DB.
        #expect(throws: (any Error).self) {
            _ = try HeadlessWalletGate.mnemonic(location: location, settings: settings, readSeed: {
                Issue.record("Background must not read the seed under unpersisted settings")
                return .notFound
            })
        }
        #expect(throws: (any Error).self) { try settings.requireHealthy() }
        #expect(throws: (any Error).self) { try settings.setDurably("unsaved", forKey: "key") }
        #expect(settings.string(forKey: "key") == nil)
        #expect(throws: (any Error).self) { try RecurringStore(settings: settings).checkAutomationAllowed() }
        #expect(chmod(directory.path, 0o700) == 0)
        #expect(chmod(file.path, 0o600) == 0)
        try settings.setDurably("saved", forKey: "key")
        try settings.requireHealthy()
        #expect(stale.string(forKey: "key") == "saved")
        try stale.setDurably("other", forKey: "second")
        #expect(settings.string(forKey: "key") == "saved")
        let reopened = try SettingsStorage(rootPath: root.path)
        #expect(reopened.string(forKey: "key") == "saved")
        #expect(try reopened.takeUpgradeNotices().isEmpty)
    }

    @MainActor @Test func recurringSubmissionStopsWhenSettingsCannotPersist() async throws {
        let h = try RecurringExecutorTests.Harness()
        _ = try h.add()
        defer { chmod(h.directory.path, 0o700); try? FileManager.default.removeItem(at: h.directory.deletingLastPathComponent()) }
        // The policy is readable, but the executor cannot persist its next state.
        #expect(chmod(h.directory.path, 0o500) == 0)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 0)
        #expect(throws: (any Error).self) { try h.store.checkAutomationAllowed() }
        let authority = RecurringAutomationAuthority(directory: h.directory.appending(path: "unwritable"))
        #expect(throws: (any Error).self) { try authority.stop() }
        #expect(!authority.allowed())
    }
}
