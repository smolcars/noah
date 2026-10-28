import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringOverrideSettlementTests {
    @Test(arguments: [false, true]) func recoveredHandoffDuringOverrideMustApplyPaidHistory(journalWriteFails: Bool) async throws {
        let firstWrite = RecurringExecutorTests.Locked(true)
        let h = try RecurringExecutorTests.Harness(journalWrite: { data, file in
            if journalWriteFails {
                let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                let row = (root?["attempts"] as? [String: [String: Any]])?.values.first
                if (row?["terminal"] as? [String: Any])?["outcome"] as? String == "succeeded",
                   firstWrite.withLock({ value in let old = value; value = false; return old }) {
                    throw CocoaError(.fileWriteUnknown)
                }
            }
            try RecurringStore.durableWrite(data, to: file)
        })
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try h.add()
        let sink = try #require(h.controller.persistRecurringEvidence)
        var fail = true
        h.controller.persistRecurringEvidence = { attempt, generation, walletGeneration, outcome, message in
            if !journalWriteFails && fail { fail = false; throw CocoaError(.fileReadUnknown) }
            try sink(attempt, generation, walletGeneration, outcome, message)
        }
        await h.executor.execute()
        #expect(h.controller.state == .storageBlocked)
        #expect(h.saved?.receipt != nil)
        #expect(try h.journal.load()[s.id]?.terminal == nil)
        try await h.executor.overrideAndResume(s.id, confirmed: true)
        h.rebuild()
        await h.executor.execute(); await h.executor.checkStatus(s.id)
        let e = try #require(h.journal.load()[s.id])
        #expect(e.terminal?.outcome == .succeeded)
        #expect(e.consumed == true && e.historyApplied == true && e.released == true)
        #expect(h.calls.withLock { $0 } == 1)
        #expect(try h.store.load().schedules[s.id]?.occurrencesPaid == 1)
        #expect(try h.store.load().schedules[s.id]?.runs.count(where: { $0.status == "success" }) == 1)
    }
    private func failInitialHandoff(_ h: RecurringExecutorTests.Harness) throws {
        let sink = try #require(h.controller.persistRecurringEvidence)
        var first = true
        h.controller.persistRecurringEvidence = { attempt, generation, walletGeneration, outcome, message in
            if first { first = false; throw CocoaError(.fileReadUnknown) }
            try sink(attempt, generation, walletGeneration, outcome, message)
        }
    }
    private func recover(_ h: RecurringExecutorTests.Harness, id: String) async {
        h.storeWriteFails.withLock { $0 = false }
        h.store = RecurringStore(settings: h.settings)
        h.rebuild()
        h.controller.reloadForWalletChange()
        for _ in 0..<3 { await h.executor.execute(); await h.executor.checkStatus(id) }
    }
    @Test(arguments: ["alreadyPaid", "failed"])
    func definitiveOutcomesRepairedByCleanupUseNormalSettlement(outcome: String) async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try h.add(); try failInitialHandoff(h)
        h.afterPay = {
            if outcome == "alreadyPaid" { throw SendAlreadyPaid(receipt: .init(amount: s.amountSat, proof: .submitReturnedArkSuccess)) }
            throw SendNotSubmitted(reason: .fundsChanged)
        }
        await h.executor.execute()
        #expect(h.controller.state == .storageBlocked)
        #expect(try h.journal.load()[s.id]?.terminal == nil)
        try await h.executor.overrideAndResume(s.id, confirmed: true)
        await recover(h, id: s.id)
        let e = try #require(h.journal.load()[s.id])
        #expect(e.terminal?.outcome == (outcome == "alreadyPaid" ? .alreadyPaid : .failed))
        #expect(e.resolved && e.historyApplied == true && e.released == true)
        #expect((e.consumed == true) == (outcome == "alreadyPaid"))
        #expect(try h.store.load().schedules[s.id]?.occurrencesPaid == 0)
        #expect(try h.store.load().schedules[s.id]?.runs.count(where: { $0.status == "failed" }) == 1)
        #expect(h.calls.withLock { $0 } == 1)
    }
    @Test(arguments: ["terminal", "sendCleared", "consumed", "policy", "historyApplied", "released", "resumed"])
    func deathDuringOverrideRecoversPaidHistoryExactlyOnce(boundary: String) async throws {
        let enabled = RecurringExecutorTests.Locked(false)
        let crashed = RecurringExecutorTests.Locked(false)
        let storeFailure = RecurringExecutorTests.Locked<RecurringExecutorTests.Locked<Bool>?>(nil)
        let h = try RecurringExecutorTests.Harness(journalWrite: { data, file in
            let root = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
            let row = (root["attempts"] as? [String: [String: Any]])?.values.first
            let matches: Bool
            switch boundary {
            case "terminal": matches = row?["terminal"] != nil
            case "sendCleared", "consumed": matches = row?["consumed"] as? Bool == true
            case "policy", "historyApplied": matches = row?["historyApplied"] as? Bool == true
            default: matches = row?["released"] as? Bool == true
            }
            if enabled.withLock({ $0 }) && matches && !crashed.withLock({ $0 }) {
                crashed.withLock { $0 = true }
                if boundary == "resumed" {
                    try RecurringStore.durableWrite(data, to: file)
                    storeFailure.withLock { $0 }?.withLock { $0 = true }
                    return // The next policy write dies after its ledger write-ahead.
                }
                if boundary != "sendCleared" && boundary != "policy" { try RecurringStore.durableWrite(data, to: file) }
                throw CocoaError(.fileWriteUnknown)
            }
            try RecurringStore.durableWrite(data, to: file)
        })
        defer { try? FileManager.default.removeItem(at: h.directory) }
        storeFailure.withLock { $0 = h.storeWriteFails }
        let s = try h.add(); try failInitialHandoff(h)
        await h.executor.execute()
        #expect(h.controller.state == .storageBlocked)
        enabled.withLock { $0 = true }
        _ = try? await h.executor.overrideAndResume(s.id, confirmed: true)
        #expect(crashed.withLock { $0 }, "Boundary must actually interrupt the override")
        enabled.withLock { $0 = false }
        await recover(h, id: s.id)
        let row = try #require(h.store.load().schedules[s.id])
        #expect(row.occurrencesPaid == 1)
        #expect(row.runs.count(where: { $0.status == "success" }) == 1)
        let e = try #require(h.journal.load()[s.id])
        #expect(e.consumed == true && e.historyApplied == true && e.released == true && e.terminal?.outcome == .succeeded)
        #expect(h.calls.withLock { $0 } == 1)
        await recover(h, id: s.id)
        #expect(try h.store.load().schedules[s.id]?.occurrencesPaid == 1)
    }
}
