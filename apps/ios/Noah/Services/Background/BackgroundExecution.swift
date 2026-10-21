import Foundation

/// One budget for push and BGTaskScheduler. Completion is prompt on expiry;
/// session admission stays held by the worker until native settlement finishes.
@MainActor final class BackgroundExecution {
    private static var active: [UUID: BackgroundExecution] = [:]
    private let id = UUID()
    private let budget = RecurringSubmissionBudget()
    private var work: Task<Void, Never>?
    private var timer: Task<Void, Never>?
    private var completion: ((Bool) -> Void)?

    init(timeout: Duration = .seconds(25), logFile: LogFile = .shared, completion: @escaping (Bool) -> Void,
         operation: @escaping @Sendable () async throws -> Void) {
        self.completion = completion
        let deadline = ContinuousClock.now + timeout
        let budget = budget
        Self.active[id] = self
        work = Task {
            let success = await ForegroundExecution.$admission.withValue(nil) {
                await RecurringExecutionBudget.$deadline.withValue(deadline) {
                    await RecurringSubmissionBudget.$current.withValue(budget) {
                        do { try Self.checkActive(); try await operation(); try Self.checkActive(); return true }
                        catch { return false }
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
            expire()
        }
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
