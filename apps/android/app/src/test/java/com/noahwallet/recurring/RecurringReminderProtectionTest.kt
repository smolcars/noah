package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecurringReminderProtectionTest {
    @Test fun failedPauseOrCancelCannotAdvertiseRolledBackActivePolicy() = runBlocking {
        for (cancel in listOf(false, true)) {
            val w = RecurringWorld(this); w.add(); w.executor.timeChanged()
            val active = w.raw
            val due = w.schedule!!.nextRunAt!!
            w.mmkvFails = true
            assertTrue(runCatching { if (cancel) w.executor.cancel("rent") else w.executor.pause("rent") }.isFailure)
            assertEquals(active, w.raw)
            val store = RecurringStore({ w.raw }, { w.raw = it })
            store.protectPolicies(File("${w.journal.file.path}.policies"), sync = {}, syncFile = {}, binding = { w.binding })
            val reader = RecurringExecutor(RecurringExecutor.Dependencies(store, w.journal, w.controller,
                ensureOpen = {}, binding = { w.binding }, allowed = {}, quote = { error("unused") }, automationGeneration = { null }), this)
            assertNull(reader.reminderSchedule("rent", due))
        }
    }
}
