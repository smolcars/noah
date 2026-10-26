package com.noahwallet.ui.send

import com.noahwallet.send.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Review's Arrives row: an Ark-funded on-chain send shows the round's confirmations, whatever fine print the review carries. */
class SendArrivesTest {
    private val address = "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"
    private fun model(scope: CoroutineScope) =
        SendModel(controller(FakeSendWallet(), TestJournal(), scope), "regtest", { error("no LNURL here") }, { FakeSendWallet.quote }, {}, scope)
    private fun SendModel.arrives(needed: Long = 2) = arrives(progress.selectedRail!!, source, needed)

    @Test fun anArkFundedOnchainReviewShowsItsConfirmations() = runBlocking {
        // Max from the Ark balance.
        val max = model(this).apply { arkBalance = 10_000 }
        max.maxSend(); max.source = SendSource.offchain; max.continueStage(); max.setRecipient(address); max.continueStage()
        assertEquals(SendStage.review, max.stage); assertTrue(max.payment!!.max)
        assertEquals("After {count} confirmations" to 2L, max.arrives())
        // A request whose own amount replaces the typed one: the review also shows the invoice-amount note.
        val invoice = model(this).apply { arkBalance = 10_000 }
        invoice.amount = "700"; invoice.importRecipient("bitcoin:$address?amount=0.000015", stay = true)
        assertEquals(SendStage.review, invoice.stage); assertEquals(1_500L, invoice.payment!!.amount); assertNotNull(invoice.parsed.amount)
        assertEquals(SendSource.offchain, invoice.payment!!.source)
        assertEquals("After {count} confirmations" to 2L, invoice.arrives())
        // Regtest's setting is one.
        assertEquals("After one confirmation" to null, invoice.arrives(needed = 1))
    }
}
