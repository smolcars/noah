package com.noahwallet.recurring

import com.noahwallet.send.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecurringSettlementRepeatTest {
    @Test fun repeatedStatusAfterAckFailureMustNotRecordSameOccurrenceTwice() = repeatedStatus(false)
    @Test fun historyAppliedInterruptionDoesNotAdvanceAgain() = repeatedStatus(true)
    private fun repeatedStatus(legacy: Boolean) = runBlocking {
        val w = RecurringWorld(this); w.add()
        w.wallet.payBlock = { error("ambiguous") }
        w.executor.execute()
        assertEquals("needs_attention", w.schedule!!.status)
        w.wallet.reconciliation = { SendResolution.Paid(SendReceipt(1000, proof = SendReceipt.Proof.submitReturnedArkSuccess)) }
        w.beforeJournalWrite = { if (it.contains("\"outcome\":\"succeeded\"")) w.sendJournal.fail = true }
        w.executor.checkStatus("rent")
        assertEquals(1, w.schedule!!.occurrencesPaid)
        assertTrue(w.controller.locked)
        w.beforeJournalWrite = {}
        if (legacy) w.journal.save(w.journal.load().getValue("rent").copy(historyApplied = false, released = false))
        val applied = w.schedule; val notices = w.notifications.toList()
        w.beforeJournalWrite = {}; w.sendJournal.fail = false
        w.executor.checkStatus("rent")
        assertEquals("one paid occurrence must create one history entry", 1, w.schedule!!.occurrencesPaid)
        assertEquals(applied, w.schedule); assertEquals(notices, w.notifications)
        assertTrue(w.journal.load().getValue("rent").policyApplied)
        assertFalse(w.controller.locked); assertEquals(1, w.wallet.calls)
    }

}
