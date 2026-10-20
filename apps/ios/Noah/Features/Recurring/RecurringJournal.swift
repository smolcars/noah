import Foundation

/// Independent fsynced marker: MMKV and the Send receipt can both lag a killed process.
struct RecurringJournal {
    struct Entry: Codable, Sendable {
        var binding: RecurringStore.Binding
        var scheduleID: String
        var plan: RecurringPayment.Plan
        var markedAt: Double
        var policyRevision: UUID?
        var resolved = false
        var consumed: Bool?
        var key: String { "\(scheduleID):\(plan.occurrenceIndex!)" }
    }
    private struct Scope: Codable, Equatable {
        var binding: RecurringStore.Binding
        var scheduleID: String
    }
    private struct Watermark: Codable {
        var binding: RecurringStore.Binding
        var scheduleID: String
        var through: Double
    }
    private struct ConsumedIndices: Codable {
        var binding: RecurringStore.Binding
        var scheduleID: String
        var policyRevision: UUID?
        var indices: [Int]
    }
    private struct State: Codable {
        var version = 2
        var attempts: [String: Entry] = [:]
        var consumed: [Watermark] = []
        var consumedIndices: [ConsumedIndices] = []
        var legacyUncertainty: [Scope] = []

        mutating func remember(_ entry: Entry) throws {
            guard entry.consumed == true else { return }
            guard let at = entry.plan.scheduledFor, at.isFinite,
                  let occurrence = entry.plan.occurrenceIndex, occurrence >= 0 else {
                throw RecurringError.message("Recurring consumption evidence is incomplete. Sending remains stopped.")
            }
            if let index = consumed.firstIndex(where: { $0.binding == entry.binding && $0.scheduleID == entry.scheduleID }) {
                consumed[index].through = max(consumed[index].through, at)
            } else {
                consumed.append(.init(binding: entry.binding, scheduleID: entry.scheduleID, through: at))
            }
            if let index = consumedIndices.firstIndex(where: {
                $0.binding == entry.binding && $0.scheduleID == entry.scheduleID && $0.policyRevision == entry.policyRevision
            }) {
                consumedIndices[index].indices = Array(Set(consumedIndices[index].indices + [occurrence])).sorted()
            } else {
                consumedIndices.append(.init(binding: entry.binding, scheduleID: entry.scheduleID,
                                             policyRevision: entry.policyRevision, indices: [occurrence]))
            }
        }
    }
    private struct Header: Decodable { var version: Int }
    private struct V1: Decodable { var attempts: [String: Entry]; var consumed: [Watermark] }
    var file: URL
    private func state() throws -> State {
        guard FileManager.default.fileExists(atPath: file.path) else { return State() }
        let bytes = try Data(contentsOf: file)
        let decoder = JSONDecoder()
        var migrated: State
        if let header = try? decoder.decode(Header.self, from: bytes) {
            switch header.version {
            case 2: return try decoder.decode(State.self, from: bytes)
            case 1:
                let old = try decoder.decode(V1.self, from: bytes)
                migrated = State(attempts: old.attempts, consumed: old.consumed)
                // Watermarks can cover overwritten revisions whose indices are unknowable.
                migrated.legacyUncertainty = old.consumed.map { Scope(binding: $0.binding, scheduleID: $0.scheduleID) }
            default: throw RecurringError.message("Unsupported recurring journal version. Sending remains stopped.")
            }
        } else {
            let entries = try decoder.decode([String: Entry].self, from: bytes)
            migrated = State(attempts: entries)
            // Even a legacy failed attempt may have replaced earlier consumed evidence.
            migrated.legacyUncertainty = entries.values.map { Scope(binding: $0.binding, scheduleID: $0.scheduleID) }
        }
        for entry in migrated.attempts.values { try migrated.remember(entry) }
        try write(migrated)
        return migrated
    }
    func load() throws -> [String: Entry] { try state().attempts }
    func consumed(binding: RecurringStore.Binding, scheduleID: String, at: Double,
                  policyRevision: UUID?, occurrenceIndex: Int) throws -> Bool {
        let state = try state()
        return state.legacyUncertainty.contains(.init(binding: binding, scheduleID: scheduleID))
            || state.consumed.contains { $0.binding == binding && $0.scheduleID == scheduleID && at <= $0.through }
            || state.consumedIndices.contains {
                $0.binding == binding && $0.scheduleID == scheduleID && $0.policyRevision == policyRevision && $0.indices.contains(occurrenceIndex)
            }
    }
    private func write(_ state: State) throws {
        try RecurringStore.durableWrite(JSONEncoder().encode(state), to: file)
    }
    func save(_ entry: Entry) throws {
        var state = try state()
        try state.remember(entry)
        state.attempts[entry.scheduleID] = entry
        try write(state)
    }
}
