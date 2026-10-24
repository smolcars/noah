import Foundation

/// One exit as the redesigned screens read it (docs/native-rewrite/design/exit.md): bark's exit
/// state and its per-transaction statuses, mapped to a phase and a picture. Pure: the screens and
/// the tests build it from the same `ExitModel`.
struct ExitProgress: Equatable, Sendable {
    enum Phase: Equatable, Sendable {
        /// `Start`: marked, nothing sent. The VTXO lifts off the Ark line.
        case starting
        /// `Processing`, nothing sent yet: the path unrolls to the round's block.
        case publishing
        /// The next transaction is ready for its fee child (`AwaitingCpfpBroadcast`).
        case sending
        /// Decision 3, inferred: parked past a progress cycle with too few confirmed funds.
        case needsFunds
        /// In the mempool with this wallet's fee child.
        case mempool
        /// In the mempool or a block with another wallet's child: adopted, no fee.
        case shared
        /// The last transaction sent confirmed; the next waits for its inputs.
        case confirmed
        /// Every exit transaction confirmed; bark hasn't reached the timelock yet.
        case onchain
        case waiting, claimable, claiming, claimed, canceled, alreadySpent, swept
    }

    enum Picture: Equatable, Sendable { case lift, path, lock, unlocked, claim, claimed, canceled, none }

    var phase: Phase
    /// Exit transactions on the path; 0 until bark lists them.
    var total = 0
    /// Of those, the ones in blocks.
    var confirmed = 0
    /// 1-based: the transaction being sent.
    var step: Int?
    /// Each transaction's block, root first.
    var heights: [UInt32?] = []
    /// Blocks mined since the transaction in flight reached the mempool.
    var stuck: UInt32 = 0
    /// Its fee child was replaced: the fast rate rose and Noah paid more.
    var bumped = false
    var claimableHeight: UInt32?
    var claimedHeight: UInt32?
    /// The tip at the app's own last completed progress (Check the chain). bark keeps a state's
    /// height until something changes, so that height isn't when it last checked.
    var checked: UInt32?
    /// Still sending while nothing progresses it: bark's daemon isn't running in this session and the
    /// app's last progress is two or more blocks old. While the daemon runs, bark checks every minute.
    var stale = false
    var cancelable = false

    var live: Bool { ![.claimed, .canceled, .alreadySpent, .swept].contains(phase) }

    var picture: Picture {
        switch phase {
        case .starting: .lift
        case .publishing, .sending, .needsFunds, .mempool, .shared, .confirmed, .onchain: .path
        case .waiting: .lock
        case .claimable: .unlocked
        case .claiming: .claim
        case .claimed: .claimed
        case .canceled: .canceled
        case .alreadySpent, .swept: .none
        }
    }

    /// Blocks until claimable at `tip`.
    func left(tip: UInt32) -> UInt32? { claimableHeight.map { $0 > tip ? $0 - tip : 0 } }

    init(phase: Phase) { self.phase = phase }

    /// `claimSent`: Noah journaled a broadcast claim for it that bark hasn't seen yet. `checkedTip`
    /// and `progressing`: the app's last completed progress, and whether bark's daemon is running.
    init(_ exit: ExitModel, tip: UInt32, needsFunds: Bool = false, claimSent: Bool = false,
         checkedTip: UInt32? = nil, progressing: Bool = true) {
        let details = exit.details
        checked = checkedTip
        cancelable = exit.cancelable
        defer {
            // Without the daemon or a check of its own, the recorded height is the last progress there was.
            let last = checkedTip ?? details.tip_height
            stale = !progressing && ["Start", "Processing"].contains(exit.state) && last.map { tip >= $0 + 2 } == true
        }
        let path = exit.path
        total = path.count
        heights = path.map { $0.status.block?.height }
        confirmed = heights.prefix { $0 != nil }.count
        claimableHeight = details.claimable_height ?? details.claimable_since?.height
        // Past Processing every exit transaction is in a block, though bark's last list may predate
        // the leaf's confirmation (Processing goes straight to AwaitingDelta): the leaf's block is
        // the highest one, `confirmed_block`.
        if ["AwaitingDelta", "Claimable", "ClaimInProgress", "Claimed"].contains(exit.state), !path.isEmpty {
            let leaf = exit.records.compactMap(\.confirmed_block?.height).last
            let last = path.count - 1
            heights = path.indices.map { $0 == last ? path[$0].status.block?.height ?? leaf : path[$0].status.block?.height }
            confirmed = total
        }
        switch exit.state {
        case "Start": phase = .starting
        case "AwaitingDelta": phase = .waiting
        case "Claimable": phase = claimSent ? .claiming : .claimable
        case "ClaimInProgress": phase = .claiming
        case "Claimed":
            phase = .claimed
            claimedHeight = details.block?.height
        case "Canceled": phase = .canceled
        case "VtxoAlreadySpent": phase = .alreadySpent
        case "VtxoSwept": phase = .swept
        default:
            phase = .publishing
            guard exit.state == "Processing", let current = path.first(where: { $0.status.block == nil }) else {
                if exit.state == "Processing", !path.isEmpty { phase = .onchain }
                return
            }
            let index = confirmed
            switch current.status.kind {
            case "awaiting-cpfp-broadcast":
                step = index + 1
                phase = needsFunds ? .needsFunds : .sending
            case "awaiting-confirmation":
                step = index + 1
                phase = current.status.origin == "wallet" || current.status.origin == nil ? .mempool : .shared
                // The first record of this transaction in the mempool: blocks since then, and its child then.
                let first = exit.records.first { record in
                    record.transactions?.contains { $0.txid == current.txid && $0.status.kind == "awaiting-confirmation" } == true
                }
                // Blocks mined since: against the chain's tip, as bark keeps the state's height until it changes.
                if let first, let since = first.tip_height, tip > since { stuck = tip - since }
                let child = first?.transactions?.first { $0.txid == current.txid }?.status.child_txid
                bumped = child != nil && current.status.child_txid != nil && child != current.status.child_txid
            default:
                // Waiting for its parent's block: the root's parent is the round, already on-chain.
                if index > 0 { phase = .confirmed }
            }
        }
    }
}

extension ExitModel {
    /// Every state bark recorded, oldest first, then the current one.
    var records: [ExitDetails] { historyDetails + [details] }

    /// The exit's transactions, root first: the current state's, or the last list bark recorded
    /// (later states don't carry them).
    var path: [ExitDetails.Transaction] {
        records.last { $0.transactions?.isEmpty == false }?.transactions ?? []
    }

    /// The transaction parked in `AwaitingCpfpBroadcast`, waiting for a fee child.
    var parkedTxid: String? {
        guard state == "Processing", let next = details.transactions?.first(where: { $0.status.block == nil }),
              next.status.kind == "awaiting-cpfp-broadcast" else { return nil }
        return next.txid
    }

    /// bark recorded it parked at an earlier tip too: at least one progress since then failed to fund it.
    var parkedAcrossProgress: Bool {
        guard let txid = parkedTxid, let now = details.tip_height else { return false }
        return historyDetails.contains { record in
            record.tip_height.map { $0 < now } == true
                && record.transactions?.contains { $0.txid == txid && $0.status.kind == "awaiting-cpfp-broadcast" } == true
        }
    }
}

/// Decision 3. bark 0.8 never reports missing funds: it leaves the transaction in
/// `AwaitingCpfpBroadcast` and logs a warning. Within one progress call bark persists the parked
/// state, tries to build the fee child, then reads the state again; so a transaction still parked
/// after a completed call had too few confirmed funds. The proof is that call: the app's own
/// progress (`proven`, from Check the chain) or bark's record of it parked at an earlier tip. A failed
/// call proves nothing, elapsed time proves nothing, and no error strings are matched. It then needs
/// the confirmed balance below the package's fee; it clears when funds arrive or it moves on.
enum ExitFunds {
    static func needsFunds(_ exit: ExitModel, confirmed: UInt64, packageFee: UInt64?, proven: Set<String>) -> Bool {
        guard let txid = exit.parkedTxid, let packageFee, confirmed < packageFee else { return false }
        return proven.contains(txid) || exit.parkedAcrossProgress
    }
}

/// Decision 4: "Fees more than it's worth" on each VTXO whose own share of the exit fees is at
/// least its amount. Its share is what it adds to exiting all eligible VTXOs, so a transaction it
/// shares with others is counted once, for the set, not again for it.
enum ExitEconomics {
    /// `estimate` prices a set of VTXOs (broadcast plus claim, deduplicated across shared
    /// transactions, as bark's estimator does).
    // shortcut: n + 1 estimates, each signing a dummy claim of up to n inputs; precompute per round if wallets hold hundreds of VTXOs.
    static func uneconomic(_ vtxos: [VtxoModel], estimate: @Sendable ([String]) async throws -> UInt64) async throws -> Set<String> {
        let ids = vtxos.map(\.id)
        guard !ids.isEmpty else { return [] }
        let all = try await estimate(ids)
        var result = Set<String>()
        for vtxo in vtxos {
            let rest = ids.filter { $0 != vtxo.id }
            // An empty list would price the whole wallet, so the last one's share is the whole estimate.
            let without = rest.isEmpty ? 0 : try await estimate(rest)
            if all - min(all, without) >= vtxo.amount { result.insert(vtxo.id) }
        }
        return result
    }

    /// "All": every eligible VTXO except those that cost more than they're worth.
    static func all(_ vtxos: [VtxoModel], uneconomic: Set<String>) -> [VtxoModel] {
        vtxos.filter { $0.eligible && !uneconomic.contains($0.id) }
    }
}

/// A claim transaction drains every ready exit at once, paying one fee. Each exit's part of it is
/// in proportion to its amount, largest remainders first, so the parts add up to the fee.
enum ExitClaimSplit {
    static func fees(_ amounts: [String: UInt64], fee: UInt64) -> [String: UInt64] {
        let total = amounts.values.reduce(0, +)
        guard total > 0 else { return amounts.mapValues { _ in 0 } }
        var parts = amounts.mapValues { UInt64((UInt128($0) * UInt128(fee)) / UInt128(total)) }
        let order = amounts.keys.sorted {
            let a = (UInt128(amounts[$0]!) * UInt128(fee)) % UInt128(total), b = (UInt128(amounts[$1]!) * UInt128(fee)) % UInt128(total)
            return a == b ? $0 < $1 : a > b
        }
        for id in order.prefix(Int(fee - parts.values.reduce(0, +))) { parts[id]! += 1 }
        return parts
    }
}

/// Decision 6: the exits list draws a shared-path picture only when exits share a transaction.
enum ExitSharing {
    /// Live exits joined by any shared transaction; only groups of two or more.
    static func groups(_ exits: [ExitModel]) -> [[ExitModel]] {
        let live = exits.filter { ExitRules.live($0.state) && !$0.path.isEmpty }
        var groups: [(txids: Set<String>, exits: [ExitModel])] = []
        for exit in live {
            let txids = Set(exit.path.map(\.txid))
            let joined = groups.indices.filter { !groups[$0].txids.isDisjoint(with: txids) }
            var merged = (txids: txids, exits: [exit])
            for index in joined.reversed() {
                merged.txids.formUnion(groups[index].txids)
                merged.exits = groups[index].exits + merged.exits
                groups.remove(at: index)
            }
            groups.append(merged)
        }
        return groups.map(\.exits).filter { $0.count > 1 }
    }
}
