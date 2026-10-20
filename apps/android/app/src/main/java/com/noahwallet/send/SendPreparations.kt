package com.noahwallet.send

import kotlinx.coroutines.*

/** Owned by the open session; disposal keeps ownership of reserved inputs until native success. */
internal class SendPreparations(private val discardNative: suspend (String) -> Unit,
    private val warn: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private data class Review(var payment: SendPayment? = null, var quote: SendQuote? = null,
        var timeout: Job? = null, var discardTask: Deferred<Unit>? = null,
        var discardRequested: Boolean = false, var claimed: Boolean = false, var broadcasting: Boolean = false)
    private val lock = Any()
    private val reviews = mutableMapOf<String, Review>()

    fun retain(id: String) = synchronized(lock) {
        check(id !in reviews)
        reviews[id] = Review(timeout = scope.launch { delay(SendPrepared.lifetimeMillis); discard(id) })
    }
    fun bind(id: String, payment: SendPayment, quote: SendQuote) = synchronized(lock) {
        val held = reviews[id] ?: throw SendFailure(SendFailure.Reason.feeChanged)
        requireSend(!held.discardRequested, SendFailure.Reason.feeChanged)
        held.payment = payment; held.quote = quote
    }
    fun claim(payment: SendPayment, quote: SendQuote) = synchronized(lock) {
        val prepared = quote.prepared ?: throw SendFailure(SendFailure.Reason.feeChanged)
        val held = reviews[prepared.id]
        requireSend(prepared.isCurrent() && held != null && !held.claimed && !held.discardRequested &&
            held.payment == payment && held.quote == quote, SendFailure.Reason.feeChanged)
        held!!.claimed = true; held.timeout?.cancel(); held.timeout = null
    }
    fun consume(payment: SendPayment, quote: SendQuote): Boolean = synchronized(lock) {
        val held = reviews[quote.prepared?.id] ?: return false
        if (!held.claimed || held.discardRequested || held.broadcasting || held.payment != payment || held.quote != quote) return false
        held.broadcasting = true
        true
    }
    suspend fun discard(id: String, includingClaimed: Boolean = false) = withContext(NonCancellable) {
        val task = synchronized(lock) {
            val held = reviews[id] ?: return@withContext
            if (held.claimed && !includingClaimed) return@withContext
            held.discardRequested = true
            held.discardTask ?: scope.async(start = CoroutineStart.LAZY) { performDiscard(id) }.also {
                held.discardTask = it; it.start()
            }
        }
        task.await()
    }
    private suspend fun performDiscard(id: String) {
        repeat(3) { retry ->
            try {
                discardNative(id)
                synchronized(lock) { reviews.remove(id)?.timeout?.cancel() }
                return
            } catch (_: Exception) {
                warn("Prepared send handle could not be discarded; retaining it for retry")
                if (retry < 2) delay(100)
            }
        }
        synchronized(lock) { reviews[id]?.discardTask = null }
    }
    suspend fun retryDiscards() {
        synchronized(lock) { reviews.filterValues { it.discardRequested }.keys.toList() }.forEach { discard(it, true) }
    }
    suspend fun discardAll() {
        synchronized(lock) { reviews.keys.toList() }.forEach { discard(it, true) }
        requireSend(synchronized(lock) { reviews.isEmpty() }, SendFailure.Reason.unresolved)
    }
}
