import CryptoKit
import Foundation

/// Write-ahead policy authority. A recovered MMKV snapshot can never repair this evidence.
final class RecurringPolicyLedger: @unchecked Sendable {
    static let message = "Recurring policy evidence needs attention. Saved schedules are preserved; edit or resume them to approve a new revision. Sending remains stopped."
    static let logMessage = "Recurring policy rollback or unavailable evidence detected; sending stopped"
    struct Marker: Codable { let version: Int; let binding: RecurringStore.Binding }
    private struct Entry: Codable {
        var binding: RecurringStore.Binding
        var scheduleID: String
        var generation: UInt64
        var revision: UUID?
        var status: String
        var digest: String?
        var settlementKey: String?
        var pending: RecurringPayment?
        var predecessor: String?
        var digestVersion: Int?
        var predecessorVersion: Int?
        var formatMigration: Bool?
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
    // Frozen projections preserve the exact encoder tokens, including escaped strings and numbers.
    private func digest(_ schedule: RecurringPayment, version: Int) throws -> String {
        guard (1...2).contains(version) else { throw RecurringError.message(Self.message) }
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        func fields(_ raw: String, _ names: String) throws -> RawObject {
            var object = try RawObject(raw)
            let keys = Set(names.split(separator: " ").map(String.init))
            object.fields = object.fields.filter { keys.contains($0.key) }
            return object
        }
        let names = "id label destination destinationType amountSat comment interval startAt endAt maxOccurrences nativeRevision status nextOccurrenceIndex nextRunAt occurrencesPaid consecutiveFailures lastError retryNotBefore inFlight runs createdAt updatedAt" + (version == 2 ? " issue" : "")
        var row = try fields(String(decoding: encoder.encode(schedule), as: UTF8.self), names)
        row.fields["interval"] = try fields(row.fields["interval"]!, "unit every").json()
        if let flight = row.fields["inFlight"] { row.fields["inFlight"] = try fields(flight, "occurrenceIndex startedAt").json() }
        let runs = try RawObject.arrayObjects(row.fields["runs"]!)
        row.fields["runs"] = "[" + (try runs.map { try fields($0.json(), "occurrenceIndex scheduledFor attemptedAt status amountSat error").json() }).joined(separator: ",") + "]"
        if let issue = row.fields["issue"] { row.fields["issue"] = try fields(issue, "code arguments").json() }
        return SHA256.hash(data: Data(try row.json().utf8)).map { String(format: "%02x", $0) }.joined()
    }
    private func digestMatches(_ expected: String?, version: Int?, _ schedule: RecurringPayment) throws -> Bool {
        if let version { return try expected == digest(schedule, version: version) }
        return try expected == digest(schedule, version: 2) || (schedule.issue == nil && expected == digest(schedule, version: 1))
    }
    private func matchingVersion(_ entry: Entry, _ schedule: RecurringPayment) throws -> Int {
        if let version = entry.digestVersion { return version }
        return try entry.digest == digest(schedule, version: 2) ? 2 : 1
    }
    private func entry(_ binding: RecurringStore.Binding, _ schedule: RecurringPayment, generation: UInt64) throws -> Entry {
        .init(binding: binding, scheduleID: schedule.id, generation: generation, revision: schedule.nativeRevision, status: schedule.status,
              digest: try digest(schedule, version: 2), digestVersion: 2)
    }
    private func matches(_ entry: Entry?, _ binding: RecurringStore.Binding, _ schedule: RecurringPayment) throws -> Bool {
        guard let entry else { return false }
        return try entry.binding == binding && entry.scheduleID == schedule.id && entry.revision == schedule.nativeRevision && entry.status == schedule.status && digestMatches(entry.digest, version: entry.digestVersion, schedule)
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
                guard entry.digestVersion.map({ (1...2).contains($0) }) ?? true,
                      entry.predecessorVersion.map({ (1...2).contains($0) }) ?? true,
                      !entry.scheduleID.isEmpty, id == (try key(entry.binding, entry.scheduleID)), entry.generation <= state.generation,
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
    func commit(binding: RecurringStore.Binding, before: [String: RecurringPayment], after: [String: RecurringPayment], policyID: String? = nil, reapproval: Bool = false, settlementKey: String? = nil, migrationIDs: Set<String> = []) throws {
        var state = try state(binding: binding, schedules: before)
        let changed = Set(before.keys).union(after.keys).union(policyID.map { [$0] } ?? []).filter { $0 == policyID || migrationIDs.contains($0) || before[$0] != after[$0] }
        guard !changed.isEmpty else { return }
        guard state.generation < UInt64.max else { throw RecurringError.message(Self.message) }
        state.generation += 1
        for id in changed {
            let key = try key(binding, id)
            if let schedule = after[id] {
                // User reapproval starts a fresh revision under this generation.
                let approved = reapproval && schedule.nativeRevision != nil && schedule.nativeRevision != state.entries[key]?.revision && schedule.nativeRevision != before[id]?.nativeRevision && schedule.inFlight == nil && schedule.status == "active"
                guard try approved || (before[id].map({ try matches(state.entries[key], binding, $0) }) ?? (state.entries[key] == nil)) else { throw RecurringError.message(Self.message) }
                var next = try entry(binding, schedule, generation: state.generation)
                next.settlementKey = settlementKey ?? state.entries[key]?.settlementKey
                if settlementKey != nil || migrationIDs.contains(id) {
                    next.pending = schedule; next.predecessor = state.entries[key]?.digest
                    next.predecessorVersion = try state.entries[key].map { try matchingVersion($0, before[id]!) }
                    next.formatMigration = migrationIDs.contains(id)
                }
                state.entries[key] = next
            } else {
                state.entries[key] = .init(binding: binding, scheduleID: id, generation: state.generation, revision: state.entries[key]?.revision, status: "deleted", digest: nil)
            }
        }
        do { try write(state) }
        catch { failed = true; AppLog("recurring").w(Self.logMessage); throw error }
    }
    var hasPending: Bool {
        guard let bytes = try? Data(contentsOf: file), let state = try? JSONDecoder().decode(State.self, from: bytes) else { return false }
        return state.entries.values.contains { $0.pending != nil }
    }
    func applied(binding: RecurringStore.Binding, schedules: [String: RecurringPayment], id: String, token: String) throws -> Bool {
        try state(binding: binding, schedules: schedules).entries[key(binding, id)]?.settlementKey == token
    }
    /// Repair only an unfinished exact predecessor; completed snapshots still fail closed.
    func pending(binding: RecurringStore.Binding, schedules: [String: RecurringPayment]) throws -> [RecurringPayment] {
        let state = try state(binding: binding, schedules: schedules)
        return try state.entries.values.filter { $0.binding == binding && $0.pending != nil }.map { entry in
            guard let row = schedules[entry.scheduleID], let pending = entry.pending,
                  (entry.settlementKey != nil || entry.formatMigration == true), pending.id == entry.scheduleID, pending.nativeRevision == entry.revision,
                  try digestMatches(entry.digest, version: entry.digestVersion, pending),
                  try digestMatches(entry.predecessor, version: entry.predecessorVersion, row) || matches(entry, binding, row) else { throw RecurringError.message(Self.message) }
            return pending
        }
    }
    func finish(binding: RecurringStore.Binding, schedules: [String: RecurringPayment]) throws {
        var state = try state(binding: binding, schedules: schedules)
        let pending = state.entries.filter { $0.value.binding == binding && $0.value.pending != nil }
        guard !pending.isEmpty else { return }
        for (id, entry) in pending {
            guard let row = schedules[entry.scheduleID], try matches(entry, binding, row) else { throw RecurringError.message(Self.message) }
            state.entries[id]?.pending = nil; state.entries[id]?.predecessor = nil
            state.entries[id]?.predecessorVersion = nil; state.entries[id]?.formatMigration = nil
        }
        try write(state)
    }
    /// A format change uses the same write-ahead replay as settlement, after old work completes.
    func migrate(binding: RecurringStore.Binding, schedules: [String: RecurringPayment]) throws -> Bool {
        guard let state = try? state(binding: binding, schedules: schedules) else { return false }
        guard !state.entries.values.contains(where: { $0.binding == binding && $0.pending != nil }) else { throw RecurringError.message(Self.message) }
        var after = schedules, ids: Set<String> = []
        for (id, row) in schedules where !row.unsupportedStorage {
            let entry = state.entries[try key(binding, id)]
            guard try matches(entry, binding, row) else { continue }
            var converted = row; converted.decodeLegacyIssue()
            if entry?.digestVersion != 2 || converted != row { after[id] = converted; ids.insert(id) }
        }
        guard !ids.isEmpty else { return false }
        try commit(binding: binding, before: schedules, after: after, migrationIDs: ids)
        return true
    }
}
