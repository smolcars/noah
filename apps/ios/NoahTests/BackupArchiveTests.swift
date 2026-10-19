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

    /// Expected strings from `new Date(ms).toISOString()` in Node.
    @Test func timestampMatchesJavaScriptToISOString() {
        #expect(BackupArchive.timestamp(Date(timeIntervalSince1970: 1_792_436_137.5)) == "2026-10-19T18:55:37.500Z")
        #expect(BackupArchive.timestamp(Date(timeIntervalSince1970: 1_792_436_137)) == "2026-10-19T18:55:37.000Z")
    }
}
