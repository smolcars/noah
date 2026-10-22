import Foundation
import Testing
@testable import Noah

/// Home asks for a backup until the user confirms one. Only a restore counts
/// as backed up; RN and earlier native builds never recorded an acknowledgement.
struct RecoveryPhraseBackupTests {
    static let rnWallet = #"{"state":{"isInitialized":true,"isWalletLoaded":true,"staticVtxoPubkey":"02ab","isBiometricsEnabled":false,"isDebugModeEnabled":false,"isWalletSuspended":false,"hasSeenBatteryOptimizationPrompt":true},"version":0}"#

    private func storage() throws -> (SettingsStorage, URL) {
        let root = FileManager.default.temporaryDirectory.appending(path: "phrase-backup-\(UUID())")
        return (try SettingsStorage(rootPath: root.appending(path: "mmkv").path), root)
    }

    private func wallet(_ settings: SettingsStorage) -> PersistedWalletState? {
        settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
    }

    private func backup(_ settings: SettingsStorage) -> PersistedRecoveryPhraseBackup {
        settings.persistedState(PersistedRecoveryPhraseBackup.self, key: PersistedRecoveryPhraseBackup.key) ?? .init()
    }

    @Test func reactNativeMigrationOpensHomeWithThePrompt() throws {
        // The RN import path, as SettingsStorage runs it over the MMKV snapshot.
        let imported = SettingsStorage.importValues([PersistedWalletState.key: Self.rnWallet])
        #expect(imported.values[PersistedWalletState.key] == Self.rnWallet) // Imported byte for byte.
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        settings.set(Self.rnWallet, forKey: PersistedWalletState.key)
        let state = try #require(wallet(settings))
        #expect(!AppModel.resumesOnboarding(state))
        #expect(backup(settings).isBackedUp == nil)
        #expect(backup(settings).needsBackup)
        #expect(settings.string(forKey: PersistedWalletState.key) == Self.rnWallet) // RN's store is never rewritten.
    }

    @Test func earlierNativeWalletOpensHomeWithThePrompt() throws {
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        try settings.setPersistedState(PersistedWalletState(isInitialized: true, isWalletLoaded: true, staticVtxoPubkey: "02cd"),
                                       key: PersistedWalletState.key, version: PersistedWalletState.version)
        let reopened = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        let state = try #require(wallet(reopened))
        #expect(!AppModel.resumesOnboarding(state))
        #expect(backup(reopened).needsBackup)
    }

    @Test func missingOrDamagedSettingsStillOpenHome() throws {
        #expect(!AppModel.resumesOnboarding(nil))
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        settings.set("not json", forKey: PersistedWalletState.key)
        settings.set("not json", forKey: PersistedRecoveryPhraseBackup.key)
        #expect(wallet(settings) == nil)
        #expect(!AppModel.resumesOnboarding(wallet(settings)))
        #expect(backup(settings).needsBackup)
    }

    @Test func interruptedReactNativeOnboardingResumes() throws {
        let raw = Self.rnWallet.replacingOccurrences(of: #""isInitialized":true"#, with: #""isInitialized":false"#)
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        settings.set(raw, forKey: PersistedWalletState.key)
        let state = try #require(wallet(settings))
        #expect(AppModel.resumesOnboarding(state))
    }

    @Test func restoredWalletOpensHomeWithoutThePromptAndCreationResetsIt() throws {
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        // What `restoreWallet` writes before installing the backup, then completion.
        try settings.beginOnboarding(recoveryPhraseBackedUp: true)
        #expect(settings.completeRestore())
        let restored = try #require(wallet(settings))
        #expect(restored.isInitialized == true)
        #expect(!AppModel.resumesOnboarding(restored))
        #expect(!backup(settings).needsBackup)

        // A later fresh creation never inherits the restored wallet's flag.
        try settings.beginOnboarding(recoveryPhraseBackedUp: false)
        #expect(backup(settings).needsBackup)
        #expect(wallet(settings)?.isInitialized == false)
    }

    @Test func flagSurvivesReopenAndUnrelatedWalletWrites() throws {
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        try settings.beginOnboarding(recoveryPhraseBackedUp: true)
        var state = try #require(wallet(settings))
        state.isBiometricsEnabled = true
        try settings.setPersistedState(state, key: PersistedWalletState.key, version: PersistedWalletState.version)
        let reopened = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        #expect(backup(reopened).isBackedUp == true)
        #expect(wallet(reopened)?.isBiometricsEnabled == true)
    }

    @Test func deletingTheWalletForgetsTheFlag() throws {
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        try settings.beginOnboarding(recoveryPhraseBackedUp: true)
        try settings.removeAllDurably()
        #expect(settings.string(forKey: PersistedRecoveryPhraseBackup.key) == nil)
        #expect(backup(settings).needsBackup)
    }

    @MainActor @Test func confirmingTheBackupClearsThePrompt() throws {
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        settings.set(Self.rnWallet, forKey: PersistedWalletState.key)
        let model = AppModel(settings: settings)
        try model.confirmRecoveryPhraseBackedUp()
        #expect(!model.needsRecoveryPhraseBackup)
        #expect(backup(settings).isBackedUp == true)
        #expect(settings.string(forKey: PersistedWalletState.key) == Self.rnWallet)
    }
}
