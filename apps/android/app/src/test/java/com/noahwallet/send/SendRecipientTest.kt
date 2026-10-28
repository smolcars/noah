package com.noahwallet.send

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SendRecipientTest {
    private fun model(scope: CoroutineScope, resolve: suspend (String) -> SendRecipientRoute = { route(it) },
        quote: suspend (SendPayment) -> SendQuote = { FakeSendWallet.quote }, discard: suspend (SendQuote) -> Unit = {}) =
        SendModel(controller(FakeSendWallet(), TestJournal(), scope), "regtest", resolve, quote, discard, scope)
    private fun route(destination: String) =
        SendRecipientRoute(destination, 1000, 10_000_000, 100, "old-ark", "https://example.com/callback".toHttpUrl(), "metadata")
    @Test fun modelRetainsTypedFailureArgumentsForDisplay() = runBlocking {
        val m = model(this)
        m.setRecipient("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
        m.acceptRecipient()
        assertEquals(SendDestinationError.NetworkMismatch("regtest", "mainnet"), m.destinationError)
        assertFalse(m.progress.recipientConfirmed)
        m.setRecipient("a@example.com")
        assertNull(m.destinationError)
    }
    @Test fun destinationAssignmentClearsEveryDerivedValueSynchronously() = runBlocking {
        val m = model(this)
        m.setRecipient("a@example.com")
        m.parsed = SendDestination("lnurl", 500.toBigDecimal())
        m.amount = "500"
        m.route = route(m.destination)
        m.comment = "old"
        m.source = SendSource.offchain
        m.setRecipient("b@example.com")
        assertNull(m.route)
        assertNull(m.quote)
        assertNull(m.payment)
        assertNull(m.parsed.destinationType)
        assertNull(m.source)
        assertEquals("", m.amount)
        assertEquals("", m.comment)
        assertFalse(m.showReview)
    }
    @Test fun lateRecipientResultCannotAttachToNewRecipient() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val m = model(this, resolve = { gate.await(); route(it) })
        m.setRecipient("a@example.com")
        val job = launch { m.previewRecipient() }
        yield()
        m.setRecipient("b@example.com")
        gate.complete(Unit)
        job.join()
        assertNull(m.route)
    }
    @Test fun backgroundDuringRecipientResolutionCannotStartPreparation() = runBlocking {
        val gate = CompletableDeferred<Unit>(); var quotes = 0
        val m = model(this, resolve = { gate.await(); route(it) }, quote = { quotes++; chainQuote })
        m.amount = "1000"; m.arkBalance = 10000; m.setRecipient("a@example.com")
        val job = launch { m.acceptRecipient() }; yield(); m.discardReview(); gate.complete(Unit); job.join()
        assertEquals(0, quotes); assertNull(m.quote); assertFalse(m.showReview)
    }
    @Test fun nonLnurlCannotReusePreviouslyResolvedArkRoute() = runBlocking {
        var reviewed: SendPayment? = null
        val m = model(this, quote = { reviewed = it; FakeSendWallet.quote }); m.amount = "1000"; m.arkBalance = 10000
        m.setRecipient("a@example.com"); m.route = route(m.destination); m.parsed = SendDestination("lnurl")
        m.setRecipient("tark1pwh9vsmezqqpjy9akejayl2vvcse6he97rn40g84xrlvrlnhayuuyefrp9nse2yspqqkm2rpd")
        m.parsed = SendDestination("ark"); m.route = route("a@example.com"); m.progress = m.progress.copy(selectedRail = SendRail.ark)
        m.review(); assertEquals(m.destination, reviewed!!.destination); assertEquals("ark", reviewed.method)
    }
    @Test fun exitBackgroundRecipientResetAndCancellationDiscardLatePreparation() = runBlocking {
        for (reason in listOf("exit", "background", "recipient", "reset", "cancel")) {
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); val discarded = mutableListOf<SendQuote>()
            val m = model(this, quote = { withContext(NonCancellable) { entered.complete(Unit); gate.await(); chainQuote } }, discard = { discarded.add(it) })
            m.amount = "1000"
            m.setRecipient("recipient")
            m.parsed = SendDestination("onchain")
            m.source = SendSource.onchain
            m.progress = m.progress.copy(selectedRail = SendRail.onchain)
            val job = launch { m.review() }; entered.await()
            when (reason) { "recipient" -> m.setRecipient("new"); "reset" -> m.reset(); "cancel" -> job.cancel(); else -> m.discardReview() }
            gate.complete(Unit); job.join()
            // withContext's prompt cancellation may drop a fake's result; live session owns that cleanup.
            if (reason != "cancel") assertEquals(listOf(chainQuote), discarded)
            assertNull(m.quote); assertNull(m.payment); assertFalse(m.showReview); assertFalse(m.busy); assertNull(m.error)
        }
    }
    @Test fun stalePreparationErrorDoesNotChangeNewFlow() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val m = model(this, quote = { gate.await(); error("late") })
        m.amount = "1000"
        m.setRecipient("recipient")
        m.parsed = SendDestination("onchain")
        m.source = SendSource.onchain
        m.progress = m.progress.copy(selectedRail = SendRail.onchain)
        val job = launch { m.review() }; yield(); m.reset(); gate.complete(Unit); job.join(); assertNull(m.error); assertEquals(SendStage.amount, m.stage)
    }
    @Test fun maxUsesOnlyConfirmedSourceBalance() = runBlocking {
        val m = model(this); m.maxSend(); m.source = SendSource.onchain; m.onchainBalance = 0
        assertFalse(SendSource.onchain in m.sources); m.continueStage(); assertFalse(m.progress.sourceConfirmed)
        m.onchainBalance = 2000; assertTrue(SendSource.onchain in m.sources)
    }
    @Test fun repeatPrefillHasNoTrustedRouteAndMustRevalidate() = runBlocking {
        val m = model(this); m.prefill(com.noahwallet.history.RepeatPayment("alice@example.com", "note", "SATS", "1000", 1000), "USD")
        assertEquals(SendStage.recipient, m.stage); assertNull(m.route); assertNull(m.quote); assertFalse(m.progress.recipientConfirmed)
    }
    @Test fun lnurlValidatesExactAmountNetworkAndExpiry() {
        val value = invoice()
        assertTrue(runCatching { SendRecipientService.validateInvoice(value, 1000, "regtest") }.isFailure)
        assertTrue(runCatching { SendRecipientService.validateInvoice(value, 1000, "mainnet") }.isFailure) // amountless/expired fixture
        assertNull(SendRecipientService.suggestionQuery("lnbc1anything", "example.com"))
        assertEquals("al", SendRecipientService.suggestionQuery("al", "example.com"))
    }
    @Test fun suggestionsNeverIncludeOwnAddress() {
        assertEquals(listOf("bob@example.com", "carol@example.com"),
            SendRecipientService.withoutOwnAddress(listOf("bob@example.com", "ALICE@Example.com", "carol@example.com"), "alice@example.com"))
        assertEquals(listOf("bob@example.com"), SendRecipientService.withoutOwnAddress(listOf("bob@example.com"), null))
    }
}
