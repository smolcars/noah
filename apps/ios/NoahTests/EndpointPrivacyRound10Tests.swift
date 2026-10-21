import Foundation
import Sentry
import Testing

@testable import Noah

struct EndpointPrivacyRound10Tests {
    @Test func reorderedAccentsCannotExposeEndpointTokens() throws {
        struct Case: Decodable { let endpoint: String; let input: String; let expected: String }
        let cases = try Fixtures.decode("esplora-log-privacy-r10", as: [Case].self)
        let directory = FileManager.default.temporaryDirectory.appending(path: "r10-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        #expect(EndpointLogPrivacy.SecretMatcher(["fakeR9Secreté"]).contains(cases[0].input))
        for test in cases {
            EndpointLogPrivacy.register(test.endpoint)
            #expect(EndpointLogPrivacy.sanitize(test.input, registered: []) == test.input)
            #expect(LogFile.redact(test.input) == test.expected)
            #expect(EndpointLogPrivacy.redactRecord(test.input) == test.expected)
            let event = Event(level: .error); event.message = SentryMessage(formatted: test.input)
            event.exceptions = [Exception(value: test.input, type: "FakeEndpointError")]
            let clean = try #require(AppTelemetry.scrub(event))
            #expect(clean.message?.formatted == test.expected)
            #expect(clean.exceptions?.first?.value == test.expected)
            let crumb = Breadcrumb(level: .info, category: "esplora"); crumb.message = test.input
            #expect(try #require(AppTelemetry.scrub(crumb)).message == test.expected)
            AppLog("r10", file: file).e(test.input)
            #expect(file.lines().last?.hasSuffix(test.expected) == true)
            try test.input.write(to: directory.appending(path: "noah.log.1"), atomically: true, encoding: .utf8)
            #expect(file.lines().first?.hasSuffix(test.expected) == true)
        }
    }

    private func expandedRun(_ count: Int) -> String {
        "x" + String(repeating: String(repeating: "\u{0301}\u{0327}", count: 16) + "\u{FF9E}", count: count)
    }

    @Test func compatibilityExpansionCannotResetCombiningRunBound() {
        // U+FF9E is a letter in the input, but decomposes to a combining mark.
        #expect(!EndpointLogPrivacy.hasExcessiveCombiningRun("x" + String(repeating: "\u{FF9E}", count: 32)))
        #expect(EndpointLogPrivacy.hasExcessiveCombiningRun("x" + String(repeating: "\u{FF9E}", count: 33)))
        for input in [expandedRun(1), expandedRun(16), expandedRun(256)] {
            #expect(EndpointLogPrivacy.hasExcessiveCombiningRun(input))
            #expect(EndpointLogPrivacy.SecretMatcher(["fakeSpeedSecret"]).contains(input))
            #expect(LogFile.decodedViews(input, percentEncoded: true, compatibility: true) == nil)
            #expect(EndpointLogPrivacy.sanitize(input, registered: []) == input)
        }
        let escaped = expandedRun(2).replacingOccurrences(of: "\u{FF9E}", with: #"\uFF9E"#)
        #expect(LogFile.decodedViews(escaped, percentEncoded: true, compatibility: true) == nil)
    }

    @Test func compatibilityExpansionScaling() {
        EndpointLogPrivacy.register("https://fake-r10-speed.example/fakeSpeedSecret")
        for boundary in ["endpoint", "file", "record"] {
            var medians: [Duration] = []
            for count in [16, 256] {
                let input = expandedRun(count)
                let redact: (String) -> String = { text in
                    switch boundary {
                    case "endpoint": return EndpointLogPrivacy.redact(text)
                    case "file": return LogFile.redact(text)
                    default: return EndpointLogPrivacy.redactRecord(text)
                    }
                }
                #expect(redact(input) == (boundary == "file" && count == 256 ? "[oversized log message omitted]" : "[custom-esplora]"))
                var samples: [Duration] = []
                for _ in 0..<5 {
                    let start = ContinuousClock.now; _ = redact(input); samples.append(ContinuousClock.now - start)
                }
                medians.append(samples.sorted()[2])
            }
            print("REDACTION compatibility expansion \(boundary) 16 -> 256: \(medians)")
            #expect(medians[1] < medians[0] * 64 + .milliseconds(20))
            #expect(medians[1] < .seconds(2))
        }
    }
}
