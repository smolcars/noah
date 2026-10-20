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
        var attempt: @Sendable () async throws -> [OnchainOperationJournal.Attempt]
        var acknowledge: @Sendable (UUID) async throws -> Void
        var check: @Sendable (UUID) async throws -> Bool = { _ in false }
        var discard: @Sendable (UUID) async throws -> Void = { _ in }
        var detail: @Sendable (String) async throws -> ExitModel? = { _ in nil }
        var refreshReceipts: @Sendable () async throws -> Void = {}
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
                attempt: { try await OnchainOperationJournal.shared.loadAll() },
                acknowledge: { try await OnchainOperationJournal.shared.acknowledge($0) },
                check: { try await session.checkOnchainAttempt($0) },
                discard: { try await OnchainOperationJournal.shared.discardUncertain($0) },
                detail: { try await session.exitDetail($0) },
                refreshReceipts: { try await session.refreshOnchainReceiptFees() })
        }
    }
    private let dependencies: Dependencies
    private let detailID: String?
    private(set) var exit: ExitModel?
    private var generation = UUID()
    private(set) var overview: OnchainOverview?
    private(set) var quote: OnchainQuote?
    private(set) var busy = false
    private(set) var loading = false
    private(set) var attempts: [OnchainOperationJournal.Attempt] = []
    var attempt: OnchainOperationJournal.Attempt? { attempts.first }
    private(set) var message: String?
    private(set) var error: String?
    var locked: Bool { busy }

    init(dependencies: Dependencies = .live(), detailID: String? = nil) {
        self.dependencies = dependencies
        self.detailID = detailID
    }
    func invalidate() {
        generation = UUID()
        quote = nil
    }

    func load() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            attempts = try await dependencies.attempt()
            guard await dependencies.ready() else {
                overview = nil
                exit = nil
                invalidate()
                throw OnchainFailure.unavailable
            }
            // Fee lookup is optional after submission; it must never hide an existing receipt.
            try? await dependencies.refreshReceipts()
            attempts = try await dependencies.attempt()
            let token = generation
            if let detailID {
                let detail = try await dependencies.detail(detailID)
                guard token == generation, await dependencies.ready() else { return }
                exit = detail
            }
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
            attempts = try await dependencies.attempt()
            guard !attempts.contains(where: { $0.blocks(action, ids: ids) }) else { throw OnchainFailure.busy }
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
    func acknowledge(_ id: UUID) async {
        do {
            try await dependencies.acknowledge(id)
            attempts.removeAll { $0.id == id }
            message = nil
        } catch { self.error = OnchainFailure.uncertain.errorDescription }
    }
    func checkStatus(_ id: UUID) async {
        guard !busy else { return }
        busy = true
        invalidate()
        defer { busy = false }
        do {
            guard await dependencies.ready() else { throw OnchainFailure.unavailable }
            let resolved = try await dependencies.check(id)
            await load()
            message = resolved ? "Status resolved. Review the receipt." : "Status remains uncertain. No operation was repeated. You may use other recovery operations."
        } catch {
            self.error = (error as? OnchainFailure)?.errorDescription ?? "Couldn't check status. The attempt is still saved."
        }
    }
    func discardUncertain(_ id: UUID) async {
        guard !busy else { return }
        busy = true
        invalidate()
        defer { busy = false }
        do {
            try await dependencies.discard(id)
            attempts.removeAll { $0.id == id }
            message = "Attempt cleared. Review the current wallet state before submitting again."
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
