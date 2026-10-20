package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringMoneyPathTest {
    @Test fun suspendedWalletRollbackMustNotAuthorizeRecurring() = runBlocking {
        val w = RecurringWorld(this); w.add(); w.executor.timeChanged()
        
        val location = com.noahwallet.services.storage.WalletDataLocation(w.policyDirectory.parentFile!!, "regtest")
        val session = com.noahwallet.services.wallet.WalletSession("regtest", location,
            inspect = { com.noahwallet.services.wallet.WalletDatabase.Properties("regtest", "wallet-a") },
            opener = { _, _, _, _, _ -> error("unused") })
        val lifecycle = com.noahwallet.services.wallet.WalletLifecycle(session, location,
            setSuspended = { w.allowed = !it }, deregister = {},
            readSeed = { com.noahwallet.services.storage.SecretResult.NotFound }, deleteSeed = {}, clearToken = {},
            clearSettings = {}, clearWidget = {}, sweepScratch = {},
            syncIdentityDirectory = {}, stopAutomation = { w.generation = java.util.UUID.randomUUID().toString() })
        lifecycle.suspend()
        assertFalse(w.allowed)
        // Model accepted MMKV recovery of wallet-storage and automation generation.
        w.allowed = true; w.generation = null
        w.executor.execute()
        assertEquals("wallet suspension must survive MMKV rollback", 0, w.wallet.calls)
    }

    @Test fun durableGenerationRejectsAlreadyAdmittedWorkEvenAfterResumeAndMMKVRollback() = runBlocking {
        for (resume in listOf(false, true)) {
            val w = RecurringWorld(this); w.add()
            w.wallet.prepareBlock = {
                val authority = RecurringAutomationAuthority(w.policyDirectory) { file, bytes -> durableRecurringWrite(file, bytes) {} }
                authority.stop()
                if (resume) authority.resume()
                w.generation = null; w.allowed = true
                w.wallet.snapshot
            }
            w.executor.execute()
            assertEquals(0, w.wallet.calls)
        }
    }
}
