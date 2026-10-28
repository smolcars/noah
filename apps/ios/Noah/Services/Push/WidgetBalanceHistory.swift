import BarkBoltFFI
import Foundation

/// The settled balance changes behind the widgets' wave (docs/native-rewrite/design/widgets.md, "The wave"),
/// from the records Activity reads: bark's movements and the on-chain wallet's transactions. Their sum is
/// the total balance's change, as each side accounts for its own half of a board or an offboard.
enum WidgetBalanceHistory {
    /// Settled movements count at their creation time, the date Activity shows. Confirmed on-chain
    /// transactions count with the movement that names them (a board's anchor, an offboard's transaction),
    /// and only once it's no longer pending; any other at its block's height, ten minutes a block before the
    /// tip. Pending, failed and canceled movements aren't changes.
    static func changes(movements: [Movement], chain: [WalletTransaction], tip: UInt32, now: Date) -> [WidgetWave.Change] {
        var named: [String: Movement] = [:]
        for movement in movements {
            let metadata = HistoryMetadata.parse(movement.metadataJson)
            let anchor = metadata.chainAnchor?.replacingOccurrences(of: ":\\d+$", with: "", options: .regularExpression)
            if let txid = metadata.offboardTxid ?? anchor, !txid.isEmpty { named[txid] = movement }
        }
        var changes = movements.compactMap { movement -> WidgetWave.Change? in
            guard movement.status == "successful", movement.effectiveBalanceSats != 0 else { return nil }
            return .init(at: date(movement, now: now), delta: movement.effectiveBalanceSats)
        }
        for transaction in chain where transaction.balanceChangeSats != 0 {
            guard let confirmation = transaction.confirmation else { continue }
            if let movement = named[transaction.txid] {
                if movement.status != "pending" {
                    changes.append(.init(at: date(movement, now: now), delta: transaction.balanceChangeSats))
                }
            } else {
                let blocks = tip > confirmation.height ? tip - confirmation.height : 0
                changes.append(.init(at: now.addingTimeInterval(-Double(blocks) * 600), delta: transaction.balanceChangeSats))
            }
        }
        return changes
    }

    /// `HistoryMapping.transaction`'s date.
    private static func date(_ movement: Movement, now: Date) -> Date { HistoryRules.parseDate(movement.createdAt) ?? now }
}
