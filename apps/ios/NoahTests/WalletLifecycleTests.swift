import BarkBoltFFI
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
        func record() { calls += 1 }
        func release() { waiters.forEach { $0.resume() }; waiters = [] }
    }

    private func lifecycle(
        session: WalletSession, settings: SettingsStorage, seed: any WalletDeletionSeed, token: KeychainPassword,
        scratch: ScratchSpace? = nil,
        mutation: WalletMutationGate = .shared,
        drainTimeout: Duration = .seconds(30),
        settleBackground: @escaping @Sendable () async throws -> Void = {},
        deregister: @escaping @Sendable () async throws -> Void = {}
    ) -> WalletLifecycle {
        WalletLifecycle(dependencies: .init(
            session: session, location: location, seed: seed, authToken: token, settings: settings,
            scratch: scratch ?? ScratchSpace(root: root.appending(path: "scratch")), appGroup: nil, mutation: mutation, drainTimeout: drainTimeout,
            deregister: deregister, settleBackground: { _ in try await settleBackground() }))
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
        #expect({ seed.read() == .found(replacementMnemonic) }())
        #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
        // A late headless recovery also rechecks after the new wallet exists.
        _ = try await recovery.settle()
        #expect(location.databaseExists)
        #expect({ seed.read() == .found(replacementMnemonic) }())
    }

    @Test func markerForAnotherOrLegacyWalletNeverDeletesCurrentData() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        for marker in [Data(UUID().uuidString.utf8), Data([1]), Data("invalid".utf8)] {
            if !location.databaseExists { try writeBarkDatabase() }
            try seed.save("current wallet")
            try marker.write(to: location.deletionMarker)
            let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
            try await lifecycle(session: session, settings: settings, seed: seed, token: token).recoverDeletion()
            #expect(location.databaseExists)
            #expect(seed.read() == .found("current wallet"))
            #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
        }
        // Even a different marker with a real instance identity must be discarded.
        try location.writeDeletionIdentity(seed: seed.read())
        try Data(UUID().uuidString.utf8).write(to: location.deletionMarker)
        #expect(try location.deletionStatus(seed: seed.read()) == .mismatched)
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        try await lifecycle(session: session, settings: settings, seed: seed, token: token).recoverDeletion()
        #expect(location.databaseExists)
        #expect(seed.read() == .found("current wallet"))
        // A legacy deletion may have removed only part of its datadir. Preserve it
        // until the user explicitly confirms deletion on the incomplete-wallet screen.
        try FileManager.default.removeItem(at: location.database)
        try Data([1]).write(to: location.deletionMarker)
        let current = lifecycle(session: session, settings: settings, seed: seed, token: token)
        try await current.recoverDeletion()
        #expect(seed.read() == .found("current wallet"))
        try await current.delete()
        #expect(seed.read() == .notFound)
    }

    private struct UnreadableSeed: WalletDeletionSeed {
        func read() -> KeychainPassword.ReadResult { .unavailable(-25308) }
        func deleteWalletSeed() throws { Issue.record("Unreadable seed must never be deleted") }
    }

    @Test(arguments: ["bound", "uuid", "one-byte", "invalid"], ["same", "different", "none", "unreadable"])
    func absentDatadirRequiresSeedBoundEvidence(marker: String, current: String) async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try makeSettings()
        settings.set("retained", forKey: "sentinel")
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try token.save("token")
        switch marker {
        case "bound": try location.writeDeletionIdentity(seed: .found("wallet A"))
        case "uuid": try Data(UUID().uuidString.utf8).write(to: location.deletionMarker)
        case "one-byte": try Data([1]).write(to: location.deletionMarker)
        default: try Data("invalid".utf8).write(to: location.deletionMarker)
        }
        if current == "same" { try seed.save("wallet A") }
        if current == "different" { try seed.save("wallet B") }
        let item: any WalletDeletionSeed = current == "unreadable" ? UnreadableSeed() : seed
        let before = item.read()
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let result = try await lifecycle(session: session, settings: settings, seed: item, token: token).recoverDeletion()
        let waiting = marker == "bound" && current == "unreadable"
        #expect(result == (waiting ? .waitingForKeychain : .nothingPending))
        let finishes = marker == "bound" && (current == "same" || current == "none")
        #expect(item.read() == (finishes ? .notFound : before))
        #expect(token.read() == (finishes ? .notFound : .found("token")))
        #expect(settings.string(forKey: "sentinel") == (finishes ? nil : "retained"))
        #expect(FileManager.default.fileExists(atPath: location.deletionMarker.path) == waiting)
        #expect(!FileManager.default.fileExists(atPath: location.directory.path))
    }

    @Test(arguments: ["installing", "rollback", "committed"])
    func restoreJournalSettlesBeforeMatchingStaleDeletionMarker(phase: String) async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        settings.set("retained", forKey: "sentinel")
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("wallet A"); try token.save("token")
        let transaction = RestoreTransaction(location: location)
        try transaction.prepare()
        try FileManager.default.createDirectory(at: transaction.staged, withIntermediateDirectories: false)
        try Data("wallet B data".utf8).write(to: transaction.staged.appending(path: "db.sqlite"))
        try Data("plaintext".utf8).write(to: transaction.work.appending(path: "snapshot.sqlite"))
        try transaction.begin(seed: "wallet B")
        if phase != "installing" { try transaction.markCommitting() }
        if phase == "committed" { try seed.save("wallet B") }
        // Matching both live instance and current seed used to enter deletion and throw
        // restorePending forever, before the journal got a chance to settle.
        try location.writeDeletionIdentity(seed: seed.read())
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let mutation = WalletMutationGate()
        let lifecycle = lifecycle(session: session, settings: settings, seed: seed, token: token, mutation: mutation)
        let location = location
        let recovery = RestoreRecovery(mutation: mutation) {
            try await lifecycle.recoverRestoreAndDeletionLocked {
                try await WalletRestorer.recoverInterruptedRestore(session: session, location: location, seed: seed)
            }
        }
        let first = Task { try await recovery.settle() }
        let headless = Task { try await recovery.settle() }
        let results = try await [first.value, headless.value]
        #expect(results.contains(phase == "committed" ? .completed : .rolledBack))
        #expect(results.contains(.nothingPending))
        if phase == "committed" {
            #expect(try Data(contentsOf: location.database) == Data("wallet B data".utf8))
            #expect(seed.read() == .found("wallet B"))
        } else {
            #expect(try WalletDatabase.properties(at: location.database).network == "regtest")
            #expect(seed.read() == .found("wallet A"))
        }
        #expect(token.read() == .found("token"))
        #expect(settings.string(forKey: "sentinel") == "retained")
        #expect(suspended(settings) != true)
        #expect(!location.restorePending)
        #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
        #expect(!FileManager.default.fileExists(atPath: transaction.previous.path))
        #expect(!FileManager.default.fileExists(atPath: transaction.work.path))
        let hold = try await session.reserve()
        await session.release(hold)
        try await lifecycle.delete() // Still explicitly deletable.
        #expect(seed.read() == .notFound)
    }

    @Test func restoreWaitingForKeychainRetainsMarkerUntilUnlock() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("wallet B")
        let transaction = RestoreTransaction(location: location)
        try transaction.prepare()
        try FileManager.default.createDirectory(at: transaction.staged, withIntermediateDirectories: false)
        try Data("wallet B data".utf8).write(to: transaction.staged.appending(path: "db.sqlite"))
        try transaction.begin(seed: "wallet B")
        try transaction.markCommitting()
        try location.writeDeletionIdentity(seed: seed.read())
        let secret = FakeSeed("wallet B")
        secret.makeUnavailable()
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let mutation = WalletMutationGate()
        let lifecycle = lifecycle(session: session, settings: settings, seed: LockableSeed(item: seed, availability: secret), token: token, mutation: mutation)
        let location = location
        let recovery = RestoreRecovery(mutation: mutation) {
            try await lifecycle.recoverRestoreAndDeletionLocked {
                try await WalletRestorer.recoverInterruptedRestore(session: session, location: location, seed: secret)
            }
        }
        #expect(try await recovery.settle() == .waitingForKeychain)
        #expect(location.restorePending)
        #expect(FileManager.default.fileExists(atPath: location.deletionMarker.path))
        #expect(suspended(settings) != true)
        #expect(seed.read() == .found("wallet B"))
        secret.makeUnavailable(false)
        #expect(try await recovery.settle() == .completed)
        #expect(!location.restorePending)
        #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
        #expect(try Data(contentsOf: location.database) == Data("wallet B data".utf8))
        #expect(seed.read() == .found("wallet B"))
    }

    @Test func interruptionAfterRollbackCannotReauthorizeStaleDeletion() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("wallet A")
        let transaction = RestoreTransaction(location: location)
        try transaction.prepare()
        try FileManager.default.createDirectory(at: transaction.staged, withIntermediateDirectories: false)
        try Data("wallet B data".utf8).write(to: transaction.staged.appending(path: "db.sqlite"))
        try transaction.begin(seed: "wallet B")
        try location.writeDeletionIdentity(seed: seed.read())
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let mutation = WalletMutationGate()
        let lifecycle = lifecycle(session: session, settings: settings, seed: seed, token: token, mutation: mutation)
        let location = location
        let interrupted = RestoreRecovery(mutation: mutation) {
            try await lifecycle.recoverRestoreAndDeletionLocked {
                #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
                #expect(try await WalletRestorer.recoverInterruptedRestore(session: session, location: location, seed: seed) == .rolledBack)
                throw Unreachable() // Interruption after the journal has gone.
            }
        }
        await #expect(throws: Unreachable.self) { try await interrupted.settle() }
        let relaunched = RestoreRecovery(mutation: mutation) {
            try await lifecycle.recoverRestoreAndDeletionLocked {
                try await WalletRestorer.recoverInterruptedRestore(session: session, location: location, seed: seed)
            }
        }
        #expect(try await relaunched.settle() == .nothingPending)
        #expect(seed.read() == .found("wallet A"))
        #expect(try WalletDatabase.properties(at: location.database).network == "regtest")
        #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
    }

    private struct LockableSeed: WalletDeletionSeed {
        let item: KeychainPassword
        let availability: FakeSeed
        func read() -> KeychainPassword.ReadResult {
            if case .unavailable(let status) = availability.read() { return .unavailable(status) }
            return item.read()
        }
        func deleteWalletSeed() throws { try item.deleteWalletSeed() }
    }

    @Test(arguments: Array(0...3), [true, false])
    func lockedDeletionRetainsStagingThenAllowsRestoreAndDelete(stage: Int, sameSeed: Bool) async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("wallet A"); try token.save("token")
        try location.writeDeletionIdentity(seed: seed.read())
        let fm = FileManager.default
        if stage >= 1 { try fm.moveItem(at: location.directory, to: location.deletionDirectory) }
        if stage >= 2 { try fm.removeItem(at: location.deletionDirectory.appending(path: ".noah-wallet-instance")) }
        if stage >= 3 { try fm.removeItem(at: location.deletionDirectory) }
        let availability = FakeSeed(nil)
        availability.makeUnavailable()
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let mutation = WalletMutationGate()
        let current = lifecycle(session: session, settings: settings, seed: LockableSeed(item: seed, availability: availability), token: token, mutation: mutation)
        let recovery = RestoreRecovery(mutation: mutation) {
            try await current.recoverRestoreAndDeletionLocked { .nothingPending }
        }
        #expect(try await recovery.settle() == .waitingForKeychain)
        #expect(fm.fileExists(atPath: location.deletionMarker.path))
        #expect(fm.fileExists(atPath: location.deletionDirectory.path) == (stage == 1 || stage == 2))
        #expect(seed.read() == .found("wallet A")); #expect(token.read() == .found("token"))
        #expect(throws: WalletSession.OpenError.restorePending) { try location.removeDiscardedDeletionData() }
        await #expect(throws: WalletLifecycle.Failure.waitingForSecureStorage) { try await current.delete() }
        availability.makeUnavailable(false)
        if !sameSeed { try seed.save("wallet B") }
        #expect(try await recovery.settle() == .nothingPending)
        #expect(!fm.fileExists(atPath: location.deletionMarker.path))
        #expect(!fm.fileExists(atPath: location.deletionDirectory.path))
        #expect(seed.read() == (sameSeed ? .notFound : .found("wallet B")))
        // Recreate a restored datadir; the next deletion must never hit old staging.
        try seed.save("restored wallet")
        if fm.fileExists(atPath: location.directory.path) { try fm.removeItem(at: location.directory) }
        try writeBarkDatabase()
        try await current.delete()
        #expect(seed.read() == .notFound)
        #expect(!fm.fileExists(atPath: location.directory.path))
    }

    @Test(arguments: ["recover", "restore", "delete", "old-marker"])
    func discardedStagingIsSyncedBeforeRestoreOrNewMarker(next: String) async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("wallet A")
        if next == "old-marker" { try location.writeDeletionIdentity(seed: seed.read()) }
        try FileManager.default.moveItem(at: location.directory, to: location.deletionDirectory)
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        let current = lifecycle(session: session, settings: settings, seed: seed, token: token)
        if next == "restore" {
            var syncs: [URL] = []
            try location.removeDiscardedDeletionData { syncs.append($0) }
            #expect(syncs.map(\.path) == [root.path])
            #expect(seed.read() == .found("wallet A"))
            try writeBarkDatabase()
        } else {
            try writeBarkDatabase()
            if next == "old-marker" { try location.writeDeletionIdentity(seed: seed.read()) }
            if next == "recover" {
                try await current.recoverDeletion()
                #expect(location.databaseExists)
                #expect(seed.read() == .found("wallet A"))
            } else { try await current.delete() }
        }
        #expect(!FileManager.default.fileExists(atPath: location.deletionDirectory.path))
    }

    @Test func abandonedCleanupSyncFailurePreservesLiveWalletAndRetries() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try writeBarkDatabase()
        let settings = try makeSettings()
        let (seed, token) = items()
        defer { seed.remove(); token.remove() }
        try seed.save("wallet A")
        try FileManager.default.moveItem(at: location.directory, to: location.deletionDirectory)
        try writeBarkDatabase()
        #expect(throws: Unreachable.self) { try location.removeDiscardedDeletionData { _ in throw Unreachable() } }
        #expect(!FileManager.default.fileExists(atPath: location.deletionMarker.path))
        #expect(location.databaseExists); #expect(seed.read() == .found("wallet A"))
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        try await lifecycle(session: session, settings: settings, seed: seed, token: token).delete()
        #expect(seed.read() == .notFound)
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
