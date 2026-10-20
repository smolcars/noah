import BackgroundTasks
import Foundation
import Testing
@testable import Noah

@MainActor struct BackgroundTaskTests {
    @MainActor final class SchedulerHarness {
        var requests: [BGTaskRequest] = []
        var cancelled: [String] = []
        var eligible = true
        var foreground = false
        var calls: [BackgroundScheduler.Kind] = []
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        lazy var scheduler = BackgroundScheduler(dependencies: .init(
            pending: { self.requests.map(\.identifier) }, submit: { self.requests.append($0) },
            cancel: { id in self.cancelled.append(id); self.requests.removeAll { $0.identifier == id } },
            eligible: { self.eligible }, isForeground: { self.foreground },
            perform: { kind in await MainActor.run { self.calls.append(kind) } }, now: { self.now }))
    }

    @Test func schedulesBothKindsWithoutPostponingPendingRequests() async throws {
        let h = SchedulerHarness()
        await h.scheduler.schedule(); await h.scheduler.schedule()
        #expect(h.requests.count == 2)
        let refresh = try #require(h.requests.first { $0 is BGAppRefreshTaskRequest })
        let maintenance = try #require(h.requests.first { $0 is BGProcessingTaskRequest } as? BGProcessingTaskRequest)
        #expect(refresh.identifier == Bundle.main.bundleIdentifier! + ".refresh")
        #expect(maintenance.identifier == Bundle.main.bundleIdentifier! + ".maintenance")
        #expect(refresh.earliestBeginDate == h.now.addingTimeInterval(900))
        #expect(maintenance.earliestBeginDate == h.now.addingTimeInterval(21_600))
        #expect(maintenance.requiresNetworkConnectivity)
        #expect(!maintenance.requiresExternalPower)
        h.eligible = false
        await h.scheduler.schedule()
        #expect(h.requests.isEmpty)
        #expect(Set(h.cancelled) == Set(BackgroundScheduler.Kind.allCases.map(\.identifier)))
    }

    @Test(arguments: [true, false]) func launchRechecksForegroundAndWalletEligibility(foreground: Bool) async throws {
        let h = SchedulerHarness()
        h.foreground = foreground; h.eligible = !foreground ? false : true
        let done = await withCheckedContinuation { continuation in
            _ = h.scheduler.start(.maintenance) { continuation.resume(returning: $0) }
        }
        #expect(done)
        #expect(h.calls.isEmpty)
    }

    @Test func eligibleLaunchCallsExistingJobOnce() async {
        let h = SchedulerHarness()
        let done = await withCheckedContinuation { continuation in
            _ = h.scheduler.start(.refresh) { continuation.resume(returning: $0) }
        }
        #expect(done)
        #expect(h.calls == [.refresh])
    }

    @Test func generatedPlistPermitsRegisteredIdentifiersAndModes() throws {
        let permitted = try #require(Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String])
        #expect(Set(permitted) == Set(BackgroundScheduler.Kind.allCases.map(\.identifier)))
        let modes = try #require(Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String])
        #expect(Set(modes) == ["fetch", "processing", "remote-notification"])
    }

    @Test func expiryRevokesInheritedBudgetBeforeShieldedWorkSettles() async throws {
        let gate = RecurringReservationTests.Gate()
        var complete: [Bool] = [], available: Bool?
        let execution = BackgroundExecution(timeout: .seconds(5), completion: { complete.append($0) }) {
            await Task { await gate.wait() }.value
            await MainActor.run { available = RecurringSubmissionBudget.available }
        }
        let limit = ContinuousClock.now + .seconds(2)
        while !(await gate.entered), ContinuousClock.now < limit { await Task.yield() }
        try #require(await gate.entered)
        execution.systemExpired()
        while complete.isEmpty, ContinuousClock.now < limit { await Task.yield() }
        execution.expire()
        #expect(complete == [false])
        #expect(available == nil)
        await gate.release()
        while available == nil, ContinuousClock.now < limit { await Task.yield() }
        #expect(available == false)
        #expect(complete == [false])
    }

    @Test func systemExpiryStopsShieldedBackgroundStagesBeforeCancellationArrives() async throws {
        let gate = RecurringReservationTests.Gate()
        let recorder = PushJobsTests.Recorder()
        var completed: [Bool] = []
        let execution = BackgroundExecution(completion: { completed.append($0) }) {
            await Task {
                await gate.wait()
                do {
                    try BackgroundExecution.checkActive()
                    recorder.add("later")
                } catch {}
            }.value
            recorder.add("settled")
        }
        let limit = ContinuousClock.now + .seconds(3)
        while !(await gate.entered), ContinuousClock.now < limit { await Task.yield() }
        try #require(await gate.entered)
        execution.systemExpired()
        await gate.release()
        while !recorder.all.contains("settled"), ContinuousClock.now < limit { await Task.yield() }
        #expect(recorder.all == ["settled"])
        #expect(completed == [false])
    }

    @Test func deadlineRevokesSubmission() async throws {
        do {
            let gate = RecurringReservationTests.Gate()
            var complete: [Bool] = [], available: Bool?
            _ = BackgroundExecution(timeout: .milliseconds(20), completion: { complete.append($0) }) {
                await Task { await gate.wait() }.value
                await MainActor.run { available = RecurringSubmissionBudget.available && RecurringExecutionBudget.available }
            }
            let limit = ContinuousClock.now + .seconds(2)
            while !(await gate.entered), ContinuousClock.now < limit { await Task.yield() }
            while complete.isEmpty, ContinuousClock.now < limit { await Task.yield() }
            #expect(complete == [false])
            await gate.release()
            while available == nil, ContinuousClock.now < limit { await Task.yield() }
            #expect(available == false)
        }
    }

    @Test func expiryDuringRecurringQuoteCannotSubmitAndLaterTriggerPaysOnce() async throws {
        let h = try RecurringExecutorTests.Harness(); _ = try h.add()
        defer { try? FileManager.default.removeItem(at: h.directory) }
        var release: CheckedContinuation<Void, Never>?
        h.preflight = { await withCheckedContinuation { release = $0 } }
        var completed: Bool?, settled = false
        let execution = BackgroundExecution(completion: { completed = $0 }) {
            await h.executor.execute()
            await MainActor.run { settled = true }
        }
        let limit = ContinuousClock.now + .seconds(5)
        while release == nil, ContinuousClock.now < limit { await Task.yield() }
        try #require(release != nil)
        execution.expire(); release?.resume()
        while !settled, ContinuousClock.now < limit { await Task.yield() }
        #expect(completed == false)
        #expect(h.calls.withLock { $0 } == 0)
        h.preflight = nil
        await h.executor.execute(); await h.executor.execute()
        #expect(h.calls.withLock { $0 } == 1)
    }
}
