import Foundation

/// Swaps a restored wallet into the datadir with a rollback copy, like
/// `installWalletSnapshot` / `finalize…` / `rollback…` in noah-tools, plus
/// recovery from an install interrupted between its two renames.
struct SnapshotInstaller: Sendable {
    let datadir: URL

    private var parent: URL { datadir.deletingLastPathComponent() }
    private static let installPrefix = ".wallet-install-"
    private static let rollbackPrefix = ".wallet-rollback-"

    /// Installs `contents` (a directory whose files become the datadir) and
    /// returns where the previous datadir was moved, if there was one.
    func install(contentsOf contents: URL) throws -> URL? {
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
        let staged = parent.appending(path: Self.installPrefix + UUID().uuidString, directoryHint: .isDirectory)
        try fileManager.copyItem(at: contents, to: staged)

        var rollback: URL?
        do {
            if fileManager.fileExists(atPath: datadir.path) {
                let target = parent.appending(path: Self.rollbackPrefix + UUID().uuidString, directoryHint: .isDirectory)
                try fileManager.moveItem(at: datadir, to: target)
                rollback = target
            }
            do {
                try fileManager.moveItem(at: staged, to: datadir)
            } catch {
                if let rollback { try? fileManager.moveItem(at: rollback, to: datadir) }
                throw error
            }
            return rollback
        } catch {
            try? fileManager.removeItem(at: staged)
            throw error
        }
    }

    /// Restored wallet is in place and opened: drop the previous copy.
    func finalize(rollback: URL?) {
        guard let rollback else { return }
        try? FileManager.default.removeItem(at: rollback)
    }

    /// Puts the previous datadir back (or removes the restored one when
    /// there was none).
    func rollback(_ rollback: URL?) throws {
        let fileManager = FileManager.default
        if fileManager.fileExists(atPath: datadir.path) {
            try fileManager.removeItem(at: datadir)
        }
        if let rollback {
            try fileManager.moveItem(at: rollback, to: datadir)
        }
    }

    /// Run at launch before deciding anything from the datadir. If an
    /// install died after moving the wallet aside but before moving the new
    /// one in, the datadir is missing and the previous wallet sits in a
    /// rollback folder: move it back. Leftover staging folders are removed.
    /// Rollback folders next to an existing datadir are left alone.
    @discardableResult
    func recoverInterruptedInstall() -> Bool {
        let fileManager = FileManager.default
        let siblings = (try? fileManager.contentsOfDirectory(atPath: parent.path)) ?? []
        for name in siblings where name.hasPrefix(Self.installPrefix) {
            try? fileManager.removeItem(at: parent.appending(path: name))
        }
        guard !fileManager.fileExists(atPath: datadir.path) else { return false }
        let rollbacks = siblings.filter { $0.hasPrefix(Self.rollbackPrefix) }
            .map { parent.appending(path: $0) }
            .sorted { modified($0) > modified($1) }
        guard let newest = rollbacks.first else { return false }
        return (try? fileManager.moveItem(at: newest, to: datadir)) != nil
    }

    private func modified(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }
}
