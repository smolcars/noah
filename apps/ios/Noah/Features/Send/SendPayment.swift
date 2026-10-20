import Foundation
import Observation

struct SendPayment: Codable, Equatable, Sendable {
    var destination: String
    var method: String
    var amount: UInt64
    var source: SendSource?
    var max = false
    var comment = ""
    var repeatMetadata: String?

    static func metadata(destination: String, comment: String, amount: String, fiatCurrency: String?) throws -> String {
        var details: [String: Any] = [
            "version": 1, "destination": SendDestination.cleaned(destination).lowercased(), "comment": comment,
            "amount_mode": fiatCurrency == nil ? "sats" : "fiat", "amount_value": amount,
        ]
        if let fiatCurrency { details["fiat_currency"] = fiatCurrency }
        return String(
            decoding: try JSONSerialization.data(withJSONObject: ["noah": ["repeat_payment": details]]), as: UTF8.self)
    }
}
struct SendQuote: Codable, Sendable, Equatable {
    var amount: UInt64
    var fee: UInt64
    var total: UInt64
    var feeRate: UInt64?
    var approximate = false
    var vtxoIDs: [String]?
    static let feeEstimateExplanation = "The fee and total are estimates based on two inputs and two outputs. The final fee and total may be higher if more coins are needed."
}
struct SendReceipt: Codable, Equatable, Sendable {
    var amount: UInt64
    var txid: String?
    var paymentHash: String?
    var movementID: UInt32?
    var amountIsEstimated: Bool?
    var status: Status?
    var fee: UInt64?
    var proof: Proof?
    enum Proof: String, Codable, Sendable { case submitReturnedTxid, actualMovement }
    enum Status: String, Codable, Sendable { case submitted, confirmed, unlocated }
    var isSubmitted: Bool { txid != nil && status != .confirmed }
    func isProven(for payment: SendPayment, knownHash: String? = nil, alreadyPaid: Bool = false) -> Bool {
        switch proof {
        case .submitReturnedTxid:
            return payment.method == "onchain" && txid?.isEmpty == false && amount > 0
                && (payment.max || amount == payment.amount)
        case .actualMovement:
            return ["lightning", "offer", "lnurl"].contains(payment.method)
                && txid == nil && paymentHash?.isEmpty == false && movementID != nil && fee != nil
                && amount > 0 && (alreadyPaid || amount == payment.amount)
                && (payment.method != "lightning" || paymentHash == (knownHash ?? PaymentEncoding.invoice(payment.destination)?.hash))
        case nil: return false
        }
    }
}
struct SendSnapshot: Codable, Sendable {
    var movementIDs: Set<UInt32>
    var bdkTxids: Set<String>?
}
enum SendFailure: LocalizedError {
    case invalid, insufficient, expired, ownAddress, feeChanged, unresolved, onchainMaxUnavailable, fundsChanged, constructionFailed
    var errorDescription: String? {
        switch self {
        case .onchainMaxUnavailable: "Sending your entire onchain balance isn’t available yet. Enter an amount instead."
        case .constructionFailed: "The transaction could not be built. No payment was submitted. Review the amount and fee."
        case .fundsChanged: "The available funds changed. Review the payment again."
        case .invalid: "Enter a valid destination and a whole-satoshi amount greater than zero."
        case .insufficient: "The selected balance cannot cover this payment and its fee."
        case .expired: "This Lightning invoice has expired. Request a new invoice."
        case .ownAddress:
            "Your Ark balance cannot be swept to this wallet's own onchain address. Use an external Bitcoin address."
        case .feeChanged: "The amount or fee changed. Review the payment again."
        case .unresolved: "This payment may have been sent. Check its status before making another payment."
        }
    }
}

/// Explicit proof that submission stopped before the SDK could record or broadcast a payment.
struct SendNotSubmitted: Error { let reason: SendFailure }
struct SendAlreadyPaid: Error { let receipt: SendReceipt }
struct SendExistingLightning: Error { let progress: SendLightningProgress }
struct SendLightningProgress: Codable, Equatable, Sendable {
    var amount: UInt64
    var fee: UInt64
    var message: String { "A payment of \(amount.formatted()) sats for this invoice is already in progress. Do not pay again." }
}

/// Stored before funds can move. Weak history matches never authorize another attempt.
@MainActor @Observable final class SendPaymentController {
    enum State: Equatable {
        case ready, submitting, uncertain
        case succeeded(SendReceipt)
        case alreadyPaid(SendReceipt)
        case failed, storageBlocked
    }
    struct Attempt: Codable, Sendable {
        var safetyVersion: Int? = 3
        var payment: SendPayment
        var snapshot: SendSnapshot
        var receipt: SendReceipt?
        var alreadyPaid: SendReceipt?
        var failed = false
        var startedAt: Date? = Date()
        var paymentHash: String?
        var quote: SendQuote?
        var candidates: [SendCandidate]?
        var failureMessage: String?
        var existingLightning: SendLightningProgress?
        var possibleTxids: [String]?
    }
    struct Dependencies {
        var prepare: @Sendable (SendPayment, SendQuote) async throws -> SendSnapshot
        var pay: @Sendable (SendPayment, SendQuote, SendSnapshot) async throws -> SendReceipt
        var reconcile: @Sendable (Attempt) async throws -> Resolution
        var read: () throws -> Attempt?
        var write: (Attempt?) throws -> Void
    }
    enum Resolution: Sendable, Equatable {
        case uncertain
        case paid(SendReceipt)
        case alreadyPaid(SendReceipt)
        case candidates([SendCandidate])
        case inProgress(SendLightningProgress)
        case possibleTransactions([String])
        case failed
    }
    static let shared = SendPaymentController(dependencies: .live())
    private let dependencies: Dependencies
    private(set) var state: State = .ready
    private(set) var attempt: Attempt?
    private(set) var message: String?
    private(set) var checking = false
    private var task: Task<Void, Never>?
    var locked: Bool { state != .ready }

    init(dependencies: Dependencies) {
        self.dependencies = dependencies
        do {
            attempt = try dependencies.read()
            if var saved = attempt {
                let old = saved.safetyVersion != 3
                let unsafeReceipt = saved.receipt.map { old || !$0.isProven(for: saved.payment, knownHash: saved.paymentHash) } ?? false
                let unsafeAlreadyPaid = saved.alreadyPaid.map { old || !$0.isProven(for: saved.payment, knownHash: saved.paymentHash, alreadyPaid: true) } ?? false
                if old || unsafeReceipt || unsafeAlreadyPaid {
                    // v1 and early v2 could fabricate receipts. Their txids are evidence, never identity.
                    let txids = [unsafeReceipt ? saved.receipt?.txid : nil,
                                 unsafeAlreadyPaid ? saved.alreadyPaid?.txid : nil].compactMap { $0 }
                    if !txids.isEmpty {
                        saved.possibleTxids = Array(Set((saved.possibleTxids ?? []) + txids)).sorted()
                    }
                    if unsafeReceipt { saved.receipt = nil }
                    if unsafeAlreadyPaid { saved.alreadyPaid = nil }
                    saved.failed = false
                    saved.safetyVersion = 3
                    saved.failureMessage = "This saved payment outcome could not be verified. It may have been sent. Check history before sending again."
                    attempt = saved
                    try dependencies.write(saved)
                }
                attempt = saved
                state = saved.alreadyPaid.map(State.alreadyPaid)
                    ?? saved.receipt.map { $0.status == .unlocated ? .uncertain : .succeeded($0) }
                    ?? (saved.failed ? .failed : .uncertain)
                message = saved.failureMessage
            }
        } catch { state = .storageBlocked }
    }
    func submit(_ payment: SendPayment, quote: SendQuote) {
        guard state == .ready else { return }
        state = .submitting
        message = nil
        // This task belongs to the controller, not a sheet's cancellable .task.
        task = Task {
            do {
                let snapshot = try await dependencies.prepare(payment, quote)
                let pending = Attempt(payment: payment, snapshot: snapshot,
                                      paymentHash: payment.method == "lightning" ? PaymentEncoding.invoice(payment.destination)?.hash : nil, quote: quote)
                try dependencies.write(pending)
                attempt = pending
            } catch {
                state = .ready
                message =
                    (error as? SendFailure)?.errorDescription ?? "Unable to prepare payment. No payment was submitted."
                return
            }
            do {
                let receipt = try await dependencies.pay(payment, quote, attempt!.snapshot)
                finish(.paid(receipt))
            } catch let error as SendExistingLightning {
                finish(.inProgress(error.progress))
            } catch let error as SendAlreadyPaid {
                finish(.alreadyPaid(error.receipt))
            } catch let error as SendNotSubmitted {
                attempt?.failureMessage = error.reason.errorDescription
                message = error.reason.errorDescription
                finish(.failed)
            } catch {
                state = .uncertain
                await checkStatus()
            }
        }
    }
    func checkStatus() async {
        guard !checking, let attempt else { return }
        guard state == .uncertain || attempt.receipt?.txid != nil else { return }
        checking = true
        defer { checking = false }
        do { finish(try await dependencies.reconcile(attempt)) } catch {
            finish(.uncertain)
            message = "Unable to check payment status. It may have been sent. Do not pay again."
        }
    }
    private func finish(_ resolution: Resolution) {
        guard var attempt else { return }
        attempt.safetyVersion = 3
        switch resolution {
        case .uncertain:
            if attempt.receipt?.txid != nil { attempt.receipt?.status = .unlocated }
            state = .uncertain
        case .possibleTransactions(let txids):
            attempt.possibleTxids = Array(Set((attempt.possibleTxids ?? []) + txids)).sorted()
            state = .uncertain
        case .inProgress(let progress):
            attempt.existingLightning = progress
            state = .uncertain
        case .candidates(let candidates):
            guard attempt.receipt == nil else { return }
            attempt.candidates = candidates
            state = .uncertain
        case .paid(let receipt):
            attempt.receipt = receipt
            state = receipt.status == .unlocated ? .uncertain : .succeeded(receipt)
        case .alreadyPaid(let receipt):
            attempt.alreadyPaid = receipt
            state = .alreadyPaid(receipt)
        case .failed:
            guard attempt.receipt == nil else { return }
            attempt.failed = true
            state = .failed
        }
        self.attempt = attempt
        do { try dependencies.write(attempt) } catch {
            message = "Payment status could not be saved. Check status again after restarting Noah."
        }
    }
    var canOverride: Bool {
        guard state == .uncertain, !checking, let attempt, attempt.receipt == nil else { return false }
        return true
    }
    func startNewPaymentAfterHistoryCheck(confirmed: Bool) {
        guard confirmed, canOverride else { return }
        clearAttempt()
    }
    func acknowledge() {
        switch state {
        case .succeeded, .alreadyPaid, .failed: break
        default: return
        }
        clearAttempt()
    }
    private func clearAttempt() {
        guard !checking else { return }
        do {
            try dependencies.write(nil)
            attempt = nil
            state = .ready
            message = nil
        } catch { message = "Unable to save payment status." }
    }
}

extension SendPaymentController.Dependencies {
    static func live(session: WalletSession = .shared, variant: AppVariant = .current) -> Self {
        let file = WalletDataLocation(variant: variant).directory.appending(path: "noah-send-attempt.json")
        return Self(
            prepare: { try await session.prepareSend($0, quote: $1) },
            pay: { try await session.submitSend($0, quote: $1, snapshot: $2) },
            reconcile: { try await session.reconcileSend($0) },
            read: {
                guard FileManager.default.fileExists(atPath: file.path) else { return nil }
                return try JSONDecoder().decode(SendPaymentController.Attempt.self, from: Data(contentsOf: file))
            },
            write: { attempt in
                if let attempt {
                    try JSONEncoder().encode(attempt).write(
                        to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                    var excluded = file
                    var values = URLResourceValues()
                    values.isExcludedFromBackup = true
                    try excluded.setResourceValues(values)
                } else if FileManager.default.fileExists(atPath: file.path) {
                    try FileManager.default.removeItem(at: file)
                }
            })
    }
}
