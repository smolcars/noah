import Foundation
import Testing

@testable import Noah

@MainActor
struct LogSnapshotTests {
    @Test func readStatesKeepTheLastSuccessfulTimestampAndRecords() async {
        let state = LogSnapshot()
        #expect(state.loading)
        let date = Date(timeIntervalSince1970: 100)
        await state.load(read: { ["older", "newest"] }, now: { date })
        #expect(!state.loading && state.error == nil)
        #expect(state.lines.last == "newest")
        #expect(state.updatedAt == date)
        await state.load { throw CocoaError(.fileReadNoPermission) }
        #expect(!state.loading && state.error == "Failed to fetch logs.")
        #expect(state.updatedAt == date && state.lines == ["older", "newest"])
    }

    @Test func unreadableFilesAreErrorsAndMissingDirectoryIsEmpty() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "log-read-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let file = LogFile(directory: root)
        #expect(try await file.checkedLinesForExport().isEmpty)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try "older\n".write(to: root.appending(path: "noah.log.1"), atomically: true, encoding: .utf8)
        try "newest\n".write(to: root.appending(path: "noah.log"), atomically: true, encoding: .utf8)
        #expect(try await file.checkedLinesForExport() == ["older", "newest"])
        try FileManager.default.removeItem(at: root.appending(path: "noah.log"))
        try FileManager.default.createDirectory(at: root.appending(path: "noah.log"), withIntermediateDirectories: true)
        await #expect(throws: (any Error).self) { try await file.checkedLinesForExport() }
    }
}
