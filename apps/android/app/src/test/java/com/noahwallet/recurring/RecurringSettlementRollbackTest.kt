package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringSettlementRollbackTest {
    @Test fun rollbackMismatchMustStillAcknowledgeProvenPaidReceipt() = runBlocking {
        val w = RecurringWorld(this); w.add(); val active = w.raw
        w.beforeJournalWrite = { if (it.contains("\"consumed\":true")) w.sendJournal.fail = true }
        w.executor.execute()
        assertEquals(1, w.wallet.calls)
        assertTrue(w.controller.locked)
        w.beforeJournalWrite = {}; w.sendJournal.fail = false
        w.raw = active
        assertEquals("needs_attention", w.schedule!!.status)
        w.executor.checkStatus("rent")
        assertTrue(w.journal.load().getValue("rent").consumed)
        assertFalse("policy mismatch must not retain an already paid Send lock", w.controller.locked)
    }
}
