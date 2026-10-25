import Foundation
import Synchronization

/// One budget for push and BGTaskScheduler. Completion is prompt on expiry;
/// session admission stays held by the worker until native settlement finishes.
@MainActor final class BackgroundExecution {
    /// How long a settled job's report may run, past the budget if need be. A push's
    /// 25 s + 3 s stays inside the ~30 s iOS allows before its completion handler runs.
    nonisolated static let reportAllowance = Duration.seconds(3)
    private static var active: [UUID: BackgroundExecution] = [:]
    private let id = UUID()
    private let budget = RecurringSubmissionBudget()
    private var work: Task<Void, Never>?
    private var timer: Task<Void, Never>?
    private var completion: ((Bool) -> Void)?

    init(timeout: Duration = .seconds(25), reportAllowance: Duration = BackgroundExecution.reportAllowance,
         logFile: LogFile = .shared, completion: @escaping (Bool) -> Void,
         operation: @escaping @Sendable () async throws -> Void) {
        self.completion = completion
        let deadline = ContinuousClock.now + timeout
        let budget = budget
        let reports = BackgroundReportWindow(allowance: reportAllowance, closesAt: deadline + reportAllowance)
        Self.active[id] = self
        work = Task {
            let success = await ForegroundExecution.$admission.withValue(nil) {
                await BackgroundReportWindow.$current.withValue(reports) {
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
            // Nothing new starts after the budget; a report already under way may finish first.
            budget.revoke()
            work?.cancel()
            await reports.drain()
            expire()
        }
    }

    /// A settled job's report: shielded from the budget's cancellation and bounded by its
    /// own allowance, never past the execution's report window. Completion waits for it.
    nonisolated static func withReportAllowance(_ report: @escaping @Sendable () async -> Void) async {
        let window = BackgroundReportWindow.current
        let start = ContinuousClock.now
        let limit = window.map { min(start + $0.allowance, $0.closesAt) } ?? start + reportAllowance
        let bounded = Task { await BackgroundReportWindow.run(report, until: limit) }
        window?.track(bounded)
        await bounded.value
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

    static func foregroundWillEnter() {
        for execution in Array(active.values) { execution.expire() }
    }

    private func finish(_ success: Bool) {
        guard let completion else { return }
        self.completion = nil
        budget.revoke()
        timer?.cancel()
        timer = nil
        completion(success)
    }
}

/// The reports an execution's completion waits for after its deadline, all over by `closesAt`.
final class BackgroundReportWindow: Sendable {
    @TaskLocal static var current: BackgroundReportWindow?
    let allowance: Duration
    let closesAt: ContinuousClock.Instant
    private let reports = Mutex<[Task<Void, Never>]>([])

    init(allowance: Duration, closesAt: ContinuousClock.Instant) {
        self.allowance = allowance
        self.closesAt = closesAt
    }

    func track(_ report: Task<Void, Never>) {
        reports.withLock { $0.append(report) }
    }

    func drain() async {
        for report in reports.withLock({ $0 }) { await report.value }
    }

    /// Returns by `limit` even if `report` ignores its cancellation.
    static func run(_ report: @escaping @Sendable () async -> Void, until limit: ContinuousClock.Instant) async {
        let call = Task { await report() }
        let waiter = Mutex<CheckedContinuation<Void, Never>?>(nil)
        @Sendable func resume() {
            waiter.withLock { state -> CheckedContinuation<Void, Never>? in
                defer { state = nil }
                return state
            }?.resume()
        }
        await withCheckedContinuation { continuation in
            waiter.withLock { $0 = continuation }
            let timeout = Task {
                try? await Task.sleep(until: limit, clock: .continuous)
                call.cancel()
                resume()
            }
            Task { await call.value; timeout.cancel(); resume() }
        }
    }
}
