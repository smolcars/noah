package com.noahwallet.send

import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.Serializable

/** Persisted before check-only advancement; disappearance must never become a fresh pay. */
@Serializable data class SendCheckpoint(val paymentHash: String, val invoice: String, val amount: Long, val fee: Long,
    val movementID: UInt?, val hasFailedRevocation: Boolean) {
    constructor(send: LightningSend) : this(send.paymentHash, SendDestination.cleaned(send.invoice).lowercase(),
        send.amountSats.toLong(), send.feeSats.toLong(), send.movementId, send.hasFailedRevocation)
    fun matches(invoice: String, payment: SendPayment, quote: SendQuote): Boolean =
        amount > 0 && fee >= 0 && !hasFailedRevocation && amount <= Long.MAX_VALUE - fee &&
            quote.amount >= 0 && quote.fee >= 0 && quote.amount <= Long.MAX_VALUE - quote.fee &&
            this.invoice == SendDestination.cleaned(invoice).lowercase() && paymentHash == PaymentEncoding.invoice(invoice)?.hash &&
            amount == payment.amount && amount == quote.amount && fee <= quote.fee && amount + fee <= quote.total
    suspend fun advance(payment: SendPayment, quote: SendQuote, state: LightningSendStatus,
        check: suspend (String) -> LightningSendStatus): LightningSendStatus {
        if (!matches(payment.destination, payment, quote)) throw SendExistingLightning(SendLightningProgress(amount, fee))
        when (state) {
            is LightningSendStatus.InProgress -> requireSameSend(SendCheckpoint(state.send), payment, quote)
            is LightningSendStatus.Paid -> requireSend(state.paymentHash == paymentHash && state.preimage.isNotEmpty(), SendFailure.Reason.unresolved)
            LightningSendStatus.Unknown -> throw SendFailure(SendFailure.Reason.unresolved)
        }
        return check(paymentHash)
    }
    /** The send in progress must still be this checkpoint's: same invoice, amounts and movement. */
    private fun requireSameSend(current: SendCheckpoint, payment: SendPayment, quote: SendQuote) {
        if (!current.matches(payment.destination, payment, quote) || (movementID != null && current.movementID != movementID))
            throw SendExistingLightning(SendLightningProgress(current.amount, current.fee))
    }
    fun receipt(status: LightningSendStatus, movements: List<Movement>, payment: SendPayment, quote: SendQuote): SendReceipt {
        requireSend(status is LightningSendStatus.Paid && status.paymentHash == paymentHash && status.preimage.isNotEmpty(), SendFailure.Reason.unresolved)
        val bound = movements.filter { SendReconciliation.isLightning(it, paymentHash) }
        val resolution = SendReconciliation.lightning(bound, paymentHash, payment.amount, SendSnapshot(), SendReconciliation.fixesAmount(invoice))
        val receipt = (resolution as? SendResolution.Paid)?.receipt ?: throw SendFailure(SendFailure.Reason.unresolved)
        requireSend(receipt.isProven(payment, paymentHash) && receipt.fee != null && receipt.fee <= quote.fee && receipt.amount <= quote.total - receipt.fee,
            SendFailure.Reason.unresolved)
        return receipt
    }
}
object SendLightningSubmission {
    /** Every send waits for its outcome (wait = false is a later phase, see async-payments.md).
     *  60 s is today's server default, set explicitly; the server caps it (5 min by default). */
    val options = LightningSendOptions(wait = true, retryForSecs = 60u)
    suspend fun offer(pending: List<LightningSend>, pay: suspend () -> LightningSendStatus): LightningSendStatus {
        requireSend(pending.isEmpty(), SendFailure.Reason.lightningInProgress)
        return pay()
    }
    @Suppress("LongParameterList") // the submission's inputs and the two bark calls it may make; tests pass each
    suspend fun submit(payment: SendPayment, state: LightningSendStatus, movements: List<Movement>,
        checkpoint: SendCheckpoint?, quote: SendQuote, check: suspend (String) -> LightningSendStatus,
        pay: suspend () -> LightningSendStatus): LightningSendStatus {
        if (checkpoint != null) return checkpoint.advance(payment, quote, state, check)
        val hash = PaymentEncoding.invoice(payment.destination)?.hash ?: throw SendFailure(SendFailure.Reason.invalid)
        refuseRepayment(payment, state, movements.filter { SendReconciliation.isLightning(it, hash) }, hash)
        return pay()
    }
    /** An invoice in progress or already paid is never paid again. */
    private fun refuseRepayment(payment: SendPayment, state: LightningSendStatus, matching: List<Movement>, hash: String) {
        val fixed = SendReconciliation.fixesAmount(payment.destination)
        when (state) {
            is LightningSendStatus.InProgress -> throw SendExistingLightning(SendLightningProgress(state.send.amountSats.toLong(), state.send.feeSats.toLong()))
            is LightningSendStatus.Paid -> throw SendAlreadyPaid(paidReceipt(payment, matching, hash, fixed))
            LightningSendStatus.Unknown -> if (matching.isNotEmpty()) refuseIfPaid(payment, matching, hash, fixed)
        }
    }
    private fun paidReceipt(payment: SendPayment, matching: List<Movement>, hash: String, fixed: Boolean): SendReceipt {
        val resolution = SendReconciliation.lightning(matching, hash, payment.amount, SendSnapshot(matching.map { it.id }.toSet()), fixed)
        return (resolution as? SendResolution.AlreadyPaid)?.receipt ?: throw SendFailure(SendFailure.Reason.unresolved)
    }
    private fun refuseIfPaid(payment: SendPayment, matching: List<Movement>, hash: String, fixed: Boolean) {
        when (val result = SendReconciliation.lightning(matching, hash, payment.amount, SendSnapshot(matching.map { it.id }.toSet()), fixed)) {
            is SendResolution.AlreadyPaid -> throw SendAlreadyPaid(result.receipt)
            SendResolution.Failed -> Unit
            else -> throw SendFailure(SendFailure.Reason.unresolved)
        }
    }
}
