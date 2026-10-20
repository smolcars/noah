package com.noahwallet.recurring

import com.noahwallet.services.storage.WalletDataLocation
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecurringAdmissionRaceTest {
    @Test fun deletionAfterAdmissionMustNotPoisonNewWallet() = admissionRace(replacement = false)
    @Test fun replacementAfterAdmissionMustNotTouchNewAuthority() = admissionRace(replacement = true)
    private fun admissionRace(replacement: Boolean) = runBlocking {
        val w = RecurringWorld(this)
        try {
            val location = WalletDataLocation(w.policyDirectory.parentFile!!, "regtest")
            location.database.writeBytes(byteArrayOf())
            val store = RecurringStore({ w.raw }, { w.raw = it })
            val binding = { check(location.databaseExists); w.binding }
            store.protectPolicies(File("${w.journal.file.path}.policies"), sync = {}, syncFile = {}, binding = binding)
            val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, w.journal, w.controller,
                ensureOpen = {}, binding = binding,
                allowed = { RecurringRuntime.requireAdmission(location, null, false) },
                quote = { error("unused") },
                automationGeneration = {
                    // Model deletion completing after allowed(), before the authority read.
                    location.directory.deleteRecursively()
                    RecurringAutomationAuthority.deleted(location.directory)
                    if (replacement) {
                        location.directory.mkdirs(); location.database.writeBytes(byteArrayOf())
                        w.binding = w.binding.copy(instance = java.util.UUID.randomUUID().toString())
                    }
                    null
                }), this)
            executor.execute()
            assertNull(executor.error)
            assertFalse(File(location.directory, ".noah-recurring-automation.json").exists())
            location.directory.mkdirs()
            location.database.writeBytes(byteArrayOf())
            assertTrue("A completed deletion must not let old work poison the replacement wallet", RecurringAutomationAuthority(location.directory) { file, bytes -> durableRecurringWrite(file, bytes) {} }.allowed())
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
    @Test fun waitingTriggerRevalidatesAfterDeletionReleasesLifecycleGate() = runBlocking {
        val w = RecurringWorld(this)
        try {
            val location = WalletDataLocation(w.policyDirectory.parentFile!!, "regtest")
            location.database.writeBytes(byteArrayOf())
            val mutation = Mutex(locked = true)
            val waiting = CompletableDeferred<Unit>()
            var bindings = 0
            val binding = { bindings++; check(location.databaseExists); w.binding }
            val store = RecurringStore({ w.raw }, { w.raw = it })
            store.protectPolicies(File("${w.journal.file.path}.policies"), sync = {}, syncFile = {}, binding = binding)
            val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, w.journal, w.controller,
                ensureOpen = {}, binding = binding,
                allowed = { RecurringRuntime.requireAdmission(location, null, false) },
                quote = { error("unused") }, automationGeneration = { null },
                withWalletMutation = { work -> waiting.complete(Unit); mutation.withLock { work() } }), this)
            val trigger = async { executor.execute() }
            withTimeout(5_000) { waiting.await() }
            assertEquals(0, bindings)
            location.directory.deleteRecursively()
            RecurringAutomationAuthority.deleted(location.directory)
            mutation.unlock()
            withTimeout(5_000) { trigger.await() }
            assertNull(executor.error)
            assertEquals(0, w.wallet.calls)
            assertFalse(location.directory.exists())
            location.directory.mkdirs(); location.database.writeBytes(byteArrayOf())
            assertTrue(RecurringAutomationAuthority(location.directory) { file, bytes -> durableRecurringWrite(file, bytes) {} }.allowed())
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }

}
