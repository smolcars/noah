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
    /// Blocks mined since Noah first saw the transaction in flight in the mempool.
    var stuck: UInt32 = 0
    /// Its fee child was replaced: the fast rate rose and Noah paid more.
    var bumped = false
    var claimableHeight: UInt32?
    var claimedHeight: UInt32?
    /// The tip at the app's own last completed progress (Check the chain). bark keeps a state's
    /// height until something changes, so that height isn't when it last checked.
    var checked: UInt32?
    /// Still sending while Noah has evidence nothing progresses it: the last Check the chain failed,
    /// or the wallet is open without bark's daemon (which otherwise keeps exits current).
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

    /// `tip` is the wallet's tip. `claimSent`: Noah journaled a broadcast claim bark hasn't seen yet.
    /// `checkedTip`: Noah's last completed Check the chain. `progressing`: bark's daemon runs and the
    /// last Check didn't fail. `seen`: the tip at which Noah first saw each transaction in its status.
    init(_ exit: ExitModel, tip: UInt32, needsFunds: Bool = false, claimSent: Bool = false,
         checkedTip: UInt32? = nil, progressing: Bool = true, seen: [String: UInt32] = [:]) {
        let details = exit.details
        checked = checkedTip
        cancelable = exit.cancelable
        stale = !progressing && ["Start", "Processing"].contains(exit.state)
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
                // Blocks since Noah first saw it in the mempool (bark keeps a state's height until it changes).
                if let since = seen[ExitSightings.key(current.txid, current.status.kind)], tip > since { stuck = tip - since }
                // Its child when bark first recorded it in the mempool: a different one now means a re-bump.
                let first = exit.records.first { record in
                    record.transactions?.contains { $0.txid == current.txid && $0.status.kind == "awaiting-confirmation" } == true
                }
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

    /// A claim Noah broadcast (`txid`, from its journal) that bark hasn't recorded yet: shown as
    /// claiming until bark first sees it. After that bark's state counts, so a claim that fell out
    /// of the mempool reads Claimable again.
    func awaitsClaim(_ txid: String) -> Bool {
        state == "Claimable" && !records.contains { $0.claimTxid == txid }
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

/// When Noah first saw each exit transaction in its current status: the wallet's tip and the time.
/// bark keeps a state, height included, until something changes, so its height can't count blocks.
/// Persisted per wallet.
struct ExitSightings: Codable, Equatable, Sendable {
    struct Sighting: Codable, Equatable, Sendable {
        var tip: UInt32
        var at: Date
    }
    var seen: [String: Sighting] = [:]

    static func key(_ txid: String, _ kind: String) -> String { txid + "|" + kind }

    /// Adds what's newly seen and forgets what's no longer current; true when it changed.
    mutating func observe(_ exits: [ExitModel], tip: UInt32, now: Date) -> Bool {
        let current = Set(exits.filter { $0.state == "Processing" }.flatMap { exit in
            (exit.details.transactions ?? []).filter { $0.status.block == nil }.map { Self.key($0.txid, $0.status.kind) }
        })
        let before = seen
        seen = seen.filter { current.contains($0.key) }
        for key in current where seen[key] == nil { seen[key] = .init(tip: tip, at: now) }
        return seen != before
    }

    var tips: [String: UInt32] { seen.mapValues(\.tip) }

    struct Store: Sendable {
        static let key = "native-exit-sightings-v1"
        var read: @Sendable () -> String?
        var write: @Sendable (String) throws -> Void
        init(storage: SettingsStorage = .shared) {
            read = { storage.string(forKey: Self.key) }
            write = { try storage.setDurably($0, forKey: Self.key) }
        }
        init(read: @escaping @Sendable () -> String?, write: @escaping @Sendable (String) throws -> Void) {
            self.read = read; self.write = write
        }
        private func all() -> [String: ExitSightings] {
            read().flatMap { try? JSONDecoder().decode([String: ExitSightings].self, from: Data($0.utf8)) } ?? [:]
        }
        func load(walletID: String) -> ExitSightings { all()[walletID] ?? .init() }
        func save(_ sightings: ExitSightings, walletID: String) throws {
            var all = all()
            all[walletID] = sightings
            try write(String(decoding: JSONEncoder().encode(all), as: UTF8.self))
        }
    }
}

/// Decision 3. bark 0.8 never reports missing funds: it leaves the transaction in
/// `AwaitingCpfpBroadcast` and logs a warning. The proof is a confirmed balance below that
/// package's fee; it clears when funds arrive. No error strings are matched.
enum ExitFunds {
    /// bark's `daemon_sync_interval_secs` default (Noah doesn't set it), plus a margin for a cycle in flight.
    static let cycle: TimeInterval = 60 + 15

    /// bark's canonical CPFP child (estimate.rs `canonical_cpfp_child_weight`): P2A and P2TR key-spend in, P2TR out.
    static let childWeight: UInt64 = 609

    /// A zero-fee exit transaction's package fee at `satPerVb` (bark's `package_fee`, rounded up).
    static func packageFee(parentWeight: UInt64, satPerVb: UInt64, childWeight: UInt64 = childWeight) -> UInt64 {
        (satPerVb * 250 * (parentWeight + childWeight) + 999) / 1000
    }

    static func needsFunds(_ exit: ExitModel, confirmed: UInt64, packageFee: UInt64?, proven: Set<String>,
                           parkedSince: Date?, daemonSince: Date?, now: Date) -> Bool {
        guard let txid = exit.parkedTxid, let packageFee, confirmed < packageFee else { return false }
        if proven.contains(txid) || exit.parkedAcrossProgress { return true }
        // Only after bark has had a full daemon cycle to fund it, so the label doesn't flash before it tried.
        guard let parkedSince, let daemonSince else { return false }
        return now.timeIntervalSince(max(parkedSince, daemonSince)) >= cycle
    }
}

/// Decision 4: "Fees more than it's worth" on each VTXO whose estimated exit fees (broadcast and
/// claim) are at least its amount. Each VTXO is priced on its own, the conservative side: siblings
/// each carry their shared path, so it never adds an uneconomic VTXO to All.
enum ExitEconomics {
    /// One VTXO's classification; a VTXO not yet in the map is still being priced.
    enum Cost: Equatable, Sendable { case worthIt, uneconomic, failed }

    // shortcut: standalone estimates, as bark-bolt-ffi's estimate gives only set totals; deduplicate shared transactions once it exposes per-transaction estimate data.
    static func classify(_ vtxos: [VtxoModel], estimate: @Sendable ([String]) async throws -> UInt64) async -> [String: Cost] {
        var costs: [String: Cost] = [:]
        for vtxo in vtxos {
            do { costs[vtxo.id] = try await estimate([vtxo.id]) >= vtxo.amount ? .uneconomic : .worthIt }
            catch { costs[vtxo.id] = .failed }
        }
        return costs
    }

    /// "All": every eligible VTXO known to be worth its fees.
    static func all(_ vtxos: [VtxoModel], costs: [String: Cost]) -> [VtxoModel] {
        vtxos.filter { $0.eligible && costs[$0.id] == .worthIt }
    }

    /// All can be reviewed only once every eligible VTXO is classified.
    static func classified(_ vtxos: [VtxoModel], costs: [String: Cost]) -> Bool {
        vtxos.filter(\.eligible).allSatisfy { costs[$0.id] == .worthIt || costs[$0.id] == .uneconomic }
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
