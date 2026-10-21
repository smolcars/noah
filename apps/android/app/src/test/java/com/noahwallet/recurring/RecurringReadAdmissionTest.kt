package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RecurringReadAdmissionTest {
    @Test fun revocationDuringAnyPrequotePolicyReadPreventsQuote() = prequoteRead(false)
    @Test fun callerCancellationDuringAnyPrequotePolicyReadPreventsQuote() = prequoteRead(true)

    private fun prequoteRead(cancelCaller: Boolean) = runBlocking {
        val probe = RecurringWorld(this); probe.add()
        val reads = AtomicInteger(); var readsBeforeQuote = 0
        val probeBudget = RecurringBudget()
        try {
            probe.beforeRead = { reads.incrementAndGet() }
            probe.beforeQuote = { readsBeforeQuote = reads.get(); probeBudget.revoke() }
            probe.executor.execute(budget = probeBudget)
        } finally { probe.policyDirectory.parentFile!!.deleteRecursively() }
        assertTrue(readsBeforeQuote > 0)
        for (position in 1..readsBeforeQuote) {
            val w = RecurringWorld(this); w.add()
            try {
                val budget = RecurringBudget(); val count = AtomicInteger()
                val caller = launch(start = CoroutineStart.LAZY) { w.executor.execute(budget = budget) }
                w.beforeRead = {
                    if (count.incrementAndGet() == position) {
                        if (cancelCaller) caller.cancel() else budget.revoke()
                    }
                }
                caller.start(); caller.join()
                withTimeout(5000) { while (w.executor.running) yield() }
                assertEquals("policy read $position", 0, w.quotes)
                assertEquals(0, w.wallet.calls)
                assertTrue(w.journal.load().isEmpty())
                assertEquals(0, w.schedule!!.consecutiveFailures)
            } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
        }
    }

    @Test fun revocationDuringQuotedPolicyOrMarkerReadPreventsAdmission() = runBlocking {
        // Each protected load reads reason-migration evidence and then the current policy.
        for (position in 1..4) {
            val w = RecurringWorld(this); w.add()
            try {
                val budget = RecurringBudget(); val reads = AtomicInteger()
                w.beforeRead = { if (w.quotes > 0 && reads.incrementAndGet() == position) budget.revoke() }
                w.executor.execute(budget = budget)
                assertTrue("read $position was reached", reads.get() >= position)
                assertEquals(1, w.quotes)
                assertEquals(0, w.wallet.calls)
                assertTrue("post-quote policy read $position", w.journal.load().isEmpty())
                assertNull(w.schedule!!.inFlight)
                assertEquals(0, w.schedule!!.consecutiveFailures)
            } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
        }
    }
}
