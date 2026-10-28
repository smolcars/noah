import UIKit

/// Foreground tasks retain revocable admission across sleeps, gates and native settlement.
enum ForegroundExecution {
    @TaskLocal static var admission: (@MainActor @Sendable () -> Bool)?

    @MainActor static var isActive: Bool {
        UIApplication.shared.applicationState == .active &&
            UIApplication.shared.connectedScenes.contains { $0.activationState == .foregroundActive }
    }

    @MainActor static func run(budget: RecurringSubmissionBudget = .init(),
                               isActive: @escaping @MainActor @Sendable () -> Bool = { Self.isActive },
                               _ operation: () async -> Void) async {
        guard !Task.isCancelled, RecurringSubmissionBudget.available, RecurringExecutionBudget.available, isActive() else { budget.revoke(); return }
        let center = NotificationCenter.default
        let observers = [UIScene.willDeactivateNotification, UIScene.didEnterBackgroundNotification,
                         UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification].map {
            center.addObserver(forName: $0, object: nil, queue: nil) { _ in budget.revoke() }
        }
        defer { budget.revoke(); observers.forEach { center.removeObserver($0) } }
        await $admission.withValue(isActive) {
            await RecurringSubmissionBudget.$current.withValue(budget) { await operation() }
        }
    }

    /// Background jobs have their own budget; only inherited foreground work checks scene state.
    @MainActor static func checkActiveNow() throws {
        guard let admission else { return }
        guard admission() else {
            RecurringSubmissionBudget.current?.revoke()
            throw CancellationError()
        }
        try BackgroundExecution.checkActive()
    }

    nonisolated static func checkActive() async throws {
        guard admission != nil else { return }
        try await checkActiveNow()
    }
}
