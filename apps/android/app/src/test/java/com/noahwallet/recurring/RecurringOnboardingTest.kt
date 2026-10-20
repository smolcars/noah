package com.noahwallet.recurring

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecurringOnboardingTest {
    @Test fun preOnboardingTriggerMustNotPoisonTheNextWalletAuthority() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.policyDirectory.deleteRecursively()
            w.allowed = false
            val store = RecurringStore({ w.raw }, { w.raw = it })
            val binding = { check(w.policyDirectory.isDirectory); w.binding }
            store.protectPolicies(java.io.File("${w.journal.file.path}.policies"), sync = {}, syncFile = {}, binding = binding)
            val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, w.journal, w.controller,
                ensureOpen = { check(w.allowed) }, binding = binding, allowed = { check(w.allowed) },
                quote = { error("No schedule exists") }, automationGeneration = { null }), this)
            executor.timeChanged(); executor.execute() // TIMEZONE_CHANGED dispatches both even on Welcome.
            assertEquals(0, w.wallet.calls)
            w.policyDirectory.mkdirs()
            w.allowed = true
            store.bind(w.binding)
            val authority = RecurringAutomationAuthority(w.policyDirectory) { file, bytes -> durableRecurringWrite(file, bytes) {} }
            assertTrue("The newly created wallet must bootstrap its own authority", authority.allowed())
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
}
