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
    var workDirectory: URL = .cachesDirectory
    var uploadSession: URLSession = .shared

    func registerBackup() async throws {
        _ = try await client.updateBackupSettings(body: .json(.init(backupEnabled: true))).ok
    }

    func performBackup(lastUploadedSnapshotSha256: String?) async throws -> Outcome {
        let operation = UUID().uuidString
        let snapshotURL = workDirectory.appending(path: "wallet-snapshot-\(operation).sqlite")
        let encryptedURL = workDirectory.appending(path: "wallet-backup-\(operation).noahbackup")
        defer {
            try? FileManager.default.removeItem(at: snapshotURL)
            try? FileManager.default.removeItem(at: encryptedURL)
        }

        let snapshot = try await session.createSnapshot(at: snapshotURL)
        if snapshot.sha256 == lastUploadedSnapshotSha256 {
            return Outcome(backupId: nil, snapshotSha256: snapshot.sha256, uploaded: false)
        }

        let encrypted = try BackupArchive.seal(
            snapshot: snapshotURL, manifest: Self.manifest(for: snapshot), mnemonic: try readMnemonic(),
            to: encryptedURL, workDirectory: workDirectory)

        let upload = try await client.initiateBackupObjectUpload(
            body: .json(.init(
                encryptedSha256: encrypted.sha256,
                encryptedSize: Int64(encrypted.sizeBytes),
                formatVersion: Int32(BackupArchive.formatVersion)))
        ).ok.body.json

        try await put(file: encrypted, to: upload.uploadUrl, checksum: upload.checksumSha256)
        _ = try await client.completeBackupObjectUpload(body: .json(.init(backupId: upload.backupId))).ok
        return Outcome(backupId: upload.backupId, snapshotSha256: snapshot.sha256, uploaded: true)
    }

    /// `.noahbackup` for the Export screen, written to `destination`.
    func exportEncryptedBackup(to destination: URL) async throws -> BackupArchive.EncryptedFile {
        let snapshotURL = workDirectory.appending(path: "wallet-export-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: snapshotURL) }
        let snapshot = try await session.createSnapshot(at: snapshotURL)
        return try BackupArchive.seal(
            snapshot: snapshotURL, manifest: Self.manifest(for: snapshot), mnemonic: try readMnemonic(),
            to: destination, workDirectory: workDirectory)
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
