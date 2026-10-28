import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringLegacyRegressionTests {
    @Test func legacyUncertaintyMustStayStoppedAfterOverride() async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        try h.store.bind(h.owner.withLock { $0 })
        let failed = RecurringJournal.Entry(binding: h.owner.withLock { $0 }, scheduleID: schedule.id,
                                            plan: schedule.plan(now: h.now), markedAt: h.now, resolved: true, consumed: false)
        try JSONEncoder().encode([schedule.id: failed]).write(to: h.journal.file)
        await h.executor.execute()
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
        await #expect(throws: (any Error).self) { try await h.executor.overrideAndResume(schedule.id, confirmed: true) }
        await h.executor.execute()
        #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
        #expect(h.calls.withLock { $0 } == 0)
    }

    @Test(arguments: ["resume", "edit", "override"])
    func legacyUncertaintyRejectsChangesBeforeDuePlanning(action: String) async throws {
        let h = try RecurringExecutorTests.Harness(); var schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let owner = h.owner.withLock { $0 }
        try h.store.bind(owner)
        let failed = RecurringJournal.Entry(binding: owner, scheduleID: schedule.id,
                                            plan: schedule.plan(now: h.now), markedAt: h.now, resolved: true, consumed: false)
        try JSONEncoder().encode([schedule.id: failed]).write(to: h.journal.file)
        // A future or paused policy has no due plan, but its legacy history is still uncertain.
        schedule.startAt = h.now + 86_400_000; schedule.nextRunAt = schedule.startAt; schedule.status = action == "override" ? "needs_attention" : "paused"
        schedule.lastError = "Old interruption message"
        try h.store.upsert(schedule)
        do {
            switch action {
            case "resume": try await h.executor.resume(schedule.id)
            case "edit": try await h.executor.save(schedule, editing: true)
            default: try await h.executor.overrideAndResume(schedule.id, confirmed: true)
            }
            Issue.record("Legacy uncertainty must reject policy changes")
        } catch { #expect(error.localizedDescription == RecurringJournal.legacyMessage) }
        let journalBytes = try Data(contentsOf: h.journal.file)
        await h.executor.execute()
        let stopped = try #require(h.store.load().schedules[schedule.id])
        #expect(stopped.status == "needs_attention" && stopped.lastError == RecurringJournal.legacyMessage)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try Data(contentsOf: h.journal.file) == journalBytes)
        #expect(!FileManager.default.fileExists(atPath: h.directory.appending(path: "noah-recurring-overrides.json").path))
        try await h.executor.cancel(schedule.id)
        schedule.id = UUID().uuidString; schedule.status = "active"
        schedule.lastError = nil; schedule.issue = nil // A newly approved policy has no prior runtime error.
        schedule.startAt = h.now; schedule.nextRunAt = h.now
        try await h.executor.save(schedule, editing: false)
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.journal.legacyUncertain(binding: owner, scheduleID: failed.scheduleID))
        #expect(try !h.journal.legacyUncertain(binding: owner, scheduleID: schedule.id))
    }
}
