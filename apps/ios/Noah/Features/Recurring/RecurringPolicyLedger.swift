import CryptoKit
import Foundation

/// Write-ahead policy authority. A recovered MMKV snapshot can never repair this evidence.
final class RecurringPolicyLedger: @unchecked Sendable {
    static let message = "Recurring policy evidence needs attention. Saved schedules are preserved; cancel them and approve new payments. Sending remains stopped."
    static let logMessage = "Recurring policy rollback or unavailable evidence detected; sending stopped"
    struct Marker: Codable { let version: Int; let binding: RecurringStore.Binding }
    private struct Entry: Codable {
        var binding: RecurringStore.Binding
        var scheduleID: String
        var generation: UInt64
        var revision: UUID?
        var status: String
        var digest: String?
    }
    private struct State: Codable {
        var version: Int
        var binding: RecurringStore.Binding
        var generation: UInt64
        var entries: [String: Entry]
    }
    let file: URL
    var marker: URL { file.appendingPathExtension("initialized") }
    private var failed = false
    private let sync: @Sendable (URL) throws -> Void
    init(file: URL, sync: @escaping @Sendable (URL) throws -> Void = RestoreTransaction.fullSync) { self.file = file; self.sync = sync }
    private func key(_ binding: RecurringStore.Binding, _ id: String) throws -> String {
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        return SHA256.hash(data: try encoder.encode(binding)).map { String(format: "%02x", $0) }.joined() + ":" + id
    }
    private func digest(_ schedule: RecurringPayment) throws -> String {
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        return SHA256.hash(data: try encoder.encode(schedule)).map { String(format: "%02x", $0) }.joined()
    }
    private func entry(_ binding: RecurringStore.Binding, _ schedule: RecurringPayment, generation: UInt64) throws -> Entry {
        .init(binding: binding, scheduleID: schedule.id, generation: generation, revision: schedule.nativeRevision, status: schedule.status, digest: try digest(schedule))
    }
    private func matches(_ entry: Entry?, _ binding: RecurringStore.Binding, _ schedule: RecurringPayment) throws -> Bool {
        guard let entry else { return false }
        let currentDigest = try digest(schedule)
        return entry.binding == binding && entry.scheduleID == schedule.id && entry.revision == schedule.nativeRevision && entry.status == schedule.status && entry.digest == currentDigest
    }
    private func write(_ state: State) throws { try RecurringStore.durableWrite(JSONEncoder().encode(state), to: file, sync: sync) }
    private func state(binding: RecurringStore.Binding, schedules: [String: RecurringPayment]) throws -> State {
        guard !failed else { throw RecurringError.message(Self.message) }
        do {
            if !FileManager.default.fileExists(atPath: marker.path), !FileManager.default.fileExists(atPath: file.path) {
                // Marker first: interrupted bootstrap must never silently import a second time.
                try RecurringStore.durableWrite(JSONEncoder().encode(Marker(version: 1, binding: binding)), to: marker, sync: sync)
                try write(.init(version: 1, binding: binding, generation: 0, entries: Dictionary(uniqueKeysWithValues: try schedules.map { (try key(binding, $0.key), try entry(binding, $0.value, generation: 0)) })))
            }
            let marker = try JSONDecoder().decode(Marker.self, from: Data(contentsOf: marker))
            let state = try JSONDecoder().decode(State.self, from: Data(contentsOf: file))
            guard marker.version == 1, marker.binding == state.binding, state.version == 1 else { throw RecurringError.message(Self.message) }
            for (id, entry) in state.entries {
                guard !entry.scheduleID.isEmpty, id == (try key(entry.binding, entry.scheduleID)), entry.generation <= state.generation,
                      ["active", "paused", "needs_attention", "needs_approval", "completed", "deleted"].contains(entry.status),
                      entry.status == "deleted" ? entry.digest == nil : entry.digest?.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
                else { throw RecurringError.message(Self.message) }
            }
            return state
        } catch { failed = true; AppLog("recurring").w(Self.logMessage); throw RecurringError.message(Self.message) }
    }
    func stopped(binding: RecurringStore.Binding, schedules: [String: RecurringPayment]) -> Set<String> {
        do {
            let state = try state(binding: binding, schedules: schedules)
            let stopped = try Set(schedules.filter { try !matches(state.entries[key(binding, $0.key)], binding, $0.value) }.keys)
            if !stopped.isEmpty { AppLog("recurring").w(Self.logMessage) }
            return stopped
        } catch { return Set(schedules.keys) }
    }
    func commit(binding: RecurringStore.Binding, before: [String: RecurringPayment], after: [String: RecurringPayment], policyID: String? = nil) throws {
        var state = try state(binding: binding, schedules: before)
        let changed = Set(before.keys).union(after.keys).union(policyID.map { [$0] } ?? []).filter { $0 == policyID || before[$0] != after[$0] }
        guard !changed.isEmpty else { return }
        guard state.generation < UInt64.max else { throw RecurringError.message(Self.message) }
        state.generation += 1
        for id in changed {
            let key = try key(binding, id)
            if let schedule = after[id] {
                // Deletion is the only way to resolve rollback; overrides cannot adopt old policy.
                guard try before[id].map({ try matches(state.entries[key], binding, $0) }) ?? (state.entries[key] == nil) else { throw RecurringError.message(Self.message) }
                state.entries[key] = try entry(binding, schedule, generation: state.generation)
            } else {
                state.entries[key] = .init(binding: binding, scheduleID: id, generation: state.generation, revision: state.entries[key]?.revision, status: "deleted", digest: nil)
            }
        }
        do { try write(state) }
        catch { failed = true; AppLog("recurring").w(Self.logMessage); throw error }
    }
}
