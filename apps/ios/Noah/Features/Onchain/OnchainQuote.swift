import BarkBoltFFI
import Foundation

enum VtxoStatus: String, CaseIterable {
    case active, expiring, expired, locked
    var label: String {
        switch self { case .active: "Active"; case .expiring: "Expiring"; case .expired: "Expired"; case .locked: "Locked" }
    }
}

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
    var statusID: VtxoStatus? {
        state == "Locked" ? .locked : expired ? .expired : expiring ? .expiring : state == "Spendable" ? .active : nil
    }
    var status: String { statusID?.label ?? state }
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
    /// Noah's id for this run of the exit (`ExitRuns`); empty for exits started before Noah kept one.
    var run = ""
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
    /// The server's `vtxo_exit_delta`: blocks an exit waits after its last transaction confirms.
    var exitDelta: UInt32?
    /// Fees this wallet paid for its exits, by fee-child txid.
    var childFees: [String: UInt64] = [:]
    /// What this wallet received, by claim txid (claims to this phone).
    var received: [String: UInt64] = [:]
    /// Decision 3: a parked exit's package fee (its parent and bark's canonical child), by exit.
    var packageFees: [String: UInt64] = [:]
    /// The claims Noah built, by claim txid (`ExitClaimRecord`).
    var claimRecords: [String: ExitClaimRecord] = [:]
    /// Since when bark's daemon has been progressing exits in this session; nil without it.
    var daemonSince: Date? = .distantPast
    /// The app's own last exit progress (Check the chain): its tip, the parking episode it left waiting
    /// for a fee child after trying to fund it, by exit, and whether it failed.
    var checkedTip: UInt32?
    var provenParked: [String: String] = [:]
    var checkFailed = false
}

enum OnchainAction: String, Codable, Sendable {
    // swiftlint:disable:next raw_value_for_camel_cased_codable_enum - case names are the wire strings; SwiftFormat strips equal raw values
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
    // swiftlint:disable:next discouraged_optional_boolean - nil: not checked yet; persisted in journals
    var destinationIsMine: Bool?
    var amount: UInt64
    var fee: UInt64?
    var chainFee: UInt64?
    var confirmed: UInt64
    var feeRate: UInt64?
    var createdAt = Date()
    /// When this review opened on `ReviewClock`. Never persisted, so nil once read back from storage.
    var reviewedAt: ContinuousClock.Instant?
    var automatic = false
    // Optional so journals written before MAX used boardAll remain readable.
    // swiftlint:disable:next discouraged_optional_boolean - journals written before MAX lack it
    var boardAll: Bool?
    var unknownFeeConsent: UUID?
    var prepared: SendPreparedTransaction?
    private enum CodingKeys: String, CodingKey {
        case id, action, walletID, ids, destination, destinationIsMine, amount, fee, chainFee, confirmed, feeRate,
             createdAt, automatic, boardAll, unknownFeeConsent, prepared
    }
    func elapsed(at now: ContinuousClock.Instant = .now) -> Duration {
        ReviewClock.elapsed(since: reviewedAt, createdAt: createdAt, now: now)
    }
    var isCurrent: Bool {
        prepared.map { $0.isCurrent && elapsed() < .seconds(120) } ?? ExitRules.current(elapsed: elapsed())
    }
    var externalDestinationWarning: String? {
        action == .claim && destinationIsMine == false
            ? "This address isn't in your Noah wallet. The claimed funds will be sent there." : nil
    }
    var feesUnknown: Bool { fee == nil && chainFee == nil && (action == .startExit || action == .claim) }
    var hasUnknownFeeConsent: Bool { feesUnknown && unknownFeeConsent == id }
    var net: UInt64 { ExitRules.received(amount: amount, claimFee: estimated(fee)) }
    /// The prepared transaction spends one of these outpoints ("txid:vout").
    func spends(_ outpoints: Set<String>) -> Bool {
        prepared?.inputs?.contains { outpoints.contains("\($0.txid):\($0.vout)") } == true
    }
    var totalFee: UInt64 { estimated(fee) + estimated(chainFee) }
    /// A fee the caller already checked was estimated (`feesUnknown` quotes have neither).
    func estimated(_ fee: UInt64?) -> UInt64 {
        guard let fee else { preconditionFailure("quote fee read before checking it was estimated") }
        return fee
    }
    var fundable: Bool { chainFee.map { confirmed >= $0 } ?? false }
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
            && prepared == other.prepared
    }
}

enum OnchainFailure: LocalizedError {
    case unavailable, changed, invalid, uncertain, busy, cancellationRefused, claimFeeChanged, constructionFailed
    case insufficientFunds
    case belowMinimum, tooManyInputs, reservedInputs, notSynced, boardNotAttempted
    var errorDescription: String? { message }
    var message: String {
        switch self {
        case .insufficientFunds: "There are not enough confirmed onchain funds for this amount and its fees. Reduce the amount or wait for confirmation."
        case .belowMinimum: "The amount Ark receives is below its minimum. Increase the boarding amount."
        case .tooManyInputs: "There are too many small coins for this board. Try a smaller amount."
        case .reservedInputs: "These coins are unavailable or reserved by another payment in review. Close that review, then try again."
        case .notSynced: "Sync the wallet once before preparing a board."
        case .boardNotAttempted: "Boarding was not submitted. Review again when the wallet is ready."
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

struct OnchainReceipt: Codable, Equatable, Sendable, ExpressibleByStringLiteral {
    var message: String
    var txid: String?
    var onchainFee: UInt64?
    var arkFee: UInt64?
    // swiftlint:disable:next discouraged_optional_boolean - receipts omit it unless a broadcast retry is pending
    var broadcastRetryPending: Bool?
    var chainStatus: ChainTransactionStatus?
    init(message: String, txid: String? = nil, onchainFee: UInt64? = nil) {
        self.message = message
        self.txid = txid
        self.onchainFee = onchainFee
    }
    struct BoardAmounts: Decodable, Equatable {
        let arkReceives: UInt64
        let totalLeaving: UInt64
    }
    func boardAmounts(quote: OnchainQuote) -> BoardAmounts {
        .init(arkReceives: quote.net, totalLeaving: quote.amount + (onchainFee ?? quote.estimated(quote.chainFee)))
    }
    /// Move exit forward's receipt when bark progressed every exit without an error.
    static let progressedMessage = "Exits progressed. Transactions may have been broadcast or fee-bumped."
    static let broadcastFailedMessage = "Boarding submitted but unconfirmed. Noah will keep trying the broadcast. Do not board these funds again."
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
            arkFee = try values.decodeIfPresent(UInt64.self, forKey: .arkFee)
            broadcastRetryPending = try values.decodeIfPresent(Bool.self, forKey: .broadcastRetryPending)
            chainStatus = try values.decodeIfPresent(ChainTransactionStatus.self, forKey: .chainStatus)
        }
    }
}

/// Created only after all input checks succeed and the estimator itself fails.
struct ExitEstimateUnavailable: Error {
    var review: OnchainQuote

    static func allowsFallback(_ error: any Error) -> Bool {
        // FFI 0.7.0 types exit failures: only the chain source's are retryable, and fee-rate overflow is
        // InvalidFeeRate (it was Other, as transport failures still are). Never-exitable VTXOs never fall back.
        if let error = error as? BarkError {
            return [.other, .invalidFeeRate, .exitTransactionRetrievalFailure, .exitTipRetrievalFailure,
                    .exitBlockRetrievalFailure, .exitAncestorRetrievalFailure].contains(error.code)
        }
        if let error = error as? OnchainFailure { return error == .unavailable }
        if let error = error as? URLError {
            return [.timedOut, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost,
                    .dnsLookupFailed, .notConnectedToInternet, .badServerResponse,
                    .secureConnectionFailed, .resourceUnavailable].contains(error.code)
        }
        return false
    }

    func preserving(_ original: OnchainQuote, now: ContinuousClock.Instant = .now) throws -> Self {
        var scope = original
        scope.fee = nil; scope.chainFee = nil; scope.feeRate = nil
        guard ExitRules.current(elapsed: original.elapsed(at: now)), review.feesUnknown,
              scope.matches(review) else { throw OnchainFailure.changed }
        var fallback = review
        fallback.id = original.id; fallback.createdAt = original.createdAt; fallback.reviewedAt = original.reviewedAt
        fallback.unknownFeeConsent = nil
        return .init(review: fallback)
    }
}

/// bark refused to exit a VTXO, and retrying can't change that (FFI 0.7.0's never-exitable codes). bark
/// names the VTXO when it can.
struct ExitRefusal: LocalizedError, Equatable, Sendable {
    var code: BarkErrorCode
    var vtxoID: String?

    static let codes: Set<BarkErrorCode> = [.exitNonStandardVtxo, .exitDustLimit, .exitVtxoAlreadySpent, .exitVtxoAlreadyExited,
                                            .exitVtxoSwept, .exitUnknownVtxo, .exitCyclicTransactions, .exitMissingAnchorOutput]

    init(code: BarkErrorCode, vtxoID: String?) { self.code = code; self.vtxoID = vtxoID }
    init?(_ error: any Error) {
        guard let error = error as? BarkError, Self.codes.contains(error.code) else { return nil }
        self.init(code: error.code, vtxoID: error.vtxoId)
    }

    /// Non-standard, dust included: bark exits it once it's renewed into a standard VTXO.
    var renewFirst: Bool { code == .exitNonStandardVtxo || code == .exitDustLimit }

    var errorDescription: String? { message }
    var message: String {
        switch code {
        case .exitVtxoAlreadySpent: "Already spent"
        case .exitVtxoSwept: "Swept"
        default: "This VTXO can't be exited."
        }
    }

    /// Its row on Choose VTXOs: the label, and the line under it for one to renew first.
    var label: String { renewFirst ? "Renew first" : message }
    var detail: String? { renewFirst ? "Renew this VTXO in Settings → VTXOs before exiting it." : nil }

    /// The exit screens' words for FFI 0.7.0's typed exit errors; nil for any other error.
    static func message(_ error: any Error) -> String? {
        if let refusal = Self(error) { return refusal.errorDescription }
        return (error as? BarkError)?.code == .exitInsufficientConfirmedFunds ? "Needs on-chain funds" : nil
    }
}
