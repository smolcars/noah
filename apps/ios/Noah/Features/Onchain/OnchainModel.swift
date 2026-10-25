import BarkBoltFFI
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
        var estimate: @Sendable ([String]) async throws -> EmergencyExitFeeEstimate = { _ in throw OnchainFailure.unavailable }
        var exclusions: @Sendable () async throws -> [ExcludedExitVtxo] = { [] }
        /// Where Noah keeps when it first saw each exit transaction in its status (in memory unless given).
        var sightings: ExitSightings.Store? = nil
        /// Something these pages show may have changed: the wallet committed (a round or renew, an exit step, an
        /// on-chain sync that found funds) or an on-chain operation moved. Coalesced to the latest.
        var changes: @Sendable () async -> AsyncStream<Void> = { AsyncStream { $0.finish() } }
        /// Waits while the wallet is only briefly not ready (`ExitRules.settling`), as after every app switch; true once
        /// it's ready.
        var settle: @Sendable () async -> Bool = { false }
        static func live(session: WalletSession = .shared) -> Self {
            @Sendable func wallet(_ rule: (Bool, Bool, Bool, Bool) -> Bool) async -> Bool {
                let state = SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
                return await rule(state?.isInitialized != false, session.isOpen, state?.isWalletSuspended == true,
                                  WalletBackgroundActivity.shared.isRunning)
            }
            return Self(
                ready: { await wallet(ExitRules.ready) }, overview: { try await session.onchainOverview() },
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
                discardReview: { await session.discardBoard($0) },
                estimate: { try await session.exitEstimate($0) },
                exclusions: { try await session.exitExclusions() },
                sightings: .init(key: ExitSightings.key),
                changes: {
                    Self.changes(subscribe: { try await session.stateChanges() }, journal: OnchainOperationJournal.shared.changes.stream())
                },
                settle: {
                    // shortcut: checks every half second for up to 30 s; a stream of the session's state would replace it.
                    for _ in 0..<60 {
                        guard await wallet(ExitRules.settling), (try? await Task.sleep(for: .milliseconds(500))) != nil else { break }
                    }
                    return await wallet(ExitRules.ready)
                })
        }

        /// The wallet's commits and the journal's changes, coalesced. The journal is subscribed before this returns.
        /// bark's subscription starts later, so its `initial` event counts as a change too: a commit after the page's
        /// first read began but before the subscription existed has no other event (as History's observer).
        static func changes(subscribe: @escaping @Sendable () async throws -> AsyncThrowingStream<StateChangeEvent, Error>,
                            journal: AsyncStream<Void>) -> AsyncStream<Void> {
            let (stream, continuation) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
            let wallet = Task {
                // bark's subscription ends after `resyncRequired`; subscribe again then, as History does.
                var resubscribe = true
                while resubscribe, !Task.isCancelled, let events = try? await subscribe() {
                    resubscribe = false
                    do {
                        for try await event in events {
                            continuation.yield()
                            if event.reason == "resyncRequired" { resubscribe = true }
                        }
                    } catch { return }
                }
            }
            let journal = Task { for await _ in journal { continuation.yield() } }
            continuation.onTermination = { _ in wallet.cancel(); journal.cancel() }
            return stream
        }

        /// Home's Renew: the same review, submitted through the renewal path that rechecks
        /// eligibility under the sync flight and the journal's operation.
        static func renew(session: WalletSession = .shared) -> Self {
            var dependencies = live(session: session)
            dependencies.execute = { try await session.executeRenewal($0) }
            return dependencies
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

    /// A load asked for while one runs runs again after it, so a change it may have missed isn't dropped.
    func load() async {
        guard !loading else { reloadPending = true; return }
        loading = true
        defer { loading = false }
        repeat {
            reloadPending = false
            await read()
        } while reloadPending
    }
    @ObservationIgnored private var reloadPending = false

    /// Reloads as soon as `changes` reports one, rather than at the page's next minute. `canLoad` is the page's guard.
    func observeChanges(canLoad: @escaping () -> Bool) async {
        for await _ in await dependencies.changes() where canLoad() { await load() }
    }

    private func read() async {
        do {
            let saved = try await dependencies.attempt()
            attempts = saved.filter { $0.overrideAudit?.acknowledgedAt == nil }
            // A sync, a background job or a reopen holds the wallet for a moment: keep what's shown and wait for it.
            if await !dependencies.ready(), await !dependencies.settle() {
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
                guard token == generation else { return }
                // The wallet got busy during the read: read again, which waits for it.
                guard await dependencies.ready() else { reloadPending = true; return }
                exit = detail
            }
            let latest = try await dependencies.overview()
            guard token == generation else { return }
            guard await dependencies.ready() else { reloadPending = true; return }
            overview = latest
            var seen = sightings.walletID == latest.walletID ? sightings.value
                : dependencies.sightings?.load(walletID: latest.walletID) ?? .init()
            if seen.observe(latest.exits, tip: latest.tip, now: Date()) { try? dependencies.sightings?.save(seen, walletID: latest.walletID) }
            sightings = (latest.walletID, seen)
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
            Self.remember(ExitRefusal(error))
            self.error =
                (error as? OnchainFailure)?.errorDescription ?? ExitRefusal.message(error)
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
    /// Caller must have presented the quote and obtained explicit confirmation. True only when it was
    /// submitted: an estimate that failed at submit leaves "Continue without estimate" instead.
    @discardableResult
    func confirm() async -> Bool {
        guard !busy, let quote else { return false }
        self.quote = nil
        expiry?.cancel()
        let token = generation
        busy = true
        error = nil
        defer { busy = false }
        var submitted = false
        do {
            guard await dependencies.ready(), generation == token, quote.isCurrent else {
                throw OnchainFailure.changed
            }
            message = try await dependencies.execute(quote)
            submitted = true
        } catch let failure as ExitEstimateUnavailable {
            if await dependencies.ready(), generation == token, !Task.isCancelled {
                do { estimateFallback = try failure.preserving(quote).review }
                catch { self.error = OnchainFailure.changed.errorDescription }
            }
        } catch {
            // A typed exit error here came from the re-quote or bark's refused start: nothing was broadcast.
            Self.remember(ExitRefusal(error))
            self.error =
                (error as? OnchainFailure)?.errorDescription ?? ExitRefusal.message(error) ?? OnchainFailure.uncertain.errorDescription
        }
        await dependencies.discardReview(quote)
        let submissionError = error
        await load()
        if let submissionError { error = submissionError }
        return submitted && estimateFallback == nil
    }
    /// When this wallet's exit transactions were first seen in their status.
    private(set) var sightings: (walletID: String, value: ExitSightings) = ("", .init())
    /// When the app last left or came back. bark's daemon doesn't run while it's away, so the daemon's
    /// clock counts from here at the earliest, whatever clock the last read brought.
    private(set) var sceneChangedAt = Date.distantPast
    func sceneChanged(at now: Date = Date()) { sceneChangedAt = now }

    /// The exit as its screens show it, with the needs-funds inference.
    func progress(_ exit: ExitModel, now: Date = Date()) -> ExitProgress {
        let parkedSince = exit.parkedEpisode.flatMap { sightings.value.seen[$0]?.at }
        let needsFunds = overview.map {
            ExitFunds.needsFunds(exit, confirmed: $0.confirmed, packageFee: $0.packageFees[exit.id], proven: $0.provenParked,
                                 parkedSince: parkedSince, daemonSince: $0.daemonSince.map { max($0, sceneChangedAt) }, now: now)
        } ?? false
        let claimSent = attempts.contains { attempt in
            attempt.quote.action == .claim && attempt.quote.ids.contains(exit.id) && attempt.receipt?.txid.map(exit.awaitsClaim) == true
        }
        return ExitProgress(exit, tip: overview?.tip ?? exit.details.tip_height ?? 0, needsFunds: needsFunds, claimSent: claimSent,
                            checkedTip: overview?.checkedTip,
                            progressing: overview.map { $0.daemonSince != nil && !$0.checkFailed } ?? true, seen: sightings.value.tips)
    }

    /// Why bark refused the last exit estimate, when it can never exit what was asked.
    private(set) var exitRefusal: ExitRefusal?

    /// The VTXOs bark named as never exitable, for the rest of the app run: Choose VTXOs never offers them.
    private(set) static var refused: [String: ExitRefusal] = [:]
    private static func remember(_ refusal: ExitRefusal?) {
        if let refusal, let id = refusal.vtxoID { refused[id] = refusal }
    }

    /// The exit estimate for a selection, or nil when it can't be read (the review still offers
    /// "Continue without estimate", unless bark refused: `exitRefusal`). Read-only.
    func exitEstimate(_ ids: [String]) async -> EmergencyExitFeeEstimate? {
        guard !ids.isEmpty, await dependencies.ready() else { return nil }
        do {
            let estimate = try await dependencies.estimate(ids)
            exitRefusal = nil
            return estimate
        } catch {
            exitRefusal = ExitRefusal(error)
            Self.remember(exitRefusal)
            return nil
        }
    }

    /// The VTXOs bark's whole-wallet exit leaves out, to renew first; none when that can't be read
    /// (each VTXO's own estimate still refuses them).
    @discardableResult
    func exitExclusions() async -> [String: ExitRefusal] {
        guard await dependencies.ready(), let excluded = try? await dependencies.exclusions() else { return [:] }
        // Non-standard, or (code Other) not loadable: either way renewing it is the way out.
        let refusals = Dictionary(excluded.map { ($0.vtxoId, ExitRefusal(code: .exitNonStandardVtxo, vtxoID: $0.vtxoId)) },
                                  uniquingKeysWith: { a, _ in a })
        refusals.values.forEach(Self.remember)
        return refusals
    }

    /// Decision 4: each VTXO's classification, `.failed` where bark couldn't estimate it.
    func exitCosts(_ vtxos: [VtxoModel]) async -> [String: ExitEconomics.Cost] {
        guard await dependencies.ready() else { return Dictionary(uniqueKeysWithValues: vtxos.map { ($0.id, .failed) }) }
        let estimate = dependencies.estimate
        let costs = await ExitEconomics.classify(vtxos) { try await estimate($0).totalFeeSats }
        // A one-VTXO estimate's refusal is that VTXO's, whether or not bark names it.
        for (id, cost) in costs { if let refusal = cost.refusal { Self.refused[id] = refusal } }
        return costs
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
            // Only an uncertain board proven never boarded leaves the journal on a check.
            message = !attempts.contains(where: { $0.id == id }) ? OnchainFailure.boardNotAttempted.errorDescription
                : resolved ? "Status resolved. Review the receipt." : "Status remains uncertain. Noah checks again automatically as your wallet syncs."
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
        } catch { self.error = (error as? OnchainFailure)?.errorDescription ?? OnchainFailure.uncertain.errorDescription }
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
