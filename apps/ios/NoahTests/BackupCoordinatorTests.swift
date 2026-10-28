import Foundation
@testable import Noah
import OpenAPIRuntime
import Testing

/// Mirrors `client/src/lib/backupCoordinator.ts`, with a manual clock.
@Suite(.timeLimit(.minutes(1)))
struct BackupCoordinatorTests {
    /// Sleeps wait until the test releases them; cancelling throws, like `Task.sleep`.
    actor ManualClock {
        private var sleepers: [UUID: (duration: Duration, continuation: CheckedContinuation<Void, Error>)] = [:]
        private var order: [UUID] = []

        func sleep(_ duration: Duration) async throws {
            let id = UUID()
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    sleepers[id] = (duration, continuation)
                    order.append(id)
                }
            } onCancel: {
                Task { await self.cancel(id) }
            }
        }

        private func cancel(_ id: UUID) {
            sleepers.removeValue(forKey: id)?.continuation.resume(throwing: CancellationError())
            order.removeAll { $0 == id }
        }

        var pending: [Duration] { order.compactMap { sleepers[$0]?.duration } }

        /// Wakes the oldest sleeper; returns its duration.
        @discardableResult
        func fireNext() -> Duration? {
            guard let id = order.first, let sleeper = sleepers.removeValue(forKey: id) else { return nil }
            order.removeFirst()
            sleeper.continuation.resume()
            return sleeper.duration
        }
    }

    /// Fake `performBackup` and MMKV.
    struct Boom: Error {}
    final class World: @unchecked Sendable {
        private let lock = NSLock()
        private var _state = PersistedBackupState()
        private var _calls: [String?] = []
        private var _failures = 0
        private var _snapshot = "sha-1"
        var eligible = true
        var enabled = true
        var held: CheckedContinuation<Void, Never>?
        var holdNext = false

        var state: PersistedBackupState { lock.withLock { _state } }
        var calls: [String?] { lock.withLock { _calls } }
        func fail(times: Int) { lock.withLock { _failures = times } }
        func snapshot(_ sha: String) { lock.withLock { _snapshot = sha } }
        func hold() { lock.withLock { holdNext = true } }
        func releaseHeld() { lock.withLock { held?.resume(); held = nil } }
        var isHolding: Bool { lock.withLock { held != nil } }

        func dependencies(clock: ManualClock) -> BackupCoordinator.Dependencies {
            BackupCoordinator.Dependencies(
                perform: { last, onUploadRequired in
                    let (shouldHold, sha) = self.lock.withLock { () -> (Bool, String) in
                        self._calls.append(last)
                        defer { self.holdNext = false }
                        return (self.holdNext, self._snapshot)
                    }
                    if shouldHold { await withCheckedContinuation { c in self.lock.withLock { self.held = c } } }
                    if self.lock.withLock({ () -> Bool in
                        guard self._failures > 0 else { return false }
                        self._failures -= 1
                        return true
                    }) { throw Boom() }
                    guard sha != last else { return .init(backupId: nil, snapshotSha256: sha, uploaded: false) }
                    onUploadRequired()
                    return .init(backupId: "backup-\(sha)", snapshotSha256: sha, uploaded: true)
                },
                canBackup: { self.eligible && (!$0 || self.enabled) },
                loadState: { self.lock.withLock { self._state } },
                saveState: { new in self.lock.withLock { self._state = new } },
                sleep: { try await clock.sleep($0) },
                jitter: { 1.0 },
                now: { 1_000 })
        }
    }

    /// Polls on real time: yields alone can run out before the
    /// coordinator's tasks get a thread.
    private func eventually(_ condition: @Sendable () async -> Bool) async {
        let deadline = ContinuousClock.now + .seconds(5)
        while ContinuousClock.now < deadline {
            if await condition() { return }
            try? await Task.sleep(for: .milliseconds(2))
        }
        Issue.record("condition never became true")
    }

    @MainActor @Test(arguments: [false, true])
    func automaticBackupAfterReentryOutlivesActivation(immediate: Bool) async {
        let world = World(), clock = ManualClock()
        let gate = RecurringReservationTests.Gate()
        var dependencies = world.dependencies(clock: clock)
        dependencies.perform = { _, uploading in
            await gate.wait()
            // Same admission as WalletSession.createSnapshot after queued wallet access.
            try BackgroundExecution.checkActive()
            try await ForegroundExecution.checkActive()
            uploading()
            return .init(backupId: "test", snapshotSha256: "test", uploaded: true)
        }
        let coordinator = BackupCoordinator(dependencies: dependencies)
        await ForegroundExecution.run(isActive: { true }) {
            await coordinator.schedule(.appForeground, immediate: immediate)
        }
        if !immediate {
            await eventually { await clock.pending == [BackupCoordinator.debounce] }
            await clock.fireNext()
        }
        await eventually { await gate.entered }
        await gate.release()
        await coordinator.settle()
        #expect(world.state.lastBackupStatus == .success)
        #expect(!world.state.backupPending)
    }

    @MainActor @Test func automaticRequestFromObserverWithExpiredActivationStillBacksUp() async {
        let world = World(), clock = ManualClock()
        var dependencies = world.dependencies(clock: clock)
        let perform = dependencies.perform
        dependencies.perform = { last, uploading in
            try BackgroundExecution.checkActive()
            try await ForegroundExecution.checkActive()
            return try await perform(last, uploading)
        }
        let coordinator = BackupCoordinator(dependencies: dependencies)
        let gate = RecurringReservationTests.Gate()
        var observer: Task<Void, Never>?
        await ForegroundExecution.run(isActive: { true }) {
            observer = Task {
                await gate.wait()
                await coordinator.schedule(.databaseChanged)
            }
        }
        await eventually { await gate.entered }
        await gate.release()
        await observer?.value
        await eventually { await clock.pending == [BackupCoordinator.debounce] }
        await clock.fireNext()
        await eventually { world.state.lastBackupStatus == .success }
        await coordinator.settle()
        #expect(!world.state.backupPending)
    }

    @MainActor @Test func automaticRetryOutlivesTheForegroundFlushThatScheduledIt() async {
        let world = World(), clock = ManualClock()
        world.fail(times: 1)
        var dependencies = world.dependencies(clock: clock)
        let perform = dependencies.perform
        dependencies.perform = { last, uploading in
            try BackgroundExecution.checkActive()
            try await ForegroundExecution.checkActive()
            return try await perform(last, uploading)
        }
        let coordinator = BackupCoordinator(dependencies: dependencies)
        await ForegroundExecution.run(isActive: { true }) {
            await #expect(throws: Boom.self) { try await coordinator.flush(.manual) }
        }
        await eventually { await clock.pending == [BackupCoordinator.firstRetry] }
        await clock.fireNext()
        await eventually { world.state.lastBackupStatus == .success }
        await coordinator.settle()
        #expect(world.calls.count == 2)
        #expect(!world.state.backupPending)
    }

    @Test func explicitFlushRetainsRevokedBackgroundBudget() async {
        let world = World(), clock = ManualClock()
        var dependencies = world.dependencies(clock: clock)
        dependencies.perform = { _, _ in
            try BackgroundExecution.checkActive()
            return .init(backupId: "test", snapshotSha256: "test", uploaded: true)
        }
        let coordinator = BackupCoordinator(dependencies: dependencies)
        let budget = RecurringSubmissionBudget()
        budget.revoke()
        await RecurringSubmissionBudget.$current.withValue(budget) {
            await #expect(throws: CancellationError.self) { try await coordinator.flush(.push, requireEnabled: false) }
        }
        #expect(world.state.lastBackupStatus == .idle)
        #expect(world.state.backupPending)
        #expect(await clock.pending.isEmpty)
    }

    @Test(arguments: ["task", "url", "client"]) func cancellationLeavesBackupPendingWithoutFailure(kind: String) async throws {
        let world = World(), clock = ManualClock()
        var dependencies = world.dependencies(clock: clock)
        dependencies.perform = { _, uploading in
            uploading()
            if kind == "url" { throw URLError(.cancelled) }
            if kind == "client" {
                throw ClientError(operationID: "backup", operationInput: "test", causeDescription: "cancelled",
                                  underlyingError: URLError(.cancelled))
            }
            throw CancellationError()
        }
        let coordinator = BackupCoordinator(dependencies: dependencies)
        await #expect(throws: (any Error).self) { try await coordinator.flush(.manual) }
        #expect(world.state.backupPending)
        #expect(world.state.lastBackupStatus == .idle)
        #expect(world.state.lastBackupError == nil)
        #expect(HomeBanner.backup(world.state, now: 0, locale: Locale(identifier: "en")) == nil)
        #expect(await clock.pending.isEmpty)
        await coordinator.cancelScheduled()
    }

    @Test func backupFailureUsesRNCopyInsteadOfAnErrorType() async throws {
        let world = World(), clock = ManualClock()
        world.fail(times: 1)
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        await #expect(throws: Boom.self) { try await coordinator.flush(.manual) }
        #expect(world.state.lastBackupError == "An unknown error occurred while backing up.")
        var legacy = world.state
        legacy.lastBackupError = "ClientError"
        #expect(HomeBanner.backup(legacy, now: 0, locale: Locale(identifier: "en"))?.message == "An unknown error occurred while backing up.")
        await coordinator.cancelScheduled()
    }

    @Test func deletingLatestUploadSchedulesAnEnabledReplacement() async throws {
        let clock = ManualClock()
        let world = World()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        try await coordinator.flush(.manual)
        await coordinator.forgetUploadedBackup("other")
        #expect(await clock.pending.isEmpty)
        await coordinator.forgetUploadedBackup("backup-sha-1")
        await eventually { await clock.pending == [.seconds(5)] }
        #expect(world.calls.count == 1)
        #expect(world.state.lastUploadedSnapshotSha256 == nil)
        await clock.fireNext()
        await eventually { world.calls.count == 2 && world.state.lastBackupStatus == .success }
        #expect(world.calls == [nil, nil])
        await coordinator.cancelScheduled()
    }

    @Test(arguments: [false, true]) func deletingLatestRespectsDisabledAndSuspendedWallets(suspended: Bool) async throws {
        let clock = ManualClock()
        let world = World()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        try await coordinator.flush(.manual)
        world.eligible = !suspended
        world.enabled = suspended
        await coordinator.forgetUploadedBackup("backup-sha-1")
        #expect(world.state.lastUploadedSnapshotSha256 == nil)
        #expect(world.state.backupPending)
        #expect(await clock.pending.isEmpty)
        #expect(world.calls.count == 1)
    }

    @Test func deletingLatestWaitsForUnchangedPassBeforeReplacingIt() async throws {
        let clock = ManualClock()
        let world = World()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        try await coordinator.flush(.manual)
        world.hold()
        let pass = Task { try await coordinator.flush(.manual) }
        await eventually { world.isHolding }
        let deletion = Task { await coordinator.forgetUploadedBackup("backup-sha-1") }
        world.releaseHeld()
        try await pass.value
        await deletion.value
        await eventually { await clock.pending == [.seconds(5)] }
        await clock.fireNext()
        await eventually { world.calls.count == 3 && world.state.lastBackupStatus == .success }
        #expect(world.calls == [nil, "sha-1", nil])
        await coordinator.cancelScheduled()
    }

    @Test func deletionTimeoutDoesNotAbandonAnUncooperativePass() async throws {
        let world = World()
        let clock = ManualClock()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        world.hold()
        await coordinator.schedule(.manual, immediate: true)
        await eventually { world.isHolding }
        world.eligible = false
        await #expect(throws: WalletLifecycle.Failure.backupBusy) {
            try await coordinator.settleForDeletion(timeout: .milliseconds(20))
        }
        #expect(world.isHolding)
        #expect(world.state.lastBackupAt == nil)
        world.releaseHeld()
        try await coordinator.settleForDeletion()
        #expect(!world.isHolding)
    }

    @Test func deletionCancelsACooperativeUploadAndBoundsItsResourceTimeout() async throws {
        let world = World()
        let clock = ManualClock()
        var dependencies = world.dependencies(clock: clock)
        dependencies.perform = { _, _ in
            try await clock.sleep(.seconds(600))
            return .init(backupId: nil, snapshotSha256: "unused", uploaded: false)
        }
        let coordinator = BackupCoordinator(dependencies: dependencies)
        await coordinator.schedule(.manual, immediate: true)
        await eventually { await clock.pending == [.seconds(600)] }
        world.eligible = false
        try await coordinator.settleForDeletion(timeout: .seconds(1))
        #expect(await clock.pending.isEmpty)
        #expect(BackupService.uploadSession.configuration.timeoutIntervalForResource == 60)
    }

    @Test func settleWaitsForActiveUploadAndItsFinalStateWrite() async throws {
        let clock = ManualClock()
        let world = World()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        world.hold()
        await coordinator.schedule(.manual, immediate: true)
        await eventually { world.isHolding }
        world.eligible = false
        let finished = FakeSeed(nil)
        let settling = Task { await coordinator.settle(); try? finished.save("settled") }
        try await Task.sleep(for: .milliseconds(20))
        #expect(finished.current == nil)
        world.releaseHeld()
        await settling.value
        #expect(finished.current == "settled")
        #expect(world.state.lastBackupStatus == .success)
        #expect(await clock.pending.isEmpty)
    }

    @Test func debouncedRequestsShareOnePass() async {
        let clock = ManualClock()
        let world = World()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        for _ in 0..<3 { await coordinator.schedule(.databaseChanged) }
        await eventually { await clock.pending == [.seconds(5)] }
        #expect(world.calls.isEmpty)
        #expect(world.state.backupPending)

        await clock.fireNext()
        await eventually { world.state.lastBackupStatus == .success }
        #expect(world.calls == [nil])
        #expect(world.state.backupPending == false)
        #expect(world.state.lastUploadedSnapshotSha256 == "sha-1")
        #expect(world.state.lastBackupId == "backup-sha-1")
        #expect(world.state.lastBackupAt == 1_000)
    }

    @Test func unchangedSnapshotIsNotUploadedAgain() async throws {
        let clock = ManualClock()
        let world = World()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        try await coordinator.flush(.manual)
        #expect(world.state.lastBackupStatus == .success)

        try await coordinator.flush(.manual)
        #expect(world.calls == [nil, "sha-1"], "second pass compares against the last upload")
        #expect(world.state.lastBackupStatus == .idle)
        #expect(world.state.lastBackupId == "backup-sha-1")
    }

    @Test func failuresRetryWithBackoffAndSuccessResetsIt() async {
        let clock = ManualClock()
        let world = World()
        world.fail(times: 2)
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))

        await coordinator.schedule(.startup, immediate: true)
        await eventually { await clock.pending == [.seconds(15)] }
        #expect(world.state.lastBackupStatus == .failed)
        #expect(world.state.lastBackupError != nil)

        await clock.fireNext()
        await eventually { await clock.pending == [.seconds(30)] }
        await clock.fireNext()
        await eventually { world.state.lastBackupStatus == .success }
        #expect(world.calls.count == 3)

        // Back to the first delay after a success.
        world.fail(times: 1)
        world.snapshot("sha-2")
        await coordinator.schedule(.startup, immediate: true)
        await eventually { await clock.pending == [.seconds(15)] }
    }

    @Test func retryDelaysDoubleToFifteenMinutesWithJitter() {
        #expect(BackupCoordinator.retryDelay(attempt: 0, jitter: 1) == .seconds(15))
        #expect(BackupCoordinator.retryDelay(attempt: 0, jitter: 0.8) == .seconds(12))
        #expect(BackupCoordinator.retryDelay(attempt: 5, jitter: 1) == .seconds(480))
        #expect(BackupCoordinator.retryDelay(attempt: 6, jitter: 1) == .seconds(900))
        #expect(BackupCoordinator.retryDelay(attempt: 6, jitter: 1.2) == .seconds(900))
        #expect(BackupCoordinator.retryDelay(attempt: 10, jitter: 0.8) == .seconds(720))
    }

    @Test func flushWaitsForAPassThatCoversItsRequest() async throws {
        let clock = ManualClock()
        let world = World()
        world.hold()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        await coordinator.schedule(.startup, immediate: true)
        await eventually { world.isHolding }

        // Requested while the first pass runs: that pass doesn't cover it.
        world.snapshot("sha-2")
        let flush = Task { try await coordinator.flush(.push, requireEnabled: false) }
        for _ in 0..<100 { await Task.yield() }
        world.releaseHeld()
        try await flush.value
        #expect(world.calls == [nil, "sha-1"])
        #expect(world.state.lastUploadedSnapshotSha256 == "sha-2")
        #expect(world.state.backupPending == false)
    }

    /// A push out of background time stops waiting; the backup itself finishes.
    @Test func cancelledFlushStopsWaitingWhileThePassFinishes() async throws {
        let clock = ManualClock()
        let world = World()
        world.hold()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        let flush = Task { try await coordinator.flush(.push, requireEnabled: false) }
        await eventually { world.isHolding }

        flush.cancel()
        await #expect(throws: CancellationError.self) { try await flush.value }
        #expect(world.state.lastBackupStatus != .success, "the upload hasn't finished yet")

        world.releaseHeld()
        await eventually { world.state.lastBackupStatus == .success }
        #expect(world.calls == [nil])
    }

    @Test func ineligibleWalletIsLeftAlone() async throws {
        let clock = ManualClock()
        let world = World()
        world.eligible = false
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        await coordinator.schedule(.databaseChanged)
        await #expect(throws: BackupCoordinator.NotReady.self) { try await coordinator.flush(.manual) }
        #expect(world.state == PersistedBackupState())
        #expect(await clock.pending.isEmpty)
    }

    @Test func cancellingScheduledWorkStopsTheTimers() async {
        let clock = ManualClock()
        let world = World()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        await coordinator.schedule(.databaseChanged)
        await eventually { await clock.pending.count == 1 }
        await coordinator.cancelScheduled()
        await eventually { await clock.pending.isEmpty }
        for _ in 0..<100 { await Task.yield() }
        #expect(world.calls.isEmpty)
    }

    @Test func storeReadsAndWritesTheReactNativeShape() throws {
        let rn = #"{"state":{"backupPending":true,"lastBackupAt":1784160000000,"lastBackupAttemptAt":null,"lastBackupSt"#
            + #"atus":"in_progress","lastBackupError":null,"lastBackupId":"b1","lastUploadedSnapshotSha256":"ab"},"v"#
            + #"ersion":0}"#
        let decoded = try JSONDecoder().decode(PersistedEnvelope<PersistedBackupState>.self, from: Data(rn.utf8)).state
        #expect(decoded.lastBackupStatus == .inProgress)
        #expect(decoded.lastBackupAt == 1_784_160_000_000)
        #expect(decoded.lastUploadedSnapshotSha256 == "ab")

        var state = decoded
        state.markPending()
        #expect(state.lastBackupStatus == .inProgress, "a pending mark doesn't hide a running backup")
        let forgotLast = state.forgetUploadedBackup("b1")
        #expect(forgotLast)
        #expect(state.lastUploadedSnapshotSha256 == nil)
        let forgotOther = state.forgetUploadedBackup("other")
        #expect(!forgotOther)

        let encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(PersistedBackupState())) as? [String: Any]
        #expect(encoded?.keys.sorted() == [
            "backupPending", "lastBackupAt", "lastBackupAttemptAt", "lastBackupError", "lastBackupId",
            "lastBackupStatus", "lastUploadedSnapshotSha256",
        ])
        #expect(encoded?["lastBackupStatus"] as? String == "idle")
    }
}
