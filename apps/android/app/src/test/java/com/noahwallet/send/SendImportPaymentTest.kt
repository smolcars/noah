package com.noahwallet.send

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** A recent recipient's shortcut or an assistant's Pay fills Send in, at most up to its review; only holding sends. */
class SendImportPaymentTest {
    private val address = "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"
    private fun model(scope: CoroutineScope, reviewed: MutableList<SendPayment> = mutableListOf()) =
        SendModel(controller(FakeSendWallet(), TestJournal(), scope), "regtest", { error("no LNURL here") }, { reviewed += it; FakeSendWallet.quote },
            {}, scope).apply { arkBalance = 10_000 }

    @Test fun aRecipientAndAmountStopAtTheReview() = runBlocking {
        val reviewed = mutableListOf<SendPayment>()
        val m = model(this, reviewed)
        m.amount = "42"; m.comment = "left over"
        m.importPayment(address, 700)
        assertEquals(SendStage.review, m.stage)
        assertTrue(m.showReview)
        assertEquals(listOf(SendPayment(address, "onchain", 700, source = SendSource.offchain)), reviewed)
        // Reviewed, never sent: nothing is in flight until the user holds to send.
        assertNull(m.controller.attempt); assertFalse(m.controller.locked)
    }

    @Test fun aRecipientAloneWaitsOnTheKeypad() = runBlocking {
        val reviewed = mutableListOf<SendPayment>()
        val m = model(this, reviewed)
        m.amount = "42"
        m.importPayment(address, null)
        assertEquals(SendStage.amount, m.stage)
        assertEquals(address, m.destination); assertEquals("", m.amount)
        assertTrue(m.progress.recipientConfirmed)
        assertTrue(reviewed.isEmpty()); assertNull(m.quote)
    }

    @Test fun aRecipientSendCannotPayShowsTodaysError() = runBlocking {
        val m = model(this)
        m.importPayment("not a destination", 700)
        assertNotNull(m.error); assertNull(m.quote)
        assertFalse(m.progress.recipientConfirmed)
    }
}
