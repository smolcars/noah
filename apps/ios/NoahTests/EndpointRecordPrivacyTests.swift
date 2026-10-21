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

    private struct HeaderCase: Decodable {
        let name: String; let endpoint: String; let input: String; let expected: String
    }

    @Test func sharedHeadersPreserveOnlyTheAnchoredFormatterPrefix() throws {
        for test in try Fixtures.decode("esplora-log-headers", as: [HeaderCase].self) {
            EndpointLogPrivacy.register(test.endpoint)
            #expect(EndpointLogPrivacy.redact(test.input) == test.expected, "\(test.name)")
            #expect(LogFile.redact(test.input) == test.expected, "\(test.name)")
            #expect(LogFile.redactForTelemetry(test.input) == test.expected, "\(test.name)")
            let event = Event(level: .error); event.message = SentryMessage(formatted: test.input)
            event.exceptions = [Exception(value: test.input, type: "FakeEndpointError")]
            let clean = try #require(AppTelemetry.scrub(event))
            #expect(clean.message?.formatted == test.expected)
            #expect(clean.exceptions?.first?.value == test.expected)
            let crumb = Breadcrumb(level: .info, category: "esplora"); crumb.message = test.input
            #expect(try #require(AppTelemetry.scrub(crumb)).message == test.expected)
        }
    }

    @Test func everyHourSurvivesWritingReadingAndHistoricalExport() async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "header-privacy-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        let tests = try Fixtures.decode("esplora-log-headers", as: [HeaderCase].self)
        for test in tests.prefix(24) {
            EndpointLogPrivacy.register(test.endpoint)
            file.append(level: .debug, tag: "bark:load", message: "2:0", date: try #require(formatter.date(from: String(test.input.prefix(23)))))
        }
        file.flush()
        let active = directory.appending(path: "noah.log")
        let raw = try String(contentsOf: active, encoding: .utf8).split(separator: "\n").map(String.init)
        let expected = tests.prefix(24).map(\.expected)
        #expect(raw == expected)
        #expect(file.lines() == expected)
        #expect(await file.linesForExport() == expected)
        #expect(try await file.checkedLinesForExport() == expected)
        try FileManager.default.removeItem(at: active)
        for test in tests {
            try test.input.write(to: directory.appending(path: "noah.log.1"), atomically: true, encoding: .utf8)
            #expect(file.lines() == [test.expected], "\(test.name)")
            #expect(await file.linesForExport() == [test.expected], "\(test.name)")
            #expect(try await file.checkedLinesForExport() == [test.expected], "\(test.name)")
        }
    }

    @Test func finalWriteGuardKeepsHeaderWhenTheBodyMatchesASecret() {
        EndpointLogPrivacy.register("https://review-number.example/835917264")
        let header = "2026-10-21 08:21:42.292 D "
        #expect(EndpointLogPrivacy.redactRecord(header + "[sdk] 835917264") == header + "[custom-esplora]")
        // A registered token equal to formatter-owned digits cannot erase the header.
        let matcher = EndpointLogPrivacy.SecretMatcher(["2026-10-21"])
        #expect(EndpointLogPrivacy.sanitize(header + "[sdk] ordinary", registered: ["https://review-number.example"], matcher: matcher) == header + "[sdk] ordinary")
    }

    @Test func callbackTextAndTagsCannotClaimARecordHeader() throws {
        EndpointLogPrivacy.register("https://review-number.example/835917264")
        let header = "2026-10-21 08:21:42.292 D "
        let nested = header + "[sdk] ordinary"
        let safe = "2026-10-21 [custom-esplora] D [sdk] ordinary"
        let directory = FileManager.default.temporaryDirectory.appending(path: "header-callback-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let system = SystemRecords()
        let file = LogFile(directory: directory, systemSink: { _, tag, message in system.append(tag, message) })
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        let date = try #require(formatter.date(from: String(header.prefix(23))))
        BarkLog(file: file).log(level: .debug, target: "sdk", message: nested, date: date)
        file.append(level: .debug, tag: nested, message: "ordinary", date: date, systemLog: true)
        file.flush()
        let expected = ["[bark:sdk] \(safe)", "[\(safe)] ordinary"]
        #expect(system.text == expected.joined(separator: "\n"))
        let raw = try String(contentsOf: directory.appending(path: "noah.log"), encoding: .utf8).split(separator: "\n").map(String.init)
        #expect(raw == expected.map { header + $0 })
        #expect(file.lines() == expected.map { header + $0 })
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
