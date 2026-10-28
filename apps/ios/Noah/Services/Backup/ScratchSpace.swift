import Foundation
import Synchronization

/// Per-operation folders for plaintext wallet data (backup snapshots, zip
/// staging) that must not outlive their operation. Folders in use are
/// tracked in-process; `sweepAbandoned` removes the others, which a killed
/// app left behind, and never touches one in use.
final class ScratchSpace: Sendable {
    static let shared = ScratchSpace(root: URL.cachesDirectory.appending(path: "noah-scratch", directoryHint: .isDirectory))

    let root: URL
    private struct State {
        var live: Set<String> = []
        var operations = 0
        var deleting = false
    }
    private let state = Mutex(State())

    func withOperation<T>(isolation _: isolated (any Actor)? = #isolation, _ body: () async throws -> T) async throws -> T {
        try state.withLock { value in
            guard !value.deleting else { throw CocoaError(.fileWriteNoPermission) }
            value.operations += 1
        }
        defer { state.withLock { $0.operations -= 1 } }
        return try await body()
    }

    func drainForWalletDeletion(timeout: Duration = .seconds(30)) async throws {
        try state.withLock { value in
            guard !value.deleting else { throw CocoaError(.fileWriteFileExists) }
            value.deleting = true
        }
        do {
            let deadline = ContinuousClock.now + timeout
            while state.withLock({ $0.operations != 0 }) {
                guard ContinuousClock.now < deadline else { throw WalletLifecycle.Failure.backupBusy }
                try await Task.sleep(for: .milliseconds(20))
            }
        } catch {
            finishWalletDeletion()
            throw error
        }
    }

    func finishWalletDeletion() { state.withLock { $0.deleting = false } }

    init(root: URL) {
        self.root = root
    }

    /// Runs `body` with a fresh folder that is removed afterwards, whatever happens.
    func withFolder<T>(_ purpose: String, _ body: (URL) async throws -> T) async throws -> T {
        try await withOperation {
            let folder = try makeFolder(purpose)
            defer { discard(folder) }
            return try await body(folder)
        }
    }

    /// Run at launch, before any backup can start in this process.
    func sweepAbandoned() {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? []
        state.withLock { value in
            for name in names where !value.live.contains(name) {
                try? FileManager.default.removeItem(at: root.appending(path: name))
            }
        }
    }

    /// Refuse deletion while plaintext is live, and propagate removal/durability failures before seed deletion.
    func sweepForWalletDeletion() throws {
        try state.withLock { value in
            guard value.operations == 0, value.deleting || value.live.isEmpty else { throw CocoaError(.fileWriteFileExists) }
            let fm = FileManager.default
            if fm.fileExists(atPath: root.path) {
                for name in try fm.contentsOfDirectory(atPath: root.path) {
                    try fm.removeItem(at: root.appending(path: name))
                }
                value.live.removeAll()
                try RestoreTransaction.fullSync(root)
            }
            let parent = root.deletingLastPathComponent()
            if fm.fileExists(atPath: parent.path) { try RestoreTransaction.fullSync(parent) }
        }
    }

    /// For share-sheet lifetimes; the caller must discard after dismissal.
    func makeFolder(_ purpose: String) throws -> URL {
        let name = "\(purpose)-\(UUID().uuidString)"
        let folder = root.appending(path: name, directoryHint: .isDirectory)
        try state.withLock { value in
            guard !value.deleting else { throw CocoaError(.fileWriteNoPermission) }
            try FileManager.default.createDirectory(
                at: folder, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            value.live.insert(name)
        }
        return folder
    }

    func discard(_ folder: URL) {
        state.withLock { value in
            try? FileManager.default.removeItem(at: folder)
            value.live.remove(folder.lastPathComponent)
        }
    }
}
