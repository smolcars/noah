import Foundation
import Security

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

    /// Lives outside the removed datadir, so ownership survives every deletion boundary.
    private struct DeletionIdentity: Codable {
        var version = 1
        var id: UUID
        var seedSalt: Data
        var seedTag: Data?
    }

    func writeDeletionIdentity(seed: KeychainPassword.ReadResult) throws {
        if case .unavailable(let status) = seed { throw KeychainPassword.WriteError(status: status) }
        var salt = Data(count: 32)
        let status = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard status == errSecSuccess else { throw RestoreTransaction.Failure.randomUnavailable }
        let tag: Data?
        if case .found(let mnemonic) = seed { tag = RestoreTransaction.tag(mnemonic, salt: salt) }
        else { tag = nil }
        let identity = DeletionIdentity(id: UUID(), seedSalt: salt, seedTag: tag)
        let fm = FileManager.default
        if fm.fileExists(atPath: directory.path) {
            try Data(identity.id.uuidString.utf8).write(to: instanceIdentity, options: .atomic)
            let handle = try FileHandle(forWritingTo: instanceIdentity)
            try handle.synchronize()
            try handle.close()
            try RestoreTransaction.fullSync(directory)
        }
        try JSONEncoder().encode(identity).write(to: deletionMarker, options: .atomic)
    }

    func deletionMatchesWallet(seed: KeychainPassword.ReadResult) throws -> Bool {
        let marker = try Data(contentsOf: deletionMarker)
        // UUID-only and one-byte legacy markers cannot prove seed ownership, even without a datadir.
        guard let identity = try? JSONDecoder().decode(DeletionIdentity.self, from: marker),
              identity.version == 1, identity.seedSalt.count == 32,
              identity.seedTag == nil || identity.seedTag?.count == 32 else { return false }
        switch seed {
        case .found(let mnemonic):
            guard RestoreTransaction.tag(mnemonic, salt: identity.seedSalt) == identity.seedTag else { return false }
        case .unavailable: return false
        case .notFound: break // Only non-seed cleanup remains.
        }
        guard FileManager.default.fileExists(atPath: directory.path) else { return true }
        guard FileManager.default.fileExists(atPath: instanceIdentity.path) else { return false }
        return try Data(contentsOf: instanceIdentity) == Data(identity.id.uuidString.utf8)
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
