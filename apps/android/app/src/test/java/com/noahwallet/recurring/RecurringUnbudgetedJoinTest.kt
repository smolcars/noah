package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringUnbudgetedJoinTest {
    @Test fun omittedBudgetJoinOnlyDrainsRevokedWork() = runBlocking {
        val w = RecurringWorld(this); w.add()
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            w.beforeQuote = { if (w.quotes == 1) { entered.complete(Unit); release.await() } }
            val budget = RecurringBudget()
            val foreground = async { w.executor.execute(budget = budget) }
            withTimeout(5000) { entered.await() }
            val drain = async(start = CoroutineStart.UNDISPATCHED) { w.executor.execute() }
            budget.revoke(); release.complete(Unit)
            withTimeout(5000) { foreground.await(); drain.await() }
            assertEquals(0, w.wallet.calls)
            assertEquals(1, w.quotes)
            assertEquals(0, w.schedule!!.occurrencesPaid)
            assertTrue(w.journal.load().isEmpty())
            w.executor.execute(budget = RecurringBudget.push())
            assertEquals(1, w.wallet.calls)
            assertEquals(1, w.schedule!!.occurrencesPaid)
            assertEquals(1, w.schedule!!.runs.size)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
}
