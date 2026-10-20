import Foundation
import MMKV
import Testing
@testable import Noah

@MainActor struct RecurringSettlementRollbackTests {
    @Test func rollbackMismatchMustStillAcknowledgeProvenPaidReceipt() async throws {
        let h = try RecurringExecutorTests.Harness(); let original = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        await h.executor.timeChanged()
        let active = try #require(h.settings.string(forKey: RecurringStore.key))
        h.writeRemovalFails = true
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(h.controller.locked)
        h.writeRemovalFails = false
        try h.settings.setDurably(active, forKey: RecurringStore.key)
        #expect(try h.store.load().schedules[original.id]?.status == "needs_attention")
        await h.executor.checkStatus(original.id)
        #expect(try h.journal.load()[original.id]?.consumed == true)
        #expect(!h.controller.locked, "policy mismatch must not retain an already paid Send lock")
    }

    @Test func consumedReceiptCanBeAcknowledgedWithLatchedPolicyWriteFailure() async throws {
        let h = try RecurringExecutorTests.Harness(); let original = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        h.writeRemovalFails = true
        h.afterPay = { h.storeWriteFails.withLock { $0 = true } }
        await h.executor.execute()
        #expect(h.controller.locked)
        #expect(try h.journal.load()[original.id]?.historyApplied != true)
        #expect(throws: (any Error).self) { try h.store.load() }
        h.writeRemovalFails = false
        h.storeWriteFails.withLock { $0 = false }
        await h.executor.checkStatus(original.id)
        #expect(!h.controller.locked)
        #expect(h.calls.withLock { $0 } == 1)
    }
}
