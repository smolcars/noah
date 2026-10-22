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
