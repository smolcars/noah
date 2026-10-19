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
        WalletSession(variant: .regtest, location: location) { _, _, _, _ in
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
}
