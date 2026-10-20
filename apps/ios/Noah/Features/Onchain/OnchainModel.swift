import Foundation
import Observation

@MainActor
@Observable
final class OnchainModel {
    struct Dependencies: Sendable {
        var ready: @Sendable () async -> Bool
        var overview: @Sendable () async throws -> OnchainOverview
        var quote: @Sendable (OnchainAction, [String], UInt64, String?) async throws -> OnchainQuote
        var execute: @Sendable (OnchainQuote) async throws -> String
        var attempt: @Sendable () async throws -> OnchainOperationJournal.Attempt?
        var acknowledge: @Sendable () async throws -> Void
        static func live(session: WalletSession = .shared) -> Self {
            Self(
                ready: {
                    let state = SettingsStorage.shared.persistedState(
                        PersistedWalletState.self, key: PersistedWalletState.key)
                    return await ExitRules.ready(
                        initialized: state?.isInitialized == true, loaded: session.isOpen,
                        suspended: state?.isWalletSuspended == true,
                        background: WalletBackgroundActivity.shared.isRunning)
                }, overview: { try await session.onchainOverview() },
                quote: { try await session.quoteOnchain($0, ids: $1, amount: $2, destination: $3) },
                execute: { try await session.executeOnchain($0) },
                attempt: { try await OnchainOperationJournal.shared.load() },
                acknowledge: { try await OnchainOperationJournal.shared.acknowledge() })
        }
    }
    private let dependencies: Dependencies
    private var generation = UUID()
    private(set) var overview: OnchainOverview?
    private(set) var quote: OnchainQuote?
    private(set) var busy = false
    private(set) var loading = false
    private(set) var attempt: OnchainOperationJournal.Attempt?
    private(set) var message: String?
    private(set) var error: String?
    var locked: Bool { busy || attempt != nil }

    init(dependencies: Dependencies = .live()) { self.dependencies = dependencies }
    func invalidate() {
        generation = UUID()
        quote = nil
    }

    func load() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            attempt = try await dependencies.attempt()
            guard await dependencies.ready() else {
                overview = nil
                invalidate()
                throw OnchainFailure.unavailable
            }
            let token = generation
            let latest = try await dependencies.overview()
            guard token == generation, await dependencies.ready() else { return }
            overview = latest
            error = nil
        } catch {
            self.error =
                (error as? OnchainFailure)?.errorDescription
                ?? "Couldn't read wallet status. Pull to refresh."
        }
    }
    func review(
        _ action: OnchainAction, ids: [String] = [], amount: UInt64 = 0, destination: String? = nil
    ) async {
        guard !busy else { return }
        invalidate()
        error = nil
        message = nil
        busy = true
        let token = generation
        defer { busy = false }
        do {
            guard await dependencies.ready() else { throw OnchainFailure.unavailable }
            guard try await dependencies.attempt() == nil else { throw OnchainFailure.busy }
            let fresh = try await dependencies.quote(action, ids, amount, destination)
            guard generation == token, await dependencies.ready() else { return }
            quote = fresh
        } catch {
            self.error =
                (error as? OnchainFailure)?.errorDescription
                ?? "Couldn't estimate fees. Nothing was submitted. Review again when the wallet is ready."
        }
    }
    /// Caller must have presented the quote and obtained explicit confirmation.
    func confirm() async {
        guard !busy, let quote else { return }
        self.quote = nil
        busy = true
        error = nil
        defer { busy = false }
        do {
            guard await dependencies.ready(), ExitRules.current(reviewedAt: quote.createdAt) else {
                throw OnchainFailure.changed
            }
            message = try await dependencies.execute(quote)
        } catch {
            self.error =
                (error as? OnchainFailure)?.errorDescription ?? OnchainFailure.uncertain.errorDescription
        }
        let submissionError = error
        await load()
        if let submissionError { error = submissionError }
    }
    func acknowledge() async {
        do {
            try await dependencies.acknowledge()
            attempt = nil
            message = nil
        } catch { self.error = OnchainFailure.uncertain.errorDescription }
    }
}

/// Native equivalent of RN's isBackgroundJobRunning query gate; counts overlapping pushes.
actor WalletBackgroundActivity {
    static let shared = WalletBackgroundActivity()
    private var count = 0
    var isRunning: Bool { count > 0 }
    func begin() { count += 1 }
    func end() { count -= 1 }
}
