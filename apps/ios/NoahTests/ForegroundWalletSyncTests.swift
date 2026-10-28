import Foundation
@testable import Noah
import Testing

struct ForegroundWalletSyncTests {
    @Test @MainActor func foregroundReentryQueuedBehindWalletAccessRechecksSceneState() async {
        let configuration = WalletMutationGate(), mutation = WalletMutationGate()
        let foreground = ForegroundWalletSync(configuration: configuration, mutation: mutation)
        let held = RecurringReservationTests.Gate()
        let active = RecurringExecutorTests.Locked(true)
        let stages = RecurringExecutorTests.Locked(0)
        let holder = Task { await configuration.withLock { await held.wait() } }
        while !(await held.entered) { await Task.yield() }
        let work = Task {
            await ForegroundExecution.run(isActive: { active.withLock { $0 } }) {
                try? await foreground.run {
                    try await WalletSync().run([{ stages.withLock { $0 += 1 } }])
                }
            }
        }
        let deadline = ContinuousClock.now + .seconds(5)
        while await configuration.waitingCount == 0, ContinuousClock.now < deadline { await Task.yield() }
        #expect(await configuration.waitingCount == 1)
        active.withLock { $0 = false }
        await held.release(); await holder.value; await work.value
        #expect(stages.withLock { $0 } == 0)
    }

    @Test func foregroundReentryDropsBeforeWaitingOnEitherOuterGate() async throws {
        for backgroundGate in [false, true] {
            let configuration = WalletMutationGate(), mutation = WalletMutationGate()
            let foreground = ForegroundWalletSync(configuration: configuration, mutation: mutation)
            let flight = WalletSync(), gate = RecurringReservationTests.Gate()
            let held = RecurringReservationTests.Gate()
            let passes = RecurringExecutorTests.Locked(0)
            let holder = Task { await (backgroundGate ? configuration : mutation).withLock { await held.wait() } }
            while !(await held.entered) { await Task.yield() }
            let first = Task {
                try await foreground.run { try await flight.run([{ passes.withLock { $0 += 1 }; await gate.wait() }]) }
            }
            let deadline = ContinuousClock.now + .seconds(5)
            while await configuration.waitingCount + mutation.waitingCount == 0, ContinuousClock.now < deadline { await Task.yield() }
            let returned = RecurringExecutorTests.Locked(false)
            let second = Task {
                try await foreground.run { try await flight.run([{ passes.withLock { $0 += 1 } }]) }
                returned.withLock { $0 = true }
            }
            while !returned.withLock({ $0 }), await configuration.waitingCount + mutation.waitingCount < 2,
                  ContinuousClock.now < deadline { await Task.yield() }
            await held.release(); await holder.value
            while !(await gate.entered) { await Task.yield() }
            await gate.release(); try await first.value; try await second.value
            #expect(passes.withLock { $0 } == 1)
            try await foreground.run { try await flight.run([{ passes.withLock { $0 += 1 } }]) }
            #expect(passes.withLock { $0 } == 2)
        }
    }
}
