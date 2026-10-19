import BarkBoltFFI
import Foundation
import ZIPFoundation

/// Restore from the user's recovery phrase, ported from `restoreWallet` and
/// `BackupService.restoreBackup` in `client/src/lib/backupService.ts`:
/// newest valid v2 backup first (legacy v1 when the server has none),
/// validated against its manifest, swapped in with a rollback copy, opened,
/// and only then committed (seed saved, rollback copy dropped). Any failure
/// puts the previous wallet and seed back.
struct WalletRestorer: Sendable {
    enum Failure: Error, Equatable {
        case walletAlreadyOpen
        case wrongNetwork
        case identityMismatch
        case noUsableBackup
        case unsupportedFormat(Int32)
        case rollbackFailed
    }

    enum Step: Sendable {
        case fetching, validating, loading, finalizing
    }

    let variant: AppVariant
    let session: WalletSession
    let location: WalletDataLocation
    let seed: KeychainPassword
    /// Authenticated client for `mnemonic` whose token is never persisted.
    let makeClient: @Sendable (_ mnemonic: String) -> Client
    var workDirectory: URL = .cachesDirectory
    var downloadSession: URLSession = .shared

    struct Outcome: Equatable, Sendable {
        let backupId: String?
        let snapshotSha256: String?
    }

    private var expectedNetwork: String { variant == .mainnet ? "bitcoin" : variant.rawValue }

    func restore(mnemonic: String, progress: @Sendable (Step) -> Void = { _ in }) async throws -> Outcome {
        guard !(await session.isOpen) else { throw Failure.walletAlreadyOpen }
        let previousSeed: String?
        switch seed.read() {
        case .found(let value): previousSeed = value
        case .notFound: previousSeed = nil
        case .unavailable(let status): throw KeychainPassword.WriteError(status: status)
        }

        progress(.fetching)
        let installer = SnapshotInstaller(datadir: location.directory)
        let (outcome, rollback) = try await fetchAndInstall(mnemonic: mnemonic, installer: installer, progress: progress)

        var walletOpened = false
        var seedChanged = false
        do {
            progress(.loading)
            try await session.openExisting(mnemonic: mnemonic, runDaemon: true, appVersion: "restore")
            walletOpened = true
            progress(.finalizing)
            seedChanged = true
            try seed.save(mnemonic)
        } catch {
            do {
                if walletOpened { try await session.close() }
                if seedChanged {
                    if let previousSeed { try seed.save(previousSeed) } else { seed.delete(onlyIfValueIs: mnemonic) }
                }
                try installer.rollback(rollback)
            } catch {
                throw Failure.rollbackFailed
            }
            throw error
        }
        installer.finalize(rollback: rollback)
        return outcome
    }

    private func fetchAndInstall(
        mnemonic: String, installer: SnapshotInstaller, progress: @Sendable (Step) -> Void
    ) async throws -> (Outcome, URL?) {
        let client = makeClient(mnemonic)
        let backups: [Components.Schemas.BackupObjectInfo]
        switch try await client.listBackupObjects() {
        case .ok(let response):
            backups = try response.body.json
        case .undocumented(statusCode: 404, _):
            backups = []
        case let other:
            _ = try other.ok  // throws with the server's response
            backups = []
        }
        if backups.isEmpty {
            return try await installLegacy(mnemonic: mnemonic, client: client, installer: installer)
        }

        var firstError: Error?
        for backup in backups {
            guard backup.formatVersion == Int32(BackupArchive.formatVersion) else {
                firstError = firstError ?? Failure.unsupportedFormat(backup.formatVersion)
                continue
            }
            do {
                return try await installV2(backup.backupId, mnemonic: mnemonic, client: client, installer: installer, progress: progress)
            } catch {
                firstError = firstError ?? error
                AppLog("restore").w("A retained backup failed validation, trying an older one")
            }
        }
        throw firstError ?? Failure.noUsableBackup
    }

    private func installV2(
        _ backupId: String, mnemonic: String, client: Client, installer: SnapshotInstaller,
        progress: @Sendable (Step) -> Void
    ) async throws -> (Outcome, URL?) {
        let download = try await client.getBackupObjectDownloadUrl(body: .json(.init(backupId: backupId))).ok.body.json
        let operation = UUID().uuidString
        let encrypted = workDirectory.appending(path: "wallet-restore-\(operation).noahbackup")
        let unpacked = workDirectory.appending(path: "wallet-restore-\(operation)", directoryHint: .isDirectory)
        defer {
            try? FileManager.default.removeItem(at: encrypted)
            try? FileManager.default.removeItem(at: unpacked)
        }
        try await downloadFile(download.downloadUrl, to: encrypted)

        progress(.validating)
        let opened = try BackupArchive.open(encrypted: encrypted, mnemonic: mnemonic, into: unpacked)
        let manifest = opened.manifest
        guard manifest.network == expectedNetwork else { throw Failure.wrongNetwork }
        let validated = try await validateWalletSnapshot(
            path: opened.snapshot.path,
            expected: .init(network: manifest.network, walletFingerprint: manifest.walletFingerprint, serverPubkey: manifest.serverPubkey))
        guard validated.sha256 == manifest.snapshotSha256,
            Int(validated.schemaVersion) == manifest.barkSchemaVersion,
            validated.serverPubkey == manifest.serverPubkey,
            validated.mailboxPubkey == manifest.mailboxPubkey
        else { throw Failure.identityMismatch }

        // The restored datadir holds only db.sqlite, as in the RN app.
        let contents = unpacked.appending(path: "datadir", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: contents, withIntermediateDirectories: false)
        try FileManager.default.moveItem(at: opened.snapshot, to: contents.appending(path: "db.sqlite"))
        let rollback = try installer.install(contentsOf: contents)
        return (Outcome(backupId: download.backup.backupId, snapshotSha256: manifest.snapshotSha256), rollback)
    }

    /// v1: base64 text of a v1 envelope around a zip of
    /// `backup_staging/noah-data-<variant>/…`. Installed through the same
    /// rollback swap (the RN app deleted the current data first).
    private func installLegacy(mnemonic: String, client: Client, installer: SnapshotInstaller) async throws -> (Outcome, URL?) {
        let download = try await client.getDownloadUrl(body: .json(.init(backupVersion: nil))).ok.body.json
        let operation = UUID().uuidString
        let base64File = workDirectory.appending(path: "wallet-legacy-\(operation).txt")
        let unpacked = workDirectory.appending(path: "wallet-legacy-\(operation)", directoryHint: .isDirectory)
        defer {
            try? FileManager.default.removeItem(at: base64File)
            try? FileManager.default.removeItem(at: unpacked)
        }
        try await downloadFile(download.downloadUrl, to: base64File)
        let text = String(decoding: try Data(contentsOf: base64File), as: UTF8.self)
        guard let envelope = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw Failure.noUsableBackup
        }
        let zip = try BackupCrypto.decrypt(envelope, mnemonic: mnemonic, expecting: .legacyDirectory)

        let prefix = "backup_staging/noah-data-\(variant.rawValue)/"
        let contents = unpacked.appending(path: "datadir", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: contents, withIntermediateDirectories: true)
        let archive = try Archive(data: zip, accessMode: .read)
        for entry in archive where entry.type == .file && entry.path.hasPrefix(prefix) {
            let relative = String(entry.path.dropFirst(prefix.count))
            guard !relative.isEmpty, !relative.split(separator: "/").contains("..") else { throw Failure.noUsableBackup }
            _ = try archive.extract(entry, to: contents.appending(path: relative))
        }
        guard FileManager.default.fileExists(atPath: contents.appending(path: "db.sqlite").path) else {
            throw Failure.noUsableBackup
        }
        return (Outcome(backupId: nil, snapshotSha256: nil), try installer.install(contentsOf: contents))
    }

    private func downloadFile(_ url: String, to destination: URL) async throws {
        guard let source = URL(string: url) else { throw URLError(.badURL) }
        let (temporary, response) = try await downloadSession.download(for: URLRequest(url: source, timeoutInterval: 60))
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        try FileManager.default.moveItem(at: temporary, to: destination)
    }
}
