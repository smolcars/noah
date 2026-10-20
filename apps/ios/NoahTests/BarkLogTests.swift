import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

struct BarkLogTests {
    @Test func customEndpointPathsAreRemovedAtSDKAppAndTelemetryBoundaries() {
        let endpoint = "https://esplora-privacy.example/api/syntheticOpaqueToken"
        EndpointLogPrivacy.register(endpoint)
        let message = "Using Esplora at \(endpoint)/block-height/0"
        #expect(EndpointLogPrivacy.redact(message) == "Using Esplora at https://esplora-privacy.example")
        #expect(LogFile.redact(message) == "Using Esplora at https://esplora-privacy.example")
        #expect(LogFile.redactForTelemetry(message) == "Using Esplora at https://esplora-privacy.example")
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        BarkLog(file: file).log(level: .info, target: "wallet", message: message)
        #expect(file.lines().last?.hasSuffix("Using Esplora at https://esplora-privacy.example") == true)
    }

    @Test func mapsEveryLevelAndMatchesRNVerbosity() {
        #expect(BarkLog.maxLevel == .debug)
        #expect(LogLevel.allCases.map(BarkLog.appLevel) == [.error, .warn, .info, .debug, .verbose])
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
        for message in examples { sink.log(level: .info, target: "wallet", message: message) }
        let text = file.lines().joined(separator: "\n")
        #expect(text.components(separatedBy: "[redacted]").count == examples.count + 1)
        #expect(!text.contains("synthetic"))
        #expect(!text.contains("X-Amz"))
        #expect(!text.contains("3, 4"))
    }

    @Test func oversizedEntriesAndRotationStayBounded() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        let sink = BarkLog(file: file)
        sink.log(level: .debug, target: "wallet", message: String(repeating: "x", count: LogFile.maxFileSizeBytes * 2))
        #expect(file.lines().first?.hasSuffix("[oversized log message omitted]") == true)
        for _ in 0..<200 {
            sink.log(level: .debug, target: "wallet", message: String(repeating: "z", count: 16_000))
        }
        file.flush()
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
        #expect(files.count == 5)
        for url in files {
            #expect(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize! <= LogFile.maxFileSizeBytes)
        }
    }
}
