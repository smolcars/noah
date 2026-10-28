import BarkBoltFFI
import Foundation

extension WalletSession {
    /// Same syncPendingRounds call as RN's usePendingRounds; the session owns native work.
    func homePendingRounds() async throws -> [HomeRound] {
        try await withOpenWallet { handles in
            try await handles.wallet.syncPendingRounds().map { round in
                HomeRound(roundID: round.roundId, status: String(describing: round.status),
                          isFinal: round.isFinal, isSuccess: round.isSuccess,
                          fundingTxid: round.fundingTxid, unsignedFundingTxids: round.unsignedFundingTxids,
                          error: round.error)
            }
        }
    }

    /// Home's renew nudge: what `getExpiringVtxos` reports inside the refresh
    /// threshold (the maintenance window). Read-only; renewing goes through the review.
    func homeRenewal() async throws -> HomeRenewal? {
        try await withOpenWallet { handles in
            let tip = try await handles.onchain.tipHeight()
            let expiring = try await handles.wallet.getExpiringVtxos(
                thresholdBlocks: UInt32(WalletConfiguration.base(for: .current).vtxoRefreshExpiryThreshold))
            return HomeRenewal.select(expiring.map(Self.renewalCoin), tip: tip)
        }
    }
}

extension WalletSession {
    /// What the widgets show besides the balance, read-only. Home's arriving amount comes from the records Home
    /// reads. The wave and the Renew line need the chain tip (`readsTip`); without it the widgets keep what they have.
    func widgetFacts(readsTip: Bool = true, dates: WidgetChainDates = .shared, now: Date = Date()) async throws -> WidgetDataWriter.Facts {
        try await withOpenWallet { handles in
            var facts = WidgetDataWriter.Facts()
            let records = await Self.widgetRecords(handles, pending: readsTip)
            // The records hold for the balance the writer shows (the summary's, read before them) only if a reading after
            // them agrees (`WidgetDataWriter.snapshot`). bark's balance waits on the chain tip: the wait is capped, and a read
            // that outlives it blocks new ones until it returns (`WidgetBalanceCheck`).
            let after = records?.pending == nil ? nil : await self.widgetBalanceCheck.run(within: .seconds(2)) { try? await self.currentBalance() }
            if let records {
                // Activity's rows, merged as History merges them, so the amount is Home's.
                let rows = HistoryRules.merge(records.movements.filter(HistoryMapping.include).map { HistoryMapping.transaction($0, now: now) },
                                              onchain: records.chain.filter { $0.balanceChangeSats != 0 }.map { HistoryMapping.transaction($0) })
                facts.incomingOnchain = HomeStatusLine.incomingOnchain(rows)
            } else { AppLog("widget").w(#"History unavailable; the widget keeps its wave"#) }
            guard readsTip else { return facts }
            guard let tip = try? await handles.onchain.tipHeight() else {
                AppLog("widget").w(#"Chain tip unavailable; the widget keeps its wave and Renew line"#)
                return facts
            }
            if let records, let pending = records.pending {
                if let after {
                    Self.addWave(to: &facts, records, pending: pending, balance: after, tip: tip, dates: dates, now: now)
                } else { AppLog("widget").w(#"Balance late or unavailable after the widget read its records; it keeps its wave"#) }
            }
            if let expiring = try? await handles.wallet.getExpiringVtxos(
                thresholdBlocks: UInt32(WalletConfiguration.base(for: .current).vtxoRefreshExpiryThreshold)) {
                facts.renewal = HomeRenewal.select(expiring.map(Self.renewalCoin), tip: tip).map { .blocks($0.blocksLeft) } ?? .notDue
            } else { AppLog("widget").w(#"Expiring VTXOs unavailable; the widget keeps its Renew line"#) }
            return facts
        }
    }

    /// `balance(_:)` as counted work of its own, so a caller can stop waiting for it while close still does.
    func currentBalance() async throws -> WalletBalance {
        try await withOpenWallet { try await Self.balance($0) }
    }

    /// What the wave reads: bark's movements, the on-chain wallet's transactions and which pending items the total counts.
    private struct WidgetRecords {
        var movements: [Movement]
        var chain: [WalletTransaction]
        /// Nil when not asked for or unread.
        var pending: WidgetBalanceHistory.Pending?
    }

    // swiftlint:disable:next function_parameter_count - the reads widgetFacts made, handed on
    private static func addWave(to facts: inout WidgetDataWriter.Facts, _ records: WidgetRecords, pending: WidgetBalanceHistory.Pending,
                                balance: WalletBalance, tip: UInt32, dates: WidgetChainDates, now: Date) {
        let (movements, chain) = (records.movements, records.chain)
        let estimates = chain.reduce(into: [String: Date]()) { $0[$1.txid] = WidgetBalanceHistory.estimate($1, tip: tip, now: now) }
        let chainDates = dates.dates(estimates, keeping: Set(chain.map(\.txid)),
                                     unconfirmed: Set(chain.filter { $0.confirmation == nil }.map(\.txid)), now: now)
        facts.changes = WidgetBalanceHistory.changes(movements: movements, chain: chain, chainDates: chainDates, pending: pending, now: now)
        facts.offboards = WidgetBalanceHistory.offboards(movements: movements, now: now)
        facts.balance = balance
    }

    /// Nil without the history.
    private static func widgetRecords(_ handles: Handles, pending readsPending: Bool) async -> WidgetRecords? {
        guard let movements = try? await handles.wallet.history(), let chain = try? await handles.onchain.transactions() else { return nil }
        return WidgetRecords(movements: movements, chain: chain, pending: readsPending ? await widgetPending(handles.wallet) : nil)
    }

    /// Which pending receives and boards the total counts; nil when unread, and the widget keeps its wave.
    private static func widgetPending(_ wallet: Wallet) async -> WidgetBalanceHistory.Pending? {
        guard let receives = try? await wallet.pendingLightningReceives(), let boards = try? await wallet.pendingBoardVtxos() else {
            AppLog("widget").w(#"Pending payments unavailable; the widget keeps its wave"#)
            return nil
        }
        return .init(claimableReceives: Set(receives.filter { $0.state == "preimage-revealed" }.compactMap(\.movementId)),
                     boardVtxos: Set(boards.map(\.id)))
    }
}
