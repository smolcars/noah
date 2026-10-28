import Foundation
@testable import Noah
import Testing

@MainActor struct RecurringJournalMigrationRaceTests {
    @Test func separateStartupEntryPointsCannotOverwriteDurableProof() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let s = try h.add(), binding = h.owner.withLock { $0 }, now = h.now
        let entry = RecurringJournal.Entry(binding: binding, scheduleID: s.id, plan: s.plan(now: now), markedAt: now, policyRevision: s.nativeRevision)
        try h.journal.save(entry)
        let quoted = h.quoted(s, fees: RecurringFeePolicy.fallback)
        let payment = quoted.payment, quote = quoted.quote
        await h.controller.submitAndWait(payment, quote: quote, recurringKey: entry.key, beforeSubmit: {})
        let paid = try #require(h.saved)
        #expect(paid.receipt != nil)
        try h.journal.save(entry)
        var root = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: h.journal.file)) as? [String: Any])
        root["version"] = 2
        try JSONSerialization.data(withJSONObject: root).write(to: h.journal.file)

        try race(file: h.journal.file, paid: paid, id: s.id, binding: binding, now: now)
        #expect(h.calls.withLock { $0 } == 1)
    }
    private func race(file: URL, paid: SendPaymentController.Attempt, id: String, binding: RecurringStore.Binding, now: Double) throws {
        let ready = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        let started = DispatchSemaphore(value: 0), finished = DispatchSemaphore(value: 0)
        let group = DispatchGroup(), errors = RecurringExecutorTests.Locked<[String]>([])
        let executorJournal = RecurringJournal(file: file, persist: { data, file in
            ready.signal()
            guard release.wait(timeout: .now() + 10) == .success else { throw CocoaError(.fileWriteUnknown) }
            try RecurringStore.durableWrite(data, to: file)
        })
        let sendJournal = RecurringJournal(file: file)
        defer { release.signal(); _ = group.wait(timeout: .now() + 10) }
        group.enter()
        DispatchQueue.global().async {
            defer { group.leave() }
            do { _ = try executorJournal.load() } catch { errors.withLock { $0.append(String(describing: error)) } }
        }
        #expect(ready.wait(timeout: .now() + 10) == .success)
        group.enter()
        DispatchQueue.global().async {
            defer { group.leave(); finished.signal() }
            started.signal()
            do { try sendJournal.record(paid, generations: .init(generation: 1, walletGeneration: 1), message: nil, binding: binding, now: now) }
            catch { errors.withLock { $0.append(String(describing: error)) } }
        }
        #expect(started.wait(timeout: .now() + 10) == .success)
        // The static recursive lock must keep handoff behind the unfinished migration.
        #expect(finished.wait(timeout: .now() + 0.1) == .timedOut)
        release.signal()
        #expect(group.wait(timeout: .now() + 10) == .success)
        #expect(errors.withLock { $0.isEmpty })
        #expect(try sendJournal.load()[id]?.terminal?.outcome == .succeeded)
    }
}
