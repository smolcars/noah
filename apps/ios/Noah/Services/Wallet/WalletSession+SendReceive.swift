import BarkBoltFFI
import Foundation

/// Read-only observations for the Send and Receive motion (send-receive.md). None of
/// these move funds, change a payment or write anything; they read the wallet's own
/// chain source, history and fee schedule.
extension WalletSession {
    /// The chain source's tip height (the same Esplora or bitcoind the wallet uses).
    func chainTip() async throws -> UInt32 {
        try await withOpenWallet { try await $0.onchain.tipHeight() }
    }

    /// Whether history records the refund of a failed Lightning send (see `SendRefundEvidence`).
    func lightningSendRefunded(paymentHash: String) async throws -> Bool {
        try await withOpenWallet { SendRefundEvidence.recorded(try await $0.wallet.history(), paymentHash: paymentHash) }
    }

    /// Today's fee estimates for each rail a request offers, for the Method cards.
    /// Estimates only: nothing is prepared or reserved. A rail whose estimate fails is left out.
    func sendRailEstimates(amount: UInt64, request: [String: String]) async -> [SendRail: UInt64] {
        guard amount > 0 else { return [:] }
        return (try? await withOpenWallet { handles in
            var fees: [SendRail: UInt64] = [:]
            if request["arkAddress"] != nil,
               let estimate = try? await handles.wallet.estimateArkoorPaymentFee(amountSats: amount) {
                fees[.ark] = estimate.feeSats
            }
            if request["lightningInvoice"] != nil || request["offer"] != nil,
               let estimate = try? await handles.wallet.estimateLightningSendFee(amountSats: amount) {
                fees[.lightning] = estimate.feeSats
            }
            if let address = request["onchainAddress"],
               let estimate = try? await handles.wallet.estimateSendOnchainFee(address: address, amountSats: amount) {
                fees[.onchain] = estimate.feeSats
            }
            return fees
        }) ?? [:]
    }

    /// Syncs the on-chain wallet (shared with today's sync flight) and reads the tip and
    /// incoming transactions, for Receive's on-chain arrival and its block row.
    func receiveChainSnapshot(address: String) async throws -> ReceiveChainSnapshot {
        let flight = syncFlight
        return try await withOpenWallet { handles in
            try await flight.serialized { _ = try await handles.onchain.sync() }
            let tip = try await handles.onchain.tipHeight()
            let incoming = try await handles.onchain.transactions().filter { $0.balanceChangeSats > 0 }.map {
                ReceiveChainSnapshot.Transaction(
                    txid: $0.txid, toAddress: (try? SendTransaction.amount(to: address, hex: $0.txHex)) ?? 0,
                    received: UInt64($0.balanceChangeSats), confirmedHeight: $0.confirmation?.height,
                    inputs: Set((try? SendTransaction.parse(hex: $0.txHex).inputs) ?? []))
            }
            return ReceiveChainSnapshot(tip: tip, incoming: incoming)
        }
    }
}

/// A failed Lightning send whose coins came back: bark revoked the HTLC, recorded new
/// coins for the full amount (nothing left the wallet) and finished the movement as
/// failed. A send that instead went to an emergency exit, or whose revocation failed
/// and is still pending, is not a refund.
enum SendRefundEvidence {
    static func recorded(_ movements: [Movement], paymentHash: String) -> Bool {
        let sends = movements.filter {
            $0.subsystemName == "bark.lightning_send" && $0.subsystemKind == "send" && $0.paymentHash == paymentHash
        }
        guard sends.count == 1, let send = sends.first else { return false }
        return send.status == "failed" && !send.outputVtxoIds.isEmpty && send.exitedVtxoIds.isEmpty
            && send.effectiveBalanceSats == 0 && send.offchainFeeSats == 0
    }
}

/// What Receive's on-chain watch sees on one check.
struct ReceiveChainSnapshot: Equatable, Sendable {
    struct Transaction: Equatable, Sendable {
        var txid: String
        /// Sats paid to the request's Bitcoin address; zero when the transaction doesn't pay it.
        var toAddress: UInt64
        var received: UInt64
        var confirmedHeight: UInt32?
        /// The outputs it spends, to recognise a replacement (RBF).
        var inputs: Set<SendTransaction.Input> = []

        /// What Receive shows arriving: the sats paid to the address, else all received.
        var arrivingSats: Int64 { Int64(clamping: toAddress > 0 ? toAddress : received) }
    }

    var tip: UInt32
    var incoming: [Transaction]

    /// One check of Receive's watch: the new unconfirmed incoming transactions to announce
    /// (those paying the request's address first, then any other), and what's known after.
    /// The first check (`known` nil) already knows every transaction except unconfirmed ones
    /// paying the request's address: those arrived while it synced. An already-confirmed
    /// transaction is never news.
    func look(known: Set<String>?, announced: Set<String>) -> (arrivals: [Transaction], known: Set<String>) {
        let seen = (known ?? Set(incoming.filter { $0.toAddress == 0 || $0.confirmedHeight != nil }.map(\.txid))).union(announced)
        let fresh = incoming.filter { !seen.contains($0.txid) && $0.confirmedHeight == nil }
        return (fresh.filter { $0.toAddress > 0 } + fresh.filter { $0.toAddress == 0 }, seen.union(incoming.map(\.txid)))
    }

    /// A followed transaction in this check, or the one that replaced it (spending any of
    /// its inputs), which may already be confirmed.
    func follow(txid: String, inputs: Set<SendTransaction.Input>) -> Transaction? {
        incoming.first { $0.txid == txid } ?? incoming.first { !inputs.isEmpty && !$0.inputs.isDisjoint(with: inputs) }
    }
}
