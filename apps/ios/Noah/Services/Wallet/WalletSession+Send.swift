import BarkBoltFFI
import Foundation

extension WalletSession {
    func quoteSend(_ payment: SendPayment) async throws -> SendQuote {
        let invoice = payment.method == "lnurl" ? try await SendRecipientService.invoice(payment, session: self) : nil
        return try await withOpenWallet { handles in
            var resolved = payment
            if let invoice { resolved.destination = invoice; resolved.method = "lightning" }
            var quote = try await self.quote(resolved, handles: handles)
            if quote.prepared != nil { await self.retainSendReview(payment, quote: quote, onchain: handles.onchain) }
            quote.resolvedInvoice = invoice
            return quote
        }
    }
    private func quote(_ payment: SendPayment, handles: Handles) async throws -> SendQuote {
        try SendMaxValidation.available(payment)
        guard payment.amount > 0 || payment.max else { throw SendFailure.invalid }
        guard payment.amount <= 2_100_000_000_000_000 else { throw SendFailure.invalid }
        let parsed = SendDestination.parse(payment.destination)
        guard parsed.destinationType == payment.method, parsed.error == nil else { throw SendFailure.invalid }
        try await SendOfferAmount.require(payment) { try await handles.wallet.parsePaymentRequest(input: $0) }
        if payment.method == "lightning" {
            guard let invoice = PaymentEncoding.invoice(payment.destination) else { throw SendFailure.invalid }
            guard invoice.expires > Date() else { throw SendFailure.expired }
            if let amount = invoice.sats, amount > 0, amount != Decimal(payment.amount) { throw SendFailure.invalid }
        }
        if payment.method == "ark" {
            guard try await handles.wallet.validateArkoorAddress(address: payment.destination) else {
                throw SendFailure.invalid
            }
            _ = try parseArkAddress(address: payment.destination)
        }
        if payment.max {
            guard payment.method == "onchain", payment.source != nil else { throw SendFailure.invalid }
            if payment.source == .offchain, try await handles.onchain.isMine(address: payment.destination) {
                throw SendFailure.ownAddress
            }
        }
        if payment.method == "lightning", let hash = PaymentEncoding.invoice(payment.destination)?.hash,
           case .inProgress(let send) = try await handles.wallet.lightningSendState(paymentHash: hash) {
            // Funds may already be held by the checkpoint. Review the bound, not fresh spendability.
            let (total, overflow) = payment.amount.addingReportingOverflow(send.feeSats)
            guard !overflow else { throw SendFailure.invalid }
            return SendQuote(amount: payment.amount, fee: send.feeSats, total: total,
                             walletIdentity: try await Self.sendIdentity(handles), checkpoint: .init(send))
        }
        let available =
            payment.method == "onchain" && payment.source == .onchain
            ? try await handles.onchain.balance().confirmedSats : try await handles.wallet.balance().spendableSats
        var quote: SendQuote
        if payment.method == "onchain", payment.source == .onchain {
            let rate = SendFlow.sdkFeeRate(satsPerKwu: try await handles.onchain.feeRates().regularSatPerKwu)
            let prepared = try await handles.onchain.prepareSend(
                address: payment.destination, amount: payment.max ? .drainAll : .amount(amountSats: payment.amount),
                feeRateSatPerVb: rate, confirmedOnly: true)
            quote = SendQuote(amount: prepared.amountSats, fee: prepared.feeSats,
                              total: prepared.amountSats + prepared.feeSats, feeRate: rate,
                              prepared: SendPreparedTransaction(prepared))
        } else {
            let estimate: FeeEstimate
            var vtxoIDs: [String]?
            switch payment.method {
            case "ark": estimate = try await handles.wallet.estimateArkoorPaymentFee(amountSats: payment.amount)
            case "lightning", "offer", "lnurl":
                estimate = try await handles.wallet.estimateLightningSendFee(amountSats: payment.amount)
            case "onchain":
                guard payment.source == .offchain else { throw SendFailure.invalid }
                if payment.max {
                    let ids = try await handles.wallet.spendableVtxos().map(\.id).sorted()
                    guard !ids.isEmpty else { throw SendFailure.insufficient }
                    vtxoIDs = ids
                    estimate = try await handles.wallet.estimateOffboardFee(address: payment.destination, vtxoIds: ids)
                } else {
                    estimate = try await handles.wallet.estimateSendOnchainFee(
                        address: payment.destination, amountSats: payment.amount)
                }
            default: throw SendFailure.invalid
            }
            quote = SendQuote(amount: estimate.netAmountSats, fee: estimate.feeSats, total: estimate.grossAmountSats, vtxoIDs: vtxoIDs)
        }
        do {
            guard quote.amount > 0, quote.total <= available else { throw SendFailure.insufficient }
            quote.walletIdentity = try await Self.sendIdentity(handles)
        } catch {
            if quote.prepared != nil {
                retainSendReview(payment, quote: quote, onchain: handles.onchain)
                await discardSend(quote)
            }
            throw error
        }
        return quote
    }
    func prepareSend(_ payment: SendPayment, quote: SendQuote) async throws -> SendSnapshot {
        let invoice = quote.resolvedInvoice
        return try await withOpenWallet { handles in
            guard payment.method != "lnurl" || invoice != nil else { throw SendFailure.unresolved }
            let resolved = invoice ?? (payment.method == "lightning" ? payment.destination : "")
            guard quote.walletIdentity == (try await Self.sendIdentity(handles)) else { throw SendFailure.unresolved }
            var checkpoint = quote.checkpoint
            var invoicePaid: Bool?
            if let hash = PaymentEncoding.invoice(resolved)?.hash {
                checkpoint = SendLightningCheckpoint.prepared(reviewed: quote,
                    inspected: try await handles.wallet.lightningSendState(paymentHash: hash))
                invoicePaid = try await handles.wallet.isInvoicePaid(paymentHash: hash)
            }
            if payment.method == "onchain", payment.source == .onchain {
                guard quote.prepared != nil else { throw SendFailure.feeChanged }
                try await self.claimSendReview(payment, quote: quote)
                return SendSnapshot(movementIDs: [], walletIdentity: quote.walletIdentity)
            } else if checkpoint == nil {
                var resolvedPayment = payment
                if let invoice { resolvedPayment.destination = invoice; resolvedPayment.method = "lightning" }
                var fresh = try await self.quote(resolvedPayment, handles: handles)
                fresh.resolvedInvoice = invoice
                guard fresh == quote else { throw SendFailure.feeChanged }
            }
            return SendSnapshot(movementIDs: Set(try await handles.wallet.history().map(\.id)),
                                bdkTxids: Set(try await handles.onchain.transactions().map(\.txid)), resolvedInvoice: invoice,
                                walletIdentity: try await Self.sendIdentity(handles), checkpoint: checkpoint, invoicePaid: invoicePaid)
        }
    }
    func submitSend(_ payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot, beforeSubmit: @escaping @Sendable () throws -> Void = {}) async throws -> SendReceipt {
        do { try SendMaxValidation.available(payment) }
        catch let error as SendFailure { throw SendNotSubmitted(reason: error) }
        @Sendable func checkSubmission() async throws {
            do { try await ForegroundExecution.checkActive() }
            catch { throw SendNotSubmitted(reason: .recurringStopped) }
            try beforeSubmit()
        }
        return try await withOpenWallet { handles in
            guard snapshot.walletIdentity == (try await Self.sendIdentity(handles)) else { throw SendFailure.unresolved }
            var receipt = SendReceipt(amount: quote.amount)
            var lightning: LightningSendStatus?
            switch payment.method {
            case "ark":
                try await checkSubmission()
                // Persist the SDK's success result and the payment's movement id, never infer them from history candidates.
                receipt.movementID = try await handles.wallet.sendArkoorPayment(arkAddress: payment.destination, amountSats: payment.amount)
                receipt.proof = .submitReturnedArkSuccess
            case "lightning", "lnurl":
                var resolved = payment
                if payment.method == "lnurl" {
                    guard let invoice = snapshot.resolvedInvoice else { throw SendFailure.invalid }
                    try SendRecipientService.validateInvoice(invoice, amount: payment.amount, metadata: nil)
                    resolved.destination = invoice
                    resolved.method = "lightning"
                }
                let invoice = resolved.destination
                let fixed = PaymentEncoding.invoice(invoice)?.sats.map { $0 > 0 } ?? false
                guard let hash = PaymentEncoding.invoice(invoice)?.hash else { throw SendFailure.invalid }
                lightning = try await SendLightningSubmission.submit(
                    payment: resolved, state: try await handles.wallet.lightningSendState(paymentHash: hash),
                    movements: try await handles.wallet.history(), checkpoint: snapshot.checkpoint, quote: quote,
                    check: { try await handles.wallet.checkLightningPayment(paymentHash: $0, wait: false) }) {
                        try await checkSubmission()
                        return try await handles.wallet.payLightningInvoice(
                            invoice: invoice, amountSats: fixed ? nil : payment.amount, options: SendLightningSubmission.options)
                    }
            case "offer":
                lightning = try await SendLightningSubmission.offer(pending: try await handles.wallet.pendingLightningSends()) {
                    try await checkSubmission()
                    return try await handles.wallet.payLightningOffer(
                        offer: payment.destination, amountSats: payment.amount, options: SendLightningSubmission.options)
                }
            case "onchain":
                if payment.source == .onchain {
                    guard let prepared = quote.prepared,
                          await self.consumeSendReview(payment, quote: quote) else {
                        throw SendNotSubmitted(reason: .feeChanged)
                    }
                    do {
                        try await checkSubmission()
                        let result = try await handles.onchain.broadcastPrepared(id: prepared.id)
                        receipt = try SendOnchainSubmission.receipt(result, quote: quote)
                    } catch {
                        if let reason = SendOnchainSubmission.notSubmitted(error) {
                            throw SendNotSubmitted(reason: reason)
                        }
                        throw error
                    }
                } else {
                    receipt.status = .submitted
                    if payment.max {
                        let current = try await handles.wallet.spendableVtxos().map(\.id)
                        do { try SendMaxValidation.offchain(currentIDs: current, reviewedIDs: quote.vtxoIDs) }
                        catch let error as SendFailure { throw SendNotSubmitted(reason: error) }
                        try await checkSubmission()
                        let result = try await handles.wallet.offboardVtxos(vtxoIds: quote.vtxoIDs!, bitcoinAddress: payment.destination)
                        receipt.txid = result.txid
                        receipt.movementID = result.movementId
                    } else {
                        try await checkSubmission()
                        let result = try await handles.wallet.sendOnchain(address: payment.destination, amountSats: payment.amount)
                        receipt.txid = result.txid
                        receipt.movementID = result.movementId
                    }
                }
            default: throw SendFailure.invalid
            }
            if let lightning {
                guard case .paid(let hash, let preimage) = lightning, !preimage.isEmpty else {
                    throw SendFailure.unresolved
                }
                let movements = try await handles.wallet.history().filter {
                    $0.subsystemName == "bark.lightning_send" && $0.subsystemKind == "send" && $0.paymentHash == hash
                }
                if let checkpoint = snapshot.checkpoint {
                    receipt = try checkpoint.receipt(status: lightning, movements: movements, payment: payment, quote: quote)
                } else {
                    switch SendReconciliation.lightningAttempt(movements, hash: hash, amount: payment.amount, snapshot: snapshot, startedAt: nil,
                                                               fixed: SendReconciliation.fixesAmount(snapshot.resolvedInvoice ?? payment.destination)) {
                    case .paid(let actual): receipt = actual
                    case .alreadyPaid(let actual): throw SendAlreadyPaid(receipt: actual)
                    default: throw SendFailure.unresolved
                    }
                }
            }
            if receipt.txid != nil && receipt.proof == nil { receipt.proof = .submitReturnedTxid }
            if payment.method == "onchain", payment.source == .offchain {
                if payment.max {
                    // offboardVtxos binds reviewed coins, but Bark recomputes the fee/output at submit.
                    receipt.amount = 0
                    receipt.amountIsPending = true
                }
                if let movements = try? await handles.wallet.history() {
                    receipt = SendReconciliation.offboardReceipt(receipt, payment: payment, movements: movements)
                }
            }
            if payment.source == .onchain, let txid = receipt.txid,
               let transactions = try? await handles.onchain.transactions(),
               let tx = transactions.first(where: { $0.txid == txid }) {
                receipt.fee = tx.onchainFeeSats
            }
            // Metadata failure is never a payment failure and must never enable resubmission.
            if let patch = payment.repeatMetadata, let id = SendReconciliation.metadataMovementID(receipt, snapshot: snapshot) {
                do { try await handles.wallet.updateHistoryMetadata(movementId: id, patchJson: patch) } catch {
                    AppLog("send").w("Payment succeeded; repeat metadata could not be saved")
                }
            }
            return receipt
        }
    }
    func reconcileSend(_ attempt: SendPaymentController.Attempt) async throws -> SendPaymentController.Resolution {
        let flight = syncFlight
        return try await withOpenWallet { handles in
            guard attempt.walletIdentity == (try await Self.sendIdentity(handles)) else { throw SendFailure.unresolved }
            guard attempt.inspectionPending != true else { return .uncertain }
            if let checkpoint = attempt.snapshot.checkpoint {
                guard let quote = attempt.quote else { throw SendFailure.unresolved }
                var payment = attempt.payment
                payment.destination = attempt.snapshot.resolvedInvoice ?? payment.destination
                payment.method = "lightning"
                let status = try await checkpoint.advance(
                    invoice: payment.destination, payment: payment, quote: quote,
                    state: try await handles.wallet.lightningSendState(paymentHash: checkpoint.paymentHash),
                    check: { try await handles.wallet.checkLightningPayment(paymentHash: $0, wait: false) })
                return .paid(try checkpoint.receipt(status: status, movements: try await handles.wallet.history(), payment: payment, quote: quote))
            }
            if attempt.payment.method == "onchain", attempt.payment.source == .onchain || attempt.receipt?.txid != nil {
                let resolution: SendPaymentController.Resolution
                if attempt.payment.source == .onchain {
                    // Preserve BDK reorg and confirmed competing-spend checks.
                    try await flight.serialized { _ = try await handles.onchain.sync() }
                    resolution = try SendReconciliation.onchain(attempt, transactions: try await handles.onchain.transactions())
                } else if let receipt = attempt.receipt {
                    resolution = .paid(SendReconciliation.offboardReceipt(receipt, payment: attempt.payment,
                                                                         movements: try await handles.wallet.history()))
                } else { return .uncertain }
                return await SendReconciliation.chainEvidence(resolution, source: attempt.payment.source) { txid in
                    let status = attempt.payment.source == .onchain
                        ? try await handles.onchain.transactionStatus(txid: txid)
                        : try await handles.wallet.transactionStatus(txid: txid)
                    return .init(status)
                }
            }
            if ["lightning", "lnurl"].contains(attempt.payment.method), let hash = attempt.paymentHash {
                // Status checks must not drive an inherited checkpoint with an unreviewed amount.
                let state = try await handles.wallet.lightningSendState(paymentHash: hash)
                if case .inProgress(let send) = state {
                    return .inProgress(.init(amount: send.amountSats, fee: send.feeSats))
                }
                if let existing = attempt.existingLightning, existing.amount != attempt.payment.amount {
                    return .inProgress(existing)
                }
                let movements = try await handles.wallet.history()
                var tracked = attempt
                if attempt.existingLightning != nil {
                    tracked.snapshot.movementIDs.formUnion(movements.map(\.id))
                }
                return SendReconciliation.ark(tracked, movements: movements)
            }
            try await flight.serialized { try await handles.wallet.sync() }
            let resolution = SendReconciliation.ark(attempt, movements: try await handles.wallet.history())
            if case .paid(let receipt) = resolution, let patch = attempt.payment.repeatMetadata,
               let id = SendReconciliation.metadataMovementID(receipt, snapshot: attempt.snapshot) {
                do { try await handles.wallet.updateHistoryMetadata(movementId: id, patchJson: patch) }
                catch { AppLog("send").w(#"Reconciled payment; repeat metadata could not be saved"#) }
            }
            return resolution
        }
    }
    private static func sendIdentity(_ handles: Handles) async throws -> SendWalletIdentity {
        let network = try await handles.wallet.network()
        return .init(fingerprint: handles.wallet.fingerprint(), network: network == .bitcoin ? "bitcoin" : String(describing: network))
    }
    func pendingSendLightningHashes() async throws -> Set<String> {
        try await withOpenWallet { Set(try await $0.wallet.pendingLightningSends().map(\.paymentHash)) }
    }
    func sendArkServerKey() async throws -> String? {
        try await withOpenWallet { try await $0.wallet.arkInfo()?.serverPubkey }
    }
    func validateSendArkAddress(_ address: String) async throws -> Bool {
        try await withOpenWallet { try await $0.wallet.validateArkoorAddress(address: address) }
    }
    /// The amount a BOLT12 offer fixes, for Send's keypad; the quote checks it again.
    func sendOfferAmount(_ offer: String) async throws -> UInt64? {
        try await withOpenWallet { handles in try await SendOfferAmount.fixed(offer) { try await handles.wallet.parsePaymentRequest(input: $0) } }
    }
}

/// BOLT12 offers that fix their amount (ffi-phase-b.md row 12): Bark's parser supplies the amount, the quote enforces it.
enum SendOfferAmount {
    /// The amount an offer fixes, or nil when it fixes none. One Bark parses but can't pay (another network) is refused;
    /// one it can't parse at all (fiat-priced offers, `invalidPaymentRequest`) keeps the typed amount, as before.
    static func fixed(_ offer: String, parse: @Sendable (String) async throws -> PaymentRequest) async throws -> UInt64? {
        let request: PaymentRequest
        do { request = try await parse(offer) } catch let error as BarkError where error.code == .invalidPaymentRequest { return nil }
        guard request.defaultOption != nil else { throw SendFailure.invalid }
        if let amount = request.amountSats, amount > 2_100_000_000_000_000 { throw SendFailure.invalid }
        return request.amountSats
    }
    /// Bark pays a passed amount over an offer's own, so a fixed offer must be paid exactly its amount.
    static func require(_ payment: SendPayment, parse: @Sendable (String) async throws -> PaymentRequest) async throws {
        guard payment.method == "offer", let fixed = try await fixed(payment.destination, parse: parse) else { return }
        guard fixed == payment.amount else { throw SendFailure.invalid }
    }
}

/// MAX uses confirmed-only signed preparations for BDK; offboards pin their VTXOs.
enum SendMaxValidation {
    static func available(_ payment: SendPayment) throws {
        guard !payment.max || (payment.method == "onchain" && payment.source != nil) else { throw SendFailure.invalid }
    }
    static func offchain(currentIDs: [String], reviewedIDs: [String]?) throws {
        guard let reviewedIDs, !reviewedIDs.isEmpty, Set(currentIDs) == Set(reviewedIDs) else {
            throw SendFailure.fundsChanged
        }
    }
}

/// Only for the first broadcastPrepared call for an app-owned preparation (FFI v0.4.0).
enum SendOnchainSubmission {
    static func notSubmitted(_ error: Error) -> SendFailure? {
        guard let code = (error as? BarkError)?.code else { return nil }
        // core/onchain.rs (FFI 0.7.0) throws only before record_prepared, after which it returns an outcome:
        // inputs gone or reserved, a confirmed-only parent reorged out, or an unreachable chain source.
        // The app never retries this id.
        switch code {
        case .preparedTransactionNotFound, .preparedInputsUnavailable: return .fundsChanged
        default: return .constructionFailed
        }
    }
    static func receipt(_ result: BroadcastResult, quote: SendQuote) throws -> SendReceipt {
        guard let prepared = quote.prepared, result.txid == prepared.txid else { throw SendFailure.unresolved }
        switch result.broadcast {
        case .notAttempted: throw SendNotSubmitted(reason: .constructionFailed)
        case .uncertain: throw SendFailure.unresolved // Boards only (FFI 0.7.0); never treated as unsent.
        case .accepted, .failed:
            var receipt = SendReceipt(amount: quote.amount, txid: result.txid, status: .submitted,
                                      fee: quote.fee, proof: .preparedTxid)
            if case .failed = result.broadcast { receipt.broadcastRetryPending = true }
            return receipt
        }
    }
}

/// The pinned Bark resumes checkpoints without checking the newly supplied amount.
enum SendLightningSubmission {
    /// Every send waits for its outcome (wait: false is a later phase, see async-payments.md).
    /// 60 s is today's server default, set explicitly; the server caps it (5 min by default).
    static let options = LightningSendOptions(wait: true, retryForSecs: 60)
    static func offer(pending: [LightningSend], pay: @Sendable () async throws -> LightningSendStatus) async throws -> LightningSendStatus {
        // The SDK resolves offers internally, so their hash cannot be inspected before Bark resumes a checkpoint.
        guard pending.isEmpty else { throw SendFailure.lightningInProgress }
        return try await pay()
    }
    static func submit(
        payment: SendPayment, state: LightningSendStatus, movements: [Movement],
        checkpoint: SendLightningCheckpoint? = nil, quote: SendQuote? = nil,
        check: (@Sendable (String) async throws -> LightningSendStatus)? = nil,
        pay: @Sendable () async throws -> LightningSendStatus
    ) async throws -> LightningSendStatus {
        if let checkpoint {
            guard let quote, let check else { throw SendFailure.unresolved }
            return try await checkpoint.advance(invoice: payment.destination, payment: payment, quote: quote, state: state, check: check)
        }
        guard let hash = PaymentEncoding.invoice(payment.destination)?.hash else { throw SendFailure.invalid }
        let fixed = SendReconciliation.fixesAmount(payment.destination)
        let matching = movements.filter {
            $0.subsystemName == "bark.lightning_send" && $0.subsystemKind == "send" && $0.paymentHash == hash
        }
        switch state {
        case .inProgress(let send):
            throw SendExistingLightning(progress: .init(amount: send.amountSats, fee: send.feeSats))
        case .paid:
            let snapshot = SendSnapshot(movementIDs: Set(matching.map(\.id)))
            guard case .alreadyPaid(let receipt) = SendReconciliation.lightning(
                matching, hash: hash, amount: payment.amount, snapshot: snapshot, fixed: fixed)
            else { throw SendFailure.unresolved }
            throw SendAlreadyPaid(receipt: receipt)
        case .unknown:
            // Unknown or pending movement evidence cannot authorize resuming a payment.
            if !matching.isEmpty {
                let snapshot = SendSnapshot(movementIDs: Set(matching.map(\.id)))
                switch SendReconciliation.lightning(matching, hash: hash, amount: payment.amount, snapshot: snapshot, fixed: fixed) {
                case .alreadyPaid(let receipt): throw SendAlreadyPaid(receipt: receipt)
                case .failed: break
                default: throw SendFailure.unresolved
                }
            }
        }
        return try await pay()
    }
}
