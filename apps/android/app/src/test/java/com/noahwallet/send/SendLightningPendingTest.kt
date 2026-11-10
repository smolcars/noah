package com.noahwallet.send

import com.noahwallet.history.HistoryRules
import com.noahwallet.ui.send.SendEnding
import com.noahwallet.ui.send.SendMilestone
import com.noahwallet.ui.send.SendProgressRules
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

/**
 * Paying without waiting (async-payments.md): bark returns the send still in progress, the attempt tracks it from
 * the journal, and only bark's own records end it. Nothing here ever pays twice.
 */
class SendLightningPendingTest {
    private val hash = PaymentEncoding.invoice(invoice())!!.hash
    private val payment = SendPayment(invoice(), "lightning", 1000)
    private val quote = FakeSendWallet.quote.copy(fee = 79, total = 1079)
    private fun send(amount: Long = 1000, movement: UInt? = 5u) =
        LightningSend(hash, emptyList(), movement, invoice(), amount.toULong(), 79u, 1u, false)

    /** SdkSendWallet's path for a send bark took: paying returns it in progress; a check reads bark's state and movements. */
    private inner class Bark : FakeSendWallet() {
        var checkpoint: LightningSend? = send()
        var movement: Movement? = movement(status = "pending", id = 5u).copy(effectiveBalanceSats = -1079)
        val state get() = checkpoint?.let { LightningSendStatus.InProgress(it) } ?: LightningSendStatus.Unknown
        override suspend fun prepare(payment: SendPayment, quote: SendQuote) = SendSnapshot(walletIdentity = owner, invoicePaid = false)
        override suspend fun pay(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot): SendReceipt {
            calls++
            throw SendLightningSubmission.handedOver(checkpoint!!, payment.amount)
        }
        /** Holds a status check inside the reservation until completed. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun reconcile(attempt: SendAttempt): SendResolution {
            gate?.await()
            return attempt.pending?.let { SendReconciliation.pending(attempt, it, state, listOfNotNull(movement)) } ?: SendResolution.Uncertain
        }
        /** bark ending the send a moment after it started. */
        fun finish(status: String, effective: Long) {
            checkpoint = null
            movement = movement!!.copy(status = status, effectiveBalanceSats = effective,
                completedAt = HistoryRules.isoDate(Date(System.currentTimeMillis() + 1_000)))
        }
    }

    private suspend fun CoroutineScope.send(bark: Bark, journal: TestJournal = TestJournal()): SendController {
        val subject = controller(bark, journal, this)
        subject.submit(payment, quote)!!.await()
        return subject
    }

    @Test fun aSendStillPayingIsTrackedThenSent() = runBlocking {
        val bark = Bark(); val journal = TestJournal()
        val subject = send(bark, journal)
        assertEquals(SendController.State.uncertain, subject.state); assertTrue(subject.locked)
        assertEquals(SendCheckpoint(send()), subject.attempt!!.pending)
        assertNull(subject.attempt!!.existingLightning)
        assertEquals(hash, journal.attempt!!.pending!!.paymentHash)
        // Still paying says nothing new.
        subject.checkStatus()
        assertEquals(SendController.State.uncertain, subject.state); assertNotNull(subject.attempt!!.pending)

        bark.finish("successful", -1079)
        subject.checkStatus()
        assertEquals(SendController.State.succeeded, subject.state)
        val receipt = subject.attempt!!.receipt!!
        assertEquals(1000L, receipt.amount); assertEquals(79L, receipt.fee); assertEquals(5u, receipt.movementID)
        assertTrue(receipt.isProven(payment, hash))
        assertNull(subject.attempt!!.pending); assertNull(journal.attempt!!.pending)
        assertEquals(1, bark.calls)
    }

    @Test fun aSendStillPayingThatFailsIsFailed() = runBlocking {
        val bark = Bark()
        val subject = send(bark)
        bark.finish("failed", 0)
        subject.checkStatus()
        assertEquals(SendController.State.failed, subject.state); assertNull(subject.attempt!!.pending)
        assertEquals(1, bark.calls)
    }

    @Test fun aPendingSendIsStillTrackedAfterARestart() = runBlocking {
        val bark = Bark(); val journal = TestJournal()
        send(bark, journal)
        val restarted = controller(bark, journal, this)
        restarted.reloadForWalletChange()
        assertEquals(SendController.State.uncertain, restarted.state); assertEquals(hash, restarted.attempt!!.pending!!.paymentHash)
        bark.finish("successful", -1079)
        restarted.checkStatus()
        assertEquals(SendController.State.succeeded, restarted.state)
        assertEquals(1, bark.calls)
    }

    /**
     * The app stopped (or the call failed) after bark took the payment but before the journal recorded it: the next
     * check finds a send for the reviewed amount where none was in progress at review, and tracks it as its own.
     */
    @Test fun aSendBarkTookBeforeTheAppStoppedIsTracked() {
        val attempt = SendAttempt(payment = payment, paymentHash = hash)
        assertEquals(SendResolution.Pending(SendCheckpoint(send())), SendReconciliation.inProgress(attempt, send()))
        // A send for another amount, or one already shown as someone else's, was never reviewed here.
        assertEquals(SendResolution.InProgress(SendLightningProgress(2000, 79)), SendReconciliation.inProgress(attempt, send(amount = 2000)))
        assertEquals(SendResolution.InProgress(SendLightningProgress(1000, 79)),
            SendReconciliation.inProgress(attempt.copy(existingLightning = SendLightningProgress(1000, 79)), send()))
    }

    /** While the server may still be retrying, a pending send offers no override; once overdue it does. */
    @Test fun overrideWaitsUntilThePaymentIsOverdue() = runBlocking {
        val bark = Bark(); val journal = TestJournal()
        val subject = send(bark, journal)
        assertTrue(subject.attempt!!.isPaying()); assertFalse(subject.canOverride)
        journal.attempt = journal.attempt!!.copy(startedAt = System.currentTimeMillis() - SendLightningSubmission.overdueMillis - 1)
        val later = controller(bark, journal, this)
        later.reloadForWalletChange()
        assertFalse(later.attempt!!.isPaying()); assertTrue(later.canOverride)
    }

    /** A cancelled caller (a screen's refresh ending as the payment settles) is not reported as a failed check. */
    @Test fun aCancelledCheckIsNotAFailure() = runBlocking {
        val bark = Bark()
        val subject = send(bark)
        bark.finish("successful", -1079)
        bark.gate = CompletableDeferred()
        val check = launch(start = CoroutineStart.UNDISPATCHED) { subject.checkStatus() }
        check.cancel(); bark.gate!!.complete(Unit); check.join()
        assertEquals(SendController.State.succeeded, subject.state)
        assertNull(subject.message)
    }

    /** A send bark ended without a record this attempt can read is "may have been sent", never "still sending". */
    @Test fun aSendThatEndedWithoutProofIsUncertain() = runBlocking {
        val bark = Bark()
        val subject = send(bark)
        bark.checkpoint = null; bark.movement = null
        subject.checkStatus()
        assertEquals(SendController.State.uncertain, subject.state); assertNull(subject.attempt!!.pending)
    }

    @Test fun aSendForAnotherAmountIsShownNotTracked() {
        assertTrue(SendLightningSubmission.handedOver(send(amount = 2000), 1000) is SendExistingLightning)
        assertTrue(SendLightningSubmission.handedOver(send(), 1000) is SendLightningPending)
        val checkpoint = SendCheckpoint(send())
        val attempt = SendAttempt(payment = payment, pending = checkpoint)
        for (other in listOf(send(amount = 2000), send(movement = 6u))) {
            assertEquals(SendResolution.InProgress(SendLightningProgress(other.amountSats.toLong(), 79)),
                SendReconciliation.pending(attempt, checkpoint, LightningSendStatus.InProgress(other), emptyList()))
            assertFalse(checkpoint.drives(LightningSendStatus.InProgress(other)))
        }
    }

    @Test fun statusChecksDriveOnlyThisAttemptsSend() {
        val checkpoint = SendCheckpoint(send())
        assertTrue(checkpoint.drives(LightningSendStatus.InProgress(send())))
        // A drive finishes a settlement that was interrupted after bark recorded the payment.
        assertTrue(checkpoint.drives(LightningSendStatus.Paid(hash, "proof")))
        assertFalse(checkpoint.drives(LightningSendStatus.Unknown))
    }

    /** A Lightning address payment gets its receipt on a later check now, so that check owes it the repeat metadata. */
    @Test fun aPaymentProvenOnACheckKeepsItsRepeatMetadata() {
        val address = SendPayment("alice@example.com", "lnurl", 1000, repeatMetadata = """{"noah":{}}""")
        val checkpoint = SendCheckpoint(send())
        val paid = LightningSendStatus.Paid(hash, "proof")
        val movement = movement(id = 5u).copy(effectiveBalanceSats = -1079)
        fun owed(payment: SendPayment, before: Set<UInt> = emptySet()): UInt? {
            val attempt = SendAttempt(payment = payment, pending = checkpoint,
                snapshot = SendSnapshot(before, resolvedInvoice = invoice(), invoicePaid = false))
            return SendReconciliation.repeatMetadata(SendReconciliation.pending(attempt, checkpoint, paid, listOf(movement)), attempt)?.first
        }
        assertEquals(5u, owed(address))
        // Never a movement from before the attempt, a payment without metadata, or one that failed.
        assertNull(owed(address, before = setOf(5u)))
        assertNull(owed(address.copy(repeatMetadata = null)))
        assertNull(SendReconciliation.repeatMetadata(SendResolution.Failed, SendAttempt(payment = address, pending = checkpoint)))
    }

    /** An offer's hash is known only from the send bark made for it. */
    @Test fun aPendingOfferLearnsItsHash() = runBlocking {
        val offer = SendPayment("lno1offer", "offer", 1000)
        val checkpoint = SendCheckpoint(send())
        val receipt = SendReceipt(1000, paymentHash = hash, movementID = 5u, fee = 79, proof = SendReceipt.Proof.actualMovement)
        val journal = TestJournal()
        val wallet = FakeSendWallet().apply {
            payBlock = { throw SendLightningPending(checkpoint) }
            reconciliation = { SendResolution.Paid(receipt) }
        }
        val subject = controller(wallet, journal, this)
        subject.submit(offer, quote)!!.await()
        assertEquals(SendController.State.uncertain, subject.state); assertEquals(checkpoint, subject.attempt!!.pending)
        assertEquals(hash, journal.attempt!!.paymentHash)
        subject.checkStatus()
        assertEquals(SendController.State.succeeded, subject.state); assertEquals(receipt, subject.attempt!!.receipt)
    }

    /** The stage keeps "Sending" while a fresh payment is paying, then shows it pending; a saved one is pending at once. */
    @Test fun aFreshPendingSendStillLooksInFlight() {
        val attempt = SendAttempt(payment = payment, pending = SendCheckpoint(send()))
        val state = SendController.State.uncertain
        assertEquals(SendMilestone.paying, SendProgressRules.milestone(state, attempt, finished = true, refund = null))
        assertEquals(SendEnding.sending, SendEnding.of(state, attempt, null, stillPaying = false))
        assertEquals(SendEnding.pending, SendEnding.of(state, attempt, null, stillPaying = true))
    }
}
