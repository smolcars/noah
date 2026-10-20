import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringReapprovalTests {
    @Test(arguments: [false, true]) func explicitResumeAndEditRetainConsumption(edit: Bool) async throws {
        let h = try RecurringExecutorTests.Harness(); let original = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        await h.executor.timeChanged()
        let active = try #require(h.settings.string(forKey: RecurringStore.key))
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        try h.settings.setDurably(active, forKey: RecurringStore.key)
        #expect(try h.store.load().schedules[original.id]?.lastError == RecurringPolicyLedger.message)
        if edit {
            var input = original; input.destination = "new-recipient"; input.amountSat = 500; input.startAt = h.now; input.nextRunAt = h.now
            try await h.executor.save(input, editing: true)
        } else { try await h.executor.resume(original.id) }
        let approved = try #require(h.store.load().schedules[original.id])
        #expect(approved.nativeRevision != original.nativeRevision)
        #expect(approved.status == "active")
        let entry = try #require(h.journal.load()[original.id])
        #expect(try h.journal.consumed(binding: entry.binding, scheduleID: original.id, at: entry.plan.scheduledFor!, policyRevision: entry.policyRevision, occurrenceIndex: entry.plan.occurrenceIndex!))
        await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 1)
    }
}
