import BarkBoltFFI
import Foundation

/// Classification and accounting mirror `useTransactions` and `barkMovement`.
/// The SDK's typed destination records replace Nitro's string/object union.
enum HistoryMapping {
    static let kinds = [
        "bark.board:board": "onboard",
        "bark.offboard:offboard": "offboard", "bark.round:offboard": "offboard",
        "bark.offboard:send_onchain": "send-onchain", "bark.round:send_onchain": "send-onchain",
        "bark.arkoor:receive": "arkoor-receive", "bark.exit:start": "exit",
        "bark.lightning_receive:receive": "lightning-receive",
    ]
    static func subsystemID(_ movement: Movement) -> String? {
        let id = "\(movement.subsystemName.lowercased()):\(movement.subsystemKind.lowercased())"
        return kinds[id] != nil || ["bark.arkoor:send", "bark.lightning_send:send"].contains(id) ? id : nil
    }

    static func isFailedOrCanceled(_ status: String) -> Bool { ["failed", "canceled"].contains(status) }

    static func include(_ movement: Movement) -> Bool {
        let kind = subsystemID(movement).flatMap { kinds[$0] }
        if ["onboard", "offboard"].contains(kind) { return true }
        return movement.status != "failed" && (movement.intendedBalanceSats != 0 || movement.effectiveBalanceSats != 0)
    }

    static func boardAmount(_ movement: Movement) -> Int64 {
        for value in [abs(movement.intendedBalanceSats), abs(movement.effectiveBalanceSats),
                      sum(movement.sentToAddresses), sum(movement.receivedOnAddresses)] where value > 0 { return value }
        return 0
    }

    static func transactionID(_ movement: Movement, metadata: HistoryMetadata, outgoing: Bool) -> String {
        let anchor = metadata.chainAnchor?.replacingOccurrences(of: ":\\d+$", with: "", options: .regularExpression)
        if let txid = metadata.offboardTxid ?? anchor, !txid.isEmpty { return txid }
        let candidates = outgoing
            ? movement.inputVtxoIds + movement.outputVtxoIds + movement.exitedVtxoIds
            : movement.outputVtxoIds + movement.inputVtxoIds + movement.exitedVtxoIds
        return candidates.first { !$0.isEmpty } ?? "movement-\(movement.id)"
    }

    private static func sum(_ destinations: [MovementDestination]) -> Int64 {
        destinations.reduce(0) { $0 + Int64($1.amountSats) }
    }

    static func transaction(_ movement: Movement, now: Date = Date()) -> HistoryTransaction {
        let subsystem = subsystemID(movement)
        let kind = subsystem.flatMap { kinds[$0] }
        let outgoing: Bool
        switch kind {
        case "offboard", "send-onchain", "exit": outgoing = true
        case "onboard", "arkoor-receive", "lightning-receive": outgoing = false
        default:
            outgoing = subsystem == "bark.arkoor:send" || subsystem == "bark.lightning_send:send"
                || movement.effectiveBalanceSats < 0
        }
        let destinations = outgoing ? movement.sentToAddresses : movement.receivedOnAddresses
        let routed = sum(destinations)
        let amount = ["onboard", "offboard"].contains(kind)
            ? boardAmount(movement) : routed > 0 ? routed : abs(movement.effectiveBalanceSats)
        let metadata = HistoryMetadata.parse(movement.metadataJson)
        let first = destinations.first?.paymentMethod
        let repeatPayment = outgoing && movement.sentToAddresses.count == 1
            ? RepeatPayment.resolve(metadata: metadata.repeatPayment, destination: first?.value,
                                    paymentMethod: first?.methodType, amountSat: amount) : nil
        let type: String
        if metadata.repeatPayment != nil || first?.methodType == "lightning-address" { type = "Lnurl" }
        else if kind == "lightning-receive" { type = "Bolt11" }
        else if destinations.contains(where: { $0.paymentMethod.methodType == "bitcoin" }) { type = "Onchain" }
        else if kind == "arkoor-receive" || subsystem == "bark.arkoor:send" { type = "Arkoor" }
        else if subsystem == "bark.lightning_send:send" { type = "Bolt11" }
        else if ["onboard", "offboard", "send-onchain", "exit"].contains(kind) { type = "Onchain" }
        else { type = outgoing && !destinations.isEmpty ? "Arkoor" : "Onchain" }
        // Nitro's `time.created_at` corresponds to the SDK's `createdAt`.
        let date = HistoryRules.parseDate(movement.createdAt) ?? now
        return HistoryTransaction(
            id: "movement-\(movement.id)", type: type, amount: amount, date: HistoryRules.isoDate(date),
            direction: outgoing ? "outgoing" : "incoming", sortTimestamp: date.timeIntervalSince1970 * 1000,
            source: "ark", txid: transactionID(movement, metadata: metadata, outgoing: outgoing),
            destination: repeatPayment?.destination ?? first?.value ?? "", movementId: movement.id,
            movementStatus: movement.status, movementKind: kind,
            subsystemName: subsystem == nil ? nil : movement.subsystemName.lowercased(),
            subsystemKind: subsystem == nil ? nil : movement.subsystemKind.lowercased(),
            metadataJson: movement.metadataJson, intendedBalanceSat: movement.intendedBalanceSats,
            effectiveBalanceSat: movement.effectiveBalanceSats, offchainFeeSat: Int64(movement.offchainFeeSats),
            onchainFeeSat: metadata.onchainFeeSat, chainAnchor: metadata.chainAnchor,
            sentTo: movement.sentToAddresses.map(destination), receivedOn: movement.receivedOnAddresses.map(destination),
            inputVtxos: movement.inputVtxoIds, outputVtxos: movement.outputVtxoIds, exitedVtxos: movement.exitedVtxoIds,
            repeatPayment: repeatPayment)
    }

    private static func destination(_ value: MovementDestination) -> HistoryTransaction.Destination {
        .init(destination: value.paymentMethod.value, amount_sat: Int64(value.amountSats), paymentMethod: value.paymentMethod.methodType)
    }

    static func transaction(_ chain: WalletTransaction, blockTime: Date? = nil) -> HistoryTransaction {
        HistoryTransaction(
            id: "onchain-wallet-\(chain.txid)", type: "Onchain", amount: abs(chain.balanceChangeSats),
            date: HistoryRules.isoDate(blockTime ?? Date(timeIntervalSince1970: 0)),
            direction: chain.balanceChangeSats < 0 ? "outgoing" : "incoming",
            dateLabel: blockTime == nil ? (chain.confirmation.map { "Confirmed at block \($0.height)" } ?? "Unconfirmed") : nil,
            sortTimestamp: blockTime.map { $0.timeIntervalSince1970 * 1000 }, sortHeight: chain.confirmation?.height,
            source: "onchain-wallet", txid: chain.txid, txHex: chain.txHex,
            onchainFeeSat: chain.onchainFeeSats.map(Int64.init), balanceChangeSat: chain.balanceChangeSats,
            hasOnchainFee: chain.onchainFeeSats != nil, hasConfirmation: chain.confirmation != nil,
            confirmationHeight: chain.confirmation?.height, confirmationHash: chain.confirmation?.hash)
    }
}
