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
    /// The tip bark last checked this exit at.
    var checked: UInt32?
    /// Noah hasn't progressed it since the chain moved on (today's stale rule).
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

    init(_ exit: ExitModel, tip: UInt32, needsFunds: Bool = false) {
        let details = exit.details
        checked = details.tip_height
        cancelable = exit.cancelable
        stale = ExitRules.live(exit.state) && details.tip_height.map { $0 < tip } == true
        let path = exit.path
        total = path.count
        heights = path.map { $0.status.block?.height }
        confirmed = heights.prefix { $0 != nil }.count
        claimableHeight = details.claimable_height ?? details.claimable_since?.height
        switch exit.state {
        case "Start": phase = .starting
        case "AwaitingDelta": phase = .waiting
        case "Claimable": phase = .claimable
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
                if let first, let since = first.tip_height, let now = details.tip_height, now > since { stuck = now - since }
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
/// `AwaitingCpfpBroadcast` and logs a warning. So a transaction parked there past one progress
/// cycle, while the confirmed on-chain balance is below its package's fee, reads as
/// "Needs on-chain funds". No error strings are matched.
struct ExitFunds {
    /// bark's daemon progresses exits every 60 s (`daemon_sync_interval_secs`).
    static let cycle: TimeInterval = 60
    private(set) var parkedSince: [String: Date] = [:]

    /// Remembers when each parked transaction was first seen; forgets those that moved on.
    mutating func observe(_ exits: [ExitModel], now: Date) {
        let parked = Set(exits.compactMap(\.parkedTxid))
        parkedSince = parkedSince.filter { parked.contains($0.key) }
        for txid in parked where parkedSince[txid] == nil { parkedSince[txid] = now }
    }

    func needsFunds(_ exit: ExitModel, confirmed: UInt64, packageFee: UInt64?, now: Date) -> Bool {
        guard let txid = exit.parkedTxid, let packageFee, confirmed < packageFee else { return false }
        if let since = parkedSince[txid], now.timeIntervalSince(since) >= Self.cycle { return true }
        return exit.parkedAcrossProgress
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
