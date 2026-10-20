import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringSettlementAdmissionTests {
    @Test func acknowledgedReceiptWhileSettlementWaitsStillConsumesOccurrence() async throws {
        try await settlementRace(attended: true, change: "done")
    }

    @Test(arguments: ["done", "replacement", "sameKeyReplacement", "reloadedAttempt", "failedMessage"])
    func queuedSettlementKeepsItsOutcomeAndAcknowledgesOnlyItsAttempt(change: String) async throws {
        try await settlementRace(attended: false, change: change)
    }

    private func settlementRace(attended: Bool, change: String) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var schedule = try h.add()
        if attended { schedule.status = "needs_approval"; try h.store.upsert(schedule) }
        let gate = WalletMutationGate()
        let entered = RecurringExecutorTests.Locked(false)
        let release = AsyncStream<Void>.makeStream()
        var holder: Task<Void, Never>?
        h.afterPay = {
            holder = Task {
                await gate.withLock {
                    entered.withLock { $0 = true }
                    var iterator = release.stream.makeAsyncIterator()
                    _ = await iterator.next()
                }
            }
            while !entered.withLock({ $0 }) { await Task.yield() }
            if change == "failedMessage" { throw SendNotSubmitted(reason: .fundsChanged) }
        }
        let owner = h.owner, clock = h.clock
        let executor = RecurringExecutor(dependencies: .init(store: h.store, journal: h.journal,
            controller: h.controller, ensureOpen: {}, binding: { owner.withLock { $0 } }, allowed: {},
            quote: { schedule in await h.quoted(schedule, fees: RecurringFeePolicy.fallback) },
            now: { clock.withLock { $0 } }, mutation: gate))
        let review = attended ? try await executor.review(schedule.id) : nil
        let run = Task { await executor.execute(manual: true, approval: review) }
        while await gate.waitingCount == 0 { await Task.yield() }
        let originalAttempt = h.controller.attempt, originalMessage = h.controller.message
        let paid = change != "failedMessage"
        if paid {
            if case .succeeded = h.controller.state {} else { Issue.record("Expected a proven terminal receipt") }
        } else { #expect(h.controller.state == .failed) }
        h.controller.acknowledge() // The attended Send sheet's Done action.
        if change == "reloadedAttempt" {
            h.saved = originalAttempt
            h.controller.reloadForWalletChange()
        } else if change != "done" {
            h.beforePay = { throw SendNotSubmitted(reason: .recurringStopped) }
            let (payment, quote, _) = h.quoted(schedule, fees: RecurringFeePolicy.fallback)
            let key = change == "sameKeyReplacement" ? originalAttempt?.recurringOccurrenceKey ?? "missing" : "another-occurrence"
            await h.controller.submitAndWait(payment, quote: quote, recurringKey: key, beforeSubmit: {})
            #expect(h.controller.state == .failed)
        }
        let replacement = h.controller.attempt
        release.continuation.yield(()); release.continuation.finish()
        await holder?.value
        await run.value
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.journal.load()[schedule.id]?.consumed == paid)
        #expect(try h.journal.load()[schedule.id]?.policyApplied == true)
        #expect(try h.journal.load()[schedule.id]?.resolved == true)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == (paid ? 1 : 0))
        if !paid { #expect(try h.store.load().schedules[schedule.id]?.lastError == originalMessage) }
        if change == "done" {
            #expect(h.controller.attempt == nil)
        } else {
            #expect(h.controller.locked)
            #expect(h.controller.attempt?.recurringOccurrenceKey == replacement?.recurringOccurrenceKey)
            #expect(h.controller.attempt?.startedAt == replacement?.startedAt)
        }
        await executor.checkStatus(schedule.id)
        await executor.execute()
        #expect(try h.store.load().schedules[schedule.id]?.status == "active")
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == (paid ? 1 : 0))
        #expect(h.calls.withLock { $0 } == 1)
    }
}
