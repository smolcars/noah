package com.noahwallet.onchain

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*

/** Session-owned reservations; a failed discard keeps ownership until native cleanup succeeds. */
internal class BoardPreparations(
    private val discardNative: suspend (String) -> Unit,
    private val retrySubmitted: suspend (String) -> Unit,
    private val warn: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val lifetimeMillis: Long = BoardPrepared.lifetimeMillis,
) {
    private class Review(var quote: OnchainQuote? = null, var claimed: Boolean = false,
        var submitting: Boolean = false, var discardRequested: Boolean = false,
        var timeout: Job? = null, var disposal: Deferred<Unit>? = null)
    private val lock = Any()
    private val unsubmitted = mutableMapOf<String, OnchainQuote>()
    private val reviews = mutableMapOf<String, Review>()
    fun retain(id: String) = synchronized(lock) {
        check(id !in reviews)
        reviews[id] = Review(timeout = scope.launch { delay(lifetimeMillis); discard(id) })
    }
    fun contains(id: String): Boolean = synchronized(lock) { id in reviews }
    fun bind(quote: OnchainQuote) = synchronized(lock) {
        val held = reviews[quote.preparedBoard?.id] ?: fail(OnchainFailure.changed)
        if (held.discardRequested) fail(OnchainFailure.changed)
        held.quote = quote
    }
    fun claim(quote: OnchainQuote) = synchronized(lock) {
        val held = reviews[quote.preparedBoard?.id] ?: fail(OnchainFailure.changed)
        if (!quote.current() || held.quote != quote || held.claimed || held.discardRequested) fail(OnchainFailure.changed)
        held.claimed = true; held.timeout?.cancel(); held.timeout = null
    }
    fun consume(quote: OnchainQuote) = synchronized(lock) {
        val held = reviews[quote.preparedBoard?.id] ?: fail(OnchainFailure.changed)
        if (!held.claimed || held.submitting || held.discardRequested || held.quote != quote) fail(OnchainFailure.changed)
        held.submitting = true
    }
    suspend fun discard(id: String, includingClaimed: Boolean = false) = withContext(NonCancellable) {
        val task = synchronized(lock) {
            val held = reviews[id] ?: return@withContext
            if (held.claimed && !includingClaimed) return@withContext
            held.discardRequested = true
            held.disposal ?: scope.async(start = CoroutineStart.LAZY) { dispose(id) }.also { held.disposal = it; it.start() }
        }
        task.await()
    }
    private suspend fun dispose(id: String) {
        repeat(3) { retry ->
            try {
                try { discardNative(id) }
                catch (error: BarkError) {
                    when (error.code) {
                        BarkErrorCode.PREPARED_TRANSACTION_NOT_FOUND -> Unit
                        BarkErrorCode.PERSISTENCE -> {
                            // Only repair the already accepted board, using the same id and signed bytes.
                            check(synchronized(lock) { reviews[id]?.submitting == true })
                            retrySubmitted(id)
                            discardNative(id)
                        }
                        else -> throw error
                    }
                }
                synchronized(lock) {
                    reviews.remove(id)?.let { held ->
                        if (!held.claimed && !held.submitting) held.quote?.takeIf { it.automatic }?.let { unsubmitted[it.id] = it }
                        held.timeout?.cancel()
                    }
                }
                return
            } catch (_: Exception) {
                warn("""Prepared board cleanup failed; retaining its handle for retry""")
                if (retry < 2) delay(100)
            }
        }
        synchronized(lock) { reviews[id]?.disposal = null }
    }
    suspend fun abandonUnsubmitted(quote: OnchainQuote): Boolean {
        val id = quote.preparedBoard?.id ?: return false
        val allowed = synchronized(lock) {
            val held = reviews[id]
            if (held != null) {
                if (held.quote != quote || held.claimed || held.submitting) false
                else { held.discardRequested = true; true }
            } else unsubmitted[quote.id] == quote
        }
        if (!allowed) return false
        discard(id)
        return synchronized(lock) { unsubmitted.remove(quote.id) == quote }
    }
    suspend fun retryDiscards() {
        synchronized(lock) { reviews.filterValues { it.discardRequested }.keys.toList() }.forEach { discard(it, true) }
    }
    suspend fun discardAll() {
        synchronized(lock) { reviews.keys.toList() }.forEach { discard(it, true) }
        if (synchronized(lock) { reviews.isNotEmpty() }) fail(OnchainFailure.uncertain)
    }
}
