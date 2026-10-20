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

    func run(_ operation: @escaping @Sendable () async throws -> Void) async throws {
        guard !running else { return }
        running = true
        defer { running = false }
        try await configuration.withLock {
            try await self.mutation.withLock(operation)
        }
    }
}
