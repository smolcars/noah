import BarkBoltFFI
import Foundation
import Sentry
import Testing

@testable import Noah

struct BarkLogTests {
    @Test func customEndpointPathsAreRemovedAtSDKAppAndTelemetryBoundaries() {
        let endpoint = "https://esplora-privacy.example/api/syntheticOpaqueToken"
        EndpointLogPrivacy.register(endpoint)
        let message = "Using Esplora at \(endpoint)/block-height/0"
        #expect(EndpointLogPrivacy.redact(message) == "Using Esplora at [custom-esplora]")
        #expect(LogFile.redact(message) == "Using Esplora at [custom-esplora]")
        #expect(LogFile.redactForTelemetry(message) == "Using Esplora at [custom-esplora]")
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        BarkLog(file: file).log(level: .info, target: "wallet", message: message)
        #expect(file.lines().last?.hasSuffix("Using Esplora at [custom-esplora]") == true)
    }

    @Test func relativeAndEscapedProviderErrorsCannotLeakEndpointCredentials() async throws {
        let token = "fakeOpaqueCredential59df"
        EndpointLogPrivacy.register("https://relative-privacy.example/api/\(token)")
        let inputs = [
            "No route: /api/\(token)/fee-estimates",
            "fee estimation failed: HttpResponse(404): No route: /api/\(token)/fee-estimates\nCaused by: \(token)",
            #"No route: \/api\/fakeOpaqueCredential59df\/fee-estimates"#,
            #"No route: \u002fapi\u002ffakeOpaqueCredential59df\u002ffee-estimates"#,
            "No route: %2fapi%2ffakeOpaqueCredential59df%2ffee-estimates",
            "No route: /api/%66akeOpaqueCredential59df/fee-estimates",
            #"No route: /api/\u0066akeOpaqueCredential59df/fee-estimates"#,
            "No route: %252Fapi%252FfakeOpaqueCredential59df%252Ffee-estimates",
            #"No route: \\/api\\/fakeOpaqueCredential59df\\/fee-estimates"#,
        ]
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // Old files must be scrubbed when exported after an endpoint is registered.
        try inputs.joined(separator: "\n").write(to: directory.appending(path: "noah.log.1"), atomically: true, encoding: .utf8)
        let file = LogFile(directory: directory)
        for input in inputs {
            #expect(EndpointLogPrivacy.redact(input) != input)
            #expect(LogFile.redact(input) != input)
            #expect(LogFile.redactForTelemetry(input) != input)
            BarkLog(file: file).log(level: .warn, target: "bark::onchain", message: input)
            AppLog("wallet", file: file).e(input)
            let event = Event(level: .error)
            event.message = SentryMessage(formatted: input)
            let clean = try #require(AppTelemetry.scrub(event))
            #expect(clean.message?.formatted.contains(token) == false)
            event.releaseName = input
            #expect(AppTelemetry.scrub(event) == nil, "Sentry final guard")
        }
        #expect(!file.lines().joined().contains(token))
        #expect(!(await file.linesForExport()).joined().contains(token))
        #expect(EndpointLogPrivacy.redact("HTTP 404: fee-estimates failed") == "HTTP 404: fee-estimates failed")
    }

    @Test func sdkSuppressesProviderResponseBodiesButKeepsErrorTypeAndStatus() {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        BarkLog(file: file).log(level: .warn, target: "bark::onchain", message:
            #"fee estimation failed: HttpResponse { status: 404, message: "No route: /unknown/fakeUnregisteredCredential; \"nested\"" }; caused by: upstream unavailable"#)
        #expect(file.lines().first?.hasSuffix(
            "HttpResponse { status: 404 }") == true)
    }

    @Test func rustCallbacksReturnWhileTheDiskQueueIsBlocked() async {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let queue = DispatchQueue(label: "test.blocked-bark-writer")
        queue.suspend()
        let file = LogFile(directory: directory, capacity: 1, queue: queue)
        let sink = BarkLog(file: file)
        DispatchQueue.concurrentPerform(iterations: 20) { _ in
            sink.log(level: .info, target: "test", message: "callback")
        }
        queue.resume()
        let lines = await file.linesForExport()
        #expect(lines.count == 2)
        #expect(lines.last?.hasSuffix("[dropped 19 log records]") == true)
    }

    @Test func workerCallbacksAreSerializedAndRedacted() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        let sink = BarkLog(file: file)
        DispatchQueue.concurrentPerform(iterations: 100) { index in
            sink.log(level: .warn, target: "ark::payment", message: "worker \(index): preimage=\(String(repeating: "a1", count: 32))")
        }
        let lines = file.lines()
        #expect(lines.count == 100)
        #expect(lines.allSatisfy { $0.contains(" W [bark:ark::payment] worker") && $0.hasSuffix("[redacted]") })
    }

    @Test func barkMessagesCannotPersistSecrets() throws {
        EndpointLogPrivacy.register("https://review-number.example/835917264")
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        let sink = BarkLog(file: file)
        // Synthetic material only; never print inputs on a failed assertion.
        let examples = [
            "wallet: abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "signing private_key=synthetic-key-value",
            "payment signature: synthetic-base64-value==",
            "payment preimage=\(String(repeating: "ab", count: 32))",
            "signing signature=\(String(repeating: "c2", count: 64))",
            "backup https://bucket.invalid/archive?X-Amz-Credential=fake&X-Amz-Signature=fake",
            "secret_key: [1, 2,\n3, 4]",
            "Mnemonic { words: [synthetic, test, words] }",
            "key \("xprv" + String(repeating: "A", count: 100))",
            "key \("K" + String(repeating: "A", count: 51))",
        ]
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        let date = try #require(formatter.date(from: "2026-10-21 08:21:42.292"))
        for message in examples { sink.log(level: .info, target: "wallet", message: message, date: date) }
        let text = file.lines().joined(separator: "\n")
        #expect(text.replacingOccurrences(of: "[custom-esplora]", with: "[redacted]").components(separatedBy: "[redacted]").count == examples.count + 1)
        #expect(!text.contains("synthetic"))
        #expect(!text.contains("X-Amz"))
        #expect(!text.contains("3, 4"))
    }

    /// Rotation itself is AppLogTests.rotatesIntoAtMostFourOldFiles.
    @Test func oversizedEntriesAreOmitted() {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        let sink = BarkLog(file: file)
        sink.log(level: .debug, target: "wallet", message: String(repeating: "x", count: LogFile.maxFileSizeBytes * 2))
        #expect(file.lines().first?.hasSuffix("[oversized log message omitted]") == true)
    }
}
