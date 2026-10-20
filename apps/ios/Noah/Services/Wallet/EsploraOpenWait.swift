import Foundation

/// A UI deadline never cancels native work or releases its session reservation.
final class EsploraOpenWait: @unchecked Sendable {
    struct Timeout: Error {}
    private let lock = NSLock()
    private var waiter: CheckedContinuation<Void, Error>?
    private var expired = false
    private var committed = false
    private var defaultWaiter: CheckedContinuation<Void, Never>?
    private var defaultChosen = false
    private var deadline = ContinuousClock.now

    var timedOut: Bool { lock.withLock { expired } }

    func run(timeout: Duration = .seconds(12),
             operation: @escaping @Sendable (EsploraOpenWait) async throws -> Void,
             lateCompletion: @escaping @Sendable (Bool) async -> Void = { _ in }) async throws {
        try await withCheckedThrowingContinuation { continuation in
            lock.withLock { waiter = continuation; deadline = .now + timeout }
            let timer = Task {
                do { try await Task.sleep(for: timeout) } catch { return }
                self.expire()
            }
            Task {
                let result: Result<Void, Error>
                do { try await operation(self); result = .success(()) }
                catch { result = .failure(error) }
                let late = lock.withLock {
                    timer.cancel()
                    let continuation = waiter; waiter = nil
                    continuation?.resume(with: result)
                    return expired
                }
                if late { await lateCompletion((try? result.get()) != nil) }
            }
        }
    }
    private func expire() {
        lock.withLock {
            guard !committed, let continuation = waiter else { return }
            expired = true; waiter = nil
            continuation.resume(throwing: Timeout())
        }
    }
    /// No awaits remain after commit: the session publishes the handles synchronously.
    func commit(_ persist: @Sendable () throws -> Void) throws {
        try lock.withLock {
            guard !expired, ContinuousClock.now < deadline else {
                expired = true
                waiter?.resume(throwing: Timeout()); waiter = nil
                throw Timeout()
            }
            try persist()
            committed = true
        }
    }
    func chooseDefault() {
        lock.withLock { defaultChosen = true; defaultWaiter?.resume(); defaultWaiter = nil }
    }
    func waitForDefault() async {
        await withCheckedContinuation { continuation in
            lock.withLock {
                if defaultChosen { continuation.resume() } else { defaultWaiter = continuation }
            }
        }
    }
}
