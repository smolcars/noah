import Foundation

struct HistoryTransaction: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var type: String
    var amount: Int64
    var date: String
    var direction: String
    var dateLabel: String?
    var sortTimestamp: Double?
    var sortHeight: UInt32?
    var source: String?
    var txid: String?
    var txHex: String?
    var destination: String?
    var btcPrice: Double?
    var movementId: UInt32?
    var movementStatus: String?
    var movementKind: String?
    var subsystemName: String?
    var subsystemKind: String?
    var metadataJson: String?
    var intendedBalanceSat: Int64?
    var effectiveBalanceSat: Int64?
    var offchainFeeSat: Int64?
    var onchainFeeSat: Int64?
    var chainAnchor: String?
    var sentTo: [Destination]?
    var receivedOn: [Destination]?
    var inputVtxos: [String]?
    var outputVtxos: [String]?
    var exitedVtxos: [String]?
    var balanceChangeSat: Int64?
    var hasOnchainFee: Bool?
    var hasConfirmation: Bool?
    var confirmationHeight: UInt32?
    var confirmationHash: String?
    var repeatPayment: RepeatPayment?

    struct Destination: Codable, Equatable, Sendable {
        var destination: String
        var amount_sat: Int64
        var paymentMethod: String?
    }

    var isCanceled: Bool { movementStatus == "canceled" }
    var isInternalTransfer: Bool { movementKind == "onboard" }
    var canRepeat: Bool { direction == "outgoing" && movementStatus == "successful" && repeatPayment != nil }
    var displayLabel: String {
        let label: String
        switch movementKind {
        case "onboard": label = "Board"
        case "offboard": label = "Offboard"
        case "exit": label = "Ark Exit"
        default:
            switch type {
            case "Bolt11", "Lnurl": label = "Lightning"
            case "Arkoor": label = "Ark"
            default: label = type
            }
        }
        return isCanceled ? "Canceled \(label)" : label
    }

    struct Accounting: Codable, Equatable {
        let direction: String
        let amount: Int64
    }
    var accounting: Accounting {
        if isCanceled { return Accounting(direction: "None", amount: 0) }
        if isInternalTransfer { return Accounting(direction: "Transfer", amount: amount) }
        return direction == "outgoing"
            ? Accounting(direction: "Outgoing", amount: -amount)
            : Accounting(direction: "Incoming", amount: amount)
    }

    var confirmationLabel: String? {
        guard dateLabel != nil else { return nil }
        return hasConfirmation == true ? "Confirmed at block \(confirmationHeight ?? 0)" : "Unconfirmed"
    }
    var timestamp: Date? { HistoryRules.parseDate(date) }
    var statusLabel: String? { movementStatus?.capitalized }
    var receiptStatus: String {
        if movementStatus == "successful" || hasConfirmation == true { return "Completed" }
        return statusLabel ?? (source == "onchain-wallet" || balanceChangeSat != nil ? "Unconfirmed" : "Recorded")
    }

    func explorerURL(variant: AppVariant) -> URL? {
        guard type == "Onchain" || source == "onchain-wallet" || balanceChangeSat != nil,
              let txid = txid?.split(separator: ":").first, !txid.isEmpty else { return nil }
        let base = variant == .mainnet ? "https://mempool.space/tx/" : "https://mempool.space/signet/tx/"
        return URL(string: base)?.appendingPathComponent(String(txid))
    }
}

enum HistoryFilter: String, CaseIterable, Identifiable {
    case all = "All", lightning = "Lightning", ark = "Ark", onchain = "Onchain"
    var id: Self { self }
    func includes(_ transaction: HistoryTransaction) -> Bool {
        switch self {
        case .all: true
        case .lightning: ["Bolt11", "Lnurl"].contains(transaction.type)
        case .ark: transaction.type == "Arkoor"
        case .onchain: transaction.type == "Onchain"
        }
    }
}

struct HistoryMetadata: Codable, Equatable, Sendable {
    var offboardTxid: String?
    var onchainFeeSat: Int64?
    var chainAnchor: String?
    var repeatPayment: RepeatPayment?

    static func parse(_ json: String) -> Self {
        guard let data = json.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return Self() }
        let fee = object["onchain_fee_sat"] as? NSNumber
        return Self(
            offboardTxid: object["offboard_txid"] as? String,
            onchainFeeSat: fee.flatMap { CFGetTypeID($0) != CFBooleanGetTypeID() ? Int64(exactly: $0.doubleValue) : nil },
            chainAnchor: object["chain_anchor"] as? String,
            repeatPayment: RepeatPayment.parse((object["noah"] as? [String: Any])?["repeat_payment"]))
    }
}

enum HistoryRules {
    static func parseDate(_ value: String) -> Date? {
        let value = value.replacingOccurrences(of: " ", with: "T")
        let formatter = ISO8601DateFormatter()
        for options: ISO8601DateFormatter.Options in [[.withInternetDateTime, .withFractionalSeconds], [.withInternetDateTime]] {
            formatter.formatOptions = options
            if let date = formatter.date(from: value) ?? formatter.date(from: value + "Z") { return date }
        }
        return nil
    }

    static func isoDate(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    static func merge(_ movements: [HistoryTransaction], onchain: [HistoryTransaction]) -> [HistoryTransaction] {
        var byTxid: [String: HistoryTransaction] = [:]
        for transaction in onchain { if let txid = transaction.txid, !txid.isEmpty { byTxid[txid] = transaction } }
        var mergedIDs: Set<String> = []
        let merged = movements.map { movement in
            guard ["onboard", "offboard"].contains(movement.movementKind),
                  let txid = movement.txid, let chain = byTxid[txid] else { return movement }
            mergedIDs.insert(chain.id)
            var result = movement
            result.txHex = chain.txHex
            result.btcPrice = movement.btcPrice ?? chain.btcPrice
            result.balanceChangeSat = chain.balanceChangeSat
            result.hasOnchainFee = chain.hasOnchainFee
            result.onchainFeeSat = movement.onchainFeeSat ?? chain.onchainFeeSat
            result.hasConfirmation = chain.hasConfirmation
            result.confirmationHeight = chain.confirmationHeight
            result.confirmationHash = chain.confirmationHash
            result.sortHeight = chain.sortHeight
            return result
        }
        return merged + onchain.filter { !mergedIDs.contains($0.id) }
    }

    static func precedes(_ a: HistoryTransaction, _ b: HistoryTransaction) -> Bool {
        let aChain = a.source == "onchain-wallet", bChain = b.source == "onchain-wallet"
        let aPending = aChain && a.hasConfirmation != true, bPending = bChain && b.hasConfirmation != true
        if aPending != bPending { return aPending }
        let aTimed = (a.sortTimestamp ?? 0) > 0, bTimed = (b.sortTimestamp ?? 0) > 0
        if aTimed && bTimed { return (a.sortTimestamp ?? 0) > (b.sortTimestamp ?? 0) }
        if aChain && bChain { return (a.sortHeight ?? 0) > (b.sortHeight ?? 0) }
        if aTimed != bTimed { return aTimed }
        return (a.sortTimestamp ?? (a.timestamp?.timeIntervalSince1970 ?? 0) * 1000)
            > (b.sortTimestamp ?? (b.timestamp?.timeIntervalSince1970 ?? 0) * 1000)
    }
}
