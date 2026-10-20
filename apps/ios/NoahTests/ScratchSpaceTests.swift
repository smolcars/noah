import Foundation
import Testing

@testable import Noah

struct ScratchSpaceTests {
    private let root = FileManager.default.temporaryDirectory.appending(path: "scratch-\(UUID().uuidString)")

    @Test func folderIsRemovedAfterSuccessAndFailure() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let scratch = ScratchSpace(root: root)
        let used = try await scratch.withFolder("backup") { folder in
            try Data("plaintext".utf8).write(to: folder.appending(path: "db.sqlite"))
            return folder
        }
        #expect(!FileManager.default.fileExists(atPath: used.path))

        struct Boom: Error {}
        await #expect(throws: Boom.self) {
            try await scratch.withFolder("backup") { folder in
                try Data("plaintext".utf8).write(to: folder.appending(path: "db.sqlite"))
                throw Boom()
            }
        }
        #expect(try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty)
    }

    @Test func deletionGateRejectsNewExportsAndASecondDeletionUntilReleased() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let scratch = ScratchSpace(root: root)
        try await scratch.drainForWalletDeletion()
        #expect(throws: (any Error).self) { try scratch.makeFolder("late-share") }
        await #expect(throws: (any Error).self) { try await scratch.withFolder("late-export") { _ in } }
        await #expect(throws: (any Error).self) { try await scratch.drainForWalletDeletion() }
        scratch.finishWalletDeletion()
        try await scratch.withFolder("new-wallet") { _ in }
    }

    @Test func checkedSweepRefusesActivePlaintext() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let scratch = ScratchSpace(root: root)
        let folder = try scratch.makeFolder("backup")
        let snapshot = folder.appending(path: "snapshot.sqlite")
        try Data("plaintext".utf8).write(to: snapshot)
        #expect(throws: (any Error).self) { try scratch.sweepForWalletDeletion() }
        #expect(FileManager.default.fileExists(atPath: snapshot.path))
        scratch.discard(folder)
        try scratch.sweepForWalletDeletion()
    }

    @Test func sweepRemovesAbandonedFoldersButNotOnesInUse() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let scratch = ScratchSpace(root: root)
        // Left by a backup that died with the app.
        let abandoned = root.appending(path: "backup-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: abandoned, withIntermediateDirectories: true)
        try Data("plaintext".utf8).write(to: abandoned.appending(path: "wallet-snapshot.sqlite"))

        try await scratch.withFolder("export") { folder in
            scratch.sweepAbandoned()
            #expect(!FileManager.default.fileExists(atPath: abandoned.path))
            #expect(FileManager.default.fileExists(atPath: folder.path), "a folder in use survives the sweep")
        }
    }
}
