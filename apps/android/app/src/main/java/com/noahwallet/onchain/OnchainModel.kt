package com.noahwallet.onchain

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** UI state never replays a mutation. Refresh reconciles cached evidence, including retained originals. */
class OnchainModel(private val dependencies: Dependencies, private val detailID: String? = null) {
    class Dependencies(
        val ready: () -> Boolean, val overview: suspend () -> OnchainOverview,
        val quote: suspend (OnchainAction, List<String>, Long, String?) -> OnchainQuote,
        val execute: suspend (OnchainQuote) -> String, val attempts: suspend () -> List<OnchainOperationJournal.Attempt>,
        val acknowledge: suspend (String) -> Unit, val check: suspend (String) -> Boolean,
        val override: suspend (String) -> Unit, val detail: suspend (String) -> ExitModel?,
        val refreshReceipts: suspend () -> Unit,
        /** Waits while the wallet is only briefly not ready ([ExitRules.settling]), as after every app switch; true once it's ready. */
        val settle: suspend () -> Boolean = { false },
        val claimAddress: suspend () -> String = { fail(OnchainFailure.unavailable) },
        val claimOwnership: suspend (String) -> Boolean = { fail(OnchainFailure.unavailable) },
        val discard: suspend (OnchainQuote) -> Unit = {},
        /** Submits Home's Renew, checking under the wallet's locks that every VTXO is still due. */
        val executeRenewal: suspend (OnchainQuote) -> String = { fail(OnchainFailure.unavailable) },
        /** One coin by ID from the wallet, spent ones included (Activity opens a payment's coins). */
        val coin: suspend (String) -> VtxoModel = { fail(OnchainFailure.unavailable) },
    )
    /**
     * A coin the overview doesn't list, such as one a payment spent, or the failed lookup. bark has no not-found code,
     * so a failure is unknown, never "no record": a refresh retries it.
     */
    suspend fun coin(id: String): Result<VtxoModel> = try { Result.success(dependencies.coin(id)) }
        catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Result.failure(failure) }
    var overview by mutableStateOf<OnchainOverview?>(null); private set
    /** Counts completed overview reads (pull to refresh, the periodic read), so a coin lookup can retry after each. */
    var revision by mutableIntStateOf(0); private set
    var exit by mutableStateOf<ExitModel?>(null); private set
    var estimateFallback by mutableStateOf<OnchainQuote?>(null); private set
    var quote by mutableStateOf<OnchainQuote?>(null); private set
    var busy by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var attempts by mutableStateOf<List<OnchainOperationJournal.Attempt>>(emptyList()); private set
    var message by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var claimDestination by mutableStateOf(""); private set
    var claimDestinationIsMine by mutableStateOf<Boolean?>(null); private set
    var claimDestinationError by mutableStateOf<String?>(null); private set
    fun editClaimDestination(value: String) {
        claimDestination = value; claimDestinationIsMine = null; claimDestinationError = null; invalidate()
    }
    suspend fun useWalletClaimAddress() {
        val previous = claimDestination
        try {
            if (!dependencies.ready()) fail(OnchainFailure.unavailable)
            val address = dependencies.claimAddress()
            if (previous == claimDestination && dependencies.ready()) editClaimDestination(address)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { claimDestinationError = "Couldn't load a Noah wallet address. Try again when the wallet is ready." }
    }
    suspend fun checkClaimDestination() {
        val address = claimDestination
        claimDestinationIsMine = null; claimDestinationError = null
        if (address.isBlank()) return
        try {
            if (!dependencies.ready()) fail(OnchainFailure.unavailable)
            val mine = dependencies.claimOwnership(address.trim())
            if (address == claimDestination && dependencies.ready()) claimDestinationIsMine = mine
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (address == claimDestination) claimDestinationError = "Enter a valid Bitcoin address for this network when the wallet is ready." }
    }
    private var generation = 0
    /** The shown quote is Home's Renew: it's submitted through [Dependencies.executeRenewal]. */
    private var renewal = false
    private val disposalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun invalidate() {
        generation++
        quote?.let { held -> disposalScope.launch { dependencies.discard(held) } }
        quote = null; estimateFallback = null; renewal = false
    }
    fun unavailable() { invalidate(); overview = null; exit = null }
    private fun visible(saved: List<OnchainOperationJournal.Attempt>) = saved.filter { it.overrideAudit?.acknowledgedAt == null }
    private var readAgain = false
    /** One read at a time; asking during a read gets one more read after it, however many times it was asked. */
    suspend fun load() {
        if (loading) { readAgain = true; return }
        loading = true
        try { do { readAgain = false; read() } while (readAgain) }
        finally { loading = false }
    }
    private suspend fun read() {
        try {
            val saved = dependencies.attempts()
            attempts = visible(saved)
            // A sync, a background job or a reopen holds the wallet for a moment: keep what's shown and wait for it.
            if (!dependencies.ready() && !dependencies.settle()) { unavailable(); fail(OnchainFailure.unavailable) }
            var receiptError: String? = null
            for (attempt in saved.filter { it.receipt == null || it.receipt.txid != null }) {
                try { dependencies.check(attempt.id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { receiptError = com.noahwallet.services.wallet.ChainStatus.errorText }
            }
            try { dependencies.refreshReceipts() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            attempts = visible(dependencies.attempts())
            val token = generation
            val detail = detailID?.let { dependencies.detail(it) }
            val latest = dependencies.overview()
            if (generation == token && dependencies.ready()) { exit = detail; overview = latest; error = receiptError; revision++ }
            // The wallet got busy during the read: read again, which waits for it.
            else if (!dependencies.ready()) readAgain = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = (failure as? OnchainException)?.message ?: "Couldn't read wallet status. Pull to refresh." }
    }
    suspend fun review(action: OnchainAction, ids: List<String> = emptyList(), amount: Long = 0, destination: String? = null,
                       renewal: Boolean = false) {
        if (busy) return
        invalidate(); error = null; message = null; busy = true
        val token = generation
        try {
            if (!dependencies.ready()) fail(OnchainFailure.unavailable)
            attempts = visible(dependencies.attempts())
            if (attempts.any { it.blocks(action, ids) }) fail(OnchainFailure.busy)
            val caller = currentCoroutineContext()
            val fresh = withContext(NonCancellable) { dependencies.quote(action, ids, amount, destination) }
            if (caller.isActive && token == generation && dependencies.ready()) { quote = fresh; this.renewal = renewal }
            else withContext(NonCancellable) { dependencies.discard(fresh) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ExitEstimateUnavailable) {
            if (token == generation && dependencies.ready()) estimateFallback = failure.review
        }
        catch (failure: Exception) { if (token == generation) error = (failure as? OnchainException ?: failure as? ExitNotPossible)?.message ?: "Couldn't prepare the review. Nothing was submitted. Try again when the wallet is ready." }
        finally { busy = false }
    }
    fun continueWithoutEstimate() {
        if (busy) return
        val fallback = estimateFallback ?: return
        estimateFallback = null
        if (!dependencies.ready() || !fallback.current()) { invalidate(); return }
        quote = fallback.copy(unknownFeeConsent = fallback.id)
    }
    /** The view must present the quote and obtain explicit consent before calling this. */
    suspend fun confirm() {
        if (busy) return
        val reviewed = quote ?: return
        val renewing = renewal
        val token = generation
        quote = null; renewal = false; busy = true; error = null
        try {
            if (!dependencies.ready() || !reviewed.current()) fail(OnchainFailure.changed)
            message = if (renewing) dependencies.executeRenewal(reviewed) else dependencies.execute(reviewed)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ExitEstimateUnavailable) {
            if (token == generation && dependencies.ready()) {
                try { estimateFallback = failure.preserving(reviewed).review }
                catch (expired: OnchainException) { error = expired.message }
            }
        }
        catch (failure: Exception) { error = (failure as? OnchainException ?: failure as? ExitNotPossible)?.message ?: OnchainFailure.uncertain.text }
        finally { withContext(NonCancellable) { dependencies.discard(reviewed) }; busy = false }
        val submissionError = error
        load()
        if (submissionError != null) error = submissionError
    }
    suspend fun acknowledge(id: String) {
        if (busy) return
        try { dependencies.acknowledge(id); attempts = attempts.filter { it.id != id }; message = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = OnchainFailure.uncertain.text }
    }
    suspend fun checkStatus(id: String) {
        if (busy) return
        busy = true; invalidate()
        try {
            if (!dependencies.ready()) fail(OnchainFailure.unavailable)
            val resolved = dependencies.check(id)
            load()
            // Only a board proven never recorded leaves the journal on a check.
            message = if (resolved && attempts.none { it.id == id }) OnchainFailure.boardNotAttempted.text
                else if (resolved) "Status resolved. Review the receipt." else "Status remains uncertain. Noah checks again automatically as your wallet syncs."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = (failure as? OnchainException)?.message ?: "Couldn't check status. The attempt is still saved." }
        finally { busy = false }
    }
    suspend fun overrideUncertain(id: String) {
        if (busy) return
        busy = true; invalidate()
        try {
            dependencies.override(id); attempts = visible(dependencies.attempts())
            message = "Repeat protection overridden. The original attempt is still saved. Review the current wallet state before submitting again."
        } catch (failure: Exception) { error = OnchainFailure.uncertain.text }
        finally { busy = false }
    }
}
