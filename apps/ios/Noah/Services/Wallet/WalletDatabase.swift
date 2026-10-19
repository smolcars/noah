import Foundation
import SQLite3

/// Read-only look at an existing bark database, used before handing the
/// datadir to bark-bolt-ffi. Since 0.3.0 `OnchainWallet.openExisting` makes
/// the same check (plus the seed fingerprint) before any write; this one
/// stays as defence in depth and gives Noah its own error for the UI.
enum WalletDatabase {
    struct Properties: Equatable, Sendable {
        let network: String
        let fingerprint: String
    }

    enum InspectionError: Error, Equatable {
        case cannotOpen(Int32)
        /// No `bark_properties` row: empty, foreign or half-created database.
        case notABarkWallet
    }

    static func properties(at url: URL) throws(InspectionError) -> Properties {
        var handle: OpaquePointer?
        let status = sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_READONLY, nil)
        defer { sqlite3_close(handle) }
        guard status == SQLITE_OK else { throw .cannotOpen(status) }

        var statement: OpaquePointer?
        let sql = "SELECT network, fingerprint FROM bark_properties LIMIT 1"
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else {
            throw .notABarkWallet
        }
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW,
            let network = sqlite3_column_text(statement, 0),
            let fingerprint = sqlite3_column_text(statement, 1)
        else {
            throw .notABarkWallet
        }
        return Properties(network: String(cString: network), fingerprint: String(cString: fingerprint))
    }
}
