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
            let runs = Self.exitRuns(handles)
            let models = try await handles.wallet.listAllExits(includeHistory: true, includeTransactions: false)
                .map { Self.exitModel($0, runs: runs) }
            var overview = OnchainOverview(
                walletID: handles.wallet.fingerprint(), vtxos: vtxos, exits: models,
                confirmed: try await handles.onchain.balance().confirmedSats, tip: tip,
                pendingTotal: try await handles.wallet.pendingExitsTotalSats(),
                claimableHeight: try await handles.wallet.allExitsClaimableAtHeight())
            // What the exit screens add; optional, so a failed read never hides the exits themselves.
            overview.exitDelta = try? await handles.wallet.arkInfo()?.vtxoExitDelta
            overview.claimRecords = ExitClaimRecord.Store(key: ExitClaimRecord.key).load(walletID: overview.walletID) ?? [:]
            overview.daemonSince = await self.daemonSince
            let check = await self.exitChecks[overview.walletID]
            if let check {
                overview.checkedTip = check.tip
                overview.provenParked = check.parked
                overview.checkFailed = check.failed
            }
            guard !models.isEmpty else { return overview }
            let children = Set(models.flatMap { $0.path.compactMap(\.status.child_txid) })
            let claims = Set(models.compactMap(\.details.claimTxid))
            for transaction in (try? await handles.onchain.transactions()) ?? [] {
                // BDK can't price a fee child (its anchor input isn't the wallet's); what left the wallet is its fee.
                if children.contains(transaction.txid), let fee = transaction.onchainFeeSats
                    ?? (transaction.balanceChangeSats < 0 ? UInt64(-transaction.balanceChangeSats) : nil) {
                    overview.childFees[transaction.txid] = fee
                }
                if claims.contains(transaction.txid), transaction.balanceChangeSats > 0 {
                    overview.received[transaction.txid] = UInt64(transaction.balanceChangeSats)
                }
            }
            // The parked package's own fee: its parent's weight and bark's canonical child at the rate bark's
            // exits pay (its estimate's). Unpriced, needs-funds stays unknown.
            for exit in models {
                guard let txid = exit.parkedTxid else { continue }
                // bark's own figure, when Check the chain reported this parking episode short of confirmed funds.
                if let needed = check?.needed[exit.id], overview.provenParked[exit.id] == exit.parkedEpisode {
                    overview.packageFees[exit.id] = needed
                    continue
                }
                guard let status = try? await handles.wallet.getExitStatus(vtxoId: exit.id, includeHistory: false, includeTransactions: true),
                      let hex = status.transactions.first(where: { $0.parentTxid == txid })?.parentTxHex,
                      let weight = BitcoinTransaction.weight(hex: hex),
                      let rate = try? await handles.wallet.estimateEmergencyExitFee(
                          vtxoIds: [exit.id], feeRateSatPerVb: nil, destination: nil, feeMargin: 1).feeRateSatPerVb
                else { continue }
                overview.packageFees[exit.id] = ExitFunds.packageFee(parentWeight: weight, satPerVb: rate)
            }
            return overview
        }
    }

    /// The emergency exit estimate for these VTXOs; read-only, it never starts or sends anything.
    /// An empty list would price the whole wallet, so it's refused.
    func exitEstimate(_ ids: [String]) async throws -> EmergencyExitFeeEstimate {
        guard !ids.isEmpty else { throw OnchainFailure.invalid }
        return try await withOpenWallet {
            try await $0.wallet.estimateEmergencyExitFee(vtxoIds: ids, feeRateSatPerVb: nil, destination: nil)
        }
    }

    /// What bark's whole-wallet exit wouldn't start (FFI 0.7.0's `excludedVtxos` for `vtxoIds: []`):
    /// non-standard VTXOs, dust included. Read-only; only the left-out list is used, never its prices.
    func exitExclusions() async throws -> [ExcludedExitVtxo] {
        try await withOpenWallet {
            try await $0.wallet.estimateEmergencyExitFee(vtxoIds: [], feeRateSatPerVb: nil, destination: nil).excludedVtxos
        }
    }

    /// One coin by ID, spent ones included (Activity's coin links): nil when the wallet has no such
    /// coin, a throw when it couldn't be read. A tip that can't be read leaves it at 0.
    func vtxo(_ id: String) async throws -> VtxoModel? {
        try await withOpenWallet { handles in
            let tip = (try? await handles.onchain.tipHeight()) ?? 0
            return try await Self.lookUp(id, byID: { try await handles.wallet.getVtxoById(vtxoId: $0) },
                                         all: { try await handles.wallet.allVtxos() }, id: \.id)
                .map { Self.vtxoModel($0, tip: tip, expiring: []) }
        }
    }

    /// bark-bolt-ffi has no typed "not found" and its error text is never matched: a failed by-ID read
    /// is settled by the full list (spent coins included). Absent there, the record is missing; if the
    /// list can't be read either, the lookup failed.
    static func lookUp<Record>(_ id: String, byID: (String) async throws -> Record, all: () async throws -> [Record],
                               id identify: (Record) -> String) async throws -> Record? {
        if let record = try? await byID(id) { return record }
        return try await all().first { identify($0) == id }
    }

    func exitDetail(_ id: String) async throws -> ExitModel? {
        try await withOpenWallet { handles in
            try await handles.wallet.getExitStatus(vtxoId: id, includeHistory: true, includeTransactions: false)
                .map { Self.exitModel($0, runs: Self.exitRuns(handles)) }
        }
    }

    static func exitModel(_ status: ExitTransactionStatus, runs: [String: String] = [:]) -> ExitModel {
        let current = Self.exitState(status.state)
        let history = (status.history ?? []).map(Self.exitState)
        return .init(id: status.vtxoId, amount: status.amountSats, state: current.0, details: current.1,
                     history: history.map(\.0), historyDetails: history.map(\.1), claimable: current.0 == "Claimable",
                     run: runs[status.vtxoId] ?? "")
    }

    private static func exitRuns(_ handles: Handles) -> [String: String] {
        ExitRuns.Store(key: ExitRuns.key).load(walletID: handles.wallet.fingerprint()) ?? [:]
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
        guard ExitRules.acceptsClaimAddress(address, network: network) else { throw OnchainFailure.invalid }
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
            let funding = try await AutoBoardFunding.confirmed(walletID: handles.wallet.fingerprint(),
                utxos: handles.onchain.utxos(), transactions: handles.onchain.transactions())
            // An exit claim to this phone is never a deposit, however its claim was recorded.
            let claims = Set(try await handles.wallet.listAllExits(includeHistory: false, includeTransactions: false)
                .compactMap { Self.exitModel($0).details.claimTxid })
            let claimed = funding.outpoints.filter { claims.contains(String($0.prefix { $0 != ":" })) }
            try await MainActor.run { try AutoBoardFunding.markClaimed(claimed, walletID: funding.walletID) }
            return funding
        }
    }

    /// Preparation is held only for the visible confirmation; never submitted by automation. Exit
    /// claims to this phone stay on-chain: their value is left out of the threshold and the amount.
    func autoBoardQuote() async throws -> OnchainQuote? {
        try await withOpenWallet { handles in
            guard let info = try await handles.wallet.arkInfo() else { throw OnchainFailure.unavailable }
            let balance = try await handles.onchain.balance().confirmedSats
            let claims = try await Self.exitClaims(handles)
            guard balance > claims.held, balance - claims.held > BoardingRules.reserve + BoardingRules.threshold(info.minBoardAmountSats)
            else { return nil }
            return try await self.prepareBoardReview(handles, requested: 0, automatic: true, claims: claims)
        }
    }

    /// This wallet's exit claims to this phone, and what of them auto-board leaves on-chain.
    static func exitClaims(_ handles: Handles) async throws -> AutoBoardFunding.Claims {
        let outpoints = try AutoBoardFunding().claims(walletID: handles.wallet.fingerprint())
        return .init(outpoints: outpoints, held: AutoBoardFunding.held(outpoints, utxos: try await handles.onchain.utxos()))
    }

    private func prepareBoardReview(_ handles: Handles, requested: UInt64, automatic: Bool,
                                    claims: AutoBoardFunding.Claims = .init()) async throws -> OnchainQuote? {
        let balance = try await handles.onchain.balance().confirmedSats
        let rate = SendFlow.sdkFeeRate(satsPerKwu: try await handles.onchain.feeRates().regularSatPerKwu)
        let walletID = handles.wallet.fingerprint()
        let minimum = try await handles.wallet.arkInfo()?.minBoardAmountSats ?? 0
        @Sendable func prepare(_ amount: UInt64, excluding: Set<String>?) async throws -> OnchainQuote {
            let board = try await handles.wallet.prepareBoard(
                amount: amount == 0 ? .drainAll : .amount(amountSats: amount),
                feeRateSatPerVb: rate, confirmedOnly: true, excludedOutpoints: excluding.map(Self.outpoints))
            let quote = OnchainQuote(action: .board, walletID: walletID, ids: [], amount: board.grossSats,
                fee: board.arkFeeSats, chainFee: board.onchainFeeSats, confirmed: balance, feeRate: rate,
                automatic: automatic, boardAll: !automatic && requested == 0 ? true : nil, prepared: .init(board))
            await self.retainBoardReview(quote, wallet: handles.wallet)
            return quote
        }
        guard automatic else {
            // Move to Ark excludes nothing: the user may move everything, exit claims to this phone included.
            do { return try await prepare(requested, excluding: nil) }
            catch { throw OnchainSubmission.boardFailure(error) ?? error }
        }
        return try await Self.reserveBoardReview(minimum: minimum, feeRate: rate, claims: claims,
            discard: { quote in
                await self.discardBoard(quote)
                guard await self.boardPreparations[quote.prepared!.id] == nil else { throw OnchainFailure.uncertain }
            }, prepare: prepare)
    }

    /// "txid:vout" outpoints for `prepareBoard`; bark ignores malformed and foreign ones.
    static func outpoints(_ points: Set<String>) -> [OutPoint] {
        points.compactMap { point in
            let parts = point.split(separator: ":")
            guard parts.count == 2, let vout = UInt32(parts[1]) else { return nil }
            return OutPoint(txid: String(parts[0]), vout: vout)
        }
    }

    /// `claims`: exit claims to this phone. Coin selection never spends them (`prepare`'s exclusions),
    /// so they stay on-chain; a preparation that spends one anyway is discarded: no prompt, and the next
    /// balance refresh tries again.
    static func reserveBoardReview(
        minimum: UInt64, feeRate: UInt64, claims: AutoBoardFunding.Claims = .init(),
        discard: @Sendable (OnchainQuote) async throws -> Void,
        prepare: @Sendable (UInt64, Set<String>) async throws -> OnchainQuote
    ) async throws -> OnchainQuote? {
        do {
            // Confirmed-only DrainAll of every other coin can afford its own exact input fees.
            let probe = try await prepare(0, claims.outpoints)
            try await discard(probe)
            try Task.checkCancellation()
            // Allow for the extra change output (at most 43 vB) absent from the drain probe.
            let retained = BoardingRules.reserve + 43 * feeRate
            guard probe.amount > retained, probe.amount - retained >= BoardingRules.threshold(minimum) else { return nil }
            let quote = try await prepare(probe.amount - retained, claims.outpoints)
            do {
                try Task.checkCancellation()
                guard !quote.spends(claims.outpoints),
                      quote.amount + quote.chainFee! + BoardingRules.reserve + claims.held <= quote.confirmed,
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
        /// A claim to this phone: its output (the drain's only one) joins auto-board's handled ledger.
        var claimedToPhone: @Sendable (String) async throws -> Void = { _ in }
        /// Keeps what the screens show of a claim (`ExitClaimRecord`), by its txid.
        var recorded: @Sendable (String, ExitClaimRecord) async throws -> Void = { _, _ in }
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
            broadcast: { try await handles.wallet.broadcastTx(txHex: $0) },
            claimedToPhone: { [walletID = handles.wallet.fingerprint()] txid in
                try await MainActor.run { try AutoBoardFunding.markClaimed(["\(txid):0"], walletID: walletID) }
            },
            recorded: { [walletID = handles.wallet.fingerprint()] txid, record in
                let store = ExitClaimRecord.Store(key: ExitClaimRecord.key)
                var records = store.load(walletID: walletID) ?? [:]
                records[txid] = record
                try store.save(records, walletID: walletID)
            })
    }

    /// Start-only SDK seam, like the claim's; callers still hold the counted open-wallet session.
    struct OnchainStartAccess: Sendable {
        var walletID: String
        var confirmed: @Sendable () async throws -> UInt64
        /// Spendable VTXOs' amounts, by ID.
        var spendable: @Sendable () async throws -> [String: UInt64]
        var estimate: @Sendable ([String]) async throws -> EmergencyExitFeeEstimate
        var start: @Sendable ([String]) async throws -> Void
        /// Just before the start: a new run for each exit (`ExitRuns`), so nothing from an earlier run counts.
        var began: @Sendable ([String]) async throws -> Void = { _ in }
    }

    private static func startAccess(_ handles: Handles) -> OnchainStartAccess {
        .init(
            walletID: handles.wallet.fingerprint(),
            confirmed: { try await handles.onchain.balance().confirmedSats },
            spendable: {
                Dictionary(try await handles.wallet.vtxos().filter { $0.state == .spendable }.map { ($0.id, $0.amountSats) },
                           uniquingKeysWith: { a, _ in a })
            },
            estimate: { try await handles.wallet.estimateEmergencyExitFee(vtxoIds: $0, feeRateSatPerVb: nil, destination: nil) },
            start: { try await handles.wallet.startExitForVtxos(vtxoIds: $0) })
    }

    static func startQuote(
        ids: [String], automatic: Bool = false, estimate: Bool = true, access: OnchainStartAccess
    ) async throws -> OnchainQuote {
        let balance = try await access.confirmed()
        guard Set(ids).count == ids.count else { throw OnchainFailure.invalid }
        let spendable = try await access.spendable()
        let selected = ids.compactMap { spendable[$0] }
        guard !ids.isEmpty, selected.count == ids.count else { throw OnchainFailure.changed }
        guard selected.allSatisfy({ $0 >= ExitRules.dust }) else { throw OnchainFailure.invalid }
        let quote = OnchainQuote(
            action: .startExit, walletID: access.walletID, ids: ids.sorted(), amount: selected.reduce(0, +),
            fee: 0, chainFee: 0, confirmed: balance, automatic: automatic)
        return try await exitStartEstimate(quote, estimate: estimate) { try await access.estimate(ids) }
    }

    static func executeStart(
        _ quote: OnchainQuote, journal: OnchainOperationJournal, access: OnchainStartAccess
    ) async throws -> String {
        guard quote.action == .startExit, ExitRules.current(reviewedAt: quote.createdAt) else { throw OnchainFailure.changed }
        guard !quote.feesUnknown || quote.hasUnknownFeeConsent else { throw OnchainFailure.changed }
        let fresh: OnchainQuote
        do {
            fresh = try await startQuote(ids: quote.ids, automatic: quote.automatic, estimate: !quote.hasUnknownFeeConsent, access: access)
        } catch let failure as ExitEstimateUnavailable { throw try failure.preserving(quote) }
        guard quote.matches(fresh), ExitRules.current(reviewedAt: quote.createdAt) else { throw OnchainFailure.changed }
        return try await journal.perform(quote) {
            // The new run is recorded before bark starts, or bark isn't asked (nothing sent yet).
            do { try await access.began(quote.ids) } catch { throw OnchainFailure.constructionFailed }
            try await access.start(quote.ids)
            return "Exit started. Noah progresses it on its own, including in the background when the system lets it, paying broadcast and fee-bump fees from your onchain balance."
        }
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
            // A claim to this phone is recorded before it's sent, or not sent (nothing broadcast yet).
            let expected = BitcoinTransaction.txid(hex: tx)
            if quote.destinationIsMine == true {
                guard let expected else { throw OnchainFailure.constructionFailed }
                do { try await access.claimedToPhone(expected) } catch { throw OnchainFailure.constructionFailed }
            }
            // Its fee for the screens, before it's sent, so an uncertain broadcast still shows it. Never blocks.
            let record = ExitClaimRecord(ids: quote.ids, fee: claim.feeSats, toPhone: quote.destinationIsMine == true)
            if let expected { try? await access.recorded(expected, record) }
            let txid: String
            do { txid = try await access.broadcast(tx) }
            catch { throw OnchainFailure.uncertain }
            if txid != expected { try? await access.recorded(txid, record) }
            // Auto-board's own read also marks claims bark has seen.
            if quote.destinationIsMine == true, txid != expected { try? await access.claimedToPhone(txid) }
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
        if action == .startExit {
            return try await startQuote(ids: ids, automatic: automatic, estimate: estimate, access: startAccess(handles))
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
        case .refresh:
            let available = try await handles.wallet.vtxos().filter { $0.state == .spendable }
            let selected = available.filter { ids.contains($0.id) }
            guard !ids.isEmpty, selected.count == ids.count else { throw OnchainFailure.changed }
            quote.amount = selected.reduce(0) { $0 + $1.amountSats }
            let fee = try await handles.wallet.estimateRefreshFee(vtxoIds: ids)
            quote.fee = fee.feeSats
        case .startExit:
            throw OnchainFailure.invalid // Handled by the start path above.
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
                    confirmed: { try await handles.onchain.balance().confirmedSats }, claims: { try await Self.exitClaims(handles) },
                    funding: funding,
                    readFunding: {
                        guard try await handles.wallet.pendingBoards().isEmpty else { return nil }
                        return try await AutoBoardFunding.confirmed(walletID: handles.wallet.fingerprint(),
                            utxos: handles.onchain.utxos(), transactions: handles.onchain.transactions())
                    })
            }
            if quote.action == .claim {
                return try await Self.executeClaim(quote, journal: journal, access: Self.claimAccess(handles))
            }
            if quote.action == .startExit {
                var access = Self.startAccess(handles)
                let walletID = handles.wallet.fingerprint()
                access.began = { ids in
                    await self.forgetExitProof(ids, walletID: walletID)
                    try ExitRuns.begin(ids, walletID: walletID)
                }
                return try await Self.executeStart(quote, journal: journal, access: access)
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
                    throw OnchainFailure.invalid // Handled by the start path above.
                case .progress:
                    let failures = try await self.progressExits(quote.feeRate, access: Self.exitProgressAccess(handles))
                    return OnchainReceipt(
                        message: failures == 0
                            ? OnchainReceipt.progressedMessage
                            : "Exit progression returned \(failures) errors. Transactions may have been broadcast or fee-bumped. Refresh exit details before continuing.")
                case .claim:
                    throw OnchainFailure.invalid // Handled by the shared claim path above.
                case .cancel:
                    let result = try await handles.wallet.cancelExit(vtxoId: quote.ids[0])
                    guard result.canceled else { throw OnchainFailure.cancellationRefused }
                    await self.forgetExitProof(quote.ids, walletID: handles.wallet.fingerprint())
                    return "Exit canceled. The VTXO remains spendable."
                }
            }
        }
    }

    /// What exit progress reads and calls, so its rules are tested without the SDK.
    struct ExitProgressAccess: Sendable {
        var walletID: String
        var exits: @Sendable () async throws -> [ExitVtxo]
        var progress: @Sendable (_ feeRate: UInt64?) async throws -> [ExitProgressStatus]
        /// Every exit with its history, read after the call.
        var models: @Sendable () async throws -> [ExitModel]
        var tip: @Sendable () async throws -> UInt32
    }

    private static func exitProgressAccess(_ handles: Handles) -> ExitProgressAccess {
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
        let (tried, needed, failures) = Self.progressOutcome(statuses)
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
        catch is CancellationError {}
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
    static func progressOutcome(_ statuses: [ExitProgressStatus]) -> (tried: Set<String>, needed: [String: UInt64], failures: Int) {
        let short = statuses.filter { $0.error?.code == .exitInsufficientConfirmedFunds }
        let needed = Dictionary(short.compactMap { status in status.error?.neededSats.map { (status.vtxoId, $0) } },
                                uniquingKeysWith: { a, _ in a })
        return (Set((statuses.filter { $0.error == nil } + short).map(\.vtxoId)), needed,
                statuses.filter { $0.error != nil }.count - short.count)
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
                }, transactionStatus: { .init(try await handles.wallet.transactionStatus(txid: $0)) },
                vtxoAnchors: { Set(try await handles.wallet.allVtxos().map(\.anchorPoint.txid)) },
                unspent: {
                    Set(try await handles.onchain.utxos().compactMap {
                        if case let .local(point, _, _) = $0 { "\(point.txid):\(point.vout)" } else { nil }
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
        _ id: UUID, journal: OnchainOperationJournal, reads: OnchainAttemptReads
    ) async throws -> Bool {
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
                try await journal.recordStatusError(for: id)
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
                        let status: ExitDetails.Transaction.Status
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
