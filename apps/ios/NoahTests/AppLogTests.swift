import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

struct AppLogTests {
    private let directory = FileManager.default.temporaryDirectory.appending(path: "logs-\(UUID().uuidString)")

    private static let performanceInputs = [
        ("punctuation", "!@#$%^&*()[]{}:;,?"), ("hyphen", "x-"), ("dot", "x."),
        ("digit", "1234567890"), ("mixed", "wallet sync completed xyzzy "),
        ("escaped", #"\u0078\u002e"#), ("entities", "&#x78;&#46;"),
        ("provider body", #"HttpResponse { status: 404, message: "fake" }"#),
    ]

    @Test func redactionPerformance() {
        EndpointLogPrivacy.register("https://scaling-privacy.example/api/fakeScaleToken")
        for (name, unit) in Self.performanceInputs {
            assertRedactionScales(name, unit, redact: LogFile.redact)
            assertRedactionScales("boundary " + name, unit, redact: EndpointLogPrivacy.redact)
        }
    }

    private func assertRedactionScales(_ name: String, _ unit: String, redact: (String) -> String = LogFile.redact) {
        LogFile.prepareRedaction()
        let inputs = [1024, 16384].map { size in
            String(String(repeating: unit, count: (size + unit.count - 1) / unit.count).prefix(size))
        }
        for input in inputs { _ = redact(input) }
        var samples = [[Duration](), [Duration]()]
        for _ in 0..<5 {
            for (index, input) in inputs.enumerated() {
                let started = ContinuousClock.now
                _ = redact(input)
                samples[index].append(ContinuousClock.now - started)
            }
        }
        let small = samples[0].sorted()[2], large = samples[1].sorted()[2]
        print("REDACTION \(name) 1 KiB \(small); 16 KiB \(large)")
        // Linear work grows 16x; quadratic suffix rescans grow 256x. Allow scheduler noise.
        #expect(large < small * 64 + .milliseconds(20), "\(name): \(small) -> \(large)")
        #expect(large < .seconds(2), "\(name) 16 KiB exceeds 2 s ceiling")
    }

    @Test @MainActor func mainActorAndSDKCallbacksOnlyEnqueueRawText() async {
        let queue = DispatchQueue(label: "test.deferred-redaction")
        queue.suspend()
        let file = LogFile(directory: directory, capacity: 100, queue: queue)
        // Warm admission; redaction and every sink must still be waiting on the suspended queue.
        AppLog("warmup", file: file).i("warmup")
        let sink = BarkLog(file: file)
        var slowest = Duration.zero
        for (_, unit) in Self.performanceInputs {
            let input = String(String(repeating: unit, count: (16384 + unit.count - 1) / unit.count).prefix(16384))
            let started = ContinuousClock.now
            AppLog(input, file: file).e(input)
            slowest = max(slowest, ContinuousClock.now - started)
            let callbackStarted = ContinuousClock.now
            sink.log(level: .error, target: input, message: input)
            slowest = max(slowest, ContinuousClock.now - callbackStarted)
        }
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        AppLog(phrase, file: file).e(phrase)
        #expect(slowest < .milliseconds(5))
        print("LOG ADMISSION main actor / SDK maximum \(slowest)")
        #expect(!FileManager.default.fileExists(atPath: directory.path))
        queue.resume()
        let lines = await file.linesForExport()
        #expect(lines.count == 18)
        #expect(lines.last?.hasSuffix(" E [[redacted]] [redacted]") == true)
    }

    @Test func sdkErrorLoggingDefersInspectionButReportsStayRedacted() async {
        let input = String(repeating: "x.", count: 8192)
        let error = BarkError(message: input)
        let started = ContinuousClock.now
        let raw = SafeErrorDescription.forLogging(error)
        #expect(ContinuousClock.now - started < .milliseconds(5))
        #expect(raw == input)
        #expect(SafeErrorDescription.of(BarkError(message: "token=fake-test-token")) == "[redacted]")
        let file = LogFile(directory: directory)
        AppLog("sdk", file: file).e(raw)
        await file.flushAsync()
        #expect(file.lines().last?.hasSuffix(input) == true)
    }

    @Test func tokenScanPreservesBoundaryAndTrailingPunctuationBehavior() {
        for (input, expected) in [
            ("foo@b.co.", "[redacted]."), ("foo@b.co-", "[redacted]-"),
            ("-key=fake", "-[redacted]"), ("foo.key=fake", "foo.[redacted]"),
            ("x-amz-foo.bar: ordinary", "x-amz-foo.bar: ordinary"),
            ("seed phrase: fake", "[redacted]"), ("private keyStore: fake", "[redacted]"),
            ("http://http://ordinary", "http://http://ordinary"),
            ("ExpoPushToken[unfinished", "[redacted]"),
        ] { #expect(LogFile.redact(input) == expected) }
    }

    @Test func failedTokenCandidatesStayBounded() {
        // Missing terminators and repeated keywords must not restart a suffix search.
        for unit in ["seed.", "http://", "ExpoPushToken[", "x@x.", "xprv", "\\u0078!"] {
            assertRedactionScales("failed candidate \(unit)", unit)
        }
    }

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

    @Test func escapedCredentialsAreRedacted() throws {
        for fixture in try Fixtures.load("log-redaction", input: String.self, expected: String.self)
            where fixture.name.hasPrefix("r3-escaped-") {
            #expect(LogFile.redact(fixture.input) == fixture.expected, "\(fixture.name)")
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
        await file.drainAsync(until: .now + .milliseconds(20))
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

    @Test func visuallyRecoverableMnemonicsStayInvalidSeedsAndCannotReachLogs() throws {
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
