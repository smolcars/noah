import BarkBoltFFI
import Foundation
import Sentry
import Testing

@testable import Noah

struct EndpointRecordPrivacyTests {
    private struct Case: Decodable {
        let name: String; let kind: String; let endpoint: String
        let tag: String?; let message: String?; let secret: String?; let type: String?; let count: Int?
    }

    private final class SystemRecords: @unchecked Sendable {
        private let lock = NSLock()
        private var records: [String] = []
        func append(_ tag: String, _ message: String) { lock.withLock { records.append("[\(tag)] \(message)") } }
        var text: String { lock.withLock { records.joined(separator: "\n") } }
    }

    @Test func formattingCannotRebuildTokensAtFileSystemAndTelemetrySinks() throws {
        for test in try Fixtures.decode("esplora-log-records", as: [Case].self) where ["record", "bark"].contains(test.kind) {
            EndpointLogPrivacy.register(test.endpoint)
            let directory = FileManager.default.temporaryDirectory.appending(path: "record-privacy-\(UUID())")
            defer { try? FileManager.default.removeItem(at: directory) }
            let system = SystemRecords()
            let file = LogFile(directory: directory, systemSink: { _, tag, message in system.append(tag, message) })
            let tag = try #require(test.tag), message = try #require(test.message), secret = try #require(test.secret)
            if test.kind == "bark" { BarkLog(file: file).log(level: .error, target: tag, message: message) }
            else { AppLog(tag, file: file).e(message) }
            file.flush()
            let raw = try String(contentsOf: directory.appending(path: "noah.log"), encoding: .utf8)
            #expect(!raw.contains(secret), "\(test.name)")
            #expect(!system.text.contains(secret), "\(test.name)")
            #expect(!file.lines().joined(separator: "\n").contains(secret))
            let formatted = "[\(tag)] \(message)"
            let event = Event(level: .error); event.message = SentryMessage(formatted: formatted)
            #expect(try #require(AppTelemetry.scrub(event)).message?.formatted.contains(secret) == false)
            let crumb = Breadcrumb(level: .info, category: "esplora"); crumb.message = formatted
            #expect(try #require(AppTelemetry.scrub(crumb)).message?.contains(secret) == false)
        }
    }

    @Test func generatedOverflowRecordsPassTheFinalBoundary() throws {
        for test in try Fixtures.decode("esplora-log-records", as: [Case].self) where test.kind == "overflow" {
            EndpointLogPrivacy.register(test.endpoint)
            let directory = FileManager.default.temporaryDirectory.appending(path: "overflow-privacy-\(UUID())")
            defer { try? FileManager.default.removeItem(at: directory) }
            let queue = DispatchQueue(label: "test.overflow-privacy")
            queue.suspend()
            let file = LogFile(directory: directory, capacity: 1, queue: queue)
            for _ in 0..<(try #require(test.count) + 1) { file.append(level: .info, tag: "test", message: "ordinary record") }
            queue.resume(); file.flush()
            let raw = try String(contentsOf: directory.appending(path: "noah.log"), encoding: .utf8)
            #expect(!raw.contains(try #require(test.secret)))
            #expect(raw.contains("[custom-esplora]"))
        }
    }

    @Test func fallbackTypeNamesUseThePrivacyBoundary() throws {
        struct FakeOrdinaryError: Error {}
        let error = NSError(domain: "fake", code: 1)
        #expect(SafeErrorDescription.of(FakeOrdinaryError()) == "FakeOrdinaryError")
        for test in try Fixtures.decode("esplora-log-records", as: [Case].self) where test.kind == "fallback" && test.type == "NSError" {
            EndpointLogPrivacy.register(test.endpoint)
            #expect(SafeErrorDescription.of(error) == "[custom-esplora]")
            #expect(SafeErrorDescription.forLogging(error) == "[custom-esplora]")
            #expect(SafeErrorDescription.of(FakeOrdinaryError()) == "FakeOrdinaryError")
        }
    }
}
