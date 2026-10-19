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

        /// `Balance::pending()`.
        var pending: UInt64 {
            pendingInRound + pendingBoard + pendingArkoorSend + pendingLightningSend
                + claimableLightningReceive + pendingOffboard + pendingExit
        }

        /// `Balance::total()`.
        var total: UInt64 { spendable + needsRefresh + pending }
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
}
