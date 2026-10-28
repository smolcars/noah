package com.noahwallet.send

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** The process owns submission. Sheet cancellation cannot abandon a submitted native call. */
class SendController(private val dependencies: Dependencies,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    enum class State { ready, submitting, uncertain, succeeded, alreadyPaid, failed, storageBlocked }
    internal var persistRecurringEvidence: ((SendAttempt, Int, Int, State, String?) -> Unit)? = dependencies.recurringEvidence
    private var recurringEvidenceDurable = false
    data class Dependencies(val read: suspend () -> SendAttempt?, val write: (SendAttempt?) -> Unit,
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
        (attempt != null && (state in listOf(State.uncertain, State.storageBlocked) ||
            (state == State.succeeded && attempt?.receipt?.broadcastRetryPending == true))))

    fun invalidateForWalletChange() {
        recurringEvidenceDurable = false
        generation++; walletGeneration++; journalIdentity = null; checking = false; attempt = null; message = null
        unboundJournal = null; unreadableJournal = false; state = State.storageBlocked
    }
    suspend fun reloadForWalletChange() {
        loadAttempt(null)
    }
    /** What loading found; published together, only if no newer load or wallet change started meanwhile. */
    private class Loaded(var attempt: SendAttempt?) {
        var unbound: SendAttempt? = null
        var unreadable = false
        var identity: SendIdentity? = null
        var state = State.storageBlocked
        var message: String? = null
    }
    private suspend fun loadAttempt(retained: SendAttempt?) {
        recurringEvidenceDurable = false
        generation++; walletGeneration++; val current = generation; checking = true; attempt = retained; state = State.storageBlocked; message = null
        unboundJournal = null; unreadableJournal = false
        val loaded = Loaded(retained)
        try {
            dependencies.reservation { wallet -> loadReserved(wallet, current, retained, loaded) }
        } catch (_: Exception) {
            loaded.message = "Unable to load saved payment status. Check your history and recipient before forgetting this attempt."
            loaded.state = State.storageBlocked
        } finally {
            if (current == generation) {
                journalIdentity = loaded.identity
                attempt = loaded.attempt; unboundJournal = loaded.unbound; unreadableJournal = loaded.unreadable
                state = loaded.state; message = loaded.message; checking = false
            }
        }
    }
    private suspend fun loadReserved(wallet: SendWallet, current: Int, retained: SendAttempt?, loaded: Loaded) {
        if (current != generation) return
        val identity = wallet.identity()
        if (current != generation) return
        loaded.identity = identity
        val read = retained ?: readJournal(loaded)
        if (current != generation) return
        val saved = read ?: run { loaded.state = State.ready; return }
        if (owned(saved, identity, loaded)) publish(saved, current, loaded)
    }
    /** A legacy attempt without an owner stays visible; another wallet's attempt is dropped. */
    private fun owned(saved: SendAttempt, identity: SendIdentity, loaded: Loaded): Boolean {
        if (saved.walletIdentity == null) {
            loaded.unbound = saved; loaded.state = State.uncertain
            loaded.message = "This legacy payment has no wallet identity. Check your history and recipient before discarding it."
            return false
        }
        if (saved.walletIdentity != identity) { dependencies.write(null); loaded.attempt = null; loaded.state = State.ready; return false }
        return true
    }
    private fun publish(saved: SendAttempt, current: Int, loaded: Loaded) {
        // Retain the original read-only evidence until migration is durable.
        loaded.attempt = saved
        val migrated = migrated(saved)
        loaded.attempt = migrated
        loaded.state = savedState(migrated)
        loaded.message = migrated.failureMessage
        if (migrated.recurringOccurrenceKey != null) {
            checkNotNull(persistRecurringEvidence)(migrated, current, walletGeneration, loaded.state, loaded.message)
            recurringEvidenceDurable = true
        }
    }
    private suspend fun readJournal(loaded: Loaded): SendAttempt? {
        var read = false
        try { return dependencies.read().also { read = true } } finally { if (!read) loaded.unreadable = true }
    }
    /** An unprovable outcome becomes uncertain; an older journal version is rewritten at the current one. */
    private fun migrated(saved: SendAttempt): SendAttempt {
        val unproven = saved.safetyVersion !in 1..journalVersion || saved.receipt?.isProven(saved.payment, saved.paymentHash) == false ||
            saved.alreadyPaid?.isProven(saved.payment, saved.paymentHash, true) == false
        if (unproven) {
            return saved.copy(safetyVersion = journalVersion, receipt = null, alreadyPaid = null, failed = false,
                possibleTxids = (saved.possibleTxids + listOfNotNull(saved.receipt?.txid, saved.alreadyPaid?.txid)).distinct(),
                failureMessage = "This saved payment outcome could not be verified. It may have been sent. Check history before sending again.")
                .also { dependencies.write(it) }
        }
        // v1 lacked input outpoints but already required receipt provenance.
        if (saved.safetyVersion != journalVersion) return saved.copy(safetyVersion = journalVersion).also { dependencies.write(it) }
        return saved
    }
    private fun savedState(saved: SendAttempt) = when {
        saved.alreadyPaid != null -> State.alreadyPaid
        saved.receipt != null -> if (saved.receipt.isUnresolved) State.uncertain else State.succeeded
        saved.failed -> State.failed
        else -> State.uncertain
    }
    /** One submission: [generation] is the controller's when it started; a newer one supersedes it. */
    private class Submission(val generation: Int, val payment: SendPayment, val quote: SendQuote, val recurringKey: String?,
        val recurringAttemptID: String?, val beforeSubmit: (() -> Unit)?)

    /** Must be invoked on Main: take the lock before launching any coroutine. */
    fun submit(payment: SendPayment, quote: SendQuote, recurringKey: String? = null, beforeSubmit: (() -> Unit)? = null): Deferred<Unit>? {
        if (locked) return null
        state = State.submitting; message = null
        val current = ++generation
        val recurringAttemptID = if (recurringKey != null) java.util.UUID.randomUUID().toString() else null
        recurringEvidenceDurable = false
        val submission = Submission(current, payment, quote, recurringKey, recurringAttemptID, beforeSubmit)
        return scope.async {
            try {
                val reservation = if (recurringKey != null) dependencies.recurringReservation ?: dependencies.reservation else dependencies.reservation
                reservation reserve@{ wallet ->
                    if (current != generation) return@reserve
                    if (prepare(wallet, submission)) pay(wallet, submission)
                }
            } catch (_: Exception) {
                if (current == generation) {
                    state = if (attempt == null) State.ready else State.uncertain
                    message = "Unable to reserve the wallet. Check payment status before sending again."
                }
            }
        }
    }
    /** Journals the attempt before any money can move; false when nothing may be submitted. */
    private suspend fun prepare(wallet: SendWallet, s: Submission): Boolean = try {
        journal(wallet, s)
    } catch (failure: SendNotSubmitted) {
        if (s.generation == generation) {
            if (s.recurringKey != null && attempt == null) {
                val stopped = SendAttempt(recurringAttemptID = s.recurringAttemptID, walletIdentity = s.quote.walletIdentity,
                    recurringOccurrenceKey = s.recurringKey,
                    payment = s.payment, quote = s.quote, failed = true, failureMessage = failure.reason.message)
                attempt = stopped; message = failure.reason.message; finish(SendResolution.Failed)
            } else notPrepared(wallet, s, failure)
        }
        false
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // whatever stopped preparation, report what may exist
        if (s.generation == generation) notPrepared(wallet, s, failure)
        false
    }
    private suspend fun journal(wallet: SendWallet, s: Submission): Boolean {
        val current = s.generation
        val identity = wallet.identity()
        if (current != generation) { wallet.discard(s.quote, includingClaimed = true); return false }
        requireSend(identity == s.quote.walletIdentity, SendFailure.Reason.unresolved)
        s.beforeSubmit?.invoke()
        if (s.payment.method in listOf("lightning", "lnurl")) {
            val marker = SendAttempt(recurringAttemptID = s.recurringAttemptID, walletIdentity = identity, inspectionPending = true,
                recurringOccurrenceKey = s.recurringKey, payment = s.payment, quote = s.quote)
            dependencies.write(marker); attempt = marker
        }
        val snapshot = wallet.prepare(s.payment, s.quote)
        if (current != generation) { wallet.discard(s.quote, includingClaimed = true); return false }
        if (s.recurringKey != null && snapshot.checkpoint != null) throw SendFailure(SendFailure.Reason.lightningInProgress)
        val pending = SendAttempt(recurringAttemptID = s.recurringAttemptID, walletIdentity = identity, recurringOccurrenceKey = s.recurringKey,
            broadcastingTxid = s.quote.prepared?.txid, payment = s.payment,
            snapshot = snapshot,
            paymentHash = PaymentEncoding.invoice(snapshot.resolvedInvoice ?: if (s.payment.method == "lightning") s.payment.destination else "")?.hash,
            quote = s.quote)
        dependencies.write(pending); attempt = pending
        return true
    }
    private suspend fun notPrepared(wallet: SendWallet, s: Submission, failure: Exception) {
        state = if (attempt == null) State.ready else State.uncertain
        message = (failure as? SendFailure)?.message ?: if (attempt == null) "Unable to prepare payment. No payment was submitted."
        else "Unable to inspect this payment. It may already be in progress. Check status before sending again."
        wallet.discard(s.quote, includingClaimed = true)
    }
    /** Submits the journaled attempt; an error with an unknown outcome reconciles instead of guessing. */
    private suspend fun pay(wallet: SendWallet, s: Submission) {
        val current = s.generation
        try {
            val receipt = submitPayment(wallet, s)
            if (current != generation) return
            if (finish(SendResolution.Paid(receipt)) && receipt.txid != null) refreshPaid(wallet, s, receipt)
        } catch (failure: SendAlreadyPaid) { if (current == generation) finish(SendResolution.AlreadyPaid(failure.receipt))
        } catch (failure: SendExistingLightning) { if (current == generation) finish(SendResolution.InProgress(failure.progress))
        } catch (failure: SendNotSubmitted) {
            if (current != generation) return
            attempt = attempt?.copy(failureMessage = failure.reason.message); message = failure.reason.message; finish(SendResolution.Failed)
        } catch (failure: SendFailure) {
            if (current == generation) payFailed(wallet, failure)
        } catch (_: Exception) { // any other error leaves the outcome unknown
            if (current == generation) { state = State.uncertain; reconcile(wallet) }
        } finally { wallet.discard(s.quote, includingClaimed = true) }
    }
    private suspend fun submitPayment(wallet: SendWallet, s: Submission): SendReceipt =
        if (s.beforeSubmit == null) wallet.pay(s.payment, s.quote, attempt!!.snapshot)
        else wallet.payGuarded(s.payment, s.quote, attempt!!.snapshot, s.beforeSubmit)
    private suspend fun payFailed(wallet: SendWallet, failure: SendFailure) {
        if (failure.reason == SendFailure.Reason.lightningInProgress) {
            attempt = attempt?.copy(failureMessage = failure.message); message = failure.message; finish(SendResolution.Uncertain)
        } else { state = State.uncertain; reconcile(wallet) }
    }
    private suspend fun refreshPaid(wallet: SendWallet, s: Submission, receipt: SendReceipt) {
        try {
            val refreshed = wallet.refreshReceipt(s.payment, receipt)
            if (s.generation == generation) finish(SendResolution.Paid(refreshed))
        } catch (_: Exception) { if (s.generation == generation) showRefreshFailure() }
    }
    internal suspend fun releaseRecurring(attemptID: String?, expectedGeneration: Int, expectedWalletGeneration: Int, retainReceipt: Boolean) {
        val current = generation == expectedGeneration && walletGeneration == expectedWalletGeneration
        val same = attemptID != null && attempt?.recurringAttemptID == attemptID
        if (current && same && !retainReceipt) clear()
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
        val current = attempt ?: return false
        if (resolution == SendResolution.Failed && current.receipt != null) return false
        val (saved, outcome) = resolved(current, resolution)
        attempt = saved
        val durable = try { dependencies.write(saved); true } catch (_: Exception) {
            message = "Payment status could not be saved. Check status again after restarting Noah."
            false
        }
        // Done may hide this receipt before the reservation returns to recurring settlement.
        if (saved.recurringOccurrenceKey != null && !persistRecurring(saved, outcome)) return false
        state = outcome
        return durable
    }
    /** The attempt with [resolution] recorded, and the state it leaves Send in. A paid resolution also shows its status error. */
    private fun resolved(saved: SendAttempt, resolution: SendResolution): Pair<SendAttempt, State> = when (resolution) {
        SendResolution.Uncertain ->
            saved.copy(receipt = saved.receipt?.let { if (it.txid != null) it.copy(status = SendReceipt.Status.unlocated) else it }) to State.uncertain
        is SendResolution.Paid -> {
            message = resolution.receipt.statusError
            saved.copy(receipt = resolution.receipt.copy(statusError = null)) to if (resolution.receipt.isUnresolved) State.uncertain else State.succeeded
        }
        is SendResolution.AlreadyPaid -> saved.copy(alreadyPaid = resolution.receipt) to State.alreadyPaid
        is SendResolution.InProgress -> saved.copy(existingLightning = resolution.progress) to State.uncertain
        is SendResolution.Candidates -> saved.copy(candidates = resolution.ids) to State.uncertain
        is SendResolution.PossibleTransactions -> saved.copy(possibleTxids = (saved.possibleTxids + resolution.ids).distinct()) to State.uncertain
        SendResolution.Failed -> saved.copy(failed = true) to State.failed
    }
    /** False, with Send storage-blocked, when the recurring journal can't record this outcome. */
    private fun persistRecurring(saved: SendAttempt, outcome: State): Boolean = try {
        val persist = persistRecurringEvidence ?: error("""Recurring evidence storage unavailable""")
        persist(saved, generation, walletGeneration, outcome, message)
        recurringEvidenceDurable = true
        true
    } catch (_: Exception) {
        recurringEvidenceDurable = false
        message = "Payment status could not be saved. Check status again after restarting Noah."
        state = State.storageBlocked
        false
    }
    suspend fun acknowledge(expected: SendAttempt? = attempt) {
        if (attempt !== expected) return
        if (state !in listOf(State.succeeded, State.alreadyPaid, State.failed) || attempt?.receipt?.broadcastRetryPending == true) return
        clear()
    }
    val overrideWarning get() = listOfNotNull(attempt?.receipt?.conflictDescription, SendOverrideAudit.warningText).joinToString("\n\n")
    suspend fun startNewPaymentAfterHistoryCheck(confirmed: Boolean, warning: String = overrideWarning,
        confirmation: String = SendOverrideAudit.confirmationText) {
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
    private suspend fun clear(beforeRemove: (() -> Unit)? = null) {
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
                    persistRecurringBeforeRemoval()
                    dependencies.write(null)
                    removed = true
                }
            }
            if (removed && current == generation) {
                recurringEvidenceDurable = false
                attempt = null; unboundJournal = null; unreadableJournal = false; state = State.ready; message = null
            }
        } catch (_: Exception) {
            if (current == generation) {
                message = if (!audited) "Unable to save the override confirmation. Sending remains locked." else "Unable to save payment status."
            }
        }
        finally { if (current == generation) checking = false }
    }
    private fun persistRecurringBeforeRemoval() {
        if (attempt?.recurringOccurrenceKey != null && !recurringEvidenceDurable) {
            val saved = attempt!!
            val persist = persistRecurringEvidence ?: error("""Recurring evidence storage unavailable""")
            persist(saved, generation, walletGeneration, state, message)
            recurringEvidenceDurable = true
        }
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
