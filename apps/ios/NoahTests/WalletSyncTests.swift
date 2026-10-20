import Foundation
import Testing
@testable import Noah

struct WalletSyncTests {
    struct Failed: Error, Equatable { let stage: Int }

    @Test func failedSyncStillAttemptsOtherWalletAndLightningClaims() async {
        let stages = RecurringExecutorTests.Locked<[Int]>([])
        do {
            try await WalletSync().run([
                { stages.withLock { $0.append(1) }; throw Failed(stage: 1) },
                { stages.withLock { $0.append(2) }; throw Failed(stage: 2) },
                { stages.withLock { $0.append(3) } },
            ])
            Issue.record("Expected the first sync failure")
        } catch { #expect(error as? Failed == Failed(stage: 1)) }
        #expect(stages.withLock { $0 } == [1, 2, 3])
    }

    @Test func overlappingSyncSkipsUntilCountedPassSettles() async throws {
        let flight = WalletSync(), gate = RecurringReservationTests.Gate()
        let calls = RecurringExecutorTests.Locked(0)
        let first = Task { try await flight.run([{ calls.withLock { $0 += 1 }; await gate.wait() }]) }
        while !(await gate.entered) { await Task.yield() }
        try await flight.run([{ calls.withLock { $0 += 1 } }])
        #expect(calls.withLock { $0 } == 1)
        await gate.release(); try await first.value
        try await flight.run([{ calls.withLock { $0 += 1 } }])
        #expect(calls.withLock { $0 } == 2)
    }

    @Test func reconciliationWaitsForForegroundSyncInsteadOfReadingStaleState() async throws {
        let flight = WalletSync(), gate = RecurringReservationTests.Gate()
        let reconciled = RecurringExecutorTests.Locked(false)
        let first = Task { try await flight.run([{ await gate.wait() }]) }
        while !(await gate.entered) { await Task.yield() }
        let second = Task { try await flight.serialized { reconciled.withLock { $0 = true } } }
        try await Task.sleep(for: .milliseconds(20))
        #expect(!reconciled.withLock { $0 })
        await gate.release(); try await first.value; try await second.value
        #expect(reconciled.withLock { $0 })
    }

    @Test func foregroundRevocationStopsBeforeAnotherStageButDrainsAdmittedWork() async throws {
        let flight = WalletSync(), gate = RecurringReservationTests.Gate(), budget = RecurringSubmissionBudget()
        let claimed = RecurringExecutorTests.Locked(false)
        let work = Task {
            try await RecurringSubmissionBudget.$current.withValue(budget) {
                try await flight.run([{ await gate.wait() }, { claimed.withLock { $0 = true } }])
            }
        }
        while !(await gate.entered) { await Task.yield() }
        budget.revoke()
        await gate.release()
        await #expect(throws: CancellationError.self) { try await work.value }
        #expect(!claimed.withLock { $0 })
        try await flight.run([{ claimed.withLock { $0 = true } }])
        #expect(claimed.withLock { $0 })
    }
}
