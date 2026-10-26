import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringTimestampTests {
    static let fields = ["startAt", "endAt", "nextRunAt", "createdAt", "updatedAt", "retryNotBefore",
                         "inFlight.startedAt", "runs.scheduledFor", "runs.attemptedAt"]
    /// A store holding one schedule with a fractional `field` beside a healthy one, and that schedule's raw row.
    static func store(fractional field: String) throws -> (raw: String, unsupported: String, original: RecurringPayment) {
        let original = RecurringScheduleTests.schedule(start: 1000)
        var row = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as? [String: Any])
        // RN's factory writes nullable keys; JSONEncoder omits nil properties.
        for key in ["endAt", "maxOccurrences", "nextRunAt", "lastError", "inFlight"] where row[key] == nil { row[key] = NSNull() }
        var healthy = row; healthy["id"] = "healthy"
        let healthyJSON = String(decoding: try JSONSerialization.data(withJSONObject: healthy), as: UTF8.self)
        switch field {
        case "inFlight.startedAt": row["inFlight"] = ["occurrenceIndex": 0, "startedAt": 1000.5]
        case "runs.scheduledFor", "runs.attemptedAt":
            var run: [String: Any] = ["occurrenceIndex": 0, "scheduledFor": 1000, "attemptedAt": 1000, "status": "failed", "amountSat": 1000]
            run[String(field.split(separator: ".")[1])] = 1000.5
            row["runs"] = [run]
        default: row[field] = 1000.5
        }
        let unsupported = String(decoding: try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]), as: UTF8.self)
        return ("{\"version\":0,\"state\":{\"schedules\":{\"rent\":\(unsupported),\"healthy\":\(healthyJSON)}}}", unsupported, original)
    }

    // Classification is parsing only, so every field is checked here; the executor flow below samples them.
    @Test func fractionalTimeInEveryFieldStaysVisibleStoppedAndLossless() throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        for field in Self.fields {
            let (raw, _, original) = try Self.store(fractional: field)
            h.settings.set(raw, forKey: RecurringStore.key)
            let visible = try #require(RecurringStore(settings: h.settings).load().schedules[original.id], "\(field)")
            #expect(visible.unsupportedStorage && visible.status == "needs_attention", "\(field)")
            #expect(visible.lastError == RecurringPayment.unsupportedMessage, "\(field)")
            #expect(visible.plan(now: 1001).kind == "idle", "\(field)")
            #expect(h.settings.string(forKey: RecurringStore.key) == raw, "\(field)")
        }
    }

    @Test(arguments: ["startAt", "inFlight.startedAt", "runs.attemptedAt"])
    func fractionalTimeStaysVisibleStoppedLosslessAndCancellable(field: String) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        h.clock.withLock { $0 = 1001 }
        let (raw, unsupported, original) = try Self.store(fractional: field)
        h.settings.set(raw, forKey: RecurringStore.key)
        let visible = try #require(h.store.load().schedules[original.id])
        #expect(h.settings.string(forKey: RecurringStore.key) == raw)
        #expect(visible.unsupportedStorage && visible.status == "needs_attention")
        #expect(visible.lastError == RecurringPayment.unsupportedMessage)
        #expect(visible.plan(now: 1001).kind == "idle")
        await h.executor.execute()
        await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.journal.load()[original.id] == nil)
        await #expect(throws: (any Error).self) { try await h.executor.resume(original.id) }
        await #expect(throws: (any Error).self) { try await h.executor.overrideAndResume(original.id, confirmed: true) }
        await #expect(throws: (any Error).self) { try await h.executor.save(visible, editing: true) }
        try await h.executor.pause(original.id)
        try h.store.setPending(false)
        #expect(try #require(h.settings.string(forKey: RecurringStore.key)).contains(unsupported))
        #expect(try RecurringStore(settings: h.settings).load().schedules[original.id]?.unsupportedStorage == true)
        try await h.executor.cancel(original.id)
        #expect(try Set(h.store.load().schedules.keys) == ["healthy"])
        #expect(h.calls.withLock { $0 } == 1)
    }

    @Test func nativeClockKeepsNewPaymentsExecutableAfterReopen() throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let now = RecurringPayment.now
        var schedule = RecurringScheduleTests.schedule(start: now)
        schedule.createdAt = now; schedule.updatedAt = now
        try schedule.validateInput(now: now)
        try h.store.upsert(schedule)
        let reopened = try #require(RecurringStore(settings: h.settings).load().schedules[schedule.id])
        #expect(!reopened.unsupportedStorage && reopened.status == "active")
        #expect(reopened.plan(now: now).kind == "pay")
    }
}
