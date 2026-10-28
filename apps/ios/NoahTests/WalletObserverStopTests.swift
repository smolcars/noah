import Foundation
@testable import Noah
import Testing

struct WalletObserverStopTests {
    private final class Wait: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<Void, Never>?
        private var attempts = 0
        var cancelAttempts: Int { lock.withLock { attempts } }

        func next() async {
            await withCheckedContinuation { continuation in
                lock.withLock { self.continuation = continuation }
            }
        }

        func cancelWait() {
            let waiting = lock.withLock {
                attempts += 1
                let waiting = continuation
                continuation = nil
                return waiting
            }
            waiting?.resume()
        }
    }

    @Test(.timeLimit(.minutes(1))) func stopRetriesRegistrationGapWithoutCancellingReader() async {
        let wait = Wait()
        let stop = WalletObserverStop { wait.cancelWait() }
        let closing = Task { await stop.stop() }
        let deadline = ContinuousClock.now + .seconds(2)
        while wait.cancelAttempts == 0, ContinuousClock.now < deadline { await Task.yield() }
        #expect(wait.cancelAttempts > 0)
        // The first cancellation had no registered wait. A cancelled caller still retries.
        closing.cancel()
        let reader = Task.detached {
            await wait.next()
            #expect(!Task.isCancelled)
            stop.finish()
        }
        await closing.value
        await reader.value
        #expect(stop.isStopped)
        #expect(wait.cancelAttempts >= 2)
        await stop.stop()
    }
}
