package com.noahwallet.send

import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SendLightningTest {
    private fun payment() = SendPayment(invoice(), "lightning", 1000)
    private fun pending(amount: Long = 1000, fee: Long = 83) =
        LightningSend(PaymentEncoding.invoice(invoice())!!.hash, emptyList(), 1u, invoice(), amount.toULong(), fee.toULong(), 1u, false)
    @Suppress("LongParameterList") // Fixture builder: each parameter is a named, defaulted field of the model it builds.
    private suspend fun submit(state: LightningSendStatus, movements: List<Movement> = emptyList(), checkpoint: SendCheckpoint? = null,
        quote: SendQuote = FakeSendWallet.quote.copy(fee = 83, total = 1083), check: suspend (String) ->
        LightningSendStatus = { LightningSendStatus.Unknown }, pay: suspend () -> LightningSendStatus): LightningSendStatus =
        SendLightningSubmission.submit(payment(), state, movements, checkpoint, quote, check, pay)
    @Test fun sendsWaitWithAnExplicitSixtySecondServerRetry() {
        assertEquals(LightningSendOptions(wait = true, retryForSecs = 60u), SendLightningSubmission.options)
    }
    @Test fun checkpointNeverFallsThroughToPay() = runBlocking {
        var pays = 0
        for (amount in listOf(1000L, 2000L)) {
            val result = runCatching { submit(LightningSendStatus.InProgress(pending(amount))) { pays++; LightningSendStatus.Unknown } }
            assertEquals(amount, (result.exceptionOrNull() as SendExistingLightning).progress.amount)
        }
        assertEquals(0, pays)
    }
    @Test fun matchingCheckpointChecksOnlyAndDisappearanceStaysUncertain() = runBlocking {
        val checkpoint = SendCheckpoint(pending()); var pays = 0; var checks = 0
        submit(LightningSendStatus.InProgress(pending()), checkpoint = checkpoint, check = { checks++; LightningSendStatus.Unknown }) {
            pays++
            LightningSendStatus.Unknown
        }
        assertEquals(1, checks); assertEquals(0, pays)
        assertTrue(runCatching { submit(LightningSendStatus.Unknown, checkpoint = checkpoint) {
            pays++
            LightningSendStatus.Unknown
        } }.exceptionOrNull() is SendFailure)
        assertEquals(0, pays)
    }
    @Test fun canonicalIdentityAmountFeeTotalRevocationAndMovementAreBound() = runBlocking {
        val checkpoint = SendCheckpoint(pending()); val p = payment(); val q = FakeSendWallet.quote.copy(fee = 83, total = 1083)
        assertTrue(checkpoint.matches(p.destination.uppercase(), p, q))
        val variants = listOf(checkpoint.copy(invoice = "foreign"), checkpoint.copy(paymentHash = "foreign"), checkpoint.copy(amount = 2000),
            checkpoint.copy(fee = 84), checkpoint.copy(hasFailedRevocation = true), checkpoint.copy(amount = Long.MAX_VALUE))
        for (variant in variants) assertFalse(variant.matches(p.destination, p, q))
        assertFalse(checkpoint.matches(p.destination, p, q.copy(total = 1082)))
        var checks = 0
        val changed = pending().copy(movementId = 2u)
        assertTrue(runCatching { checkpoint.advance(p, q, LightningSendStatus.InProgress(changed)) {
            checks++
            LightningSendStatus.Unknown
        } }.exceptionOrNull() is SendExistingLightning)
        assertEquals(0, checks)
    }
    @Test fun adoptionIsJournaledBeforeCheckAndMismatchRemainsLockedAcrossRestart() = runBlocking {
        val journal = TestJournal(); val wallet = FakeSendWallet(); val checkpoint = SendCheckpoint(pending(2000))
        wallet.prepareBlock = { SendSnapshot(walletIdentity = wallet.owner, checkpoint = checkpoint) }
        wallet.payBlock = { assertEquals(checkpoint, journal.attempt!!.snapshot.checkpoint); throw SendExistingLightning(SendLightningProgress(2000, 83)) }
        val subject = controller(wallet, journal, this); subject.submit(payment(), FakeSendWallet.quote); coroutineContext.job.children.toList().joinAll()
        assertEquals(2000L, subject.attempt!!.existingLightning!!.amount)
        val restarted = controller(wallet, journal, this)
        restarted.reloadForWalletChange()
        assertEquals(SendController.State.uncertain, restarted.state)
        assertEquals(2000L, restarted.attempt!!.existingLightning!!.amount)
    }
    @Test fun alreadyPaidShowsOnlyProvableNumbersAndMissingMovementCannotFabricateReceipt() = runBlocking {
        val hash = PaymentEncoding.invoice(invoice())!!.hash; var pays = 0
        val paid = LightningSendStatus.Paid(hash, "proof")
        // An amountless invoice: the movement can't say which attempt's amount and fee it holds.
        val result = runCatching { submit(paid, listOf(movement(amount = 2000))) { pays++; LightningSendStatus.Unknown } }
        assertEquals(0L, (result.exceptionOrNull() as SendAlreadyPaid).receipt.amount)
        assertNull((result.exceptionOrNull() as SendAlreadyPaid).receipt.fee)
        // An invoice with its own amount: the movement's.
        val fixed = runCatching { SendLightningSubmission.submit(SendPayment(decoderInvoice("10u"), "lightning", 1000), paid, listOf(movement()), null,
            FakeSendWallet.quote.copy(fee = 83, total = 1083), { LightningSendStatus.Unknown }) { pays++; LightningSendStatus.Unknown } }
        assertEquals(1000L, (fixed.exceptionOrNull() as SendAlreadyPaid).receipt.amount)
        assertEquals(83L, (fixed.exceptionOrNull() as SendAlreadyPaid).receipt.fee)
        assertTrue(runCatching { submit(paid) { pays++; LightningSendStatus.Unknown } }.exceptionOrNull() is SendFailure)
        assertEquals(0, pays)
    }
    @Test fun unknownPendingAndOfferWithAnyCheckpointBlockPay() = runBlocking {
        var pays = 0
        assertTrue(runCatching { submit(LightningSendStatus.Unknown, listOf(movement(status = "pending", amount = 2000))) {
            pays++
            LightningSendStatus.Unknown
        } }.exceptionOrNull() is SendFailure)
        assertTrue(runCatching { SendLightningSubmission.offer(listOf(pending())) { pays++; LightningSendStatus.Unknown } }.exceptionOrNull() is SendFailure)
        assertEquals(0, pays)
    }
    @Test fun settledCheckpointRequiresRealExactMovementAndReviewedBound() {
        val checkpoint = SendCheckpoint(pending()); val p = payment(); val q = FakeSendWallet.quote.copy(fee = 83, total = 1083)
        val paid = LightningSendStatus.Paid(checkpoint.paymentHash, "proof")
        assertEquals(1000L, checkpoint.receipt(paid, listOf(movement()), p, q).amount)
        for (movements in listOf(emptyList(), listOf(movement(amount = 2000)), listOf(movement(), movement(id = 2u))))
            assertTrue(runCatching { checkpoint.receipt(paid, movements, p, q) }.isFailure)
        assertTrue(runCatching { checkpoint.receipt(paid, listOf(movement()), p, q.copy(fee = 82)) }.isFailure)
    }
}
