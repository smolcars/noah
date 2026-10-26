import Foundation
import Testing
import UIKit
@testable import Noah

@MainActor struct RecurringForegroundRunnerTests {
    @MainActor final class Minutes {
        var intervals: [Duration] = []
        private var waiters: [UUID: CheckedContinuation<Void, any Error>] = [:]
        var waiting: Bool { !waiters.isEmpty }
        func sleep(_ interval: Duration) async throws {
            let id = UUID()
            try await withTaskCancellationHandler {
                try Task.checkCancellation()
                try await withCheckedThrowingContinuation { continuation in
                    intervals.append(interval)
                    waiters[id] = continuation
                }
            } onCancel: {
                Task { @MainActor in self.waiters.removeValue(forKey: id)?.resume(throwing: CancellationError()) }
            }
        }
        func tick() {
            let pending = waiters
            waiters.removeAll()
            for waiter in pending.values { waiter.resume() }
        }
    }
    private func waitUntil(_ condition: @MainActor () async -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(5)
        while !(await condition()), ContinuousClock.now < deadline { await Task.yield() }
        try #require(await condition())
    }
    @Test func minuteTicksPayNewOccurrencesWhileSceneRemainsActive() async throws {
        let h = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var schedule = try h.add()
        schedule.startAt = h.now + 60_000; schedule.nextRunAt = schedule.startAt
        try h.store.upsert(schedule)
        let minutes = Minutes(), center = NotificationCenter()
        let runner = RecurringForegroundRunner(center: center, sleep: { try await minutes.sleep($0) }, isActive: { true }) { await h.executor.execute() }
        defer { runner.stop() }
        runner.start(); runner.start()
        try await waitUntil { minutes.waiting }
        #expect(minutes.intervals == [.seconds(60)])
        #expect(h.calls.withLock { $0 } == 0)
        h.clock.withLock { $0 += 60_000 }; minutes.tick()
        try await waitUntil { minutes.intervals.count == 2 && minutes.waiting }
        #expect(h.calls.withLock { $0 } == 1)
        h.clock.withLock { $0 += 86_400_000 }; minutes.tick()
        try await waitUntil { minutes.intervals.count == 3 && minutes.waiting }
        #expect(h.calls.withLock { $0 } == 2)
        center.post(name: UIScene.willDeactivateNotification, object: nil)
        try await waitUntil { !minutes.waiting }
        minutes.tick()
        #expect(h.calls.withLock { $0 } == 2)
        runner.start()
        try await waitUntil { minutes.intervals.count == 4 && minutes.waiting }
        #expect(minutes.intervals.allSatisfy { $0 == .seconds(60) })
        runner.stop()
        try await waitUntil { !minutes.waiting }
    }
    @Test func timerStartedDuringReentryOutlivesTheActivationBudget() async throws {
        let minutes = Minutes(), activation = RecurringSubmissionBudget()
        var executions = 0
        let runner = RecurringForegroundRunner(center: NotificationCenter(), sleep: { try await minutes.sleep($0) },
                                               isActive: { true }) { executions += 1 }
        defer { runner.stop() }
        RecurringSubmissionBudget.$current.withValue(activation) { runner.start() }
        try await waitUntil { minutes.waiting }
        activation.revoke()
        minutes.tick()
        try await waitUntil { minutes.intervals.count == 2 && minutes.waiting }
        #expect(executions == 1)
        runner.stop()
        try await waitUntil { !minutes.waiting }
    }

    @Test func suspendedMinuteWakesInBackgroundWithoutSyncOrRecurring() async throws {
        let h = try RecurringExecutorTests.Harness(); _ = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let minutes = Minutes()
        let active = RecurringExecutorTests.Locked(true)
        let stages = RecurringExecutorTests.Locked(0)
        let runner = RecurringForegroundRunner(center: NotificationCenter(), sleep: { try await minutes.sleep($0) },
                                               isActive: { active.withLock { $0 } }) {
            try? await WalletSync().run([{ stages.withLock { $0 += 1 } }, { stages.withLock { $0 += 1 } }, { stages.withLock { $0 += 1 } }])
            await h.executor.execute()
        }
        defer { runner.stop() }
        runner.start()
        try await waitUntil { minutes.waiting }
        // A process wake is not a scene activation. No new lifecycle notification is delivered.
        active.withLock { $0 = false }
        minutes.tick()
        try await waitUntil { !runner.isRunning || minutes.intervals.count == 2 }
        #expect(stages.withLock { $0 } == 0)
        #expect(h.calls.withLock { $0 } == 0)
        #expect(!runner.isRunning)
    }

    @Test func sceneStateIsRecheckedAfterAdmittedSyncSettlesWithoutANotification() async throws {
        let h = try RecurringExecutorTests.Harness(); _ = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let gate = RecurringReservationTests.Gate()
        let active = RecurringExecutorTests.Locked(true)
        let stages = RecurringExecutorTests.Locked<[Int]>([])
        let work = Task {
            await ForegroundExecution.run(isActive: { active.withLock { $0 } }) {
                try? await WalletSync().run([
                    { stages.withLock { $0.append(1) }; await gate.wait() },
                    { stages.withLock { $0.append(2) } },
                    { stages.withLock { $0.append(3) } },
                ])
                await h.executor.execute()
            }
        }
        try await waitUntil { await gate.entered }
        active.withLock { $0 = false }
        await gate.release()
        await work.value
        #expect(stages.withLock { $0 } == [1])
        #expect(h.calls.withLock { $0 } == 0)
    }

    @Test func losingSceneAdmissionDuringQuoteDoesNotMarkOrSubmitAnOccurrence() async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let gate = RecurringReservationTests.Gate()
        let active = RecurringExecutorTests.Locked(true)
        h.preflight = { await gate.wait() }
        let work = Task {
            await ForegroundExecution.run(isActive: { active.withLock { $0 } }) { await h.executor.execute() }
        }
        try await waitUntil { await gate.entered }
        active.withLock { $0 = false }
        await gate.release(); await work.value
        let saved = try #require(h.store.load().schedules[schedule.id])
        #expect(h.calls.withLock { $0 } == 0)
        #expect(saved.inFlight == nil && saved.runs.isEmpty && saved.status == "active")
        #expect(saved.lastError == nil)
    }

    @Test func backgroundJobDoesNotInheritAStaleForegroundSceneRequirement() async throws {
        let h = try RecurringExecutorTests.Harness(); _ = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let result = await ForegroundExecution.$admission.withValue({ false }) {
            await AppDelegate.completeWithin(.seconds(5)) {
                try await ForegroundExecution.checkActive()
                await h.executor.execute()
            }
        }
        #expect(result == .newData)
        #expect(h.calls.withLock { $0 } == 1)
    }

    @Test func sceneDeactivationWinsOverAnAlreadyWokenTick() async throws {
        let minutes = Minutes(), center = NotificationCenter()
        var checks = 0
        let runner = RecurringForegroundRunner(center: center, sleep: { try await minutes.sleep($0) }, isActive: { true }) { checks += 1 }
        defer { runner.stop() }
        runner.start()
        try await waitUntil { minutes.waiting }
        minutes.tick()
        // The sleeping task is runnable but cannot resume on MainActor before this stop.
        center.post(name: UIScene.willDeactivateNotification, object: nil)
        await Task.yield()
        #expect(checks == 0)
        runner.start()
        try await waitUntil { minutes.waiting }
        minutes.tick()
        try await waitUntil { checks == 1 && minutes.waiting }
        runner.stop()
        try await waitUntil { !minutes.waiting }
    }
    @Test func timerPushAndScreenOpenJoinOneExecution() async throws {
        let h = try RecurringExecutorTests.Harness(); _ = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        let minutes = Minutes(), gate = RecurringReservationTests.Gate()
        var quotes = 0
        h.preflight = { quotes += 1; await gate.wait() }
        let runner = RecurringForegroundRunner(center: NotificationCenter(), sleep: { try await minutes.sleep($0) }, isActive: { true }) { await h.executor.execute() }
        defer { runner.stop() }
        runner.start()
        try await waitUntil { minutes.waiting }
        minutes.tick()
        try await waitUntil { await gate.entered }
        var joined = 0
        let push = Task { joined += 1; await h.executor.execute() }
        let screen = Task { joined += 1; await h.executor.execute() }
        try await waitUntil { joined == 2 }
        await gate.release()
        await push.value; await screen.value
        try await waitUntil { minutes.waiting }
        #expect(quotes == 1 && h.calls.withLock { $0 } == 1)
        minutes.tick()
        try await waitUntil { minutes.intervals.count == 3 && minutes.waiting }
        #expect(quotes == 1 && h.calls.withLock { $0 } == 1)
        runner.stop()
        try await waitUntil { !minutes.waiting }
    }
    // RecurringExecutorTests.finalGuardAfterPreparationStopsPayment covers every user and wallet change; "pause" samples them here.
    @Test(arguments: ["marker", "budget", "stopGeneration", "pause"])
    func timerReusesExecutorSafetyGuards(guardKind: String) async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        if guardKind == "marker" { try h.marker(schedule, mmkv: true) }
        h.beforePay = {
            switch guardKind {
            case "stopGeneration": try h.settings.setDurably(UUID().uuidString, forKey: WalletLifecycle.automationStopKey)
            case "suspend": h.stopped.withLock { $0 = true }
            case "pause": try await h.executor.pause(schedule.id)
            case "cancel": try await h.executor.cancel(schedule.id)
            case "wallet": h.owner.withLock { $0.instance = UUID() }
            default: break
            }
        }
        let minutes = Minutes()
        let runner = RecurringForegroundRunner(center: NotificationCenter(), sleep: { try await minutes.sleep($0) }, isActive: { true }) { await h.executor.execute() }
        defer { runner.stop() }
        let deadline: ContinuousClock.Instant? = guardKind == "budget" ? ContinuousClock.now - .seconds(1) : nil
        RecurringExecutionBudget.$deadline.withValue(deadline) { runner.start() }
        try await waitUntil { minutes.waiting }
        minutes.tick()
        try await waitUntil { minutes.intervals.count == 2 && minutes.waiting }
        #expect(h.calls.withLock { $0 } == 0)
        #expect(try h.journal.load()[schedule.id]?.consumed != true)
        runner.stop()
        try await waitUntil { !minutes.waiting }
    }
}
