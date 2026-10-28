import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

struct WalletSyncTests {
    @Test(.regtestStack) func suspensionOrCloseRevokesLaterStagesAndDrainsThePass() async throws {
        for suspend in [false, true] {
            let root = FileManager.default.temporaryDirectory.appending(path: "sync-suspension-\(UUID())")
            defer { try? FileManager.default.removeItem(at: root) }
            let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
            let session = WalletSession(variant: .regtest, location: location)
            let settings = try SettingsStorage(rootPath: root.appending(path: "mmkv").path, instanceID: UUID().uuidString)
            let lifecycle = WalletLifecycle(dependencies: .init(
                session: session, location: location,
                seed: KeychainPassword(service: "sync-seed-\(UUID())", account: "test"),
                authToken: KeychainPassword(service: "sync-token-\(UUID())", account: "test"),
                settings: settings, scratch: ScratchSpace(root: root.appending(path: "scratch")),
                appGroup: nil, mutation: WalletMutationGate(), deregister: {}, walletChanged: {}))
            try await session.createNew(mnemonic: generateMnemonic(), runDaemon: false, appVersion: "test")
            let gate = RecurringReservationTests.Gate()
            let stages = RecurringExecutorTests.Locked<[Int]>([])
            let closed = RecurringExecutorTests.Locked(false)
            let work = Task {
                try await session.sync(stages: [
                    { _ in stages.withLock { $0.append(1) }; await gate.wait() },
                    { _ in stages.withLock { $0.append(2) } },
                    { _ in stages.withLock { $0.append(3) } },
                ])
            }
            while !(await gate.entered) { await Task.yield() }
            let close = Task {
                if suspend { try await lifecycle.suspend() } else { try await session.closeSettled() }
                closed.withLock { $0 = true }
            }
            let deadline = ContinuousClock.now + .seconds(5)
            while await session.isOpen, ContinuousClock.now < deadline { await Task.yield() }
            #expect(await !session.isOpen)
            if suspend { #expect(settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended == true) }
            #expect(!closed.withLock { $0 })
            await #expect(throws: WalletSession.OpenError.busy) { try await session.reserve() }
            await gate.release()
            await #expect(throws: CancellationError.self) { try await work.value }
            try await close.value
            #expect(stages.withLock { $0 } == [1])
            #expect(closed.withLock { $0 })
            let reservation = try await session.reserve()
            await session.release(reservation)
        }
    }

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

    @Test func fullSyncWaitsForAPartialPassInsteadOfDropping() async throws {
        let flight = WalletSync(), gate = RecurringReservationTests.Gate()
        let calls = RecurringExecutorTests.Locked(0)
        let partial = Task { try await flight.serialized { await gate.wait() } }
        while !(await gate.entered) { await Task.yield() }
        let full = Task { try await flight.run([{ calls.withLock { $0 += 1 } }]) }
        try await Task.sleep(for: .milliseconds(20))
        #expect(calls.withLock { $0 } == 0)
        await gate.release(); try await partial.value; try await full.value
        #expect(calls.withLock { $0 } == 1)
    }

    /// A partial pass stalls; full requests queued behind it while holding their caller's
    /// gates withdraw promptly once cancelled or revoked, without running.
    @Test func aQueuedFullSyncWithdrawsWhenCancelledOrRevoked() async throws {
        let flight = WalletSync(), gate = RecurringReservationTests.Gate()
        let partial = Task { try await flight.serialized { await gate.wait() } }
        while !(await gate.entered) { await Task.yield() }
        let ran = RecurringExecutorTests.Locked(false), held = RecurringExecutorTests.Locked(0)
        func request() async throws {
            held.withLock { $0 += 1 }
            defer { held.withLock { $0 -= 1 } }
            try await flight.run([{ ran.withLock { $0 = true } }])
        }
        let cancelled = Task { try await request() }
        let budget = RecurringSubmissionBudget()
        let revoked = Task { try await RecurringSubmissionBudget.$current.withValue(budget) { try await request() } }
        try await Task.sleep(for: .milliseconds(30))
        #expect(held.withLock { $0 } == 2)
        let start = ContinuousClock.now
        cancelled.cancel()
        budget.revoke()
        await #expect(throws: CancellationError.self) { try await cancelled.value }
        await #expect(throws: CancellationError.self) { try await revoked.value }
        #expect(ContinuousClock.now - start < .milliseconds(500))
        #expect(held.withLock { $0 } == 0 && !ran.withLock { $0 })
        await gate.release(); try await partial.value
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
