import Foundation
import OpenAPIRuntime
import Synchronization

/// Ported from `client/src/lib/backupCoordinator.ts`: one backup at a time;
/// requests coalesce through a generation counter (a pass covers every
/// request made before it started); automatic requests wait 5 s for more
/// changes; failures retry after 15 s, doubling to 15 min, ±20 % jitter.
/// Snapshots identical to the last upload are not uploaded again.
actor BackupCoordinator {
    enum Reason: String, Sendable {
        case appForeground = "app_foreground"
        case databaseChanged = "database_changed"
        case manual
        case push
        case resyncRequired = "resync_required"
        case startup
    }

    struct NotReady: Error, Equatable {}

    struct Dependencies: Sendable {
        /// `BackupService.performBackup`; calls `onUploadRequired` once it
        /// knows the snapshot changed.
        var perform: @Sendable (_ lastUploadedSha256: String?, _ onUploadRequired: @escaping @Sendable () -> Void)
            async throws -> BackupService.Outcome
        /// Wallet initialized, open and not suspended; backups enabled
        /// unless `requireEnabled` is false (manual and push backups).
        var canBackup: @Sendable (_ requireEnabled: Bool) async -> Bool
        var loadState: @Sendable () -> PersistedBackupState
        var saveState: @Sendable (PersistedBackupState) -> Void
        var sleep: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
        var jitter: @Sendable () -> Double = { Double.random(in: 0.8...1.2) }
        var now: @Sendable () -> Double = { Date().timeIntervalSince1970 * 1000 }
    }

    static let debounce = Duration.seconds(5)
    static let firstRetry = Duration.seconds(15)
    static let maxRetry = Duration.seconds(15 * 60)

    nonisolated let statusUpdates = HomeStatusUpdates<PersistedBackupState>()
    private let dependencies: Dependencies
    private let log = AppLog("backupCoordinator")
    private var requestedGeneration = 0
    private var completedGeneration = 0
    private var debounceTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var retryAttempt = 0
    private var pendingRequireEnabled = true
    private var inFlight: Task<Result<Void, Error>, Never>?
    /// `backup-storage` read-modify-writes, also from the upload callback.
    private let stateLock = Mutex(())

    init(dependencies: Dependencies) {
        self.dependencies = dependencies
    }

    /// `scheduleBackup`: debounced unless `immediate`.
    func schedule(_ reason: Reason, immediate: Bool = false, requireEnabled: Bool = true) async {
        guard await dependencies.canBackup(requireEnabled) else {
            log.d("Skipping backup request: \(reason.rawValue)")
            return
        }
        requestedGeneration += 1
        pendingRequireEnabled = pendingRequireEnabled && requireEnabled
        update { $0.markPending() }
        log.d("Backup requested: \(reason.rawValue)")
        cancelRetry()
        if immediate {
            cancelDebounce()
            // Automatic work must not inherit a foreground activation's short admission.
            Task.detached { _ = await self.runPass() }
        } else {
            scheduleTrailingPass()
        }
    }

    func cancelScheduled() {
        cancelDebounce()
        cancelRetry()
    }

    /// Suspension gates new passes before this drains the complete upload and state write.
    func settle() async {
        cancelScheduled()
        if let inFlight { _ = await inFlight.value }
        cancelScheduled()
    }

    /// Cancellation reaches URLSession uploads; a non-cooperative native pass stays owned
    /// by the coordinator and deletion fails without touching its files or seed.
    func settleForDeletion(timeout: Duration = .seconds(30)) async throws {
        cancelScheduled()
        guard let inFlight else { return }
        inFlight.cancel()
        let waiter = Task { try await Self.value(of: inFlight) }
        let deadline = Task {
            do { try await Task.sleep(for: timeout); waiter.cancel() }
            catch { /* Canceled: the wait finished first. */ }
        }
        defer { deadline.cancel(); cancelScheduled() }
        do { _ = try await waiter.value }
        catch { throw WalletLifecycle.Failure.backupBusy }
    }

    /// `flushBackup`: runs now and returns once every request made before
    /// this call is covered by a finished pass. A cancelled caller (a push
    /// out of background time) stops waiting at once; the pass itself goes
    /// on, its native work protected by the wallet session.
    func flush(_ reason: Reason, requireEnabled: Bool = true) async throws {
        guard await dependencies.canBackup(requireEnabled) else { throw NotReady() }
        requestedGeneration += 1
        pendingRequireEnabled = pendingRequireEnabled && requireEnabled
        let target = requestedGeneration
        update { $0.markPending() }
        cancelDebounce()
        cancelRetry()
        log.d("Immediate backup requested: \(reason.rawValue)")
        var result = try await Self.value(of: passTask())
        while case .success = result, completedGeneration < target {
            result = try await Self.value(of: passTask())
        }
        try result.get()
    }

    // MARK: Passes

    private func runPass() async -> Result<Void, Error> {
        await passTask().value
    }

    /// The pass in flight, or a new one. The pass clears `inFlight` itself
    /// before anyone waiting on it resumes; otherwise `flush` could keep
    /// getting this finished pass back and spin without starting the next.
    private func passTask() -> Task<Result<Void, Error>, Never> {
        if let inFlight { return inFlight }
        let task = Task {
            let result = await pass()
            inFlight = nil
            return result
        }
        inFlight = task
        return task
    }

    /// Waits for `task` unless the caller is cancelled first (then throws
    /// `CancellationError`); the task itself keeps running either way.
    private static func value<T: Sendable>(of task: Task<T, Never>) async throws -> T {
        let waiter = Mutex<(continuation: CheckedContinuation<T, Error>?, early: Result<T, Error>?)>((nil, nil))
        @Sendable func finish(_ result: Result<T, Error>) {
            let continuation = waiter.withLock { state -> CheckedContinuation<T, Error>? in
                if let continuation = state.continuation {
                    state.continuation = nil
                    return continuation
                }
                if state.early == nil { state.early = result }
                return nil
            }
            continuation?.resume(with: result)
        }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let early = waiter.withLock { state -> Result<T, Error>? in
                    if let early = state.early { return early }
                    state.continuation = continuation
                    return nil
                }
                if let early {
                    continuation.resume(with: early)
                    return
                }
                Task { finish(.success(await task.value)) }
            }
        } onCancel: {
            finish(.failure(CancellationError()))
        }
    }

    private func pass() async -> Result<Void, Error> {
        let requireEnabled = pendingRequireEnabled
        guard await dependencies.canBackup(requireEnabled) else {
            log.d("Skipping backup pass: the wallet is not eligible")
            return .failure(NotReady())
        }
        let generation = requestedGeneration
        pendingRequireEnabled = true
        let lastUploaded = dependencies.loadState().lastUploadedSnapshotSha256
        do {
            let clock = dependencies.now
            let outcome = try await dependencies.perform(lastUploaded) { [self] in
                update { $0.markInProgress(now: clock()) }
            }
            retryAttempt = 0
            cancelRetry()
            completedGeneration = max(completedGeneration, generation)
            let newer = requestedGeneration > completedGeneration
            let now = dependencies.now()
            update {
                $0.markSuccess(
                    snapshotSha256: outcome.snapshotSha256, backupId: outcome.backupId,
                    uploaded: outcome.uploaded, stillPending: newer, now: now)
            }
            log.d(outcome.uploaded ? "Backup uploaded" : "Backup already current")
            if newer { scheduleTrailingPass() }
            return .success(())
        } catch {
            pendingRequireEnabled = pendingRequireEnabled && requireEnabled
            if Self.isCancellation(error) || Task.isCancelled ||
                !RecurringSubmissionBudget.available || !RecurringExecutionBudget.available {
                update { $0.markPending(); $0.lastBackupStatus = .idle }
                return .failure(error)
            }
            let message = "An unknown error occurred while backing up."
            update { $0.markFailed(message) }
            log.w("Backup failed: \(message)")
            await scheduleRetry()
            return .failure(error)
        }
    }

    static func isCancellation(_ error: Error) -> Bool {
        if let error = error as? ClientError { return isCancellation(error.underlyingError) }
        return error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    // MARK: Timers

    private func scheduleTrailingPass() {
        guard debounceTask == nil else { return }
        let sleep = dependencies.sleep
        debounceTask = Task.detached {
            guard (try? await sleep(Self.debounce)) != nil else { return }
            await self.debounceFired()
        }
    }

    private func debounceFired() async {
        debounceTask = nil
        _ = await runPass()
    }

    private func scheduleRetry() async {
        guard retryTask == nil, debounceTask == nil else { return }
        guard await dependencies.canBackup(pendingRequireEnabled) else { return }
        guard retryTask == nil, debounceTask == nil else { return }
        let delay = Self.retryDelay(attempt: retryAttempt, jitter: dependencies.jitter())
        retryAttempt = min(retryAttempt + 1, 10)
        log.d("Backup retry scheduled in \(delay)")
        let sleep = dependencies.sleep
        retryTask = Task.detached {
            guard (try? await sleep(delay)) != nil else { return }
            await self.retryFired()
        }
    }

    private func retryFired() async {
        retryTask = nil
        _ = await runPass()
    }

    /// 15 s · 2^attempt, capped at 15 min, then ±20 % jitter, capped again.
    static func retryDelay(attempt: Int, jitter: Double) -> Duration {
        let base = min(firstRetry * (1 << min(attempt, 10)), maxRetry)
        return min(base * jitter, maxRetry)
    }

    private func cancelDebounce() {
        debounceTask?.cancel()
        debounceTask = nil
    }

    private func cancelRetry() {
        retryTask?.cancel()
        retryTask = nil
    }

    // MARK: State

    /// Applied in place, like the RN store's actions, so ordering holds.
    private nonisolated func update(_ change: (inout PersistedBackupState) -> Void) {
        stateLock.withLock { _ in
            var state = dependencies.loadState()
            change(&state)
            dependencies.saveState(state)
            statusUpdates.send(state)
        }
    }

    /// Invalidate deduplication after deleting the currently remembered upload.
    func forgetUploadedBackup(_ backupId: String) async {
        // An in-flight unchanged pass must not restore the deleted hash.
        while let inFlight { _ = await inFlight.value }
        var deletedCurrent = false
        update { deletedCurrent = $0.forgetUploadedBackup(backupId) }
        if deletedCurrent { await schedule(.manual) }
    }

    /// After a restore (RN `seedRestoredBackup`).
    func seedRestored(snapshotSha256: String, backupId: String?) {
        let now = dependencies.now()
        update { $0.seedRestored(snapshotSha256: snapshotSha256, backupId: backupId, now: now) }
    }
}
