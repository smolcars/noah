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
    private struct Watermark: Codable {
        var binding: RecurringStore.Binding
        var scheduleID: String
        var through: Double
    }
    private struct State: Codable {
        var version = 1
        var attempts: [String: Entry] = [:]
        var consumed: [Watermark] = []

        mutating func remember(_ entry: Entry) {
            guard entry.consumed == true, let at = entry.plan.scheduledFor else { return }
            if let index = consumed.firstIndex(where: { $0.binding == entry.binding && $0.scheduleID == entry.scheduleID }) {
                consumed[index].through = max(consumed[index].through, at)
            } else {
                consumed.append(.init(binding: entry.binding, scheduleID: entry.scheduleID, through: at))
            }
        }
    }
    var file: URL
    private func state() throws -> State {
        guard FileManager.default.fileExists(atPath: file.path) else { return State() }
        let bytes = try Data(contentsOf: file)
        if let state = try? JSONDecoder().decode(State.self, from: bytes) {
            guard state.version == 1 else { throw RecurringError.message("Unsupported recurring journal version. Sending remains stopped.") }
            return state
        }
        // Persist legacy consumption separately before a later revision can replace its attempt.
        let entries = try JSONDecoder().decode([String: Entry].self, from: bytes)
        var state = State(attempts: entries)
        for entry in entries.values { state.remember(entry) }
        try write(state)
        return state
    }
    func load() throws -> [String: Entry] { try state().attempts }
    func consumed(binding: RecurringStore.Binding, scheduleID: String, at: Double) throws -> Bool {
        try state().consumed.contains { $0.binding == binding && $0.scheduleID == scheduleID && at <= $0.through }
    }
    private func write(_ state: State) throws {
        try RecurringStore.durableWrite(JSONEncoder().encode(state), to: file)
    }
    func save(_ entry: Entry) throws {
        var state = try state()
        state.remember(entry)
        state.attempts[entry.scheduleID] = entry
        try write(state)
    }
}
