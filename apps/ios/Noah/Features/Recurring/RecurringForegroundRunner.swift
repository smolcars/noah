import UIKit

/// The scene owns the timer; the executor owns payment settlement and joins other triggers.
@MainActor final class RecurringForegroundRunner: NSObject {
    private let sleep: @MainActor @Sendable (Duration) async throws -> Void
    private let execute: @MainActor @Sendable () async -> Void
    private var task: Task<Void, Never>?

    init(center: NotificationCenter = .default,
         sleep: @escaping @MainActor @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
         execute: @escaping @MainActor @Sendable () async -> Void) {
        self.sleep = sleep
        self.execute = execute
        super.init()
        center.addObserver(self, selector: #selector(stop), name: UIScene.willDeactivateNotification, object: nil)
    }
    deinit { task?.cancel() }

    func start() {
        guard task == nil else { return }
        let sleep = sleep, execute = execute
        task = Task {
            while !Task.isCancelled {
                do { try await sleep(.seconds(60)) } catch { return }
                guard !Task.isCancelled else { return }
                await execute()
            }
        }
    }
    @objc func stop() {
        task?.cancel()
        task = nil
    }
}
