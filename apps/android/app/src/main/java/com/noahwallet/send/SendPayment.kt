package com.noahwallet.send

import com.noahwallet.services.wallet.ReviewClock
import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class SendIdentity(val fingerprint: String, val network: String)
@Serializable data class SendPayment(val destination: String, val method: String, val amount: Long,
    val source: SendSource? = null, val max: Boolean = false, val comment: String = "", val repeatMetadata: String? = null) {
    companion object {
        fun metadata(destination: String, comment: String, amount: String, currency: String?): String = buildJsonObject {
            putJsonObject("noah") { putJsonObject("repeat_payment") {
                put("version", 1); put("destination", SendDestination.cleaned(destination).lowercase()); put("comment", comment)
                put("amount_mode", if (currency == null) "sats" else "fiat"); put("amount_value", amount)
                currency?.let { put("fiat_currency", it) }
            } }
        }.toString()
    }
}
@Serializable data class SendInput(val txid: String, val vout: UInt)
@Serializable data class SendPrepared(val id: String, val txid: String, val vsize: Long, val inputCount: Int,
    val createdAt: Long = System.currentTimeMillis(), val inputs: List<SendInput>? = null,
    /** When this review opened on [ReviewClock]. Never persisted, so null once read back from storage. */
    @kotlinx.serialization.Transient val reviewedAt: Long? = null) {
    fun elapsed(now: Long = ReviewClock.now(), wall: Long = System.currentTimeMillis()) = ReviewClock.elapsed(reviewedAt, createdAt, now, wall)
    fun isCurrent(now: Long = ReviewClock.now(), wall: Long = System.currentTimeMillis()) = elapsed(now, wall) in 0L until lifetimeMillis
    companion object { const val lifetimeMillis = 120_000L }
}
@Serializable data class SendQuote(val amount: Long, val fee: Long, val total: Long,
    val feeRate: Long? = null, val approximate: Boolean = false, val vtxoIDs: List<String>? = null,
    val walletIdentity: SendIdentity? = null, val checkpoint: SendCheckpoint? = null,
    val resolvedInvoice: String? = null, val prepared: SendPrepared? = null,
    val lightningFeeSchedule: com.noahwallet.recurring.RecurringFeeSchedule? = null)
@Serializable data class SendReceipt(val amount: Long, val txid: String? = null, val paymentHash: String? = null,
    val movementID: UInt? = null, val amountIsPending: Boolean = false, val status: Status = Status.submitted,
    val fee: Long? = null, val proof: Proof? = null, val broadcastRetryPending: Boolean = false,
    val confirmedConflictTxid: String? = null,
    val chainStatus: com.noahwallet.services.wallet.ChainStatus? = null, @kotlinx.serialization.Transient val statusError: String? = null) {
    companion object {
        const val broadcastPendingMessage = "Submitted, awaiting broadcast. Noah will keep retrying the broadcast. Do not send this payment again."
        const val submittedMessage = "Submitted. Refresh to check network status. Do not send this payment again."
        const val uncertainMessage = "Noah has not confirmed the final outcome. Do not pay again. Checking status will never submit another payment."
    }
    @Serializable enum class Status { submitted, confirmed, unlocated, conflicted }
    val isUnresolved get() = status in listOf(Status.unlocated, Status.conflicted)
    val conflictDescription get() = confirmedConflictTxid?.let {
        "This transaction cannot confirm on the current chain: a confirmed different transaction ($it) spends one of its inputs."
    }
    @Serializable enum class Proof { preparedTxid, submitReturnedTxid, actualMovement, submitReturnedArkSuccess }
    fun isProven(payment: SendPayment, knownHash: String? = null, alreadyPaid: Boolean = false): Boolean = when (proof) {
        Proof.submitReturnedArkSuccess -> payment.method == "ark" && amount > 0 && amount == payment.amount && txid == null && paymentHash == null && !amountIsPending
        Proof.preparedTxid -> payment.method == "onchain" && payment.source == SendSource.onchain && !txid.isNullOrEmpty() && amount > 0 && (payment.max || amount == payment.amount)
        Proof.submitReturnedTxid -> payment.method == "onchain" && !txid.isNullOrEmpty() && (amount > 0 || payment.max && payment.source == SendSource.offchain && amountIsPending) && (payment.max || amount == payment.amount)
        Proof.actualMovement -> payment.method in listOf("lightning", "lnurl", "offer") && txid == null && !paymentHash.isNullOrEmpty() && movementID != null && (fee != null && amount > 0 || alreadyPaid && fee == null && amount == 0L) && (alreadyPaid || amount == payment.amount) && (payment.method != "lightning" || paymentHash == (knownHash ?: PaymentEncoding.invoice(payment.destination)?.hash))
        null -> false
    }
}
/** [invoicePaid]: whether bark had a paid record for the invoice's hash before this attempt was submitted. A failed
 *  attempt never writes one, so it tells a retried invoice's own success from one paid before. Null without an
 *  invoice hash, and in journals from before it was recorded. */
@Serializable data class SendSnapshot(val movementIDs: Set<UInt> = emptySet(), val bdkTxids: Set<String>? = null,
    val resolvedInvoice: String? = null, val walletIdentity: SendIdentity? = null, val checkpoint: SendCheckpoint? = null,
    val invoicePaid: Boolean? = null)
@Serializable data class SendAttempt(val recurringAttemptID: String? = null, val walletIdentity: SendIdentity? = null, val inspectionPending: Boolean = false,
    val recurringOccurrenceKey: String? = null,
    val safetyVersion: Int = 2, val broadcastingTxid: String? = null, val payment: SendPayment,
    val snapshot: SendSnapshot = SendSnapshot(), val receipt: SendReceipt? = null, val alreadyPaid: SendReceipt? = null,
    val failed: Boolean = false, val startedAt: Long = System.currentTimeMillis(), val paymentHash: String? = null,
    val quote: SendQuote? = null, val failureMessage: String? = null, val existingLightning: SendLightningProgress? = null,
    val possibleTxids: List<String> = emptyList(), val candidates: List<UInt> = emptyList())
@Serializable data class SendLightningProgress(val amount: Long, val fee: Long) {
    val message get() = "A payment of $amount sats for this invoice is already in progress. Do not pay again."
}
class SendFailure(val reason: Reason) : Exception(reason.message) {
    enum class Reason(val message: String) {
        recurringStopped("Recurring payment stopped before submission. No payment was sent."),
        invalid("Enter a valid destination and a whole-satoshi amount greater than zero."),
        insufficient("The selected balance cannot cover this payment and its fee."),
        expired("This Lightning invoice has expired. Request a new invoice."),
        ownAddress("Your Ark balance cannot be swept to this wallet's own onchain address. Use an external Bitcoin address."),
        feeChanged("The amount or fee changed. Review the payment again."),
        unresolved("This payment may have been sent. Check its status before making another payment."),
        fundsChanged("The available funds changed. Review the payment again."),
        constructionFailed("The transaction could not be built. No payment was submitted. Review the amount and fee."),
        lightningInProgress("Another Lightning payment is still in progress. Try again once it completes.")
    }
}
internal fun requireSend(value: Boolean, reason: SendFailure.Reason = SendFailure.Reason.invalid) { if (!value) throw SendFailure(reason) }
class SendNotSubmitted(val reason: SendFailure.Reason) : Exception()
class SendAlreadyPaid(val receipt: SendReceipt) : Exception()
class SendExistingLightning(val progress: SendLightningProgress) : Exception()
sealed interface SendResolution {
    data object Uncertain : SendResolution
    data class Paid(val receipt: SendReceipt) : SendResolution
    data class AlreadyPaid(val receipt: SendReceipt) : SendResolution
    data class InProgress(val progress: SendLightningProgress) : SendResolution
    data class Candidates(val ids: List<UInt>) : SendResolution
    data class PossibleTransactions(val ids: List<String>) : SendResolution
    data object Failed : SendResolution
}
object SendOnchainSubmission {
    fun notSubmitted(error: Throwable): SendFailure.Reason? = when ((error as? BarkError)?.code) {
        // Audited v0.7.0 record_prepared ordering; only for the first broadcast of an app-owned id. A reorged parent is unavailable.
        BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND, BarkErrorCode.PREPARED_INPUTS_UNAVAILABLE -> SendFailure.Reason.fundsChanged
        // An unreachable chain source while re-checking confirmed-only inputs, before anything is recorded.
        BarkErrorCode.OTHER -> SendFailure.Reason.constructionFailed
        else -> null
    }
    fun receipt(result: BroadcastResult, quote: SendQuote): SendReceipt {
        requireSend(quote.prepared?.txid == result.txid, SendFailure.Reason.unresolved)
        if (result.broadcast is BroadcastOutcome.NotAttempted) throw SendNotSubmitted(SendFailure.Reason.constructionFailed)
        // Only boards report it today; a send that may have been recorded is never shown as sent.
        requireSend(result.broadcast !is BroadcastOutcome.Uncertain, SendFailure.Reason.unresolved)
        return SendReceipt(quote.amount, txid = result.txid, fee = quote.fee, proof = SendReceipt.Proof.preparedTxid,
            broadcastRetryPending = result.broadcast is BroadcastOutcome.Failed)
    }
}
