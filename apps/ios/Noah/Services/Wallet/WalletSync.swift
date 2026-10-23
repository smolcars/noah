import Foundation

/// Drop overlapping ticks; attempt every independent RN sync stage unless admission is revoked.
actor WalletSync {
    private var running = false
    /// Whether the running pass is a full sync. A partial `serialized` pass doesn't stand in for one.
    private var full = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// How often a full pass waiting behind a partial one re-checks its admission.
    static let recheck: Duration = .milliseconds(50)

    func run(_ stages: [@Sendable () async throws -> Void]) async throws {
        // An overlapping full pass covers this one; a partial pass doesn't, so wait it out.
        // Waiting, it withdraws once its admission ends (cancellation, a revoked budget or an
        // expired deadline), releasing its caller's gates; started work still settles natively.
        while running && !full {
            try await ForegroundExecution.checkActive()
            try BackgroundExecution.checkActive()
            try await Task.sleep(for: Self.recheck)
        }
        guard !running else { return }
        running = true
        full = true
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
        full = false
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
