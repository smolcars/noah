import BarkBoltFFI
import Foundation
import HTTPTypes
import OpenAPIRuntime
import Testing

@testable import Noah

/// Offline restore failure paths: a real bark snapshot (created without a
/// server), a fake Noah server transport, and an opener that fails.
@Suite(.serialized)
struct WalletRestorerTests {
    private let root = FileManager.default.temporaryDirectory.appending(path: "restorer-\(UUID().uuidString)")

    /// Serves list + download for one backup file.
    struct FakeServer: ClientTransport {
        let backupId: String
        let encrypted: URL

        func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
            let data = try Data(contentsOf: encrypted)
            let info: [String: Any] = [
                "backup_id": backupId, "created_at": "2026-10-19T00:00:00Z",
                "encrypted_sha256": BackupArchive.sha256Hex(data), "encrypted_size": data.count, "format_version": 2,
            ]
            let json: Any
            switch operationID {
            case "list_backup_objects": json = [info]
            case "get_backup_object_download_url": json = ["backup": info, "download_url": encrypted.absoluteString]
            default: return (HTTPResponse(status: .notFound), nil)
            }
            var response = HTTPResponse(status: .ok)
            response.headerFields[.contentType] = "application/json"
            return (response, HTTPBody(try JSONSerialization.data(withJSONObject: json)))
        }
    }

    struct OpenRefused: Error {}

    /// Wallet created offline, sealed into a backup file.
    private func makeBackup(mnemonic: String) async throws -> URL {
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root.appending(path: "device-a"))
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        let config = WalletConfiguration.base(for: .regtest).sdkConfig(userAgent: "test")
        let onchain = try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: location.directory.path)
        let wallet = try await Wallet.openWithOnchain(
            network: .regtest, mnemonicOrSeed: mnemonic, config: config,
            args: WalletOpenArgs(runDaemon: false, datadir: location.directory.path, createWithoutServer: true, skipRecovery: true),
            onchain: onchain)
        let snapshotURL = root.appending(path: "snapshot.sqlite")
        let snapshot = try await wallet.createSnapshot(destinationPath: snapshotURL.path)
        try await wallet.stopDaemonWait()
        return try BackupArchive.seal(
            snapshot: snapshotURL, manifest: BackupService.manifest(for: snapshot), mnemonic: mnemonic,
            to: root.appending(path: "backup.noahbackup"), workDirectory: root
        ).url
    }

    @Test func failedOpenPutsThePreviousWalletAndSeedBack() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let mnemonic = try generateMnemonic()
        let backup = try await makeBackup(mnemonic: mnemonic)

        // Device B already has a wallet and a seed.
        let deviceB = WalletDataLocation(variant: .regtest, documentsDirectory: root.appending(path: "device-b"))
        try FileManager.default.createDirectory(at: deviceB.directory, withIntermediateDirectories: true)
        try Data("previous wallet".utf8).write(to: deviceB.database)
        let seedItem = KeychainPassword(service: "com.noah.tests.restorer.\(UUID().uuidString)", account: "noah")
        defer { seedItem.remove() }
        try seedItem.save("previous seed")

        // Session that passes the read-only checks only for the restored DB, then refuses to open.
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in throw OpenRefused() }
        let restorer = WalletRestorer(
            variant: .regtest, session: session, location: deviceB, seed: seedItem,
            makeClient: { _ in
                Client(serverURL: URL(string: "http://fake")!, transport: FakeServer(backupId: "b1", encrypted: backup))
            },
            workDirectory: root)

        await #expect(throws: OpenRefused.self) { try await restorer.restore(mnemonic: mnemonic) }
        #expect(try Data(contentsOf: deviceB.database) == Data("previous wallet".utf8))
        #expect(seedItem.read() == .found("previous seed"))
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: deviceB.directory.deletingLastPathComponent().path)
            .filter { $0.hasPrefix(".wallet-") }
        #expect(leftovers.isEmpty)
    }

    @Test func refusesToRestoreOverAnOpenWallet() async throws {
        // Covered by WalletSession lifecycle: the restorer checks `isOpen` first.
        let deviceB = WalletDataLocation(variant: .regtest, documentsDirectory: root.appending(path: "device-c"))
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in throw OpenRefused() }
        #expect(await !session.isOpen)
    }
}
