import Foundation
import Synchronization

/// Producers enqueue snapshots without executing observer code or waiting for UI work.
final class HomeStatusUpdates<Value: Sendable>: Sendable {
    private let observers = Mutex<[UUID: AsyncStream<Value>.Continuation]>([:])

    func stream() -> AsyncStream<Value> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<Value>.makeStream(bufferingPolicy: .bufferingNewest(1))
        observers.withLock { $0[id] = continuation }
        continuation.onTermination = { [weak self] _ in
            self?.observers.withLock { $0[id] = nil }
        }
        return stream
    }

    func send(_ value: Value) {
        let continuations = observers.withLock { Array($0.values) }
        for continuation in continuations { continuation.yield(value) }
    }
}
