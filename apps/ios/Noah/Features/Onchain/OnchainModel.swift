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
        var claimAddress: @Sendable () async throws -> String = { throw OnchainFailure.unavailable }
        var claimOwnership: @Sendable (String) async throws -> Bool = { _ in throw OnchainFailure.unavailable }
        var discardReview: @Sendable (OnchainQuote) async -> Void = { _ in }
        static func live(session: WalletSession = .shared) -> Self {
            Self(
                ready: {
                    let state = SettingsStorage.shared.persistedState(
                        PersistedWalletState.self, key: PersistedWalletState.key)
                    return await ExitRules.ready(
                        initialized: state?.isInitialized != false, loaded: session.isOpen,
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
                refreshReceipts: { try await session.refreshOnchainReceiptFees() },
                claimAddress: { try await session.exitDepositAddress() },
                claimOwnership: { try await session.exitClaimDestinationIsMine($0) },
                discardReview: { await session.discardBoard($0) })
        }
    }
    private let dependencies: Dependencies
    private let detailID: String?
    private(set) var exit: ExitModel?
    private(set) var claimDestination = ""
    private(set) var claimDestinationIsMine: Bool?
    private(set) var claimDestinationError: String?
    func editClaimDestination(_ value: String) {
        claimDestination = value
        claimDestinationIsMine = nil
        claimDestinationError = nil
        invalidate()
    }
    func useWalletClaimAddress() async {
        let previous = claimDestination
        do {
            guard await dependencies.ready() else { throw OnchainFailure.unavailable }
            let address = try await dependencies.claimAddress()
            if await dependencies.ready(), previous == claimDestination { editClaimDestination(address) }
        } catch {
            claimDestinationError = "Couldn't load a Noah wallet address. Try again when the wallet is ready."
        }
    }
    func checkClaimDestination() async {
        let address = claimDestination
        claimDestinationIsMine = nil
        claimDestinationError = nil
        guard !address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        do {
            guard await dependencies.ready() else { throw OnchainFailure.unavailable }
            let mine = try await dependencies.claimOwnership(address.trimmingCharacters(in: .whitespacesAndNewlines))
            if await dependencies.ready(), address == claimDestination { claimDestinationIsMine = mine }
        } catch {
            if address == claimDestination {
                claimDestinationError = "Enter a valid Bitcoin address for this network when the wallet is ready."
            }
        }
    }
    private var generation = UUID()
    private var expiry: Task<Void, Never>?
    private(set) var overview: OnchainOverview?
    private(set) var estimateFallback: OnchainQuote?
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
        expiry?.cancel()
        expiry = nil
        if let quote {
            let discard = dependencies.discardReview
            Task { await discard(quote) }
        }
        quote = nil
        estimateFallback = nil
    }

    func load() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            let saved = try await dependencies.attempt()
            attempts = saved.filter { $0.overrideAudit?.acknowledgedAt == nil }
            guard await dependencies.ready() else {
                overview = nil
                exit = nil
                invalidate()
                throw OnchainFailure.unavailable
            }
            // Reconcile retained originals too; a later daemon sync can resolve an override.
            var statusFailed = false
            for attempt in saved where attempt.receipt == nil || attempt.receipt?.txid != nil {
                do { _ = try await dependencies.check(attempt.id) }
                catch { statusFailed = true }
            }
            // Fee lookup is optional after submission; it must never hide an existing receipt.
            try? await dependencies.refreshReceipts()
            attempts = try await dependencies.attempt().filter { $0.overrideAudit?.acknowledgedAt == nil }
            let token = generation
            if let detailID {
                let detail = try await dependencies.detail(detailID)
                guard await dependencies.ready(), token == generation else { return }
                exit = detail
            }
            let latest = try await dependencies.overview()
            guard await dependencies.ready(), token == generation else { return }
            overview = latest
            error = statusFailed ? "Couldn't check network status. Showing the last known status." : nil
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
            attempts = try await dependencies.attempt().filter { $0.overrideAudit?.acknowledgedAt == nil }
            guard !attempts.contains(where: { $0.blocks(action, ids: ids) }) else { throw OnchainFailure.busy }
            let fresh = try await dependencies.quote(action, ids, amount, destination)
            guard await dependencies.ready(), generation == token, !Task.isCancelled else {
                await dependencies.discardReview(fresh)
                return
            }
            quote = fresh
            if fresh.prepared != nil {
                expiry = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(120)) } catch { return }
                    self?.invalidate()
                }
            }
        } catch let failure as ExitEstimateUnavailable {
            if await dependencies.ready(), generation == token, !Task.isCancelled {
                estimateFallback = failure.review
            }
        } catch {
            self.error =
                (error as? OnchainFailure)?.errorDescription
                ?? "Couldn't prepare the review. Nothing was submitted. Try again when the wallet is ready."
        }
    }
    /// Explicit first consent opens a separate confirmation for this exact, expiring review.
    func continueWithoutEstimate() async {
        guard !busy, var fallback = estimateFallback else { return }
        estimateFallback = nil
        let token = generation
        guard await dependencies.ready(), token == generation, fallback.isCurrent else { invalidate(); return }
        fallback.unknownFeeConsent = fallback.id
        quote = fallback
    }
    /// Caller must have presented the quote and obtained explicit confirmation.
    func confirm() async {
        guard !busy, let quote else { return }
        self.quote = nil
        expiry?.cancel()
        let token = generation
        busy = true
        error = nil
        defer { busy = false }
        do {
            guard await dependencies.ready(), generation == token, quote.isCurrent else {
                throw OnchainFailure.changed
            }
            message = try await dependencies.execute(quote)
        } catch let failure as ExitEstimateUnavailable {
            if await dependencies.ready(), generation == token, !Task.isCancelled {
                do { estimateFallback = try failure.preserving(quote).review }
                catch { self.error = OnchainFailure.changed.errorDescription }
            }
        } catch {
            self.error =
                (error as? OnchainFailure)?.errorDescription ?? OnchainFailure.uncertain.errorDescription
        }
        await dependencies.discardReview(quote)
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
            message = resolved ? "Status resolved. Review the receipt." : "Status remains uncertain. Noah checks again automatically as your wallet syncs."
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
            attempts = try await dependencies.attempt().filter { $0.overrideAudit?.acknowledgedAt == nil }
            message = "Repeat protection overridden. The original attempt is still saved. Review the current wallet state before submitting again."
        } catch { self.error = OnchainFailure.uncertain.errorDescription }
    }

}

/// Native equivalent of RN's isBackgroundJobRunning query gate; counts overlapping pushes.
actor WalletBackgroundActivity {
    static let shared = WalletBackgroundActivity()
    private var count = 0
    var isRunning: Bool { count > 0 }
    struct Snapshot: Equatable, Sendable {
        var isRunning: Bool
        var completionRevision: UInt64
        var revision: UInt64 = 0
    }
    private var completionRevision: UInt64 = 0
    private var revision: UInt64 = 0
    private var observers: [UUID: AsyncStream<Snapshot>.Continuation] = [:]
    var snapshot: Snapshot { .init(isRunning: isRunning, completionRevision: completionRevision, revision: revision) }

    func changes() -> AsyncStream<Snapshot> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<Snapshot>.makeStream(bufferingPolicy: .bufferingNewest(1))
        observers[id] = continuation
        continuation.yield(snapshot)
        continuation.onTermination = { _ in Task { await self.removeObserver(id) } }
        return stream
    }
    private func removeObserver(_ id: UUID) { observers[id] = nil }
    private func publish() { for observer in observers.values { observer.yield(snapshot) } }

    func begin() { count += 1; revision += 1; publish() }
    func end() { count -= 1; completionRevision += 1; revision += 1; publish() }
}
