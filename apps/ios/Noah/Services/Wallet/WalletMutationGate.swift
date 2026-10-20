import Foundation

/// One mutation across Settings, onboarding and headless recovery, including their awaits.
actor WalletMutationGate {
    static let shared = WalletMutationGate()
    private var held = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    var waitingCount: Int { waiters.count }

    func withLock<T: Sendable>(_ operation: @Sendable () async throws -> T) async rethrows -> T {
        if held { await withCheckedContinuation { waiters.append($0) } }
        else { held = true }
        defer {
            if waiters.isEmpty { held = false }
            else { waiters.removeFirst().resume() }
        }
        return try await operation()
    }
}
