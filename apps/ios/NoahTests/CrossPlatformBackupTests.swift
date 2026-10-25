import BarkBoltFFI
import Foundation
import HTTPTypes
import OpenAPIRuntime
import SQLite3
import Testing

@testable import Noah

/// Public BIP39 vector, offline unfunded wallet; never uses the app's wallet directory.
@Suite(.serialized)
struct CrossPlatformBackupTests {
    private static let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private let root = FileManager.default.temporaryDirectory.appending(path: "cross-backup-\(UUID().uuidString)")

    struct Input: Decodable { let producer: String; let mnemonic: String; let fileBase64: String }
    struct Expected: Decodable { let fingerprint: String; let movements: Int; let balance: [WalletBalance.Detail] }

    private func session(_ location: WalletDataLocation) -> WalletSession {
        WalletSession(variant: .regtest, location: location) { mnemonic, datadir, _, _, create in
            let config = WalletConfiguration.base(for: .regtest).sdkConfig(userAgent: "cross-backup-fixture")
            let onchain = create
                ? try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: datadir)
                : try await OnchainWallet.openExisting(network: .regtest, mnemonic: mnemonic, config: config, datadir: datadir)
            let wallet = try await Wallet.openWithOnchain(network: .regtest, mnemonicOrSeed: mnemonic, config: config,
                args: WalletOpenArgs(runDaemon: false, datadir: datadir, createIfNotExists: create,
                                     createWithoutServer: true, skipRecovery: true), onchain: onchain)
            return .init(wallet: wallet, onchain: onchain)
        }
    }

    /// Run explicitly, then copy tmp/native-backup-ios.json out of this disposable simulator.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["NOAH_GENERATE_CROSS_BACKUP"] == "1"))
    func generateIOSServiceExport() async throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
        let wallet = session(location)
        do {
            try await wallet.createNew(mnemonic: Self.phrase, runDaemon: false, appVersion: "cross-backup-fixture")
            let summary = try #require(try await wallet.summary())
            #expect(summary.movementCount == 0)
            #expect(summary.balance.details.allSatisfy { $0.sats == 0 })
            let service = BackupService(session: wallet, client: Client(serverURL: URL(string: "http://unused")!,
                transport: WalletRestorerTests.FakeServer(backupId: "unused", encrypted: root)),
                readMnemonic: { Self.phrase }, scratch: ScratchSpace(root: root.appending(path: "scratch")))
            let export = try await service.exportEncryptedBackup(to: root.appending(path: "ios.noahbackup"))
            let fixture: [String: Any] = ["name": "iOS BackupService export", "input": [
                "producer": "ios", "mnemonic": Self.phrase,
                "fileBase64": try Data(contentsOf: export.url).base64EncodedString()],
                "expected": ["fingerprint": summary.fingerprint, "movements": summary.movementCount,
                             "balance": try JSONSerialization.jsonObject(with: JSONEncoder().encode(summary.balance.details))]]
            try JSONSerialization.data(withJSONObject: fixture, options: [.prettyPrinted, .sortedKeys])
                .write(to: FileManager.default.temporaryDirectory.appending(path: "native-backup-ios.json"))
            try await wallet.close()
        } catch { try? await wallet.close(); try? FileManager.default.removeItem(at: root); throw error }
        try FileManager.default.removeItem(at: root)
    }

    @Test(.regtestStack) func restoresAndroidServiceExport() async throws {
        let fixtures = try Fixtures.load("native-backups", input: Input.self, expected: Expected.self)
        let fixture = try #require(fixtures.first { $0.input.producer == "android" })
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let encrypted = root.appending(path: "android.noahbackup")
        try #require(Data(base64Encoded: fixture.input.fileBase64)).write(to: encrypted)
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root.appending(path: "restored"))
        // Real SDK opener and snapshot validation; only server listing/download metadata is faked.
        let wallet = WalletSession(variant: .regtest, location: location)
        let seed = KeychainPassword(service: "com.noah.tests.cross-backup.\(UUID().uuidString)", account: "noah")
        defer { seed.remove() }
        let restorer = WalletRestorer(variant: .regtest, session: wallet, location: location, seed: seed,
            makeClient: { _ in Client(serverURL: URL(string: "http://fixture")!,
                transport: WalletRestorerTests.FakeServer(backupId: "android", encrypted: encrypted)) }, walletChanged: {})
        do {
            let outcome = try await restorer.restore(mnemonic: fixture.input.mnemonic)
            #expect(outcome.backupId == "android" && !outcome.cleanupPending)
            #expect(seed.read() == .found(fixture.input.mnemonic))
            #expect(!location.restorePending)
            try await wallet.ensureOpen(mnemonic: fixture.input.mnemonic, runDaemon: false, appVersion: "cross-backup")
            let restored = try #require(try await wallet.summary())
            #expect(restored.fingerprint == fixture.expected.fingerprint)
            #expect(restored.movementCount == fixture.expected.movements)
            #expect(try await wallet.transactionHistory().isEmpty)
            #expect(restored.balance.details == fixture.expected.balance)
            try await wallet.close()
        } catch { try? await wallet.close(); throw error }
    }
    private struct FixtureServer: ClientTransport {
        let encrypted: URL
        var format: Int32 = 2
        func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
            if operationID != "list_backup_objects" {
                return try await WalletRestorerTests.FakeServer(backupId: "android", encrypted: encrypted)
                    .send(request, body: body, baseURL: baseURL, operationID: operationID)
            }
            let data = try Data(contentsOf: encrypted)
            let info: [String: Any] = ["backup_id": "android", "created_at": "2026-10-20T00:00:00Z",
                "encrypted_sha256": BackupArchive.sha256Hex(data), "encrypted_size": data.count, "format_version": format]
            var response = HTTPResponse(status: .ok)
            response.headerFields[.contentType] = "application/json"
            return (response, HTTPBody(try JSONSerialization.data(withJSONObject: [info])))
        }
    }

    /// Returns the actual restore error after checking the old DB/seed and pre-install boundary.
    private func refuseNewer(_ kind: String) async throws -> any Error {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let fixture = try #require(Fixtures.load("native-backups", input: Input.self, expected: Expected.self)
            .first { $0.input.producer == "android" })
        let encrypted = root.appending(path: "future.noahbackup")
        try #require(Data(base64Encoded: fixture.input.fileBase64)).write(to: encrypted)
        if kind == "envelope" {
            var bytes = try Data(contentsOf: encrypted); bytes[0] = 3; try bytes.write(to: encrypted)
        } else if kind == "manifest" || kind == "schema" {
            let opened = try BackupArchive.open(encrypted: encrypted, mnemonic: Self.phrase, into: root.appending(path: "unpacked"))
            var manifest = opened.manifest
            if kind == "manifest" { manifest.formatVersion = 3 }
            else {
                var db: OpaquePointer?
                #expect(sqlite3_open(opened.snapshot.path, &db) == SQLITE_OK)
                defer { sqlite3_close(db) }
                #expect(sqlite3_exec(db, "INSERT INTO migrations(value) VALUES(9999)", nil, nil, nil) == SQLITE_OK)
                sqlite3_close(db); db = nil
                manifest.barkSchemaVersion = 9999
                manifest.snapshotSha256 = BackupArchive.sha256Hex(try Data(contentsOf: opened.snapshot))
            }
            try FileManager.default.removeItem(at: encrypted)
            _ = try BackupArchive.seal(snapshot: opened.snapshot, manifest: manifest, mnemonic: Self.phrase,
                to: encrypted, workDirectory: root)
        }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root.appending(path: "existing"))
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        let previous = Data("previous wallet bytes".utf8)
        try previous.write(to: location.database)
        let wallet = WalletSession(variant: .regtest, location: location)
        let seed = KeychainPassword(service: "com.noah.tests.future-backup.\(UUID().uuidString)", account: "noah")
        defer { seed.remove() }
        try seed.save(Self.phrase)
        let restorer = WalletRestorer(variant: .regtest, session: wallet, location: location, seed: seed,
            makeClient: { _ in Client(serverURL: URL(string: "http://fixture")!,
                transport: FixtureServer(encrypted: encrypted, format: kind == "list" ? 3 : 2)) }, walletChanged: {},
            beginOnboarding: { Issue.record("A newer backup reached the installation boundary") })
        var failure: (any Error)?
        do { _ = try await restorer.restore(mnemonic: Self.phrase) } catch { failure = error }
        #expect(try Data(contentsOf: location.database) == previous)
        #expect(seed.read() == .found(Self.phrase))
        #expect(!location.restorePending)
        #expect(await !wallet.isOpen)
        #expect((try? FileManager.default.contentsOfDirectory(atPath: location.restoreDirectory.path).isEmpty) != false)
        return try #require(failure)
    }

    @Test(arguments: ["list", "manifest", "envelope", "schema"])
    func refusesNewerBackupsWithoutPartialRestore(_ kind: String) async throws {
        let failure = try await refuseNewer(kind)
        switch kind {
        case "list": #expect(failure as? WalletRestorer.Failure == .unsupportedFormat(3))
        case "manifest": #expect(failure as? BackupArchive.Failure == .newerVersion)
        case "envelope": #expect(failure as? BackupCrypto.Failure == .newerVersion)
        default: #expect((failure as? BarkError)?.message.contains("newer than supported version") == true)
        }
    }

    @Test(arguments: ["manifest", "envelope", "schema"])
    func newerBackupsExplainUpdateRequired(_ kind: String) async throws {
        let failure = try await refuseNewer(kind)
        #expect(RestoreIssue.from(failure).message == "This backup was created by a newer version of Noah. Update the app, then try restoring again.")
    }

}
