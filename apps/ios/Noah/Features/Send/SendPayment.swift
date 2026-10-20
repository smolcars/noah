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
    var walletIdentity: SendWalletIdentity?
    var recipientIsEstimated: Bool { vtxoIDs?.isEmpty == false }
    var recipientDescription: String { recipientIsEstimated ? "Recipient receives about \(amount) sats" : "\(amount) sats" }
    static let recipientEstimateExplanation = "The exact amount depends on the network fee when sent. The total is exactly the reviewed coins."
    static let feeEstimateExplanation = "The fee and total are estimates based on two inputs and two outputs. The final fee and total may be higher if more coins are needed."
}
struct SendReceipt: Codable, Equatable, Sendable {
    var amount: UInt64
    var txid: String?
    var paymentHash: String?
    var movementID: UInt32?
    var amountIsEstimated: Bool?
    var amountIsPending: Bool?
    var status: Status?
    var fee: UInt64?
    var proof: Proof?
    enum Proof: String, Codable, Sendable { case submitReturnedTxid, actualMovement, submitReturnedArkSuccess }
    enum Status: String, Codable, Sendable { case submitted, confirmed, unlocated }
    var amountDescription: String {
        amountIsPending == true ? "Recipient amount pending" : "\(amountIsEstimated == true ? "About " : "")\(amount) sats"
    }
    var isSubmitted: Bool { txid != nil && status != .confirmed }
    func isProven(for payment: SendPayment, knownHash: String? = nil, alreadyPaid: Bool = false) -> Bool {
        switch proof {
        case .submitReturnedArkSuccess:
            return payment.method == "ark" && amount > 0 && amount == payment.amount
                && txid == nil && paymentHash == nil && amountIsPending != true
        case .submitReturnedTxid:
            return payment.method == "onchain" && txid?.isEmpty == false
                && (amount > 0 || (payment.max && payment.source == .offchain && amountIsPending == true))
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
struct SendWalletIdentity: Codable, Equatable, Sendable {
    var fingerprint: String
    var network: String
}

struct SendSnapshot: Codable, Sendable {
    var movementIDs: Set<UInt32>
    var bdkTxids: Set<String>?
    var resolvedInvoice: String?
    var walletIdentity: SendWalletIdentity?
}
enum SendFailure: LocalizedError {
    case invalid, insufficient, expired, ownAddress, feeChanged, unresolved, onchainMaxUnavailable, fundsChanged, constructionFailed, lightningInProgress
    var errorDescription: String? {
        switch self {
        case .lightningInProgress: "Another Lightning payment is still in progress. Try again once it completes."
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
        var walletIdentity: SendWalletIdentity?
        var inspectionPending: Bool?
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
        var identity: (() throws -> SendWalletIdentity)?
        var withReservation: @Sendable (@escaping @MainActor @Sendable () async -> Void) async throws -> Void = { await $0() }
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
    private var unboundJournal = false
    private var generation = 0
    var locked: Bool { state != .ready }

    init(dependencies: Dependencies) {
        self.dependencies = dependencies
        reloadForWalletChange()
    }
    func reloadForWalletChange() {
        generation += 1
        checking = false
        attempt = nil
        state = .ready
        message = nil
        unboundJournal = false
        do {
            attempt = try dependencies.read()
            if var saved = attempt {
                if let identity = dependencies.identity {
                    let current = try identity()
                    guard let owner = saved.walletIdentity else {
                        // No historic identity/epoch proves ownership. Never display or drive this journal.
                        attempt = nil
                        unboundJournal = true
                        state = .uncertain
                        message = "This legacy payment has no wallet identity. Check your history and recipient before discarding it."
                        return
                    }
                    guard owner == current else {
                        attempt = nil
                        try dependencies.write(nil)
                        return
                    }
                }
                // Older MAX receipts used the quote as the outcome; retain identity but await actual outputs.
                if saved.payment.max, saved.payment.source == .offchain,
                   saved.receipt?.proof == .submitReturnedTxid, saved.receipt?.amountIsPending == nil {
                    saved.receipt?.amount = 0
                    saved.receipt?.fee = nil
                    saved.receipt?.amountIsPending = true
                    try dependencies.write(saved)
                }
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
        let generation = self.generation
        task = Task {
            do { try await dependencies.withReservation {
                guard generation == self.generation else { return }
                await self.performSubmit(payment, quote: quote, generation: generation)
            } }
            catch {
                guard generation == self.generation else { return }
                state = .ready
                message = "Unable to reserve the wallet. No payment was submitted."
            }
        }
    }
    private func performSubmit(_ payment: SendPayment, quote: SendQuote, generation: Int) async {
        do {
            let identity = try dependencies.identity?()
            // Lightning inspection errors must survive restart without granting permission to pay.
            if ["lightning", "lnurl"].contains(payment.method) {
                let pending = Attempt(walletIdentity: identity, inspectionPending: true, payment: payment, snapshot: .init(movementIDs: []), quote: quote)
                try dependencies.write(pending)
                attempt = pending
            }
            let snapshot = try await dependencies.prepare(payment, quote)
            guard generation == self.generation else { return }
            let pending = Attempt(walletIdentity: identity, payment: payment, snapshot: snapshot,
                                  paymentHash: PaymentEncoding.invoice(snapshot.resolvedInvoice ?? (payment.method == "lightning" ? payment.destination : ""))?.hash, quote: quote)
            try dependencies.write(pending)
            attempt = pending
        } catch {
            guard generation == self.generation else { return }
            state = attempt == nil ? .ready : .uncertain
            message =
                (error as? SendFailure)?.errorDescription ?? (attempt == nil
                    ? "Unable to prepare payment. No payment was submitted."
                    : "Unable to inspect this payment. It may already be in progress. Check status before sending again.")
            return
        }
        do {
            let receipt = try await dependencies.pay(payment, quote, attempt!.snapshot)
            guard generation == self.generation else { return }
            finish(.paid(receipt))
        } catch let error as SendExistingLightning {
            guard generation == self.generation else { return }
            finish(.inProgress(error.progress))
        } catch let error as SendAlreadyPaid {
            guard generation == self.generation else { return }
            finish(.alreadyPaid(error.receipt))
        } catch SendFailure.lightningInProgress {
            guard generation == self.generation else { return }
            attempt?.failureMessage = SendFailure.lightningInProgress.errorDescription
            message = SendFailure.lightningInProgress.errorDescription
            finish(.uncertain)
        } catch let error as SendNotSubmitted {
            guard generation == self.generation else { return }
            attempt?.failureMessage = error.reason.errorDescription
            message = error.reason.errorDescription
            finish(.failed)
        } catch {
            guard generation == self.generation else { return }
            state = .uncertain
            checking = true
            await reconcileCurrentAttempt()
            checking = false
        }
    }
    func checkStatus() async {
        guard !checking, let attempt else { return }
        guard state == .uncertain || attempt.receipt?.txid != nil else { return }
        let generation = self.generation
        checking = true
        defer { if generation == self.generation { checking = false } }
        do { try await dependencies.withReservation {
            guard generation == self.generation else { return }
            await self.reconcileCurrentAttempt()
        } }
        catch { if generation == self.generation { message = "Unable to reserve the wallet to check payment status." } }
    }
    private func reconcileCurrentAttempt() async {
        guard let attempt else { return }
        let generation = self.generation
        do {
            let resolution = try await dependencies.reconcile(attempt)
            guard generation == self.generation else { return }
            finish(resolution)
        } catch {
            guard generation == self.generation else { return }
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
        if unboundJournal { return !checking }
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
            unboundJournal = false
            state = .ready
            message = nil
        } catch { message = "Unable to save payment status." }
    }
}

extension SendPaymentController.Dependencies {
    static func live(session: WalletSession = .shared, variant: AppVariant = .current, location: WalletDataLocation? = nil) -> Self {
        let location = location ?? WalletDataLocation(variant: variant)
        let file = location.directory.appending(path: "noah-send-attempt.json")
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
            },
            identity: {
                let properties = try WalletDatabase.properties(at: location.database)
                return .init(fingerprint: properties.fingerprint, network: properties.network)
            },
            withReservation: { try await session.withSendReservation($0) })
    }
}
