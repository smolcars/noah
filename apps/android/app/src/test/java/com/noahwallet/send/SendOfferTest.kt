package com.noahwallet.send

import com.noahwallet.fixtures.SharedFixtures
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** BOLT12 offers that fix their amount (ffi-phase-b.md row 12): bark's parser supplies the amount, the quote enforces it. */
class SendOfferTest {
    private val offer = SharedFixtures.load("send-utils").map { it.input.jsonObject.getValue("destination").jsonPrimitive.content }.first { it.startsWith("lno1") }
    private fun request(amount: ULong?, vararg errors: PaymentMethodParsingError): PaymentRequest {
        val option = PaymentOption(PaymentMethod("offer", offer), errors.toList(), amount == null, false)
        return PaymentRequest(amount, null, null, listOf(option), option.takeIf { errors.isEmpty() })
    }
    private val fiatPriced: suspend (String) -> PaymentRequest = { throw BarkError("unsupported payment request (fiat-priced BOLT12 offer?)", BarkErrorCode.INVALID_PAYMENT_REQUEST) }
    private fun model(scope: CoroutineScope, parse: suspend (String) -> PaymentRequest, quote: suspend (SendPayment) -> SendQuote) =
        SendModel(controller(FakeSendWallet(), TestJournal(), scope), "regtest", { error("no LNURL here") }, quote, {}, scope,
            offerAmount = { SdkSendWallet.offerAmount(it, parse) }).apply { arkBalance = 10_000 }

    @Test fun aFixedOfferLocksTheKeypadAndReviewsItsAmount() = runBlocking {
        for (typed in listOf("", "700")) {
            val reviewed = mutableListOf<SendPayment>()
            val m = model(this, { request(1000u) }) { reviewed += it; FakeSendWallet.quote }
            m.amount = typed
            m.importRecipient(offer, stay = true)
            assertEquals(typed, SendStage.review, m.stage)
            assertFalse(m.parsed.isAmountEditable)
            assertEquals("1000", m.amount)
            assertEquals(listOf(SendPayment(offer, "offer", 1000)), reviewed)
            // Another recipient unlocks the keypad again.
            m.setRecipient("bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22")
            assertEquals("", m.amount); assertTrue(m.parsed.isAmountEditable)
        }
        // Recipient-first from To: the same.
        val reviewed = mutableListOf<SendPayment>()
        val m = model(this, { request(1000u) }) { reviewed += it; FakeSendWallet.quote }
        m.recipientFirst(); m.setRecipient(offer); m.continueStage()
        assertEquals(SendStage.review, m.stage)
        assertEquals(listOf(SendPayment(offer, "offer", 1000)), reviewed)
    }

    @Test fun theQuoteRefusesAnotherAmountForAFixedOffer() = runBlocking {
        val fixed: suspend (String) -> PaymentRequest = { request(1000u) }
        SdkSendWallet.requireOfferAmount(SendPayment(offer, "offer", 1000), fixed)
        for (amount in listOf(999L, 1001L)) {
            val failure = runCatching { SdkSendWallet.requireOfferAmount(SendPayment(offer, "offer", amount), fixed) }.exceptionOrNull()
            assertEquals(SendFailure.Reason.invalid, (failure as SendFailure).reason)
        }
        // An offer without an amount takes any amount; other methods never reach bark's parser.
        SdkSendWallet.requireOfferAmount(SendPayment(offer, "offer", 700)) { request(null) }
        SdkSendWallet.requireOfferAmount(arkPayment) { error("not parsed") }
    }

    @Test fun anOfferForAnotherNetworkIsRefused() = runBlocking {
        var quotes = 0
        val m = model(this, { request(1000u, PaymentMethodParsingError.NetworkMismatch) }) { quotes++; FakeSendWallet.quote }
        m.amount = "700"
        m.importRecipient(offer, stay = true)
        assertEquals(SendStage.amount, m.stage)
        assertEquals("Unable to resolve this recipient. Please check the address.", m.error)
        assertFalse(m.progress.recipientConfirmed)
        assertEquals(0, quotes)
        assertTrue(runCatching { SdkSendWallet.requireOfferAmount(SendPayment(offer, "offer", 1000)) { request(1000u, PaymentMethodParsingError.NetworkMismatch) } }.exceptionOrNull() is SendFailure)
    }

    @Test fun anOfferBarkCannotParseKeepsTheTypedAmount() = runBlocking {
        val reviewed = mutableListOf<SendPayment>()
        val m = model(this, fiatPriced) { reviewed += it; FakeSendWallet.quote }
        m.amount = "700"
        m.importRecipient(offer, stay = true)
        // Today's path: the keypad stays open until Next, then the typed amount is reviewed.
        assertEquals(SendStage.amount, m.stage)
        assertTrue(m.parsed.isAmountEditable); assertTrue(m.progress.recipientConfirmed)
        m.continueStage()
        assertEquals(SendStage.review, m.stage)
        assertEquals(listOf(SendPayment(offer, "offer", 700)), reviewed)
        SdkSendWallet.requireOfferAmount(SendPayment(offer, "offer", 700), fiatPriced)
        // Only bark's parse failure means "no amount": any other error refuses the offer.
        val other = runCatching { SdkSendWallet.offerAmount(offer) { throw BarkError("persistence", BarkErrorCode.PERSISTENCE) } }.exceptionOrNull()
        assertEquals(BarkErrorCode.PERSISTENCE, (other as BarkError).code)
    }
}
