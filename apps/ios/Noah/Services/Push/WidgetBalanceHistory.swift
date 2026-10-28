import BarkBoltFFI
import Foundation

/// The balance changes behind the widgets' wave (docs/native-rewrite/design/widgets.md, "The wave"), from the records
/// Activity reads: bark's movements and the on-chain wallet's transactions. A change is anything the displayed total
/// already reflects, so their sum is the total's change, as each side accounts for its own half of a board or an offboard.
enum WidgetBalanceHistory {
    /// What bark-bolt-ffi reports about pending items, read with the records: the movements of Lightning receives whose
    /// preimage is out (`pendingLightningReceives`, "preimage-revealed", which `claimableLightningReceive` counts) and the
    /// board VTXOs `pendingBoard` counts (`pendingBoardVtxos`: funding transaction on the network).
    struct Pending: Equatable, Sendable {
        var claimableReceives: Set<UInt32> = []
        var boardVtxos: Set<String> = []
    }

    /// Settled movements count at their creation time, the date Activity shows. A pending movement holds its sats in the
    /// total until it settles (bark's `Balance`: a Lightning or Ark send, a round, an exit), so it isn't a change yet;
    /// except a Lightning receive or a board the total already counts (`pending`), and an offboard once its transaction
    /// is out (`offboards`). On-chain transactions are all in the on-chain total, confirmed or not: they count with the
    /// movement that names them (a board's anchor, an offboard's transaction or a round's funding transaction), any
    /// other at its date in `chainDates` (`WidgetChainDates`), or not at all without one. Failed and canceled movements
    /// aren't changes.
    static func changes(movements: [Movement], chain: [WalletTransaction], chainDates: [String: Date], pending: Pending,
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
            guard movement.effectiveBalanceSats != 0, movement.status == "successful" || counts(movement, pending) else { return nil }
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

    /// Each pending offboard's amount, for `offboarded`.
    static func offboards(movements: [Movement], now: Date) -> [WidgetWave.Change] {
        movements.filter { $0.status == "pending" && $0.subsystemName.lowercased() == "bark.offboard" && $0.effectiveBalanceSats < 0 }
            .map { .init(at: date($0, now: now), delta: $0.effectiveBalanceSats) }
    }

    /// The part of the pending `offboards` the total no longer counts, as one change: bark-bolt-ffi doesn't say which
    /// offboard's transaction is out, only what `pendingOffboard` still `held` (before broadcast it holds an offboard's
    /// sats, or more while change is split off). Dated by the oldest, usually the only one.
    static func offboarded(_ offboards: [WidgetWave.Change], held: UInt64) -> [WidgetWave.Change] {
        let left = -offboards.reduce(0) { $0 + $1.delta } - Int64(clamping: held)
        guard left > 0, let oldest = offboards.map(\.at).min() else { return [] }
        return [.init(at: oldest, delta: -left)]
    }

    /// Whether records read against `read` hold for `shown`: the total and the buckets the rules count pending items
    /// against didn't move.
    static func agrees(_ read: WalletBalance, _ shown: WalletBalance) -> Bool {
        read.totalSats == shown.totalSats && read.ark.claimableLightningReceive == shown.ark.claimableLightningReceive
            && read.ark.pendingBoard == shown.ark.pendingBoard && read.ark.pendingOffboard == shown.ark.pendingOffboard
    }

    /// A first estimate of when a transaction confirmed: ten minutes a block before the tip.
    static func estimate(_ transaction: WalletTransaction, tip: UInt32, now: Date) -> Date? {
        guard let height = transaction.confirmation?.height else { return nil }
        return now.addingTimeInterval(-Double(tip > height ? tip - height : 0) * 600)
    }

    /// A pending Lightning receive or board the total already counts.
    private static func counts(_ movement: Movement, _ pending: Pending) -> Bool {
        guard movement.status == "pending" else { return false }
        return switch HistoryMapping.subsystemID(movement) {
        case "bark.lightning_receive:receive": pending.claimableReceives.contains(movement.id)
        case "bark.board:board": movement.outputVtxoIds.contains(where: pending.boardVtxos.contains)
        default: false
        }
    }

    /// `HistoryMapping.transaction`'s date.
    private static func date(_ movement: Movement, now: Date) -> Date { HistoryRules.parseDate(movement.createdAt) ?? now }
}

/// The widget's check of the balance after its records: one read, waited for at most a limit. The read can't be stopped
/// (native work, counted by the session so close waits for it), so at most one is unfinished at a time: while one is,
/// `run` starts none and gives nil, and the guard clears only when the read itself returns.
actor WidgetBalanceCheck {
    private var reading = false
    private var waiting: CheckedContinuation<WalletBalance?, Never>?
    private var runs = 0

    /// Whether a read is still unfinished (it may have outlived its wait).
    var isReading: Bool { reading }

    func run(within limit: Duration, _ read: @escaping @Sendable () async -> WalletBalance?) async -> WalletBalance? {
        guard !reading else { return nil }
        reading = true
        runs += 1
        let run = runs
        // Both run on this actor, so neither answers before the wait below is in place.
        Task {
            try? await Task.sleep(for: limit)
            self.resume(nil, run: run)
        }
        Task {
            let value = await read()
            self.finished(value, run: run)
        }
        return await withCheckedContinuation { waiting = $0 }
    }

    private func finished(_ value: WalletBalance?, run: Int) {
        reading = false
        resume(value, run: run)
    }

    /// Answers the wait once: the first of the read and its limit; a later run's wait is never this run's.
    private func resume(_ value: WalletBalance?, run: Int) {
        guard run == runs, let waiting else { return }
        self.waiting = nil
        waiting.resume(returning: value)
    }
}
