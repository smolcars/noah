package com.noahwallet.services.background

import com.noahwallet.recurring.RecurringBudget
import kotlinx.coroutines.*

/** Foreground revokes synchronously, then waits outside the lifecycle gate for settlement. */
internal object PeriodicExecution {
    private val lock = Any()
    private var foreground = false
    private val active = mutableMapOf<RecurringBudget, CompletableDeferred<Unit>>()

    fun foreground(value: Boolean) = synchronized(lock) {
        foreground = value
        if (value) active.keys.forEach { it.revoke() }
    }

    suspend fun awaitIdle() {
        val pending = synchronized(lock) { active.values.toList() }
        pending.forEach { it.await() }
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun run(budget: RecurringBudget, block: suspend () -> Unit) {
        val settled = synchronized(lock) {
            if (foreground) { budget.revoke(); return }
            CompletableDeferred<Unit>().also { active[budget] = it }
        }
        // CoroutineWorker.onStopped is final; observe its cancellation before shielded work returns.
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) budget.revoke()
        }
        try {
            coroutineScope {
                val expiry = launch { delay(25_000); budget.revoke() }
                try { block() } finally { expiry.cancel() }
            }
        } finally {
            cancellation?.dispose()
            budget.revoke()
            synchronized(lock) { active.remove(budget); settled.complete(Unit) }
        }
    }
}
