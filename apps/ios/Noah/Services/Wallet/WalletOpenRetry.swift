import Foundation

/// Tries a failed wallet open again while Noah stays in front. Bark's open asks the chain server which network it
/// serves, so the open fails whenever that request does: no connection yet at launch, or a server error.
/// One loop at a time; the wait doubles from 2 s to 30 s.
@MainActor final class WalletOpenRetry {
    private let sleep: @MainActor @Sendable (Duration) async throws -> Void
    private var task: Task<Void, Never>?
    var isRunning: Bool { task != nil }

    init(sleep: @escaping @MainActor @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.sleep = sleep
    }

    /// `open` tries once and says whether the loop is over: the wallet is open, or nothing should open it now.
    func start(_ open: @escaping @MainActor @Sendable () async -> Bool) {
        guard task == nil else { return }
        // A return to the front starts this under its pass's budget, which ends with the pass: the loop must not keep it.
        task = RecurringSubmissionBudget.$current.withValue(nil) { Task {
            var wait = Duration.seconds(2)
            while (try? await sleep(wait)) != nil, !(await open()) { wait = min(wait * 2, .seconds(30)) }
            task = nil
        } }
    }
}
