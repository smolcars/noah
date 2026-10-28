import Foundation
import MMKV
@testable import Noah
import Testing

@MainActor struct RecurringSettlementRepeatTests {
    @Test(arguments: [false, true]) func repeatedStatusAfterAckFailureMustNotRecordSameOccurrenceTwice(legacy: Bool) async throws {
        let h = try RecurringExecutorTests.Harness(); let original = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        h.ambiguous = true
        await h.executor.execute()
        #expect(try h.store.load().schedules[original.id]?.status == "needs_attention")
        h.resolution = .paid(.init(amount: original.amountSat, proof: .submitReturnedArkSuccess))
        h.writeRemovalFails = true
        await h.executor.checkStatus(original.id)
        #expect(try h.store.load().schedules[original.id]?.occurrencesPaid == 1)
        #expect(h.controller.locked)
        if legacy {
            var entry = try #require(h.journal.load()[original.id]); entry.historyApplied = nil; entry.released = nil
            try h.journal.save(entry)
        }
        let applied = try h.store.load().schedules[original.id], notices = h.notified
        h.writeRemovalFails = false
        await h.executor.checkStatus(original.id)
        #expect(try h.store.load().schedules[original.id]?.occurrencesPaid == 1, "one paid occurrence must create one history entry")
        #expect(try h.store.load().schedules[original.id] == applied)
        #expect(h.notified == notices)
        #expect(try h.journal.load()[original.id]?.policyApplied == true)
        #expect(!h.controller.locked)
        #expect(h.calls.withLock { $0 } == 1)
    }
}
