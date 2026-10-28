import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringSettlementAdmissionTests {
    @Test func doneBeforeReservationReturnStillRecordsPaidOccurrence() async throws {
        try await reservationRace(change: "done")
    }

    @Test(arguments: ["replacement", "sameKeyReplacement", "reload", "failed", "ambiguous", "blocked", "foreignReload"])
    func terminalResultSurvivesReservationReturn(change: String) async throws {
        try await reservationRace(change: change)
    }

    private func reservationRace(change: String) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var schedule = try h.add()
        schedule.status = "needs_approval"; try h.store.upsert(schedule)
        let terminal = RecurringExecutorTests.Locked(false)
        let release = AsyncStream<Void>.makeStream()
        defer { release.continuation.finish() }
        var holdReturn = true
        h.reservation = { work in
            await work()
            // Send's terminal state is visible while the reservation returns across actors.
            if holdReturn {
                holdReturn = false
                terminal.withLock { $0 = true }
                var iterator = release.stream.makeAsyncIterator()
                _ = await iterator.next()
            }
        }
        if change == "failed" { h.afterPay = { throw SendNotSubmitted(reason: .fundsChanged) } }
        if change == "ambiguous" { h.ambiguous = true }
        let review = try await h.executor.review(schedule.id)
        let run = Task { await h.executor.execute(manual: true, approval: review) }
        try await SendPaymentTests.waitUntil { terminal.withLock { $0 } }
        let key = try #require(h.controller.attempt?.recurringOccurrenceKey)
        let result = try #require(h.journal.load()[schedule.id]?.terminal)
        expectTerminal(result, change: change, amount: schedule.amountSat)
        h.controller.acknowledge()
        if change == "ambiguous" { #expect(h.controller.locked) }
        else {
            #expect(!h.controller.locked && h.controller.attempt == nil)
            #expect(h.controller.attempt == nil)
            #expect(try h.journal.load()[schedule.id]?.terminal != nil)
        }
        try await apply(change, h, schedule: schedule, key: key)
        let replacement = h.controller.attempt, replacementGeneration = h.controller.generation
        release.continuation.yield(()); release.continuation.finish()
        await run.value
        if change == "foreignReload" {
            #expect(h.controller.attempt == nil)
            #expect(try h.journal.load()[schedule.id]?.consumed != true)
            #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == 0)
            #expect(h.calls.withLock { $0 } == 1)
            return
        }
        if change == "blocked" {
            #expect(h.controller.attempt == nil)
            #expect(h.saved == nil)
            try FileManager.default.removeItem(at: h.journal.quarantineFile)
            #expect(try h.journal.load()[schedule.id]?.consumed != true)
            await h.executor.checkStatus(schedule.id)
        }
        let paid = change != "failed" && change != "ambiguous"
        #expect(h.calls.withLock { $0 } == 1)
        #expect((try h.journal.load()[schedule.id]?.consumed == true) == paid)
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == (paid ? 1 : 0))
        if change == "failed" { #expect(try h.store.load().schedules[schedule.id]?.lastError == result.message) }
        if change == "replacement" || change == "sameKeyReplacement" {
            h.controller.acknowledge(ifGeneration: result.generation)
            #expect(h.controller.locked && h.controller.generation == replacementGeneration)
            #expect(h.controller.attempt?.recurringOccurrenceKey == replacement?.recurringOccurrenceKey)
            #expect(h.controller.attempt?.startedAt == replacement?.startedAt)
        } else if change != "ambiguous" {
            #expect(h.controller.attempt == nil)
            #expect(h.saved == nil)
        }
        await h.executor.checkStatus(schedule.id)
        await h.executor.execute()
        #expect(try h.store.load().schedules[schedule.id]?.occurrencesPaid == (paid ? 1 : 0))
        #expect(h.calls.withLock { $0 } == 1)
        if change == "ambiguous" { #expect(try h.store.load().schedules[schedule.id]?.status == "needs_attention") }
    }

    private func expectTerminal(_ result: RecurringJournal.TerminalEvidence, change: String, amount: UInt64) {
        if change == "failed" { #expect(result.outcome == .failed) }
        else if change == "ambiguous" { #expect(result.outcome == .uncertain) }
        else if result.outcome == .succeeded, let receipt = result.attempt.receipt {
            #expect(receipt.proof == .submitReturnedArkSuccess)
            #expect(receipt.amount == amount)
        } else { Issue.record("Expected paid receipt") }
    }

    /// What happens to Send while the finished payment's reservation is still returning.
    private func apply(_ change: String, _ h: RecurringExecutorTests.Harness, schedule: RecurringPayment, key: String) async throws {
        if change == "replacement" || change == "sameKeyReplacement" {
            h.beforePay = { throw SendNotSubmitted(reason: .recurringStopped) }
            let quoted = h.quoted(schedule, fees: RecurringFeePolicy.fallback)
            let payment = quoted.payment, quote = quoted.quote
            await h.controller.submitAndWait(payment, quote: quote,
                                             recurringKey: change == "sameKeyReplacement" ? key : "another-occurrence", beforeSubmit: {})
        } else if change == "reload" { h.controller.reloadForWalletChange() }
        else if change == "foreignReload" {
            h.owner.withLock { $0.instance = UUID() }
            h.saved = nil
            h.controller.reloadForWalletChange()
        } else if change == "blocked" {
            try Data("Storage admission blocked by test".utf8).write(to: h.journal.quarantineFile)
        }
    }

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
            switch h.controller.state {
            case .succeeded: break
            default: Issue.record("Expected a proven terminal receipt")
            }
        } else { #expect(h.controller.state == .failed) }
        h.controller.acknowledge() // The attended Send sheet's Done action.
        if change == "reloadedAttempt" {
            h.saved = originalAttempt
            h.controller.reloadForWalletChange()
        } else if change != "done" {
            h.beforePay = { throw SendNotSubmitted(reason: .recurringStopped) }
            let quoted = h.quoted(schedule, fees: RecurringFeePolicy.fallback)
            let payment = quoted.payment, quote = quoted.quote
            let key = change == "sameKeyReplacement" ? originalAttempt?.recurringOccurrenceKey ?? "missing" : "another-occurrence"
            await h.controller.submitAndWait(payment, quote: quote, recurringKey: key, beforeSubmit: {})
            #expect(h.controller.state == .storageBlocked)
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
