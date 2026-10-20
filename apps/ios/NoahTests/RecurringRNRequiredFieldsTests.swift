import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringRNRequiredFieldsTests {
    struct Case: Decodable, Sendable { let name: String; let rowJSON: String; let classification: String }
    struct Fixture: Decodable { let cases: [Case] }
    nonisolated static func cases() throws -> [Case] {
        let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appending(path: "fixtures")
        return try ["recurring-rn-required-fields", "recurring-rn-numeric-types", "recurring-rn-numeric-ranges", "recurring-rn-duplicate-keys"].flatMap { name in
            try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: directory.appending(path: "\(name).json"))).cases
        }
    }
    @Test(arguments: try cases())
    func firstImportClassifiesBeforeBootstrapAndPreservesUnsupportedBytes(test: Case) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let raw = "{\"version\":0,\"state\":{\"schedules\":{\"broken\":\(test.rowJSON)},\"serverSyncPending\":false}}"
        h.settings.set(raw, forKey: RecurringStore.key)
        h.clock.withLock { $0 = 1000 }
        var visible = try #require(h.store.load().schedules["broken"])
        #expect((visible.unsupportedStorage ? "stopped-unsupported" : visible.status) == test.classification, "\(test.name)")
        #expect(h.settings.string(forKey: RecurringStore.key) == raw)
        await h.executor.execute(); await h.executor.execute(manual: true)
        let unsupported = test.classification == "stopped-unsupported"
        #expect(h.calls.withLock { $0 } == (unsupported ? 0 : 1))
        guard unsupported else { return }
        #expect(try h.journal.load().isEmpty)
        await #expect(throws: (any Error).self) { try await h.executor.resume("broken") }
        await #expect(throws: (any Error).self) { try await h.executor.save(visible, editing: true) }
        try await h.executor.pause("broken"); try h.store.setPending(false)
        visible.unsupportedStorage = false; visible.status = "active"
        try h.store.upsert(visible)
        #expect(try #require(h.settings.string(forKey: RecurringStore.key)).contains(test.rowJSON))
        #expect(try RecurringStore(settings: h.settings).load().schedules["broken"]?.unsupportedStorage == true)
        try await h.executor.cancel("broken")
        #expect(try h.store.load().schedules.isEmpty)
    }
}
