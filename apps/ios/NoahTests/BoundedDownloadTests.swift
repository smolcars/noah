import Foundation
@testable import Noah
import Testing

struct BoundedDownloadTests {
    private let root = FileManager.default.temporaryDirectory.appending(path: "download-\(UUID().uuidString)")

    private func source(bytes: Int) throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let url = root.appending(path: "source.bin")
        try Data(count: bytes).write(to: url)
        return url
    }

    @Test func writesTheWholeBodyWithinTheLimit() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let destination = root.appending(path: "out.bin")
        try await BoundedDownload.fetch(URLRequest(url: try source(bytes: 300_000)), to: destination, limit: 300_000)
        #expect(try Data(contentsOf: destination).count == 300_000)
    }

    @Test func stopsOnceTheLimitIsPassed() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let destination = root.appending(path: "out.bin")
        await #expect(throws: BoundedDownload.TooLarge.self) {
            try await BoundedDownload.fetch(URLRequest(url: try source(bytes: 300_000)), to: destination, limit: 1_000)
        }
        let written = try FileManager.default.attributesOfItem(atPath: destination.path)[.size] as? Int ?? 0
        #expect(written <= 1_000)
    }

    /// The task can complete (cancelled) before `start` runs; that must end
    /// the download, not leave it waiting forever.
    @Test(.timeLimit(.minutes(1))) func cancellationBeforeStartDoesNotHang() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let request = URLRequest(url: try source(bytes: 10_000))
        let destination = root.appending(path: "out.bin")
        for _ in 0..<20 {
            let download = Task {
                withUnsafeCurrentTask { $0?.cancel() }
                try await BoundedDownload.fetch(request, to: destination, limit: 10_000)
            }
            _ = await download.result
        }
    }
}
