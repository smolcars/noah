import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// Real backup round trip against a Noah server with S3 configured (run the
/// server from the dev `.env`, then pass
/// `TEST_RUNNER_NOAH_BACKUP_IT=1 TEST_RUNNER_NOAH_REGTEST_SERVER_URL=http://localhost:3100`).
/// Throwaway wallet in a temporary datadir; never touches the app's data.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_BACKUP_IT"] == "1"))
struct BackupIntegrationTests {
    final class Memory: @unchecked Sendable {
        let lock = NSLock()
        var token: String?
    }

    @Test func backsUpSkipsUnchangedAndRestoresTheSameSnapshot() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "backup-it-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
        let session = WalletSession(variant: .regtest, location: location)
        let mnemonic = try generateMnemonic()
        try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "backup-it")
        defer { Task { try? await session.close() } }

        let memory = Memory()
        let tokens = AuthTokenProvider(dependencies: .init(
            readToken: { memory.lock.withLock { memory.token } },
            saveToken: { token in memory.lock.withLock { memory.token = token } },
            clearToken: { memory.lock.withLock { memory.token = nil } },
            readMnemonic: { mnemonic },
            login: { try await ServerClient.login(variant: .regtest, mnemonic: $0) },
            now: { Date() }))
        let client = ServerClient.make(variant: .regtest, tokens: tokens)
        _ = try await client.register(body: .json(.init())).ok

        let service = BackupService(
            session: session, client: client, readMnemonic: { mnemonic },
            scratch: ScratchSpace(root: root.appending(path: "scratch")))
        try await service.registerBackup()

        let first = try await service.performBackup(lastUploadedSnapshotSha256: nil)
        #expect(first.uploaded)
        let backupId = try #require(first.backupId)

        let unchanged = try await service.performBackup(lastUploadedSnapshotSha256: first.snapshotSha256)
        #expect(!unchanged.uploaded, "an unchanged wallet must not upload again")

        let listed = try await client.listBackupObjects().ok.body.json
        #expect(listed.contains { $0.backupId == backupId && $0.formatVersion == 2 })

        let download = try await client.getBackupObjectDownloadUrl(body: .json(.init(backupId: backupId))).ok.body.json
        let (data, response) = try await URLSession.shared.data(from: try #require(URL(string: download.downloadUrl)))
        #expect((response as? HTTPURLResponse)?.statusCode == 200)
        let encrypted = root.appending(path: "downloaded.noahbackup")
        try data.write(to: encrypted)
        #expect(BackupArchive.sha256Hex(data) == download.backup.encryptedSha256)

        let opened = try BackupArchive.open(encrypted: encrypted, mnemonic: mnemonic, into: root.appending(path: "restore"))
        #expect(opened.manifest.snapshotSha256 == first.snapshotSha256)
        #expect(opened.manifest.network == "regtest")
        let validated = try await validateWalletSnapshot(
            path: opened.snapshot.path,
            expected: .init(network: "regtest", walletFingerprint: opened.manifest.walletFingerprint))
        #expect(validated.sha256 == opened.manifest.snapshotSha256)
        #expect(Int(validated.schemaVersion) == opened.manifest.barkSchemaVersion)

        // Restore into a different, empty datadir with its own seed item.
        let original = try #require(try await session.summary())
        let restoreRoot = root.appending(path: "device-b")
        try FileManager.default.createDirectory(at: restoreRoot, withIntermediateDirectories: true)
        let restoredLocation = WalletDataLocation(variant: .regtest, documentsDirectory: restoreRoot)
        let restoredSession = WalletSession(variant: .regtest, location: restoredLocation)
        let seedItem = KeychainPassword(service: "com.noah.tests.restore.\(UUID().uuidString)", account: "noah")
        defer { seedItem.remove() }
        let restorer = WalletRestorer(
            variant: .regtest, session: restoredSession, location: restoredLocation, seed: seedItem,
            makeClient: { phrase in
                let scratch = Memory()
                return ServerClient.make(variant: .regtest, tokens: AuthTokenProvider(dependencies: .init(
                    readToken: { scratch.lock.withLock { scratch.token } },
                    saveToken: { token in scratch.lock.withLock { scratch.token = token } },
                    clearToken: { scratch.lock.withLock { scratch.token = nil } },
                    readMnemonic: { phrase },
                    login: { try await ServerClient.login(variant: .regtest, mnemonic: $0) },
                    now: { Date() })))
            })

        let outcome = try await restorer.restore(mnemonic: mnemonic)
        #expect(outcome.backupId == backupId)
        #expect(outcome.snapshotSha256 == first.snapshotSha256)
        #expect(seedItem.read() == .found(mnemonic))
        #expect(await !restoredSession.isOpen, "restore leaves the session closed")
        try await restoredSession.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        let restored = try #require(try await restoredSession.summary())
        #expect(restored.fingerprint == original.fingerprint)
        #expect(restored.movementCount == original.movementCount)
        try await restoredSession.close()
        let leftovers = (try? FileManager.default.contentsOfDirectory(atPath: restoredLocation.restoreDirectory.path)) ?? []
        #expect(leftovers.isEmpty, "journal, rollback and decrypted files are cleaned up")
    }
}
