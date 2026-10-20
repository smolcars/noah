package com.noahwallet.recurring

import kotlinx.coroutines.*

/** Detached settlement may finish after the push callback's independent deadline. */
class RecurringPushDeadline(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val timeoutMillis: Long = 25_000) {
    suspend fun run(budget: RecurringBudget = RecurringBudget.push(), block: suspend (RecurringBudget) -> Unit) {
        val work = scope.async {
            if (!budget.available) throw CancellationException()
            block(budget)
        }
        try { withTimeoutOrNull(timeoutMillis) { work.await() } }
        finally { budget.revoke() }
    }
}
