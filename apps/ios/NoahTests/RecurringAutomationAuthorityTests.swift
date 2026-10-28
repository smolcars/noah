import Foundation
@testable import Noah
import Testing

struct RecurringAutomationAuthorityTests {
    @Test func stopSurvivesReopenAndOnlyExplicitResumeClearsIt() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "automation-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        func authority() -> RecurringAutomationAuthority { .init(directory: root) }
        let before = try authority().generation()
        try authority().stop()
        #expect(!authority().allowed())
        try authority().suspension(false)
        #expect(!authority().allowed())
        try authority().resume()
        #expect(authority().allowed())
        #expect(try before != authority().generation())
        try authority().suspension(true)
        #expect(!authority().allowed())
    }
    @Test(arguments: 1...6) func interruptedWriteStaysStopped(failAt: Int) throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "automation-crash-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let step = RecurringExecutorTests.Locked(0)
        let authority = RecurringAutomationAuthority(directory: root, sync: { file in
            try RestoreTransaction.fullSync(file)
            if step.withLock({ $0 += 1; return $0 }) == failAt { throw CocoaError(.fileWriteUnknown) }
        })
        #expect(throws: (any Error).self) { try authority.stop() }
        #expect(!RecurringAutomationAuthority(directory: root).allowed())
    }
    @Test func realFileSyncPrecedesRenameAndDirectorySync() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "automation-order-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appending(path: "authority")
        let events = RecurringExecutorTests.Locked<[String]>([])
        try RecurringStore.durableWrite(Data("stop".utf8), to: file, sync: { target in
            if target.lastPathComponent == root.lastPathComponent {
                #expect(try String(contentsOf: file, encoding: .utf8) == "stop")
                events.withLock { $0.append("directory") }
            } else {
                #expect(!FileManager.default.fileExists(atPath: file.path))
                events.withLock { $0.append("file") }
            }
            try RestoreTransaction.fullSync(target)
        })
        #expect(events.withLock { $0 } == ["file", "directory"])
    }
    @Test(arguments: ["missing", "marker-missing", "corrupt", "missing-stop-field"])
    func missingOrCorruptAuthorityCannotBootstrapAgain(damage: String) throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "automation-damage-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let authority = RecurringAutomationAuthority(directory: root)
        _ = try authority.generation()
        switch damage {
        case "missing": try FileManager.default.removeItem(at: authority.file)
        case "marker-missing": try FileManager.default.removeItem(at: authority.marker)
        case "corrupt": try Data("{".utf8).write(to: authority.file)
        default:
            let raw = try String(contentsOf: authority.file, encoding: .utf8)
            try Data(raw.replacingOccurrences(of: "\"stopped\":false", with: "\"other\":false").utf8).write(to: authority.file)
        }
        #expect(!RecurringAutomationAuthority(directory: root).allowed())
        #expect(throws: (any Error).self) { try RecurringAutomationAuthority(directory: root).resume() }
    }
}
