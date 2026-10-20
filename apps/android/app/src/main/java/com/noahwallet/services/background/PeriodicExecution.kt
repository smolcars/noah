package com.noahwallet.services.background

import com.noahwallet.recurring.RecurringBudget
import kotlinx.coroutines.*

/** Deadline revocation cannot abandon an admitted native operation. */
internal object PeriodicExecution {
    @OptIn(InternalCoroutinesApi::class)
    suspend fun run(budget: RecurringBudget, block: suspend () -> Unit) {
        // CoroutineWorker.onStopped is final; observe cancellation before shielded work returns.
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
        }
    }
}
