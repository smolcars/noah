import BarkBoltFFI
import Foundation
@testable import Noah
import SQLite3
import Testing

/// Settings' suspend / resume / delete against a temporary datadir, MMKV
/// instance and Keychain items.
@Suite(.serialized)
struct WalletLifecycleTests {
    let root = FileManager.default.temporaryDirectory.appending(path: "lifecycle-\(UUID().uuidString)")
    var location: WalletDataLocation { WalletDataLocation(variant: .regtest, documentsDirectory: root) }

    struct OpenRefused: Error {}
    struct Unreachable: Error {}

    actor Gate {
        private var waiters: [CheckedContinuation<Void, Never>] = []
        private(set) var calls = 0
        func enter() async {
            calls += 1
            await withCheckedContinuation { waiters.append($0) }
        }
        func record() { calls += 1 }
        func release() { waiters.forEach { $0.resume() }; waiters = [] }
    }

    func lifecycle(
        session: WalletSession, settings: SettingsStorage, seed: any WalletDeletionSeed, token: KeychainPassword,
        scratch: ScratchSpace? = nil,
        mutation: WalletMutationGate = .shared,
        drainTimeout: Duration = .seconds(30),
        settleBackground: @escaping @Sendable () async throws -> Void = {},
        deregister: @escaping @Sendable () async throws -> Void = {}) -> WalletLifecycle {
        WalletLifecycle(dependencies: .init(
            session: session, location: location, seed: seed, authToken: token, settings: settings,
            scratch: scratch ?? ScratchSpace(root: root.appending(path: "scratch")), appGroup: nil, mutation: mutation, drainTimeout: drainTimeout,
            deregister: deregister, settleBackground: { _ in try await settleBackground() }))
    }

    func makeSettings() throws -> SettingsStorage {
        try SettingsStorage(rootPath: root.appending(path: "mmkv").path, instanceID: "lifecycle-\(UUID().uuidString)")
    }

    // swiftlint:disable:next discouraged_optional_boolean - nil when no wallet state is saved, which the tests tell apart from false
    func suspended(_ settings: SettingsStorage) -> Bool? {
        settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended
    }

    func writeBarkDatabase() throws {
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

    func items() -> (seed: KeychainPassword, token: KeychainPassword) {
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
            deregister: { try deregistered.save("called") }).delete()

        #expect(deregistered.current == "called")
        #expect(!FileManager.default.fileExists(atPath: location.directory.path))
        #expect(seed.read() == .notFound)
        #expect(token.read() == .notFound)
        #expect(settings.string(forKey: PersistedWalletState.key) == nil)
        // The session is usable again (reservation released).
        let next = try await session.reserve()
        await session.release(next)
    }

    @Test func deletionClearsRecurringLedgerAndMarkerBeforeWalletRecreation() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("seed words")
        let binding = try RecurringStore.binding(location: location)
        let file = location.directory.appending(path: "noah-recurring-journal.json.policies")
        let store = RecurringStore(settings: settings)
        store.protectPolicies(file: file, binding: { binding })
        _ = try store.load()
        #expect(FileManager.default.fileExists(atPath: file.path))
        #expect(FileManager.default.fileExists(atPath: file.appendingPathExtension("initialized").path))
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        try await lifecycle(session: session, settings: settings, seed: seed, token: token).delete()
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(!FileManager.default.fileExists(atPath: file.appendingPathExtension("initialized").path))
        try writeBarkDatabase()
        let replacement = try RecurringStore.binding(location: location)
        #expect(replacement.instance != binding.instance)
        let fresh = RecurringStore(settings: settings)
        fresh.protectPolicies(file: file, binding: { replacement })
        #expect(try fresh.load().schedules.isEmpty)
        #expect(try String(contentsOf: file, encoding: .utf8).contains(replacement.instance.uuidString))
        #expect(try !String(contentsOf: file, encoding: .utf8).contains(binding.instance.uuidString))
    }

    @Test func undeletableScratchPreservesSecretsAndLocalState() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        settings.set(#"{"state":{"isInitialized":true},"version":0}"#, forKey: PersistedWalletState.key)
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("seed words"); try token.save("jwt")
        let folder = root.appending(path: "scratch/abandoned")
        let snapshot = folder.appending(path: "snapshot.sqlite")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("plaintext".utf8).write(to: snapshot)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: folder.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder.path) }
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        await #expect(throws: (any Error).self) {
            try await lifecycle(session: session, settings: settings, seed: seed, token: token).delete()
        }
        #expect(FileManager.default.fileExists(atPath: snapshot.path))
        #expect(location.databaseExists)
        #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
        #expect(suspended(settings) == false)
        #expect(seed.read() == .found("seed words"))
        #expect(token.read() == .found("jwt"))
        #expect(settings.string(forKey: PersistedWalletState.key) != nil)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder.path)
        try await lifecycle(session: session, settings: settings, seed: seed, token: token).delete()
        #expect(!FileManager.default.fileExists(atPath: snapshot.path))
        #expect(seed.read() == .notFound)
    }

    @Test func deletionDrainsExportBeforeScratchAndDatabaseAndRemovesRetainedShare() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("seed words")
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let scratch = ScratchSpace(root: root.appending(path: "scratch"))
        let retained = try scratch.makeFolder("share-backup")
        try Data("encrypted".utf8).write(to: retained.appending(path: "wallet.noahbackup"))
        let gate = Gate()
        let exporting = Task {
            try await scratch.withFolder("export") { folder in
                try Data("plaintext".utf8).write(to: folder.appending(path: "snapshot.sqlite"))
                await gate.enter()
                #expect(location.databaseExists)
                #expect(seed.read() == .found("seed words"))
            }
        }
        while await gate.calls == 0 { await Task.yield() }
        let deleting = Task { try await lifecycle(session: session, settings: settings, seed: seed, token: token, scratch: scratch).delete() }
        let deadline = ContinuousClock.now + .seconds(5)
        while suspended(settings) != true, ContinuousClock.now < deadline { await Task.yield() }
        #expect(location.databaseExists)
        #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
        await gate.release()
        try await exporting.value
        try await deleting.value
        #expect(!FileManager.default.fileExists(atPath: retained.path))
        #expect(!location.databaseExists)
        #expect(seed.read() == .notFound)
    }

    @Test func relaunchFinishesEveryDestructiveBoundary() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        for stage in 0...5 {
            try writeBarkDatabase()
            try seed.save("seed words"); try token.save("jwt")
            try location.writeDeletionIdentity(seed: seed.read())
            if stage >= 1 { try FileManager.default.moveItem(at: location.directory, to: location.deletionDirectory) }
            if stage >= 2 { try FileManager.default.removeItem(at: location.deletionDirectory.appending(path: ".noah-wallet-instance")) }
            if stage >= 3 { try FileManager.default.removeItem(at: location.deletionDirectory) }
            if stage >= 4 { try seed.deleteWalletSeed() }
            if stage >= 5 { token.remove(); settings.removeAll() }
            let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
            let relaunched = lifecycle(session: session, settings: settings, seed: seed, token: token)
            try await relaunched.recoverDeletion()
            #expect(seed.read() == .notFound)
            #expect(token.read() == .notFound)
            #expect(settings.string(forKey: PersistedWalletState.key) == nil)
            #expect(!location.databaseExists)
            #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
            try await relaunched.recoverDeletion()
        }
    }

    @Test func timedOutExportKeepsWalletIntactAndDeletionCanRetry() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("current wallet"); try token.save("token")
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let scratch = ScratchSpace(root: root.appending(path: "scratch"))
        let gate = Gate()
        let exporting = Task { try await scratch.withFolder("export") { folder in
            try Data("plaintext".utf8).write(to: folder.appending(path: "snapshot.sqlite"))
            await gate.enter()
        } }
        while await gate.calls == 0 { await Task.yield() }
        let deletion = lifecycle(session: session, settings: settings, seed: seed, token: token,
                                 scratch: scratch, drainTimeout: .milliseconds(20))
        await #expect(throws: WalletLifecycle.Failure.backupBusy) { try await deletion.delete() }
        #expect(location.databaseExists)
        #expect(seed.read() == .found("current wallet"))
        #expect(token.read() == .found("token"))
        #expect(suspended(settings) == false)
        #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
        // The failed drain released its gate; later exports and explicit deletion still work.
        try await scratch.withFolder("retry-export") { _ in }
        await gate.release()
        try await exporting.value
        try await deletion.delete()
        #expect(seed.read() == .notFound)
    }

    @Test func queuedRecoveryCannotDeleteTheReplacementWallet() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("old wallet")
        let replacementMnemonic = try generateMnemonic()
        let mutation = WalletMutationGate()
        let committed = Gate()
        let settlement = Gate()
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let deleting = lifecycle(session: session, settings: settings, seed: seed, token: token,
                                 mutation: mutation, settleBackground: { await settlement.record() }, deregister: { await committed.enter() })
        let settingsDelete = Task { try await deleting.delete() }
        while await committed.calls == 0 { await Task.yield() }
        #expect(FileManager.default.fileExists(atPath: location.deletionMarker.path))
        let recovery = RestoreRecovery(mutation: mutation) { try await deleting.recoverDeletionLocked(); return .nothingPending }
        // Settings owns the gate at its committed marker. Creation and push recovery queue behind it.
        let creator = WalletCreator(dependencies: .init(
            mutation: mutation,
            readSeed: { seed.read() }, saveSeed: { try seed.save($0) }, deleteSeed: { seed.delete(onlyIfValueIs: $0) },
            walletDataExists: { location.hasAnyData }, datadirExists: { FileManager.default.fileExists(atPath: location.directory.path) },
            removeAttemptFiles: { _ in false }, generateMnemonic: { replacementMnemonic },
            openExisting: { _ in throw Unreachable() }, reserve: { try await session.reserve() },
            release: { await session.release($0) },
            createNew: { reservation, _ in
                try writeBarkDatabase()
                await session.release(reservation)
            }))
        let replacement = Task { try await creator.createOrResume() }
        while await mutation.waitingCount < 1 { await Task.yield() }
        let push = Task { try await recovery.settle() }
        while await mutation.waitingCount < 2 { await Task.yield() }
        #expect(await settlement.calls == 1, "recovery must not settle/close the wallet before owning the gate")
        await committed.release()
        try await settingsDelete.value
        try await replacement.value
        _ = try await push.value
        #expect(location.databaseExists)
        #expect(seed.read() == .found(replacementMnemonic))
        #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
        // A late headless recovery also rechecks after the new wallet exists.
        _ = try await recovery.settle()
        #expect(location.databaseExists)
        #expect(seed.read() == .found(replacementMnemonic))
    }
}
