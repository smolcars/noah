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

    init(timeout: Duration = .seconds(25), completion: @escaping (Bool) -> Void,
         operation: @escaping @Sendable () async throws -> Void) {
        self.completion = completion
        let deadline = ContinuousClock.now + timeout
        let budget = budget
        Self.active[id] = self
        work = Task {
            let success = await RecurringExecutionBudget.$deadline.withValue(deadline) {
                await RecurringSubmissionBudget.$current.withValue(budget) {
                    do { try Task.checkCancellation(); try await operation(); try Task.checkCancellation(); return true }
                    catch { return false }
                }
            }
            finish(success)
            Self.active[id] = nil
        }
        timer = Task {
            do { try await Task.sleep(until: deadline, clock: .continuous) }
            catch { return }
            expire()
        }
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
