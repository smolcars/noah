import Foundation
import Testing

@testable import Noah

struct AppLogTests {
    private let directory = FileManager.default.temporaryDirectory.appending(path: "logs-\(UUID().uuidString)")

    @Test func sharedSecretRedactionVectors() throws {
        for fixture in try Fixtures.load("log-redaction", input: String.self, expected: String.self)
            where !fixture.name.hasPrefix("address-") {
            #expect(LogFile.redact(fixture.input) == fixture.expected, "\(fixture.name)")
            #expect(LogFile.redact(fixture.expected) == fixture.expected, "idempotent \(fixture.name)")
            let file = LogFile(directory: directory, capacity: 5000)
            file.append(level: .info, tag: "fixture", message: fixture.input)
            #expect(file.lines().last?.hasSuffix(fixture.expected.replacingOccurrences(of: "\n", with: "\n  ")) == true,
                    "persistence \(fixture.name)")
        }
    }

    @Test func sharedAddressRedactionVectors() throws {
        for fixture in try Fixtures.load("log-redaction", input: String.self, expected: String.self)
            where fixture.name.hasPrefix("address-") {
            #expect(LogFile.redact(fixture.input) == fixture.expected, "\(fixture.name)")
            let file = LogFile(directory: directory, capacity: 5000)
            file.append(level: .info, tag: "fixture", message: fixture.input)
            #expect(file.lines().last?.hasSuffix(fixture.expected) == true, "persistence \(fixture.name)")
        }
    }

    @Test func boundedWriterDoesNotWaitForDiskAndReportsOverflowInOrder() async throws {
        let queue = DispatchQueue(label: "test.blocked-log-writer")
        queue.suspend()
        let file = LogFile(directory: directory, capacity: 2, queue: queue)
        for index in 0..<5 { file.append(level: .info, tag: "test", message: "record \(index)") }
        // All appends returned even though the filesystem queue has not run.
        #expect(!FileManager.default.fileExists(atPath: directory.path))
        queue.resume()
        let lines = await file.linesForExport()
        #expect(lines.count == 3)
        #expect(lines[0].hasSuffix("record 0"))
        #expect(lines[1].hasSuffix("record 1"))
        #expect(lines[2].hasSuffix("[dropped 3 log records]"))
        file.append(level: .info, tag: "test", message: "recovered")
        await file.flushAsync()
        #expect(file.lines().last?.hasSuffix("recovered") == true)
    }

    @Test func lifecycleDrainIsBoundedAndKeepsWriterOrdering() async {
        let queue = DispatchQueue(label: "test.blocked-lifecycle-drain")
        queue.suspend()
        let file = LogFile(directory: directory, capacity: 2, queue: queue)
        for index in 0..<5 { file.append(level: .info, tag: "test", message: "record \(index)") }
        let started = ContinuousClock.now
        #expect(!file.drain(timeout: 0.02))
        #expect(ContinuousClock.now - started < .seconds(1))
        queue.resume()
        let lines = await file.linesForExport()
        #expect(lines.count == 3)
        #expect(lines[0].hasSuffix("record 0"))
        #expect(lines[1].hasSuffix("record 1"))
        #expect(lines[2].hasSuffix("[dropped 3 log records]"))
    }

    @Test func writesReactNativeLineFormat() throws {
        let file = LogFile(directory: directory, capacity: 5000)
        let date = Date(timeIntervalSince1970: 0)
        file.append(level: .warn, tag: "sync", message: "  first\nsecond  ", date: date)
        file.flush()
        let lines = file.lines()
        #expect(lines.count == 2)
        #expect(lines[0].hasSuffix(" W [sync] first"))
        #expect(lines[1] == "  second")
    }

    @Test func rotatesIntoAtMostFourOldFiles() throws {
        let file = LogFile(directory: directory, capacity: 5000)
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
        let file = LogFile(directory: directory, capacity: 5000)
        for index in 0..<(LogFile.maxLines + 100) {
            file.append(level: .info, tag: "t", message: "line \(index)")
        }
        file.flush()
        let lines = file.lines()
        #expect(lines.count == LogFile.maxLines)
        #expect(lines.first?.hasSuffix("line 100") == true)
        #expect(lines.last?.hasSuffix("line \(LogFile.maxLines + 99)") == true)
    }

    @Test func normalizedWalletPhraseCannotReachLogs() throws {
        let phrase = Array(repeating: "abandon", count: 11).joined(separator: " ") + " about"
        let fullwidth = String(String.UnicodeScalarView(phrase.unicodeScalars.map { scalar in
            scalar.value >= 97 && scalar.value <= 122 ? UnicodeScalar(scalar.value + 0xFEE0)! : scalar
        }))
        #expect(try StoredSeed.normalized(fullwidth) == phrase)
        #expect(LogFile.redact("failed to import: " + fullwidth) == "[redacted]")
    }

    @Test func redactsMnemonicShapedText() {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        #expect(LogFile.redact("seed: \(phrase)!") == "[redacted]")
        #expect(LogFile.redact("wallet loaded in 3 seconds after sync") == "wallet loaded in 3 seconds after sync")
    }
}
