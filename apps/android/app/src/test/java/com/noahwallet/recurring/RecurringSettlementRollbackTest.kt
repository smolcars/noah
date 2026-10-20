package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringSettlementRollbackTest {
    @Test fun rollbackMismatchMustStillAcknowledgeProvenPaidReceipt() = runBlocking {
        val w = RecurringWorld(this); w.add(); val active = w.raw
        w.beforeJournalWrite = { if (it.contains("\"outcome\":\"succeeded\"")) w.sendJournal.fail = true }
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
    @Test fun consumedReceiptCanBeAcknowledgedWithLatchedPolicyWriteFailure() = runBlocking {
        val w = RecurringWorld(this); w.add()
        w.beforeJournalWrite = { if (it.contains("\"outcome\":\"succeeded\"")) { w.sendJournal.fail = true; w.mmkvFails = true } }
        w.executor.execute()
        assertTrue(w.controller.locked)
        assertFalse(w.journal.load().getValue("rent").historyApplied)
        assertTrue(runCatching { w.store.load() }.isFailure)
        w.beforeJournalWrite = {}; w.sendJournal.fail = false; w.mmkvFails = false
        w.executor.checkStatus("rent")
        assertFalse(w.controller.locked)
        assertEquals(1, w.wallet.calls)
    }
}
