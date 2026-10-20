package com.noahwallet.services.wallet

import kotlinx.coroutines.*

/** The UI deadline never cancels native work or releases its session reservation. */
internal class EsploraOpenWait {
    class Timeout : Exception()
    private val lock = Any()
    private val outcome = CompletableDeferred<Unit>()
    private val useDefault = CompletableDeferred<Unit>()
    private var expired = false
    private var committed = false
    private var deadlineNanos = 0L
    val timedOut: Boolean get() = synchronized(lock) { expired }

    suspend fun run(timeoutMillis: Long = 12_000, lateCompletion: suspend (Boolean) -> Unit = {},
        operation: suspend (EsploraOpenWait) -> Unit) {
        synchronized(lock) { deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000 }
        val timer = scope.launch {
            delay(timeoutMillis)
            synchronized(lock) {
                if (!committed && !outcome.isCompleted) { expired = true; outcome.completeExceptionally(Timeout()) }
            }
        }
        scope.launch {
            val result = runCatching { operation(this@EsploraOpenWait) }
            val late = synchronized(lock) {
                timer.cancel()
                result.fold({ outcome.complete(Unit) }, { outcome.completeExceptionally(it) })
                expired
            }
            if (late) lateCompletion(result.isSuccess)
        }
        outcome.await()
    }
    /** No suspension remains after commit: the session publishes the handles synchronously. */
    fun commit(persist: () -> Unit) = synchronized(lock) {
        if (expired || System.nanoTime() >= deadlineNanos) {
            expired = true; outcome.completeExceptionally(Timeout()); throw Timeout()
        }
        persist()
        committed = true
    }
    fun chooseDefault() { useDefault.complete(Unit) }
    suspend fun waitForDefault() { useDefault.await() }

    companion object { private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
