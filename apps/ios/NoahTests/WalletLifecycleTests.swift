import Foundation
import SQLite3
import Testing

@testable import Noah

/// Settings' suspend / resume / delete against a temporary datadir, MMKV
/// instance and Keychain items.
@Suite(.serialized)
struct WalletLifecycleTests {
    private let root = FileManager.default.temporaryDirectory.appending(path: "lifecycle-\(UUID().uuidString)")
    private var location: WalletDataLocation { WalletDataLocation(variant: .regtest, documentsDirectory: root) }

    struct OpenRefused: Error {}
    struct Unreachable: Error {}

    private actor Gate {
        private var waiters: [CheckedContinuation<Void, Never>] = []
        private(set) var calls = 0
        func enter() async {
            calls += 1
            await withCheckedContinuation { waiters.append($0) }
        }
        func release() { waiters.forEach { $0.resume() }; waiters = [] }
    }

    private func lifecycle(
        session: WalletSession, settings: SettingsStorage, seed: KeychainPassword, token: KeychainPassword,
        deregister: @escaping @Sendable () async throws -> Void = {}
    ) -> WalletLifecycle {
        WalletLifecycle(dependencies: .init(
            session: session, location: location, seed: seed, authToken: token, settings: settings,
            scratch: ScratchSpace(root: root.appending(path: "scratch")), appGroup: nil, deregister: deregister))
    }

    private func makeSettings() throws -> SettingsStorage {
        try SettingsStorage(rootPath: root.appending(path: "mmkv").path, instanceID: "lifecycle-\(UUID().uuidString)")
    }

    private func suspended(_ settings: SettingsStorage) -> Bool? {
        settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended
    }

    private func writeBarkDatabase() throws {
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var db: OpaquePointer?
        #expect(sqlite3_open(location.database.path, &db) == SQLITE_OK)
        defer { sqlite3_close(db) }
        let sql = """
            CREATE TABLE bark_properties (id TEXT PRIMARY KEY, network TEXT NOT NULL, fingerprint TEXT NOT NULL);
            INSERT INTO bark_properties VALUES ('1', 'regtest', 'abcd1234');
            """
        #expect(sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK)
    }

    private func items() -> (seed: KeychainPassword, token: KeychainPassword) {
        let id = UUID().uuidString
        return (KeychainPassword(service: "com.noah.tests.lifecycle.seed.\(id)", account: "noah"),
                KeychainPassword(service: "com.noah.tests.lifecycle.token.\(id)", account: "noah"))
    }

    @Test func suspendWaitsForAnOpenInProgressAndPersistsFirst() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        let gate = Gate()
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in
            await gate.enter()
            throw OpenRefused()
        }
        let opening = Task { try await session.openExisting(mnemonic: "unused", runDaemon: false, appVersion: "test") }
        while await gate.calls == 0 { await Task.yield() }

        let suspend = Task { try await lifecycle(session: session, settings: settings, seed: seed, token: token).suspend() }
        let deadline = ContinuousClock.now + .seconds(5)
        while suspended(settings) != true, ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(2)) }
        // The open is still held: the flag went down before the close.
        #expect(suspended(settings) == true, "flag first: nothing may reopen the wallet meanwhile")
        #expect(await gate.calls == 1)
        await gate.release()
        _ = try? await opening.value
        try await suspend.value
        #expect(await !session.isOpen)

        try lifecycle(session: session, settings: settings, seed: seed, token: token).resume()
        #expect(suspended(settings) == false)
    }

    @Test func failedSuspendPutsTheFlagBack() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try makeSettings()
        let (seed, token) = items()
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let restoreHold = try await session.reserve()
        await #expect(throws: WalletSession.OpenError.busy) {
            try await lifecycle(session: session, settings: settings, seed: seed, token: token).suspend()
        }
        #expect(suspended(settings) == false)
        await session.release(restoreHold)
    }

    @Test func deleteRemovesFilesThenSeedThenLocalState() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        settings.set(#"{"state":{"isInitialized":true},"version":0}"#, forKey: PersistedWalletState.key)
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("seed words")
        try token.save("jwt")
        let deregistered = FakeSeed(nil)
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }

        try await lifecycle(
            session: session, settings: settings, seed: seed, token: token,
            deregister: { try deregistered.save("called") }
        ).delete()

        #expect(deregistered.current == "called")
        #expect(!FileManager.default.fileExists(atPath: location.directory.path))
        #expect(seed.read() == .notFound)
        #expect(token.read() == .notFound)
        #expect(settings.string(forKey: PersistedWalletState.key) == nil)
        // The session is usable again (reservation released).
        let next = try await session.reserve()
        await session.release(next)
    }

    @Test func deleteGoesAheadWhenDeregistrationFails() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("seed words")
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        try await lifecycle(
            session: session, settings: settings, seed: seed, token: token, deregister: { throw Unreachable() }
        ).delete()
        #expect(seed.read() == .notFound)
    }

    @Test func deleteRefusesWhileARestoreIsPendingAndTouchesNothing() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("seed words")
        try FileManager.default.createDirectory(at: location.restoreDirectory, withIntermediateDirectories: true)
        try Data("{}".utf8).write(to: location.restoreDirectory.appending(path: RestoreTransaction.journalName))
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }

        await #expect(throws: WalletLifecycle.Failure.restorePending) {
            try await lifecycle(session: session, settings: settings, seed: seed, token: token).delete()
        }
        #expect(FileManager.default.fileExists(atPath: location.database.path))
        #expect(seed.read() == .found("seed words"))
        let next = try await session.reserve()
        await session.release(next)
    }
}
