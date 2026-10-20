import Foundation
import Testing
@testable import Noah

struct ForegroundWalletSyncTests {
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
