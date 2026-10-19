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
        case invalidManifest
    }

    /// Zips `snapshot` with its manifest, encrypts it for `mnemonic` and
    /// writes it to `destination`, which must not exist.
    static func seal(
        snapshot: URL, manifest: Manifest, mnemonic: String, to destination: URL,
        workDirectory: URL = .cachesDirectory
    ) throws -> EncryptedFile {
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
                    if entry.type == .directory && (name == "" || name == "/") { continue }
                    throw Failure.unexpectedEntry(name)
                }
                guard extracted.insert(name).inserted else { throw Failure.unexpectedEntry(name) }
                _ = try archive.extract(entry, to: directory.appending(path: name))
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

    /// Strict parse, matching `parseBackupManifest` in backupService.ts:
    /// every field present, nullable keys present as string or null.
    static func parseManifest(_ data: Data) throws -> Manifest {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            object["formatVersion"] as? Int == formatVersion,
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
        return Manifest(
            formatVersion: formatVersion, createdAt: createdAt, snapshotSha256: snapshotSha256,
            network: network, walletFingerprint: walletFingerprint,
            serverPubkey: serverPubkey as? String, mailboxPubkey: mailboxPubkey as? String,
            barkSchemaVersion: barkSchemaVersion)
    }

    /// Same keys and order as the RN app's `JSON.stringify(manifest)`.
    static func encode(_ manifest: Manifest) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .withoutEscapingSlashes
        func string(_ value: String?) -> String {
            guard let value else { return "null" }
            return String(decoding: (try? encoder.encode(value)) ?? Data("\"\"".utf8), as: UTF8.self)
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
