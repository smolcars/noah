import BarkBoltFFI
import Foundation

/// Encrypted wallet backups, ported from `BackupService` in
/// `client/src/lib/backupService.ts` (v2 objects): snapshot the open
/// wallet, skip the upload when nothing changed, otherwise seal it into a
/// `.noahbackup`, upload it to the presigned S3 URL and confirm it.
struct BackupService: Sendable {
    struct Outcome: Equatable, Sendable {
        let backupId: String?
        let snapshotSha256: String
        let uploaded: Bool
    }

    let session: WalletSession
    let client: Client
    let readMnemonic: @Sendable () throws -> String
    /// Snapshots and zip staging are plaintext: each operation gets its own
    /// folder, removed afterwards and swept at launch if the app died.
    var scratch: ScratchSpace = .shared
    static let uploadSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 60
        return URLSession(configuration: configuration)
    }()
    var uploadSession: URLSession = Self.uploadSession

    func registerBackup() async throws {
        _ = try await client.updateBackupSettings(body: .json(.init(backupEnabled: true))).ok
    }

    /// `onUploadRequired` runs once the snapshot is known to differ from
    /// the last upload (RN marks the backup in progress there).
    func performBackup(
        lastUploadedSnapshotSha256: String?, onUploadRequired: @Sendable () -> Void = {}) async throws -> Outcome {
        try await scratch.withFolder("backup") { folder in
            try await performBackup(
                lastUploadedSnapshotSha256: lastUploadedSnapshotSha256, onUploadRequired: onUploadRequired, in: folder)
        }
    }

    private func performBackup(
        lastUploadedSnapshotSha256: String?, onUploadRequired: @Sendable () -> Void, in folder: URL) async throws -> Outcome {
        let snapshotURL = folder.appending(path: "wallet-snapshot.sqlite")
        let encryptedURL = folder.appending(path: "wallet-backup.noahbackup")
        let snapshot = try await session.createSnapshot(at: snapshotURL)
        if snapshot.sha256 == lastUploadedSnapshotSha256 {
            return Outcome(backupId: nil, snapshotSha256: snapshot.sha256, uploaded: false)
        }
        onUploadRequired()

        let encrypted = try BackupArchive.seal(
            snapshot: snapshotURL, manifest: Self.manifest(for: snapshot), mnemonic: try readMnemonic(),
            to: encryptedURL, workDirectory: folder)

        let upload = try await client.initiateBackupObjectUpload(
            body: .json(.init(
                encryptedSha256: encrypted.sha256,
                encryptedSize: Int64(encrypted.sizeBytes),
                formatVersion: Int32(BackupArchive.formatVersion)))).ok.body.json

        try await put(file: encrypted, to: upload.uploadUrl, checksum: upload.checksumSha256)
        _ = try await client.completeBackupObjectUpload(body: .json(.init(backupId: upload.backupId))).ok
        return Outcome(backupId: upload.backupId, snapshotSha256: snapshot.sha256, uploaded: true)
    }

    /// `.noahbackup` for the Export screen, written to `destination`.
    func exportEncryptedBackup(to destination: URL) async throws -> BackupArchive.EncryptedFile {
        try await scratch.withFolder("export") { folder in
            let snapshotURL = folder.appending(path: "wallet-snapshot.sqlite")
            let snapshot = try await session.createSnapshot(at: snapshotURL)
            return try BackupArchive.seal(
                snapshot: snapshotURL, manifest: Self.manifest(for: snapshot), mnemonic: try readMnemonic(),
                to: destination, workDirectory: folder)
        }
    }

    static func manifest(for snapshot: WalletSnapshotInfo, createdAt: Date = Date()) -> BackupArchive.Manifest {
        BackupArchive.Manifest(
            formatVersion: BackupArchive.formatVersion,
            createdAt: BackupArchive.timestamp(createdAt),
            snapshotSha256: snapshot.sha256,
            network: snapshot.network,
            walletFingerprint: snapshot.walletFingerprint,
            serverPubkey: snapshot.serverPubkey,
            mailboxPubkey: snapshot.mailboxPubkey,
            barkSchemaVersion: Int(snapshot.schemaVersion))
    }

    struct UploadFailed: Error, Equatable {
        let status: Int
    }

    /// PUT with the same headers as the RN app; the presigned URL is never logged.
    private func put(file: BackupArchive.EncryptedFile, to url: String, checksum: String) async throws {
        guard let target = URL(string: url) else { throw URLError(.badURL) }
        var request = URLRequest(url: target, timeoutInterval: 60)
        request.httpMethod = "PUT"
        request.setValue(String(file.sizeBytes), forHTTPHeaderField: "Content-Length")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue(checksum, forHTTPHeaderField: "x-amz-checksum-sha256")
        let (_, response) = try await uploadSession.upload(for: request, fromFile: file.url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw UploadFailed(status: status) }
    }
}
