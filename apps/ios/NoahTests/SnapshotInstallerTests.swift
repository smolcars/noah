import Foundation
import Testing

@testable import Noah

struct SnapshotInstallerTests {
    private let root = FileManager.default.temporaryDirectory.appending(path: "installer-\(UUID().uuidString)")
    private var datadir: URL { root.appending(path: "noah-data-regtest") }

    private func folder(_ name: String, file: String, contents: String) throws -> URL {
        let url = root.appending(path: name)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        try Data(contents.utf8).write(to: url.appending(path: file))
        return url
    }

    private func read(_ url: URL) -> String? {
        (try? Data(contentsOf: url)).map { String(decoding: $0, as: UTF8.self) }
    }

    @Test func installsKeepsRollbackAndFinalizes() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        _ = try folder("noah-data-regtest", file: "db.sqlite", contents: "old")
        let restored = try folder("restored", file: "db.sqlite", contents: "new")
        let installer = SnapshotInstaller(datadir: datadir)

        let rollback = try #require(try installer.install(contentsOf: restored))
        #expect(read(datadir.appending(path: "db.sqlite")) == "new")
        #expect(read(rollback.appending(path: "db.sqlite")) == "old")
        installer.finalize(rollback: rollback)
        #expect(!FileManager.default.fileExists(atPath: rollback.path))
    }

    @Test func rollbackPutsThePreviousWalletBack() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        _ = try folder("noah-data-regtest", file: "db.sqlite", contents: "old")
        let installer = SnapshotInstaller(datadir: datadir)
        let rollback = try installer.install(contentsOf: try folder("restored", file: "db.sqlite", contents: "new"))
        try installer.rollback(rollback)
        #expect(read(datadir.appending(path: "db.sqlite")) == "old")
    }

    @Test func rollbackWithoutPreviousWalletRemovesTheRestoredOne() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let installer = SnapshotInstaller(datadir: datadir)
        let rollback = try installer.install(contentsOf: try folder("restored", file: "db.sqlite", contents: "new"))
        #expect(rollback == nil)
        try installer.rollback(rollback)
        #expect(!FileManager.default.fileExists(atPath: datadir.path))
    }

    @Test func recoversAnInstallInterruptedBetweenRenames() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        // Crash state: previous wallet moved aside, new one never moved in.
        _ = try folder(".wallet-rollback-\(UUID().uuidString)", file: "db.sqlite", contents: "old")
        _ = try folder(".wallet-install-\(UUID().uuidString)", file: "db.sqlite", contents: "partial")
        let installer = SnapshotInstaller(datadir: datadir)
        #expect(installer.recoverInterruptedInstall())
        #expect(read(datadir.appending(path: "db.sqlite")) == "old")
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: root.path).filter { $0.hasPrefix(".wallet-install-") }
        #expect(leftovers.isEmpty)
    }

    @Test func leavesRollbackFoldersAloneWhenTheDatadirExists() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        _ = try folder("noah-data-regtest", file: "db.sqlite", contents: "current")
        let stale = try folder(".wallet-rollback-\(UUID().uuidString)", file: "db.sqlite", contents: "older")
        #expect(!SnapshotInstaller(datadir: datadir).recoverInterruptedInstall())
        #expect(read(datadir.appending(path: "db.sqlite")) == "current")
        #expect(FileManager.default.fileExists(atPath: stale.path))
    }
}
