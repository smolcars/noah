import Foundation
import Testing

@testable import Noah

/// Opt-in checkpoints for RestoreTests/README.md on a disposable simulator.
/// Never emits the phrase, JWT or download URLs.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_RESTORE_LIVE_CHECK"] == "1"))
@MainActor
struct RestoreLiveTests {
    @Test func checkpoint() async throws {
        let environment = ProcessInfo.processInfo.environment
        #expect(AppVariant.current == .regtest)
        guard AppVariant.current == .regtest else { return }
        let action = try #require(environment["NOAH_RESTORE_ACTION"])
        let services = AppServices.shared
        try await services.ensureWalletOpen()
        try await services.session.sync()
        let wallet = SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
        #expect(wallet?.isInitialized == true)
        #expect(wallet?.isWalletLoaded == true)
        #expect(!WalletDataLocation(variant: .regtest).restorePending)

        if action == "backup" {
            try await services.backupService.registerBackup()
            try await services.backupCoordinator.flush(.manual, requireEnabled: false)
            let state = SettingsStorage.shared.persistedState(PersistedBackupState.self, key: PersistedBackupState.key)
            #expect(state?.lastBackupId != nil)
            let restoreClient = ServerClient.makeRestore(variant: .regtest, mnemonic: try services.readMnemonic())
            let listed = try await restoreClient.listBackupObjects().ok.body.json
            #expect(listed.contains { $0.backupId == state?.lastBackupId })
            // Test-only export: the runner must copy this privately before
            // uninstalling, then delete it after the phrase-entry check.
            let phraseFile = URL.documentsDirectory.appending(path: "restore-test-phrase.txt")
            try Data(services.readMnemonic().utf8).write(to: phraseFile, options: .completeFileProtection)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: phraseFile.path)
        }
        let summary = try #require(try await services.session.summary())
        let movements = try await services.session.withOpenWallet { try await $0.wallet.history() }
        let report: [String: Any] = [
            "fingerprint": summary.fingerprint, "spendable": summary.spendableSats,
            "movements": movements.sorted { $0.id < $1.id }.map {
                ["id": String($0.id), "status": $0.status, "balance": String($0.effectiveBalanceSats)]
            },
        ]
        try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]).write(
            to: URL.documentsDirectory.appending(path: "restore-check.json"))

        if action == "prepare-phrase" {
            // Delete only Noah's regtest seed, after the runner saved its test copy.
            let mnemonic = try services.readMnemonic()
            try await services.session.close()
            #expect(services.seed.delete(onlyIfValueIs: mnemonic))
        }
    }
}
