import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringRangeTests {
    static func fixture() throws -> String {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appending(path: "fixtures/recurring-rn-range.json")
        return try String(contentsOf: url, encoding: .utf8)
    }

    static let supported = ["count-0", "count-1", "count-2", "count-3"]
    nonisolated static let unsupported = ["count-4", "count-5", "count-6", "start-0", "start-1", "start-2", "end-fraction"]

    struct Expectation: Decodable { var classification: String; var reason: String }
    struct Expectations: Decodable { var expectedClassifications: [String: Expectation] }

    private func originalRow(_ id: String, in fixture: String) throws -> String {
        let schedules = try #require(fixture.range(of: #""schedules": {"#))
        let start = try #require(fixture.range(of: "\"\(id)\": {", range: schedules.upperBound..<fixture.endIndex))
        let rest = fixture[start.upperBound...]
        let end = try #require(rest.range(of: "\n      }"))
        return "{" + rest[..<end.upperBound]
    }

    @Test func rnRangeRowsRemainVisible() throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let raw = try Self.fixture()
        h.settings.set(raw, forKey: RecurringStore.key)
        let state = try h.store.load()
        #expect(state.schedules.count == 11)
        let expectations = try JSONDecoder().decode(Expectations.self, from: Data(raw.utf8)).expectedClassifications
        #expect(Set(state.schedules.keys) == Set(expectations.keys))
        for (id, expected) in expectations {
            let row = try #require(state.schedules[id])
            let classification = row.unsupportedStorage ? "stopped-unsupported" : row.status
            #expect(classification == expected.classification, "\(id): \(expected.reason)")
        }
        #expect(h.settings.string(forKey: RecurringStore.key) == raw)
        for id in Self.supported {
            #expect(state.schedules[id]?.status == "active")
        }
        #expect(state.schedules["count-1"]?.maxOccurrences == 3_000_000_000)
        #expect(state.schedules["count-2"]?.maxOccurrences == 9_007_199_254_740_991)
        #expect(state.schedules["count-3"]?.maxOccurrences == 9_223_372_036_854_775_000)
        for id in Self.unsupported {
            let schedule = try #require(state.schedules[id])
            #expect(schedule.status == "needs_attention" && schedule.unsupportedStorage)
            #expect(schedule.lastError == RecurringPayment.unsupportedMessage)
            #expect(schedule.next(0) == nil)
            var forced = schedule; forced.status = "active"
            #expect(forced.plan(now: 1000).kind == "idle")
        }
    }

    @Test func healthyRNRowsExecuteWithoutChangingUnsupportedBytes() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let raw = try Self.fixture()
        h.settings.set(raw, forKey: RecurringStore.key)
        h.clock.withLock { $0 = 1001 }
        await h.executor.execute()
        await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 4)
        #expect(h.executor.error == nil)
        let state = try h.store.load()
        for id in Self.supported {
            #expect(state.schedules[id]?.occurrencesPaid == 1)
        }
        #expect(state.schedules["count-1"]?.maxOccurrences == 3_000_000_000)
        #expect(state.schedules["count-2"]?.maxOccurrences == 9_007_199_254_740_991)
        #expect(state.schedules["count-3"]?.maxOccurrences == 9_223_372_036_854_775_000)
        #expect(state.schedules["start-0"]?.unsupportedStorage == true)
        #expect(state.schedules["end-fraction"]?.unsupportedStorage == true)
        let reopened = RecurringStore(settings: h.settings)
        let saved = try #require(h.settings.string(forKey: RecurringStore.key))
        for id in Self.unsupported {
            #expect(saved.contains(try originalRow(id, in: raw)))
            #expect(try reopened.load().schedules[id]?.status == "needs_attention")
            #expect(try h.journal.load()[id] == nil)
        }
        #expect(RecurringExecutor.entries(Array(state.schedules.values)).allSatisfy { !Self.unsupported.contains($0.scheduleId) })
    }

    // rnRangeRowsRemainVisible classifies every unsupported row; one count, start and end row cover the executor flow.
    @Test(arguments: ["count-4", "start-0", "end-fraction"])
    func unsupportedRNRowRejectsApprovalAndRemainsCancellable(id: String) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let raw = try Self.fixture()
        h.settings.set(raw, forKey: RecurringStore.key)
        h.clock.withLock { $0 = 1001 }
        var schedule = try #require(h.store.load().schedules[id])
        var original = RecurringScheduleTests.schedule(start: 1000); original.id = id
        let entry = RecurringJournal.Entry(binding: h.owner.withLock { $0 }, scheduleID: id,
            plan: original.plan(now: 1000.5), markedAt: 1000.5)
        try h.journal.save(entry)
        let journalBytes = try Data(contentsOf: h.journal.file)
        await h.executor.checkStatus(id)
        #expect(h.statusChecks == 0)
        #expect(try Data(contentsOf: h.journal.file) == journalBytes)
        await #expect(throws: (any Error).self) { try await h.executor.resume(id) }
        await #expect(throws: (any Error).self) { try await h.executor.overrideAndResume(id, confirmed: true) }
        await #expect(throws: (any Error).self) { try await h.executor.save(schedule, editing: true) }
        schedule.id = "new-policy"
        await #expect(throws: (any Error).self) { try await h.executor.save(schedule, editing: false) }
        #expect(h.calls.withLock { $0 } == 0)
        #expect(!FileManager.default.fileExists(atPath: h.directory.appending(path: "noah-recurring-overrides.json").path))
        try await h.executor.pause(id)
        schedule.id = id; schedule.unsupportedStorage = false; schedule.status = "active"
        try h.store.upsert(schedule) // A copied display model cannot replace an unsupported original.
        let saved = try #require(h.settings.string(forKey: RecurringStore.key))
        #expect(saved.contains(try originalRow(id, in: raw)))
        #expect(try h.store.load().schedules[id]?.status == "needs_attention")
        try await h.executor.cancel(id)
        #expect(try h.store.load().schedules[id] == nil)
        #expect(try h.store.load().schedules.count == 10)
        #expect(try Data(contentsOf: h.journal.file) == journalBytes)
    }

    @Test(arguments: ["1.5", "{}"])
    func malformedRowDoesNotBlockOtherRows(count: String) throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let raw = try Self.fixture().replacingOccurrences(of: #""maxOccurrences": 3000000000"#, with: "\"maxOccurrences\": \(count)")
        h.settings.set(raw, forKey: RecurringStore.key)
        #expect(try h.store.load().schedules["count-1"]?.unsupportedStorage == true)
        #expect(try h.store.load().schedules.count == 11)
        try h.store.setPending(false)
        #expect(try #require(h.settings.string(forKey: RecurringStore.key)).contains(try originalRow("count-1", in: raw)))
        try h.store.remove("count-1")
        #expect(try h.store.load().schedules.count == 10)
    }

    @Test func unknownJSONTokensSurviveWritesAndMalformedRowsStayVisible() throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let opaque = #" { "escaped\"key": ["},\\\"{", {"fraction":0.12345678901234567890123456789,"wide":9223372036854776000,"max":1.7976931348623157e+308}] } "#
        let rows = #"{"broken":{"amountSat":1},"primitive":false,"missing":null,"array":[]}"#
        let raw = "{\"version\":0,\"unknown\":\(opaque),\"state\":{\"schedules\":\(rows),\"unknown\":\(opaque)}}"
        h.settings.set(raw, forKey: RecurringStore.key)
        let state = try h.store.load()
        #expect(state.schedules.count == 4 && state.schedules.values.allSatisfy(\.unsupportedStorage))
        var healthy = RecurringScheduleTests.schedule()
        try h.store.upsert(healthy)
        healthy.status = "paused"; try h.store.upsert(healthy)
        try h.store.setPending(false)
        let saved = try #require(h.settings.string(forKey: RecurringStore.key))
        #expect(saved.contains(opaque.trimmingCharacters(in: .whitespaces)))
        for id in state.schedules.keys { try h.store.remove(id) }
        #expect(try h.store.load().schedules.count == 1)
    }
}
