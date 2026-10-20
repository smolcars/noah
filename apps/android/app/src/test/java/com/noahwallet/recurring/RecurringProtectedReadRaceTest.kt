package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecurringProtectedReadRaceTest {
    @Test fun periodicProtectedReadCannotPoisonReplacementAuthority() = race(false)
    @Test fun reminderProtectedReadCannotPoisonReplacementAuthority() = race(true)
    @Test fun periodicReplacementSkipsAuthority() = race(false, replacement = true)
    @Test fun reminderReplacementSkipsAuthority() = race(true, replacement = true)
    private fun race(reminder: Boolean, replacement: Boolean = false) = runBlocking {
        val w = RecurringWorld(this)
        try {
            val schedule = w.add()
            val store = RecurringStore({ w.raw }, { w.raw = it })
            var armed = true
            val binding = {
                val owner = w.binding
                if (armed) {
                    armed = false
                    // A concurrent lifecycle deletion completes after binding resolution.
                    w.policyDirectory.deleteRecursively()
                    RecurringAutomationAuthority.deleted(w.policyDirectory)
                    if (replacement) {
                        w.policyDirectory.mkdirs()
                        w.binding = w.binding.copy(instance = java.util.UUID.randomUUID().toString())
                    }
                }
                owner
            }
            store.protectPolicies(File("${w.journal.file.path}.policies"), sync = {}, syncFile = {}, binding = binding)
            val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, w.journal, w.controller,
                ensureOpen = { error("Read paths must not open the wallet") }, binding = binding,
                allowed = {}, quote = { error("unused") }, automationGeneration = { null }), this)
            if (reminder) assertNull(executor.reminderSchedule(schedule.id, schedule.nextRunAt!!))
            else assertFalse(executor.hasDuePayments(w.now))
            assertEquals(0, w.wallet.calls)
            assertFalse(File(w.policyDirectory, ".noah-recurring-automation.json").exists())
            w.policyDirectory.mkdirs()
            val authority = RecurringAutomationAuthority(w.policyDirectory) { file, bytes -> durableRecurringWrite(file, bytes) {} }
            assertTrue("An old protected read must not latch authority off after completed deletion", authority.allowed())
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
    @Test fun periodicReadWaitsForLifecycleDeletion() = waiting(false)
    @Test fun reminderReadWaitsForLifecycleDeletion() = waiting(true)
    private fun waiting(reminder: Boolean) = runBlocking {
        val w = RecurringWorld(this)
        try {
            val schedule = w.add()
            val gate = Mutex(locked = true)
            val entered = CompletableDeferred<Unit>()
            var bindings = 0
            val reader = RecurringExecutor(RecurringExecutor.Dependencies(w.store, w.journal, w.controller,
                ensureOpen = { error("Read paths must not open the wallet") },
                binding = { bindings++; w.binding }, allowed = {}, quote = { error("unused") }, automationGeneration = { null },
                withWalletMutation = { work -> entered.complete(Unit); gate.withLock { work() } }), this)
            val read = async { if (reminder) assertNull(reader.reminderSchedule(schedule.id, schedule.nextRunAt!!)) else assertFalse(reader.hasDuePayments(w.now)) }
            withTimeout(5000) { entered.await() }
            assertEquals(0, bindings)
            w.policyDirectory.deleteRecursively()
            RecurringAutomationAuthority.deleted(w.policyDirectory)
            gate.unlock()
            withTimeout(5000) { read.await() }
            assertNull(reader.error)
            assertFalse(w.policyDirectory.exists())
            w.policyDirectory.mkdirs()
            assertTrue(RecurringAutomationAuthority(w.policyDirectory) { file, bytes -> durableRecurringWrite(file, bytes) {} }.allowed())
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
    @Test fun periodicReadRevalidatesStopGeneration() = changedGeneration(false)
    @Test fun reminderReadRevalidatesStopGeneration() = changedGeneration(true)
    private fun changedGeneration(reminder: Boolean) = runBlocking {
        val w = RecurringWorld(this)
        try {
            val schedule = w.add()
            var generation = 0
            val reader = RecurringExecutor(RecurringExecutor.Dependencies(w.store, w.journal, w.controller,
                ensureOpen = { error("Read paths must not open the wallet") }, binding = { w.binding }, allowed = {},
                quote = { error("unused") }, automationGeneration = { (generation++).toString() }), this)
            if (reminder) assertNull(reader.reminderSchedule(schedule.id, schedule.nextRunAt!!)) else assertFalse(reader.hasDuePayments(w.now))
            assertFalse(File(w.policyDirectory, ".noah-recurring-automation.json").exists())
            assertNull(reader.error)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }

}
