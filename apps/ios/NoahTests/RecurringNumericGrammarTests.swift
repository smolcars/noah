import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringNumericGrammarTests {
    struct Case: Decodable, Sendable { let name: String; let rowJSON: String; let supported: Bool }
    struct Fixture: Decodable { let cases: [Case] }
    nonisolated static func cases() throws -> [Case] {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appending(path: "fixtures/recurring-rn-numeric-grammar.json")
        return try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: file)).cases
    }
    /// Pure parsing, so one store checks every token.
    @Test func exactTokens() throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        for test in try Self.cases() {
            let raw = "{\"version\":0,\"state\":{\"schedules\":{\"broken\":\(test.rowJSON)},\"serverSyncPending\":false}}"
            h.settings.set(raw, forKey: RecurringStore.key)
            let store = RecurringStore(settings: h.settings)
            #expect(((try? store.load().schedules["broken"]?.unsupportedStorage) == false) == test.supported, "\(test.name)")
            #expect(h.settings.string(forKey: RecurringStore.key) == raw, "\(test.name)")
        }
    }
}
