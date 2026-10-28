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
    /// What the widgets show besides the balance, read-only: settled balance changes for the wave and Home's
    /// Renew rule. Without the chain tip neither is read, and the widgets keep what they have.
    func widgetFacts(now: Date = Date()) async throws -> WidgetDataWriter.Facts {
        try await withOpenWallet { handles in
            var facts = WidgetDataWriter.Facts()
            guard let tip = try? await handles.onchain.tipHeight() else {
                AppLog("widget").w("Chain tip unavailable; the widget keeps its wave and Renew line")
                return facts
            }
            if let movements = try? await handles.wallet.history(), let chain = try? await handles.onchain.transactions() {
                facts.changes = WidgetBalanceHistory.changes(movements: movements, chain: chain, tip: tip, now: now)
            } else { AppLog("widget").w("History unavailable; the widget keeps its wave") }
            if let expiring = try? await handles.wallet.getExpiringVtxos(
                thresholdBlocks: UInt32(WalletConfiguration.base(for: .current).vtxoRefreshExpiryThreshold)) {
                facts.renewal = HomeRenewal.select(expiring.map(Self.renewalCoin), tip: tip).map { .blocks($0.blocksLeft) } ?? .notDue
            } else { AppLog("widget").w("Expiring VTXOs unavailable; the widget keeps its Renew line") }
            return facts
        }
    }
}
