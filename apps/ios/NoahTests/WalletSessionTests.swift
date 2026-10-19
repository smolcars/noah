import BarkBoltFFI
import Foundation
import SQLite3
import Testing

@testable import Noah

struct WalletSessionTests {
    private let documents = FileManager.default.temporaryDirectory.appending(path: "session-\(UUID().uuidString)")

    private var location: WalletDataLocation { WalletDataLocation(variant: .regtest, documentsDirectory: documents) }

    /// Minimal stand-in for a bark database: only `bark_properties`.
    private func writeBarkProperties(network: String) throws {
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var db: OpaquePointer?
        #expect(sqlite3_open(location.database.path, &db) == SQLITE_OK)
        defer { sqlite3_close(db) }
        let sql = """
            CREATE TABLE bark_properties (id TEXT PRIMARY KEY, network TEXT NOT NULL, fingerprint TEXT NOT NULL);
            INSERT INTO bark_properties (id, network, fingerprint) VALUES ('1', '\(network)', 'abcd1234');
            """
        #expect(sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK)
    }

    /// Same database in WAL mode, closed cleanly: no -wal or -shm left behind.
    private func writeClosedWALDatabase(network: String) throws {
        try writeBarkProperties(network: network)
        var db: OpaquePointer?
        #expect(sqlite3_open(location.database.path, &db) == SQLITE_OK)
        #expect(sqlite3_exec(db, "PRAGMA journal_mode=WAL; INSERT INTO bark_properties VALUES ('2', 'x', 'y'); DELETE FROM bark_properties WHERE id = '2';", nil, nil, nil) == SQLITE_OK)
        sqlite3_close(db)
        #expect(!FileManager.default.fileExists(atPath: location.database.path + "-wal"))
        #expect(!FileManager.default.fileExists(atPath: location.database.path + "-shm"))
    }

    /// Opener that never reaches the SDK; it waits until released, then fails.
    private actor Gate {
        private var waiters: [CheckedContinuation<Void, Never>] = []
        private(set) var calls = 0
        func enter() async {
            calls += 1
            await withCheckedContinuation { waiters.append($0) }
        }
        func release() { waiters.forEach { $0.resume() }; waiters = [] }
    }

    private struct OpenerFailed: Error {}

    private func session(gate: Gate? = nil) -> WalletSession {
        WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in
            if let gate { await gate.enter() }
            throw OpenerFailed()
        }
    }

    @Test func refusesMissingDatabaseWithoutCreatingAnything() async {
        await #expect(throws: WalletSession.OpenError.walletDatabaseMissing) {
            try await session().openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        #expect(!FileManager.default.fileExists(atPath: documents.path))
    }

    @Test func refusesEmptyDatabaseAndLeavesItUntouched() async throws {
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        try Data().write(to: location.database)
        await #expect(throws: WalletSession.OpenError.notABarkWallet) {
            try await session().openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        let size = try FileManager.default.attributesOfItem(atPath: location.database.path)[.size] as? Int
        #expect(size == 0)
    }

    @Test func refusesOtherNetworkBeforeOpening() async throws {
        try writeBarkProperties(network: "signet")
        let gate = Gate()
        await #expect(throws: WalletSession.OpenError.networkMismatch(found: "signet")) {
            try await session(gate: gate).openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        #expect(await gate.calls == 0)
    }

    /// The v0.3.0 SDK opens these; Noah's own preflight must not reject them first.
    @Test func cleanlyClosedWALDatabaseReachesTheOpener() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        try writeClosedWALDatabase(network: "regtest")
        #expect(try WalletDatabase.properties(at: location.database) == .init(network: "regtest", fingerprint: "abcd1234"))
        #expect(!FileManager.default.fileExists(atPath: location.database.path + "-shm"), "the check created no sidecar")

        let gate = Gate()
        let session = session(gate: gate)
        let open = Task { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls == 0 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await open.value }

        let reservation = try await session.reserve()
        let verify = Task { try await session.verify(reservation, mnemonic: "unused", appVersion: "test") }
        while await gate.calls < 2 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await verify.value }
        await session.release(reservation)
    }

    @Test func liveWALDatabaseIsReadWithItsLog() throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        try writeBarkProperties(network: "regtest")
        var writer: OpaquePointer?
        #expect(sqlite3_open(location.database.path, &writer) == SQLITE_OK)
        defer { sqlite3_close(writer) }
        // Committed only to the WAL while the writer stays open.
        #expect(sqlite3_exec(writer, "PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0; UPDATE bark_properties SET network = 'signet';", nil, nil, nil) == SQLITE_OK)
        #expect(FileManager.default.fileExists(atPath: location.database.path + "-wal"))
        #expect(try WalletDatabase.properties(at: location.database).network == "signet")
    }

    @Test func rejectsOverlappingOpenAndCloseThenRecovers() async throws {
        try writeBarkProperties(network: "regtest")
        let gate = Gate()
        let session = session(gate: gate)

        let first = Task { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls == 0 { await Task.yield() }

        await #expect(throws: WalletSession.OpenError.busy) {
            try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.close() }

        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await first.value }
        #expect(try await session.summary() == nil)

        // A failed open returns to closed: the next attempt reaches the opener.
        let second = Task { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls < 2 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await second.value }
    }

    /// Real bark wallet, created without a server so the test runs offline.
    private func offlineSession() -> WalletSession {
        WalletSession(variant: .regtest, location: location) { mnemonic, datadir, _, _, create in
            let config = WalletConfiguration.base(for: .regtest).sdkConfig(userAgent: "test")
            let onchain = try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: datadir)
            let wallet = try await Wallet.openWithOnchain(
                network: .regtest, mnemonicOrSeed: mnemonic, config: config,
                args: WalletOpenArgs(
                    runDaemon: false, datadir: datadir, createIfNotExists: create,
                    createWithoutServer: true, skipRecovery: true),
                onchain: onchain)
            return WalletSession.Handles(wallet: wallet, onchain: onchain)
        }
    }

    @Test func closeWaitsForWalletCallsStillRunning() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = offlineSession()
        try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: false, appVersion: "test")
        let gate = Gate()
        let call = Task { try await session.withOpenWallet { _ in await gate.enter() } }
        while await gate.calls == 0 { await Task.yield() }
        // The caller gives up; like Rust work under BoltFFI, the call goes on.
        call.cancel()

        let closing = Task { try await session.close() }
        for _ in 0..<50 { await Task.yield() }
        await #expect(throws: WalletSession.SessionError.notOpen) { try await session.peekAddress(index: 0) }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }

        await gate.release()
        try await closing.value
        let reservation = try await session.reserve()
        await session.release(reservation)
    }

    @Test(.timeLimit(.minutes(1))) func closeStopsReceiveObserversAndWaitsForThem() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        let session = offlineSession()
        try await session.createNew(mnemonic: try generateMnemonic(), runDaemon: false, appVersion: "test")
        // Offline wallet: no server for real addresses, and none are needed to wait.
        let request = ReceiveRequest(arkAddress: "unused-ark-address", onchainAddress: "unused-onchain-address")
        let completions = try await session.receiveCompletions(for: request)
        let reader = Task {
            var received: [ReceiveCompletion] = []
            for try await completion in completions { received.append(completion) }
            return received
        }
        for _ in 0..<50 { await Task.yield() }

        try await session.close()
        // The observer ended with the session: no completion, stream finished.
        #expect(try await reader.value.isEmpty)
        let reservation = try await session.reserve()
        await session.release(reservation)
    }

    @Test func reservationKeepsEveryoneElseOut() async throws {
        defer { try? FileManager.default.removeItem(at: documents) }
        try writeBarkProperties(network: "regtest")
        let gate = Gate()
        let session = session(gate: gate)
        let reservation = try await session.reserve()
        await #expect(throws: WalletSession.OpenError.busy) {
            try await session.ensureOpen(mnemonic: "unused", runDaemon: false, appVersion: "test")
        }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }
        await #expect(throws: WalletSession.OpenError.busy) { try await session.close() }
        #expect(await gate.calls == 0)

        await session.release(reservation)
        let open = Task { try await session.ensureOpen(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls == 0 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await open.value }
    }

    @Test func ensureOpenWaitsForAnOpenInProgress() async throws {
        try writeBarkProperties(network: "regtest")
        let gate = Gate()
        let session = session(gate: gate)
        let first = Task { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls == 0 { await Task.yield() }

        // Joins the in-flight open (and its failure) instead of reporting busy.
        let joined = Task { try await session.ensureOpen(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        for _ in 0..<50 { await Task.yield() }
        await gate.release()
        await #expect(throws: OpenerFailed.self) { try await first.value }
        await #expect(throws: OpenerFailed.self) { try await joined.value }
        #expect(await gate.calls == 1)
    }
}
