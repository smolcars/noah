package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RecurringLoadingTest {
    private fun hold(w: RecurringWorld, boundary: String, hook: () -> Unit) {
        when (boundary) {
            "generation" -> w.beforeGeneration = hook
            "binding" -> w.beforeBinding = hook
            "policy" -> w.beforeRead = hook
            "journal" -> w.beforeJournalRead = hook
        }
    }

    @Test fun triggersWaitForCompleteSafetyReadsAndConsumedEvidenceNeverReplays() = runBlocking {
        for (boundary in listOf("generation", "binding", "policy", "journal")) {
            val w = RecurringWorld(this); val schedule = w.add()
            val plan = schedule.plan(w.now, w.zone, true)
            w.journal.save(RecurringJournal.Entry(w.binding, schedule.id, plan, w.now, schedule.nativeRevision, resolved = true, consumed = true))
            val entered = CompletableDeferred<Thread>()
            val release = CountDownLatch(1)
            val once = AtomicBoolean()
            val caller = Thread.currentThread()
            hold(w, boundary) {
                if (once.compareAndSet(false, true)) {
                    entered.complete(Thread.currentThread())
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val foreground = async { w.executor.execute() }
            try {
                assertNotSame("$boundary must load off Main", caller, withTimeout(5000) { entered.await() })
                val push = async { w.executor.execute(budget = RecurringBudget.push()) }
                yield()
                assertTrue(w.executor.running)
                assertFalse(foreground.isCompleted); assertFalse(push.isCompleted)
                assertEquals(0, w.quotes); assertEquals(0, w.wallet.calls)
                release.countDown(); foreground.await(); push.await()
                assertEquals(0, w.wallet.calls); assertEquals(0, w.quotes)
                assertEquals("needs_attention", w.schedule!!.status)
                assertTrue(w.journal.consumed(w.binding, schedule.id, w.now, schedule.nativeRevision, plan.occurrenceIndex))
            } finally { release.countDown(); foreground.await() }
        }
    }

    @Test fun failedSafetyLoadNeverQuotesOrSubmits() = runBlocking {
        for (boundary in listOf("generation", "binding", "policy", "journal")) {
            val w = RecurringWorld(this); w.add()
            hold(w, boundary) { error("unreadable safety evidence") }
            w.executor.execute(); w.executor.execute()
            assertEquals(0, w.quotes); assertEquals(0, w.wallet.calls)
            assertNotNull(w.executor.error)
        }
    }

    @Test fun stopAndReplacementDuringIdentityReadCannotSubmit() = runBlocking {
        for (replace in listOf(false, true)) {
            val w = RecurringWorld(this); w.add()
            val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            val once = AtomicBoolean()
            w.beforeBinding = {
                if (once.compareAndSet(false, true)) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
            }
            val work = async { w.executor.execute() }
            try {
                withTimeout(5000) { entered.await() }
                if (replace) w.binding = w.binding.copy(instance = java.util.UUID.randomUUID().toString())
                else { w.generation = "stopped"; w.allowed = false }
                release.countDown(); work.await()
                assertEquals(0, w.wallet.calls)
                assertTrue(w.controller.attempt == null)
            } finally { release.countDown(); work.await() }
        }
    }
    @Test fun pendingMarkerAndConsumptionWritesRetainOrderingAndLocks() = runBlocking {
        for (boundary in listOf("occurrence", "inFlight", "consumption")) {
            val w = RecurringWorld(this); w.add()
            val entered = CompletableDeferred<Thread>(); val release = CountDownLatch(1)
            val once = AtomicBoolean(); val caller = Thread.currentThread()
            fun holdWrite(raw: String) {
                val matches = when (boundary) {
                    "inFlight" -> raw.contains("\"inFlight\":{")
                    "consumption" -> raw.contains("\"consumed\":true")
                    else -> true
                }
                if (matches && once.compareAndSet(false, true)) {
                    entered.complete(Thread.currentThread()); check(release.await(5, TimeUnit.SECONDS))
                }
            }
            if (boundary == "inFlight") w.beforeWrite = ::holdWrite else w.beforeJournalWrite = ::holdWrite
            val run = async { w.executor.execute() }
            try {
                assertNotSame(caller, withTimeout(5000) { entered.await() })
                val repeat = async { w.executor.execute() }; yield()
                assertFalse(run.isCompleted); assertFalse(repeat.isCompleted)
                assertEquals(if (boundary == "consumption") 1 else 0, w.wallet.calls)
                if (boundary == "consumption") {
                    assertFalse(w.controller.locked)
                    assertTrue(w.journalRaw!!.contains("\"outcome\":\"succeeded\""))
                    assertFalse(w.journalRaw!!.contains("\"consumed\":true"))
                } else {
                    if (boundary == "inFlight") assertNotNull(w.journalRaw)
                    w.generation = "stopped"; w.allowed = false
                }
                release.countDown(); run.await(); repeat.await()
                assertEquals(if (boundary == "consumption") 1 else 0, w.wallet.calls)
                if (boundary == "consumption") {
                    assertTrue(w.journal.load().getValue("rent").consumed)
                    assertEquals(1, w.schedule!!.occurrencesPaid)
                    assertFalse(w.controller.locked)
                }
            } finally { release.countDown(); run.await() }
        }
    }

}
