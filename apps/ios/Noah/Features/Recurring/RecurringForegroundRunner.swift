import UIKit

/// The scene owns the timer; the executor owns payment settlement and joins other triggers.
@MainActor final class RecurringForegroundRunner: NSObject {
    private let sleep: @MainActor @Sendable (Duration) async throws -> Void
    private let isActive: @MainActor @Sendable () -> Bool
    private let execute: @MainActor @Sendable () async -> Void
    private var task: Task<Void, Never>?
    var isRunning: Bool { task != nil }
    private var budget: RecurringSubmissionBudget?

    init(center: NotificationCenter = .default,
         sleep: @escaping @MainActor @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
         isActive: @escaping @MainActor @Sendable () -> Bool = { ForegroundExecution.isActive },
         execute: @escaping @MainActor @Sendable () async -> Void) {
        self.sleep = sleep
        self.execute = execute
        self.isActive = isActive
        super.init()
        for name in [UIScene.willDeactivateNotification, UIScene.didEnterBackgroundNotification,
                     UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification] {
            center.addObserver(self, selector: #selector(stop), name: name, object: nil)
        }
    }
    deinit { budget?.revoke(); task?.cancel() }

    func start() {
        guard task == nil, isActive() else { return }
        let sleep = sleep, execute = execute, isActive = isActive
        // Reentry's budget ends before the timer's first tick; each tick admits fresh scene-bound work.
        task = RecurringSubmissionBudget.$current.withValue(nil) { Task { [weak self] in
            while !Task.isCancelled {
                do { try await sleep(.seconds(60)) } catch { return }
                guard !Task.isCancelled else { return }
                guard isActive() else { self?.stop(); return }
                let tick = RecurringSubmissionBudget()
                self?.budget = tick
                await ForegroundExecution.run(budget: tick, isActive: isActive, execute)
                tick.revoke()
                if self?.budget === tick { self?.budget = nil }
            }
        } }
    }
    @objc func stop() {
        budget?.revoke()
        budget = nil
        task?.cancel()
        task = nil
    }
}

/// Inherited by independent executor/Send tasks; revocation forbids submission, not settlement.
final class RecurringSubmissionBudget: @unchecked Sendable {
    @TaskLocal static var current: RecurringSubmissionBudget?
    static var available: Bool { current?.isAvailable ?? true }
    private let lock = NSLock()
    private var revoked = false
    private var isAvailable: Bool { lock.withLock { !revoked } }
    func revoke() { lock.withLock { revoked = true } }
}
