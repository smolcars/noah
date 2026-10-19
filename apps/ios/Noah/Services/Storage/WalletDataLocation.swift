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

    var database: URL { directory.appending(path: "db.sqlite", directoryHint: .notDirectory) }

    var databaseExists: Bool {
        FileManager.default.fileExists(atPath: database.path)
    }
}
