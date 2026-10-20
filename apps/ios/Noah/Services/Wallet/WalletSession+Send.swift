import BarkBoltFFI
import Foundation

extension WalletSession {
    func quoteSend(_ payment: SendPayment) async throws -> SendQuote {
        try await withOpenWallet { handles in try await Self.quote(payment, handles: handles) }
    }
    private static func quote(_ payment: SendPayment, handles: Handles) async throws -> SendQuote {
        try SendMaxValidation.available(payment)
        guard payment.amount > 0 || payment.max else { throw SendFailure.invalid }
        guard payment.amount <= 2_100_000_000_000_000 else { throw SendFailure.invalid }
        let parsed = SendDestination.parse(payment.destination)
        guard parsed.destinationType == payment.method, parsed.error == nil else { throw SendFailure.invalid }
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
        let available =
            payment.method == "onchain" && payment.source == .onchain
            ? try await handles.onchain.balance().confirmedSats : try await handles.wallet.balance().spendableSats
        let quote: SendQuote
        if payment.method == "onchain", payment.source == .onchain {
            let rate = SendFlow.sdkFeeRate(satsPerKwu: try await handles.onchain.feeRates().regularSatPerKwu)
            let (fee, overflow) = rate.multipliedReportingOverflow(by: 208)  // RN's 2-input, 2-output estimate.
            guard !overflow, fee < available else { throw SendFailure.insufficient }
            let amount = payment.amount
            guard amount <= available - fee else { throw SendFailure.insufficient }
            quote = SendQuote(amount: amount, fee: fee, total: amount + fee, feeRate: rate, approximate: true)
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
        guard quote.amount > 0, quote.total <= available else { throw SendFailure.insufficient }
        return quote
    }
    func prepareSend(_ payment: SendPayment, quote: SendQuote) async throws -> SendSnapshot {
        try await withOpenWallet { handles in
            let fresh = try await Self.quote(payment, handles: handles)
            guard fresh == quote else { throw SendFailure.feeChanged }
            return SendSnapshot(movementIDs: Set(try await handles.wallet.history().map(\.id)),
                                bdkTxids: Set(try await handles.onchain.transactions().map(\.txid)))
        }
    }
    func submitSend(_ payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot) async throws -> SendReceipt {
        do { try SendMaxValidation.available(payment) }
        catch let error as SendFailure { throw SendNotSubmitted(reason: error) }
        return try await withOpenWallet { handles in
            var receipt = SendReceipt(amount: quote.amount)
            var lightning: LightningSendStatus?
            switch payment.method {
            case "ark":
                try await handles.wallet.sendArkoorPayment(arkAddress: payment.destination, amountSats: payment.amount)
            case "lightning":
                let fixed = PaymentEncoding.invoice(payment.destination)?.sats.map { $0 > 0 } ?? false
                lightning = try await handles.wallet.payLightningInvoice(
                    invoice: payment.destination, amountSats: fixed ? nil : payment.amount, wait: true)
            case "offer":
                lightning = try await handles.wallet.payLightningOffer(
                    offer: payment.destination, amountSats: payment.amount, wait: true)
            case "lnurl":
                if SendDestination.isLightningAddress(payment.destination) {
                    lightning = try await handles.wallet.payLightningAddress(
                        lightningAddress: payment.destination, amountSats: payment.amount, comment: payment.comment,
                        wait: true)
                } else {
                    lightning = try await handles.wallet.payLnurl(
                        lnurl: payment.destination, amountSats: payment.amount, comment: payment.comment, wait: true)
                }
            case "onchain":
                if payment.source == .onchain {
                    receipt.status = .submitted
                    do {
                        receipt.txid = try await handles.onchain.send(
                            address: payment.destination, amountSats: payment.amount, feeRateSatPerVb: quote.feeRate!)
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
                        receipt.txid = try await handles.wallet.offboardVtxos(
                            vtxoIds: quote.vtxoIDs!, bitcoinAddress: payment.destination).txid
                    } else {
                        receipt.txid = try await handles.wallet.sendOnchain(address: payment.destination, amountSats: payment.amount)
                    }
                }
            default: throw SendFailure.invalid
            }
            if let lightning {
                guard case .paid(let hash, let preimage) = lightning, !preimage.isEmpty else {
                    throw SendFailure.unresolved
                }
                receipt.paymentHash = hash
            }
            // Metadata failure is never a payment failure and must never enable resubmission.
            if let patch = payment.repeatMetadata, let history = try? await handles.wallet.history(),
                let movement = history.first(where: {
                    !snapshot.movementIDs.contains($0.id)
                        && SendReconciliation.matches($0, payment: payment, hash: receipt.paymentHash)
                })
            {
                receipt.movementID = movement.id
                do { try await handles.wallet.updateHistoryMetadata(movementId: movement.id, patchJson: patch) } catch {
                    AppLog("send").w("Payment succeeded; repeat metadata could not be saved")
                }
            }
            return receipt
        }
    }
    func reconcileSend(_ attempt: SendPaymentController.Attempt) async throws -> SendPaymentController.Resolution {
        try await withOpenWallet { handles in
            if attempt.payment.method == "onchain", attempt.payment.source == .onchain || attempt.receipt?.txid != nil {
                // Read even if sync fails: a persist error can leave the transaction only in BDK memory.
                _ = try? await handles.onchain.sync()
                return try SendReconciliation.onchain(attempt, transactions: try await handles.onchain.transactions())
            }
            try await handles.wallet.sync()
            let resolution = SendReconciliation.ark(attempt, movements: try await handles.wallet.history())
            if case .paid(let receipt) = resolution, let id = receipt.movementID, let patch = attempt.payment.repeatMetadata {
                do { try await handles.wallet.updateHistoryMetadata(movementId: id, patchJson: patch) }
                catch { AppLog("send").w("Reconciled payment; repeat metadata could not be saved") }
            }
            return resolution
        }
    }
    func sendArkServerKey() async throws -> String? {
        try await withOpenWallet { try await $0.wallet.arkInfo()?.serverPubkey }
    }
    func validateSendArkAddress(_ address: String) async throws -> Bool {
        try await withOpenWallet { try await $0.wallet.validateArkoorAddress(address: address) }
    }
}

/// BDK drain cannot bind coin selection to a reviewed balance (FFI handoff item 14).
enum SendMaxValidation {
    static func available(_ payment: SendPayment) throws {
        guard !(payment.max && payment.source == .onchain) else { throw SendFailure.onchainMaxUnavailable }
    }
    static func offchain(currentIDs: [String], reviewedIDs: [String]?) throws {
        guard let reviewedIDs, !reviewedIDs.isEmpty, Set(currentIDs) == Set(reviewedIDs) else {
            throw SendFailure.fundsChanged
        }
    }
}

/// Only use for an error thrown directly by OnchainWallet.send in bark-bolt-ffi 0.3.0.
enum SendOnchainSubmission {
    static func notSubmitted(_ error: Error) -> SendFailure? {
        // The Swift BarkError is a message-only struct, not a typed error enum:
        // https://github.com/smolcars/bark-bolt-ffi/blob/v0.3.0/src/error.rs
        // Exact outer contexts identify the pre-recording stages (never search nested messages).
        // Address/network and fee validation precede the call into Bark:
        // https://github.com/smolcars/bark-bolt-ffi/blob/v0.3.0/src/core/mod.rs
        // https://github.com/smolcars/bark-bolt-ffi/blob/v0.3.0/src/core/onchain.rs
        guard let message = (error as? BarkError)?.message else { return nil }
        if message.hasPrefix("invalid bitcoin address: ")
            || ["bitcoin", "testnet", "testnet4", "signet", "regtest"].contains(where: {
                message.hasPrefix("bitcoin address is not valid on \($0): ")
            }) { return .invalid }
        if message == "Invalid fee rate" { return .feeChanged }
        // prepare_tx's b.finish() (including coin-selection insufficient funds) is before
        // sign_psbt, record_broadcast_tx and broadcast. Persist/broadcast/unknown errors remain uncertain.
        // https://gitlab.com/ark-bitcoin/bark/-/blob/bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c/bark/src/onchain/bdk.rs
        if message.hasPrefix("Send failed: error building tx: ") { return .constructionFailed }
        return nil
    }
}
