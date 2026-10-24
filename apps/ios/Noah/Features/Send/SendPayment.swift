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
    var checkpoint: SendLightningCheckpoint?
    var resolvedInvoice: String?
    var prepared: SendPreparedTransaction?
    var recipientIsEstimated: Bool { vtxoIDs?.isEmpty == false }
    var recipientDescription: String { recipientIsEstimated ? "Recipient receives about \(amount) sats" : "\(amount) sats" }
    static let recipientEstimateExplanation = "The exact amount depends on the network fee when sent. The total is exactly the reviewed coins."
    static let feeEstimateExplanation = "The fee and total are estimates based on two inputs and two outputs. The final fee and total may be higher if more coins are needed."
}
struct SendReceipt: Codable, Equatable, Sendable {
    static let broadcastPendingMessage = "Submitted, awaiting broadcast. Noah will keep retrying the broadcast. Do not send this payment again."
    static let submittedMessage = "Submitted. Refresh to check network status. Do not send this payment again."
    static let uncertainMessage = "Noah has not confirmed the final outcome. Do not pay again. Checking status will never submit another payment."

    var amount: UInt64
    var txid: String?
    var paymentHash: String?
    var movementID: UInt32?
    var amountIsEstimated: Bool?
    var amountIsPending: Bool?
    var status: Status?
    var fee: UInt64?
    var proof: Proof?
    var broadcastRetryPending: Bool?
    var confirmedConflictTxid: String?
    var chainStatus: ChainTransactionStatus?
    // Lookup failures are display state, never durable payment evidence.
    var chainStatusError: String? = nil
    private enum CodingKeys: String, CodingKey {
        case amount, txid, paymentHash, movementID, amountIsEstimated, amountIsPending, status, fee, proof
        case broadcastRetryPending, confirmedConflictTxid, chainStatus
    }
    enum Proof: String, Codable, Sendable { case preparedTxid, submitReturnedTxid, actualMovement, submitReturnedArkSuccess }
    enum Status: String, Codable, Sendable { case submitted, confirmed, unlocated, conflicted }
    var isUnresolved: Bool { status == .unlocated || status == .conflicted }
    var conflictDescription: String? {
        confirmedConflictTxid.map { "This transaction cannot confirm on the current chain: a confirmed different transaction (\($0)) spends one of its inputs." }
    }
    var amountDescription: String {
        amountIsPending == true ? "Recipient amount pending" : "\(amountIsEstimated == true ? "About " : "")\(amount) sats"
    }
    var isSubmitted: Bool { txid != nil && status != .confirmed }
    func isProven(for payment: SendPayment, knownHash: String? = nil, alreadyPaid: Bool = false) -> Bool {
        switch proof {
        case .submitReturnedArkSuccess:
            return payment.method == "ark" && amount > 0 && amount == payment.amount
                && txid == nil && paymentHash == nil && amountIsPending != true
        case .preparedTxid:
            return payment.method == "onchain" && payment.source == .onchain && txid?.isEmpty == false
                && amount > 0 && (payment.max || amount == payment.amount)
        case .submitReturnedTxid:
            return payment.method == "onchain" && txid?.isEmpty == false
                && (amount > 0 || (payment.max && payment.source == .offchain && amountIsPending == true))
                && (payment.max || amount == payment.amount)
        case .actualMovement:
            return ["lightning", "offer", "lnurl"].contains(payment.method)
                && txid == nil && paymentHash?.isEmpty == false && movementID != nil
                // An amountless invoice's already-paid receipt has no numbers it can prove (SendReconciliation.alreadyPaid).
                && (fee != nil && amount > 0 || alreadyPaid && fee == nil && amount == 0)
                && (alreadyPaid || amount == payment.amount)
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
    var checkpoint: SendLightningCheckpoint?
    /// Whether bark had a paid record for the invoice's hash before this attempt was submitted. A failed attempt never
    /// writes one, so it tells a retried invoice's own success from one paid before. Nil without an invoice hash, and
    /// in journals from before it was recorded.
    var invoicePaid: Bool?
}
enum SendFailure: LocalizedError {
    case invalid, insufficient, expired, ownAddress, feeChanged, unresolved, fundsChanged, constructionFailed, lightningInProgress, recurringStopped
    var errorDescription: String? {
        switch self {
        case .recurringStopped: "Recurring payment stopped before submission. No payment was sent."
        case .lightningInProgress: "Another Lightning payment is still in progress. Try again once it completes."
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
    enum State: Equatable, Sendable {
        case ready, submitting, uncertain
        case succeeded(SendReceipt)
        case alreadyPaid(SendReceipt)
        case failed, storageBlocked
    }
    struct Attempt: Codable, Sendable {
        var recurringAttemptID: UUID?
        var recurringOccurrenceKey: String?
        var walletIdentity: SendWalletIdentity?
        var inspectionPending: Bool?
        var safetyVersion: Int? = 3
        var broadcastingTxid: String?
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
        var discard: @Sendable (SendQuote) async -> Void = { _ in }
        var withReservation: @Sendable (@escaping @MainActor @Sendable () async -> Void) async throws -> Void = { await $0() }
        var auditOverride: (SendOverrideAudit) throws -> Void = { _ in }
        var withRecurringReservation: (@Sendable (@escaping @MainActor @Sendable () async -> Void) async throws -> Void)?
        var recurringEvidence: ((Attempt, Int, Int, State, String?) throws -> Void)? = nil
        var guardedPay: (@Sendable (SendPayment, SendQuote, SendSnapshot, @escaping @Sendable () throws -> Void) async throws -> SendReceipt)?
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
    var persistRecurringEvidence: ((Attempt, Int, Int, State, String?) throws -> Void)?
    private var recurringEvidenceDurable = false
    private var unboundJournal: Attempt?
    private(set) var generation = 0
    private var walletGeneration = 0
    var locked: Bool { state != .ready }

    init(dependencies: Dependencies) {
        self.dependencies = dependencies
        persistRecurringEvidence = dependencies.recurringEvidence
        reloadForWalletChange()
    }
    func reloadForWalletChange() {
        loadAttempt(nil)
    }
    private func loadAttempt(_ retained: Attempt?) {
        recurringEvidenceDurable = false
        generation += 1
        walletGeneration += 1
        checking = false
        attempt = retained
        state = .ready
        message = nil
        unboundJournal = nil
        do {
            if var saved = try retained ?? dependencies.read() {
                if let identity = dependencies.identity {
                    let current = try identity()
                    guard let owner = saved.walletIdentity else {
                        // No historic identity/epoch proves ownership. Never display or drive this journal.
                        attempt = nil
                        unboundJournal = saved
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
                // Retain the original read-only evidence until migration is durable.
                attempt = saved
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
                    try dependencies.write(saved)
                }
                attempt = saved
                let outcome = saved.alreadyPaid.map(State.alreadyPaid)
                    ?? saved.receipt.map { $0.isUnresolved ? .uncertain : .succeeded($0) }
                    ?? (saved.failed ? .failed : .uncertain)
                message = saved.failureMessage ?? saved.receipt?.chainStatusError
                if saved.recurringOccurrenceKey != nil {
                    guard let persistRecurringEvidence else { throw SendFailure.unresolved }
                    try persistRecurringEvidence(saved, generation, walletGeneration, outcome, message)
                    recurringEvidenceDurable = true
                }
                state = outcome
            }
        } catch {
            state = .storageBlocked
            message = "Unable to load saved payment status. Check your history and recipient before forgetting this attempt."
        }
    }
    func submit(_ payment: SendPayment, quote: SendQuote, recurringKey: String? = nil, beforeSubmit: (@Sendable () throws -> Void)? = nil) {
        guard state == .ready else { return }
        generation += 1
        state = .submitting
        recurringEvidenceDurable = false
        message = nil
        // This task belongs to the controller, not a sheet's cancellable .task.
        let generation = self.generation
        task = Task {
            let reserve = recurringKey == nil ? dependencies.withReservation : (dependencies.withRecurringReservation ?? dependencies.withReservation)
            do { try await reserve {
                guard generation == self.generation else { return }
                await self.performSubmit(payment, quote: quote, generation: generation, recurringKey: recurringKey, beforeSubmit: beforeSubmit)
            } }
            catch {
                if generation == self.generation {
                    await dependencies.discard(quote)
                    state = .ready
                    message = "Unable to reserve the wallet. Check payment status before sending again."
                }
            }
        }
    }
    /// Automation awaits the controller-owned task; cancelling a push cannot abandon a send.
    func submitAndWait(_ payment: SendPayment, quote: SendQuote, recurringKey: String,
                       beforeSubmit: @escaping @Sendable () throws -> Void) async {
        guard state == .ready else { return }
        submit(payment, quote: quote, recurringKey: recurringKey, beforeSubmit: beforeSubmit)
        let submission = task
        await submission?.value
    }
    func releaseRecurring(attemptID: UUID?, expectedGeneration: Int, expectedWalletGeneration: Int, retainReceipt: Bool) {
        if generation == expectedGeneration, walletGeneration == expectedWalletGeneration, let attemptID, attempt?.recurringAttemptID == attemptID, !retainReceipt { acknowledge() }
    }
    private func performSubmit(_ payment: SendPayment, quote: SendQuote, generation: Int,
                               recurringKey: String?, beforeSubmit: (@Sendable () throws -> Void)?) async {
        let recurringAttemptID = recurringKey.map { _ in UUID() }
        defer { Task { await dependencies.discard(quote) } }
        do {
            let identity = try dependencies.identity?()
            // Lightning inspection errors must survive restart without granting permission to pay.
            if ["lightning", "lnurl"].contains(payment.method) {
                let pending = Attempt(recurringAttemptID: recurringAttemptID, recurringOccurrenceKey: recurringKey, walletIdentity: identity, inspectionPending: true, payment: payment, snapshot: .init(movementIDs: []), quote: quote)
                try dependencies.write(pending)
                attempt = pending
            }
            let snapshot = try await dependencies.prepare(payment, quote)
            guard generation == self.generation else { return }
            let pending = Attempt(recurringAttemptID: recurringAttemptID, recurringOccurrenceKey: recurringKey, walletIdentity: identity, broadcastingTxid: quote.prepared?.txid, payment: payment, snapshot: snapshot,
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
            let receipt: SendReceipt
            if let beforeSubmit {
                guard let guardedPay = dependencies.guardedPay else { throw SendFailure.unresolved }
                guard attempt!.snapshot.checkpoint == nil else { throw SendFailure.lightningInProgress }
                receipt = try await guardedPay(payment, quote, attempt!.snapshot, beforeSubmit)
            } else { receipt = try await dependencies.pay(payment, quote, attempt!.snapshot) }
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
        guard !checking else { return }
        if state == .storageBlocked { loadAttempt(attempt); return }
        guard let attempt else { return }
        guard state == .uncertain || attempt.receipt?.txid != nil else { return }
        let generation = self.generation
        checking = true
        defer { if generation == self.generation { checking = false } }
        do { try await dependencies.withReservation {
            guard generation == self.generation else { return }
            await self.reconcileCurrentAttempt()
        } }
        catch { if generation == self.generation { message = "Couldn't check the latest status. Showing the last known status." } }
    }
    private func reconcileCurrentAttempt() async {
        guard let attempt else { return }
        let generation = self.generation
        do {
            let resolution = try await dependencies.reconcile(attempt)
            guard generation == self.generation else { return }
            message = nil
            finish(resolution)
        } catch {
            guard generation == self.generation else { return }
            // A failed refresh says nothing about the last proven outcome or missing history.
            let offline = (error as? URLError)?.code == .notConnectedToInternet
            let freshness = offline ? "Couldn't check the latest status — you're offline. Showing the last known status."
                : "Couldn't check the latest status. Showing the last known status."
            message = freshness + (attempt.receipt == nil || attempt.receipt?.broadcastRetryPending == true
                ? " This payment may still complete. Do not pay again." : "")
        }
    }
    private func finish(_ resolution: Resolution) {
        guard var attempt else { return }
        attempt.safetyVersion = 3
        var outcome = state
        switch resolution {
        case .uncertain:
            if attempt.receipt?.txid != nil { attempt.receipt?.status = .unlocated }
            outcome = .uncertain
        case .possibleTransactions(let txids):
            attempt.possibleTxids = Array(Set((attempt.possibleTxids ?? []) + txids)).sorted()
            outcome = .uncertain
        case .inProgress(let progress):
            attempt.existingLightning = progress
            outcome = .uncertain
        case .candidates(let candidates):
            guard attempt.receipt == nil else { return }
            attempt.candidates = candidates
            outcome = .uncertain
        case .paid(var receipt):
            message = receipt.chainStatusError
            receipt.chainStatusError = nil
            attempt.receipt = receipt
            outcome = receipt.isUnresolved ? .uncertain : .succeeded(receipt)
        case .alreadyPaid(let receipt):
            attempt.alreadyPaid = receipt
            outcome = .alreadyPaid(receipt)
        case .failed:
            guard attempt.receipt == nil else { return }
            attempt.failed = true
            outcome = .failed
        }
        self.attempt = attempt
        do { try dependencies.write(attempt) } catch {
            message = "Payment status could not be saved. Check status again after restarting Noah."
        }
        if attempt.recurringOccurrenceKey != nil {
            do {
                guard let persistRecurringEvidence else { throw SendFailure.unresolved }
                try persistRecurringEvidence(attempt, generation, walletGeneration, outcome, message)
                recurringEvidenceDurable = true
            } catch {
                recurringEvidenceDurable = false
                state = .storageBlocked
                message = "Payment status could not be saved. Check status again after restarting Noah."
                return
            }
        }
        state = outcome
    }
    var canOverride: Bool {
        if unboundJournal != nil { return !checking }
        guard !checking, attempt != nil else { return false }
        if state == .uncertain || state == .storageBlocked { return true }
        if case .succeeded(let receipt) = state { return receipt.broadcastRetryPending == true }
        return false
    }
    var overrideWarning: String {
        [attempt?.receipt?.conflictDescription, SendOverrideAudit.warningText].compactMap { $0 }.joined(separator: "\n\n")
    }
    func startNewPaymentAfterHistoryCheck(confirmed: Bool, confirmationText: String = SendOverrideAudit.confirmationText, warningText: String? = nil) {
        guard confirmed, canOverride else { return }
        let evidence = attempt ?? unboundJournal
        do {
            try dependencies.auditOverride(.init(txid: evidence?.receipt?.txid ?? evidence?.alreadyPaid?.txid ?? evidence?.broadcastingTxid,
                                                walletIdentity: evidence?.walletIdentity,
                                                ownershipUnverified: evidence?.walletIdentity == nil,
                                                candidates: evidence?.candidates, possibleTxids: evidence?.possibleTxids,
                                                warning: warningText ?? overrideWarning, confirmation: confirmationText,
                                                statusMessage: message))
        } catch {
            message = "Unable to save the override confirmation. Sending remains locked."
            return
        }
        clearAttempt()
    }
    func acknowledge(ifGeneration expected: Int) {
        guard generation == expected else { return }
        acknowledge()
    }
    func acknowledge() {
        switch state {
        case .succeeded(let receipt):
            guard receipt.broadcastRetryPending != true else { return }
        case .alreadyPaid, .failed: break
        default: return
        }
        clearAttempt()
    }
    private func clearAttempt() {
        guard !checking else { return }
        if let attempt, attempt.recurringOccurrenceKey != nil, !recurringEvidenceDurable {
            do {
                guard let persistRecurringEvidence else { throw SendFailure.unresolved }
                try persistRecurringEvidence(attempt, generation, walletGeneration, state, message)
                recurringEvidenceDurable = true
            } catch { message = "Unable to save payment status."; return }
        }
        do {
            try dependencies.write(nil)
            recurringEvidenceDurable = false
            attempt = nil
            unboundJournal = nil
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
            discard: { await session.discardSend($0, includingClaimed: true) },
            withReservation: { try await session.withSendReservation($0) },
            auditOverride: { try SendOverrideAudit.append($0, to: location.directory.appending(path: "noah-send-overrides.json")) },
            withRecurringReservation: { try await session.withSendReservation($0, exclusive: true) },
            recurringEvidence: { attempt, generation, walletGeneration, _, message in
                try RecurringJournal(file: location.directory.appending(path: "noah-recurring-journal.json"))
                    .record(attempt, generation: generation, walletGeneration: walletGeneration,
                            message: message, binding: RecurringStore.binding(location: location), now: RecurringPayment.now)
            },
            guardedPay: { try await session.submitSend($0, quote: $1, snapshot: $2, beforeSubmit: $3) })
    }
}
