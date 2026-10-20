package com.noahwallet.send

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** The process owns submission. Sheet cancellation cannot abandon a submitted native call. */
class SendController(private val dependencies: Dependencies,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    enum class State { ready, submitting, uncertain, succeeded, alreadyPaid, failed, storageBlocked }
    class Dependencies(val read: () -> SendAttempt?, val write: (SendAttempt?) -> Unit,
        val quarantineUnreadable: () -> Unit = { error("Journal quarantine unavailable") },
        val auditOverride: (SendOverrideAudit) -> Unit = { error("Override audit storage unavailable") },
        val reservation: suspend (suspend (SendWallet) -> Unit) -> Unit)
    var state by mutableStateOf(State.ready); private set
    var attempt by mutableStateOf<SendAttempt?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var checking by mutableStateOf(false); private set
    private var generation = 0
    private var unboundJournal: SendAttempt? = null
    private var unreadableJournal = false
    val locked get() = state != State.ready
    val canOverride get() = !checking && (unboundJournal != null || unreadableJournal ||
        attempt != null && (state == State.uncertain || state == State.succeeded && attempt?.receipt?.broadcastRetryPending == true))

    fun invalidateForWalletChange() {
        generation++; checking = false; attempt = null; message = null; unboundJournal = null; unreadableJournal = false; state = State.storageBlocked
    }
    suspend fun reloadForWalletChange() {
        generation++; checking = false; attempt = null; state = State.storageBlocked; message = null; unboundJournal = null; unreadableJournal = false
        try {
            dependencies.reservation { wallet ->
                var saved = try { dependencies.read() } catch (failure: Exception) { unreadableJournal = true; throw failure }
                    ?: run { state = State.ready; return@reservation }
                if (saved.walletIdentity == null) {
                    unboundJournal = saved; state = State.uncertain
                    message = "This legacy payment has no wallet identity. Check your history and recipient before discarding it."
                    return@reservation
                }
                if (saved.walletIdentity != wallet.identity()) { dependencies.write(null); state = State.ready; return@reservation }
                if (saved.safetyVersion != journalVersion || saved.receipt?.isProven(saved.payment, saved.paymentHash) == false || saved.alreadyPaid?.isProven(saved.payment, saved.paymentHash, true) == false) {
                    saved = saved.copy(safetyVersion = journalVersion, receipt = null, alreadyPaid = null, failed = false,
                        possibleTxids = (saved.possibleTxids + listOfNotNull(saved.receipt?.txid, saved.alreadyPaid?.txid)).distinct(),
                        failureMessage = "This saved payment outcome could not be verified. It may have been sent. Check history before sending again.")
                    dependencies.write(saved)
                }
                attempt = saved
                state = when {
                    saved.alreadyPaid != null -> State.alreadyPaid
                    saved.receipt != null -> if (saved.receipt.status == SendReceipt.Status.unlocated) State.uncertain else State.succeeded
                    saved.failed -> State.failed
                    else -> State.uncertain
                }
                message = saved.failureMessage
            }
        } catch (_: Exception) { message = "Unable to load saved payment status. Check your history and recipient before forgetting this attempt."; state = State.storageBlocked }
    }
    /** Must be invoked on Main: take the lock before launching any coroutine. */
    fun submit(payment: SendPayment, quote: SendQuote) {
        if (locked) return
        state = State.submitting; message = null
        val current = generation
        scope.launch {
            try {
                dependencies.reservation { wallet ->
                    if (current != generation) return@reservation
                    try {
                        val identity = wallet.identity()
                        requireSend(identity == quote.walletIdentity, SendFailure.Reason.unresolved)
                        if (payment.method in listOf("lightning", "lnurl")) {
                            val marker = SendAttempt(walletIdentity = identity, inspectionPending = true, payment = payment, quote = quote)
                            dependencies.write(marker); attempt = marker
                        }
                        val snapshot = wallet.prepare(payment, quote)
                        val pending = SendAttempt(walletIdentity = identity, broadcastingTxid = quote.prepared?.txid, payment = payment,
                            snapshot = snapshot, paymentHash = PaymentEncoding.invoice(snapshot.resolvedInvoice ?: if (payment.method == "lightning") payment.destination else "")?.hash, quote = quote)
                        dependencies.write(pending); attempt = pending
                    } catch (failure: Exception) {
                        state = if (attempt == null) State.ready else State.uncertain
                        message = (failure as? SendFailure)?.message ?: if (attempt == null) "Unable to prepare payment. No payment was submitted." else "Unable to inspect this payment. It may already be in progress. Check status before sending again."
                        wallet.discard(quote, includingClaimed = true)
                        return@reservation
                    }
                    try {
                        finish(SendResolution.Paid(wallet.pay(payment, quote, attempt!!.snapshot)))
                    } catch (failure: SendAlreadyPaid) { finish(SendResolution.AlreadyPaid(failure.receipt))
                    } catch (failure: SendExistingLightning) { finish(SendResolution.InProgress(failure.progress))
                    } catch (failure: SendNotSubmitted) {
                        attempt = attempt?.copy(failureMessage = failure.reason.message); message = failure.reason.message; finish(SendResolution.Failed)
                    } catch (failure: Exception) {
                        if (failure is SendFailure && failure.reason == SendFailure.Reason.lightningInProgress) {
                            attempt = attempt?.copy(failureMessage = failure.message); message = failure.message; finish(SendResolution.Uncertain)
                        } else { state = State.uncertain; reconcile(wallet) }
                    } finally { wallet.discard(quote, includingClaimed = true) }
                }
            } catch (_: Exception) {
                if (current == generation) { state = if (attempt == null) State.ready else State.uncertain; message = "Unable to reserve the wallet. Check payment status before sending again." }
            }
        }
    }
    suspend fun checkStatus() {
        if (checking || attempt == null || (state != State.uncertain && attempt?.receipt?.txid == null)) return
        checking = true
        try { dependencies.reservation { wallet -> reconcile(wallet) } }
        catch (_: Exception) { message = "Unable to reserve the wallet to check payment status." }
        finally { checking = false }
    }
    private suspend fun reconcile(wallet: SendWallet) {
        val saved = attempt ?: return
        try { finish(wallet.reconcile(saved)) }
        catch (_: Exception) { finish(SendResolution.Uncertain); message = "Unable to check payment status. It may have been sent. Do not pay again." }
    }
    private fun finish(resolution: SendResolution) {
        var saved = attempt ?: return
        when (resolution) {
            SendResolution.Uncertain -> { saved = saved.copy(receipt = saved.receipt?.let { if (it.txid != null) it.copy(status = SendReceipt.Status.unlocated) else it }); state = State.uncertain }
            is SendResolution.Paid -> { saved = saved.copy(receipt = resolution.receipt); state = if (resolution.receipt.status == SendReceipt.Status.unlocated) State.uncertain else State.succeeded }
            is SendResolution.AlreadyPaid -> { saved = saved.copy(alreadyPaid = resolution.receipt); state = State.alreadyPaid }
            is SendResolution.InProgress -> { saved = saved.copy(existingLightning = resolution.progress); state = State.uncertain }
            is SendResolution.Candidates -> { saved = saved.copy(candidates = resolution.ids); state = State.uncertain }
            is SendResolution.PossibleTransactions -> { saved = saved.copy(possibleTxids = (saved.possibleTxids + resolution.ids).distinct()); state = State.uncertain }
            SendResolution.Failed -> { if (saved.receipt != null) return; saved = saved.copy(failed = true); state = State.failed }
        }
        attempt = saved
        try { dependencies.write(saved) } catch (_: Exception) {
            message = "Payment status could not be saved. Check status again after restarting Noah."
        }
    }
    fun acknowledge() {
        if (state !in listOf(State.succeeded, State.alreadyPaid, State.failed) || attempt?.receipt?.broadcastRetryPending == true) return
        clear()
    }
    val overrideWarning get() = SendOverrideAudit.warningText
    fun startNewPaymentAfterHistoryCheck(confirmed: Boolean) {
        if (!confirmed || !canOverride) return
        val evidence = attempt ?: unboundJournal
        try {
            if (unreadableJournal) dependencies.quarantineUnreadable()
            dependencies.auditOverride(SendOverrideAudit(txid = evidence?.receipt?.txid ?: evidence?.alreadyPaid?.txid ?: evidence?.broadcastingTxid,
                walletIdentity = evidence?.walletIdentity, ownershipUnverified = evidence?.walletIdentity == null,
                candidates = evidence?.candidates.orEmpty(), possibleTxids = evidence?.possibleTxids.orEmpty(),
                originalEvidence = evidence, warning = overrideWarning, status = state.name, statusMessage = message))
        } catch (_: Exception) { message = "Unable to save the override confirmation. Sending remains locked."; return }
        clear()
    }
    private fun clear() {
        if (checking) return
        try { dependencies.write(null); attempt = null; unboundJournal = null; unreadableJournal = false; state = State.ready; message = null }
        catch (_: Exception) { message = "Unable to save payment status." }
    }
    companion object { const val journalVersion = 1 }
}

/** A reservation supplies one open wallet for the entire journal/inspection/payment interval. */
interface SendWallet {
    suspend fun identity(): SendIdentity
    suspend fun quote(payment: SendPayment): SendQuote
    suspend fun prepare(payment: SendPayment, quote: SendQuote): SendSnapshot
    suspend fun pay(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot): SendReceipt
    suspend fun reconcile(attempt: SendAttempt): SendResolution
    suspend fun discard(quote: SendQuote, includingClaimed: Boolean = false)
    suspend fun retryDiscards() {}
    suspend fun discardAll()
}
