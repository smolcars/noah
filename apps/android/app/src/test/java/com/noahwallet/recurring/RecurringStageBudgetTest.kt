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
}
