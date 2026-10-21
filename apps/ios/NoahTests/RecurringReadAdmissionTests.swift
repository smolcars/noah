import Foundation
import Testing
@testable import Noah

@MainActor struct RecurringReadAdmissionTests {
    @Test(arguments: [0, 1, 2])
    func revocationDuringScheduleQuotedPolicyOrMarkerReadStopsAdmission(offset: Int) async throws {
        let lastPrequoteRead = try await run(revokeRead: nil).readAtQuote
        let result = try await run(revokeRead: lastPrequoteRead + offset)
        #expect(result.quotes == (offset == 0 ? 0 : 1))
        #expect(result.payments == 0)
        #expect(!result.marked)
    }

    private func run(revokeRead: Int?) async throws -> (readAtQuote: Int, quotes: Int, payments: Int, marked: Bool) {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let budget = RecurringSubmissionBudget()
        let reads = RecurringExecutorTests.Locked(0)
        let quotes = RecurringExecutorTests.Locked(0)
        let readAtQuote = RecurringExecutorTests.Locked(0)
        let owner = h.owner, clock = h.clock
        let executor = RecurringExecutor(dependencies: .init(store: h.store, journal: h.journal,
            controller: h.controller, ensureOpen: {}, binding: { owner.withLock { $0 } }, allowed: {
                let read = reads.withLock { $0 += 1; return $0 }
                if read == revokeRead { budget.revoke() }
            }, quote: { schedule in
                quotes.withLock { $0 += 1 }
                readAtQuote.withLock { $0 = reads.withLock { $0 } }
                if revokeRead == nil { budget.revoke() }
                return await h.quoted(schedule, fees: RecurringFeePolicy.fallback)
            }, now: { clock.withLock { $0 } }, mutation: WalletMutationGate()))
        await RecurringSubmissionBudget.$current.withValue(budget) { await executor.execute() }
        #expect(try h.store.load().schedules[schedule.id]?.consecutiveFailures == 0)
        return (readAtQuote.withLock { $0 }, quotes.withLock { $0 }, h.calls.withLock { $0 },
                try h.journal.load()[schedule.id] != nil)
    }
}
