package com.noahwallet.recurring

import com.noahwallet.services.background.*
import com.noahwallet.services.background.PeriodicJobsTest.Companion.dependencies
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringPeriodicTest {
    @Test fun dueCheckUsesStoredDatesStatusAndBackoffWithoutWalletWork() = runBlocking {
        val w = RecurringWorld(this)
        try {
            val due = w.add()
            fun has(schedule: RecurringPayment) = RecurringRuntime.hasDuePayments(RecurringStore.State(mapOf(schedule.id to schedule), false, w.binding), w.now)
            assertTrue(has(due))
            assertTrue(has(due.copy(nextRunAt = w.now - 1)))
            assertFalse(has(due.copy(nextRunAt = w.now + 1)))
            assertFalse(has(due.copy(nextRunAt = null)))
            assertFalse(has(due.copy(retryNotBefore = w.now + 1)))
            assertTrue(has(due.copy(retryNotBefore = w.now)))
            for (status in listOf("paused", "completed", "needs_attention", "needs_approval")) assertFalse(has(due.copy(status = status)))
            assertFalse(has(due.copy(unsupportedStorage = true)))
            assertFalse(has(due.copy(amountSat = 0)))
            assertEquals(0, w.quotes); assertEquals(0, w.wallet.calls); assertEquals(0, w.syncCalls)
        } finally { w.policyDirectory.deleteRecursively() }
    }

    @Test fun protectedLoadRefusesRollbackCorruptionAndDurableStop() = runBlocking {
        for (action in listOf("pause-rollback", "ledger-corrupt", "authority-stop", "authority-corrupt")) {
            val w = RecurringWorld(this)
            try {
                w.add(); val raw = w.raw
                when (action) {
                    "pause-rollback" -> { w.executor.pause("rent"); w.raw = raw }
                    "ledger-corrupt" -> java.io.File("${w.journal.file.path}.policies").writeText("corrupt")
                    "authority-stop" -> RecurringAutomationAuthority(w.policyDirectory) { file, bytes -> file.writeBytes(bytes) }.stop()
                    else -> RecurringAutomationAuthority(w.policyDirectory).file.writeText("corrupt")
                }
                assertFalse(action, RecurringRuntime.hasDuePayments(w.store.load(), w.now))
                assertEquals(0, w.wallet.calls)
            } finally { w.policyDirectory.deleteRecursively() }
        }
    }

    @Test fun periodicCallsExistingExecutorOncePerOccurrenceAndNeverReplaysAmbiguousOutcome() = runBlocking {
        PeriodicExecution.foreground(false)
        for (ambiguous in listOf(false, true)) {
            val w = RecurringWorld(this)
            try {
                w.add()
                if (ambiguous) w.wallet.payBlock = { throw io.github.niteshbalusu11.bark.BarkError("ambiguous") }
                var suppliedBudget: RecurringBudget? = null
                val d = dependencies().let { base -> PeriodicJobs.Dependencies(
                    base.eligible, { RecurringRuntime.hasDuePayments(w.store.load(), w.now) }, base.ensureWalletOpen, base.sync,
                    { budget -> suppliedBudget = budget; w.executor.execute(budget = budget) },
                    base.widgetSummary, base.widgetExpiry, base.publishWidget, base.maintenance, base.backupEnabled, base.backup) }
                val budget = RecurringBudget()
                PeriodicJobs(d).run(PeriodicWork.Kind.refresh, budget)
                assertSame(budget, suppliedBudget)
                PeriodicJobs(d).run(PeriodicWork.Kind.refresh, RecurringBudget())
                assertEquals(1, w.wallet.calls)
                assertEquals(if (ambiguous) "needs_attention" else "active", w.schedule!!.status)
            } finally { w.policyDirectory.deleteRecursively() }
        }
    }
}
