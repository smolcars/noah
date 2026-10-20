import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringRound3RegressionTests {
    @Test func confirmedSettlementDuringSuspensionRemainsResumable() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let schedule = try h.add()
        h.afterPay = { try RecurringAutomationAuthority(directory: h.directory).suspension(true) }
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.journal.load()[schedule.id]?.consumed == true)
        try RecurringAutomationAuthority(directory: h.directory).resume()
        #expect(try h.store.load().schedules[schedule.id]?.status == "active", "Confirmed payment must not acquire a permanent uncertainty latch")
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 1)
        #expect(try h.journal.load()[schedule.id]?.policyApplied == true)
        #expect(!h.controller.locked)
        let settled = try h.store.load().schedules[schedule.id]
        await h.executor.checkStatus(schedule.id); await h.executor.execute(manual: true)
        #expect(try h.store.load().schedules[schedule.id] == settled)
        #expect(h.calls.withLock { $0 } == 1)
        try await h.executor.resume(schedule.id)
        #expect(try h.store.load().schedules[schedule.id]?.status == "active")
    }
    @Test(arguments: ["paused", "needs_attention"])
    func suspensionSettlementRetainsGenuinePauseAndFailureLatches(status: String) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let schedule = try h.add()
        let authority = RecurringAutomationAuthority(directory: h.directory)
        h.afterPay = {
            var latest = try #require(h.store.load().schedules[schedule.id])
            latest.status = status; latest.consecutiveFailures = 3; latest.lastError = "Policy stopped"; latest.retryNotBefore = h.now + 5000
            try h.store.upsert(latest)
            try authority.suspension(true)
        }
        await h.executor.execute(); try authority.resume()
        let settled = try #require(h.store.load().schedules[schedule.id])
        #expect(settled.status == status && settled.consecutiveFailures == 3 && settled.lastError == "Policy stopped")
        #expect(settled.retryNotBefore == h.now + 5000 && settled.occurrencesPaid == 1)
        #expect(try h.journal.load()[schedule.id]?.consumed == true)
        await h.executor.execute(manual: true)
        #expect(h.calls.withLock { $0 } == 1)
    }
}
