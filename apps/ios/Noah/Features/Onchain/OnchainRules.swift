import Foundation

/// RN exitFeeEstimate / exitOverviewCoordination. Amounts are always satoshis.
enum ExitRules {
    static let dust: UInt64 = 330
    static func eligible(state: String, amount: UInt64) -> Bool {
        state == "Spendable" && amount >= dust
    }
    static func ready(initialized: Bool, loaded: Bool, suspended: Bool, background: Bool) -> Bool {
        initialized && loaded && !suspended && !background
    }
    /// Not ready only for a moment: a sync or background job holds the wallet, or it's still opening. Suspended or
    /// not set up isn't a moment.
    static func settling(initialized: Bool, loaded: Bool, suspended: Bool, background: Bool) -> Bool {
        initialized && !suspended && (background || !loaded)
    }
    /// An exit review is current for 30 seconds after it opened (`ReviewClock`).
    static func current(elapsed: Duration) -> Bool {
        elapsed >= .zero && elapsed < .seconds(30)
    }
    static func received(amount: UInt64, claimFee: UInt64) -> UInt64 {
        amount > claimFee ? amount - claimFee : 0
    }
    static func warning(amount: UInt64, claimFee: UInt64, totalFee: UInt64, claimOnly: Bool)
        -> String?
    {
        if claimFee >= amount {
            return
                "The estimated claim fee consumes the entire recovered value. A claim may not be possible."
        }
        if !claimOnly && totalFee >= amount {
            return "Estimated total fees meet or exceed the value being recovered."
        }
        return nil
    }
    /// States an exit ends in; every other state is live (start to claimed or canceled).
    static let finished = ["Canceled", "Claimed", "VtxoAlreadySpent", "VtxoSwept"]
    static func live(_ state: String) -> Bool { !finished.contains(state) }
    /// Decision 5: Settings' Emergency exit row says "In progress" only while an exit is live.
    static func settingsValue(_ exits: [ExitModel]) -> String? {
        exits.contains { live($0.state) } ? "In progress" : nil
    }
    /// Decision 8: a claim to another wallet takes only an on-chain Bitcoin address for this
    /// network (today's destination parser; no URIs, no other networks).
    static func acceptsClaimAddress(_ address: String, network: String) -> Bool {
        guard let actual = PaymentEncoding.bitcoinNetwork(address) else { return false }
        return actual == network || actual == "testnet"
            && (network == "signet" || network == "regtest" && !address.lowercased().hasPrefix("tb1"))
    }
    static func cancelable(state: String, finalTransactionKind: String?) -> Bool {
        if state == "Start" { return true }
        guard state == "Processing" else { return false }
        return finalTransactionKind == nil
            || ["verify-inputs", "awaiting-input-confirmation", "awaiting-cpfp-broadcast"].contains(
                finalTransactionKind!)
    }
}

struct ExitDetails: Codable, Equatable, Sendable {
    struct Block: Codable, Equatable, Sendable {
        var height: UInt32
        var hash: String
    }
    struct Transaction: Codable, Equatable, Sendable {
        struct Status: Codable, Equatable, Sendable {
            var kind: String
            var child_txid: String?
            var block: Block?
            /// Whose fee child: "wallet" (this wallet paid), "mempool" or "block" (another wallet's, adopted).
            var origin: String?
        }
        var txid: String
        var status: Status
    }
    var kind: String?
    var tip_height: UInt32?
    var transactions: [Transaction]?
    var confirmed_block: Block?
    var claimable_height: UInt32?
    var claimable_since: Block?
    var last_scanned_block: Block?
    var claim_txid: String?
    var block: Block?
    var txid: String?
    var height: UInt32? { claimable_height ?? claimable_since?.height ?? block?.height ?? tip_height }
    /// The claim's transaction, once bark has seen it (in progress or claimed).
    var claimTxid: String? { claim_txid ?? txid }
}

struct ExitTimelineItem: Equatable, Sendable {
    var state: String
    var count: Int
    var startHeight: UInt32?
    var endHeight: UInt32?
    var details: ExitDetails?
    var isCurrent: Bool
    var label: String { ExitTimeline.labels[state] ?? state }
    var description: String {
        switch state {
        case "Start": "Exit tracking was registered for this VTXO."
        case "Canceled": "Exit processing was canceled before completion."
        case "Processing": "Exit transactions were prepared and monitored."
        case "AwaitingDelta":
            details?.claimable_height.map {
                "Exit transaction confirmed; funds become claimable at block \($0)."
            } ?? "Exit transaction confirmed; waiting for the timelock."
        case "Claimable": "Funds are sweepable to an on-chain address."
        case "ClaimInProgress": "Final claim transaction was broadcast and is waiting for confirmation."
        case "Claimed": "Funds were recovered on-chain."
        case "VtxoAlreadySpent": "Exit tracking found that this VTXO was already spent."
        case "VtxoSwept": "An ancestor exit input was swept."
        default: state
        }
    }
}

enum ExitTimeline {
    static let labels = [
        "Start": "Started", "Processing": "Processing", "AwaitingDelta": "Waiting",
        "Claimable": "Claimable", "ClaimInProgress": "Claiming", "Claimed": "Claimed",
        "VtxoAlreadySpent": "Already spent", "Canceled": "Canceled", "VtxoSwept": "Swept",
    ]
    static func build(
        history: [String], historyDetails: [ExitDetails], state: String, details current: ExitDetails?
    ) -> [ExitTimelineItem] {
        var states = history.isEmpty ? [state] : history
        if states.last != state { states.append(state) }
        var details: [ExitDetails?] = historyDetails.map { $0 }
        if details.count < states.count {
            details.append(current)
        } else if details.count == states.count {
            details[details.count - 1] = current ?? details.last!
        }
        var items: [ExitTimelineItem] = []
        for (index, state) in states.enumerated() {
            let detail = index < details.count ? details[index] : nil
            let height = detail?.height
            if items.last?.state == state {
                let last = items.count - 1
                items[last].count += 1
                items[last].endHeight = height ?? items[last].endHeight
                items[last].details = detail
                items[last].isCurrent = index == states.count - 1
            } else {
                items.append(
                    .init(
                        state: state, count: 1, startHeight: height, endHeight: height, details: detail,
                        isCurrent: index == states.count - 1))
            }
        }
        return items
    }
}

enum BoardingRules {
    static let reserve: UInt64 = 5_000
    static func threshold(_ minimum: UInt64) -> UInt64 { max(minimum, 20_000) }
    static func onchainFee(satsPerKwu: UInt64) -> UInt64 { (satsPerKwu * 208 + 249) / 250 }
    static func autoAmount(balance: UInt64, minimum: UInt64, onchainFee: UInt64, arkFee: UInt64)
        -> UInt64?
    {
        guard balance >= onchainFee + reserve else { return nil }
        let gross = balance - onchainFee - reserve
        guard gross >= arkFee, gross - arkFee >= threshold(minimum) else { return nil }
        return gross
    }
}

struct ExitReviewInputs: Codable, Sendable {
    var walletId: String?
    var vtxoIds: [String]
    var amountSat: UInt64
    var scope: String
    var destinationAddress: String?
    var revision: String
    func matches(_ other: Self) -> Bool {
        walletId == other.walletId && vtxoIds.sorted() == other.vtxoIds.sorted()
            && amountSat == other.amountSat
            && scope == other.scope && destinationAddress == other.destinationAddress
            && revision == other.revision
    }
}
