import Foundation
import Observation

@MainActor @Observable
final class LogSnapshot {
    private(set) var lines: [String] = []
    private(set) var loading = true
    private(set) var error: String?
    private(set) var updatedAt: Date?

    func load(read: () async throws -> [String], now: () -> Date = Date.init) async {
        loading = true; error = nil
        defer { loading = false }
        do { lines = try await read(); updatedAt = now() }
        catch is CancellationError { /* Superseded by a newer read. */ } catch { self.error = "Failed to fetch logs." }
    }
}
