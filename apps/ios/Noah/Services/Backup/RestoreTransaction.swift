import CryptoKit
import Darwin
import Foundation

/// Swaps a restored wallet into the datadir and commits its seed so that a
/// crash or power loss at any point is settled at the next launch: either
/// the restored wallet with its seed, or the previous wallet with the
/// previous seed, never one wallet with the other's seed.
///
/// Workspace (`WalletDataLocation.restoreDirectory`, next to the datadir):
///
///     journal.json  id, phase, whether a datadir existed, tag of the new seed
///     previous/     the datadir that was there before the restore
///     staged/       the restored datadir before it is moved in
///     discard/      a restored datadir being thrown away by `undo`
///     work/         downloads and decrypted files
///
/// Restore order: build `staged/` → `begin` (staged files and the journal
/// `installing` on disk, datadir to `previous/`, `staged/` to datadir, both
/// directories synced) → open check → `markCommitting` → save the seed →
/// `finish`. The seed write is the commit point: `installing` is always
/// undone; `committing` is finished only if the Keychain holds the restored
/// seed, undone if it holds anything else, and left alone while the
/// Keychain can't be read.
///
/// Every step that recovery relies on is fully synced before the next one
/// (`F_FULLFSYNC`), and the journal goes last, so a power loss can't bring
/// back a journal for a transaction that was already settled. The restored
/// datadir carries the transaction id until `finish`; undo never removes a
/// datadir without it, and first renames it into `discard/` so a crash in the
/// middle of deleting it can't leave an unmarked datadir behind.
struct RestoreTransaction: Sendable {
    static let journalName = "journal.json"
    static let markerName = ".noah-restore-id"

    enum Phase: String, Codable, Sendable {
        case installing, committing
    }

    struct Journal: Codable, Equatable, Sendable {
        var version = 1
        var id: UUID
        var phase: Phase
        var hadPreviousData: Bool
        /// HMAC-SHA256 of the restored mnemonic under `seedSalt`: lets
        /// recovery recognise the seed without storing it.
        var seedSalt: Data
        var seedTag: Data
    }

    enum Recovery: Equatable, Sendable {
        case nothingPending
        case rolledBack
        case completed
        /// `committing`, and the Keychain can't be read: decide later.
        case waitingForKeychain
    }

    enum Failure: Error, Equatable {
        case alreadyPending
        case journalUnreadable
        /// `previous/` exists without a journal. It may be the user's wallet,
        /// so nothing touches it.
        case orphanedPreviousWallet
        /// The datadir isn't the one this transaction installed; undo won't
        /// remove it.
        case unexpectedDatadir
        case randomUnavailable
        case syncFailed(Int32)
    }

    private let location: WalletDataLocation
    let datadir: URL
    let directory: URL

    init(location: WalletDataLocation) {
        self.location = location
        datadir = location.directory
        directory = location.restoreDirectory
    }

    var journalURL: URL { directory.appending(path: Self.journalName) }
    var previous: URL { directory.appending(path: "previous", directoryHint: .isDirectory) }
    var staged: URL { directory.appending(path: "staged", directoryHint: .isDirectory) }
    var discard: URL { directory.appending(path: "discard", directoryHint: .isDirectory) }
    var work: URL { directory.appending(path: "work", directoryHint: .isDirectory) }

    var isPending: Bool { exists(journalURL) }

    /// Empty `work/` for a new restore; `staged/` is left for the caller to
    /// create. Fails while a transaction is pending.
    func prepare() throws {
        guard !isPending else { throw Failure.alreadyPending }
        guard !exists(previous) else { throw Failure.orphanedPreviousWallet }
        try location.removeDiscardedDeletionData()
        try ensureWorkspace()
        try removeScratch()
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: false)
    }

    /// Records the transaction, then moves the current datadir (if any) to
    /// `previous/` and `staged/` into its place. If this throws, call `undo`.
    func begin(seed mnemonic: String) throws {
        var salt = Data(count: 32)
        let status = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard status == errSecSuccess else { throw Failure.randomUnavailable }
        let journal = Journal(
            id: UUID(), phase: .installing, hadPreviousData: exists(datadir),
            seedSalt: salt, seedTag: Self.tag(mnemonic, salt: salt))

        try Data(journal.id.uuidString.utf8).write(to: staged.appending(path: Self.markerName))
        try Self.syncTree(staged)
        try Self.fullSync(directory)
        try write(journal)

        // Each rename is durable before the next.
        if journal.hadPreviousData {
            try FileManager.default.moveItem(at: datadir, to: previous)
            try syncBothSides()
        }
        try FileManager.default.moveItem(at: staged, to: datadir)
        try syncBothSides()
    }

    /// The restored files opened with the new seed; the seed write comes next.
    func markCommitting() throws {
        var journal = try readJournal()
        journal.phase = .committing
        try write(journal)
    }

    var settingsCompletion: URL { directory.appending(path: "settings-pending") }
    var settingsPending: Bool { exists(settingsCompletion) }

    /// Wallet recovery is committed independently of preferences; retry on foreground opens.
    func completeSettings(_ settings: SettingsStorage) {
        guard settingsPending, settings.completeRestore() else { return }
        try? FileManager.default.removeItem(at: settingsCompletion)
        try? Self.fullSync(directory)
    }

    /// The seed is saved: retain settings completion, then drop the journal last.
    func finish(sync: @Sendable (URL) throws -> Void = RestoreTransaction.fullSync) throws {
        try Data().write(to: settingsCompletion)
        try sync(settingsCompletion)
        try sync(directory)
        try establishStoppedAutomation(sync: sync)
        let marker = datadir.appending(path: Self.markerName)
        if exists(marker) {
            try FileManager.default.removeItem(at: marker)
            try sync(datadir)
        }
        try removeIfPresent(previous)
        try removeScratch()
        try sync(directory)
        try removeJournal(sync: sync)
    }

    /// Only committed restore settlement may repair authority, always stopped, state before marker.
    private func establishStoppedAutomation(sync: @Sendable (URL) throws -> Void) throws {
        let authority = RecurringAutomationAuthority(directory: datadir)
        let identity: [String: Any] = ["version": 1, "instance": UUID().uuidString]
        var state = identity
        state["generation"] = UUID().uuidString
        state["suspended"] = false
        state["stopped"] = true
        try RecurringStore.durableWrite(JSONSerialization.data(withJSONObject: state), to: authority.file, sync: sync)
        try RecurringStore.durableWrite(JSONSerialization.data(withJSONObject: identity), to: authority.marker, sync: sync)
        RecurringAutomationAuthority.deleted(directory: datadir)
    }

    /// Puts the previous datadir back (or removes the restored one when there
    /// was none). Safe to repeat after a crash at any step.
    func undo() throws {
        let journal = try readJournal()
        let restoredInPlace = (try? String(contentsOf: datadir.appending(path: Self.markerName), encoding: .utf8))
            == journal.id.uuidString
        // Without previous/, a datadir that existed before was never moved (or
        // was already moved back): it is the original and stays.
        let restoredMustGo = journal.hadPreviousData ? exists(previous) : true
        if restoredMustGo && exists(datadir) {
            guard restoredInPlace else { throw Failure.unexpectedDatadir }
            try removeIfPresent(discard)
            try FileManager.default.moveItem(at: datadir, to: discard)
            try syncBothSides()
        }
        if journal.hadPreviousData && exists(previous) {
            try FileManager.default.moveItem(at: previous, to: datadir)
            try syncBothSides()
        }
        try removeScratch()
        try Self.fullSync(directory)
        try removeJournal()
    }

    /// Run before anything opens the wallet or reads the datadir at launch.
    /// With nothing pending, only decrypted leftovers are removed.
    func recover(seed: any SecretItem) throws -> Recovery {
        guard isPending else {
            try removeScratch()
            return .nothingPending
        }
        let journal = try readJournal()
        switch journal.phase {
        case .installing:
            try undo()
            return .rolledBack
        case .committing:
            switch seed.read() {
            case .found(let value) where Self.tag(value, salt: journal.seedSalt) == journal.seedTag:
                try finish()
                return .completed
            case .found, .notFound:
                try undo()
                return .rolledBack
            case .unavailable:
                return .waitingForKeychain
            }
        }
    }

    /// Removes the decrypted downloads, an unused staged datadir and a
    /// discarded restored one.
    func removeScratch() throws {
        try removeIfPresent(staged)
        try removeIfPresent(discard)
        try removeIfPresent(work)
    }

    func readJournal() throws -> Journal {
        guard let data = try? Data(contentsOf: journalURL),
            let journal = try? JSONDecoder().decode(Journal.self, from: data), journal.version == 1
        else { throw Failure.journalUnreadable }
        return journal
    }

    static func tag(_ mnemonic: String, salt: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data(mnemonic.utf8), using: SymmetricKey(data: salt)))
    }

    // MARK: Files

    /// Readable after first unlock (push handlers) and excluded from device
    /// backups, like the decrypted data it holds. Applied every time: an
    /// existing folder may be left from an interrupted first setup.
    private func ensureWorkspace() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: directory.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try url.setResourceValues(values)
        try Self.fullSync(directory.deletingLastPathComponent())
    }

    /// Temp file, full sync, rename, full sync of the directory: the journal
    /// is on disk before any move it describes.
    private func write(_ journal: Journal) throws {
        try ensureWorkspace()
        let data = try JSONEncoder().encode(journal)
        let temporary = directory.appending(path: ".journal-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: temporary) }
        try data.write(to: temporary, options: .completeFileProtectionUntilFirstUserAuthentication)
        try Self.fullSync(temporary)
        guard Darwin.rename(temporary.path, journalURL.path) == 0 else { throw Failure.syncFailed(errno) }
        try Self.fullSync(directory)
    }

    /// Settled: the journal's removal is on disk before anyone else may touch
    /// the datadir, so it can't reappear after a power loss.
    private func removeJournal(sync: @Sendable (URL) throws -> Void = RestoreTransaction.fullSync) throws {
        try removeIfPresent(journalURL)
        try sync(directory)
    }

    /// A rename between the workspace and the datadir's parent changes both.
    private func syncBothSides() throws {
        try Self.fullSync(datadir.deletingLastPathComponent())
        try Self.fullSync(directory)
    }

    /// Every file and folder under `root`, then `root` itself.
    private static func syncTree(_ root: URL) throws {
        let items = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)?
            .compactMap { $0 as? URL } ?? []
        for item in items { try fullSync(item) }
        try fullSync(root)
    }

    /// `F_FULLFSYNC` (survives power loss); plain `fsync` only where the file
    /// system doesn't support it. Real I/O errors are reported.
    static func fullSync(_ url: URL) throws {
        let descriptor = Darwin.open(url.path, O_RDONLY)
        guard descriptor >= 0 else { throw Failure.syncFailed(errno) }
        defer { Darwin.close(descriptor) }
        if fcntl(descriptor, F_FULLFSYNC) == 0 { return }
        let fullSyncError = errno
        guard [ENOTSUP, EINVAL, ENOTTY].contains(fullSyncError) else { throw Failure.syncFailed(fullSyncError) }
        guard fsync(descriptor) == 0 else { throw Failure.syncFailed(errno) }
    }

    private func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    private func removeIfPresent(_ url: URL) throws {
        guard exists(url) else { return }
        try FileManager.default.removeItem(at: url)
    }
}
