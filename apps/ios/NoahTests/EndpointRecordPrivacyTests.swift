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
        let name: String; let endpoint: String; let input: String; let expected: String; let generalExpected: String; let recordExpected: String?; let tokens: [String]
    }

    @Test func sharedHeaderVectorsUseIsolatedSnapshots() throws {
        for test in try Fixtures.decode("esplora-log-headers", as: [HeaderCase].self) {
            let normalized = try EsploraEndpoint.normalize(test.endpoint)
            let url = try #require(URLComponents(string: normalized))
            let host = try #require(url.host)
            let registered: Set<String> = ["https://" + host]
            let hosts: Set<String> = [host]
            let hostTokens: Set<String> = [host, host + ":" + String(url.port ?? 443)]
            let matcher = EndpointLogPrivacy.SecretMatcher(hostTokens.union(test.tokens))
            #expect(EndpointLogPrivacy.redactLogLines(test.input.components(separatedBy: "\n"), registered: registered, matcher: matcher, hosts: hosts) == test.expected.components(separatedBy: "\n"), "\(test.name)")
            #expect((EndpointLogPrivacy.sanitize(test.input, registered: registered, matcher: matcher, hosts: hosts) ?? "[redacted]") == test.generalExpected, "\(test.name)")
            if test.recordExpected != nil {
                // The date/boundary token itself must cause the whole-record replacement.
                #expect(EndpointLogPrivacy.redactLogLines(test.input.components(separatedBy: "\n"), registered: registered, matcher: EndpointLogPrivacy.SecretMatcher(hostTokens), hosts: hosts) != ["[custom-esplora]"], "\(test.name)")
            }
        }
    }

    @Test func numericHeaderAliasesUseCanonicalHostsWithoutFailingClosed() throws {
        for endpoints in [["https://12:21"], ["https://12:21", "https://8:443", "https://0x9:80"]] {
            let normalized = try endpoints.map { try EsploraEndpoint.normalize($0) }
            let urls = try normalized.map { try #require(URLComponents(string: $0)) }
            let hosts = Set(try urls.map { try #require($0.host) })
            #expect(hosts == (urls.count == 1 ? ["0.0.0.12"] : ["0.0.0.12", "0.0.0.8", "0.0.0.9"]))
            let registered = Set(normalized)
            let ports = try urls.map { try #require($0.host) + ":" + String($0.port ?? 443) }
            let matcher = EndpointLogPrivacy.SecretMatcher(hosts.union(ports))
            for hour in 0...23 {
                let time = String(format: "%02d:21:42.292", hour)
                for body in ["[sdk] ordinary", "[sdk] email=person@example.com"] {
                    let input = "2037-11-29 \(time) D \(body)"
                    let plain = LogFile.redactPlain(input)
                    if body.contains("email=") { #expect(plain != input) }
                    let expected = hour == 12 ? plain.replacingOccurrences(of: time, with: "[custom-esplora]") : plain
                    #expect(EndpointLogPrivacy.redactLogLines(input.components(separatedBy: "\n"), registered: registered, matcher: matcher, hosts: hosts) == [expected], "\(endpoints): \(input)")
                }
            }
        }
    }

    @Test func sharedHeadersPreserveOnlyTheAnchoredFormatterPrefix() throws {
        for test in try Fixtures.decode("esplora-log-headers", as: [HeaderCase].self) {
            EndpointLogPrivacy.register(test.endpoint)
            let expected = test.generalExpected
            let telemetry = expected == "[redacted]" ? nil : expected
            #expect(EndpointLogPrivacy.redact(test.input) == expected, "\(test.name)")
            #expect(LogFile.redact(test.input) == expected, "\(test.name)")
            #expect(LogFile.redactForTelemetry(test.input) == telemetry, "\(test.name)")
            #expect(SafeErrorDescription.of(BarkError(message: test.input)) == expected, "\(test.name)")
            #expect(EndpointLogPrivacy.redactLogLines(test.input.components(separatedBy: "\n")) == test.expected.components(separatedBy: "\n"), "\(test.name)")
            if let recordExpected = test.recordExpected { #expect(EndpointLogPrivacy.redactRecord(test.input) == recordExpected, "\(test.name)") }
            let event = Event(level: .error); event.message = SentryMessage(formatted: test.input)
            event.exceptions = [Exception(value: test.input, type: "FakeEndpointError")]
            let clean = try #require(AppTelemetry.scrub(event))
            #expect(clean.message?.formatted == telemetry)
            #expect(clean.exceptions?.first?.value == telemetry)
            let crumb = Breadcrumb(level: .info, category: "esplora"); crumb.message = test.input
            #expect(AppTelemetry.scrub(crumb)?.message == telemetry)
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
            EndpointLogPrivacy.register(test.endpoint)
            try test.input.write(to: directory.appending(path: "noah.log.1"), atomically: true, encoding: .utf8)
            #expect(file.lines() == test.expected.components(separatedBy: "\n"), "\(test.name)")
            #expect(await file.linesForExport() == test.expected.components(separatedBy: "\n"), "\(test.name)")
            #expect(try await file.checkedLinesForExport() == test.expected.components(separatedBy: "\n"), "\(test.name)")
        }
    }

    @Test func finalWriteGuardInspectsTheWholeRecord() {
        EndpointLogPrivacy.register("https://review-number.example/835917264")
        let header = "2026-10-21 08:21:42.292 D "
        #expect(EndpointLogPrivacy.redactRecord(header + "[sdk] 835917264") == "[custom-esplora]")
        // The complete formatter prefix remains subject to the token guard.
        let matcher = EndpointLogPrivacy.SecretMatcher(["2026-10-21"])
        #expect(EndpointLogPrivacy.sanitize(header + "[sdk] ordinary", registered: ["https://review-number.example"], matcher: matcher) == "[custom-esplora]")
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
