import BarkBoltFFI
import Foundation
import Synchronization

extension WalletSession {
    func onchainOverview() async throws -> OnchainOverview {
        try await withOpenWallet { handles in
            let tip = try await handles.onchain.tipHeight()
            let expiring = Set(
                try await handles.wallet.getExpiringVtxos(
                    thresholdBlocks: UInt32(
                        WalletConfiguration.base(for: .current).vtxoRefreshExpiryThreshold)
                ).map(\.id))
            let vtxos = try await handles.wallet.vtxos().map {
                Self.vtxoModel($0, tip: tip, expiring: expiring)
            }
            let models = try await handles.wallet.listAllExits(includeHistory: true, includeTransactions: false)
                .map(Self.exitModel)
            return OnchainOverview(
                walletID: handles.wallet.fingerprint(), vtxos: vtxos, exits: models,
                confirmed: try await handles.onchain.balance().confirmedSats, tip: tip,
                pendingTotal: try await handles.wallet.pendingExitsTotalSats(),
                claimableHeight: try await handles.wallet.allExitsClaimableAtHeight())
        }
    }

    /// One coin by ID, spent ones included (Activity's coin links).
    func vtxo(_ id: String) async throws -> VtxoModel {
        try await withOpenWallet { handles in
            Self.vtxoModel(try await handles.wallet.getVtxoById(vtxoId: id), tip: try await handles.onchain.tipHeight(), expiring: [])
        }
    }

    func exitDetail(_ id: String) async throws -> ExitModel? {
        try await withOpenWallet { handles in
            try await handles.wallet.getExitStatus(vtxoId: id, includeHistory: true, includeTransactions: false)
                .map(Self.exitModel)
        }
    }

    static func exitModel(_ status: ExitTransactionStatus) -> ExitModel {
        let current = Self.exitState(status.state)
        let history = (status.history ?? []).map(Self.exitState)
        return .init(id: status.vtxoId, amount: status.amountSats, state: current.0, details: current.1,
                     history: history.map(\.0), historyDetails: history.map(\.1), claimable: current.0 == "Claimable")
    }

    func exitDepositAddress() async throws -> String {
        try await withOpenWallet { try await $0.onchain.newAddress() }
    }

    func exitClaimDestinationIsMine(_ address: String) async throws -> Bool {
        try await withOpenWallet { handles in
            try await Self.claimDestinationOwnership(address, network: AppVariant.current.rawValue) {
                try await handles.onchain.isMine(address: $0)
            }
        }
    }

    static func claimDestinationOwnership(
        _ address: String, network: String, isMine: (String) async throws -> Bool
    ) async throws -> Bool {
        guard let actual = PaymentEncoding.bitcoinNetwork(address),
            actual == network || actual == "testnet"
                && (network == "signet" || network == "regtest" && !address.lowercased().hasPrefix("tb1"))
        else { throw OnchainFailure.invalid }
        return try await isMine(address)
    }

    func boardingMinimum() async throws -> UInt64? {
        try await withOpenWallet { try await $0.wallet.arkInfo()?.minBoardAmountSats }
    }

    func quoteOnchain(
        _ action: OnchainAction, ids: [String] = [], amount: UInt64 = 0, destination: String? = nil,
        automatic: Bool = false
    ) async throws -> OnchainQuote {
        try await withOpenWallet { handles in
            if action == .board {
                guard let quote = try await self.prepareBoardReview(handles, requested: amount, automatic: automatic)
                else { throw OnchainFailure.invalid }
                return quote
            }
            return try await Self.onchainQuote(
                handles, action: action, ids: ids, amount: amount, destination: destination,
                automatic: automatic)
        }
    }

    func autoBoardFunding(journal: OnchainOperationJournal = .shared) async throws -> AutoBoardFunding.Snapshot? {
        for attempt in try await journal.loadAll() where attempt.blocks(.board, ids: []) {
            _ = try await checkOnchainAttempt(attempt.id, journal: journal)
        }
        guard !(try await journal.loadAll()).contains(where: { $0.blocks(.board, ids: []) }) else { return nil }
        return try await withOpenWallet { handles in
            guard try await handles.wallet.pendingBoards().isEmpty else { return nil }
            return try await AutoBoardFunding.confirmed(walletID: handles.wallet.fingerprint(),
                utxos: handles.onchain.utxos(), transactions: handles.onchain.transactions())
        }
    }

    /// Preparation is held only for the visible confirmation; never submitted by automation.
    func autoBoardQuote() async throws -> OnchainQuote? {
        try await withOpenWallet { handles in
            guard let info = try await handles.wallet.arkInfo() else { throw OnchainFailure.unavailable }
            let balance = try await handles.onchain.balance().confirmedSats
            guard balance > BoardingRules.reserve + BoardingRules.threshold(info.minBoardAmountSats)
            else { return nil }
            return try await self.prepareBoardReview(handles, requested: 0, automatic: true)
        }
    }

    private func prepareBoardReview(_ handles: Handles, requested: UInt64, automatic: Bool) async throws -> OnchainQuote? {
        let balance = try await handles.onchain.balance().confirmedSats
        let rate = SendFlow.sdkFeeRate(satsPerKwu: try await handles.onchain.feeRates().regularSatPerKwu)
        let walletID = handles.wallet.fingerprint()
        let minimum = try await handles.wallet.arkInfo()?.minBoardAmountSats ?? 0
        @Sendable func prepare(_ amount: UInt64) async throws -> OnchainQuote {
            let board = try await handles.wallet.prepareBoard(
                amount: amount == 0 ? .drainAll : .amount(amountSats: amount),
                feeRateSatPerVb: rate, confirmedOnly: true)
            let quote = OnchainQuote(action: .board, walletID: walletID, ids: [], amount: board.grossSats,
                fee: board.arkFeeSats, chainFee: board.onchainFeeSats, confirmed: balance, feeRate: rate,
                automatic: automatic, boardAll: !automatic && requested == 0 ? true : nil, prepared: .init(board))
            await self.retainBoardReview(quote, wallet: handles.wallet)
            return quote
        }
        guard automatic else {
            do { return try await prepare(requested) }
            catch { throw OnchainSubmission.boardFailure(error) ?? error }
        }
        return try await Self.reserveBoardReview(minimum: minimum, feeRate: rate,
            discard: { quote in
                await self.discardBoard(quote)
                guard await self.boardPreparations[quote.prepared!.id] == nil else { throw OnchainFailure.uncertain }
            }, prepare: prepare)
    }

    static func reserveBoardReview(
        minimum: UInt64, feeRate: UInt64,
        discard: @Sendable (OnchainQuote) async throws -> Void,
        prepare: @Sendable (UInt64) async throws -> OnchainQuote
    ) async throws -> OnchainQuote? {
        do {
            let probe = try await prepare(0) // Confirmed-only DrainAll can afford its own exact input fees.
            try await discard(probe)
            try Task.checkCancellation()
            // Allow for the extra change output (at most 43 vB) absent from the drain probe.
            let retained = BoardingRules.reserve + 43 * feeRate
            guard probe.amount > retained else { return nil }
            let gross = probe.amount - retained
            guard gross >= BoardingRules.threshold(minimum) else { return nil }
            let quote = try await prepare(gross)
            do {
                try Task.checkCancellation()
                guard quote.amount + quote.chainFee! + BoardingRules.reserve <= quote.confirmed,
                      quote.net >= BoardingRules.threshold(minimum) else {
                    try await discard(quote)
                    return nil
                }
                return quote
            } catch {
                try? await discard(quote)
                throw error
            }
        } catch {
            throw OnchainSubmission.boardFailure(error) ?? error
        }
    }

    /// Claim-only SDK seam; callers still hold the counted open-wallet session.
    struct OnchainClaimAccess: Sendable {
        var walletID: String
        var network: String
        var confirmed: @Sendable () async throws -> UInt64
        var exits: @Sendable () async throws -> [ExitVtxo]
        var isMine: @Sendable (String) async throws -> Bool
        var estimate: @Sendable ([String], String) async throws -> EmergencyExitFeeEstimate
        var drain: @Sendable ([String], String) async throws -> ExitClaimTransaction
        var transaction: @Sendable (String) throws -> String = { try extractTxFromPsbt(psbtBase64: $0) }
        var broadcast: @Sendable (String) async throws -> String
    }

    private static func claimAccess(_ handles: Handles) -> OnchainClaimAccess {
        .init(
            walletID: handles.wallet.fingerprint(), network: AppVariant.current.rawValue,
            confirmed: { try await handles.onchain.balance().confirmedSats },
            exits: { try await handles.wallet.getExitVtxos() },
            isMine: { try await handles.onchain.isMine(address: $0) },
            estimate: { try await handles.wallet.estimateEmergencyExitFee(
                vtxoIds: $0, feeRateSatPerVb: nil, destination: $1) },
            drain: { try await handles.wallet.drainExits(vtxoIds: $0, address: $1, feeRateSatPerVb: nil) },
            broadcast: { try await handles.wallet.broadcastTx(txHex: $0) })
    }

    static func claimQuote(
        ids: [String], destination: String?, automatic: Bool = false, estimate: Bool = true, access: OnchainClaimAccess
    ) async throws -> OnchainQuote {
        let balance = try await access.confirmed()
        guard Set(ids).count == ids.count else { throw OnchainFailure.invalid }
        let selected = try await access.exits().filter { $0.isClaimable && ids.contains($0.vtxoId) }
        guard !ids.isEmpty, selected.count == ids.count else { throw OnchainFailure.changed }
        guard let destination, !destination.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw OnchainFailure.invalid
        }
        let mine = try await claimDestinationOwnership(destination, network: access.network, isMine: access.isMine)
        var quote = OnchainQuote(
            action: .claim, walletID: access.walletID, ids: ids.sorted(), destination: destination,
            destinationIsMine: mine, amount: selected.reduce(0) { $0 + $1.amountSats },
            fee: nil, chainFee: nil, confirmed: balance, automatic: automatic)
        if estimate {
            do {
                quote.fee = try await access.estimate(ids, destination).claimFeeSats
                quote.chainFee = 0
            } catch is CancellationError { throw CancellationError() }
            catch {
                guard ExitEstimateUnavailable.allowsFallback(error) else { throw error }
                throw ExitEstimateUnavailable(review: quote)
            }
        }
        return quote
    }

    static func executeClaim(
        _ quote: OnchainQuote, journal: OnchainOperationJournal, access: OnchainClaimAccess
    ) async throws -> String {
        guard quote.action == .claim, ExitRules.current(reviewedAt: quote.createdAt) else { throw OnchainFailure.changed }
        guard !quote.feesUnknown || quote.hasUnknownFeeConsent else { throw OnchainFailure.changed }
        let fresh: OnchainQuote
        do {
            fresh = try await claimQuote(ids: quote.ids, destination: quote.destination, automatic: quote.automatic,
                estimate: !quote.hasUnknownFeeConsent, access: access)
        } catch let failure as ExitEstimateUnavailable { throw try failure.preserving(quote) }
        guard quote.matches(fresh), ExitRules.current(reviewedAt: quote.createdAt) else { throw OnchainFailure.changed }
        return try await journal.perform(quote) {
            let claim = try await access.drain(quote.ids, quote.destination!)
            guard quote.hasUnknownFeeConsent || claim.feeSats == quote.fee else { throw OnchainFailure.claimFeeChanged }
            let tx = try access.transaction(claim.psbtBase64)
            let txid: String
            do { txid = try await access.broadcast(tx) }
            catch { throw OnchainFailure.uncertain }
            return OnchainReceipt(message: "Claim submitted. Refresh to check confirmation.", txid: txid)
        }
    }

    private static func onchainQuote(
        _ handles: Handles, action: OnchainAction, ids: [String], amount requested: UInt64,
        destination: String?, automatic: Bool, estimate: Bool = true
    ) async throws -> OnchainQuote {
        if action == .claim {
            return try await claimQuote(ids: ids, destination: destination, automatic: automatic, access: claimAccess(handles))
        }
        let balance = try await handles.onchain.balance().confirmedSats
        var quote = OnchainQuote(
            action: action, walletID: handles.wallet.fingerprint(), ids: ids.sorted(),
            destination: destination,
            amount: requested, fee: 0, chainFee: 0, confirmed: balance, automatic: automatic)
        guard Set(ids).count == ids.count else { throw OnchainFailure.invalid }
        switch action {
        case .board:
            throw OnchainFailure.changed // Boards must use the retained signed preparation.
        case .refresh, .startExit:
            let available = try await handles.wallet.vtxos().filter { $0.state == .spendable }
            let selected = available.filter { ids.contains($0.id) }
            guard !ids.isEmpty, selected.count == ids.count else { throw OnchainFailure.changed }
            quote.amount = selected.reduce(0) { $0 + $1.amountSats }
            if action == .refresh {
                let fee = try await handles.wallet.estimateRefreshFee(vtxoIds: ids)
                quote.fee = fee.feeSats
            } else {
                guard selected.allSatisfy({ $0.amountSats >= ExitRules.dust }) else {
                    throw OnchainFailure.invalid
                }
                quote = try await exitStartEstimate(quote, estimate: estimate) {
                    try await handles.wallet.estimateEmergencyExitFee(
                        vtxoIds: ids, feeRateSatPerVb: nil, destination: nil)
                }
            }
        case .progress:
            let all = try await handles.wallet.getExitVtxos()
            let eligible = all.filter { exit in
                let state = Self.exitState(exit.state).0
                return !["Canceled", "Claimed", "VtxoAlreadySpent", "VtxoSwept"].contains(state)
            }
            let selected = eligible.filter { ids.contains($0.vtxoId) }
            guard !ids.isEmpty, selected.count == ids.count else { throw OnchainFailure.changed }
            // progressExits acts on ALL pending exits, never just a visible selection.
            if action == .progress, Set(eligible.map(\.vtxoId)) != Set(ids) {
                throw OnchainFailure.changed
            }
            quote.amount = selected.reduce(0) { $0 + $1.amountSats }
            let fee = try await handles.wallet.estimateEmergencyExitFee(
                vtxoIds: ids, feeRateSatPerVb: nil, destination: destination)
            quote.fee = fee.claimFeeSats
            quote.chainFee = fee.exitBroadcastFeeSats
            quote.feeRate = fee.feeRateSatPerVb
        case .claim:
            throw OnchainFailure.invalid // Handled by the shared claim path above.
        case .cancel:
            guard ids.count == 1,
                let exit = try await handles.wallet.getExitVtxos().first(where: { $0.vtxoId == ids[0] }),
                let status = try await handles.wallet.getExitStatus(
                    vtxoId: ids[0], includeHistory: false, includeTransactions: false)
            else { throw OnchainFailure.changed }
            let state = Self.exitState(status.state)
            guard
                ExitRules.cancelable(
                    state: state.0, finalTransactionKind: state.1.transactions?.last?.status.kind)
            else { throw OnchainFailure.cancellationRefused }
            quote.amount = exit.amountSats
        }
        return quote
    }

    static func exitStartEstimate(_ validated: OnchainQuote, estimate: Bool = true,
        fetch: () async throws -> EmergencyExitFeeEstimate) async throws -> OnchainQuote {
        var quote = validated
        quote.fee = nil; quote.chainFee = nil; quote.feeRate = nil
        guard estimate else { return quote }
        do {
            let fee = try await fetch()
            quote.fee = fee.claimFeeSats; quote.chainFee = fee.exitBroadcastFeeSats
            quote.feeRate = fee.feeRateSatPerVb
            return quote
        } catch is CancellationError { throw CancellationError() }
        catch {
            guard ExitEstimateUnavailable.allowsFallback(error) else { throw error }
            throw ExitEstimateUnavailable(review: quote)
        }
    }

    func executeOnchain(_ quote: OnchainQuote, journal: OnchainOperationJournal = .shared,
        funding: AutoBoardFunding.Snapshot? = nil)
        async throws -> String
    {
        return try await withOpenWallet { handles in
            if quote.action == .board {
                return try await self.submitBoard(quote, walletID: handles.wallet.fingerprint(), journal: journal,
                    confirmed: { try await handles.onchain.balance().confirmedSats }, funding: funding,
                    readFunding: {
                        guard try await handles.wallet.pendingBoards().isEmpty else { return nil }
                        return try await AutoBoardFunding.confirmed(walletID: handles.wallet.fingerprint(),
                            utxos: handles.onchain.utxos(), transactions: handles.onchain.transactions())
                    })
            }
            if quote.action == .claim {
                return try await Self.executeClaim(quote, journal: journal, access: Self.claimAccess(handles))
            }
            guard ExitRules.current(reviewedAt: quote.createdAt) else { throw OnchainFailure.changed }
            guard !quote.feesUnknown || quote.hasUnknownFeeConsent else { throw OnchainFailure.changed }
            let fresh: OnchainQuote
            do {
                fresh = try await Self.onchainQuote(
                    handles, action: quote.action, ids: quote.ids, amount: quote.boardAll == true ? 0 : quote.amount,
                    destination: quote.destination, automatic: quote.automatic, estimate: !quote.hasUnknownFeeConsent)
            } catch let failure as ExitEstimateUnavailable { throw try failure.preserving(quote) }
            guard quote.matches(fresh), ExitRules.current(reviewedAt: quote.createdAt) else {
                throw OnchainFailure.changed
            }
            return try await journal.perform(quote) {
                switch quote.action {
                case .board:
                    throw OnchainFailure.changed
                case .refresh:
                    let round = try await handles.wallet.refreshVtxosDelegated(vtxoIds: quote.ids)
                    return OnchainReceipt(
                        message: round.map { "Refresh submitted to delegated round \($0.id)." }
                            ?? "No refresh round was needed.")
                case .startExit:
                    try await handles.wallet.startExitForVtxos(vtxoIds: quote.ids)
                    return "Exit started. Noah progresses it automatically in the background, paying broadcast and fee-bump fees from your onchain balance."
                case .progress:
                    let statuses = try await handles.wallet.progressExits(feeRateSatPerVb: quote.feeRate)
                    let failures = statuses.filter { $0.error != nil }.count
                    return OnchainReceipt(
                        message: failures == 0
                            ? "Exits progressed. Transactions may have been broadcast or fee-bumped."
                            : "Exit progression returned \(failures) errors. Transactions may have been broadcast or fee-bumped. Refresh exit details before continuing.")
                case .claim:
                    throw OnchainFailure.invalid // Handled by the shared claim path above.
                case .cancel:
                    let result = try await handles.wallet.cancelExit(vtxoId: quote.ids[0])
                    guard result.canceled else { throw OnchainFailure.cancellationRefused }
                    return "Exit canceled. The VTXO remains spendable."
                }
            }
        }
    }

    /// Home's Renew. The review is the existing refresh review; submission takes this path
    /// instead of `executeOnchain`, so it can carry the expiry threshold. Manual refreshes
    /// (the VTXO screen refreshes any spendable VTXO) keep `executeOnchain`.
    func executeRenewal(_ quote: OnchainQuote, journal: OnchainOperationJournal = .shared) async throws -> String {
        let flight = syncFlight
        let threshold = UInt32(WalletConfiguration.base(for: .current).vtxoRefreshExpiryThreshold)
        return try await withOpenWallet { handles in
            try await Self.submitRenewal(
                quote, threshold: threshold, journal: journal, flight: flight,
                requote: {
                    try await Self.onchainQuote(handles, action: .refresh, ids: quote.ids, amount: quote.amount,
                                                destination: nil, automatic: quote.automatic)
                },
                expiring: { try await handles.wallet.getExpiringVtxos(thresholdBlocks: $0).map(Self.renewalCoin) },
                tip: { try await handles.onchain.tipHeight() },
                refresh: { ids in
                    let round = try await handles.wallet.refreshVtxosDelegated(vtxoIds: ids)
                    return OnchainReceipt(
                        message: round.map { "Refresh submitted to delegated round \($0.id)." }
                            ?? "No refresh round was needed.")
                })
        }
    }

    /// Holds the sync flight from the final re-quote to submission, so no app sync can move
    /// the tip meanwhile, and runs the eligibility check inside the journal's single on-chain
    /// operation, immediately before submitting with nothing awaited in between: every VTXO
    /// must still be in the expiring set at this threshold and tip, spendable and unexpired.
    /// Otherwise, or if either can't be read, nothing is submitted and the review has changed.
    static func submitRenewal(
        _ quote: OnchainQuote, threshold: UInt32, journal: OnchainOperationJournal, flight: WalletSync,
        requote: @escaping @Sendable () async throws -> OnchainQuote,
        expiring: @escaping @Sendable (UInt32) async throws -> [HomeRenewal.Coin],
        tip: @escaping @Sendable () async throws -> UInt32,
        refresh: @escaping @Sendable ([String]) async throws -> OnchainReceipt
    ) async throws -> String {
        guard quote.action == .refresh, ExitRules.current(reviewedAt: quote.createdAt) else { throw OnchainFailure.changed }
        let message = Mutex<String?>(nil)
        try await flight.serialized {
            let fresh = try await requote()
            guard quote.matches(fresh), ExitRules.current(reviewedAt: quote.createdAt) else { throw OnchainFailure.changed }
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
    // An absent txid or inconclusive check must remain unresolved.
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
                }, transactionStatus: { .init(try await handles.wallet.transactionStatus(txid: $0)) }))
        }
    }

    /// Narrow test seam deliberately provides no sync, progression or broadcast capability.
    struct OnchainAttemptReads: Sendable {
        var walletID: String
        var transactionIDs: @Sendable () async throws -> Set<String>
        var vtxoState: @Sendable (String) async throws -> String
        var exitState: @Sendable (String) async throws -> String?
        var transactionStatus: @Sendable (String) async throws -> ChainTransactionStatus? = { _ in nil }
    }

    static func checkOnchainAttempt(
        _ id: UUID, journal: OnchainOperationJournal, reads: OnchainAttemptReads
    ) async throws -> Bool {
        guard let attempt = try await journal.loadAll().first(where: { $0.id == id }),
            attempt.quote.walletID == reads.walletID else { throw OnchainFailure.changed }
        var evidence = OnchainOperationJournal.Evidence(walletID: reads.walletID)
        evidence.transactionIDs = try await reads.transactionIDs()
        for id in attempt.quote.ids {
            if attempt.quote.action == .refresh { evidence.vtxoStates[id] = try await reads.vtxoState(id) }
            evidence.exitStates[id] = try await reads.exitState(id)
        }
        var resolved = try await journal.reconcile(id, evidence: evidence)
        if let txid = attempt.txid ?? attempt.receipt?.txid {
            let status: ChainTransactionStatus?
            do { status = try await reads.transactionStatus(txid) }
            catch {
                try await journal.recordStatusError(for: id)
                return resolved || attempt.receipt != nil
            }
            if let status {
                if status != .notFound {
                    evidence.transactionIDs.insert(txid)
                    if !resolved { resolved = try await journal.reconcile(id, evidence: evidence) }
                }
                try await journal.observe(status, for: id)
            }
        }
        return resolved || attempt.receipt != nil
    }

    private static func vtxoModel(_ value: Vtxo, tip: UInt32, expiring: Set<String>) -> VtxoModel {
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
        switch value {
        case .start(let tip): return ("Start", .init(kind: "start", tip_height: tip))
        case .processing(let tip, let transactions):
            return (
                "Processing",
                .init(
                    kind: "processing", tip_height: tip,
                    transactions: transactions.map { tx in
                        let status: ExitDetails.Transaction.Status
                        switch tx.status {
                        case .verifyInputs: status = .init(kind: "verify-inputs")
                        case .awaitingInputConfirmation: status = .init(kind: "awaiting-input-confirmation")
                        case .awaitingCpfpBroadcast: status = .init(kind: "awaiting-cpfp-broadcast")
                        case .awaitingConfirmation(let child, _):
                            status = .init(kind: "awaiting-confirmation", child_txid: child)
                        case .confirmed(let child, let ref, _):
                            status = .init(kind: "confirmed", child_txid: child, block: block(ref))
                        }
                        return .init(txid: tx.txid, status: status)
                    })
            )
        case .awaitingDelta(let tip, let ref, let height):
            return (
                "AwaitingDelta",
                .init(
                    kind: "awaiting-delta", tip_height: tip, confirmed_block: block(ref),
                    claimable_height: height)
            )
        case .claimable(let tip, let since, let scanned):
            return (
                "Claimable",
                .init(
                    kind: "claimable", tip_height: tip, claimable_since: block(since),
                    last_scanned_block: scanned.map(block))
            )
        case .claimInProgress(let tip, let since, let tx):
            return (
                "ClaimInProgress",
                .init(
                    kind: "claim-in-progress", tip_height: tip, claimable_since: block(since), claim_txid: tx)
            )
        case .claimed(let tip, let tx, let ref):
            return ("Claimed", .init(kind: "claimed", tip_height: tip, block: block(ref), txid: tx))
        case .vtxoAlreadySpent(let tip):
            return ("VtxoAlreadySpent", .init(kind: "vtxo-already-spent", tip_height: tip))
        case .vtxoSwept(let tip, _): return ("VtxoSwept", .init(kind: "vtxo-swept", tip_height: tip))
        case .canceled(let tip): return ("Canceled", .init(kind: "canceled", tip_height: tip))
        }
    }
}
