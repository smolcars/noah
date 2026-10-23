package com.noahwallet.send

import io.github.niteshbalusu11.bark.*
import com.noahwallet.services.wallet.ChainStatus

/** Called only inside WalletSession's counted, cancellation-shielded Send reservation. */
internal class SdkSendWallet(private val wallet: Wallet, private val onchain: OnchainWallet,
    private val network: String, private val warn: (String) -> Unit,
    private val synchronization: com.noahwallet.services.wallet.WalletSync = com.noahwallet.services.wallet.WalletSync()) : SendWallet {
    private val preparations = SendPreparations(onchain::discardPrepared, warn)
    override suspend fun retryDiscards() = preparations.retryDiscards()
    fun recipientService() = SendRecipientService(network, warn, arkServerKey = { wallet.arkInfo()?.serverPubkey }, validateArk = { wallet.validateArkoorAddress(it) })
    suspend fun pendingHashes() = wallet.pendingLightningSends().map { it.paymentHash }.toSet()
    suspend fun offerAmount(offer: String) = offerAmount(offer) { wallet.parsePaymentRequest(it) }
    /** The SDK estimates today's quotes start from. Prepares and reserves nothing; never used to pay. */
    suspend fun estimateFee(rail: SendRail, amount: Long, destination: String?): Long = when (rail) {
        SendRail.ark -> wallet.estimateArkoorPaymentFee(amount.toULong())
        SendRail.lightning -> wallet.estimateLightningSendFee(amount.toULong())
        SendRail.onchain -> wallet.estimateSendOnchainFee(destination ?: throw SendFailure(SendFailure.Reason.invalid), amount.toULong())
    }.feeSats.toLong()
    override suspend fun identity() = SendIdentity(wallet.fingerprint(), wallet.network().name)
    override suspend fun quote(payment: SendPayment): SendQuote {
        requireSend(payment.amount in 1..2_100_000_000_000_000L || payment.max && payment.amount >= 0)
        requireSend(!payment.max || payment.method == "onchain" && payment.source != null)
        val invoice = if (payment.method == "lnurl") recipientService().invoice(payment) else null
        val resolved = if (invoice != null) payment.copy(destination = invoice, method = "lightning") else payment
        val captured = if (payment.method in listOf("lightning", "lnurl", "offer")) try {
            wallet.arkInfo()?.feeSchedule?.lightningSend?.let { fees ->
                fun signed(value: ULong) = value.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
                com.noahwallet.recurring.RecurringFeeSchedule(signed(fees.minFeeSats), signed(fees.baseFeeSats),
                    fees.ppmExpiryTable.maxOfOrNull { it.ppm.toLong() } ?: 0)
            }
        } catch (_: Exception) { null } else null
        val quote = quoteResolved(resolved).copy(resolvedInvoice = invoice, lightningFeeSchedule = captured)
        quote.prepared?.let { preparations.bind(it.id, payment, quote) }
        return quote
    }
    private suspend fun quoteResolved(payment: SendPayment): SendQuote {
        val parsed = SendDestination.parse(payment.destination, network)
        requireSend(parsed.destinationType == payment.method && parsed.error == null)
        requireOfferAmount(payment) { wallet.parsePaymentRequest(it) }
        if (payment.method == "lightning") {
            val invoice = PaymentEncoding.invoice(payment.destination) ?: throw SendFailure(SendFailure.Reason.invalid)
            requireSend(invoice.expires > System.currentTimeMillis() / 1000, SendFailure.Reason.expired)
            requireSend(invoice.sats == null || invoice.sats.signum() == 0 || invoice.sats.compareTo(payment.amount.toBigDecimal()) == 0)
            val pending = wallet.pendingLightningSends().filter { it.paymentHash == invoice.hash }
            requireSend(pending.size <= 1, SendFailure.Reason.unresolved)
            val state = wallet.lightningSendState(invoice.hash)
            val send = pending.firstOrNull() ?: (state as? LightningSendStatus.InProgress)?.send
            if (send != null) {
                val fee = send.feeSats.toLong(); requireSend(fee >= 0)
                return SendQuote(payment.amount, fee, Math.addExact(payment.amount, fee), walletIdentity = identity(), checkpoint = SendCheckpoint(send))
            }
        }
        if (payment.method == "ark") { requireSend(wallet.validateArkoorAddress(payment.destination)); parseArkAddress(payment.destination) }
        if (payment.max && payment.source == SendSource.offchain) requireSend(!onchain.isMine(payment.destination), SendFailure.Reason.ownAddress)
        val available = if (payment.method == "onchain" && payment.source == SendSource.onchain) onchain.balance().confirmedSats else wallet.balance().spendableSats
        var preparedID: String? = null
        try {
            val result = if (payment.method == "onchain" && payment.source == SendSource.onchain) {
                val rate = SendFlow.sdkFeeRate(onchain.feeRates().regularSatPerKwu)
                val prepared = onchain.prepareSend(payment.destination, if (payment.max) OnchainSendAmount.DrainAll else OnchainSendAmount.Amount(payment.amount.toULong()), rate, true)
                preparedID = prepared.id
                preparations.retain(prepared.id)
                SendQuote(prepared.amountSats.toLong(), prepared.feeSats.toLong(), Math.addExact(prepared.amountSats.toLong(), prepared.feeSats.toLong()), rate.toLong(),
                    prepared = SendPrepared(prepared.id, prepared.txid, prepared.vsize.toLong(), prepared.inputs.size,
                        inputs = prepared.inputs.map { SendInput(it.txid, it.vout) }))
            } else {
                var ids: List<String>? = null
                val estimate = when (payment.method) {
                    "ark" -> wallet.estimateArkoorPaymentFee(payment.amount.toULong())
                    "lightning", "offer" -> wallet.estimateLightningSendFee(payment.amount.toULong())
                    "onchain" -> {
                        requireSend(payment.source == SendSource.offchain)
                        if (payment.max) {
                            ids = wallet.spendableVtxos().map { it.id }.sorted(); requireSend(ids.isNotEmpty(), SendFailure.Reason.insufficient)
                            wallet.estimateOffboardFee(payment.destination, ids)
                        } else wallet.estimateSendOnchainFee(payment.destination, payment.amount.toULong())
                    }
                    else -> throw SendFailure(SendFailure.Reason.invalid)
                }
                SendQuote(estimate.netAmountSats.toLong(), estimate.feeSats.toLong(), estimate.grossAmountSats.toLong(),
                    approximate = payment.method == "onchain", vtxoIDs = ids)
            }
            requireSend(result.amount > 0 && result.fee >= 0 && result.total >= result.amount && result.total.toULong() <= available, SendFailure.Reason.insufficient)
            return result.copy(walletIdentity = identity())
        } catch (failure: Exception) {
            preparedID?.let { preparations.discard(it, true) }
            throw failure
        }
    }
    override suspend fun prepare(payment: SendPayment, quote: SendQuote): SendSnapshot {
        requireSend(quote.walletIdentity == identity(), SendFailure.Reason.unresolved)
        val resolvedInvoice = quote.resolvedInvoice
        requireSend(payment.method != "lnurl" || resolvedInvoice != null, SendFailure.Reason.unresolved)
        val invoice = resolvedInvoice ?: if (payment.method == "lightning") payment.destination else ""
        var checkpoint = quote.checkpoint
        var invoicePaid: Boolean? = null
        PaymentEncoding.invoice(invoice)?.hash?.let { hash ->
            val pending = wallet.pendingLightningSends().filter { it.paymentHash == hash }
            requireSend(pending.size <= 1, SendFailure.Reason.unresolved)
            checkpoint = checkpoint ?: pending.firstOrNull()?.let(::SendCheckpoint) ?: (wallet.lightningSendState(hash) as? LightningSendStatus.InProgress)?.send?.let(::SendCheckpoint)
            invoicePaid = wallet.isInvoicePaid(hash)
        }
        if (payment.method == "onchain" && payment.source == SendSource.onchain) {
            preparations.claim(payment, quote)
            return SendSnapshot(walletIdentity = quote.walletIdentity)
        }
        if (checkpoint == null) {
            val resolved = if (resolvedInvoice != null) payment.copy(destination = resolvedInvoice, method = "lightning") else payment
            val fresh = quoteResolved(resolved).copy(resolvedInvoice = resolvedInvoice, lightningFeeSchedule = quote.lightningFeeSchedule)
            requireSend(fresh == quote, SendFailure.Reason.feeChanged)
        }
        return SendSnapshot(wallet.history().map { it.id }.toSet(), onchain.transactions().map { it.txid }.toSet(), resolvedInvoice, identity(), checkpoint, invoicePaid)
    }
    override suspend fun pay(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot) = payInternal(payment, quote, snapshot, null)
    override suspend fun payGuarded(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot, beforeSubmit: () -> Unit) = payInternal(payment, quote, snapshot, beforeSubmit)
    private suspend fun payInternal(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot, beforeSubmit: (() -> Unit)?): SendReceipt {
        requireSend(snapshot.walletIdentity == identity(), SendFailure.Reason.unresolved)
        var receipt = SendReceipt(quote.amount)
        val lightning: LightningSendStatus? = when (payment.method) {
            // Persist the SDK's success result and the payment's movement id, never infer them from history candidates.
            "ark" -> { beforeSubmit?.invoke(); receipt = receipt.copy(movementID = wallet.sendArkoorPayment(payment.destination, payment.amount.toULong()), proof = SendReceipt.Proof.submitReturnedArkSuccess); null }
            "lightning", "lnurl" -> {
                val invoice = if (payment.method == "lnurl") snapshot.resolvedInvoice ?: throw SendFailure(SendFailure.Reason.invalid) else payment.destination
                if (payment.method == "lnurl") SendRecipientService.validateInvoice(invoice, payment.amount, network)
                val decoded = PaymentEncoding.invoice(invoice) ?: throw SendFailure(SendFailure.Reason.invalid)
                val resolved = payment.copy(destination = invoice, method = "lightning")
                // Inspect the checkpoint list before any creating API, even if a status lookup is stale.
                val pending = wallet.pendingLightningSends().filter { it.paymentHash == decoded.hash }
                requireSend(pending.size <= 1, SendFailure.Reason.unresolved)
                val state = pending.firstOrNull()?.let { LightningSendStatus.InProgress(it) } ?: wallet.lightningSendState(decoded.hash)
                SendLightningSubmission.submit(resolved, state, wallet.history(), snapshot.checkpoint, quote,
                    check = { beforeSubmit?.invoke(); wallet.checkLightningPayment(it, false) },
                    pay = { beforeSubmit?.invoke(); wallet.payLightningInvoice(invoice, if ((decoded.sats?.signum() ?: 0) > 0) null else payment.amount.toULong(), SendLightningSubmission.options) })
            }
            "offer" -> SendLightningSubmission.offer(wallet.pendingLightningSends()) { beforeSubmit?.invoke(); wallet.payLightningOffer(payment.destination, payment.amount.toULong(), SendLightningSubmission.options) }
            "onchain" -> {
                if (payment.source == SendSource.onchain) {
                    val prepared = quote.prepared ?: throw SendNotSubmitted(SendFailure.Reason.feeChanged)
                    if (!preparations.consume(payment, quote)) throw SendNotSubmitted(SendFailure.Reason.feeChanged)
                    try { receipt = SendOnchainSubmission.receipt(onchain.broadcastPrepared(prepared.id), quote) }
                    catch (failure: Exception) { SendOnchainSubmission.notSubmitted(failure)?.let { throw SendNotSubmitted(it) }; throw failure }
                } else {
                    val result = if (payment.max) {
                        val ids = wallet.spendableVtxos().map { it.id }.toSet()
                        if (quote.vtxoIDs.isNullOrEmpty() || ids != quote.vtxoIDs.toSet()) throw SendNotSubmitted(SendFailure.Reason.fundsChanged)
                        wallet.offboardVtxos(quote.vtxoIDs, payment.destination)
                    } else wallet.sendOnchain(payment.destination, payment.amount.toULong())
                    receipt = SendReceipt(if (payment.max) 0 else quote.amount, txid = result.txid, movementID = result.movementId,
                        amountIsPending = payment.max, proof = SendReceipt.Proof.submitReturnedTxid)
                    runCatching { wallet.history() }.getOrNull()?.let { receipt = SendReconciliation.offboardReceipt(receipt, payment, it) }
                }
                null
            }
            else -> throw SendFailure(SendFailure.Reason.invalid)
        }
        if (lightning != null) {
            requireSend(lightning is LightningSendStatus.Paid && lightning.preimage.isNotEmpty(), SendFailure.Reason.unresolved)
            val hash = (lightning as LightningSendStatus.Paid).paymentHash
            val movements = wallet.history().filter { SendReconciliation.isLightning(it, hash) }
            receipt = if (snapshot.checkpoint != null) snapshot.checkpoint.receipt(lightning, movements, payment, quote)
            else when (val result = SendReconciliation.lightningAttempt(movements, hash, payment.amount, snapshot, null,
                SendReconciliation.fixesAmount(snapshot.resolvedInvoice ?: payment.destination))) {
                is SendResolution.Paid -> result.receipt
                is SendResolution.AlreadyPaid -> throw SendAlreadyPaid(result.receipt)
                else -> throw SendFailure(SendFailure.Reason.unresolved)
            }
        }
        // Metadata failure is never a payment failure and must never enable resubmission.
        if (payment.repeatMetadata != null) SendReconciliation.metadataMovementID(receipt, snapshot)?.let { id ->
            try { wallet.updateHistoryMetadata(id, payment.repeatMetadata) } catch (_: Exception) { warn("Payment succeeded; repeat metadata could not be saved") }
        }
        return receipt
    }
    override suspend fun refreshReceipt(payment: SendPayment, receipt: SendReceipt): SendReceipt {
        val txid = receipt.txid ?: return receipt
        return receipt.copy(chainStatus = ChainStatus.from(if (payment.source == SendSource.onchain) onchain.transactionStatus(txid) else wallet.transactionStatus(txid)), statusError = null)
    }
    override suspend fun reconcile(attempt: SendAttempt): SendResolution {
        requireSend(attempt.walletIdentity == identity(), SendFailure.Reason.unresolved)
        if (attempt.inspectionPending) return SendResolution.Uncertain
        val checkpoint = attempt.snapshot.checkpoint
        if (checkpoint != null) {
            val quote = attempt.quote ?: throw SendFailure(SendFailure.Reason.unresolved)
            val payment = attempt.payment.copy(destination = attempt.snapshot.resolvedInvoice ?: attempt.payment.destination, method = "lightning")
            val status = checkpoint.advance(payment, quote, wallet.lightningSendState(checkpoint.paymentHash)) { wallet.checkLightningPayment(it, false) }
            return SendResolution.Paid(checkpoint.receipt(status, wallet.history(), payment, quote))
        }
        if (attempt.payment.method == "onchain" && (attempt.payment.source == SendSource.onchain || attempt.receipt?.txid != null)) {
            return SendReconciliation.onchainStatus(attempt, sync = { synchronization.serialized { onchain.sync() } }, transactions = { onchain.transactions() },
                offboardHistory = { wallet.history() }, status = { txid ->
                    ChainStatus.from(if (attempt.payment.source == SendSource.onchain) onchain.transactionStatus(txid) else wallet.transactionStatus(txid))
                })
        }
        if (attempt.payment.method in listOf("lightning", "lnurl") && attempt.paymentHash != null) {
            val state = wallet.lightningSendState(attempt.paymentHash)
            if (state is LightningSendStatus.InProgress) return SendResolution.InProgress(SendLightningProgress(state.send.amountSats.toLong(), state.send.feeSats.toLong()))
            if (attempt.existingLightning != null && attempt.existingLightning.amount != attempt.payment.amount) return SendResolution.InProgress(attempt.existingLightning)
            val movements = wallet.history()
            return SendReconciliation.ark(if (attempt.existingLightning != null) attempt.copy(snapshot = attempt.snapshot.copy(movementIDs = attempt.snapshot.movementIDs + movements.map { it.id })) else attempt, movements)
        }
        synchronization.serialized { wallet.sync() }
        return SendReconciliation.ark(attempt, wallet.history())
    }
    override suspend fun discard(quote: SendQuote, includingClaimed: Boolean) {
        quote.prepared?.let { preparations.discard(it.id, includingClaimed) }
    }
    override suspend fun discardAll() = preparations.discardAll()
    companion object {
        /** The amount a BOLT12 offer fixes, or null when it fixes none. One bark parses but can't pay (another network) is
         *  refused; one it can't parse at all (fiat-priced offers, `invalidPaymentRequest`) keeps the typed amount, as before. */
        suspend fun offerAmount(offer: String, parse: suspend (String) -> PaymentRequest): Long? {
            val request = try { parse(offer) } catch (failure: BarkError) {
                if (failure.code == BarkErrorCode.INVALID_PAYMENT_REQUEST) return null else throw failure
            }
            requireSend(request.defaultOption != null)
            return request.amountSats?.let { requireSend(it <= 2_100_000_000_000_000uL); it.toLong() }
        }
        /** bark pays a passed amount over an offer's own, so a fixed offer must be paid exactly its amount. */
        suspend fun requireOfferAmount(payment: SendPayment, parse: suspend (String) -> PaymentRequest) {
            if (payment.method == "offer") offerAmount(payment.destination, parse)?.let { requireSend(it == payment.amount) }
        }
    }
}
