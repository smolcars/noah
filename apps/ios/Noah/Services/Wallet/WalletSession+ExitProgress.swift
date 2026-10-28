import BarkBoltFFI
import Foundation
import Synchronization

/// Exit progress, renewals, attempt checks and the VTXO and exit models read from bark.
extension WalletSession {
    /// What exit progress reads and calls, so its rules are tested without the SDK.
    struct ExitProgressAccess: Sendable {
        var walletID: String
        var exits: @Sendable () async throws -> [ExitVtxo]
        var progress: @Sendable (_ feeRate: UInt64?) async throws -> [ExitProgressStatus]
        /// Every exit with its history, read after the call.
        var models: @Sendable () async throws -> [ExitModel]
        var tip: @Sendable () async throws -> UInt32
    }

    static func exitProgressAccess(_ handles: Handles) -> ExitProgressAccess {
        .init(
            walletID: handles.wallet.fingerprint(),
            exits: { try await handles.wallet.getExitVtxos() },
            progress: { try await handles.wallet.progressExits(feeRateSatPerVb: $0) },
            models: {
                let runs = Self.exitRuns(handles)
                return try await handles.wallet.listAllExits(includeHistory: true, includeTransactions: false)
                    .map { Self.exitModel($0, runs: runs) }
            },
            tip: { try await handles.onchain.tipHeight() })
    }

    /// One exit progress at `feeRate` (nil: bark's own), recorded as the wallet's last exit check, which the
    /// exit screens read; how many exits failed otherwise.
    nonisolated func progressExits(_ feeRate: UInt64?, access: ExitProgressAccess) async throws -> Int {
        let statuses: [ExitProgressStatus]
        do { statuses = try await access.progress(feeRate) }
        catch {
            await recordFailedExitCheck(walletID: access.walletID)
            throw error
        }
        let outcome = Self.progressOutcome(statuses)
        let tried = outcome.tried, needed = outcome.needed, failures = outcome.failures
        // A completed call tried to fund every parked transaction; those still parked had too
        // few confirmed funds (Decision 3), in this parking episode only, so the history is
        // read again. Other errors mark the check failed: exits may be stale.
        let parked = ((try? await access.models()) ?? []).filter { tried.contains($0.id) }
            .compactMap { exit in exit.parkedEpisode.map { (exit.id, $0) } }
        let proven = Dictionary(parked, uniquingKeysWith: { a, _ in a })
        await recordExitCheck(.init(tip: try? await access.tip(), parked: proven,
                                    needed: needed.filter { proven[$0.key] != nil },
                                    failed: failures > 0), walletID: access.walletID)
        return failures
    }

    /// Background jobs' exit progress; their opens run without bark's daemon, which otherwise moves exits.
    /// Skipped once the job's budget is gone. Never throws: a failure's reason, safe to log, goes to `failed`.
    nonisolated func progressPendingExits(failed: @Sendable (String) -> Void) async {
        do { _ = try await withOpenWallet { try await self.progressPendingExits(access: Self.exitProgressAccess($0)) } }
        catch is CancellationError { /* The budget ended: not a failure. */ }
        catch { failed(SafeErrorDescription.of(error)) }
    }

    /// Nothing without a pending exit (Move exit forward's own filter), else one call at bark's own fee rate,
    /// recorded like a completed Move exit forward, never toasted. True when it called bark.
    @discardableResult
    nonisolated func progressPendingExits(access: ExitProgressAccess) async throws -> Bool {
        guard try await access.exits().contains(where: { ExitRules.live(Self.exitState($0.state).0) }) else { return false }
        try BackgroundExecution.checkActive()
        _ = try await progressExits(nil, access: access)
        return true
    }

    /// One Check the chain, read through FFI 0.7.0's typed errors: the exits it tried to fund, bark's
    /// figure for those it reported short of confirmed funds (direct evidence for "Needs on-chain funds";
    /// bark 0.8.0 itself doesn't report it yet), and how many failed otherwise. Messages are never read.
    struct ProgressOutcome { let tried: Set<String>; let needed: [String: UInt64]; let failures: Int }
    static func progressOutcome(_ statuses: [ExitProgressStatus]) -> ProgressOutcome {
        let short = statuses.filter { $0.error?.code == .exitInsufficientConfirmedFunds }
        let needed = Dictionary(short.compactMap { status in status.error?.neededSats.map { (status.vtxoId, $0) } },
                                uniquingKeysWith: { a, _ in a })
        return ProgressOutcome(tried: Set((statuses.filter { $0.error == nil } + short).map(\.vtxoId)), needed: needed,
                               failures: statuses.count(where: { $0.error != nil }) - short.count)
    }

    /// Home's Renew. The review is the existing refresh review; submission takes this path
    /// instead of `executeOnchain`, so it can carry the expiry threshold. Manual refreshes
    /// (the VTXO screen refreshes any spendable VTXO) keep `executeOnchain`.
    func executeRenewal(_ quote: OnchainQuote, journal: OnchainOperationJournal = .shared) async throws -> String {
        let flight = syncFlight
        let threshold = UInt32(WalletConfiguration.base(for: .current).vtxoRefreshExpiryThreshold)
        return try await withOpenWallet { handles in
            let reads = RenewalReads(
                requote: {
                    try await Self.onchainQuote(handles, request: QuoteRequest(action: .refresh, ids: quote.ids, amount: quote.amount,
                                                                               destination: nil, automatic: quote.automatic))
                },
                expiring: { try await handles.wallet.getExpiringVtxos(thresholdBlocks: $0).map(Self.renewalCoin) },
                tip: { try await handles.onchain.tipHeight() },
                refresh: { ids in
                    let round = try await handles.wallet.refreshVtxosDelegated(vtxoIds: ids)
                    return OnchainReceipt(
                        message: round.map { "Refresh submitted to delegated round \($0.id)." }
                            ?? "No refresh round was needed.")
                })
            return try await Self.submitRenewal(quote, threshold: threshold, journal: journal, flight: flight, reads: reads)
        }
    }

    /// Holds the sync flight from the final re-quote to submission, so no app sync can move
    /// the tip meanwhile, and runs the eligibility check inside the journal's single on-chain
    /// operation, immediately before submitting with nothing awaited in between: every VTXO
    /// must still be in the expiring set at this threshold and tip, spendable and unexpired.
    /// Otherwise, or if either can't be read, nothing is submitted and the review has changed.
    /// What a renewal reads and calls, so its checks are tested without the SDK.
    struct RenewalReads: Sendable {
        var requote: @Sendable () async throws -> OnchainQuote
        var expiring: @Sendable (UInt32) async throws -> [HomeRenewal.Coin]
        var tip: @Sendable () async throws -> UInt32
        var refresh: @Sendable ([String]) async throws -> OnchainReceipt
    }
    static func submitRenewal(
        _ quote: OnchainQuote, threshold: UInt32, journal: OnchainOperationJournal, flight: WalletSync, reads: RenewalReads) async throws -> String {
        let requote = reads.requote, expiring = reads.expiring, tip = reads.tip, refresh = reads.refresh
        guard quote.action == .refresh, ExitRules.current(elapsed: quote.elapsed()) else { throw OnchainFailure.changed }
        let message = Mutex<String?>(nil)
        try await flight.serialized {
            let fresh = try await requote()
            guard quote.matches(fresh), ExitRules.current(elapsed: quote.elapsed()) else { throw OnchainFailure.changed }
            let receipt = try await journal.perform(quote) {
                // The expiring set first, then the tip it's judged against. Nothing has been submitted
                // if either read fails, so that refuses like a changed set and the attempt is dropped;
                // only the submission itself can leave an uncertain one.
                let current: HomeRenewal?
                do { current = HomeRenewal.select(try await expiring(threshold), tip: try await tip()) }
                catch { throw OnchainFailure.changed }
                guard HomeRenewal.allEligible(quote.ids, in: current) else { throw OnchainFailure.changed }
                return try await refresh(quote.ids)
            }
            message.withLock { $0 = receipt }
        }
        guard let result = message.withLock({ $0 }) else { throw OnchainFailure.uncertain }
        return result
    }

    static func renewalCoin(_ vtxo: Vtxo) -> HomeRenewal.Coin {
        .init(id: vtxo.id, sats: vtxo.amountSats, expiryHeight: vtxo.expiryHeight, spendable: vtxo.state == .spendable)
    }

    func refreshOnchainReceiptFees(journal: OnchainOperationJournal = .shared) async throws {
        let attempts = try await journal.loadAll()
        guard attempts.contains(where: {
            $0.quote.action == .board && $0.receipt?.txid != nil && $0.receipt?.onchainFee == nil
        }) else { return }
        try await withOpenWallet { handles in
            let transactions = try await handles.onchain.transactions()
            let fees = Dictionary(transactions.compactMap { transaction -> (String, UInt64)? in
                transaction.onchainFeeSats.map { (transaction.txid, $0) }
            }, uniquingKeysWith: { first, _ in first })
            try await journal.refreshBoardFees(walletID: handles.wallet.fingerprint(), fees: fees)
        }
    }

    /// Cached wallet evidence plus read-only chain status; never BDK sync or rebroadcast.
    /// An absent txid or inconclusive check must remain unresolved.
    func checkOnchainAttempt(_ id: UUID, journal: OnchainOperationJournal = .shared) async throws -> Bool {
        try await withOpenWallet { handles in
            try await Self.checkOnchainAttempt(id, journal: journal, reads: .init(
                walletID: handles.wallet.fingerprint(),
                transactionIDs: {
                    let cached = try await handles.onchain.transactions().map(\.txid)
                    let pending = try await handles.wallet.pendingBoards().map(\.txid)
                    // A board movement can precede its durable checkpoint; metadata alone is not submission evidence.
                    return Set(cached + pending)
                },
                vtxoState: { id in
                    let vtxo = try await handles.wallet.getVtxoById(vtxoId: id)
                    return Self.vtxoModel(vtxo, tip: 0, expiring: []).state
                },
                exitState: { id in
                    guard let status = try await handles.wallet.getExitStatus(
                        vtxoId: id, includeHistory: true, includeTransactions: false) else { return nil }
                    return Self.exitState(status.state).0
                }, transactionStatus: { .init(try await handles.wallet.transactionStatus(txid: $0)) },
                vtxoAnchors: { Set(try await handles.wallet.allVtxos().map(\.anchorPoint.txid)) },
                unspent: {
                    Set(try await handles.onchain.utxos().compactMap {
                        if case .local(let point, _, _) = $0 { "\(point.txid):\(point.vout)" } else { nil }
                    })
                }))
        }
    }

    /// Narrow test seam deliberately provides no sync, progression or broadcast capability.
    struct OnchainAttemptReads: Sendable {
        var walletID: String
        var transactionIDs: @Sendable () async throws -> Set<String>
        var vtxoState: @Sendable (String) async throws -> String
        var exitState: @Sendable (String) async throws -> String?
        var transactionStatus: @Sendable (String) async throws -> ChainTransactionStatus? = { _ in nil }
        /// The funding txids stored VTXOs are anchored to, spent ones included: a board's VTXO proves the board.
        var vtxoAnchors: @Sendable () async throws -> Set<String> = { [] }
        /// The wallet's unspent outpoints ("txid:vout").
        var unspent: @Sendable () async throws -> Set<String> = { [] }
    }

    static func checkOnchainAttempt(
        _ id: UUID, journal: OnchainOperationJournal, reads: OnchainAttemptReads) async throws -> Bool {
        guard let attempt = try await journal.loadAll().first(where: { $0.id == id }),
              attempt.quote.walletID == reads.walletID else { throw OnchainFailure.changed }
        var evidence = OnchainOperationJournal.Evidence(walletID: reads.walletID)
        evidence.transactionIDs = try await reads.transactionIDs()
        // A stored board VTXO outlives its checkpoint and BDK transaction.
        if attempt.quote.action == .board { evidence.transactionIDs.formUnion(try await reads.vtxoAnchors()) }
        for id in attempt.quote.ids {
            if attempt.quote.action == .refresh { evidence.vtxoStates[id] = try await reads.vtxoState(id) }
            evidence.exitStates[id] = try await reads.exitState(id)
        }
        var resolved = try await journal.reconcile(id, evidence: evidence)
        var chain: ChainTransactionStatus?
        if let txid = attempt.txid ?? attempt.receipt?.txid {
            do { chain = try await reads.transactionStatus(txid) }
            catch {
                await journal.recordStatusError(for: id)
                return resolved || attempt.receipt != nil
            }
            if let status = chain {
                if status != .notFound {
                    evidence.transactionIDs.insert(txid)
                    if !resolved { resolved = try await journal.reconcile(id, evidence: evidence) }
                }
                try await journal.observe(status, for: id)
            }
        }
        // FFI 0.7.0's uncertain board is released only once nothing was boarded: no pending board or wallet
        // transaction names its funding txid, the chain source says it's not found, and every input is still an
        // unspent wallet coin. A BOARD movement alone doesn't hold it: bark only had the unsigned transaction, and
        // the signed bytes died with the FFI's consumed preparation. Any doubt (coins unreadable too) keeps it.
        if !resolved, attempt.uncertainBoard == true, let txid = attempt.txid, chain == .notFound,
           !evidence.transactionIDs.contains(txid), let inputs = attempt.quote.prepared?.inputs, !inputs.isEmpty,
           let unspent = try? await reads.unspent(), inputs.allSatisfy({ unspent.contains("\($0.txid):\($0.vout)") }) {
            try await journal.releaseUnboarded(id)
            return true
        }
        return resolved || attempt.receipt != nil
    }

    static func vtxoModel(_ value: Vtxo, tip: UInt32, expiring: Set<String>) -> VtxoModel {
        let state: String
        switch value.state {
        case .spendable: state = "Spendable"
        case .locked: state = "Locked"
        case .spent: state = "Spent"
        case .exited: state = "Exited"
        case .unknown: state = "Unknown"
        }
        return .init(
            id: value.id, amount: value.amountSats, expiry: value.expiryHeight, state: state,
            kind: value.kind,
            exitDepth: value.exitDepth, exitWeight: value.exitTxWeightWu, registered: value.registered,
            serverPubkey: value.serverPubkey, exitDelta: value.exitDelta,
            anchorPoint: "\(value.anchorPoint.txid):\(value.anchorPoint.vout)",
            point: "\(value.point.txid):\(value.point.vout)",
            expiring: expiring.contains(value.id), tip: tip)
    }

    static func exitState(_ value: ExitState) -> (String, ExitDetails) {
        func block(_ value: BlockRef) -> ExitDetails.Block {
            .init(height: value.height, hash: value.hash)
        }
        func origin(_ value: ExitTxOrigin) -> String {
            switch value {
            case .wallet: "wallet"
            case .mempool: "mempool"
            case .block: "block"
            }
        }
        switch value {
        case .start(let tip): return ("Start", .init(kind: "start", tip_height: tip))
        case .processing(let tip, let transactions):
            return (
                "Processing",
                .init(
                    kind: "processing", tip_height: tip,
                    transactions: transactions.map { tx in
                        let status: ExitDetails.TransactionStatus
                        switch tx.status {
                        case .verifyInputs: status = .init(kind: "verify-inputs")
                        case .awaitingInputConfirmation: status = .init(kind: "awaiting-input-confirmation")
                        case .awaitingCpfpBroadcast: status = .init(kind: "awaiting-cpfp-broadcast")
                        case .awaitingConfirmation(let child, let from):
                            status = .init(kind: "awaiting-confirmation", child_txid: child, origin: origin(from))
                        case .confirmed(let child, let ref, let from):
                            status = .init(kind: "confirmed", child_txid: child, block: block(ref), origin: origin(from))
                        }
                        return .init(txid: tx.txid, status: status)
                    }))
        case .awaitingDelta(let tip, let ref, let height):
            return (
                "AwaitingDelta",
                .init(
                    kind: "awaiting-delta", tip_height: tip, confirmed_block: block(ref),
                    claimable_height: height))
        case .claimable(let tip, let since, let scanned):
            return (
                "Claimable",
                .init(
                    kind: "claimable", tip_height: tip, claimable_since: block(since),
                    last_scanned_block: scanned.map(block)))
        case .claimInProgress(let tip, let since, let tx):
            return (
                "ClaimInProgress",
                .init(
                    kind: "claim-in-progress", tip_height: tip, claimable_since: block(since), claim_txid: tx))
        case .claimed(let tip, let tx, let ref):
            return ("Claimed", .init(kind: "claimed", tip_height: tip, block: block(ref), txid: tx))
        case .vtxoAlreadySpent(let tip):
            return ("VtxoAlreadySpent", .init(kind: "vtxo-already-spent", tip_height: tip))
        case .vtxoSwept(let tip, _): return ("VtxoSwept", .init(kind: "vtxo-swept", tip_height: tip))
        case .canceled(let tip): return ("Canceled", .init(kind: "canceled", tip_height: tip))
        }
    }
}
