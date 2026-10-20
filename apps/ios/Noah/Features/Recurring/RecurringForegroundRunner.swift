import UIKit

/// The scene owns the timer; the executor owns payment settlement and joins other triggers.
@MainActor final class RecurringForegroundRunner: NSObject {
    private let sleep: @MainActor @Sendable (Duration) async throws -> Void
    private let execute: @MainActor @Sendable () async -> Void
    private var task: Task<Void, Never>?
    private var budget: RecurringSubmissionBudget?

    init(center: NotificationCenter = .default,
         sleep: @escaping @MainActor @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
         execute: @escaping @MainActor @Sendable () async -> Void) {
        self.sleep = sleep
        self.execute = execute
        super.init()
        center.addObserver(self, selector: #selector(stop), name: UIScene.willDeactivateNotification, object: nil)
    }
    deinit { budget?.revoke(); task?.cancel() }

    func start() {
        guard task == nil else { return }
        let sleep = sleep, execute = execute
        task = Task { [weak self] in
            while !Task.isCancelled {
                do { try await sleep(.seconds(60)) } catch { return }
                guard !Task.isCancelled else { return }
                let tick = RecurringSubmissionBudget()
                self?.budget = tick
                await RecurringSubmissionBudget.$current.withValue(tick) { await execute() }
                tick.revoke()
                if self?.budget === tick { self?.budget = nil }
            }
        }
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
