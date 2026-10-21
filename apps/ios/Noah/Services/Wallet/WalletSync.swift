import Foundation

/// Drop overlapping ticks; attempt every independent RN sync stage unless admission is revoked.
actor WalletSync {
    private var running = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func run(_ stages: [@Sendable () async throws -> Void]) async throws {
        guard !running else { return }
        running = true
        defer { release() }
        try await Self.attemptAll(stages)
    }

    /// Reconciliation needs a fresh sync, so it waits instead of dropping its request.
    func serialized(_ stage: @Sendable () async throws -> Void) async throws {
        while running { await withCheckedContinuation { waiters.append($0) } }
        try BackgroundExecution.checkActive()
        running = true
        defer { release() }
        try await stage()
    }

    private func release() {
        running = false
        let pending = waiters
        waiters = []
        pending.forEach { $0.resume() }
    }

    static func attemptAll(_ stages: [@Sendable () async throws -> Void]) async throws {
        var failure: Error?
        for stage in stages {
            try await ForegroundExecution.checkActive()
            try BackgroundExecution.checkActive()
            do { try await stage() }
            catch { if failure == nil { failure = error } }
        }
        if let failure { throw failure }
    }
}
