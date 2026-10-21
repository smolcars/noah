package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RecurringWakeHandoffTest(private val outcome: String) {
    @Test fun backgroundWakesRecheckDueWorkAfterJoiningForeground() = runBlocking {
        val w = RecurringWorld(this); val schedule = w.add()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        w.beforeQuote = {
            if (w.quotes == 1 && outcome != "ambiguous") { entered.complete(Unit); release.await() }
        }
        if (outcome == "ambiguous") w.wallet.payBlock = {
            entered.complete(Unit); release.await(); error("Unknown payment outcome")
        }
        val foregroundBudget = RecurringBudget()
        val foreground = async { w.executor.execute(budget = foregroundBudget) }
        withTimeout(5000) { entered.await() }
        val budgets = List(2) { RecurringBudget.push() }
        val wakes = budgets.map { budget -> async(start = CoroutineStart.UNDISPATCHED) {
            w.executor.execute(budget = budget)
        } }
        if (outcome != "settled") foregroundBudget.revoke()
        if (outcome == "expired") budgets.forEach { it.revoke() }
        release.complete(Unit)
        withTimeout(5000) { foreground.await(); wakes.awaitAll() }
        val expectedCalls = if (outcome == "expired") 0 else 1
        assertEquals(expectedCalls, w.wallet.calls)
        assertEquals(if (outcome == "ambiguous") 0 else expectedCalls, w.schedule!!.occurrencesPaid)
        if (outcome == "ambiguous") {
            assertTrue(w.controller.locked)
            assertEquals("needs_attention", w.schedule!!.status)
        } else if (expectedCalls == 1) {
            assertTrue(w.journal.consumed(w.binding, schedule.id, w.now, schedule.nativeRevision, 0))
        }
        if (outcome != "expired") {
            w.executor.execute()
            assertEquals(1, w.wallet.calls)
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf("revoked", "expired", "settled", "ambiguous")
    }
}
