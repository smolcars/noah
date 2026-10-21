import Foundation
import Sentry
import Testing

@testable import Noah

struct EndpointPrivacyRound9Tests {
    private func cases(_ kind: String) throws -> [[String: Any]] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appending(path: "fixtures/esplora-log-privacy-r9.json")
        return try (JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [[String: Any]])
            .filter { $0["kind"] as? String == kind }
    }

    @Test func serializedNumbersCannotBypassTheFinalTelemetryGuard() throws {
        for test in try cases("telemetry") + cases("breadcrumb") {
            EndpointLogPrivacy.register(try #require(test["endpoint"] as? String))
            let payload = try #require(test["payload"] as? [String: Any])
            #expect(!AppTelemetry.isSafePayload(payload))
            if let contexts = payload["contexts"] as? [String: [String: Any]] {
                let event = Event(level: .error); event.context = contexts
                #expect(AppTelemetry.scrub(event) == nil)
            } else if test["kind"] as? String == "telemetry" {
                let event = Event(level: .error)
                let frame = Frame(); frame.lineNumber = 835917264
                let error = Exception(value: nil, type: "FakeFailure")
                error.stacktrace = SentryStacktrace(frames: [frame], registers: [:]); event.exceptions = [error]
                #expect(AppTelemetry.scrub(event) == nil)
            }
        }
        let crumb = Breadcrumb(level: .info, category: "ordinary"); crumb.message = "ordinary diagnostic"
        #expect(AppTelemetry.scrub(crumb)?.message == crumb.message)
    }

    @Test func longCombiningRunsFailClosedBeforeNormalization() throws {
        for mark in ["\u{0301}", "\u{0903}", "\u{20DD}", "\u{1D165}"] {
            #expect(!EndpointLogPrivacy.hasExcessiveCombiningRun("x" + String(repeating: mark, count: 32)))
            #expect(EndpointLogPrivacy.hasExcessiveCombiningRun("x" + String(repeating: mark, count: 33)))
            #expect(!EndpointLogPrivacy.hasExcessiveCombiningRun(String(repeating: "x" + String(repeating: mark, count: 32), count: 2)))
        }
        #expect(LogFile.decodedViews("x" + String(repeating: #"\u0301\u0327"#, count: 33), percentEncoded: true, compatibility: true) == nil)
        for test in try cases("combining") {
            EndpointLogPrivacy.register(try #require(test["endpoint"] as? String))
            let input = "x" + String(repeating: try #require(test["unit"] as? String), count: try #require(test["count"] as? Int))
            #expect(EndpointLogPrivacy.SecretMatcher(["fakeSpeedSecret"]).contains(input))
            #expect(LogFile.decodedViews(input, percentEncoded: true, compatibility: true) == nil)
            #expect(EndpointLogPrivacy.sanitize(input, registered: []) == input)
            #expect(EndpointLogPrivacy.redact(input) == "[custom-esplora]")
        }
    }

    @Test func combiningRunScaling() throws {
        let vectors = try cases("combining").filter { ($0["count"] as? Int ?? 0) >= 512 }
        for boundary in ["endpoint", "file", "record"] {
            var medians: [Duration] = []
            for test in vectors {
                EndpointLogPrivacy.register(test["endpoint"] as? String)
                let input = "x" + String(repeating: try #require(test["unit"] as? String), count: try #require(test["count"] as? Int))
                let redact: (String) -> String = { text in
                    switch boundary {
                    case "endpoint": return EndpointLogPrivacy.redact(text)
                    case "file": return LogFile.redact(text)
                    default: return EndpointLogPrivacy.redactRecord(text)
                    }
                }
                _ = redact(input)
                var samples: [Duration] = []
                for _ in 0..<5 {
                    let start = ContinuousClock.now; _ = redact(input); samples.append(ContinuousClock.now - start)
                }
                medians.append(samples.sorted()[2])
            }
            print("REDACTION long combining run \(boundary) 512 -> 8192: \(medians)")
            #expect(medians.count == 2)
            #expect(medians[1] < medians[0] * 64 + .milliseconds(20))
            #expect(medians[1] < .seconds(2))
        }
    }
}
