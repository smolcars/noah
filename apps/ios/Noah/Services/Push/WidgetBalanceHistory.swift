import BarkBoltFFI
import Foundation

/// The balance changes behind the widgets' wave (docs/native-rewrite/design/widgets.md, "The wave"), from the records
/// Activity reads: bark's movements and the on-chain wallet's transactions. A change is anything the displayed total
/// already reflects, so their sum is the total's change, as each side accounts for its own half of a board or an offboard.
enum WidgetBalanceHistory {
    /// The balance field that counts a pending incoming movement.
    enum Bucket: Sendable { case lightning, board }

    /// A pending incoming movement bark's balance counts only from a later step: a Lightning receive once its preimage is
    /// out (`claimableLightningReceive`), a board once its funding transaction is on the network (`pendingBoard`).
    /// `counted` settles it against the balance the widget shows.
    struct Incoming: Equatable, Sendable {
        var bucket: Bucket
        var change: WidgetWave.Change
    }

    /// Settled movements count at their creation time, the date Activity shows. A pending movement holds its sats in the
    /// total until it settles (bark's `Balance`: a Lightning or Ark send, a round, an exit), so it isn't a change yet;
    /// except an offboard whose transaction is out, which has left the total (its sats are on chain), and the incoming
    /// ones in `incoming`. On-chain transactions are all in the on-chain total, confirmed or not: they count with the
    /// movement that names them (a board's anchor, an offboard's transaction or a round's funding transaction), any
    /// other at its date in `chainDates` (`WidgetChainDates`), or not at all without one. Failed and canceled movements
    /// aren't changes.
    static func changes(movements: [Movement], chain: [WalletTransaction], chainDates: [String: Date],
                        now: Date) -> [WidgetWave.Change] {
        // A transaction a movement names outright beats one it shares a round with; among those sharing a round,
        // the movement that moved money on chain (an offboard) beats a renewal.
        var named: [String: (movement: Movement, rank: Int)] = [:]
        for movement in movements {
            let metadata = HistoryMetadata.parse(movement.metadataJson)
            let anchor = metadata.chainAnchor?.replacingOccurrences(of: ":\\d+$", with: "", options: .regularExpression)
            let kind = HistoryMapping.subsystemID(movement).flatMap { HistoryMapping.kinds[$0] }
            let onchain = ["onboard", "offboard", "send-onchain"].contains(kind)
            for (txid, rank) in [(metadata.offboardTxid, 2), (anchor, 2), (metadata.fundingTxid, onchain ? 1 : 0)] {
                guard let txid, !txid.isEmpty, named[txid].map({ $0.rank < rank }) ?? true else { continue }
                named[txid] = (movement, rank)
            }
        }
        var changes = movements.compactMap { movement -> WidgetWave.Change? in
            guard movement.effectiveBalanceSats != 0, movement.status == "successful" || isBroadcastOffboard(movement) else { return nil }
            return .init(at: date(movement, now: now), delta: movement.effectiveBalanceSats)
        }
        for transaction in chain where transaction.balanceChangeSats != 0 {
            if let movement = named[transaction.txid]?.movement {
                changes.append(.init(at: date(movement, now: now), delta: transaction.balanceChangeSats))
            } else if let at = chainDates[transaction.txid] {
                changes.append(.init(at: at, delta: transaction.balanceChangeSats))
            }
        }
        return changes
    }

    /// The pending Lightning receives and boards, for `counted`.
    static func incoming(movements: [Movement], now: Date) -> [Incoming] {
        movements.compactMap { movement in
            guard movement.status == "pending", movement.effectiveBalanceSats > 0 else { return nil }
            let bucket: Bucket? = switch HistoryMapping.subsystemID(movement) {
            case "bark.lightning_receive:receive": .lightning
            case "bark.board:board": .board
            default: nil
            }
            return bucket.map { Incoming(bucket: $0, change: .init(at: date(movement, now: now), delta: movement.effectiveBalanceSats)) }
        }
    }

    /// The incoming movements `balance` counts: oldest first, as many as its bucket holds (a newer one is still at the
    /// earlier step).
    static func counted(_ incoming: [Incoming], in balance: WalletBalance) -> [WidgetWave.Change] {
        var room: [Bucket: Int64] = [.lightning: Int64(clamping: balance.ark.claimableLightningReceive),
                                     .board: Int64(clamping: balance.ark.pendingBoard)]
        return incoming.sorted { $0.change.at < $1.change.at }.compactMap { item in
            guard let left = room[item.bucket], item.change.delta <= left else { return nil }
            room[item.bucket] = left - item.change.delta
            return item.change
        }
    }

    /// A first estimate of when a transaction confirmed: ten minutes a block before the tip.
    static func estimate(_ transaction: WalletTransaction, tip: UInt32, now: Date) -> Date? {
        guard let height = transaction.confirmation?.height else { return nil }
        return now.addingTimeInterval(-Double(tip > height ? tip - height : 0) * 600)
    }

    /// bark records an offboard's transaction on its movement as it broadcasts it; from then on the sats are on chain.
    private static func isBroadcastOffboard(_ movement: Movement) -> Bool {
        movement.status == "pending" && movement.subsystemName.lowercased() == "bark.offboard"
            && !(HistoryMetadata.parse(movement.metadataJson).offboardTxid ?? "").isEmpty
    }

    /// `HistoryMapping.transaction`'s date.
    private static func date(_ movement: Movement, now: Date) -> Date { HistoryRules.parseDate(movement.createdAt) ?? now }
}
