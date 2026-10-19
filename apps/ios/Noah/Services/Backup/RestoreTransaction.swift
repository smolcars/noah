import CryptoKit
import Darwin
import Foundation

/// Swaps a restored wallet into the datadir and commits its seed so that a
/// crash at any point is settled at the next launch: either the restored
/// wallet with its seed, or the previous wallet with the previous seed,
/// never one wallet with the other's seed.
///
/// Workspace (`WalletDataLocation.restoreDirectory`, next to the datadir):
///
///     journal.json  phase, whether a datadir existed, tag of the new seed
///     previous/     the datadir that was there before the restore
///     staged/       the restored datadir before it is moved in
///     work/         downloads and decrypted files
///
/// Restore order: build `staged/` → `begin` (journal `installing`, datadir to
/// `previous/`, `staged/` to datadir) → open check → `markCommitting` → save
/// the seed → `finish`. The seed write is the commit point:
/// `installing` is always undone; `committing` is finished only if the
/// Keychain holds the restored seed, undone if it holds anything else, and
/// left alone while the Keychain can't be read.
struct RestoreTransaction: Sendable {
    static let journalName = "journal.json"

    enum Phase: String, Codable, Sendable {
        case installing, committing
    }

    struct Journal: Codable, Equatable, Sendable {
        var version = 1
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
        case randomUnavailable
    }

    let datadir: URL
    let directory: URL

    init(location: WalletDataLocation) {
        datadir = location.directory
        directory = location.restoreDirectory
    }

    var journalURL: URL { directory.appending(path: Self.journalName) }
    var previous: URL { directory.appending(path: "previous", directoryHint: .isDirectory) }
    var staged: URL { directory.appending(path: "staged", directoryHint: .isDirectory) }
    var work: URL { directory.appending(path: "work", directoryHint: .isDirectory) }

    var isPending: Bool { exists(journalURL) }

    /// Empty `work/` for a new restore; `staged/` is left for the caller to
    /// create. Fails while a transaction is pending.
    func prepare() throws {
        guard !isPending else { throw Failure.alreadyPending }
        guard !exists(previous) else { throw Failure.orphanedPreviousWallet }
        try createWorkspace()
        try removeIfPresent(staged)
        try removeIfPresent(work)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: false)
    }

    /// Records the transaction, then moves the current datadir (if any) to
    /// `previous/` and `staged/` into its place. If this throws, call `undo`.
    func begin(seed mnemonic: String) throws {
        var salt = Data(count: 32)
        let status = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard status == errSecSuccess else { throw Failure.randomUnavailable }
        let journal = Journal(
            phase: .installing, hadPreviousData: exists(datadir),
            seedSalt: salt, seedTag: Self.tag(mnemonic, salt: salt))
        try write(journal)
        if journal.hadPreviousData {
            try FileManager.default.moveItem(at: datadir, to: previous)
        }
        try FileManager.default.moveItem(at: staged, to: datadir)
    }

    /// The restored files opened with the new seed; the seed write comes next.
    func markCommitting() throws {
        var journal = try readJournal()
        journal.phase = .committing
        try write(journal)
    }

    /// The seed is saved: drop the previous wallet and the scratch files.
    func finish() throws {
        try removeIfPresent(previous)
        try removeScratch()
        try removeIfPresent(journalURL)
    }

    /// Puts the previous datadir back (or removes the restored one when there
    /// was none). Safe to repeat after a crash at any step.
    func undo() throws {
        let journal = try readJournal()
        if journal.hadPreviousData {
            // Without previous/, the datadir was never moved (or was already
            // moved back): it is the original and stays.
            if exists(previous) {
                try removeIfPresent(datadir)
                try FileManager.default.moveItem(at: previous, to: datadir)
            }
        } else {
            try removeIfPresent(datadir)
        }
        try removeScratch()
        try removeIfPresent(journalURL)
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

    /// Removes the decrypted downloads and an unused staged datadir.
    func removeScratch() throws {
        try removeIfPresent(staged)
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

    /// Readable after first unlock (push handlers), excluded from device
    /// backups like the decrypted data it holds.
    private func createWorkspace() throws {
        guard !exists(directory) else { return }
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try url.setResourceValues(values)
    }

    /// Temp file, full sync, rename, full sync of the directory: the journal
    /// is on disk before any move it describes.
    private func write(_ journal: Journal) throws {
        try createWorkspace()
        let data = try JSONEncoder().encode(journal)
        let temporary = directory.appending(path: ".journal-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: temporary) }
        try data.write(to: temporary, options: .completeFileProtectionUntilFirstUserAuthentication)
        try Self.fullSync(temporary)
        guard Darwin.rename(temporary.path, journalURL.path) == 0 else { throw CocoaError(.fileWriteUnknown) }
        try Self.fullSync(directory)
    }

    private static func fullSync(_ url: URL) throws {
        let descriptor = Darwin.open(url.path, O_RDONLY)
        guard descriptor >= 0 else { throw CocoaError(.fileWriteUnknown) }
        defer { Darwin.close(descriptor) }
        guard fcntl(descriptor, F_FULLFSYNC) == 0 || fsync(descriptor) == 0 else { throw CocoaError(.fileWriteUnknown) }
    }

    private func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    private func removeIfPresent(_ url: URL) throws {
        guard exists(url) else { return }
        try FileManager.default.removeItem(at: url)
    }
}
