package com.noahwallet.send

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Next with 0 shows the amount error; a valid amount clears it at once and a destination error stays (send-receive.md, Amount). */
class SendAmountErrorTest {
    private fun model(scope: CoroutineScope) =
        SendModel(controller(FakeSendWallet(), TestJournal(), scope), "regtest", { error("no LNURL here") }, { FakeSendWallet.quote }, {},
            scope).apply { arkBalance = 10_000 }

    @Test fun aValidAmountClearsTheZeroAmountError() = runBlocking {
        val m = model(this)
        m.amount = "0"; m.continueStage()
        assertEquals("Enter an amount greater than zero.", m.error)
        m.amount = "0"
        assertEquals("Enter an amount greater than zero.", m.error)
        m.amount = "5"
        assertNull(m.error)
        // Back to zero, it waits for Next again.
        m.amount = ""
        assertNull(m.error)
        assertEquals(SendStage.amount, m.stage)
    }

    @Test fun aDestinationErrorStaysWhileTyping() = runBlocking {
        val m = model(this)
        m.importRecipient("not a destination", stay = true)
        val shown = m.error
        assertNotNull(shown)
        m.amount = "5"
        assertEquals(shown, m.error)
    }
}
