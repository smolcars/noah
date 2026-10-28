import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// Holds one step of the logging pipeline until the test releases it. A caller that waited for that step could never
/// return, so a watchdog releases it after ten seconds and the test fails rather than hangs.
final class LogStall: @unchecked Sendable {
    private let lock = NSLock()
    private var released = false
    private let onRelease: @Sendable () -> Void

    init(onRelease: @escaping @Sendable () -> Void) {
        self.onRelease = onRelease
        DispatchQueue.global().asyncAfter(deadline: .now() + 10) { [self] in release() }
    }

    /// True for the call that released it: the test's own call is false only if the watchdog had to.
    @discardableResult func release() -> Bool {
        let first = lock.withLock { () -> Bool in
            defer { released = true }
            return !released
        }
        if first { onRelease() }
        return first
    }
}

struct AppLogTests {
    private let directory = FileManager.default.temporaryDirectory.appending(path: "logs-\(UUID().uuidString)")

    /// Redaction must stay linear: missing terminators, repeated keywords and registered endpoints must not restart a
    /// scan. One input per path that once rescanned.
    @Test func redactionScalesLinearly() {
        for index in 0..<1000 {
            EndpointLogPrivacy.register("https://scaling-privacy-\(index).example/api/fakeScale\(index)")
        }
        EndpointLogPrivacy.register("https://fake-r8-scaling.example/fakeOpaque")
        EndpointLogPrivacy.register("https://xn--strae-oqa.example/api")
        for (name, unit) in [("mixed", "wallet sync completed xyzzy "), ("escaped", #"\u0078\u002e"#), ("entities", "&#x78;&#46;"),
                             ("registered authorities", "https://scaling-privacy-999.example/other "),
                             ("path history hit", "xxfakeScale999yy "),
                             ("failed seed", "seed."), ("failed URL", "http://"), ("failed push token", "ExpoPushToken["),
                             ("failed email", "x@x."), ("failed xprv", "xprv"), ("failed escape", "\\u0078!")] {
            assertRedactionScales(name, unit, redact: LogFile.redact)
        }
        for (name, unit) in [("boundary other authorities", "https://unrelated-scaling.example/other "),
                             ("combining mark hit", "xxfakeOpaque\u{0301}yy "), ("IDNA2008 hit", "xxstraßeyy ")] {
            assertRedactionScales(name, unit, redact: EndpointLogPrivacy.redact)
        }
        let shortHostMatcher = EndpointLogPrivacy.SecretMatcher(["a"])
        assertRedactionScales("fullwidth bare host", "ａ ") {
            EndpointLogPrivacy.sanitize($0, registered: ["https://a"], matcher: shortHostMatcher, hosts: ["a"]) ?? "[redacted]"
        }
    }

    /// One 8 KiB call against eight 1 KiB calls, in thread CPU time: linear work costs about the same, a rescan 8x.
    private func assertRedactionScales(_ name: String, _ unit: String, redact: (String) -> String = LogFile.redact) {
        LogFile.prepareRedaction()
        let small = String(String(repeating: unit, count: (1024 + unit.count - 1) / unit.count).prefix(1024))
        let large = String(String(repeating: unit, count: (8192 + unit.count - 1) / unit.count).prefix(8192))
        _ = redact(small); _ = redact(large) // Warm both sizes before sampling.
        func cpu(_ body: () -> Void) -> UInt64 {
            let started = clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)
            body()
            return clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID) - started
        }
        let smalls = (0..<3).map { _ in cpu { for _ in 0..<8 { _ = redact(small) } } }.sorted()
        let larges = (0..<3).map { _ in cpu { _ = redact(large) } }.sorted()
        #expect(smalls[1] > 0 && larges[1] < smalls[1] * 4, "\(name) CPU scaling: 8 × 1 KiB \(smalls[1]) ns, 8 KiB \(larges[1]) ns")
    }

    /// Redaction runs on the writer, never on the caller: every main-actor and SDK call returns while redaction is
    /// held, and the records land, redacted, once it's released.
    @Test @MainActor func mainActorAndSDKCallbacksOnlyEnqueueRawText() async {
        let held = DispatchSemaphore(value: 0)
        let stall = LogStall { held.signal() }
        let file = LogFile(directory: directory, capacity: 100, redact: { held.wait(); held.signal(); return LogFile.redact($0) })
        let sink = BarkLog(file: file)
        for unit in ["x.", #"\u0078\u002e"#] {
            let input = String(String(repeating: unit, count: (16384 + unit.count - 1) / unit.count).prefix(16384))
            AppLog(input, file: file).e(input)
            sink.log(level: .error, target: input, message: input)
        }
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        AppLog(phrase, file: file).e(phrase)
        #expect(stall.release(), "a caller waited for redaction")
        let lines = await file.linesForExport()
        #expect(lines.count == 5)
        #expect(lines.last?.hasSuffix(" E [[redacted]] [redacted]") == true)
    }

    @Test func sdkErrorLoggingDefersInspectionButReportsStayRedacted() async {
        let input = String(repeating: "x.", count: 8192)
        let error = BarkError(message: input)
        let raw = SafeErrorDescription.forLogging(error)
        #expect(raw == input)
        #expect(SafeErrorDescription.of(BarkError(message: "token=fake-test-token")) == "[redacted]")
        let file = LogFile(directory: directory)
        AppLog("sdk", file: file).e(raw)
        await file.flushAsync()
        #expect(file.lines().last?.hasSuffix(LogFile.redact(input)) == true)
    }

    @Test func tokenScanPreservesBoundaryAndTrailingPunctuationBehavior() {
        EndpointLogPrivacy.register("https://token-scan.example/api")
        for (input, expected) in [
            ("foo@b.co.", "[redacted]."), ("foo@b.co-", "[redacted]-"),
            ("-key=fake", "-[redacted]"), ("foo.key=fake", "foo.[redacted]"),
            ("x-amz-foo.bar: ordinary", "x-amz-foo.bar: ordinary"),
            ("seed phrase: fake", "[redacted]"), ("private keyStore: fake", "[redacted]"),
            ("http://http://ordinary", "[custom-esplora]"),
            ("ExpoPushToken[unfinished", "[redacted]"),
        ] { #expect(LogFile.redact(input) == expected) }
    }

    private func redactBaseline(_ message: String) -> String {
        LogFile.redactForTelemetry(message, endpointPrivacy: { EndpointLogPrivacy.sanitize($0, registered: []) }) ?? "[redacted]"
    }

    @Test func sharedSecretRedactionVectors() throws {
        EndpointLogPrivacy.register("https://review-number.example/835917264")
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        let date = try #require(formatter.date(from: "2026-10-21 09:21:42.292"))
        for fixture in try Fixtures.load("log-redaction", input: String.self, expected: String.self)
            where !fixture.name.hasPrefix("address-") {
            #expect(redactBaseline(fixture.input) == fixture.expected, "\(fixture.name)")
            #expect(redactBaseline(fixture.expected) == fixture.expected, "idempotent \(fixture.name)")
            let file = LogFile(directory: directory, capacity: 5000)
            file.append(level: .info, tag: "fixture", message: fixture.input, date: date)
            #expect(file.lines().last?.hasSuffix(LogFile.redact(fixture.input).replacingOccurrences(of: "\n", with: "\n  ")) == true,
                    "persistence \(fixture.name)")
        }
    }

    @Test func sharedAddressRedactionVectors() throws {
        for fixture in try Fixtures.load("log-redaction", input: String.self, expected: String.self)
            where fixture.name.hasPrefix("address-") {
            #expect(redactBaseline(fixture.input) == fixture.expected, "\(fixture.name)")
            let file = LogFile(directory: directory, capacity: 5000)
            file.append(level: .info, tag: "fixture", message: fixture.input)
            #expect(file.lines().last?.hasSuffix(LogFile.redact(fixture.input)) == true, "persistence \(fixture.name)")
        }
    }

    @Test func boundedWriterDoesNotWaitForDiskAndReportsOverflowInOrder() async {
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
        await file.drainAsync(until: .now + .milliseconds(20))
        #expect(ContinuousClock.now - started < .seconds(1))
        queue.resume()
        let lines = await file.linesForExport()
        #expect(lines.count == 3)
        #expect(lines[0].hasSuffix("record 0"))
        #expect(lines[1].hasSuffix("record 1"))
        #expect(lines[2].hasSuffix("[dropped 3 log records]"))
    }

    @Test func writesReactNativeLineFormat() {
        let file = LogFile(directory: directory, capacity: 5000)
        let date = Date(timeIntervalSince1970: 0)
        file.append(level: .warn, tag: "sync", message: "  first\nsecond  ", date: date)
        file.flush()
        let lines = file.lines()
        #expect(lines.count == 2)
        #expect(lines[0].hasSuffix(" W [sync] first"))
        #expect(lines[1] == "  second")
    }

    /// Files start where a previous launch left them, so one write crosses the size limit.
    @Test func rotatesIntoAtMostFourOldFiles() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for index in 1...4 { try Data("old \(index)\n".utf8).write(to: directory.appending(path: "noah.log.\(index)")) }
        let full = String(repeating: "x", count: LogFile.maxFileSizeBytes - 20) + "\n"
        try Data(full.utf8).write(to: directory.appending(path: "noah.log"))
        let file = LogFile(directory: directory)
        file.append(level: .info, tag: "t", message: "newest")
        file.flush()
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
        #expect(names == ["noah.log", "noah.log.1", "noah.log.2", "noah.log.3", "noah.log.4"])
        func contents(_ name: String) throws -> String { try String(contentsOf: directory.appending(path: name), encoding: .utf8) }
        // The oldest file was dropped and each other moved one along.
        #expect(try contents("noah.log.4") == "old 3\n" && contents("noah.log.2") == "old 1\n")
        #expect(try contents("noah.log.1") == full)
        #expect(try contents("noah.log").hasSuffix(" I [t] newest\n"))
    }

    @Test func exportsAtMostTheNewestLines() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let half = (LogFile.maxLines + 100) / 2
        for (name, range) in [("noah.log.1", 0..<half), ("noah.log", half..<(LogFile.maxLines + 100))] {
            try Data(range.map { "line \($0)\n" }.joined().utf8).write(to: directory.appending(path: name))
        }
        let lines = LogFile(directory: directory).lines()
        #expect(lines.count == LogFile.maxLines)
        #expect(lines.first == "line 100")
        #expect(lines.last == "line \(LogFile.maxLines + 99)")
    }

    @Test func visuallyRecoverableMnemonicsStayInvalidSeedsAndCannotReachLogs() {
        let phrase = Array(repeating: "abandon", count: 11).joined(separator: " ") + " about"
        for mark in ["\u{200B}", "\u{200C}", "\u{200D}", "\u{2060}", "\u{00AD}", "\u{0301}", "а", "α"] {
            let replacement = ["а", "α"].contains(mark) ? mark : "a" + mark
            let transformed = phrase.replacingOccurrences(of: "a", with: replacement)
            #expect((try? StoredSeed.normalized(transformed)) == nil)
            #expect(LogFile.redact("restore failed: " + transformed) == "[redacted]")
        }
    }

    @Test func normalizedWalletPhraseCannotReachLogs() throws {
        let phrase = Array(repeating: "abandon", count: 11).joined(separator: " ") + " about"
        let fullwidth = try String(String.UnicodeScalarView(phrase.unicodeScalars.map { scalar in
            try scalar.value >= 97 && scalar.value <= 122 ? #require(UnicodeScalar(scalar.value + 0xFEE0)) : scalar
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
