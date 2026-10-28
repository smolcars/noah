package com.noahwallet.recurring

import com.noahwallet.send.SendReceipt
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringWakeCleanupTest {
    @Test fun revokedCleanupFinishesAndIndependentWakePaysNewDueWork() = cleanupHandoff("revoked")
    @Test fun failedPredecessorDoesNotFailIndependentWake() = cleanupHandoff("failed")
    @Test fun cancelledPredecessorDoesNotCancelIndependentWake() = cleanupHandoff("cancelled")

    private fun cleanupHandoff(outcome: String) = runBlocking { supervisorScope {
        val w = RecurringWorld(this); val first = w.add(); val second = first.copy(id = "second")
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            w.beforeQuote = { if (w.quotes == 1) { entered.complete(Unit); release.await() } }
            val budget = RecurringBudget()
            var hit = false
            w.beforeRead = {
                // reload publishes schedules before cleanup's final asynchronous state read.
                if (!hit && w.executor.schedules.isNotEmpty()) {
                    hit = true
                    w.store.upsert(second)
                    when (outcome) {
                        "revoked" -> budget.revoke()
                        "failed" -> budget.admission = { error("Predecessor cleanup failed") }
                        "cancelled" -> budget.admission = { throw CancellationException("Predecessor cancelled") }
                    }
                }
            }
            val foreground = async { runCatching { w.executor.execute(budget = budget) } }
            withTimeout(5000) { entered.await() }
            val wake = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { w.executor.execute(budget = RecurringBudget.push()) }
            }
            release.complete(Unit)
            val results = withTimeout(5000) { listOf(foreground.await(), wake.await()) }
            assertTrue("Targeted cleanup boundary was reached", hit)
            assertTrue("Independent wake failed: ${results[1].exceptionOrNull()}; payments=${w.wallet.calls}; " +
                "secondPaid=${w.store.load().schedules[second.id]?.occurrencesPaid}", results[1].isSuccess)
            assertEquals(outcome == "revoked", results[0].isSuccess)
            assertEquals(2, w.wallet.calls)
            for (schedule in listOf(first, second)) {
                val paid = w.store.load().schedules.getValue(schedule.id)
                assertEquals(1, paid.occurrencesPaid)
                assertEquals(1, paid.runs.size)
                assertTrue(w.journal.consumed(w.binding, schedule.id, w.now, schedule.nativeRevision, 0))
                assertTrue(w.journal.load().getValue(schedule.id).released)
            }
            w.executor.execute(budget = RecurringBudget.push())
            assertEquals(2, w.wallet.calls)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    } }

    @Test fun submittedForegroundRevocationCannotReplay() = runBlocking {
        for (revokeBeforeJoin in listOf(false, true)) {
            val w = RecurringWorld(this); val schedule = w.add()
            try {
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                w.wallet.payBlock = {
                    entered.complete(Unit); release.await()
                    SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess)
                }
                val budget = RecurringBudget()
                val foreground = async { w.executor.execute(budget = budget) }
                withTimeout(5000) { entered.await() }
                assertEquals(1, w.wallet.calls)
                if (revokeBeforeJoin) budget.revoke()
                val wakes = List(3) { async(start = CoroutineStart.UNDISPATCHED) {
                    w.executor.execute(budget = RecurringBudget.push())
                } }
                budget.revoke(); release.complete(Unit)
                withTimeout(5000) { foreground.await(); wakes.awaitAll() }
                assertEquals(1, w.wallet.calls)
                assertEquals(1, w.schedule!!.occurrencesPaid)
                assertEquals(1, w.schedule!!.runs.size)
                assertTrue(w.journal.consumed(w.binding, schedule.id, w.now, schedule.nativeRevision, 0))
            } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
        }
    }

    @Test fun cancelledWaiterStopsWithoutUsingItsLiveBudget() = runBlocking {
        val w = RecurringWorld(this); w.add()
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            w.beforeQuote = { if (w.quotes == 1) { entered.complete(Unit); release.await() } }
            val budget = RecurringBudget()
            val foreground = async { w.executor.execute(budget = budget) }
            withTimeout(5000) { entered.await() }
            val wake = async(start = CoroutineStart.UNDISPATCHED) { w.executor.execute(budget = RecurringBudget.push()) }
            wake.cancelAndJoin()
            budget.revoke(); release.complete(Unit)
            withTimeout(5000) { foreground.await() }
            assertTrue(wake.isCancelled)
            assertEquals(0, w.wallet.calls)
            w.executor.execute(budget = RecurringBudget.push())
            assertEquals(1, w.wallet.calls)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
}
