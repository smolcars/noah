import CryptoKit
import Foundation
import ZIPFoundation

/// `.noahbackup` v2 files, compatible with the RN app
/// (`encryptWalletSnapshot` / `decryptWalletBackup` in noah-tools): a zip
/// whose root holds `manifest.json` and `db.sqlite`, sealed with
/// `BackupCrypto` version 2.
enum BackupArchive {
    static let formatVersion = 2
    private static let manifestName = "manifest.json"
    private static let snapshotName = "db.sqlite"

    struct Manifest: Equatable, Sendable {
        var formatVersion: Int
        var createdAt: String
        var snapshotSha256: String
        var network: String
        var walletFingerprint: String
        var serverPubkey: String?
        var mailboxPubkey: String?
        var barkSchemaVersion: Int
    }

    struct EncryptedFile: Equatable, Sendable {
        let url: URL
        let sizeBytes: Int
        let sha256: String
    }

    enum Failure: Error, Equatable {
        case destinationExists
        case snapshotMissing
        case unexpectedEntry(String)
        case newerVersion
        case invalidManifest
        /// Inflates past the restore limits (headers can understate sizes).
        case tooLarge
        case checksumMismatch(String)
        case notLegacyBackup
    }

    /// Upper bound on everything a restore unpacks. Wallet databases are a
    /// few MB; this only stops a hostile archive from filling the disk.
    static let maxUnpackedBytes: Int64 = 1 << 30
    private static let maxManifestBytes: Int64 = 64 * 1024
    private static let maxLegacyEntries = 256

    /// Zips `snapshot` with its manifest, encrypts it for `mnemonic` and
    /// writes it to `destination`, which must not exist. The plaintext zip
    /// staging goes in `workDirectory` (a `ScratchSpace` folder in the app).
    static func seal(
        snapshot: URL, manifest: Manifest, mnemonic: String, to destination: URL, workDirectory: URL) throws -> EncryptedFile {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: snapshot.path) else { throw Failure.snapshotMissing }
        guard !fileManager.fileExists(atPath: destination.path) else { throw Failure.destinationExists }

        let staging = workDirectory.appending(path: ".noah-backup-\(UUID().uuidString)", directoryHint: .isDirectory)
        let zip = workDirectory.appending(path: ".noah-backup-\(UUID().uuidString).zip")
        defer {
            try? fileManager.removeItem(at: staging)
            try? fileManager.removeItem(at: zip)
        }
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: false)
        try encode(manifest).write(to: staging.appending(path: manifestName), options: .atomic)
        try fileManager.copyItem(at: snapshot, to: staging.appending(path: snapshotName))
        try fileManager.zipItem(at: staging, to: zip, shouldKeepParent: false, compressionMethod: .deflate)

        let sealed = try BackupCrypto.encrypt(Data(contentsOf: zip), mnemonic: mnemonic, version: .snapshot)
        // Complete file under a temporary name, then a rename that refuses
        // to replace anything at `destination`.
        let partial = destination.deletingLastPathComponent().appending(path: ".\(UUID().uuidString).partial")
        defer { try? fileManager.removeItem(at: partial) }
        try sealed.write(to: partial, options: .atomic)
        try fileManager.moveItem(at: partial, to: destination)
        return EncryptedFile(url: destination, sizeBytes: sealed.count, sha256: sha256Hex(sealed))
    }

    /// Decrypts and unpacks `encrypted` into `directory` (must not exist).
    /// Only `manifest.json` and `db.sqlite` are extracted.
    static func open(encrypted: URL, mnemonic: String, into directory: URL) throws -> (manifest: Manifest, snapshot: URL) {
        let fileManager = FileManager.default
        guard !fileManager.fileExists(atPath: directory.path) else { throw Failure.destinationExists }
        let zipData = try BackupCrypto.decrypt(Data(contentsOf: encrypted), mnemonic: mnemonic, expecting: .snapshot)

        try fileManager.createDirectory(at: directory, withIntermediateDirectories: false)
        do {
            let archive = try Archive(data: zipData, accessMode: .read)
            var extracted = Set<String>()
            for entry in archive {
                let name = entry.path.hasPrefix("./") ? String(entry.path.dropFirst(2)) : entry.path
                guard entry.type == .file, name == manifestName || name == snapshotName else {
                    if entry.type == .directory && (name.isEmpty || name == "/") { continue }
                    throw Failure.unexpectedEntry(name)
                }
                guard extracted.insert(name).inserted else { throw Failure.unexpectedEntry(name) }
                let limit = name == manifestName ? maxManifestBytes : maxUnpackedBytes
                _ = try extract(entry, from: archive, to: directory.appending(path: name), limit: limit)
            }
            let snapshot = directory.appending(path: snapshotName)
            guard fileManager.fileExists(atPath: snapshot.path) else { throw Failure.snapshotMissing }
            let manifest = try parseManifest(Data(contentsOf: directory.appending(path: manifestName)))
            return (manifest, snapshot)
        } catch {
            try? fileManager.removeItem(at: directory)
            throw error
        }
    }

    /// v1 (RN `createBackup`): base64 text of a v1 envelope around a zip of
    /// `backup_staging/noah-data-<variant>/…`. Unpacks that folder's files
    /// into `directory` (must not exist).
    static func openLegacy(base64: Data, mnemonic: String, variant: AppVariant, into directory: URL) throws {
        let fileManager = FileManager.default
        guard !fileManager.fileExists(atPath: directory.path) else { throw Failure.destinationExists }
        guard let text = String(bytes: base64, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              let envelope = Data(base64Encoded: text) else { throw Failure.notLegacyBackup }
        let zip = try BackupCrypto.decrypt(envelope, mnemonic: mnemonic, expecting: .legacyDirectory)

        try fileManager.createDirectory(at: directory, withIntermediateDirectories: false)
        do {
            let prefix = "backup_staging/noah-data-\(variant.rawValue)/"
            let archive = try Archive(data: zip, accessMode: .read)
            var remaining = maxUnpackedBytes
            var count = 0
            for entry in archive where entry.type == .file && entry.path.hasPrefix(prefix) {
                count += 1
                guard count <= maxLegacyEntries else { throw Failure.tooLarge }
                let relative = String(entry.path.dropFirst(prefix.count))
                let parts = relative.split(separator: "/", omittingEmptySubsequences: false)
                guard !parts.isEmpty, !parts.contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }) else {
                    throw Failure.unexpectedEntry(entry.path)
                }
                let destination = directory.appending(path: relative)
                try fileManager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                remaining -= try extract(entry, from: archive, to: destination, limit: remaining)
            }
            guard fileManager.fileExists(atPath: directory.appending(path: snapshotName).path) else {
                throw Failure.snapshotMissing
            }
        } catch {
            try? fileManager.removeItem(at: directory)
            throw error
        }
    }

    /// Writes one file entry to `destination` (must not exist), stopping as
    /// soon as more than `limit` bytes come out, and rejects a CRC-32 that
    /// doesn't match the archive. Returns the bytes written.
    static func extract(_ entry: Entry, from archive: Archive, to destination: URL, limit: Int64) throws -> Int64 {
        guard entry.uncompressedSize <= UInt64(max(limit, 0)) else { throw Failure.tooLarge }
        guard !FileManager.default.fileExists(atPath: destination.path) else { throw Failure.unexpectedEntry(entry.path) }
        guard FileManager.default.createFile(atPath: destination.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let handle = try FileHandle(forWritingTo: destination)
        defer { try? handle.close() }
        var written: Int64 = 0
        let checksum = try archive.extract(entry, skipCRC32: false) { chunk in
            written += Int64(chunk.count)
            guard written <= limit else { throw Failure.tooLarge }
            try handle.write(contentsOf: chunk)
        }
        guard checksum == entry.checksum else { throw Failure.checksumMismatch(entry.path) }
        return written
    }

    /// Strict parse, matching `parseBackupManifest` in backupService.ts:
    /// every field present, nullable keys present as string or null.
    static func parseManifest(_ data: Data) throws -> Manifest {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = object["formatVersion"] as? Int,
              let createdAt = object["createdAt"] as? String,
              let snapshotSha256 = object["snapshotSha256"] as? String,
              let network = object["network"] as? String,
              let walletFingerprint = object["walletFingerprint"] as? String,
              let barkSchemaVersion = object["barkSchemaVersion"] as? Int,
              let serverPubkey = object["serverPubkey"], serverPubkey is String || serverPubkey is NSNull,
              let mailboxPubkey = object["mailboxPubkey"], mailboxPubkey is String || mailboxPubkey is NSNull
        else {
            throw Failure.invalidManifest
        }
        guard version <= formatVersion else { throw Failure.newerVersion }
        guard version == formatVersion else { throw Failure.invalidManifest }
        return Manifest(
            formatVersion: formatVersion, createdAt: createdAt, snapshotSha256: snapshotSha256,
            network: network, walletFingerprint: walletFingerprint,
            serverPubkey: serverPubkey as? String, mailboxPubkey: mailboxPubkey as? String,
            barkSchemaVersion: barkSchemaVersion)
    }

    /// Same keys and order as the RN app's `JSON.stringify(manifest)`.
    static func encode(_ manifest: Manifest) -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .withoutEscapingSlashes
        func string(_ value: String?) -> String {
            guard let value else { return "null" }
            return (try? String(json: encoder.encode(value))) ?? "\"\""
        }
        let json = """
        {"formatVersion":\(manifest.formatVersion),"createdAt":\(string(manifest.createdAt)),\
        "snapshotSha256":\(string(manifest.snapshotSha256)),"network":\(string(manifest.network)),\
        "walletFingerprint":\(string(manifest.walletFingerprint)),"serverPubkey":\(string(manifest.serverPubkey)),\
        "mailboxPubkey":\(string(manifest.mailboxPubkey)),"barkSchemaVersion":\(manifest.barkSchemaVersion)}
        """
        return Data(json.utf8)
    }

    /// `new Date().toISOString()`: UTC, millisecond precision, `Z`.
    static func timestamp(_ date: Date = Date()) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let milliseconds = (date.timeIntervalSince1970 * 1000).rounded()
        return formatter.string(from: Date(timeIntervalSince1970: milliseconds / 1000))
    }

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
