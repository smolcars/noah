package com.noahwallet.send

import com.noahwallet.history.HistoryRules
import com.noahwallet.ui.send.SendEnding
import com.noahwallet.ui.send.SendProgressRules
import com.noahwallet.ui.send.SendRefund
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

/**
 * Paying a failed BOLT11 invoice again: bark 0.8 reuses the hash's finished movement, flipping it from failed to
 * successful with its first fee field (async-payments.md, "Reused hashes"). The attempt is bound by bark's paid
 * record from before it began and by the movement finishing after it started, never by the old movement alone.
 */
class SendLightningRetryTest {
    private val hash = PaymentEncoding.invoice(invoice())!!.hash
    private val payment = SendPayment(invoice(), "lightning", 1000)
    private val quote = FakeSendWallet.quote.copy(fee = 79, total = 1079)
    private val longAgo = System.currentTimeMillis() - 3_600_000

    /** The hash's one movement, as bark keeps it across attempts. */
    private fun reused(status: String, effective: Long, completedAt: Long?, fee: ULong = 0u, outputs: List<String> = listOf("change:0", "refund:0")) =
        movement(status = status, id = 5u).copy(effectiveBalanceSats = effective, offchainFeeSats = fee, outputVtxoIds = outputs,
            completedAt = completedAt?.let { HistoryRules.isoDate(Date(it)) })

    /**
     * SdkSendWallet's Lightning path (prepare's snapshot, payInternal's guard and receipt, reconcile) over a scripted
     * bark, since SdkSendWallet itself needs the native wallet. [onPay] is what bark does once paying starts.
     */
    private inner class Bark : FakeSendWallet() {
        var movement: Movement? = null
        var paid = false
        var checkpoint: LightningSend? = null
        var onPay: Bark.() -> Unit = {}
        val state: LightningSendStatus get() = if (paid) LightningSendStatus.Paid(hash, "proof")
            else checkpoint?.let { LightningSendStatus.InProgress(it) } ?: LightningSendStatus.Unknown
        val movements get() = listOfNotNull(movement)
        override suspend fun prepare(payment: SendPayment, quote: SendQuote) =
            SendSnapshot(movements.map { it.id }.toSet(), walletIdentity = owner, invoicePaid = paid)
        override suspend fun pay(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot): SendReceipt {
            val status = SendLightningSubmission.submit(payment, state, movements, snapshot.checkpoint, quote, check = { state }) { calls++; onPay(); state }
            requireSend(status is LightningSendStatus.Paid, SendFailure.Reason.unresolved)
            return when (val result = SendReconciliation.lightningAttempt(movements, hash, payment.amount, snapshot, null)) {
                is SendResolution.Paid -> result.receipt
                is SendResolution.AlreadyPaid -> throw SendAlreadyPaid(result.receipt)
                else -> throw SendFailure(SendFailure.Reason.unresolved)
            }
        }
        override suspend fun reconcile(attempt: SendAttempt): SendResolution {
            (state as? LightningSendStatus.InProgress)?.let { return SendResolution.InProgress(SendLightningProgress(it.send.amountSats.toLong(), it.send.feeSats.toLong())) }
            if (attempt.existingLightning != null && attempt.existingLightning.amount != attempt.payment.amount) return SendResolution.InProgress(attempt.existingLightning)
            return SendReconciliation.ark(if (attempt.existingLightning != null) attempt.copy(snapshot = attempt.snapshot.copy(movementIDs = attempt.snapshot.movementIDs + movements.map { it.id })) else attempt, movements)
        }
        /** bark finishing this attempt a moment after it started, as a real payment does. */
        fun finish(status: String, effective: Long) {
            movement = (movement ?: reused(status, effective, null, fee = 79u, outputs = emptyList()))
                .copy(status = status, effectiveBalanceSats = effective, completedAt = HistoryRules.isoDate(Date(System.currentTimeMillis() + 1_000)))
            paid = status == "successful"
        }
        fun failsAgain() { onPay = { finish("failed", 0); movement = movement!!.copy(outputVtxoIds = movement!!.outputVtxoIds + "refund:1") } }
        fun pays() { onPay = { finish("successful", -1079) } }
    }

    private suspend fun CoroutineScope.send(bark: Bark, journal: TestJournal = TestJournal()): SendController {
        val subject = controller(bark, journal, this)
        subject.submit(payment, quote)!!.await()
        return subject
    }

    @Test fun aRetryAfterOneFailurePaysWithItsRealFee() = runBlocking {
        val bark = Bark().apply { movement = reused("failed", 0, longAgo); pays() }
        val subject = send(bark)
        assertEquals(SendController.State.succeeded, subject.state)
        val receipt = subject.attempt!!.receipt!!
        assertNull(subject.attempt!!.alreadyPaid)
        assertEquals(1000L, receipt.amount); assertEquals(5u, receipt.movementID)
        // bark's numbers: 1,079 left the wallet for 1,000 sent, while the reused fee field still says 0.
        assertEquals(79L, receipt.fee)
        assertEquals(-bark.movement!!.effectiveBalanceSats, receipt.amount + receipt.fee!!)
        assertEquals(0uL, bark.movement!!.offchainFeeSats)
        assertTrue(receipt.isProven(payment, hash))
        assertEquals(1, bark.calls)
    }

    @Test fun aRetryThatFailsAgainIsAFailureWithoutTheRefundLine() = runBlocking {
        val bark = Bark().apply { movement = reused("failed", 0, longAgo); failsAgain() }
        val subject = send(bark)
        assertEquals(SendController.State.failed, subject.state)
        assertNull(subject.attempt!!.receipt)
        // Its coins came back on the reused movement, which can't be tied to this attempt.
        val refund = SendProgressRules.refund(subject.state, subject.attempt, bark.movements)
        assertEquals(SendRefund.untied, refund)
        assertEquals(SendEnding.failed, SendEnding.of(subject.state, subject.attempt, refund, false))
    }

    @Test fun twoFailuresThenSuccess() = runBlocking {
        val bark = Bark()
        // The first attempt makes the movement and fails with a recorded refund.
        bark.onPay = { finish("failed", 0); movement = movement!!.copy(outputVtxoIds = listOf("change:0", "refund:0")) }
        val first = send(bark)
        assertEquals(SendController.State.failed, first.state)
        assertEquals(SendRefund.recorded, SendProgressRules.refund(first.state, first.attempt, bark.movements))
        first.acknowledge(); assertFalse(first.locked)
        // The second reuses it and fails again.
        bark.failsAgain()
        first.submit(payment, quote)!!.await()
        assertEquals(SendController.State.failed, first.state)
        assertEquals(SendRefund.untied, SendProgressRules.refund(first.state, first.attempt, bark.movements))
        first.acknowledge(); assertFalse(first.locked)
        // The third pays.
        bark.pays()
        first.submit(payment, quote)!!.await()
        assertEquals(SendController.State.succeeded, first.state)
        assertEquals(79L, first.attempt!!.receipt!!.fee)
        assertEquals(3, bark.calls)
    }

    @Test fun anInvoicePaidBeforeThisAttemptIsStillAlreadyPaid() = runBlocking {
        val bark = Bark().apply { movement = reused("successful", -1083, longAgo, fee = 83u); paid = true; onPay = { error("never pays twice") } }
        val subject = send(bark)
        assertEquals(SendController.State.alreadyPaid, subject.state)
        assertEquals(83L, subject.attempt!!.alreadyPaid!!.fee)
        assertEquals(0, bark.calls)
        // Bound by the paid record from before the attempt, even with the movement completing afterwards.
        val later = reused("successful", -1083, System.currentTimeMillis() + 1_000, fee = 83u)
        assertTrue(SendReconciliation.lightningAttempt(listOf(later), hash, 1000, SendSnapshot(setOf(5u), invoicePaid = true), longAgo) is SendResolution.AlreadyPaid)
    }

    @Test fun aJournalFromBeforeThePaidFlagGoesByTheMovementsCompletion() = runBlocking {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val startedAt = System.currentTimeMillis() - 60_000
        val current = json.encodeToJsonElement(SendAttempt(walletIdentity = FakeSendWallet().owner, payment = payment, quote = quote, startedAt = startedAt,
            paymentHash = hash, snapshot = SendSnapshot(setOf(5u), walletIdentity = FakeSendWallet().owner))).jsonObject
        val snapshot = current.getValue("snapshot").jsonObject
        val old = json.decodeFromJsonElement<SendAttempt>(JsonObject(current + ("snapshot" to JsonObject(snapshot - "invoicePaid"))))
        assertNull(old.snapshot.invoicePaid)
        fun resolve(movement: Movement) = SendReconciliation.ark(old, listOf(movement))
        // Completed after the attempt started: this attempt paid it, with the fee from the effective balance.
        val paid = resolve(reused("successful", -1079, startedAt + 5_000)) as SendResolution.Paid
        assertEquals(79L, paid.receipt.fee)
        // Completed before: paid before this attempt.
        assertTrue(resolve(reused("successful", -1079, startedAt - 5_000)) is SendResolution.AlreadyPaid)
        // A failure counts only once the movement finished after the attempt started.
        assertEquals(SendResolution.Uncertain, resolve(reused("failed", 0, startedAt - 5_000)))
        assertEquals(SendResolution.Failed, resolve(reused("failed", 0, startedAt + 5_000)))
        // No completion time to go by: nothing is assumed.
        assertEquals(SendResolution.Uncertain, resolve(reused("successful", -1079, null)))
        // Through the controller, after a restart.
        val journal = TestJournal().apply { attempt = old }
        val bark = Bark().apply { movement = reused("successful", -1079, startedAt + 5_000) }
        val restarted = controller(bark, journal, this); restarted.reloadForWalletChange(); restarted.checkStatus()
        assertEquals(SendController.State.succeeded, restarted.state)
        assertEquals(79L, journal.attempt!!.receipt!!.fee)
    }

    @Test fun aRestartMidRetryWhileTheOldMovementIsStillFailedStaysUnresolved() = runBlocking {
        val bark = Bark().apply { movement = reused("failed", 0, longAgo) }
        val journal = TestJournal()
        // The payment call dies before bark touches the movement: the journal keeps the attempt.
        bark.onPay = { error("process died") }
        send(bark, journal)
        val saved = checkNotNull(journal.attempt)
        assertNull(saved.receipt); assertFalse(saved.failed); assertEquals(false, saved.snapshot.invoicePaid)
        assertTrue(5u in saved.snapshot.movementIDs)

        val restarted = controller(bark, journal, this); restarted.reloadForWalletChange()
        assertEquals(SendController.State.uncertain, restarted.state)
        // Nothing yet: the old failure is not this attempt's.
        restarted.checkStatus()
        assertEquals(SendController.State.uncertain, restarted.state); assertFalse(journal.attempt!!.failed)
        // bark resumes the retry: still paying, never "failed" or "already paid".
        bark.checkpoint = LightningSend(hash, emptyList(), 1u, invoice(), 1000u, 79u, 5u, false)
        restarted.checkStatus()
        assertEquals(SendController.State.uncertain, restarted.state)
        assertEquals(SendLightningProgress(1000, 79), journal.attempt!!.existingLightning)
        assertEquals(SendEnding.pending, SendEnding.of(restarted.state, restarted.attempt, null, false))
        // It pays: Sent, with this attempt's receipt and fee.
        bark.checkpoint = null; bark.finish("successful", -1079)
        restarted.checkStatus()
        assertEquals(SendController.State.succeeded, restarted.state)
        assertNull(journal.attempt!!.alreadyPaid)
        assertEquals(79L, journal.attempt!!.receipt!!.fee)
        assertEquals("checking status never pays", 1, bark.calls)
    }

    @Test fun aResumedRetryCheckpointShowsItsOwnFee() {
        val checkpoint = SendCheckpoint(LightningSend(hash, emptyList(), 1u, invoice(), 1000u, 79u, 5u, false))
        val paid = LightningSendStatus.Paid(hash, "proof")
        // The reused movement's fee field says 0; its effective balance took exactly this checkpoint's fee.
        assertEquals(79L, checkpoint.receipt(paid, listOf(reused("successful", -1079, longAgo)), payment, quote).fee)
        // A fee field that agrees is kept, and without that evidence the reading is today's.
        assertEquals(79L, checkpoint.receipt(paid, listOf(reused("successful", -1079, longAgo, fee = 79u)), payment, quote).fee)
        assertEquals(0L, checkpoint.receipt(paid, listOf(reused("successful", -1000, longAgo)), payment, quote).fee)
    }

    @Test fun theReusedMovementMustMatchTheAmountAndShowARealFee() {
        val snapshot = SendSnapshot(setOf(5u), invoicePaid = false)
        // An amountless invoice retried for another amount: bark keeps the first attempt's destination amount.
        assertEquals(SendResolution.Uncertain, SendReconciliation.lightningAttempt(listOf(reused("successful", -2079, longAgo)), hash, 2000, snapshot, null))
        // An effective balance that doesn't cover the amount can't give a fee.
        assertEquals(SendResolution.Uncertain, SendReconciliation.lightningAttempt(listOf(reused("successful", -900, longAgo)), hash, 1000, snapshot, null))
        // A movement made by this attempt keeps today's reading, fee field included.
        val fresh = reused("successful", -1083, longAgo, fee = 83u)
        assertEquals(83L, (SendReconciliation.lightningAttempt(listOf(fresh), hash, 1000, SendSnapshot(invoicePaid = false), null) as SendResolution.Paid).receipt.fee)
        // A still-pending reused movement is nothing yet.
        assertEquals(SendResolution.Uncertain, SendReconciliation.lightningAttempt(listOf(reused("pending", -1079, null)), hash, 1000, snapshot, longAgo))
    }
}
