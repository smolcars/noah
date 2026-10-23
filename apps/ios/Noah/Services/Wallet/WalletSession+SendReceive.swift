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

    /// One status read for an Ark-funded on-chain send's transaction (Activity's block row).
    func chainTransactionStatus(_ txid: String) async throws -> ChainTransactionStatus {
        try await withOpenWallet { .init(try await $0.wallet.transactionStatus(txid: txid)) }
    }

    /// What history records coming back from a failed Lightning send (see `SendRefundEvidence`). `before` is the
    /// attempt's snapshot of movement ids.
    func lightningSendRefund(paymentHash: String, before: Set<UInt32>) async throws -> SendRefund {
        try await withOpenWallet { SendRefundEvidence.refund(try await $0.wallet.history(), paymentHash: paymentHash, before: before) }
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
    /// incoming transactions, for Receive's on-chain arrival and its block row. The first
    /// check (`baseline`) also notes what the wallet knew before its sync.
    func receiveChainSnapshot(address: String, baseline: Bool) async throws -> ReceiveChainSnapshot {
        let flight = syncFlight
        return try await withOpenWallet { handles in
            let before = baseline ? Set(try await handles.onchain.transactions().map(\.txid)) : nil
            try await flight.serialized { _ = try await handles.onchain.sync() }
            let tip = try await handles.onchain.tipHeight()
            let incoming = try await handles.onchain.transactions().filter { $0.balanceChangeSats > 0 }.map {
                ReceiveChainSnapshot.Transaction(
                    txid: $0.txid, toAddress: (try? SendTransaction.amount(to: address, hex: $0.txHex)) ?? 0,
                    received: UInt64($0.balanceChangeSats), confirmedHeight: $0.confirmation?.height,
                    inputs: Set((try? SendTransaction.parse(hex: $0.txHex).inputs) ?? []))
            }
            return ReceiveChainSnapshot(tip: tip, incoming: incoming, before: before)
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

    /// A retried invoice reuses the hash's movement from before the attempt, whose outputs include the earlier
    /// attempts' coins, so nothing there can be tied to this one (async-payments.md, "Reused hashes").
    static func refund(_ movements: [Movement], paymentHash: String, before: Set<UInt32>) -> SendRefund {
        let reused = movements.contains {
            $0.subsystemName == "bark.lightning_send" && $0.subsystemKind == "send" && $0.paymentHash == paymentHash && before.contains($0.id)
        }
        if reused { return .untied }
        return recorded(movements, paymentHash: paymentHash) ? .recorded : .notRecorded
    }
}

/// What a failed Lightning send's records show came back to the wallet.
enum SendRefund: Equatable, Sendable {
    /// bark recorded the revocation's coins on this attempt's own movement: "back in your wallet".
    case recorded
    /// No refund recorded: the send failed before leaving the wallet, or its coins went to an exit.
    case notRecorded
    /// A retried invoice: what came back can't be tied to this attempt.
    case untied
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
    /// On the first check, the transactions the wallet knew before syncing.
    var before: Set<String>? = nil

    /// One check of Receive's watch: the new unconfirmed incoming transactions to announce
    /// (those paying the request's address first, then any other), and what's known after.
    /// The first check (`known` nil) knows what the wallet had before its sync, so anything
    /// that synced in meanwhile is news, to any of your addresses; an unconfirmed payment to
    /// this request's fresh address is news even if a sync elsewhere fetched it first. An
    /// already-confirmed transaction is never news.
    /// A transaction spending any of `spent` (the inputs of deposits already announced, and of
    /// their replacements) replaces one of them (RBF) and is never a new arrival. The `spent`
    /// returned adds its inputs and the arrivals', so a replacement of a replacement isn't either.
    func look(known: Set<String>?, announced: Set<String>, spent: Set<SendTransaction.Input> = [])
        -> (arrivals: [Transaction], known: Set<String>, spent: Set<SendTransaction.Input>) {
        let unseenHere = Set(incoming.filter { $0.toAddress > 0 && $0.confirmedHeight == nil }.map(\.txid))
        let first = (before ?? Set(incoming.map(\.txid))).subtracting(unseenHere)
        let seen = (known ?? first).union(announced)
        let spent = incoming.filter { !$0.inputs.isDisjoint(with: spent) }.reduce(spent) { $0.union($1.inputs) }
        let fresh = incoming.filter { !seen.contains($0.txid) && $0.confirmedHeight == nil && $0.inputs.isDisjoint(with: spent) }
        let arrivals = fresh.filter { $0.toAddress > 0 } + fresh.filter { $0.toAddress == 0 }
        return (arrivals, seen.union(incoming.map(\.txid)), arrivals.reduce(spent) { $0.union($1.inputs) })
    }

    /// A followed transaction in this check, or the one that replaced it (spending any of
    /// its inputs), which may already be confirmed.
    func follow(txid: String, inputs: Set<SendTransaction.Input>) -> Transaction? {
        incoming.first { $0.txid == txid } ?? incoming.first { !inputs.isEmpty && !$0.inputs.isDisjoint(with: inputs) }
    }
}
