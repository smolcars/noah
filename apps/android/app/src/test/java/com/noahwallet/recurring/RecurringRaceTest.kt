package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RecurringRaceTest(private val action: String, private val preparation: Boolean) {
    @Test fun pendingExecutionCannotOutliveUserChange() = runBlocking {
        val w = RecurringWorld(this); w.add()
        if (preparation) w.wallet.prepareBlock = { w.change(action); w.wallet.snapshot }
        else w.beforeQuote = { w.change(action) }
        w.executor.execute(); assertEquals(0, w.wallet.calls)
        if (action == "cancel") assertNull(w.schedule)
        if (action == "pause") assertEquals("paused", w.schedule!!.status)
        assertFalse(w.journal.consumed(w.binding, "rent", 1000))
    }
    companion object { @JvmStatic @Parameterized.Parameters(name = "{0}, after preparation={1}") fun cases() =
        listOf("pause", "cancel", "edit", "suspend", "replace", "restore").flatMap { action -> listOf(false, true).map { arrayOf<Any>(action, it) } } }
}
