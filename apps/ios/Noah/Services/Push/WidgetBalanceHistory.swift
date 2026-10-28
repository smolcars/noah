import BarkBoltFFI
import Foundation

/// The settled balance changes behind the widgets' wave (docs/native-rewrite/design/widgets.md, "The wave"),
/// from the records Activity reads: bark's movements and the on-chain wallet's transactions. Their sum is
/// the total balance's change, as each side accounts for its own half of a board or an offboard.
enum WidgetBalanceHistory {
    /// Settled movements count at their creation time, the date Activity shows. Confirmed on-chain
    /// transactions count with the movement that names them (a board's anchor, an offboard's transaction or a
    /// round's funding transaction), and only once it's no longer pending; any other at its `confirmed` date
    /// (`WidgetChainDates`), or not at all without one. Pending, failed and canceled movements aren't changes.
    static func changes(movements: [Movement], chain: [WalletTransaction], confirmed: [String: Date],
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
            guard movement.status == "successful", movement.effectiveBalanceSats != 0 else { return nil }
            return .init(at: date(movement, now: now), delta: movement.effectiveBalanceSats)
        }
        for transaction in chain where transaction.balanceChangeSats != 0 && transaction.confirmation != nil {
            if let movement = named[transaction.txid]?.movement {
                if movement.status != "pending" {
                    changes.append(.init(at: date(movement, now: now), delta: transaction.balanceChangeSats))
                }
            } else if let at = confirmed[transaction.txid] {
                changes.append(.init(at: at, delta: transaction.balanceChangeSats))
            }
        }
        return changes
    }

    /// A first estimate of when a transaction confirmed: ten minutes a block before the tip.
    static func estimate(_ transaction: WalletTransaction, tip: UInt32, now: Date) -> Date? {
        guard let height = transaction.confirmation?.height else { return nil }
        return now.addingTimeInterval(-Double(tip > height ? tip - height : 0) * 600)
    }

    /// `HistoryMapping.transaction`'s date.
    private static func date(_ movement: Movement, now: Date) -> Date { HistoryRules.parseDate(movement.createdAt) ?? now }
}
