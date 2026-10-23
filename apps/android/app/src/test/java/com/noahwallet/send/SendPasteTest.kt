package com.noahwallet.send

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Paste on Amount goes through Scan's import, but a recipient it can't accept leaves you on Amount with today's error. */
class SendPasteTest {
    private fun model(scope: CoroutineScope, quote: suspend (SendPayment) -> SendQuote = { FakeSendWallet.quote }) =
        SendModel(controller(FakeSendWallet(), TestJournal(), scope), "regtest", { error("no LNURL here") }, quote, {}, scope).apply { arkBalance = 10_000 }

    @Test fun anInvoiceWithAnAmountSkipsTheKeypadToReview() = runBlocking {
        for (typed in listOf("", "700")) {
            val reviewed = mutableListOf<SendPayment>()
            val m = model(this) { reviewed += it; FakeSendWallet.quote }
            m.amount = typed
            val invoice = decoderInvoice("10u")
            m.importRecipient(invoice, stay = true)
            assertEquals(typed, SendStage.review, m.stage)
            assertTrue(m.showReview)
            // The invoice sets the amount, over whatever was typed.
            assertEquals("1000", m.amount); assertEquals(1000L, m.sats)
            assertEquals(listOf(SendPayment(invoice, "lightning", 1000)), reviewed)
            assertEquals(invoice, m.destination)
        }
    }

    @Test fun aDestinationWithoutAnAmountFillsTheRecipientAndStaysOnAmount() = runBlocking {
        val m = model(this)
        m.importRecipient("bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22", stay = true)
        assertEquals(SendStage.amount, m.stage)
        assertEquals("bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22", m.destination)
        assertTrue(m.progress.recipientConfirmed)
        assertEquals("recipient-first", m.progress.entry)
        assertNull(m.error); assertNull(m.quote)
        // Typing the amount then carries on to the review.
        m.amount = "1000"; m.continueStage()
        assertEquals(SendStage.review, m.stage)
    }

    @Test fun aTypedAmountWaitsForNextWhenTheRecipientHasNone() = runBlocking {
        val address = "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"
        for (paste in listOf(true, false)) {
            val reviewed = mutableListOf<SendPayment>()
            val m = model(this) { reviewed += it; FakeSendWallet.quote }
            m.amount = "700"
            // Paste stays on Amount itself; Scan imports through To and comes back to the keypad.
            m.importRecipient(address, stay = paste)
            assertEquals("paste=$paste", SendStage.amount, m.stage)
            assertEquals("700", m.amount); assertEquals(address, m.destination)
            assertTrue(m.progress.recipientConfirmed); assertNull(m.quote); assertTrue(reviewed.isEmpty())
            m.continueStage()
            assertEquals(SendStage.review, m.stage)
            assertEquals(listOf(SendPayment(address, "onchain", 700, source = SendSource.offchain)), reviewed)
        }
        // An amount confirmed earlier, back on Amount: the import still waits for Next.
        val m = model(this)
        m.amount = "700"; m.continueStage(); assertEquals(SendStage.recipient, m.stage)
        m.stage = SendStage.amount
        m.importRecipient(address, stay = true)
        assertEquals(SendStage.amount, m.stage); assertNull(m.quote)
    }

    @Test fun anInvalidPasteStaysOnAmountWithTodaysError() = runBlocking {
        var quotes = 0
        for (value in listOf("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", "not a destination")) {
            val m = model(this) { quotes++; FakeSendWallet.quote }
            m.amount = "500"
            m.importRecipient(value, stay = true)
            assertEquals(value, SendStage.amount, m.stage)
            assertNotNull(value, m.error)
            assertFalse(m.progress.recipientConfirmed)
            assertEquals("500", m.amount)
        }
        // The address for another network keeps today's typed error.
        val m = model(this); m.importRecipient("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", stay = true)
        assertEquals(SendDestinationError.NetworkMismatch("regtest", "mainnet"), m.destinationError)
        assertEquals(0, quotes)
        // Scan keeps going to To with it, as today.
        val scanned = model(this); scanned.importRecipient("not a destination")
        assertEquals(SendStage.recipient, scanned.stage); assertNotNull(scanned.error)
    }
}
