import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// The foreground pass does what bark's daemon did with the app open: delegated maintenance once per
/// foreground entry, never an interactive round, and exit progress while an exit is live.
struct ForegroundPassTests {
    typealias Locked = RecurringExecutorTests.Locked

    private static func pass(_ calls: Locked<[String]>, due: Locked<Bool>,
                             progressExits: @escaping @Sendable () async throws -> Void = {}) -> WalletSession.ForegroundPass {
        .init(syncOnchain: { calls.withLock { $0.append("onchain") } },
              sync: { calls.withLock { $0.append("sync") } },
              maintenance: { calls.withLock { $0.append("maintenance") } },
              claimLightning: { calls.withLock { $0.append("lightning") } },
              progressExits: { calls.withLock { $0.append("exits") }; try await progressExits() },
              takeMaintenance: { due.withLock { taken in defer { taken = false }; return taken } },
              maintenanceFailed: { due.withLock { $0 = true } })
    }

    @Test func foregroundEntryRunsDelegatedMaintenanceOnceInPlaceOfTheSync() async throws {
        let calls = Locked<[String]>([]), due = Locked(true)
        let stages = Self.pass(calls, due: due).stages
        try await WalletSync().run(stages)
        try await WalletSync().run(stages)
        #expect(calls.withLock { $0 } == ["onchain", "maintenance", "lightning", "exits", "onchain", "sync", "lightning", "exits"])
        // The next entry: once more.
        due.withLock { $0 = true }
        calls.withLock { $0 = [] }
        try await WalletSync().run(stages)
        #expect(calls.withLock { $0 } == ["onchain", "maintenance", "lightning", "exits"])
    }

    @Test func aFailingStageDoesNotStopExitProgress() async throws {
        struct Boom: Error {}
        let calls = Locked<[String]>([]), due = Locked(true)
        var pass = Self.pass(calls, due: due)
        pass.maintenance = { throw Boom() }
        await #expect(throws: Boom.self) { try await WalletSync().run(pass.stages) }
        #expect(calls.withLock { $0 } == ["onchain", "lightning", "exits"])
    }

    /// An entry's maintenance that fails (opened offline) runs again on each later pass, one per 60 s tick, until it
    /// completes: bark's sync never moves an interactive attempt an older version left, so its VTXOs stay locked till then.
    @Test func aFailedMaintenanceRunsOnEachLaterPassUntilItCompletes() async throws {
        struct Offline: Error {}
        let calls = Locked<[String]>([]), due = Locked(true), offline = Locked(true)
        var pass = Self.pass(calls, due: due)
        pass.maintenance = {
            calls.withLock { $0.append("maintenance") }
            if offline.withLock({ $0 }) { throw Offline() }
        }
        for _ in 0..<2 { await #expect(throws: Offline.self) { try await WalletSync().run(pass.stages) } }
        #expect(calls.withLock { $0 } == Array(repeating: ["onchain", "maintenance", "lightning", "exits"], count: 2).flatMap(\.self))
        offline.withLock { $0 = false }
        calls.withLock { $0 = [] }
        try await WalletSync().run(pass.stages)
        try await WalletSync().run(pass.stages)
        #expect(calls.withLock { $0 } == ["onchain", "maintenance", "lightning", "exits", "onchain", "sync", "lightning", "exits"])
    }

    @Test func eachTickMovesExitsOnlyWhileOneIsLive() async throws {
        let session = WalletSession()
        let rates = BackgroundTaskTests.ExitCalls()
        let live = ExitVtxo(vtxoId: "v", amountSats: 31_000, state: .start(tipHeight: 100), isClaimable: false)
        let done = ExitVtxo(vtxoId: "v", amountSats: 31_000, state: .canceled(tipHeight: 120), isClaimable: false)
        let exits = Locked([live])
        let access = WalletSession.ExitProgressAccess(
            walletID: "w", exits: { exits.withLock { $0 } }, progress: { rates.add($0); return [] }, models: { [] }, tip: { 120 })
        let stages = Self.pass(Locked([]), due: Locked(false)) {
            _ = try await session.progressPendingExits(access: access)
        }.stages
        try await WalletSync().run(stages)
        try await WalletSync().run(stages)
        #expect(rates.rates == [nil, nil])
        #expect(await session.exitChecks["w"]?.failed == false)
        exits.withLock { $0 = [done] }
        try await WalletSync().run(stages)
        #expect(rates.rates == [nil, nil])
        exits.withLock { $0 = [] }
        try await WalletSync().run(stages)
        #expect(rates.rates == [nil, nil])
    }

    /// A pass queued behind Check the chain doesn't call bark if the app left while it waited: its budget is revoked,
    /// or the scene is no longer active (`ForegroundExecution`).
    @Test(arguments: [false, true]) func aPassQueuedBehindCheckTheChainNeverProgressesOnceTheAppHasLeft(sceneOnly: Bool) async throws {
        let session = WalletSession()
        let rates = Locked<[UInt64?]>([]), active = Locked(true)
        let check = RecurringReservationTests.Gate()
        let live = ExitVtxo(vtxoId: "v", amountSats: 31_000, state: .start(tipHeight: 100), isClaimable: false)
        let access = WalletSession.ExitProgressAccess(
            walletID: "w", exits: { [live] },
            progress: { rate in
                rates.withLock { $0.append(rate) }
                if rate != nil { await check.wait() }
                return []
            },
            models: { [] }, tip: { 120 })
        let manual = Task { try await session.progressExits(12, access: access) }
        let deadline = ContinuousClock.now + .seconds(5)
        while !(await check.entered), ContinuousClock.now < deadline { await Task.yield() }
        try #require(await check.entered)
        let budget = RecurringSubmissionBudget()
        let tick = Task {
            try await ForegroundExecution.$admission.withValue({ active.withLock { $0 } }) {
                try await RecurringSubmissionBudget.$current.withValue(budget) { try await session.progressPendingExits(access: access) }
            }
        }
        while await session.exitProgressGate.waitingCount == 0, ContinuousClock.now < deadline { await Task.yield() }
        try #require(await session.exitProgressGate.waitingCount == 1)
        if sceneOnly { active.withLock { $0 = false } } else { budget.revoke() }
        await check.release()
        _ = try await manual.value
        await #expect(throws: CancellationError.self) { _ = try await tick.value }
        #expect(rates.withLock { $0 } == [12])
    }

    /// Check the chain, the foreground pass and background jobs each call bark; one at a time.
    @Test func theAppsExitProgressCallsNeverOverlap() async throws {
        let session = WalletSession()
        let running = Locked(0), peak = Locked(0)
        let first = RecurringReservationTests.Gate()
        let access = WalletSession.ExitProgressAccess(
            walletID: "w", exits: { [] },
            progress: { rate in
                let now = running.withLock { $0 += 1; return $0 }
                peak.withLock { $0 = max($0, now) }
                if rate == nil { await first.wait() }
                running.withLock { $0 -= 1 }
                return []
            },
            models: { [] }, tip: { 120 })
        let tick = Task { try await session.progressExits(nil, access: access) }
        let deadline = ContinuousClock.now + .seconds(5)
        while !(await first.entered), ContinuousClock.now < deadline { await Task.yield() }
        try #require(await first.entered)
        let check = Task { try await session.progressExits(12, access: access) }
        while await session.exitProgressGate.waitingCount == 0, ContinuousClock.now < deadline { await Task.yield() }
        #expect(await session.exitProgressGate.waitingCount == 1)
        await first.release()
        _ = try await tick.value
        _ = try await check.value
        #expect(peak.withLock { $0 } == 1)
    }
}
