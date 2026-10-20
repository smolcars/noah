package com.noahwallet.send

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Called only inside WalletSession's counted, cancellation-shielded Send reservation. */
internal class SdkSendWallet(private val wallet: Wallet, private val onchain: OnchainWallet,
    private val network: String, private val warn: (String) -> Unit) : SendWallet {
    private data class Review(val payment: SendPayment, val quote: SendQuote, var timeout: Job? = null,
        var claimed: Boolean = false, var broadcasting: Boolean = false)
    private val reviews = mutableMapOf<String, Review>()
    private val reviewLock = Any()
    private val discardGate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun recipientService() = SendRecipientService(network, warn, arkServerKey = { wallet.arkInfo()?.serverPubkey }, validateArk = { wallet.validateArkoorAddress(it) })
    suspend fun pendingHashes() = wallet.pendingLightningSends().map { it.paymentHash }.toSet()
    override suspend fun identity() = SendIdentity(wallet.fingerprint(), wallet.network().name)
    override suspend fun quote(payment: SendPayment): SendQuote {
        requireSend(payment.amount in 1..2_100_000_000_000_000L || payment.max && payment.amount >= 0)
        requireSend(!payment.max || payment.method == "onchain" && payment.source != null)
        val invoice = if (payment.method == "lnurl") recipientService().invoice(payment) else null
        val resolved = if (invoice != null) payment.copy(destination = invoice, method = "lightning") else payment
        val quote = quoteResolved(resolved).copy(resolvedInvoice = invoice)
        quote.prepared?.let { prepared ->
            val review = Review(payment, quote)
            synchronized(reviewLock) {
                reviews[prepared.id] = review
                review.timeout = scope.launch { delay(SendPrepared.lifetimeMillis); discard(quote) }
            }
        }
        return quote
    }
    private suspend fun quoteResolved(payment: SendPayment): SendQuote {
        val parsed = SendDestination.parse(payment.destination, network)
        requireSend(parsed.destinationType == payment.method && parsed.error == null)
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
                SendQuote(prepared.amountSats.toLong(), prepared.feeSats.toLong(), Math.addExact(prepared.amountSats.toLong(), prepared.feeSats.toLong()), rate.toLong(),
                    prepared = SendPrepared(prepared.id, prepared.txid, prepared.vsize.toLong(), prepared.inputs.size))
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
            preparedID?.let { onchain.discardPrepared(it) }
            throw failure
        }
    }
    override suspend fun prepare(payment: SendPayment, quote: SendQuote): SendSnapshot {
        requireSend(quote.walletIdentity == identity(), SendFailure.Reason.unresolved)
        val resolvedInvoice = quote.resolvedInvoice
        requireSend(payment.method != "lnurl" || resolvedInvoice != null, SendFailure.Reason.unresolved)
        val invoice = resolvedInvoice ?: if (payment.method == "lightning") payment.destination else ""
        var checkpoint = quote.checkpoint
        PaymentEncoding.invoice(invoice)?.hash?.let { hash ->
            val pending = wallet.pendingLightningSends().filter { it.paymentHash == hash }
            requireSend(pending.size <= 1, SendFailure.Reason.unresolved)
            checkpoint = checkpoint ?: pending.firstOrNull()?.let(::SendCheckpoint) ?: (wallet.lightningSendState(hash) as? LightningSendStatus.InProgress)?.send?.let(::SendCheckpoint)
        }
        if (payment.method == "onchain" && payment.source == SendSource.onchain) {
            synchronized(reviewLock) {
                val prepared = quote.prepared ?: throw SendFailure(SendFailure.Reason.feeChanged)
                val held = reviews[prepared.id]
                requireSend(prepared.isCurrent() && held != null && !held.claimed && held.payment == payment && held.quote == quote, SendFailure.Reason.feeChanged)
                held!!.claimed = true; held.timeout?.cancel(); held.timeout = null
            }
            return SendSnapshot(walletIdentity = quote.walletIdentity)
        }
        if (checkpoint == null) {
            val resolved = if (resolvedInvoice != null) payment.copy(destination = resolvedInvoice, method = "lightning") else payment
            val fresh = quoteResolved(resolved).copy(resolvedInvoice = resolvedInvoice)
            requireSend(fresh == quote, SendFailure.Reason.feeChanged)
        }
        return SendSnapshot(wallet.history().map { it.id }.toSet(), onchain.transactions().map { it.txid }.toSet(), resolvedInvoice, identity(), checkpoint)
    }
    override suspend fun pay(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot): SendReceipt {
        requireSend(snapshot.walletIdentity == identity(), SendFailure.Reason.unresolved)
        var receipt = SendReceipt(quote.amount)
        val lightning: LightningSendStatus? = when (payment.method) {
            "ark" -> { wallet.sendArkoorPayment(payment.destination, payment.amount.toULong()); receipt = receipt.copy(proof = SendReceipt.Proof.submitReturnedArkSuccess); null }
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
                    check = { wallet.checkLightningPayment(it, false) },
                    pay = { wallet.payLightningInvoice(invoice, if ((decoded.sats?.signum() ?: 0) > 0) null else payment.amount.toULong(), true) })
            }
            "offer" -> SendLightningSubmission.offer(wallet.pendingLightningSends()) { wallet.payLightningOffer(payment.destination, payment.amount.toULong(), true) }
            "onchain" -> {
                if (payment.source == SendSource.onchain) {
                    val prepared = quote.prepared ?: throw SendNotSubmitted(SendFailure.Reason.feeChanged)
                    synchronized(reviewLock) {
                        val held = reviews[prepared.id]
                        if (held == null || !held.claimed || held.broadcasting || held.payment != payment || held.quote != quote) throw SendNotSubmitted(SendFailure.Reason.feeChanged)
                        held.broadcasting = true
                    }
                    try { receipt = SendOnchainSubmission.receipt(onchain.broadcastPrepared(prepared.id), quote) }
                    catch (failure: Exception) { SendOnchainSubmission.notSubmitted(failure)?.let { throw SendNotSubmitted(it) }; throw failure }
                } else {
                    val txid = if (payment.max) {
                        val ids = wallet.spendableVtxos().map { it.id }.toSet()
                        if (quote.vtxoIDs.isNullOrEmpty() || ids != quote.vtxoIDs.toSet()) throw SendNotSubmitted(SendFailure.Reason.fundsChanged)
                        wallet.offboardVtxos(quote.vtxoIDs, payment.destination).txid
                    } else wallet.sendOnchain(payment.destination, payment.amount.toULong())
                    receipt = SendReceipt(if (payment.max) 0 else quote.amount, txid = txid, amountIsPending = payment.max, proof = SendReceipt.Proof.submitReturnedTxid)
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
            else when (val result = SendReconciliation.lightning(movements, hash, payment.amount, snapshot)) {
                is SendResolution.Paid -> result.receipt
                is SendResolution.AlreadyPaid -> throw SendAlreadyPaid(result.receipt)
                else -> throw SendFailure(SendFailure.Reason.unresolved)
            }
        }
        if (payment.repeatMetadata != null) {
            try {
                val movement = wallet.history().firstOrNull { it.id !in snapshot.movementIDs && SendReconciliation.matches(it, payment, receipt.paymentHash) }
                if (movement != null) { wallet.updateHistoryMetadata(movement.id, payment.repeatMetadata); receipt = receipt.copy(movementID = movement.id) }
            } catch (_: Exception) { warn("Payment succeeded; repeat metadata could not be saved") }
        }
        return receipt
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
            onchain.sync() // A failed sync must not promote cached evidence.
            val resolution = SendReconciliation.onchain(attempt, onchain.transactions())
            return if (resolution is SendResolution.Paid && attempt.payment.source == SendSource.offchain) SendResolution.Paid(SendReconciliation.offboardReceipt(resolution.receipt, attempt.payment, wallet.history())) else resolution
        }
        if (attempt.payment.method in listOf("lightning", "lnurl") && attempt.paymentHash != null) {
            val state = wallet.lightningSendState(attempt.paymentHash)
            if (state is LightningSendStatus.InProgress) return SendResolution.InProgress(SendLightningProgress(state.send.amountSats.toLong(), state.send.feeSats.toLong()))
            if (attempt.existingLightning != null && attempt.existingLightning.amount != attempt.payment.amount) return SendResolution.InProgress(attempt.existingLightning)
            val movements = wallet.history()
            return SendReconciliation.ark(if (attempt.existingLightning != null) attempt.copy(snapshot = attempt.snapshot.copy(movementIDs = attempt.snapshot.movementIDs + movements.map { it.id })) else attempt, movements)
        }
        wallet.sync()
        return SendReconciliation.ark(attempt, wallet.history())
    }
    override suspend fun discard(quote: SendQuote, includingClaimed: Boolean) = withContext(NonCancellable) { discardGate.withLock {
        val id = quote.prepared?.id ?: return@withLock
        val held = synchronized(reviewLock) {
            val held = reviews[id] ?: return@withLock
            if (held.claimed && !includingClaimed) return@withLock
            reviews.remove(id)!!
        }
        held.timeout?.cancel()
        try { onchain.discardPrepared(id) } catch (_: Exception) { warn("Prepared send handle could not be discarded") }
    }
    }
    override suspend fun discardAll() {
        synchronized(reviewLock) { reviews.values.map { it.quote } }.forEach { discard(it, true) }
        discardGate.withLock { } // Join an expiry that already removed its entry before native disposal.
    }
}
