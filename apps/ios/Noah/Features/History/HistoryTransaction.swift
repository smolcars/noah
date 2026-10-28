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
    // swiftlint:disable discouraged_optional_collection - persisted history rows omit what the source didn't report
    var sentTo: [Destination]?
    var receivedOn: [Destination]?
    var inputVtxos: [String]?
    var outputVtxos: [String]?
    var exitedVtxos: [String]?
    // swiftlint:enable discouraged_optional_collection
    var balanceChangeSat: Int64?
    // swiftlint:disable discouraged_optional_boolean - persisted history rows omit what the source didn't report
    var hasOnchainFee: Bool?
    var hasConfirmation: Bool?
    // swiftlint:enable discouraged_optional_boolean
    var confirmationHeight: UInt32?
    var confirmationHash: String?
    var repeatPayment: RepeatPayment?
    var preimage: String?
    /// The SDK's, or decoded from the stored BOLT11 invoice.
    var paymentHash: String?
    /// The description stored in the invoice or offer.
    var paymentDescription: String?
    /// The schedule this send paid, joined from the recurring journal.
    var recurring: Recurring?
    /// The exit (its VTXO ID) this on-chain transaction paid fees for: a fee child bark recorded for it.
    var exitFeeFor: String?

    struct Destination: Codable, Equatable, Sendable {
        var destination: String
        var amount_sat: Int64
        var paymentMethod: String?
    }

    struct Recurring: Codable, Equatable, Sendable {
        var scheduleID: String
        var name: String
        var interval: RecurringInterval
    }

    var isCanceled: Bool { movementStatus == "canceled" }
    var isFailed: Bool { movementStatus == "failed" }
    /// A failed Lightning send whose locked coins the server signed back: bark records the
    /// revocation's new coins as produced. One whose coins went to a unilateral exit isn't.
    var isRefunded: Bool {
        isFailed && subsystemName == "bark.lightning_send" && outputVtxos?.isEmpty == false && exitedVtxos?.isEmpty != false
    }
    var isRenewal: Bool { movementKind == "refresh" }
    var isPending: Bool { movementStatus == "pending" || (source == "onchain-wallet" && hasConfirmation != true) }
    /// Bark records an Ark-funded on-chain send's txid once its transaction is broadcast.
    var broadcastTxid: String? { HistoryMetadata.parse(metadataJson ?? "").offboardTxid }
    /// Bark records a round's funding transaction on the renewal that joined it.
    var fundingTxid: String? { HistoryMetadata.parse(metadataJson ?? "").fundingTxid }
    /// A settled Lightning send to an invoice without its own amount. Retrying one, bark 0.8 reuses its movement
    /// and keeps the first attempt's destination amount and fee field, so those numbers prove nothing.
    var paidAmountlessInvoice: Bool {
        guard direction == "outgoing", movementStatus == "successful", subsystemName == "bark.lightning_send",
              let invoice = sentTo?.first(where: { PaymentEncoding.invoice($0.destination) != nil })?.destination else { return false }
        return !SendReconciliation.fixesAmount(invoice)
    }
    /// The amount shown and shared: for a paid amountless invoice, the debit its balance change proves (amount
    /// and fee together), or nothing; never its first attempt's number.
    var shownAmount: Int64? {
        guard paidAmountlessInvoice else { return amount }
        return effectiveBalanceSat.flatMap { $0 < 0 ? -$0 : nil }
    }
    /// The on-chain transaction the record itself names: the wallet's own, an offboard's broadcast or a board's
    /// anchor. Never derived from a coin ID: `txid` falls back to one (as today's CSV does), whose parent
    /// transaction isn't this payment's.
    var chainTxid: String? {
        let named = source == "onchain-wallet" ? txid : broadcastTxid ?? chainAnchor.flatMap { $0.split(separator: ":").first.map(String.init) }
        return named.flatMap { $0.isEmpty ? nil : $0 }
    }
    var isInternalTransfer: Bool { movementKind == "onboard" }
    var canRepeat: Bool { direction == "outgoing" && movementStatus == "successful" && repeatPayment != nil }
    var displayLabel: String {
        isCanceled ? L10n.text("Canceled {value1}", baseLabel) : baseLabel
    }
    /// The label without the canceled prefix.
    var baseLabel: String {
        if exitFeeFor != nil { return "Exit fee" }
        return switch movementKind {
        case "onboard": "Board"
        case "offboard": "Offboard"
        case "exit": "Ark Exit"
        case "refresh": "Renewed"
        default:
            switch type {
            case "Bolt11", "Lnurl": "Lightning"
            case "Arkoor": "Ark"
            default: type
            }
        }
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

    var movementKindLabel: String? {
        switch movementKind {
        case "arkoor-receive": "Ark Receive"
        case "onboard": "Board"
        case "offboard": "Offboard"
        case "send-onchain": "Onchain Send"
        case "exit": "Ark Exit"
        case "lightning-receive": "Lightning Receive"
        case "refresh": "Refresh"
        default: movementKind
        }
    }

    var confirmationLabel: String? {
        guard dateLabel != nil else { return nil }
        return hasConfirmation == true ? L10n.text("Confirmed at block {height}", confirmationHeight ?? 0) : "Unconfirmed"
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
        return Self.explorerURL(txid: String(txid), variant: variant)
    }

    static func explorerURL(txid: String, variant: AppVariant) -> URL? {
        let base = variant == .mainnet ? "https://mempool.space/tx/" : "https://mempool.space/signet/tx/"
        return URL(string: base)?.appendingPathComponent(txid)
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
    var fundingTxid: String?
    var onchainFeeSat: Int64?
    var chainAnchor: String?
    var paymentPreimage: String?
    var repeatPayment: RepeatPayment?

    static func parse(_ json: String) -> Self {
        guard let data = json.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return Self() }
        // swiftlint:disable:next legacy_objc_type - only NSNumber tells a JSON Bool from a number (CFBoolean)
        let fee = object["onchain_fee_sat"] as? NSNumber
        return Self(
            offboardTxid: object["offboard_txid"] as? String,
            fundingTxid: object["funding_txid"] as? String,
            onchainFeeSat: fee.flatMap { CFGetTypeID($0) != CFBooleanGetTypeID() ? Int64(exactly: $0.doubleValue) : nil },
            chainAnchor: object["chain_anchor"] as? String,
            paymentPreimage: object["payment_preimage"] as? String,
            repeatPayment: RepeatPayment.parse((object["noah"] as? [String: Any])?["repeat_payment"]))
    }
}

enum HistoryRules {
    static func parseDate(_ value: String) -> Date? {
        let value = value.replacingOccurrences(of: " ", with: #"T"#)
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
        func timestamp(_ row: HistoryTransaction) -> Double {
            if let time = row.sortTimestamp, time.isFinite { return time }
            return (row.timestamp?.timeIntervalSince1970 ?? 0) * 1000
        }
        let aTime = timestamp(a), bTime = timestamp(b)
        if aTime != bTime { return aTime > bTime }
        if (a.sortHeight ?? 0) != (b.sortHeight ?? 0) { return (a.sortHeight ?? 0) > (b.sortHeight ?? 0) }
        return a.id < b.id
    }
}
