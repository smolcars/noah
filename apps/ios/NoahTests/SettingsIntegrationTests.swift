import BarkBoltFFI
import Foundation
import Testing
@testable import Noah

/// Opt-in live M5 acceptance against the existing server, never starts infrastructure.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SETTINGS_IT"] == "1"))
@MainActor
struct SettingsIntegrationTests {
    @Test func backupSettingsExportAndFeedback() async throws {
        SettingsStorage.bootstrap()
        let root = try ScratchSpace.shared.makeFolder("settings-it")
        defer { ScratchSpace.shared.discard(root) }
        do {
            let storage = try SettingsStorage(rootPath: root.appending(path: "mmkv").path, instanceID: UUID().uuidString)
            let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
            let session = WalletSession(variant: .regtest, location: location)
            let phrase = try generateMnemonic()
            try await session.createNew(mnemonic: phrase, runDaemon: false, appVersion: "settings-it")
            do {
                let memory = BackupIntegrationTests.Memory()
                let tokens = AuthTokenProvider(dependencies: .init(
                    readToken: { memory.lock.withLock { memory.token } },
                    saveToken: { token in memory.lock.withLock { memory.token = token } },
                    clearToken: { memory.lock.withLock { memory.token = nil } },
                    readMnemonic: { phrase },
                    login: { try await ServerClient.login(variant: .regtest, mnemonic: $0) }, now: { Date() }))
                let client = ServerClient.make(variant: .regtest, tokens: tokens)
                let server = ServerRegistration(variant: .regtest, settings: storage, session: session, client: client)
                try await server.ensureRegistered()
                try storage.setPersistedState(PersistedWalletState(isInitialized: true, isWalletLoaded: true), key: PersistedWalletState.key, version: 0)
                let service = BackupService(session: session, client: client, readMnemonic: { phrase })
                let coordinator = BackupCoordinator(dependencies: .live(service: service, session: session, settings: storage))
                try await server.setBackupEnabled(true)
                try await coordinator.flush(.manual, requireEnabled: false)
                let backups = try await client.listBackupObjects().ok.body.json
                let backup = try #require(backups.first)
                _ = try await client.deleteBackupObject(body: .json(.init(backupId: backup.backupId))).ok
                await coordinator.forgetUploadedBackup(backup.backupId)
                #expect(storage.persistedState(PersistedBackupState.self, key: PersistedBackupState.key)?.lastUploadedSnapshotSha256 == nil)
                #expect(try await client.listBackupObjects().ok.body.json.isEmpty)
                try await server.setBackupEnabled(false)
                // Manual backups remain eligible even when automatic backups are disabled.
                try await coordinator.flush(.manual, requireEnabled: false)
                let manual = try await client.listBackupObjects().ok.body.json
                #expect(manual.count == 1)
                for item in manual { _ = try await client.deleteBackupObject(body: .json(.init(backupId: item.backupId))).ok }
                await coordinator.cancelScheduled()
                let exported = try await service.exportEncryptedBackup(to: root.appending(path: "export.noahbackup"))
                #expect(try Data(contentsOf: exported.url).first == 2)
                let opened = try BackupArchive.open(encrypted: exported.url, mnemonic: phrase, into: root.appending(path: "opened"))
                #expect(opened.manifest.network == "regtest")
                let feedback = try FeedbackDraft(name: "Noah iOS Settings acceptance", subject: "Regtest native Settings verification", body: "Automated acceptance test of the native iOS feedback payload. No action required.").payload(device: DeviceInfo.current())
                _ = try await client.submitSupportTicket(body: .json(feedback)).ok
            } catch {
                try await session.close()
                throw error
            }
            try await session.close()
        }
    }
}
