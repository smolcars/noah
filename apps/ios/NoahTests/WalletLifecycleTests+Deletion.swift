import BarkBoltFFI
import Foundation
@testable import Noah
import SQLite3
import Testing

extension WalletLifecycleTests {
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
        func deleteWalletSeed() { Issue.record("Unreadable seed must never be deleted") }
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
        let current = lifecycle(
            session: session,
            settings: settings,
            seed: LockableSeed(item: seed, availability: availability),
            token: token,
            mutation: mutation)
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
            session: session, settings: settings, seed: seed, token: token, deregister: { throw Unreachable() }).delete()
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
