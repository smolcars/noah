import Foundation
import Testing
import ZIPFoundation

@testable import Noah

struct BackupArchiveTests {
    private let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private let root = FileManager.default.temporaryDirectory.appending(path: "archive-\(UUID().uuidString)")

    private func makeSnapshot() throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let url = root.appending(path: "snapshot.sqlite")
        try Data((0..<4096).map { UInt8($0 % 251) }).write(to: url)
        return url
    }

    private var manifest: BackupArchive.Manifest {
        .init(
            formatVersion: 2, createdAt: "2026-10-19T19:15:37.123Z", snapshotSha256: "ab",
            network: "regtest", walletFingerprint: "ea8c6cee", serverPubkey: "02ff", mailboxPubkey: nil,
            barkSchemaVersion: 42)
    }

    @Test func sealsAndOpensRoundTrip() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let snapshot = try makeSnapshot()
        let sealed = try BackupArchive.seal(
            snapshot: snapshot, manifest: manifest, mnemonic: mnemonic,
            to: root.appending(path: "out.noahbackup"), workDirectory: root)
        #expect(sealed.sizeBytes > 45)
        #expect(sealed.sha256 == BackupArchive.sha256Hex(try Data(contentsOf: sealed.url)))
        #expect(try Data(contentsOf: sealed.url).first == 2)

        let opened = try BackupArchive.open(encrypted: sealed.url, mnemonic: mnemonic, into: root.appending(path: "restore"))
        #expect(opened.manifest == manifest)
        #expect(try Data(contentsOf: opened.snapshot) == Data(contentsOf: snapshot))
        #expect(throws: BackupArchive.Failure.destinationExists) {
            try BackupArchive.seal(snapshot: snapshot, manifest: manifest, mnemonic: mnemonic, to: sealed.url, workDirectory: root)
        }
    }

    /// Exactly how the RN app's encryptWalletSnapshot builds the file:
    /// JSON.stringify'd manifest + snapshot in a folder, zipped without the
    /// parent folder, BackupCrypto v2.
    @Test func opensArchiveBuiltLikeReactNative() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let snapshot = try makeSnapshot()
        let staging = root.appending(path: "staging")
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: false)
        let rnManifest = #"{"formatVersion":2,"createdAt":"2026-10-19T19:15:37.123Z","snapshotSha256":"ab","network":"regtest","walletFingerprint":"ea8c6cee","serverPubkey":"02ff","mailboxPubkey":null,"barkSchemaVersion":42}"#
        try Data(rnManifest.utf8).write(to: staging.appending(path: "manifest.json"))
        try FileManager.default.copyItem(at: snapshot, to: staging.appending(path: "db.sqlite"))
        let zip = root.appending(path: "rn.zip")
        try FileManager.default.zipItem(at: staging, to: zip, shouldKeepParent: false, compressionMethod: .deflate)
        let encrypted = root.appending(path: "rn.noahbackup")
        try BackupCrypto.encrypt(Data(contentsOf: zip), mnemonic: mnemonic, version: .snapshot).write(to: encrypted)

        let opened = try BackupArchive.open(encrypted: encrypted, mnemonic: mnemonic, into: root.appending(path: "restore"))
        #expect(opened.manifest == manifest)
        #expect(String(decoding: try BackupArchive.encode(manifest), as: UTF8.self) == rnManifest)
    }

    @Test func manifestParsingIsAsStrictAsReactNative() throws {
        let valid = #"{"formatVersion":2,"createdAt":"x","snapshotSha256":"a","network":"regtest","walletFingerprint":"f","serverPubkey":null,"mailboxPubkey":null,"barkSchemaVersion":42}"#
        #expect(try BackupArchive.parseManifest(Data(valid.utf8)).serverPubkey == nil)
        for broken in [
            valid.replacingOccurrences(of: #""formatVersion":2"#, with: #""formatVersion":1"#),
            valid.replacingOccurrences(of: #""serverPubkey":null,"#, with: ""),
            valid.replacingOccurrences(of: #""barkSchemaVersion":42"#, with: #""barkSchemaVersion":"42""#),
            "not json",
        ] {
            #expect(throws: BackupArchive.Failure.invalidManifest) { try BackupArchive.parseManifest(Data(broken.utf8)) }
        }
    }

    @Test func rejectsArchivesWithOtherEntries() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        for extra in ["notes.txt", "../escape.sqlite"] {
            let zip = root.appending(path: "\(UUID().uuidString).zip")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let archive = try Archive(url: zip, accessMode: .create)
            for name in ["manifest.json", "db.sqlite", extra] {
                let data = Data("x".utf8)
                try archive.addEntry(with: name, type: .file, uncompressedSize: Int64(data.count)) { position, size in
                    data.subdata(in: Int(position)..<Int(position) + size)
                }
            }
            let encrypted = root.appending(path: "\(UUID().uuidString).noahbackup")
            try BackupCrypto.encrypt(Data(contentsOf: zip), mnemonic: mnemonic, version: .snapshot).write(to: encrypted)
            let destination = root.appending(path: UUID().uuidString)
            #expect(throws: BackupArchive.Failure.unexpectedEntry(extra)) {
                try BackupArchive.open(encrypted: encrypted, mnemonic: mnemonic, into: destination)
            }
            #expect(!FileManager.default.fileExists(atPath: destination.path), "partial restore is cleaned up")
        }
    }

    /// Zip with the given file entries, deflated.
    private func zip(_ entries: [(String, Data)]) throws -> Data {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let url = root.appending(path: "\(UUID().uuidString).zip")
        let archive = try Archive(url: url, accessMode: .create)
        for (name, data) in entries {
            try archive.addEntry(
                with: name, type: .file, uncompressedSize: Int64(data.count), compressionMethod: .deflate
            ) { position, size in
                data.subdata(in: Int(position)..<Int(position) + size)
            }
        }
        return try Data(contentsOf: url)
    }

    /// Overwrites a 32-bit field in every local header, central directory
    /// record and data descriptor, the way a hostile archive would lie.
    private func patch(_ zip: Data, crc: UInt32? = nil, uncompressedSize: UInt32? = nil) -> Data {
        var bytes = [UInt8](zip)
        func read(_ at: Int) -> UInt32 { bytes[at..<at + 4].reversed().reduce(0) { $0 << 8 | UInt32($1) } }
        func write(_ value: UInt32, _ at: Int) { for i in 0..<4 { bytes[at + i] = UInt8(value >> (8 * i) & 0xff) } }
        var offset = 0
        while offset + 30 < bytes.count {
            switch read(offset) {
            case 0x0403_4b50:  // local file header
                if let crc { write(crc, offset + 14) }
                if let uncompressedSize { write(uncompressedSize, offset + 22) }
            case 0x0201_4b50:  // central directory
                if let crc { write(crc, offset + 16) }
                if let uncompressedSize { write(uncompressedSize, offset + 24) }
            case 0x0807_4b50:  // data descriptor
                if let crc { write(crc, offset + 4) }
                if let uncompressedSize { write(uncompressedSize, offset + 12) }
            default: break
            }
            offset += 1
        }
        return Data(bytes)
    }

    private func firstEntry(of zip: Data) throws -> (Archive, Entry) {
        let archive = try Archive(data: zip, accessMode: .read)
        return (archive, try #require(archive.makeIterator().next()))
    }

    @Test func extractionStopsWhenAnEntryInflatesPastItsLimit() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let zeros = Data(count: 256 * 1024)
        // Honest header over the limit: refused before writing anything.
        let (honest, honestEntry) = try firstEntry(of: zip([("db.sqlite", zeros)]))
        let refused = root.appending(path: "refused")
        #expect(throws: BackupArchive.Failure.tooLarge) {
            try BackupArchive.extract(honestEntry, from: honest, to: refused, limit: 1024)
        }
        #expect(!FileManager.default.fileExists(atPath: refused.path))

        // Header claims 16 bytes: the byte count stops it while inflating.
        let (lying, lyingEntry) = try firstEntry(of: patch(try zip([("db.sqlite", zeros)]), uncompressedSize: 16))
        #expect(lyingEntry.uncompressedSize == 16)
        let bomb = root.appending(path: "bomb")
        #expect(throws: BackupArchive.Failure.tooLarge) {
            try BackupArchive.extract(lyingEntry, from: lying, to: bomb, limit: 64 * 1024)
        }
        let written = (try? FileManager.default.attributesOfItem(atPath: bomb.path)[.size] as? Int) ?? 0
        #expect(written <= 64 * 1024)
    }

    @Test func extractionRejectsAChecksumMismatch() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let (archive, entry) = try firstEntry(of: patch(try zip([("db.sqlite", Data("wallet".utf8))]), crc: 0xDEAD_BEEF))
        #expect(throws: BackupArchive.Failure.checksumMismatch("db.sqlite")) {
            try BackupArchive.extract(entry, from: archive, to: root.appending(path: "out"), limit: 1024)
        }
    }

    @Test func openRejectsACorruptedSnapshot() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let manifestJSON = try BackupArchive.encode(manifest)
        let corrupted = patch(try zip([("manifest.json", manifestJSON), ("db.sqlite", Data("wallet".utf8))]), crc: 0x0BAD_F00D)
        let encrypted = root.appending(path: "corrupt.noahbackup")
        try BackupCrypto.encrypt(corrupted, mnemonic: mnemonic, version: .snapshot).write(to: encrypted)
        let destination = root.appending(path: "restore")
        #expect(throws: BackupArchive.Failure.self) {
            try BackupArchive.open(encrypted: encrypted, mnemonic: mnemonic, into: destination)
        }
        #expect(!FileManager.default.fileExists(atPath: destination.path))
    }

    /// RN `createBackup`: base64 of a v1 envelope around the datadir zipped
    /// with its `backup_staging` parent.
    private func legacyBackup(_ entries: [(String, Data)]) throws -> Data {
        let envelope = try BackupCrypto.encrypt(zip(entries), mnemonic: mnemonic, version: .legacyDirectory)
        return Data(envelope.base64EncodedString().utf8)
    }

    @Test func legacyBackupUnpacksOnlyThisVariantsFolder() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let base64 = try legacyBackup([
            ("backup_staging/noah-data-regtest/db.sqlite", Data("db".utf8)),
            ("backup_staging/noah-data-regtest/db.sqlite-wal", Data("wal".utf8)),
            ("backup_staging/noah-data-regtest/nested/state.json", Data("{}".utf8)),
            ("backup_staging/noah-data-signet/db.sqlite", Data("other network".utf8)),
        ])
        let destination = root.appending(path: "legacy")
        try BackupArchive.openLegacy(base64: base64, mnemonic: mnemonic, variant: .regtest, into: destination)
        #expect(try Data(contentsOf: destination.appending(path: "db.sqlite")) == Data("db".utf8))
        #expect(try Data(contentsOf: destination.appending(path: "db.sqlite-wal")) == Data("wal".utf8))
        #expect(try Data(contentsOf: destination.appending(path: "nested/state.json")) == Data("{}".utf8))
        let names = try FileManager.default.contentsOfDirectory(atPath: destination.path).sorted()
        #expect(names == ["db.sqlite", "db.sqlite-wal", "nested"])
    }

    @Test func legacyBackupRejectsUnsafeOrIncompleteArchives() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let prefix = "backup_staging/noah-data-regtest/"
        let cases: [([(String, Data)], BackupArchive.Failure)] = [
            ([(prefix + "db.sqlite", Data()), (prefix + "../escape", Data())], .unexpectedEntry(prefix + "../escape")),
            ([(prefix + "db.sqlite", Data()), (prefix + "a//b", Data())], .unexpectedEntry(prefix + "a//b")),
            ([(prefix + "other.sqlite", Data())], .snapshotMissing),
            ([(prefix + "db.sqlite", Data())] + (0..<256).map { (prefix + "f\($0)", Data()) }, .tooLarge),
        ]
        for (entries, failure) in cases {
            let destination = root.appending(path: UUID().uuidString)
            #expect(throws: failure) {
                try BackupArchive.openLegacy(base64: legacyBackup(entries), mnemonic: mnemonic, variant: .regtest, into: destination)
            }
            #expect(!FileManager.default.fileExists(atPath: destination.path), "partial restore is cleaned up")
        }
        #expect(throws: BackupArchive.Failure.notLegacyBackup) {
            try BackupArchive.openLegacy(base64: Data("not base64!".utf8), mnemonic: mnemonic, variant: .regtest, into: root.appending(path: "x"))
        }
    }

    struct ArchiveInput: Decodable {
        let kind: String
        let variant: String
        let mnemonic: String
        let fileBase64: String?
        let fileText: String?
    }

    struct ArchiveExpected: Decodable {
        struct Manifest: Decodable {
            let formatVersion: Int
            let createdAt: String
            let snapshotSha256: String
            let network: String
            let walletFingerprint: String
            let serverPubkey: String?
            let mailboxPubkey: String?
            let barkSchemaVersion: Int
        }

        let manifest: Manifest?
        let files: [String: String]
    }

    /// Files from Node crypto + Info-ZIP (`apps/fixtures/backup-archives.json`).
    @Test func opensIndependentlyBuiltBackups() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let cases = try Fixtures.load("backup-archives", input: ArchiveInput.self, expected: ArchiveExpected.self)
        #expect(Set(cases.map(\.input.kind)) == ["v2", "legacy"])
        for fixture in cases {
            let variant = try #require(AppVariant(rawValue: fixture.input.variant))
            let destination = root.appending(path: UUID().uuidString)
            switch fixture.input.kind {
            case "v2":
                let file = root.appending(path: "\(UUID().uuidString).noahbackup")
                try #require(Data(base64Encoded: fixture.input.fileBase64 ?? "")).write(to: file)
                let opened = try BackupArchive.open(encrypted: file, mnemonic: fixture.input.mnemonic, into: destination)
                let expected = try #require(fixture.expected.manifest)
                #expect(opened.manifest == .init(
                    formatVersion: expected.formatVersion, createdAt: expected.createdAt,
                    snapshotSha256: expected.snapshotSha256, network: expected.network,
                    walletFingerprint: expected.walletFingerprint, serverPubkey: expected.serverPubkey,
                    mailboxPubkey: expected.mailboxPubkey, barkSchemaVersion: expected.barkSchemaVersion))
            default:
                try BackupArchive.openLegacy(
                    base64: Data((fixture.input.fileText ?? "").utf8), mnemonic: fixture.input.mnemonic,
                    variant: variant, into: destination)
            }
            for (path, hash) in fixture.expected.files {
                #expect(BackupArchive.sha256Hex(try Data(contentsOf: destination.appending(path: path))) == hash, "\(fixture.name): \(path)")
            }
            let unpacked = FileManager.default.enumerator(at: destination, includingPropertiesForKeys: [.isRegularFileKey])?
                .compactMap { $0 as? URL }
                .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
                .count
            let extra = fixture.input.kind == "v2" ? 1 : 0  // manifest.json
            #expect(unpacked == fixture.expected.files.count + extra, "\(fixture.name): nothing else unpacked")
        }
    }

    /// Expected strings from `new Date(ms).toISOString()` in Node.
    @Test func timestampMatchesJavaScriptToISOString() {
        #expect(BackupArchive.timestamp(Date(timeIntervalSince1970: 1_792_436_137.5)) == "2026-10-19T18:55:37.500Z")
        #expect(BackupArchive.timestamp(Date(timeIntervalSince1970: 1_792_436_137)) == "2026-10-19T18:55:37.000Z")
    }
}
