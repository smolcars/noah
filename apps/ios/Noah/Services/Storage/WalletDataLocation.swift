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
