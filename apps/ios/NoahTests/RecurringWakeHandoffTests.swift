import Foundation
@testable import Noah
import Testing
import UIKit

@MainActor struct RecurringWakeHandoffTests {
    private func waitUntil(_ condition: @MainActor () async -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(5)
        while !(await condition()), ContinuousClock.now < deadline { await Task.yield() }
        try #require(await condition())
    }

    @Test func unbudgetedJoinOnlyDrainsRevokedWork() async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let gate = RecurringReservationTests.Gate()
        let budget = RecurringSubmissionBudget()
        var quotes = 0
        h.preflight = {
            quotes += 1
            if quotes == 1 { await gate.wait() }
        }
        let original = Task {
            await RecurringSubmissionBudget.$current.withValue(budget) { await h.executor.execute() }
        }
        try await waitUntil { await gate.entered }
        budget.revoke()
        var joined = false
        let drain = Task { joined = true; await h.executor.execute() }
        try await waitUntil { joined }
        await gate.release()
        await original.value; await drain.value
        #expect(quotes == 1)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 0)
    }

    @Test(arguments: ["revoked", "expired", "settled", "ambiguous"])
    func backgroundWakesRecheckDueWorkAfterJoiningForeground(outcome: String) async throws {
        let h = try RecurringExecutorTests.Harness()
        let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let gate = RecurringReservationTests.Gate()
        let active = RecurringExecutorTests.Locked(true)
        var quotes = 0
        h.preflight = {
            quotes += 1
            if quotes == 1 && outcome != "ambiguous" { await gate.wait() }
        }
        if outcome == "ambiguous" {
            h.ambiguous = true
            h.afterPay = { await gate.wait() }
        }
        let foreground = Task {
            await ForegroundExecution.run(isActive: { active.withLock { $0 } }) {
                await h.executor.execute()
            }
        }
        try await waitUntil { await gate.entered }
        var joined = 0
        let budgets = [RecurringSubmissionBudget(), RecurringSubmissionBudget()]
        let join: @MainActor @Sendable () async -> Void = {
            joined += 1
            await h.executor.execute()
        }
        let wake: @Sendable (RecurringSubmissionBudget) async -> UIBackgroundFetchResult = { budget in
            await AppDelegate.completeWithin(.seconds(5)) {
                try await RecurringSubmissionBudget.$current.withValue(budget) {
                    await join()
                    try BackgroundExecution.checkActive()
                }
            }
        }
        let wakes = budgets.map { budget in Task { await wake(budget) } }
        try await waitUntil { joined == 2 }
        if outcome != "settled" { active.withLock { $0 = false } }
        if outcome == "expired" { budgets.forEach { $0.revoke() } }
        await gate.release()
        await foreground.value
        for wake in wakes {
            let result = await wake.value
            #expect(result == (outcome == "expired" ? .failed : .newData))
        }
        let expectedCalls = outcome == "expired" ? 0 : 1
        #expect(h.calls.withLock { $0 } == expectedCalls)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == (outcome == "ambiguous" ? 0 : expectedCalls))
        if outcome == "ambiguous" {
            #expect(h.controller.locked)
            #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention")
        } else if expectedCalls == 1 {
            #expect(try h.journal.load()[schedule.id]?.consumed == true)
        }
        // Independent later wakes must also respect consumed or ambiguous evidence.
        if outcome != "expired" {
            await h.executor.execute()
            #expect(h.calls.withLock { $0 } == 1)
        }
    }
}
