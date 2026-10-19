import Foundation
import Synchronization

/// Per-operation folders for plaintext wallet data (backup snapshots, zip
/// staging) that must not outlive their operation. Folders in use are
/// tracked in-process; `sweepAbandoned` removes the others, which a killed
/// app left behind, and never touches one in use.
final class ScratchSpace: Sendable {
    static let shared = ScratchSpace(root: URL.cachesDirectory.appending(path: "noah-scratch", directoryHint: .isDirectory))

    let root: URL
    private let inUse = Mutex<Set<String>>([])

    init(root: URL) {
        self.root = root
    }

    /// Runs `body` with a fresh folder that is removed afterwards, whatever happens.
    func withFolder<T>(_ purpose: String, _ body: (URL) async throws -> T) async throws -> T {
        let folder = try makeFolder(purpose)
        defer { discard(folder) }
        return try await body(folder)
    }

    /// Run at launch, before any backup can start in this process.
    func sweepAbandoned() {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? []
        inUse.withLock { live in
            for name in names where !live.contains(name) {
                try? FileManager.default.removeItem(at: root.appending(path: name))
            }
        }
    }

    /// For share-sheet lifetimes; the caller must discard after dismissal.
    func makeFolder(_ purpose: String) throws -> URL {
        let name = "\(purpose)-\(UUID().uuidString)"
        let folder = root.appending(path: name, directoryHint: .isDirectory)
        try inUse.withLock { live in
            try FileManager.default.createDirectory(
                at: folder, withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            live.insert(name)
        }
        return folder
    }

    func discard(_ folder: URL) {
        inUse.withLock { live in
            try? FileManager.default.removeItem(at: folder)
            live.remove(folder.lastPathComponent)
        }
    }
}
