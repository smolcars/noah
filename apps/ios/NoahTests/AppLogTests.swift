import Foundation
import Testing

@testable import Noah

struct AppLogTests {
    private let directory = FileManager.default.temporaryDirectory.appending(path: "logs-\(UUID().uuidString)")

    @Test func writesReactNativeLineFormat() throws {
        let file = LogFile(directory: directory)
        let date = Date(timeIntervalSince1970: 0)
        file.append(level: .warn, tag: "sync", message: "  first\nsecond  ", date: date)
        file.flush()
        let lines = file.lines()
        #expect(lines.count == 2)
        #expect(lines[0].hasSuffix(" W [sync] first"))
        #expect(lines[1] == "  second")
    }

    @Test func rotatesIntoAtMostFourOldFiles() throws {
        let file = LogFile(directory: directory)
        let padding = String(repeating: "x", count: 1000)
        let count = LogFile.maxFileSizeBytes / 1000 * 6
        for index in 0..<count {
            file.append(level: .info, tag: "t", message: "\(index) \(padding)")
        }
        file.flush()
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
        #expect(names == ["noah.log", "noah.log.1", "noah.log.2", "noah.log.3", "noah.log.4"])
        let lines = file.lines()
        #expect(lines.last?.contains("] \(count - 1) x") == true)
        #expect(lines.count < count, "oldest rotated file was dropped")
    }

    @Test func exportsAtMostTheNewestLines() {
        let file = LogFile(directory: directory)
        for index in 0..<(LogFile.maxLines + 100) {
            file.append(level: .info, tag: "t", message: "line \(index)")
        }
        file.flush()
        let lines = file.lines()
        #expect(lines.count == LogFile.maxLines)
        #expect(lines.first?.hasSuffix("line 100") == true)
        #expect(lines.last?.hasSuffix("line \(LogFile.maxLines + 99)") == true)
    }

    @Test func redactsMnemonicShapedText() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        #expect(LogFile.redact("seed: \(phrase)!") == "[redacted]")
        #expect(LogFile.redact("wallet loaded in 3 seconds after sync") == "wallet loaded in 3 seconds after sync")
    }
}
