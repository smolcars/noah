import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringVersionTests {
    @Test(arguments: ["1e-400", "0.0", "-0", "0e5", #""0""#, "1", "1e400", "true", "false", "null", "{}", "[]"])
    func invalidRawVersionStopsPopulatedStore(version: String) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let raw = try RecurringRangeTests.fixture().replacingOccurrences(of: #""version": 0"#, with: "\"version\": \(version)")
        h.settings.set(raw, forKey: RecurringStore.key)
        h.clock.withLock { $0 = 1001 }
        #expect(throws: (any Error).self) { try h.store.load() }
        #expect(throws: (any Error).self) { try h.store.bind(h.owner.withLock { $0 }) }
        #expect(throws: (any Error).self) { try h.store.upsert(RecurringScheduleTests.schedule()) }
        #expect(throws: (any Error).self) { try h.store.setPending(false) }
        #expect(throws: (any Error).self) { try h.store.remove("count-0") }
        await h.executor.execute()
        await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(h.executor.error != nil)
        #expect(!FileManager.default.fileExists(atPath: h.journal.file.path))
        #expect(h.settings.string(forKey: RecurringStore.key) == raw)
        #expect(throws: (any Error).self) { try RecurringStore(settings: h.settings).load() }
    }

    @Test(arguments: ["0", " 0 \n", "absent"])
    func writerAndLegacyVersionsRemainSupported(version: String) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var raw = try RecurringRangeTests.fixture()
        if version == "absent" { raw = raw.replacingOccurrences(of: ",\n  \"version\": 0", with: "") }
        else { raw = raw.replacingOccurrences(of: #""version": 0"#, with: "\"version\": \(version)") }
        h.settings.set(raw, forKey: RecurringStore.key)
        #expect(try h.store.load().schedules.count == 11)
        #expect(h.settings.string(forKey: RecurringStore.key) == raw)
        try h.store.setPending(false)
        #expect(try !RecurringStore(settings: h.settings).load().pending)
    }
}
