import Foundation

/// Stop the SDK wait, then await its normal return; never cancel/free a polling FFI future.
final class WalletObserverStop: @unchecked Sendable {
    private let lock = NSLock()
    private var stopped = false
    private var finished = false
    private let cancelWait: @Sendable () -> Void

    init(cancelWait: @escaping @Sendable () -> Void) { self.cancelWait = cancelWait }

    var isStopped: Bool { lock.withLock { stopped } }
    func requestStop() { lock.withLock { stopped = true } }
    func finish() { lock.withLock { finished = true } }

    func stop() async {
        requestStop()
        // A cancelled close caller must still suspend between stop attempts.
        await Task {
            while !lock.withLock({ finished }) {
                // SDK cancellation affects only a registered wait. Retry across the registration gap.
                cancelWait()
                try? await Task.sleep(for: .milliseconds(5))
            }
        }.value
    }
}
