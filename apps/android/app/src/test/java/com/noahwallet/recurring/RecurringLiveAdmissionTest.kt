package com.noahwallet.recurring

import com.noahwallet.send.SendPayment
import com.noahwallet.send.SendQuote
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.services.storage.WalletDataLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RecurringLiveAdmissionTest(private val initialized: Boolean?) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "initialized={0}")
        fun cases() = listOf(null, false, true).map { arrayOf(it) }
    }
    @Test fun welcomeCallbacksCannotPoisonTheCreatedWallet() = runBlocking {
        val w = RecurringWorld(this)
        try {
            val location = WalletDataLocation(w.policyDirectory.parentFile!!, "regtest")
            w.policyDirectory.deleteRecursively()
            var wallet = PersistedWalletState(isInitialized = initialized)
            val store = RecurringStore({ w.raw }, { w.raw = it })
            val binding = { check(location.databaseExists); w.binding }
            store.protectPolicies(java.io.File("${w.journal.file.path}.policies"), sync = {}, syncFile = {}, binding = binding)
            val executor = RecurringExecutor(RecurringExecutor.Dependencies(store, w.journal, w.controller,
                ensureOpen = {}, binding = binding,
                allowed = { RecurringRuntime.requireAdmission(location, wallet, storageFailed = false) },
                quote = { s -> SendPayment(s.destination, "ark", s.amountSat) to SendQuote(s.amountSat, 0, s.amountSat, walletIdentity = w.binding.wallet) },
                automationGeneration = { null }, now = { 1000 }), this)
            executor.timeChanged(); executor.execute()
            assertNull(executor.error)
            assertFalse(location.directory.exists())
            assertEquals(0, w.wallet.calls)
            location.directory.mkdirs()
            executor.timeChanged() // A directory alone is not a wallet.
            assertTrue(location.directory.listFiles().orEmpty().isEmpty())
            location.database.writeBytes(byteArrayOf())
            if (initialized == false) {
                assertTrue(runCatching { RecurringRuntime.requireAdmission(location, wallet, false) }.isFailure)
                wallet = PersistedWalletState(isInitialized = true)
            }
            RecurringRuntime.requireAdmission(location, wallet, false)
            store.bind(binding())
            assertTrue(RecurringAutomationAuthority(location.directory) { file, bytes -> durableRecurringWrite(file, bytes) {} }.allowed())
            val schedule = RecurringPayment("rent", "Rent", "recipient", "ark", 1000, interval = RecurringInterval("day", 1), startAt = 1000,
                createdAt = 0, updatedAt = 0)
            store.upsert(schedule)
            executor.timeChanged(); executor.execute(); executor.execute(manual = true)
            assertEquals(1, w.wallet.calls)
            assertEquals(1, store.load().schedules.getValue("rent").occurrencesPaid)
            assertTrue(w.journal.load().getValue("rent").consumed)
            assertNull(executor.error)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }
}
