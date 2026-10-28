import BackgroundTasks
import BarkBoltFFI
import Foundation
@testable import Noah
import Synchronization
import Testing

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
        let bundleID = try #require(Bundle.main.bundleIdentifier)
        #expect(refresh.identifier == bundleID + ".refresh")
        #expect(maintenance.identifier == bundleID + ".maintenance")
        #expect(refresh.earliestBeginDate == h.now.addingTimeInterval(3_600))
        #expect(maintenance.earliestBeginDate == h.now.addingTimeInterval(21_600))
        #expect(maintenance.requiresNetworkConnectivity)
        #expect(!maintenance.requiresExternalPower)
        h.eligible = false
        await h.scheduler.schedule()
        #expect(h.requests.isEmpty)
        #expect(Set(h.cancelled) == Set(BackgroundScheduler.Kind.allCases.map(\.identifier)))
    }

    @Test(arguments: [true, false]) func launchRechecksForegroundAndWalletEligibility(foreground: Bool) async {
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
        // swiftlint:disable:next discouraged_optional_boolean - nil until the shielded work reads the budget
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
                if (try? BackgroundExecution.checkActive()) != nil { recorder.add("later") }
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

    @Test func deadlineRevokesSubmission() async {
        do {
            let gate = RecurringReservationTests.Gate()
            // swiftlint:disable:next discouraged_optional_boolean - nil until the shielded work reads the budget
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
        var completed: Bool?, settled = false // swiftlint:disable:this discouraged_optional_boolean - nil until the task completes
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

    // MARK: Pending exits' progress, last in each task and push

    private static func stages(_ recorder: PushJobsTests.Recorder, widget: @escaping @Sendable () -> Void = {}) -> BackgroundScheduler.Stages {
        func stage(_ name: String) -> @Sendable () async throws -> Void { { recorder.add(name) } }
        return .init(open: stage("open"), sync: stage("sync"), maintenance: stage("maintenance"), recurring: stage("recurring"),
                     backup: stage("backup"), widget: { recorder.add("widget"); widget() }, progressExits: { recorder.add("exits") })
    }

    @Test(arguments: BackgroundScheduler.Kind.allCases) func taskProgressesExitsLast(kind: BackgroundScheduler.Kind) async throws {
        let recorder = PushJobsTests.Recorder()
        try await BackgroundScheduler.run(kind, Self.stages(recorder))
        #expect(recorder.all == (kind == .maintenance
                ? ["open", "sync", "maintenance", "recurring", "backup", "widget", "exits"]
                : ["open", "sync", "recurring", "widget", "exits"]))
    }

    @Test func taskSkipsExitProgressOnceItsBudgetIsGone() async throws {
        let recorder = PushJobsTests.Recorder()
        let budget = RecurringSubmissionBudget()
        await #expect(throws: CancellationError.self) {
            try await RecurringSubmissionBudget.$current.withValue(budget) {
                try await BackgroundScheduler.run(.refresh, Self.stages(recorder) { budget.revoke() })
            }
        }
        #expect(recorder.all == ["open", "sync", "recurring", "widget"])
    }

    final class ExitCalls: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [UInt64?] = []
        func add(_ rate: UInt64?) { lock.withLock { values.append(rate) } }
        var rates: [UInt64?] { lock.withLock { values } }
    }

    private static func exitAccess(_ calls: ExitCalls, _ exits: [ExitVtxo], statuses: [ExitProgressStatus] = [],
                                   failure: Error? = nil, models: [ExitModel] = []) -> WalletSession.ExitProgressAccess {
        .init(walletID: "w", exits: { exits },
              progress: { rate in calls.add(rate); if let failure { throw failure }; return statuses },
              models: { models }, tip: { 120 })
    }

    @Test func backgroundExitProgressCallsBarkOnlyForPendingExitsAndRecordsLikeACheck() async throws {
        let session = WalletSession()
        let calls = ExitCalls()
        // Finished exits only: no call, nothing recorded.
        let finished = [ExitVtxo(vtxoId: "done", amountSats: 31_000, state: .canceled(tipHeight: 100), isClaimable: false),
                        ExitVtxo(vtxoId: "gone", amountSats: 31_000, state: .vtxoAlreadySpent(tipHeight: 100), isClaimable: false)]
        #expect(try await !session.progressPendingExits(access: Self.exitAccess(calls, finished)))
        #expect(calls.rates.isEmpty)
        #expect(await session.exitChecks["w"] == nil)
        // A pending one: one call at bark's own rate, recorded as Move exit forward records it.
        var txs = ExitProgressTests.path.map { ExitProgressTests.tx($0, "verify-inputs") }
        txs[0] = ExitProgressTests.tx("root", "awaiting-cpfp-broadcast")
        let parked = ExitProgressTests.exit("Processing", ExitProgressTests.processing(txs))
        let episode = try #require(parked.parkedEpisode)
        let pending = finished + [ExitVtxo(vtxoId: "v", amountSats: 31_000, state: .start(tipHeight: 100), isClaimable: false)]
        let short = ExitProgressStatus(vtxoId: "v", state: .processing(tipHeight: 101, transactions: []),
                                       error: BarkError(message: "private", code: .exitInsufficientConfirmedFunds, neededSats: 1_215, availableSats: 310))
        #expect(try await session.progressPendingExits(access: Self.exitAccess(calls, pending, statuses: [short], models: [parked])))
        #expect(calls.rates == [nil])
        let check = try #require(await session.exitChecks["w"])
        #expect(check.tip == 120 && !check.failed)
        #expect(check.parked == ["v": episode] && check.needed == ["v": 1_215])
        // A call that throws is recorded failed and rethrown to the job's wrapper.
        await #expect(throws: PushJobsTests.Boom.self) {
            try await session.progressPendingExits(access: Self.exitAccess(calls, pending, failure: PushJobsTests.Boom()))
        }
        #expect(await session.exitChecks["w"]?.failed == true)
        // The job's budget gone after the read: bark is never called.
        let revoked = RecurringSubmissionBudget(); revoked.revoke()
        await #expect(throws: CancellationError.self) {
            try await RecurringSubmissionBudget.$current.withValue(revoked) {
                try await session.progressPendingExits(access: Self.exitAccess(calls, pending))
            }
        }
        #expect(calls.rates.count == 2)
    }

    @Test func backgroundExitProgressFailureIsReportedNeverThrown() async {
        let failures = Mutex<[String]>([])
        // No open wallet: the job's step logs why and carries on.
        await WalletSession().progressPendingExits { reason in failures.withLock { $0.append(reason) } }
        #expect(failures.withLock { $0 } == ["SessionError"])
    }
}
