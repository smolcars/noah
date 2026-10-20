package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringStageBudgetTest {
    @Test fun revocationBeforeOrDuringOpenPreventsLaterIndependentStages() = runBlocking {
        for (preRevoked in listOf(false, true)) {
            val w = RecurringWorld(this); w.add()
            val budget = RecurringBudget()
            val stages = mutableListOf<String>()
            if (preRevoked) budget.revoke()
            val executor = RecurringExecutor(RecurringExecutor.Dependencies(w.store, w.journal, w.controller,
                ensureOpen = { stages.add("open"); budget.revoke() }, binding = { w.binding }, allowed = {},
                quote = { error("revoked") }, automationGeneration = { null }, now = { w.now }, zone = { w.zone },
                sync = { stages.add("report") }, notify = { _, _ -> stages.add("notify") }, refresh = { stages.add("widget") }), this)
            executor.execute(budget = budget)
            assertEquals(if (preRevoked) emptyList<String>() else listOf("open"), stages)
            assertEquals(0, w.wallet.calls)
        }
    }
    @Test fun callerCancellationDuringOpenStopsDetachedStages() = runBlocking {
        val w = RecurringWorld(this); w.add()
        val stages = mutableListOf<String>()
        lateinit var caller: Job
        val executor = RecurringExecutor(RecurringExecutor.Dependencies(w.store, w.journal, w.controller,
            ensureOpen = { stages.add("open"); caller.cancel() }, binding = { w.binding }, allowed = {},
            quote = { error("cancelled") }, automationGeneration = { null }, now = { w.now }, zone = { w.zone },
            sync = { stages.add("report") }, notify = { _, _ -> stages.add("notify") }, refresh = { stages.add("widget") }), this)
        caller = launch(start = CoroutineStart.LAZY) { executor.execute() }
        caller.start(); caller.join()
        withTimeout(2000) { while (executor.running) yield() }
        assertEquals(listOf("open"), stages)
        assertEquals(0, w.wallet.calls)
    }
}
