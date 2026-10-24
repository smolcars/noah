import Foundation
import Testing
@testable import Noah

/// Advanced: five taps unlock debug mode, its switch turns it off again, and Suspend asks first.
@MainActor struct SettingsDebugSuspendTests {
    @Test func fiveTapsWithinTwoSecondsEachUnlockDebug() {
        var unlock = DebugUnlock()
        let start = Date(timeIntervalSince1970: 1_000)
        for tap in 0..<4 {
            let unlocked = unlock.tap(at: start.addingTimeInterval(Double(tap) * 1.5))
            #expect(!unlocked)
        }
        #expect(unlock.remaining == 1)
        let fifth = unlock.tap(at: start.addingTimeInterval(6))
        #expect(fifth)
        #expect(unlock.count == 0)
    }

    @Test func aPauseLongerThanTwoSecondsStartsOver() {
        var unlock = DebugUnlock()
        let start = Date(timeIntervalSince1970: 1_000)
        for tap in 0..<4 { _ = unlock.tap(at: start.addingTimeInterval(Double(tap))) }
        let late = unlock.tap(at: start.addingTimeInterval(3 + 2.5))
        #expect(!late)
        #expect(unlock.count == 1)
    }

    @Test func debugModeTurnsOffAndOnInTodaysFlag() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "settings-debug-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = try SettingsStorage(rootPath: root.path)
        #expect(!DebugMode.isOn(storage))
        try DebugMode.set(true, storage)
        #expect(storage.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isDebugModeEnabled == true)
        let summary = SettingsSummary(dependencies: .init(overview: { throw CancellationError() }, notificationsOn: { true }, storage: storage))
        #expect(summary.debug)
        try summary.setDebug(false)
        #expect(!summary.debug && !DebugMode.isOn(storage))
        // The rest of the wallet state is untouched.
        var wallet = PersistedWalletState()
        wallet.isBiometricsEnabled = true
        try storage.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        try DebugMode.set(true, storage)
        #expect(storage.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isBiometricsEnabled == true)
    }

    @Test func suspendingAsksFirstAndResumingDoesNot() {
        #expect(SuspendSwitch.action(suspended: false, requested: true) == .confirm)
        #expect(SuspendSwitch.action(suspended: true, requested: false) == .resume)
        #expect(SuspendSwitch.action(suspended: true, requested: true) == .none)
        #expect(SuspendSwitch.action(suspended: false, requested: false) == .none)
    }

    @Test func hideAmountsIsHomesPersistedFlag() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "settings-hide-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = try SettingsStorage(rootPath: root.path)
        let app = AppModel(settings: storage)
        #expect(!app.isBalanceHidden)
        // Settings' switch writes the same property Home's long-press toggles.
        app.isBalanceHidden = true
        #expect(storage.persistedState(PersistedPrivacyState.self, key: PersistedPrivacyState.key)?.isHomeBalanceHidden == true)
        #expect(AppModel(settings: storage).isBalanceHidden)
    }

    @Test func deleteNeedsTheWordTrimmedInAnyCase() {
        #expect(DeleteChecklist.confirms(" Delete "))
        #expect(DeleteChecklist.confirms("DELETE"))
        #expect(!DeleteChecklist.confirms("delet"))
        #expect(!DeleteChecklist.confirms("delete wallet"))
    }

    @Test func deleteChecklistShowsWhatYoudNeedToRestore() {
        var backup = PersistedBackupState()
        let none = DeleteChecklist(summary: nil, needsPhraseBackup: true, backup: backup)
        #expect(none.balance == nil && !none.phraseSaved && none.backup == .never)
        backup.lastBackupAt = 1_760_000_000_000
        backup.lastBackupStatus = .success
        let saved = DeleteChecklist(summary: HomeShotTests.summary(ark: 142_742, onchain: 10_000), needsPhraseBackup: false, backup: backup)
        #expect(saved.balance == 152_742 && saved.phraseSaved && saved.backup == .at(Date(timeIntervalSince1970: 1_760_000_000)))
        backup.lastBackupStatus = .failed
        #expect(DeleteChecklist(summary: nil, needsPhraseBackup: false, backup: backup).backup == .failed)
    }
}
