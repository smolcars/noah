import Foundation

/// Bark wallet datadir. Same path the React Native app used
/// (`ARK_DATA_PATH` = `Documents/noah-data-<variant>`), so the native app
/// reopens the existing database instead of recreating Ark state.
struct WalletDataLocation: Sendable {
    let directory: URL

    init(variant: AppVariant, documentsDirectory: URL = .documentsDirectory) {
        directory = documentsDirectory.appending(
            path: "noah-data-\(variant.rawValue)", directoryHint: .isDirectory)
    }

    var deletionMarker: URL {
        directory.deletingLastPathComponent().appending(path: ".\(directory.lastPathComponent)-deleting")
    }

    var deletionDirectory: URL {
        directory.deletingLastPathComponent().appending(path: ".\(directory.lastPathComponent)-deleting-data")
    }

    /// Local instance identity: intentionally excluded from backup snapshots.
    var instanceIdentity: URL { directory.appending(path: ".noah-wallet-instance") }

    func writeDeletionIdentity() throws {
        let fm = FileManager.default
        let identity = Data(UUID().uuidString.utf8)
        if fm.fileExists(atPath: directory.path) {
            try identity.write(to: instanceIdentity, options: .atomic)
            let handle = try FileHandle(forWritingTo: instanceIdentity)
            try handle.synchronize()
            try handle.close()
            try RestoreTransaction.fullSync(directory)
        }
        try identity.write(to: deletionMarker, options: .atomic)
    }

    func deletionMatchesWallet() throws -> Bool {
        let marker = try Data(contentsOf: deletionMarker)
        let legacy = marker == Data([1])
        guard legacy || UUID(uuidString: String(decoding: marker, as: UTF8.self)) != nil else { return false }
        guard FileManager.default.fileExists(atPath: directory.path) else { return true }
        // A legacy marker cannot establish ownership of a populated datadir.
        guard !legacy, FileManager.default.fileExists(atPath: instanceIdentity.path) else { return false }
        return try Data(contentsOf: instanceIdentity) == marker
    }

    var database: URL { directory.appending(path: "db.sqlite", directoryHint: .notDirectory) }

    /// Restore workspace (`RestoreTransaction`): next to the datadir, so on
    /// the same volume and every swap is a rename.
    var restoreDirectory: URL {
        directory.deletingLastPathComponent().appending(
            path: ".\(directory.lastPathComponent)-restore", directoryHint: .isDirectory)
    }

    /// A restore was interrupted and is not settled yet. Nothing may open or
    /// create the wallet until `RestoreTransaction.recover` has run.
    var restorePending: Bool {
        FileManager.default.fileExists(atPath: restoreDirectory.appending(path: RestoreTransaction.journalName).path)
    }

    var databaseExists: Bool {
        FileManager.default.fileExists(atPath: database.path)
    }

    /// Anything at all in the datadir, as the RN app checked
    /// (`walletDataExists`). A datadir with other files but no `db.sqlite`
    /// is still someone's data and must never be treated as empty.
    var hasAnyData: Bool {
        let contents = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return !contents.isEmpty
    }
}
