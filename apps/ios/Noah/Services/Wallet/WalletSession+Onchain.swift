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
                        WalletConfiguration.base(for: .current).vtxoRefreshExpiryThreshold)).map(\.id))
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
            await Self.addChildFees(handles, models: models, to: &overview)
            await Self.addPackageFees(handles, models: models, check: check, to: &overview)
            return overview
        }
    }

    /// Fee children and claims this wallet paid for or received, from its own transactions.
    private static func addChildFees(_ handles: Handles, models: [ExitModel], to overview: inout OnchainOverview) async {
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
    }

    private static func addPackageFees(_ handles: Handles, models: [ExitModel], check: ExitCheck?, to overview: inout OnchainOverview) async {
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

    static func exitRuns(_ handles: Handles) -> [String: String] {
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
        _ address: String, network: String, isMine: (String) async throws -> Bool) async throws -> Bool {
        guard ExitRules.acceptsClaimAddress(address, network: network) else { throw OnchainFailure.invalid }
        return try await isMine(address)
    }

    func boardingMinimum() async throws -> UInt64? {
        try await withOpenWallet { try await $0.wallet.arkInfo()?.minBoardAmountSats }
    }

    func quoteOnchain(
        _ action: OnchainAction, ids: [String] = [], amount: UInt64 = 0, destination: String? = nil,
        automatic: Bool = false) async throws -> OnchainQuote {
        try await withOpenWallet { handles in
            if action == .board {
                guard let quote = try await self.prepareBoardReview(handles, requested: amount, automatic: automatic)
                else { throw OnchainFailure.invalid }
                return quote
            }
            return try await Self.onchainQuote(
                handles,
                request: QuoteRequest(action: action, ids: ids, amount: amount, destination: destination, automatic: automatic))
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
        // swiftlint:disable:next discouraged_optional_collection - nil excludes nothing, unlike a reviewed empty set
        @Sendable func prepare(_ amount: UInt64, excluding: Set<String>?) async throws -> OnchainQuote {
            let board = try await handles.wallet.prepareBoard(
                amount: amount == 0 ? .drainAll : .amount(amountSats: amount),
                feeRateSatPerVb: rate, confirmedOnly: true, excludedOutpoints: excluding.map(Self.outpoints))
            let quote = OnchainQuote(action: .board, walletID: walletID, ids: [], amount: board.grossSats,
                                     fee: board.arkFeeSats, chainFee: board.onchainFeeSats, confirmed: balance, feeRate: rate,
                                     reviewedAt: .now, automatic: automatic, boardAll: !automatic && requested == 0 ? true : nil, prepared: .init(board))
            await retainBoardReview(quote, wallet: handles.wallet)
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
                                                     guard let id = quote.prepared?.id, await self.boardPreparations[id] == nil else {
                                                         throw OnchainFailure.uncertain
                                                     }
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
        prepare: @Sendable (UInt64, Set<String>) async throws -> OnchainQuote) async throws -> OnchainQuote? {
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
                      let chainFee = quote.chainFee, quote.amount + chainFee + BoardingRules.reserve + claims.held <= quote.confirmed,
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
        ids: [String], access: OnchainStartAccess, automatic: Bool = false, estimate: Bool = true) async throws -> OnchainQuote {
        let balance = try await access.confirmed()
        guard Set(ids).count == ids.count else { throw OnchainFailure.invalid }
        let spendable = try await access.spendable()
        let selected = ids.compactMap { spendable[$0] }
        guard !ids.isEmpty, selected.count == ids.count else { throw OnchainFailure.changed }
        guard selected.allSatisfy({ $0 >= ExitRules.dust }) else { throw OnchainFailure.invalid }
        let quote = OnchainQuote(
            action: .startExit, walletID: access.walletID, ids: ids.sorted(), amount: selected.reduce(0, +),
            fee: 0, chainFee: 0, confirmed: balance, reviewedAt: .now, automatic: automatic)
        return try await exitStartEstimate(quote, estimate: estimate) { try await access.estimate(ids) }
    }

    static func executeStart(
        _ quote: OnchainQuote, journal: OnchainOperationJournal, access: OnchainStartAccess) async throws -> String {
        guard quote.action == .startExit, ExitRules.current(elapsed: quote.elapsed()) else { throw OnchainFailure.changed }
        guard !quote.feesUnknown || quote.hasUnknownFeeConsent else { throw OnchainFailure.changed }
        let fresh: OnchainQuote
        do {
            fresh = try await startQuote(ids: quote.ids, access: access, automatic: quote.automatic, estimate: !quote.hasUnknownFeeConsent)
        } catch let failure as ExitEstimateUnavailable { throw try failure.preserving(quote) }
        guard quote.matches(fresh), ExitRules.current(elapsed: quote.elapsed()) else { throw OnchainFailure.changed }
        return try await journal.perform(quote) {
            // The new run is recorded before bark starts, or bark isn't asked (nothing sent yet).
            do { try await access.began(quote.ids) } catch { throw OnchainFailure.constructionFailed }
            try await access.start(quote.ids)
            // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
            return "Exit started. Noah progresses it on its own, including in the background when the system lets it, paying broadcast and fee-bump fees from your onchain balance."
        }
    }

    static func claimQuote(
        ids: [String], destination: String?, access: OnchainClaimAccess, automatic: Bool = false, estimate: Bool = true) async throws -> OnchainQuote {
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
            fee: nil, chainFee: nil, confirmed: balance, reviewedAt: .now, automatic: automatic)
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
        _ quote: OnchainQuote, journal: OnchainOperationJournal, access: OnchainClaimAccess) async throws -> String {
        guard quote.action == .claim, ExitRules.current(elapsed: quote.elapsed()) else { throw OnchainFailure.changed }
        guard !quote.feesUnknown || quote.hasUnknownFeeConsent else { throw OnchainFailure.changed }
        let fresh: OnchainQuote
        do {
            fresh = try await claimQuote(
                ids: quote.ids,
                destination: quote.destination,
                access: access,
                automatic: quote.automatic,
                estimate: !quote.hasUnknownFeeConsent)
        } catch let failure as ExitEstimateUnavailable { throw try failure.preserving(quote) }
        guard quote.matches(fresh), ExitRules.current(elapsed: quote.elapsed()) else { throw OnchainFailure.changed }
        // A claim quote always names where it pays; refuse before journaling one that doesn't.
        guard let destination = quote.destination else { throw OnchainFailure.invalid }
        return try await journal.perform(quote) {
            let claim = try await access.drain(quote.ids, destination)
            guard quote.hasUnknownFeeConsent || claim.feeSats == quote.fee else { throw OnchainFailure.claimFeeChanged }
            let tx = try access.transaction(claim.psbtBase64)
            // A claim to this phone is recorded before it's sent, or not sent (nothing broadcast yet).
            let expected = BitcoinTransaction.txid(hex: tx)
            if quote.destinationIsMine == true {
                guard let expected else { throw OnchainFailure.constructionFailed }
                do { try await access.claimedToPhone(expected) } catch { throw OnchainFailure.constructionFailed }
            }
            // Its fee for the screens, before it's sent, so an uncertain broadcast still shows it. Never blocks.
            let record = ExitClaimRecord(ids: quote.ids, fee: claim.feeSats, toPhone: quote.destinationIsMine == true,
                                         destination: quote.destinationIsMine == true ? nil : quote.destination)
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

    /// What to quote: the action, its VTXOs or amount, where it pays, and whether to estimate fees.
    struct QuoteRequest {
        var action: OnchainAction
        var ids: [String]
        var amount: UInt64
        var destination: String?
        var automatic: Bool
        var estimate = true
    }
    static func onchainQuote(_ handles: Handles, request: QuoteRequest) async throws -> OnchainQuote {
        let action = request.action, ids = request.ids, requested = request.amount, destination = request.destination
        let automatic = request.automatic, estimate = request.estimate
        if action == .claim {
            return try await claimQuote(ids: ids, destination: destination, access: claimAccess(handles), automatic: automatic)
        }
        if action == .startExit {
            return try await startQuote(ids: ids, access: startAccess(handles), automatic: automatic, estimate: estimate)
        }
        let balance = try await handles.onchain.balance().confirmedSats
        var quote = OnchainQuote(
            action: action, walletID: handles.wallet.fingerprint(), ids: ids.sorted(),
            destination: destination,
            amount: requested, fee: 0, chainFee: 0, confirmed: balance, reviewedAt: .now, automatic: automatic)
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
        async throws -> String {
        try await withOpenWallet { handles in
            if quote.action == .board { return try await Self.executeBoard(self, quote, handles: handles, journal: journal, funding: funding) }
            if quote.action == .claim {
                return try await Self.executeClaim(quote, journal: journal, access: Self.claimAccess(handles))
            }
            if quote.action == .startExit { return try await Self.executeStartExit(self, quote, handles: handles, journal: journal) }
            return try await Self.executeOther(self, quote, handles: handles, journal: journal)
        }
    }

    private static func executeBoard(_ session: WalletSession, _ quote: OnchainQuote, handles: Handles, journal: OnchainOperationJournal,
                                     funding: AutoBoardFunding.Snapshot?) async throws -> String {
        try await session.submitBoard(quote, walletID: handles.wallet.fingerprint(), journal: journal,
                                      confirmed: { try await handles.onchain.balance().confirmedSats },
                                      claims: { try await Self.exitClaims(handles) },
                                      funding: funding,
                                      readFunding: {
                                          guard try await handles.wallet.pendingBoards().isEmpty else { return nil }
                                          return try await AutoBoardFunding.confirmed(walletID: handles.wallet.fingerprint(),
                                                                                      utxos: handles.onchain.utxos(),
                                                                                      transactions: handles.onchain.transactions())
                                      })
    }

    private static func executeStartExit(_ session: WalletSession, _ quote: OnchainQuote, handles: Handles,
                                         journal: OnchainOperationJournal) async throws -> String {
        var access = Self.startAccess(handles)
        let walletID = handles.wallet.fingerprint()
        access.began = { ids in
            await session.forgetExitProof(ids, walletID: walletID)
            try ExitRuns.begin(ids, walletID: walletID)
        }
        return try await Self.executeStart(quote, journal: journal, access: access)
    }

    /// Refresh, progress and cancel: requoted and compared before the journal runs them.
    private static func executeOther(_ session: WalletSession, _ quote: OnchainQuote, handles: Handles,
                                     journal: OnchainOperationJournal) async throws -> String {
        guard ExitRules.current(elapsed: quote.elapsed()) else { throw OnchainFailure.changed }
        guard !quote.feesUnknown || quote.hasUnknownFeeConsent else { throw OnchainFailure.changed }
        let fresh: OnchainQuote
        do {
            fresh = try await Self.onchainQuote(
                handles,
                request: QuoteRequest(
                    action: quote.action,
                    ids: quote.ids,
                    amount: quote.boardAll == true ? 0 : quote.amount,
                    destination: quote.destination,
                    automatic: quote.automatic,
                    estimate: !quote.hasUnknownFeeConsent))
        } catch let failure as ExitEstimateUnavailable { throw try failure.preserving(quote) }
        guard quote.matches(fresh), ExitRules.current(elapsed: quote.elapsed()) else {
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
                let failures = try await session.progressExits(quote.feeRate, access: Self.exitProgressAccess(handles))
                return OnchainReceipt(
                    message: failures == 0
                        ? OnchainReceipt.progressedMessage
                        :
                        "Exit progression returned \(failures) errors. Transactions may have been broadcast or fee-bumped. Refresh exit details before continuing.")
            case .claim:
                throw OnchainFailure.invalid // Handled by the shared claim path above.
            case .cancel:
                let result = try await handles.wallet.cancelExit(vtxoId: quote.ids[0])
                guard result.canceled else { throw OnchainFailure.cancellationRefused }
                await session.forgetExitProof(quote.ids, walletID: handles.wallet.fingerprint())
                return "Exit canceled. The VTXO remains spendable."
            }
        }
    }
}
