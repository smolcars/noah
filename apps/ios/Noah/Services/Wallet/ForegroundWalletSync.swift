import Foundation

/// Foreground reentry and minute ticks share admission before either outer gate can wait.
actor ForegroundWalletSync {
    private var running = false
    private let configuration: WalletMutationGate
    private let mutation: WalletMutationGate

    init(configuration: WalletMutationGate = AppServices.configurationAccess, mutation: WalletMutationGate = .shared) {
        self.configuration = configuration
        self.mutation = mutation
    }

    @discardableResult
    func run<T: Sendable>(_ operation: @escaping @Sendable () async throws -> T) async throws -> T? {
        guard !running else { return nil }
        running = true
        defer { running = false }
        return try await configuration.withLock {
            try await self.mutation.withLock(operation)
        }
    }
}
