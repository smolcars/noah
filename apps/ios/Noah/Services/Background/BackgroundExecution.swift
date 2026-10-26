import Foundation

/// One budget for push and BGTaskScheduler. Completion is prompt on expiry;
/// session admission stays held by the worker until native settlement finishes.
/// The one exception is a job's report already under way at the deadline.
@MainActor final class BackgroundExecution {
    /// A report's own time limit, which also bounds how long it holds completion past the
    /// deadline: a push's 25 s + 3 s stays inside the ~30 s iOS allows.
    nonisolated static let reportAllowance = Duration.seconds(3)
    private static var active: [UUID: BackgroundExecution] = [:]
    private let id = UUID()
    private let budget = RecurringSubmissionBudget()
    private let deadline: ContinuousClock.Instant
    private let reportAllowance: Duration
    private var work: Task<Void, Never>?
    private var timer: Task<Void, Never>?
    private var completion: ((Bool) -> Void)?
    private var reportTimeout: DispatchWorkItem?
    private(set) var reportsClosed = false

    init(timeout: Duration = .seconds(25), reportAllowance: Duration = BackgroundExecution.reportAllowance,
         logFile: LogFile = .shared, completion: @escaping (Bool) -> Void,
         operation: @escaping @Sendable () async throws -> Void) {
        self.completion = completion
        let deadline = ContinuousClock.now + timeout
        self.deadline = deadline
        self.reportAllowance = reportAllowance
        let budget = budget
        Self.active[id] = self
        work = Task {
            let success = await ForegroundExecution.$admission.withValue(nil) {
                await BackgroundReport.$execution.withValue(self) {
                    await RecurringExecutionBudget.$deadline.withValue(deadline) {
                        await RecurringSubmissionBudget.$current.withValue(budget) {
                            do { try Self.checkActive(); try await operation(); try Self.checkActive(); return true }
                            catch { return false }
                        }
                    }
                }
            }
            // Expiry still completes immediately while a bounded drain is pending.
            let canDrain = RecurringSubmissionBudget.$current.withValue(budget) { RecurringSubmissionBudget.available }
            if canDrain { await logFile.drainAsync(until: deadline) }
            let stillActive = RecurringSubmissionBudget.$current.withValue(budget) { RecurringSubmissionBudget.available }
            finish(success && stillActive && ContinuousClock.now < deadline)
            Self.active[id] = nil
        }
        timer = Task {
            do { try await Task.sleep(until: deadline, clock: .continuous) }
            catch { return }
            deadlinePassed()
        }
    }

    /// Runs a job's report as an ordinary stage of the work, if it can still start. Its own
    /// timeout ends the execution, from a main-queue timer that needs no cooperative thread;
    /// a report under way at the deadline holds completion until it ends or times out.
    nonisolated static func report(_ body: () async -> Void) async {
        guard let execution = BackgroundReport.execution else { return await body() }
        guard await execution.admitReport() else { return }
        // Expiry can land after admission; it cancels the work before completing, so a cancelled
        // report never starts. Later awaits in the report path stop on cancellation themselves.
        if !Task.isCancelled { await body() }
        await execution.reportEnded()
    }

    /// Shielded tasks inherit the deadline and revocation even before Swift cancellation arrives.
    nonisolated static func checkActive() throws {
        try Task.checkCancellation()
        guard RecurringSubmissionBudget.available, RecurringExecutionBudget.available else { throw CancellationError() }
    }

    nonisolated func systemExpired() {
        budget.revoke()
        Task { @MainActor in self.expire() }
    }

    func expire() {
        budget.revoke()
        work?.cancel()
        finish(false)
    }

    /// The timer's action at the deadline; tests call it directly.
    func deadlinePassed() {
        reportsClosed = true
        // Nothing new starts after the budget; a report under way runs on to its timeout.
        if reportTimeout == nil { expire() } else { budget.revoke() }
    }

    private func admitReport() -> Bool {
        guard completion != nil, !reportsClosed else { return false }
        let limit = min(ContinuousClock.now + reportAllowance, deadline + reportAllowance)
        let (seconds, attoseconds) = (limit - ContinuousClock.now).components
        let timeout = DispatchWorkItem { [weak self] in MainActor.assumeIsolated { self?.expire() } }
        reportTimeout = timeout
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .nanoseconds(Int(max(0, seconds * 1_000_000_000 + attoseconds / 1_000_000_000))),
            execute: timeout)
        return true
    }

    private func reportEnded() {
        reportTimeout?.cancel()
        reportTimeout = nil
        if reportsClosed { expire() }
    }

    static func foregroundWillEnter() {
        for execution in Array(active.values) { execution.expire() }
    }

    private func finish(_ success: Bool) {
        guard let completion else { return }
        self.completion = nil
        budget.revoke()
        timer?.cancel()
        timer = nil
        reportTimeout?.cancel()
        reportTimeout = nil
        completion(success)
    }
}

/// The execution a job runs under, for `BackgroundExecution.report`.
enum BackgroundReport {
    @TaskLocal static var execution: BackgroundExecution?
}
