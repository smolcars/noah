import Foundation
import Testing

@testable import Noah

/// In-memory seed item whose reads and writes can be made to fail.
final class FakeSeed: SecretItem, @unchecked Sendable {
    private let lock = NSLock()
    private var value: String?
    private var unavailable = false
    private var failingWrites = false
    /// The write lands but still reports an error.
    private var writesLandThenFail = false
    /// A failed write also leaves the Keychain unreadable.
    private var lockAfterFailedWrite = false

    init(_ value: String?) { self.value = value }

    var current: String? { lock.withLock { value } }
    func makeUnavailable(_ flag: Bool = true) { lock.withLock { unavailable = flag } }
    func failWrites(landing: Bool = false, thenLock: Bool = false) {
        lock.withLock {
            failingWrites = true
            writesLandThenFail = landing
            lockAfterFailedWrite = thenLock
        }
    }

    func read() -> KeychainPassword.ReadResult {
        lock.withLock {
            if unavailable { return .unavailable(errSecInteractionNotAllowed) }
            return value.map { .found($0) } ?? .notFound
        }
    }

    func save(_ newValue: String) throws {
        try lock.withLock {
            guard failingWrites else {
                value = newValue
                return
            }
            if writesLandThenFail { value = newValue }
            if lockAfterFailedWrite { unavailable = true }
            throw KeychainPassword.WriteError(status: errSecIO)
        }
    }
}

/// Every crash point of a restore, reproduced on disk, then settled by
/// `recover` as the next launch would.
struct RestoreTransactionTests {
    private let root = FileManager.default.temporaryDirectory.appending(path: "restore-tx-\(UUID().uuidString)")
    private var location: WalletDataLocation { WalletDataLocation(variant: .regtest, documentsDirectory: root) }
    private let newSeed = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private func write(_ text: String, to directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(text.utf8).write(to: directory.appending(path: "db.sqlite"))
    }

    private func read(_ directory: URL) -> String? {
        (try? Data(contentsOf: directory.appending(path: "db.sqlite"))).map { String(decoding: $0, as: UTF8.self) }
    }

    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    /// Old wallet in the datadir, restored one staged, `begin` done.
    private func begun(previous: String? = "old") throws -> RestoreTransaction {
        if let previous { try write(previous, to: location.directory) }
        let transaction = RestoreTransaction(location: location)
        try transaction.prepare()
        try write("new", to: transaction.staged)
        try Data("plaintext".utf8).write(to: transaction.work.appending(path: "download"))
        try transaction.begin(seed: newSeed)
        return transaction
    }

    private func expectSettled(_ transaction: RestoreTransaction) throws {
        #expect(!transaction.isPending)
        #expect(!location.restorePending)
        for leftover in [transaction.previous, transaction.staged, transaction.work] {
            #expect(!exists(leftover))
        }
    }

    @Test(arguments: ["markerOnly", "stateOnly", "corrupt", "mismatched", "stopped"])
    func committedRestoreRepairsPartialAuthorityAsStopped(damage: String) throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let tx = try begun()
        try tx.markCommitting()
        let authority = RecurringAutomationAuthority(directory: location.directory)
        try authority.stop()
        switch damage {
        case "markerOnly": try FileManager.default.removeItem(at: authority.file)
        case "stateOnly": try FileManager.default.removeItem(at: authority.marker)
        case "corrupt": try Data("{".utf8).write(to: authority.file)
        case "mismatched":
            try Data("{\"version\":1,\"instance\":\"\(UUID())\"}".utf8).write(to: authority.marker)
        default: break
        }
        // An earlier failed reader must not permanently latch out committed restore recovery.
        #expect(!authority.allowed())
        #expect(try tx.recover(seed: FakeSeed(newSeed)) == .completed)
        try expectSettled(tx)
        #expect(read(location.directory) == "new")
        #expect(tx.settingsPending)
        #expect(!authority.allowed())
        try authority.suspension(false)
        #expect(!authority.allowed())
    }

    @Test func authorityInitializationPersistsStateBeforeMarker() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try write("wallet", to: location.directory)
        let directory = location.directory
        let stateFile = directory.appending(path: ".noah-recurring-automation.json")
        let markerFile = stateFile.appendingPathExtension("initialized")
        let authority = RecurringAutomationAuthority(directory: directory, sync: { target in
            try RestoreTransaction.fullSync(target)
            if FileManager.default.fileExists(atPath: markerFile.path) {
                #expect(FileManager.default.fileExists(atPath: stateFile.path))
                let raw = try Data(contentsOf: stateFile)
                #expect((try JSONSerialization.jsonObject(with: raw) as? [String: Any])?["generation"] != nil)
            }
        })
        _ = try authority.generation()
    }

    @Test func swapMovesTheOldWalletAsideAndRecordsNoSeed() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        #expect(read(location.directory) == "new")
        #expect(read(transaction.previous) == "old")
        let journal = try transaction.readJournal()
        #expect(journal.phase == .installing)
        #expect(journal.hadPreviousData)
        let raw = String(decoding: try Data(contentsOf: transaction.journalURL), as: UTF8.self)
        #expect(!raw.contains("abandon"))
    }

    @Test func crashAfterJournalBeforeAnyMoveKeepsTheOriginal() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        // Undo both renames: journal written, nothing moved yet.
        try FileManager.default.moveItem(at: location.directory, to: transaction.staged)
        try FileManager.default.moveItem(at: transaction.previous, to: location.directory)

        #expect(try transaction.recover(seed: FakeSeed("old seed")) == .rolledBack)
        #expect(read(location.directory) == "old")
        try expectSettled(transaction)
    }

    @Test func crashBetweenTheTwoRenamesPutsTheOriginalBack() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        try FileManager.default.moveItem(at: location.directory, to: transaction.staged)

        #expect(try transaction.recover(seed: FakeSeed("old seed")) == .rolledBack)
        #expect(read(location.directory) == "old")
        try expectSettled(transaction)
    }

    @Test func crashAfterInstallBeforeCommitIsUndoneEvenIfTheSeedMatches() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        // Restoring the seed already on the device: `installing` is still undone.
        #expect(try transaction.recover(seed: FakeSeed(newSeed)) == .rolledBack)
        #expect(read(location.directory) == "old")
        try expectSettled(transaction)
    }

    @Test func crashDuringUndoIsFinishedOnTheNextRun() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        // Undo got as far as removing the restored datadir.
        try FileManager.default.removeItem(at: location.directory)
        #expect(try transaction.recover(seed: FakeSeed("old seed")) == .rolledBack)
        #expect(read(location.directory) == "old")
        try expectSettled(transaction)
    }

    @Test func crashWhileDiscardingTheRestoredDatadirIsFinishedOnTheNextRun() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        // Undo moved the restored datadir aside and had started deleting it
        // (marker already gone) when the app died.
        try FileManager.default.moveItem(at: location.directory, to: transaction.discard)
        try FileManager.default.removeItem(at: transaction.discard.appending(path: RestoreTransaction.markerName))

        #expect(try transaction.recover(seed: FakeSeed("old seed")) == .rolledBack)
        #expect(read(location.directory) == "old")
        try expectSettled(transaction)
        #expect(!exists(transaction.discard))
    }

    @Test func crashAfterDiscardingOntoAnEmptyDeviceIsFinished() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun(previous: nil)
        try FileManager.default.moveItem(at: location.directory, to: transaction.discard)
        #expect(try transaction.recover(seed: FakeSeed(nil)) == .rolledBack)
        #expect(!exists(location.directory))
        try expectSettled(transaction)
    }

    @Test func committingWithTheRestoredSeedSavedFinishes() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        try transaction.markCommitting()

        #expect(try transaction.recover(seed: FakeSeed(newSeed)) == .completed)
        #expect(read(location.directory) == "new")
        try expectSettled(transaction)
    }

    @Test func committingWithoutTheSeedSavedIsUndone() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        try transaction.markCommitting()

        #expect(try transaction.recover(seed: FakeSeed("old seed")) == .rolledBack)
        #expect(read(location.directory) == "old")
        try expectSettled(transaction)
    }

    @Test func committingWithAnUnreadableKeychainWaits() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        try transaction.markCommitting()
        let seed = FakeSeed(newSeed)
        seed.makeUnavailable()

        #expect(try transaction.recover(seed: seed) == .waitingForKeychain)
        #expect(transaction.isPending)
        #expect(read(location.directory) == "new")
        #expect(read(transaction.previous) == "old")

        seed.makeUnavailable(false)
        #expect(try transaction.recover(seed: seed) == .completed)
        try expectSettled(transaction)
    }

    @Test func restoreOntoAnEmptyDeviceIsUndoneByRemovingIt() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun(previous: nil)
        #expect(try !transaction.readJournal().hadPreviousData)
        #expect(try transaction.recover(seed: FakeSeed(nil)) == .rolledBack)
        #expect(!exists(location.directory))
        try expectSettled(transaction)
    }

    @Test func sessionRefusesToOpenWhileARestoreIsPending() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        _ = try begun()
        struct Unreachable: Error {}
        let session = WalletSession(variant: .regtest, location: location) { _, _, _, _, _ in throw Unreachable() }
        await #expect(throws: WalletSession.OpenError.restorePending) {
            try await session.openExisting(mnemonic: newSeed, runDaemon: false, appVersion: "test")
        }
        await #expect(throws: WalletSession.OpenError.restorePending) {
            try await session.createNew(mnemonic: newSeed, runDaemon: false, appVersion: "test")
        }
    }

    @Test func undoNeverRemovesADatadirItDidNotInstall() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun(previous: nil)
        // Someone else's wallet where the restored one should be.
        try FileManager.default.removeItem(at: location.directory)
        try write("other wallet", to: location.directory)
        #expect(throws: RestoreTransaction.Failure.unexpectedDatadir) {
            try transaction.recover(seed: FakeSeed(nil))
        }
        #expect(read(location.directory) == "other wallet")
        #expect(transaction.isPending)
    }

    @Test func finishDropsTheTransactionMarker() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        #expect(exists(location.directory.appending(path: RestoreTransaction.markerName)))
        try transaction.markCommitting()
        #expect(try transaction.recover(seed: FakeSeed(newSeed)) == .completed)
        #expect(!exists(location.directory.appending(path: RestoreTransaction.markerName)))
    }

    @Test func workspaceLeftFromAnInterruptedSetupIsExcludedOnNextUse() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = RestoreTransaction(location: location)
        try FileManager.default.createDirectory(at: transaction.directory, withIntermediateDirectories: true)
        #expect(try transaction.directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == false)
        try transaction.prepare()
        #expect(try transaction.directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }

    @Test func unreadableJournalTouchesNothing() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        try Data("{".utf8).write(to: transaction.journalURL)
        #expect(throws: RestoreTransaction.Failure.journalUnreadable) {
            try transaction.recover(seed: FakeSeed("old seed"))
        }
        #expect(read(location.directory) == "new")
        #expect(read(transaction.previous) == "old")
    }

    @Test func nothingPendingSweepsDecryptedLeftoversOnly() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try write("current", to: location.directory)
        let transaction = RestoreTransaction(location: location)
        try write("decrypted", to: transaction.staged)
        try write("decrypted", to: transaction.work)

        #expect(try transaction.recover(seed: FakeSeed("seed")) == .nothingPending)
        #expect(!exists(transaction.staged))
        #expect(!exists(transaction.work))
        #expect(read(location.directory) == "current")
    }

    @Test func previousWalletWithoutJournalIsNeverTouched() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = RestoreTransaction(location: location)
        try write("someone's wallet", to: transaction.previous)

        #expect(try transaction.recover(seed: FakeSeed("seed")) == .nothingPending)
        #expect(throws: RestoreTransaction.Failure.orphanedPreviousWallet) { try transaction.prepare() }
        #expect(read(transaction.previous) == "someone's wallet")
    }

    @Test func pendingTransactionBlocksANewOne() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        #expect(throws: RestoreTransaction.Failure.alreadyPending) { try transaction.prepare() }
    }

    @Test func workspaceIsExcludedFromDeviceBackups() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let transaction = try begun()
        let values = try transaction.directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
    }
}
