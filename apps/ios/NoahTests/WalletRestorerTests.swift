import BarkBoltFFI
import Foundation
import HTTPTypes
import OpenAPIRuntime
import SQLite3
import Testing

@testable import Noah

/// Restore against a fake Noah server with a real bark snapshot. The open
/// check uses the real SDK opener (local regtest config) unless a test
/// swaps in one that fails or waits.
@Suite(.serialized)
struct WalletRestorerTests {
    private let root = FileManager.default.temporaryDirectory.appending(path: "restorer-\(UUID().uuidString)")
    private var deviceB: WalletDataLocation { WalletDataLocation(variant: .regtest, documentsDirectory: root.appending(path: "device-b")) }

    /// Serves list + download for one backup file.
    struct FakeServer: ClientTransport {
        let backupId: String
        let encrypted: URL
        var reportedSha256: String?
        var reportedSize: Int?

        func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
            let data = try Data(contentsOf: encrypted)
            let info: [String: Any] = [
                "backup_id": backupId, "created_at": "2026-10-19T00:00:00Z",
                "encrypted_sha256": reportedSha256 ?? BackupArchive.sha256Hex(data), "encrypted_size": reportedSize ?? data.count,
                "format_version": 2,
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

    /// Lets a test hold the open check until it has looked at the session.
    actor Gate {
        private var entered = false
        private var released = false
        private var enteredWaiters: [CheckedContinuation<Void, Never>] = []
        private var releaseWaiters: [CheckedContinuation<Void, Never>] = []

        func enter() async {
            entered = true
            enteredWaiters.forEach { $0.resume() }
            enteredWaiters = []
            guard !released else { return }
            await withCheckedContinuation { releaseWaiters.append($0) }
        }

        func waitUntilEntered() async {
            guard !entered else { return }
            await withCheckedContinuation { enteredWaiters.append($0) }
        }

        func release() {
            released = true
            releaseWaiters.forEach { $0.resume() }
            releaseWaiters = []
        }
    }

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

    /// Device B already has a wallet; returns a restorer for it.
    private func setUp(
        seed: FakeSeed, session: WalletSession? = nil, reportedSha256: String? = nil, reportedSize: Int? = nil
    ) async throws -> (restorer: WalletRestorer, mnemonic: String) {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let mnemonic = try generateMnemonic()
        let backup = try await makeBackup(mnemonic: mnemonic)
        try FileManager.default.createDirectory(at: deviceB.directory, withIntermediateDirectories: true)
        try Data("previous wallet".utf8).write(to: deviceB.database)
        let restorer = WalletRestorer(
            variant: .regtest, session: session ?? WalletSession(variant: .regtest, location: deviceB),
            location: deviceB, seed: seed,
            makeClient: { _ in
                Client(serverURL: URL(string: "http://fake")!,
                       transport: FakeServer(
                           backupId: "b1", encrypted: backup, reportedSha256: reportedSha256, reportedSize: reportedSize))
            })
        return (restorer, mnemonic)
    }

    private var deviceBWallet: String? {
        (try? Data(contentsOf: deviceB.database)).map { String(decoding: $0, as: UTF8.self) }
    }

    private func expectPreviousWalletBack(_ seed: FakeSeed) throws {
        #expect(deviceBWallet == "previous wallet")
        #expect(seed.current == "previous seed")
        try expectNoRestoreLeftovers()
    }

    private func expectNoRestoreLeftovers() throws {
        let workspace = deviceB.restoreDirectory
        let leftovers = (try? FileManager.default.contentsOfDirectory(atPath: workspace.path)) ?? []
        #expect(leftovers.isEmpty, "journal, previous wallet and decrypted files are gone: \(leftovers)")
    }

    @Test func failedOpenPutsThePreviousWalletBack() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in throw OpenRefused() }
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        await #expect(throws: OpenRefused.self) { try await restorer.restore(mnemonic: mnemonic) }
        try expectPreviousWalletBack(seed)
    }

    @Test func sessionIsReservedForTheWholeRestore() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        let gate = Gate()
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in
            await gate.enter()
            throw OpenRefused()
        }
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        let restore = Task { try await restorer.restore(mnemonic: mnemonic) }
        await gate.waitUntilEntered()
        // Mid-restore: the new files are in place but nobody else can reach them.
        #expect(deviceBWallet != "previous wallet")
        await #expect(throws: WalletSession.OpenError.busy) {
            try await session.ensureOpen(mnemonic: "previous seed", runDaemon: false, appVersion: "test")
        }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.close() }
        #expect(try await session.summary() == nil)
        await #expect(throws: WalletSession.OpenError.busy) { try await restorer.restore(mnemonic: mnemonic) }

        await gate.release()
        await #expect(throws: OpenRefused.self) { try await restore.value }
        try expectPreviousWalletBack(seed)
        #expect(await !session.isOpen)
    }

    @Test func cancellingDuringTheOpenCheckWaitsForTheNativeWork() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        let gate = Gate()
        let sawCancellation = FakeSeed(nil)
        // BoltFFI answers a cancelled Swift caller early while Rust goes on. The
        // session runs the opener in its own task, so the opener must never see
        // the caller's cancellation, and the restore must wait for it.
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in
            await withTaskCancellationHandler {
                await gate.enter()
            } onCancel: {
                try? sawCancellation.save("cancelled")
            }
            throw OpenRefused()
        }
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        let restore = Task { try await restorer.restore(mnemonic: mnemonic) }
        await gate.waitUntilEntered()
        restore.cancel()
        for _ in 0..<50 { await Task.yield() }
        // Still mid-check: nothing was swapped back underneath the opener.
        #expect(deviceBWallet != "previous wallet")
        #expect(deviceB.restorePending)
        await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }

        #expect(sawCancellation.current == nil)

        await gate.release()
        await #expect(throws: OpenRefused.self) { try await restore.value }
        try expectPreviousWalletBack(seed)
    }

    @Test func downloadLargerThanTheServerRecordIsCutOff() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        let (restorer, mnemonic) = try await setUp(seed: seed, reportedSize: 64)

        await #expect(throws: WalletRestorer.Failure.downloadMismatch) { try await restorer.restore(mnemonic: mnemonic) }
        try expectPreviousWalletBack(seed)
    }

    @Test func launchRecoveryWithTheWalletAlreadyOpeningIsNotAFailure() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        let gate = Gate()
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in
            await gate.enter()
            throw OpenRefused()
        }
        _ = try await setUp(seed: seed, session: session)
        try makeBarkProperties(at: deviceB)

        // A restore died while unpacking, before its journal: plaintext left.
        let transaction = RestoreTransaction(location: deviceB)
        try transaction.prepare()
        try Data("decrypted".utf8).write(to: transaction.work.appending(path: "db.sqlite"))

        // A silent push started opening the wallet before the UI launched.
        let pushOpen = Task { try await session.ensureOpen(mnemonic: "previous seed", runDaemon: false, appVersion: "test") }
        await gate.waitUntilEntered()
        #expect(try await WalletRestorer.recoverInterruptedRestore(session: session, location: deviceB, seed: seed) == .nothingPending)
        #expect(!FileManager.default.fileExists(atPath: transaction.work.path), "decrypted leftovers are swept anyway")
        await gate.release()
        _ = try? await pushOpen.value
    }

    /// Review finding: UI start and a push job (or two push jobs) recovering
    /// at once must both settle, not fail on each other's reservation.
    @Test func concurrentLaunchRecoveriesWaitForEachOther() async throws {
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in throw OpenRefused() }
        let gate = Gate()
        let recovery = RestoreRecovery {
            let hold = try await session.reserve()
            await gate.enter()
            await session.release(hold)
            return .nothingPending
        }
        let first = Task { try await recovery.settle() }
        await gate.waitUntilEntered()
        let second = Task { try await recovery.settle() }
        try await Task.sleep(for: .milliseconds(20))
        await gate.release()
        #expect(try await first.value == .nothingPending)
        #expect(try await second.value == .nothingPending)
    }

    @Test func launchRecoveryLeavesAnActiveRestoresFilesAlone() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let session = WalletSession(variant: .regtest, location: deviceB) { _, _, _, _, _ in throw OpenRefused() }
        let transaction = RestoreTransaction(location: deviceB)
        try transaction.prepare()
        try Data("downloading".utf8).write(to: transaction.work.appending(path: "backup.noahbackup"))

        let restoreHold = try await session.reserve()
        #expect(try await WalletRestorer.recoverInterruptedRestore(
            session: session, location: deviceB, seed: FakeSeed("seed")) == .nothingPending)
        #expect(FileManager.default.fileExists(atPath: transaction.work.path))
        await session.release(restoreHold)
    }

    /// Enough of a bark database to pass the session's read-only preflight.
    private func makeBarkProperties(at location: WalletDataLocation) throws {
        try? FileManager.default.removeItem(at: location.database)
        var db: OpaquePointer?
        guard sqlite3_open(location.database.path, &db) == SQLITE_OK else { throw OpenRefused() }
        defer { sqlite3_close(db) }
        let sql = """
            CREATE TABLE bark_properties (id TEXT PRIMARY KEY, network TEXT NOT NULL, fingerprint TEXT NOT NULL);
            INSERT INTO bark_properties (id, network, fingerprint) VALUES ('1', 'regtest', 'abcd1234');
            """
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw OpenRefused() }
    }

    @Test func restoreCommitsTheSeedAndLeavesTheSessionReopenable() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        let session = WalletSession(variant: .regtest, location: deviceB)
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        let outcome = try await restorer.restore(mnemonic: mnemonic)
        #expect(outcome.backupId == "b1")
        #expect(seed.current == mnemonic)
        #expect(deviceBWallet != "previous wallet")
        try expectNoRestoreLeftovers()
        // The open check released the datadir lock.
        #expect(await !session.isOpen)
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "test")
        try await session.close()
    }

    @Test func failedSeedWritePutsThePreviousWalletBack() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        seed.failWrites()
        let (restorer, mnemonic) = try await setUp(seed: seed)

        await #expect(throws: KeychainPassword.WriteError.self) { try await restorer.restore(mnemonic: mnemonic) }
        try expectPreviousWalletBack(seed)
    }

    @Test func seedWriteThatLandedDespiteAnErrorCommits() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        seed.failWrites(landing: true)
        let (restorer, mnemonic) = try await setUp(seed: seed)

        _ = try await restorer.restore(mnemonic: mnemonic)
        #expect(seed.current == mnemonic)
        #expect(deviceBWallet != "previous wallet")
        try expectNoRestoreLeftovers()
    }

    @Test func unknownSeedStateIsLeftForLaunchRecovery() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        seed.failWrites(thenLock: true)
        let session = WalletSession(variant: .regtest, location: deviceB)
        let (restorer, mnemonic) = try await setUp(seed: seed, session: session)

        await #expect(throws: WalletRestorer.Failure.seedStateUnknown) { try await restorer.restore(mnemonic: mnemonic) }
        #expect(deviceB.restorePending)
        await #expect(throws: WalletSession.OpenError.restorePending) {
            try await session.ensureOpen(mnemonic: "previous seed", runDaemon: false, appVersion: "test")
        }
        // Next launch, still locked: wait. Unlocked, old seed: undo.
        #expect(try await WalletRestorer.recoverInterruptedRestore(session: session, location: deviceB, seed: seed) == .waitingForKeychain)
        seed.makeUnavailable(false)
        #expect(try await WalletRestorer.recoverInterruptedRestore(session: session, location: deviceB, seed: seed) == .rolledBack)
        try expectPreviousWalletBack(seed)
    }

    @Test func unreadableKeychainChangesNothing() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        seed.makeUnavailable()
        let (restorer, mnemonic) = try await setUp(seed: seed)

        await #expect(throws: WalletRestorer.Failure.secureStorageUnavailable) { try await restorer.restore(mnemonic: mnemonic) }
        seed.makeUnavailable(false)
        try expectPreviousWalletBack(seed)
    }

    @Test func downloadThatDoesNotMatchTheServerRecordIsRejected() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let seed = FakeSeed("previous seed")
        let (restorer, mnemonic) = try await setUp(seed: seed, reportedSha256: String(repeating: "0", count: 64))

        await #expect(throws: WalletRestorer.Failure.downloadMismatch) { try await restorer.restore(mnemonic: mnemonic) }
        try expectPreviousWalletBack(seed)
    }
}
