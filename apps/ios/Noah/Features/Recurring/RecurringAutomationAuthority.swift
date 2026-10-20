import Foundation

/// Wallet-local authority; MMKV recovery cannot undo a stop or suspension.
final class RecurringAutomationAuthority: @unchecked Sendable {
    static let message = "Wallet automation is durably stopped. Resume the wallet explicitly before sending recurring payments."
    private struct Marker: Codable { var version = 1; var instance: UUID }
    private struct State: Codable {
        var version = 1
        var instance: UUID
        var generation = UUID()
        var suspended = false
        var stopped = false
    }
    private final class Gate: @unchecked Sendable {
        let lock = NSRecursiveLock()
        var failed: Set<URL> = []
    }
    private static let gate = Gate()
    static func deleted(directory: URL) {
        gate.lock.withLock { _ = gate.failed.remove(directory.appending(path: ".noah-recurring-automation.json")) }
    }
    let file: URL
    var marker: URL { file.appendingPathExtension("initialized") }
    private let sync: @Sendable (URL) throws -> Void
    init(directory: URL, sync: @escaping @Sendable (URL) throws -> Void = RestoreTransaction.fullSync) {
        file = directory.appending(path: ".noah-recurring-automation.json"); self.sync = sync
    }
    private func write<T: Encodable>(_ value: T, to file: URL) throws {
        try RecurringStore.durableWrite(JSONEncoder().encode(value), to: file, sync: sync)
    }
    private func state() throws -> State {
        guard !Self.gate.failed.contains(file) else { throw RecurringError.message(Self.message) }
        do {
            if !FileManager.default.fileExists(atPath: file.path), !FileManager.default.fileExists(atPath: marker.path) {
                let instance = UUID()
                try write(State(instance: instance), to: file)
                try write(Marker(instance: instance), to: marker)
            }
            let marker = try JSONDecoder().decode(Marker.self, from: Data(contentsOf: marker))
            let state = try JSONDecoder().decode(State.self, from: Data(contentsOf: file))
            guard marker.version == 1, state.version == 1, marker.instance == state.instance else { throw RecurringError.message(Self.message) }
            return state
        } catch { Self.gate.failed.insert(file); throw RecurringError.message(Self.message) }
    }
    func generation() throws -> UUID {
        try Self.gate.lock.withLock {
            let state = try state()
            guard !state.stopped, !state.suspended else { throw RecurringError.message(Self.message) }
            return state.generation
        }
    }
    func allowed() -> Bool { (try? generation()) != nil }
    private func change(_ mutate: (inout State) -> Void) throws {
        try Self.gate.lock.withLock {
            do {
                var value = try state(); value.generation = UUID(); mutate(&value)
                try write(value, to: file)
            } catch { Self.gate.failed.insert(file); throw error }
        }
    }
    func stop() throws { try change { $0.stopped = true } }
    func suspension(_ suspended: Bool) throws {
        try change { $0.suspended = suspended; if suspended { $0.stopped = true } }
    }
    func resume() throws { try change { $0.suspended = false; $0.stopped = false } }
}
