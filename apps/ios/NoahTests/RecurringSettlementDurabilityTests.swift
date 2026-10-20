import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringSettlementDurabilityTests {
    private func paidButUnsettled(_ h: RecurringExecutorTests.Harness) async throws -> RecurringPayment {
        var s = try h.add()
        s.nativeRevision = UUID()
        try h.store.upsert(s)
        try h.store.bind(h.owner.withLock { $0 })
        let plan = s.plan(now: h.now)
        let e = RecurringJournal.Entry(binding: h.owner.withLock { $0 }, scheduleID: s.id, plan: plan, markedAt: h.now, policyRevision: s.nativeRevision)
        try h.journal.save(e)
        var marked = s
        marked.inFlight = .init(occurrenceIndex: plan.occurrenceIndex!, startedAt: h.now)
        try h.store.upsert(marked)
        let (payment, quote, _) = h.quoted(s, fees: RecurringFeePolicy.fallback)
        await h.controller.submitAndWait(payment, quote: quote, recurringKey: e.key, beforeSubmit: {})
        if case .succeeded = h.controller.state {} else { Issue.record("Expected proven payment") }
        return s
    }

    @Test func restartedDoneMustNotEraseUnsettledPaidEvidence() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try await paidButUnsettled(h)
        let receipt = h.saved
        h.controller.acknowledge()
        #expect(try h.journal.load()[s.id]?.terminal != nil)
        h.saved = receipt // Death before Done's shared-journal removal reaches disk.
        h.rebuild() // New controller/executor, only durable evidence survives.
        if case .succeeded = h.controller.state {} else { Issue.record("Expected recovered receipt") }
        h.controller.acknowledge()
        await h.executor.execute(); await h.executor.checkStatus(s.id)
        #expect(try h.journal.load()[s.id]?.consumed == true, "Restarted Done must preserve the proof needed for consumption")
        #expect(try h.store.load().schedules[s.id]?.occurrencesPaid == 1)
        await h.executor.execute(); await h.executor.checkStatus(s.id)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[s.id]?.runs.filter { $0.status == "success" }.count == 1)
    }

    @Test func restoredSuccessIsPublishedOnlyAfterOccurrenceHandoff() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try await paidButUnsettled(h)
        let persist = try #require(h.controller.persistRecurringEvidence)
        var called = false
        h.controller.persistRecurringEvidence = { attempt, generation, walletGeneration, outcome, message in
            called = true
            if case .succeeded = h.controller.state { Issue.record("Restored success preceded its durable handoff") }
            try persist(attempt, generation, walletGeneration, outcome, message)
        }
        h.controller.reloadForWalletChange()
        #expect(called)
        if case .succeeded = h.controller.state {} else { Issue.record("Expected recovered receipt") }
        h.controller.acknowledge()
        await h.executor.execute(); await h.executor.checkStatus(s.id)
        #expect(try h.store.load().schedules[s.id]?.occurrencesPaid == 1)
        #expect(h.calls.withLock { $0 } == 1)
    }

    @Test func replacementSendMustNotOverwriteUnsettledPaidEvidence() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try await paidButUnsettled(h)
        h.controller.acknowledge()
        let manualCalls = RecurringExecutorTests.Locked(0)
        let manual = SendPaymentController(dependencies: .init(
            prepare: { _, _ in .init(movementIDs: []) },
            pay: { payment, _, _ in manualCalls.withLock { $0 += 1 }; return .init(amount: payment.amount, proof: .submitReturnedArkSuccess) },
            reconcile: { _ in .uncertain }, read: { h.saved }, write: { h.saved = $0 },
            identity: { h.owner.withLock { $0.wallet } }))
        let (payment, quote, _) = h.quoted(s, fees: RecurringFeePolicy.fallback)
        // The shared single-slot journal is used by normal Send too.
        manual.submit(payment, quote: quote)
        try await SendPaymentTests.waitUntil { manual.state != .submitting }
        #expect(manualCalls.withLock { $0 } == 1)
        h.rebuild()
        await h.executor.execute(); await h.executor.checkStatus(s.id)
        #expect(try h.journal.load()[s.id]?.consumed == true, "A replacement must not destroy the earlier recurring proof")
        #expect(try h.store.load().schedules[s.id]?.occurrencesPaid == 1)
        await h.executor.execute(); await h.executor.checkStatus(s.id)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[s.id]?.runs.filter { $0.status == "success" }.count == 1)
    }

    @Test func walletReloadAndOverrideMustReleaseOldAmbiguousResults() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try h.add()
        h.ambiguous = true
        await h.executor.execute()
        for _ in 0..<5 { h.controller.reloadForWalletChange(); await h.controller.checkStatus() }
        h.controller.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(!h.controller.locked)
        let controller = try #require(h.controller)
        let reflected = Mirror(reflecting: controller)
        #expect(!reflected.children.contains { $0.label?.contains("recurringResults") == true })
        h.owner.withLock { $0.instance = UUID(); $0.wallet.fingerprint = "replacement" }
        h.controller.reloadForWalletChange()
        await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.journal.load()[s.id]?.consumed != true)
    }

    @Test func consumptionClaimBeforePolicyWriteMustRecoverPaidHistory() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try await paidButUnsettled(h)
        var e = try #require(h.journal.load()[s.id])
        e.resolved = true; e.consumed = true; e.policyApplied = true
        try h.journal.save(e) // Kill boundary after consumption, before policy upsert.
        h.rebuild()
        await h.executor.execute(); await h.executor.checkStatus(s.id); await h.executor.checkStatus(s.id)
        #expect(try h.store.load().schedules[s.id]?.occurrencesPaid == 1, "A durable claim must recover the first paid history application")
    }
    @Test(arguments: [false, true])
    func v2JournalsUpgradePendingProofAndStopUnknowableHistory(consumed: Bool) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try await paidButUnsettled(h)
        var e = try #require(h.journal.load()[s.id])
        e.terminal = nil; e.resolved = consumed; e.consumed = consumed; e.policyApplied = consumed
        try h.journal.save(e)
        var root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: h.journal.file)) as? [String: Any])
        root["version"] = 2
        try RecurringStore.durableWrite(JSONSerialization.data(withJSONObject: root), to: h.journal.file)
        h.saved?.recurringAttemptID = nil // Earlier builds did not have a durable Send attempt ID.
        h.rebuild()
        if consumed { #expect(h.controller.state == .storageBlocked) }
        else { h.controller.acknowledge() }
        await h.executor.execute(); await h.executor.checkStatus(s.id)
        #expect(try h.store.load().schedules[s.id]?.occurrencesPaid == (consumed ? 0 : 1))
        if consumed { #expect(try h.store.load().schedules[s.id]?.lastError == RecurringJournal.legacyMessage) }
        #expect(h.calls.withLock { $0 } == 1)
    }

    @Test func ambiguityOverrideRemainsReleasedAfterRestart() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try h.add(); h.ambiguous = true
        await h.executor.execute()
        try await h.executor.overrideAndResume(s.id, confirmed: true)
        #expect(try h.journal.load()[s.id]?.released == true)
        h.rebuild(); await h.executor.execute(); await h.executor.checkStatus(s.id)
        #expect(try h.store.load().schedules[s.id]?.status == "active")
        #expect(try h.store.load().schedules[s.id]?.occurrencesPaid == 0)
        #expect(h.calls.withLock { $0 } == 1)
    }

}

@MainActor struct RecurringSettlementCrashTests {
    private func restart(_ h: RecurringExecutorTests.Harness) async {
        h.store = RecurringStore(settings: h.settings)
        h.rebuild()
        h.controller.reloadForWalletChange() // The occurrence sink is installed on the fresh executor.
    }
    private func assertPaidExactlyOnce(_ h: RecurringExecutorTests.Harness, id: String) async throws {
        await restart(h)
        for _ in 0..<3 { await h.executor.execute(); await h.executor.checkStatus(id) }
        let paid = try #require(h.store.load().schedules[id])
        #expect(paid.occurrencesPaid == 1)
        #expect(paid.runs.filter { $0.status == "success" }.count == 1)
        let e = try #require(h.journal.load()[id])
        #expect(e.consumed == true && e.historyApplied == true && e.released == true)
        #expect(h.calls.withLock { $0 } == 1)
        h.controller.acknowledge()
        await restart(h)
        await h.executor.execute(); await h.executor.checkStatus(id)
        #expect(try h.store.load().schedules[id]?.occurrencesPaid == 1)
        #expect(h.calls.withLock { $0 } == 1)
    }
    @Test(arguments: ["terminal", "consumed", "historyApplied", "released", "beforeTerminal"])
    func processDeathAtEachDurableStepFinishesExactlyOnce(boundary: String) async throws {
        let crashed = RecurringExecutorTests.Locked(false)
        let enabled = RecurringExecutorTests.Locked(true)
        let h = try RecurringExecutorTests.Harness(journalWrite: { data, file in
            if enabled.withLock({ $0 }) && crashed.withLock({ $0 }) { throw CocoaError(.fileWriteUnknown) }
            let root = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
            let row = (root["attempts"] as? [String: [String: Any]])?.values.first
            let matches: Bool
            if boundary == "terminal" || boundary == "beforeTerminal" {
                matches = (row?["terminal"] as? [String: Any])?["outcome"] as? String == "succeeded"
            } else { matches = row?[boundary] as? Bool == true }
            if enabled.withLock({ $0 }) && matches && boundary == "beforeTerminal" {
                crashed.withLock { $0 = true }; throw CocoaError(.fileWriteUnknown)
            }
            try RecurringStore.durableWrite(data, to: file)
            if enabled.withLock({ $0 }) && matches {
                crashed.withLock { $0 = true }; throw CocoaError(.fileWriteUnknown)
            }
        })
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try h.add()
        await h.executor.execute()
        #expect(crashed.withLock { $0 })
        if boundary == "beforeTerminal" {
            #expect(h.controller.state == .storageBlocked)
            #expect(h.saved?.receipt != nil)
            #expect(try h.journal.load()[s.id]?.terminal == nil)
            h.controller.startNewPaymentAfterHistoryCheck(confirmed: true)
            #expect(h.controller.locked && h.saved?.receipt != nil, "Override cannot erase a proven receipt before its occurrence handoff")
        }
        enabled.withLock { $0 = false }
        try await assertPaidExactlyOnce(h, id: s.id)
    }
    @Test func deathBetweenLedgerWriteAheadAndSettingsWriteReplaysHistoryOnce() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try h.add()
        h.afterPay = { h.storeWriteFails.withLock { $0 = true } }
        await h.executor.execute()
        let e = try #require(h.journal.load()[s.id])
        #expect(e.consumed == true && e.historyApplied != true)
        h.storeWriteFails.withLock { $0 = false }
        try await assertPaidExactlyOnce(h, id: s.id)
    }
    @Test func deathAfterPolicyRowBeforeHistoryFlagUsesLedgerIdempotencyKey() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try h.add(); await h.executor.execute()
        var e = try #require(h.journal.load()[s.id])
        e.historyApplied = false; e.policyApplied = false; e.released = false
        try h.journal.save(e)
        try await assertPaidExactlyOnce(h, id: s.id)
    }
    @Test func completedWriteAheadCannotRepairLaterRollbackOrUndoPause() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try h.add(); await h.executor.timeChanged()
        let before = try #require(h.settings.string(forKey: RecurringStore.key))
        await h.executor.execute(); try await h.executor.pause(s.id)
        try h.settings.setDurably(before, forKey: RecurringStore.key)
        await restart(h)
        await h.executor.execute(); await h.executor.checkStatus(s.id)
        #expect(try h.store.load().schedules[s.id]?.status == "needs_attention")
        #expect(h.calls.withLock { $0 } == 1)
    }
}
