package com.noahwallet.send

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** The process owns submission. Sheet cancellation cannot abandon a submitted native call. */
class SendController(private val dependencies: Dependencies,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    enum class State { ready, submitting, uncertain, succeeded, alreadyPaid, failed, storageBlocked }
    internal var persistRecurringEvidence: ((SendAttempt, Int, Int, State, String?) -> Unit)? = dependencies.recurringEvidence
    private var recurringEvidenceDurable = false
    class Dependencies(val read: suspend () -> SendAttempt?, val write: (SendAttempt?) -> Unit,
        val quarantineUnreadable: () -> Unit = { error("""Journal quarantine unavailable""") },
        val auditOverride: (SendOverrideAudit) -> Unit = { error("""Override audit storage unavailable""") },
        val recurringReservation: (suspend (suspend (SendWallet) -> Unit) -> Unit)? = null,
        val recurringEvidence: ((SendAttempt, Int, Int, State, String?) -> Unit)? = null,
        val reservation: suspend (suspend (SendWallet) -> Unit) -> Unit)
    var state by mutableStateOf(State.ready); private set
    var attempt by mutableStateOf<SendAttempt?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var checking by mutableStateOf(false); private set
    @Volatile private var generation = 0
    @Volatile private var walletGeneration = 0
    private var journalIdentity: SendIdentity? = null
    private var unboundJournal: SendAttempt? = null
    private var unreadableJournal = false
    val locked get() = state != State.ready
    /** This wallet's saved payment isn't read yet (every settled storageBlocked carries a message): Send is neither free nor holding one. */
    val loading get() = state == State.storageBlocked && message == null
    val canOverride get() = !checking && (unboundJournal != null || unreadableJournal ||
        attempt != null && (state in listOf(State.uncertain, State.storageBlocked) || state == State.succeeded && attempt?.receipt?.broadcastRetryPending == true))

    fun invalidateForWalletChange() {
        recurringEvidenceDurable = false
        generation++; walletGeneration++; journalIdentity = null; checking = false; attempt = null; message = null; unboundJournal = null; unreadableJournal = false; state = State.storageBlocked
    }
    suspend fun reloadForWalletChange() {
        loadAttempt(null)
    }
    private suspend fun loadAttempt(retained: SendAttempt?) {
        recurringEvidenceDurable = false
        generation++; walletGeneration++; val current = generation; checking = true; attempt = retained; state = State.storageBlocked; message = null; unboundJournal = null; unreadableJournal = false
        var loadedAttempt = retained
        var loadedUnbound: SendAttempt? = null
        var loadedUnreadable = false
        var loadedIdentity: SendIdentity? = null
        var loadedState = State.storageBlocked
        var loadedMessage: String? = null
        try {
            dependencies.reservation { wallet ->
                if (current != generation) return@reservation
                val identity = wallet.identity()
                if (current != generation) return@reservation
                loadedIdentity = identity
                val loaded = retained ?: try { dependencies.read() } catch (failure: Exception) { loadedUnreadable = true; throw failure }
                if (current != generation) return@reservation
                var saved = loaded ?: run { loadedState = State.ready; return@reservation }
                if (saved.walletIdentity == null) {
                    loadedUnbound = saved; loadedState = State.uncertain
                    loadedMessage = "This legacy payment has no wallet identity. Check your history and recipient before discarding it."
                    return@reservation
                }
                if (saved.walletIdentity != identity) { dependencies.write(null); loadedAttempt = null; loadedState = State.ready; return@reservation }
                // Retain the original read-only evidence until migration is durable.
                loadedAttempt = saved
                if (saved.safetyVersion !in 1..journalVersion || saved.receipt?.isProven(saved.payment, saved.paymentHash) == false || saved.alreadyPaid?.isProven(saved.payment, saved.paymentHash, true) == false) {
                    saved = saved.copy(safetyVersion = journalVersion, receipt = null, alreadyPaid = null, failed = false,
                        possibleTxids = (saved.possibleTxids + listOfNotNull(saved.receipt?.txid, saved.alreadyPaid?.txid)).distinct(),
                        failureMessage = "This saved payment outcome could not be verified. It may have been sent. Check history before sending again.")
                    dependencies.write(saved)
                } else if (saved.safetyVersion != journalVersion) {
                    // v1 lacked input outpoints but already required receipt provenance.
                    saved = saved.copy(safetyVersion = journalVersion)
                    dependencies.write(saved)
                }
                loadedAttempt = saved
                loadedState = when {
                    saved.alreadyPaid != null -> State.alreadyPaid
                    saved.receipt != null -> if (saved.receipt.isUnresolved) State.uncertain else State.succeeded
                    saved.failed -> State.failed
                    else -> State.uncertain
                }
                loadedMessage = saved.failureMessage
                if (saved.recurringOccurrenceKey != null) {
                    checkNotNull(persistRecurringEvidence)(saved, current, walletGeneration, loadedState, loadedMessage)
                    recurringEvidenceDurable = true
                }
            }
        } catch (_: Exception) { loadedMessage = "Unable to load saved payment status. Check your history and recipient before forgetting this attempt."; loadedState = State.storageBlocked }
        finally {
            if (current == generation) {
                journalIdentity = loadedIdentity
                attempt = loadedAttempt; unboundJournal = loadedUnbound; unreadableJournal = loadedUnreadable
                state = loadedState; message = loadedMessage; checking = false
            }
        }
    }
    /** Must be invoked on Main: take the lock before launching any coroutine. */
    fun submit(payment: SendPayment, quote: SendQuote, recurringKey: String? = null, beforeSubmit: (() -> Unit)? = null): Deferred<Unit>? {
        if (locked) return null
        state = State.submitting; message = null
        val current = ++generation
        val recurringAttemptID = if (recurringKey != null) java.util.UUID.randomUUID().toString() else null
        recurringEvidenceDurable = false
        return scope.async {
            try {
                val reservation = if (recurringKey != null) dependencies.recurringReservation ?: dependencies.reservation else dependencies.reservation
                reservation reserve@{ wallet ->
                    if (current != generation) return@reserve
                    try {
                        val identity = wallet.identity()
                        if (current != generation) { wallet.discard(quote, includingClaimed = true); return@reserve }
                        requireSend(identity == quote.walletIdentity, SendFailure.Reason.unresolved)
                        beforeSubmit?.invoke()
                        if (payment.method in listOf("lightning", "lnurl")) {
                            val marker = SendAttempt(recurringAttemptID = recurringAttemptID, walletIdentity = identity, inspectionPending = true, recurringOccurrenceKey = recurringKey, payment = payment, quote = quote)
                            dependencies.write(marker); attempt = marker
                        }
                        val snapshot = wallet.prepare(payment, quote)
                        if (current != generation) { wallet.discard(quote, includingClaimed = true); return@reserve }
                        if (recurringKey != null && snapshot.checkpoint != null) throw SendFailure(SendFailure.Reason.lightningInProgress)
                        val pending = SendAttempt(recurringAttemptID = recurringAttemptID, walletIdentity = identity, recurringOccurrenceKey = recurringKey, broadcastingTxid = quote.prepared?.txid, payment = payment,
                            snapshot = snapshot, paymentHash = PaymentEncoding.invoice(snapshot.resolvedInvoice ?: if (payment.method == "lightning") payment.destination else "")?.hash, quote = quote)
                        dependencies.write(pending); attempt = pending
                    } catch (failure: Exception) {
                        if (current != generation) return@reserve
                        if (failure is SendNotSubmitted && recurringKey != null && attempt == null) {
                            val stopped = SendAttempt(recurringAttemptID = recurringAttemptID, walletIdentity = quote.walletIdentity, recurringOccurrenceKey = recurringKey,
                                payment = payment, quote = quote, failed = true, failureMessage = failure.reason.message)
                            attempt = stopped; message = failure.reason.message; finish(SendResolution.Failed)
                            return@reserve
                        }
                        state = if (attempt == null) State.ready else State.uncertain
                        message = (failure as? SendFailure)?.message ?: if (attempt == null) "Unable to prepare payment. No payment was submitted." else "Unable to inspect this payment. It may already be in progress. Check status before sending again."
                        wallet.discard(quote, includingClaimed = true)
                        return@reserve
                    }
                    try {
                        val receipt = if (beforeSubmit == null) wallet.pay(payment, quote, attempt!!.snapshot)
                            else wallet.payGuarded(payment, quote, attempt!!.snapshot, beforeSubmit)
                        if (current != generation) return@reserve
                        if (finish(SendResolution.Paid(receipt)) && receipt.txid != null) {
                            try {
                                val refreshed = wallet.refreshReceipt(payment, receipt)
                                if (current == generation) finish(SendResolution.Paid(refreshed))
                            }
                            catch (_: Exception) { if (current == generation) showRefreshFailure() }
                        }
                    } catch (failure: SendAlreadyPaid) { if (current == generation) finish(SendResolution.AlreadyPaid(failure.receipt))
                    } catch (failure: SendExistingLightning) { if (current == generation) finish(SendResolution.InProgress(failure.progress))
                    } catch (failure: SendNotSubmitted) {
                        if (current != generation) return@reserve
                        attempt = attempt?.copy(failureMessage = failure.reason.message); message = failure.reason.message; finish(SendResolution.Failed)
                    } catch (failure: Exception) {
                        if (current != generation) return@reserve
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
    internal suspend fun releaseRecurring(attemptID: String?, expectedGeneration: Int, expectedWalletGeneration: Int, retainReceipt: Boolean) {
        if (generation == expectedGeneration && walletGeneration == expectedWalletGeneration && attemptID != null && attempt?.recurringAttemptID == attemptID && !retainReceipt) clear()
    }
    suspend fun checkStatus() {
        if (checking) return
        if (state == State.storageBlocked) { loadAttempt(attempt); return }
        if (attempt == null || (state != State.uncertain && attempt?.receipt?.txid == null)) return
        val current = generation
        checking = true
        try { dependencies.reservation { wallet -> if (current == generation) reconcile(wallet) } }
        catch (_: Exception) { if (current == generation) showRefreshFailure() }
        finally { if (current == generation) checking = false }
    }
    private suspend fun reconcile(wallet: SendWallet) {
        val saved = attempt ?: return
        val current = generation
        try {
            val resolution = wallet.reconcile(saved)
            if (current != generation) return
            message = null
            finish(resolution)
        } catch (_: Exception) { if (current == generation) showRefreshFailure() }
    }
    private fun showRefreshFailure() {
        message = "Couldn't check the latest status. Showing the last known status." +
            if (attempt?.receipt == null || attempt?.receipt?.broadcastRetryPending == true) " This payment may still complete. Do not pay again." else ""
    }
    private fun finish(resolution: SendResolution): Boolean {
        var saved = attempt ?: return false
        var outcome = state
        when (resolution) {
            SendResolution.Uncertain -> { saved = saved.copy(receipt = saved.receipt?.let { if (it.txid != null) it.copy(status = SendReceipt.Status.unlocated) else it }); outcome = State.uncertain }
            is SendResolution.Paid -> { message = resolution.receipt.statusError; saved = saved.copy(receipt = resolution.receipt.copy(statusError = null)); outcome = if (resolution.receipt.isUnresolved) State.uncertain else State.succeeded }
            is SendResolution.AlreadyPaid -> { saved = saved.copy(alreadyPaid = resolution.receipt); outcome = State.alreadyPaid }
            is SendResolution.InProgress -> { saved = saved.copy(existingLightning = resolution.progress); outcome = State.uncertain }
            is SendResolution.Candidates -> { saved = saved.copy(candidates = resolution.ids); outcome = State.uncertain }
            is SendResolution.PossibleTransactions -> { saved = saved.copy(possibleTxids = (saved.possibleTxids + resolution.ids).distinct()); outcome = State.uncertain }
            SendResolution.Failed -> { if (saved.receipt != null) return false; saved = saved.copy(failed = true); outcome = State.failed }
        }
        attempt = saved
        val durable = try { dependencies.write(saved); true } catch (_: Exception) {
            message = "Payment status could not be saved. Check status again after restarting Noah."
            false
        }
        // Done may hide this receipt before the reservation returns to recurring settlement.
        if (saved.recurringOccurrenceKey != null) {
            try {
                val persist = persistRecurringEvidence ?: error("""Recurring evidence storage unavailable""")
                persist(saved, generation, walletGeneration, outcome, message)
                recurringEvidenceDurable = true
            } catch (_: Exception) {
                recurringEvidenceDurable = false
                message = "Payment status could not be saved. Check status again after restarting Noah."
                state = State.storageBlocked
                return false
            }
        }
        state = outcome
        return durable
    }
    suspend fun acknowledge(expected: SendAttempt? = attempt) {
        if (attempt !== expected) return
        if (state !in listOf(State.succeeded, State.alreadyPaid, State.failed) || attempt?.receipt?.broadcastRetryPending == true) return
        clear()
    }
    val overrideWarning get() = listOfNotNull(attempt?.receipt?.conflictDescription, SendOverrideAudit.warningText).joinToString("\n\n")
    suspend fun startNewPaymentAfterHistoryCheck(confirmed: Boolean, warning: String = overrideWarning, confirmation: String = SendOverrideAudit.confirmationText) {
        if (!confirmed || !canOverride) return
        val evidence = attempt ?: unboundJournal
        val quarantine = unreadableJournal
        val status = state.name
        val statusMessage = message
        clear {
            if (quarantine) dependencies.quarantineUnreadable()
            dependencies.auditOverride(SendOverrideAudit(txid = evidence?.receipt?.txid ?: evidence?.alreadyPaid?.txid ?: evidence?.broadcastingTxid,
                walletIdentity = evidence?.walletIdentity, ownershipUnverified = evidence?.walletIdentity == null,
                candidates = evidence?.candidates.orEmpty(), possibleTxids = evidence?.possibleTxids.orEmpty(),
                originalEvidence = evidence, warning = warning, confirmation = confirmation, status = status, statusMessage = statusMessage))
        }
    }
    private suspend fun clear( beforeRemove: (() -> Unit)? = null) {
        if (checking) return
        val current = generation
        val identity = journalIdentity ?: attempt?.walletIdentity
        checking = true
        var removed = false
        var audited = beforeRemove == null
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                dependencies.reservation { wallet ->
                    if (current != generation) return@reservation
                    val owner = wallet.identity()
                    if (current != generation || identity == null || owner != identity) return@reservation
                    // The counted reservation prevents replacement until audit and removal are durable.
                    beforeRemove?.invoke()
                    audited = true
                    if (attempt?.recurringOccurrenceKey != null && !recurringEvidenceDurable) {
                        val saved = attempt!!
                        val persist = persistRecurringEvidence ?: error("""Recurring evidence storage unavailable""")
                        persist(saved, generation, walletGeneration, state, message)
                        recurringEvidenceDurable = true
                    }
                    dependencies.write(null)
                    removed = true
                }
            }
            if (removed && current == generation) {
                recurringEvidenceDurable = false
                attempt = null; unboundJournal = null; unreadableJournal = false; state = State.ready; message = null
            }
        } catch (_: Exception) {
            if (current == generation) message = if (!audited) "Unable to save the override confirmation. Sending remains locked." else "Unable to save payment status."
        }
        finally { if (current == generation) checking = false }
    }
    companion object { const val journalVersion = 2 }
}

/** A reservation supplies one open wallet for the entire journal/inspection/payment interval. */
interface SendWallet {
    suspend fun identity(): SendIdentity
    suspend fun quote(payment: SendPayment): SendQuote
    suspend fun prepare(payment: SendPayment, quote: SendQuote): SendSnapshot
    suspend fun pay(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot): SendReceipt
    suspend fun refreshReceipt(payment: SendPayment, receipt: SendReceipt): SendReceipt = receipt
    suspend fun payGuarded(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot, beforeSubmit: () -> Unit): SendReceipt {
        beforeSubmit(); return pay(payment, quote, snapshot)
    }
    suspend fun reconcile(attempt: SendAttempt): SendResolution
    suspend fun discard(quote: SendQuote, includingClaimed: Boolean = false)
    suspend fun retryDiscards() {}
    suspend fun discardAll()
}
