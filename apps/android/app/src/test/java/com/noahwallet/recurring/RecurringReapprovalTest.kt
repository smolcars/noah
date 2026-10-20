package com.noahwallet.recurring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringReapprovalTest {
    @Test fun explicitResumeAndEditRecoverRollbackWithoutLosingConsumption() = runBlocking {
        for (edit in listOf(false, true)) {
            val w = RecurringWorld(this); val original = w.add(); val active = w.raw
            w.executor.execute()
            assertEquals(1, w.wallet.calls)
            w.raw = active
            assertEquals(RecurringPolicyLedger.message, w.schedule!!.lastError)
            if (edit) w.executor.save(original.copy(destination = "new-recipient", amountSat = 500), true)
            else w.executor.resume("rent")
            assertNotEquals(original.nativeRevision, w.schedule!!.nativeRevision)
            assertEquals("active", w.schedule!!.status)
            assertTrue(w.journal.consumed(w.binding, "rent", 1000))
            w.executor.execute(manual = true)
            assertEquals("consumed occurrence must never replay", 1, w.wallet.calls)
        }
    }
}
