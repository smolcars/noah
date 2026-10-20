import BarkBoltFFI
import Foundation

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
            let exits = try await handles.wallet.getExitVtxos()
            var models: [ExitModel] = []
            for exit in exits {
                // Never syncExits here: Bark sync is allowed to broadcast.
                let status = try await handles.wallet.getExitStatus(
                    vtxoId: exit.vtxoId, includeHistory: true, includeTransactions: false)
                let current = Self.exitState(status?.state ?? exit.state)
                let history = (status?.history ?? []).map(Self.exitState)
                models.append(
                    .init(
                        id: exit.vtxoId, amount: exit.amountSats, state: current.0, details: current.1,
                        history: history.map(\.0), historyDetails: history.map(\.1),
                        claimable: exit.isClaimable || current.0 == "Claimable"))
            }
            return OnchainOverview(
                walletID: handles.wallet.fingerprint(), vtxos: vtxos, exits: models,
                confirmed: try await handles.onchain.balance().confirmedSats, tip: tip,
                pendingTotal: try await handles.wallet.pendingExitsTotalSats(),
                claimableHeight: try await handles.wallet.allExitsClaimableAtHeight())
        }
    }

    func exitDepositAddress() async throws -> String {
        try await withOpenWallet { try await $0.onchain.newAddress() }
    }

    func boardingMinimum() async throws -> UInt64? {
        try await withOpenWallet { try await $0.wallet.arkInfo()?.minBoardAmountSats }
    }

    func quoteOnchain(
        _ action: OnchainAction, ids: [String] = [], amount: UInt64 = 0, destination: String? = nil,
        automatic: Bool = false
    ) async throws -> OnchainQuote {
        try await withOpenWallet { handles in
            try await Self.onchainQuote(
                handles, action: action, ids: ids, amount: amount, destination: destination,
                automatic: automatic)
        }
    }

    /// Only plans a prompt. No background task ever boards funds.
    func autoBoardQuote() async throws -> OnchainQuote? {
        try await withOpenWallet { handles in
            guard let info = try await handles.wallet.arkInfo() else { throw OnchainFailure.unavailable }
            let balance = try await handles.onchain.balance().confirmedSats
            guard balance >= BoardingRules.threshold(info.minBoardAmountSats) else { return nil }
            let chainFee = BoardingRules.onchainFee(
                satsPerKwu: try await handles.onchain.feeRates().regularSatPerKwu)
            var minimum = BoardingRules.threshold(info.minBoardAmountSats)
            for _ in 0..<5 {
                let fee = try await handles.wallet.estimateBoardFee(amountSats: minimum)
                if fee.netAmountSats >= BoardingRules.threshold(info.minBoardAmountSats) { break }
                minimum += BoardingRules.threshold(info.minBoardAmountSats) - fee.netAmountSats
            }
            guard balance >= minimum + chainFee + BoardingRules.reserve else { return nil }
            let gross = balance - chainFee - BoardingRules.reserve
            let fee = try await handles.wallet.estimateBoardFee(amountSats: gross)
            guard
                BoardingRules.autoAmount(
                    balance: balance, minimum: info.minBoardAmountSats, onchainFee: chainFee,
                    arkFee: fee.feeSats) != nil
            else { return nil }
            return try await Self.onchainQuote(
                handles, action: .board, ids: [], amount: gross, destination: nil, automatic: true)
        }
    }

    private static func onchainQuote(
        _ handles: Handles, action: OnchainAction, ids: [String], amount requested: UInt64,
        destination: String?, automatic: Bool
    ) async throws -> OnchainQuote {
        let balance = try await handles.onchain.balance().confirmedSats
        var quote = OnchainQuote(
            action: action, walletID: handles.wallet.fingerprint(), ids: ids.sorted(),
            destination: destination,
            amount: requested, fee: 0, chainFee: 0, confirmed: balance, automatic: automatic)
        guard Set(ids).count == ids.count else { throw OnchainFailure.invalid }
        switch action {
        case .board:
            guard let info = try await handles.wallet.arkInfo() else { throw OnchainFailure.unavailable }
            let chainFee = BoardingRules.onchainFee(
                satsPerKwu: try await handles.onchain.feeRates().regularSatPerKwu)
            guard balance > chainFee else { throw OnchainFailure.invalid }
            quote.boardAll = requested == 0 ? true : nil
            let amount = requested == 0 ? balance - chainFee : requested
            guard amount > 0, amount <= 2_100_000_000_000_000, amount <= balance - chainFee else {
                throw OnchainFailure.invalid
            }
            let fee = try await handles.wallet.estimateBoardFee(amountSats: amount)
            guard fee.netAmountSats >= info.minBoardAmountSats else { throw OnchainFailure.invalid }
            if automatic {
                guard
                    BoardingRules.autoAmount(
                        balance: balance, minimum: info.minBoardAmountSats, onchainFee: chainFee,
                        arkFee: fee.feeSats) == amount
                else { throw OnchainFailure.changed }
            }
            quote.amount = fee.grossAmountSats
            quote.fee = fee.feeSats
            quote.chainFee = chainFee
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
                let fee = try await handles.wallet.estimateEmergencyExitFee(
                    vtxoIds: ids, feeRateSatPerVb: nil, destination: nil)
                quote.fee = fee.claimFeeSats
                quote.chainFee = fee.exitBroadcastFeeSats
                quote.feeRate = fee.feeRateSatPerVb
            }
        case .progress, .claim:
            let all = try await handles.wallet.getExitVtxos()
            let eligible = all.filter { exit in
                if action == .claim { return exit.isClaimable }
                let state = Self.exitState(exit.state).0
                return !["Canceled", "Claimed", "VtxoAlreadySpent", "VtxoSwept"].contains(state)
            }
            let selected = eligible.filter { ids.contains($0.vtxoId) }
            guard !ids.isEmpty, selected.count == ids.count else { throw OnchainFailure.changed }
            // progressExits acts on ALL pending exits, never just a visible selection.
            if action == .progress, Set(eligible.map(\.vtxoId)) != Set(ids) {
                throw OnchainFailure.changed
            }
            if action == .claim,
                destination?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false
            {
                throw OnchainFailure.invalid
            }
            quote.amount = selected.reduce(0) { $0 + $1.amountSats }
            let fee = try await handles.wallet.estimateEmergencyExitFee(
                vtxoIds: ids, feeRateSatPerVb: nil, destination: destination)
            quote.fee = fee.claimFeeSats
            quote.chainFee = action == .claim ? 0 : fee.exitBroadcastFeeSats
            quote.feeRate = action == .claim ? nil : fee.feeRateSatPerVb
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

    func executeOnchain(_ quote: OnchainQuote, journal: OnchainOperationJournal = .shared)
        async throws -> String
    {
        return try await withOpenWallet { handles in
            guard ExitRules.current(reviewedAt: quote.createdAt) else { throw OnchainFailure.changed }
            let fresh = try await Self.onchainQuote(
                handles, action: quote.action, ids: quote.ids, amount: quote.boardAll == true ? 0 : quote.amount,
                destination: quote.destination, automatic: quote.automatic)
            guard quote.matches(fresh), ExitRules.current(reviewedAt: quote.createdAt) else {
                throw OnchainFailure.changed
            }
            return try await journal.perform(quote) {
                switch quote.action {
                case .board:
                    let board: PendingBoard
                    if quote.boardAll == true {
                        board = try await handles.wallet.boardAll()
                    } else {
                        board = try await handles.wallet.boardAmount(amountSats: quote.amount)
                    }
                    // A receipt must survive even when the optional fee lookup fails after submission.
                    let fee = try? await handles.onchain.transactions().first { $0.txid == board.txid }?.onchainFeeSats
                    return OnchainReceipt(
                        message: "Boarding submitted. Funds become spendable after the board completes."
                            + (fee.map { " Actual onchain fee: \($0) sats." } ?? " Actual onchain fee is not yet available."),
                        txid: board.txid, onchainFee: fee)
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
                    let claim = try await handles.wallet.drainExits(
                        vtxoIds: quote.ids, address: quote.destination!, feeRateSatPerVb: nil)
                    guard claim.feeSats == quote.fee else { throw OnchainFailure.claimFeeChanged }
                    let tx = try extractTxFromPsbt(psbtBase64: claim.psbtBase64)
                    let txid = try await handles.wallet.broadcastTx(txHex: tx)
                    return OnchainReceipt(message: "Claim submitted. Refresh to check confirmation.", txid: txid)
                case .cancel:
                    let result = try await handles.wallet.cancelExit(vtxoId: quote.ids[0])
                    guard result.canceled else { throw OnchainFailure.cancellationRefused }
                    return "Exit canceled. The VTXO remains spendable."
                }
            }
        }
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
