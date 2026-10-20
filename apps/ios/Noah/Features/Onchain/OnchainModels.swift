import Foundation

struct VtxoModel: Identifiable, Equatable, Sendable {
    var id: String
    var amount: UInt64
    var expiry: UInt32
    var state: String
    var kind: String
    var exitDepth: UInt32
    var exitWeight: UInt64
    var registered: Bool
    var serverPubkey: String
    var exitDelta: UInt32
    var anchorPoint: String
    var point: String
    var expiring: Bool
    var tip: UInt32
    var expired: Bool { state != "Locked" && expiry <= tip }
    var status: String {
        state == "Locked"
            ? "Locked"
            : expired ? "Expired" : expiring ? "Expiring" : state == "Spendable" ? "Active" : state
    }
    var eligible: Bool { ExitRules.eligible(state: state, amount: amount) }
    var refreshable: Bool { state == "Spendable" }
}

struct ExitModel: Identifiable, Equatable, Sendable {
    var id: String
    var amount: UInt64
    var state: String
    var details: ExitDetails
    var history: [String]
    var historyDetails: [ExitDetails]
    var claimable: Bool
    var cancelable: Bool {
        ExitRules.cancelable(
            state: state, finalTransactionKind: details.transactions?.last?.status.kind)
    }
    var timeline: [ExitTimelineItem] {
        ExitTimeline.build(
            history: history, historyDetails: historyDetails, state: state, details: details)
    }
}

struct OnchainOverview: Sendable {
    var walletID: String
    var vtxos: [VtxoModel]
    var exits: [ExitModel]
    var confirmed: UInt64
    var tip: UInt32
    var pendingTotal: UInt64
    var claimableHeight: UInt32?
}

enum OnchainAction: String, Codable, Sendable {
    case board, refresh, startExit, progress, claim, cancel
    var title: String {
        switch self {
        case .board: "Board to Ark"
        case .refresh: "Refresh VTXOs"
        case .startExit: "Start Emergency Exit"
        case .progress: "Progress Exits"
        case .claim: "Claim Exits"
        case .cancel: "Cancel Exit"
        }
    }
}

struct OnchainQuote: Identifiable, Codable, Equatable, Sendable {
    var id = UUID()
    var action: OnchainAction
    var walletID: String
    var ids: [String]
    var destination: String?
    var destinationIsMine: Bool?
    var amount: UInt64
    var fee: UInt64
    var chainFee: UInt64
    var confirmed: UInt64
    var feeRate: UInt64?
    var createdAt = Date()
    var automatic = false
    // Optional so journals written before MAX used boardAll remain readable.
    var boardAll: Bool?
    var externalDestinationWarning: String? {
        action == .claim && destinationIsMine == false
            ? "This address isn't in your Noah wallet. The claimed funds will be sent there." : nil
    }
    var net: UInt64 { ExitRules.received(amount: amount, claimFee: fee) }
    var totalFee: UInt64 { fee + chainFee }
    var fundable: Bool { confirmed >= chainFee }
    var inputs: ExitReviewInputs {
        .init(
            walletId: walletID, vtxoIds: ids, amountSat: amount, scope: action.rawValue,
            destinationAddress: destination, revision: "")
    }
    // Compare the reviewed input set and all costs; UUID and creation time are not payment inputs.
    func matches(_ other: Self) -> Bool {
        inputs.matches(other.inputs) && fee == other.fee
            && chainFee == other.chainFee && confirmed == other.confirmed && feeRate == other.feeRate
            && destinationIsMine == other.destinationIsMine && automatic == other.automatic && boardAll == other.boardAll
    }
}

enum OnchainFailure: LocalizedError {
    case unavailable, changed, invalid, uncertain, busy, cancellationRefused, claimFeeChanged, constructionFailed
    var errorDescription: String? {
        switch self {
        case .unavailable: "Wait for the wallet to be ready, then refresh."
        case .changed:
            "The wallet or fees changed, or the quote expired. Review again before confirming."
        case .invalid:
            "Check the amount, selected VTXOs, and destination. There may not be enough confirmed funds."
        case .uncertain:
            "The operation may have been submitted. Check its status before repeating it. Other recovery operations remain available."
        case .busy: "This operation overlaps an unresolved attempt. Check its status before repeating it."
        case .constructionFailed: "The transaction could not be constructed. Nothing was broadcast. Review again."
        case .cancellationRefused:
            "This exit can no longer be canceled. Its final transaction may already be broadcast. Refresh its status."
        case .claimFeeChanged:
            "The built claim fee differs from the estimate. Nothing was broadcast. Review the claim again."
        }
    }
}

struct OnchainReceipt: Codable, Sendable, ExpressibleByStringLiteral {
    var message: String
    var txid: String?
    var onchainFee: UInt64?
    init(message: String, txid: String? = nil, onchainFee: UInt64? = nil) {
        self.message = message
        self.txid = txid
        self.onchainFee = onchainFee
    }
    static func boardingMessage(fee: UInt64?) -> String {
        "Boarding submitted. Funds become spendable after the board completes."
            + (fee.map { " Actual onchain fee: \($0) sats." } ?? " Actual onchain fee is not yet available.")
    }
    init(stringLiteral value: String) { message = value }
    init(from decoder: Decoder) throws {
        if let message = try? decoder.singleValueContainer().decode(String.self) {
            self.message = message
        } else {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            message = try values.decode(String.self, forKey: .message)
            txid = try values.decodeIfPresent(String.self, forKey: .txid)
            onchainFee = try values.decodeIfPresent(UInt64.self, forKey: .onchainFee)
        }
    }
}
