package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecurringRound3RegressionTest {
    @Test fun confirmedSettlementDuringSuspensionRemainsResumable() = runBlocking {
        val w = RecurringWorld(this)
        try {
            w.add()
            val authority = RecurringAutomationAuthority(w.policyDirectory) { file, bytes -> durableRecurringWrite(file, bytes) {} }
            w.wallet.payBlock = {
                authority.suspension(true)
                SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess)
            }
            w.executor.execute()
            assertEquals(1, w.wallet.calls)
            assertTrue(w.journal.load().getValue("rent").consumed)
            authority.resume()
            assertEquals("Confirmed payment must not acquire a permanent uncertainty latch", "active", w.schedule!!.status)
            assertEquals(1, w.schedule!!.occurrencesPaid)
            assertTrue(w.journal.load().getValue("rent").policyApplied)
            assertFalse(w.controller.locked)
            val settled = w.schedule
            w.executor.checkStatus("rent"); w.executor.execute(manual = true)
            assertEquals(settled, w.schedule)
            assertEquals(1, w.wallet.calls)
            w.executor.resume("rent")
            assertEquals("active", w.schedule!!.status)
        } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
    }

    @Test fun suspensionSettlementRetainsGenuinePauseAndFailureLatches() = runBlocking {
        for (status in listOf("paused", "needs_attention")) {
            val w = RecurringWorld(this)
            try {
                w.add()
                val authority = RecurringAutomationAuthority(w.policyDirectory) { file, bytes -> durableRecurringWrite(file, bytes) {} }
                w.wallet.payBlock = {
                    w.store.upsert(w.schedule!!.copy(status = status, consecutiveFailures = 3, lastError = "Policy stopped", retryNotBefore = 5000))
                    authority.suspension(true)
                    SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess)
                }
                w.executor.execute(); authority.resume()
                assertEquals(status, w.schedule!!.status)
                assertEquals(3, w.schedule!!.consecutiveFailures)
                assertEquals("Policy stopped", w.schedule!!.lastError)
                assertEquals(5000L, w.schedule!!.retryNotBefore)
                assertEquals(1, w.schedule!!.occurrencesPaid)
                assertTrue(w.journal.load().getValue("rent").consumed)
                w.executor.execute(manual = true)
                assertEquals(1, w.wallet.calls)
            } finally { w.policyDirectory.parentFile!!.deleteRecursively() }
        }
    }
}
