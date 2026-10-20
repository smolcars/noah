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
    var file: URL
    func load() throws -> [String: Entry] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [:] }
        return try JSONDecoder().decode([String: Entry].self, from: Data(contentsOf: file))
    }
    func save(_ entry: Entry) throws {
        var entries = try load()
        entries[entry.scheduleID] = entry
        try RecurringStore.durableWrite(JSONEncoder().encode(entries), to: file)
    }
}
