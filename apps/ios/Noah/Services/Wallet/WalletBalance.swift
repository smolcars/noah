import Foundation

/// Wallet balance using bark's own accounting (`bark/src/balance.rs`):
/// every VTXO sits in exactly one bucket, and the Ark total is
/// `spendable + needs_refresh + pending`. VTXOs waiting for a refresh and
/// in-flight operations stay in the total so funds never appear to vanish.
struct WalletBalance: Equatable, Sendable {
    struct Ark: Equatable, Sendable, Decodable {
        var spendable: UInt64
        /// Expired or too deep to send; counted, but not spendable until refreshed.
        var needsRefresh: UInt64
        var pendingLightningSend: UInt64
        /// Incoming Lightning payment whose preimage is revealed but not yet claimed.
        var claimableLightningReceive: UInt64
        var pendingInRound: UInt64
        var pendingExit: UInt64
        var pendingBoard: UInt64
        var pendingArkoorSend: UInt64
        var pendingOffboard: UInt64
        /// `Balance::pending()`: the seven operation buckets.
        var pending: UInt64
        /// `Balance::total()`: spendable + needs refresh + pending.
        var total: UInt64

        /// `pending` and `total` as the SDK reports them (bark's own
        /// methods); computed with bark's formula only when not given, as in
        /// the shared fixtures.
        init(
            spendable: UInt64, needsRefresh: UInt64, pendingLightningSend: UInt64, claimableLightningReceive: UInt64,
            pendingInRound: UInt64, pendingExit: UInt64, pendingBoard: UInt64, pendingArkoorSend: UInt64,
            pendingOffboard: UInt64, pending: UInt64? = nil, total: UInt64? = nil
        ) {
            self.spendable = spendable
            self.needsRefresh = needsRefresh
            self.pendingLightningSend = pendingLightningSend
            self.claimableLightningReceive = claimableLightningReceive
            self.pendingInRound = pendingInRound
            self.pendingExit = pendingExit
            self.pendingBoard = pendingBoard
            self.pendingArkoorSend = pendingArkoorSend
            self.pendingOffboard = pendingOffboard
            let pending = pending ?? (pendingInRound + pendingBoard + pendingArkoorSend + pendingLightningSend
                + claimableLightningReceive + pendingOffboard + pendingExit)
            self.pending = pending
            self.total = total ?? (spendable + needsRefresh + pending)
        }

        private enum CodingKeys: String, CodingKey {
            case spendable, needsRefresh, pendingLightningSend, claimableLightningReceive, pendingInRound
            case pendingExit, pendingBoard, pendingArkoorSend, pendingOffboard, pending, total
        }

        init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            try self.init(
                spendable: values.decode(UInt64.self, forKey: .spendable),
                needsRefresh: values.decode(UInt64.self, forKey: .needsRefresh),
                pendingLightningSend: values.decode(UInt64.self, forKey: .pendingLightningSend),
                claimableLightningReceive: values.decode(UInt64.self, forKey: .claimableLightningReceive),
                pendingInRound: values.decode(UInt64.self, forKey: .pendingInRound),
                pendingExit: values.decode(UInt64.self, forKey: .pendingExit),
                pendingBoard: values.decode(UInt64.self, forKey: .pendingBoard),
                pendingArkoorSend: values.decode(UInt64.self, forKey: .pendingArkoorSend),
                pendingOffboard: values.decode(UInt64.self, forKey: .pendingOffboard),
                pending: values.decodeIfPresent(UInt64.self, forKey: .pending),
                total: values.decodeIfPresent(UInt64.self, forKey: .total))
        }
    }

    struct Onchain: Equatable, Sendable, Decodable {
        var confirmed: UInt64
        /// Trusted + untrusted pending.
        var pending: UInt64
        /// Confirmed + immature + pending.
        var total: UInt64
    }

    let ark: Ark
    let onchain: Onchain

    var offchainSats: UInt64 { ark.total }
    var onchainSats: UInt64 { onchain.total }
    var totalSats: UInt64 { onchainSats + offchainSats }

    /// Unconfirmed onchain (pending + immature) plus pending Ark buckets.
    var pendingSats: UInt64 { (onchain.total - onchain.confirmed) + ark.pending }

    func pendingRoundMessage(amount: String) -> String? {
        ark.pendingInRound == 0 ? nil : "\(amount) pending in a round. These funds become available after the round confirms."
    }

    struct Detail: Codable, Equatable {
        let label: String
        let sats: UInt64
    }
    var details: [Detail] {
        [Detail(label: "Onchain", sats: onchain.total),
         Detail(label: "Confirmed", sats: onchain.confirmed),
         Detail(label: "Pending", sats: onchain.pending),
         Detail(label: "Immature", sats: onchain.total - onchain.confirmed - onchain.pending),
         Detail(label: "Offchain", sats: ark.total),
         Detail(label: "Spendable", sats: ark.spendable),
         Detail(label: "Needs Refresh", sats: ark.needsRefresh),
         Detail(label: "Pending Send", sats: ark.pendingLightningSend),
         Detail(label: "Claimable Lightning Receive", sats: ark.claimableLightningReceive),
         Detail(label: "Pending In Round", sats: ark.pendingInRound),
         Detail(label: "Pending Exit", sats: ark.pendingExit),
         Detail(label: "Pending Board", sats: ark.pendingBoard),
         Detail(label: "Pending Arkoor Send", sats: ark.pendingArkoorSend),
         Detail(label: "Pending Offboard", sats: ark.pendingOffboard)]
    }
    static func display(_ sats: UInt64, hidden: Bool, unit: BitcoinAmountUnit, locale: Locale) -> String {
        hidden ? "••••" : BitcoinAmount.format(sats: Int64(clamping: sats), unit: unit, locale: locale)
    }
}
