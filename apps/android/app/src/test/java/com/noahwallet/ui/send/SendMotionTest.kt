package com.noahwallet.ui.send

import com.noahwallet.send.*
import com.noahwallet.send.FakeSendWallet
import com.noahwallet.send.TestJournal
import com.noahwallet.send.controller
import io.github.niteshbalusu11.bark.Movement
import org.junit.Assert.*
import org.junit.Test

/** The motion follows the wallet: no phase before its milestone, and endings only on the real outcome. */
class SendMotionTest {
    private fun phases(motion: SendMotion, at: Long = 60_000, reduceMotion: Boolean = false) =
        SendTimeline.started(motion, at, reduceMotion).map { it.phase }

    @Test fun nothingRunsAheadOfTheWallet() {
        // Submitted only: the coin appears and splits, then the motion holds.
        val submitted = SendMotion(SendRoute.ark, 0)
        assertEquals(listOf(SendPhase.appear, SendPhase.split), phases(submitted))
        // Inside the payment call: signing, but no delivery or finale until it succeeds.
        val paying = submitted.reach(SendMilestone.paying, 300)
        assertEquals(listOf(SendPhase.appear, SendPhase.split, SendPhase.sign), phases(paying))
        val lightning = SendMotion(SendRoute.lightning, 0).reach(SendMilestone.paying, 100)
        assertEquals(listOf(SendPhase.appear, SendPhase.split, SendPhase.lock, SendPhase.hold, SendPhase.pay), phases(lightning))
        // On-chain: the block row waits for the transaction, so the drop needs the result.
        val chain = SendMotion(SendRoute.chainArk, 0).reach(SendMilestone.paying, 100)
        assertFalse(SendPhase.drop in phases(chain)); assertFalse(SendPhase.waiting in phases(chain))
    }

    @Test fun aFailureNeverShowsTheKeyOrTheFinale() {
        val base = SendMotion(SendRoute.lightning, 0).reach(SendMilestone.paying, 100)
        val refunded = phases(base.reach(SendMilestone.refunded, 3_000))
        assertTrue(SendPhase.fizzle in refunded && SendPhase.signBack in refunded && SendPhase.back in refunded)
        assertFalse(SendPhase.key in refunded); assertFalse(SendPhase.paid in refunded); assertFalse(SendPhase.finale in refunded)
        val stopped = phases(base.reach(SendMilestone.stopped, 3_000))
        assertEquals(SendPhase.stop, stopped.last())
        assertTrue(stopped.none { it in setOf(SendPhase.key, SendPhase.paid, SendPhase.finale, SendPhase.fizzle, SendPhase.back) })
        // Still pending: the bolt keeps looping, nothing ends.
        val pending = base.reach(SendMilestone.pending, 4_000)
        assertEquals(SendPhase.pay, phases(pending).last())
        assertTrue(SendTimeline.stillPaying(pending, 5_000))
        // Only success unlocks the key, then the finale.
        val paid = phases(base.reach(SendMilestone.sent, 5_000))
        assertTrue(paid.indexOf(SendPhase.key) < paid.indexOf(SendPhase.finale))
        assertFalse(SendPhase.fizzle in paid)
        // A refund outcome on a non-Lightning rail is just a stop.
        assertEquals(SendPhase.stop, phases(SendMotion(SendRoute.ark, 0).reach(SendMilestone.refunded, 100)).last())
    }

    @Test fun aProvenOutcomeRevivesAStoppedMotion() {
        // An uncertain answer stops the motion; a later Check status that proves the payment plays its ending.
        val stopped = SendMotion(SendRoute.ark, 0).reach(SendMilestone.paying, 200).reach(SendMilestone.stopped, 3_000)
        assertEquals(SendMilestone.stopped, stopped.ending)
        assertEquals(SendPhase.stop, phases(stopped).last())
        val sent = stopped.reach(SendMilestone.sent, 20_000)
        assertEquals(SendMilestone.sent, sent.ending)
        val starts = SendTimeline.started(sent, 60_000).associate { it.phase to it.start }
        assertFalse(SendPhase.stop in starts)
        assertEquals(20_000L, starts[SendPhase.deliver]); assertEquals(20_350L, starts[SendPhase.finale])
        assertTrue(SendTimeline.resultShown(sent, 60_000))
        // A refund proven later plays the way back.
        val refunded = SendMotion(SendRoute.lightning, 0).reach(SendMilestone.paying, 100).reach(SendMilestone.stopped, 3_000).reach(SendMilestone.refunded, 15_000)
        assertEquals(SendPhase.back, phases(refunded).last())
        // A proven ending is never replaced by a later stop.
        assertEquals(SendMilestone.sent, SendMotion(SendRoute.ark, 0).reach(SendMilestone.sent, 100).reach(SendMilestone.stopped, 200).ending)
    }

    @Test fun stillPayingAfterTenSecondsOrWhenReportedPending() {
        val paying = SendMotion(SendRoute.lightning, 0).reach(SendMilestone.paying, 100)
        assertFalse(SendTimeline.stillPaying(paying, 9_999))
        assertTrue(SendTimeline.stillPaying(paying, 10_000))
        assertTrue(SendTimeline.resultShown(paying, 10_000))
        assertFalse(SendTimeline.stillPaying(paying.reach(SendMilestone.sent, 3_000), 20_000))
        assertFalse(SendTimeline.stillPaying(SendMotion(SendRoute.ark, 0).reach(SendMilestone.paying, 100), 20_000))
    }

    @Test fun reduceMotionLightningWaitsGoQuiet() {
        val paying = SendMotion(SendRoute.lightning, 0).reach(SendMilestone.paying, 100)
        // The bolt loops while Lightning pays, so the clock keeps ticking with full motion.
        assertNull(SendTimeline.quietAt(paying))
        // Under Reduce Motion the bolt is still: the clock stops after the "Still paying" turn and its fade.
        val quiet = checkNotNull(SendTimeline.quietAt(paying, reduceMotion = true))
        assertTrue(quiet > SendTimeline.stillPayingMillis)
        assertTrue(SendTimeline.stillPaying(paying, quiet, reduceMotion = true))
        assertEquals(SendPhaseLines.of(paying, quiet, true, "₿ 500", null, null, 1) { it.toString() },
            SendPhaseLines.of(paying, 10 * 60_000, true, "₿ 500", null, null, 1) { it.toString() })
        assertEquals(SendTimeline.started(paying, quiet, true), SendTimeline.started(paying, 10 * 60_000, true))
        // A later milestone is a new motion, which ticks again until its own quiet point.
        val pending = paying.reach(SendMilestone.pending, 30_000)
        assertNotNull(SendTimeline.quietAt(pending, reduceMotion = true))
        assertTrue(checkNotNull(SendTimeline.quietAt(paying.reach(SendMilestone.sent, 40_000), reduceMotion = true)) > 40_000)
    }

    @Test fun reduceMotionKeepsTheOrderAndTheGates() {
        val motion = SendMotion(SendRoute.lightning, 0).reach(SendMilestone.paying, 0).reach(SendMilestone.refunded, 100)
        val phases = phases(motion, reduceMotion = true)
        assertTrue(SendPhase.back in phases); assertFalse(SendPhase.key in phases)
        assertTrue(SendTimeline.started(motion, 60_000, true).first { it.phase == SendPhase.back }.start <
            SendTimeline.started(motion, 60_000).first { it.phase == SendPhase.back }.start)
    }

    private fun lightning(state: SendController.State = SendController.State.failed, before: Set<UInt> = setOf(1u, 2u)) =
        SendAttempt(payment = SendPayment("lnbcrt1invoice", "lightning", 1_000), paymentHash = "hash", failed = state == SendController.State.failed,
            snapshot = SendSnapshot(before, invoicePaid = false))
    private fun movement(status: String = "failed", outputs: List<String> = listOf("refund:0"), exited: List<String> = emptyList(),
                         hash: String? = "hash", subsystem: String = "bark.lightning_send", id: UInt = 7u) =
        Movement(id, status, subsystem, "send", "{}", -1_000, 0, 0u, emptyList(), emptyList(), listOf("htlc:0"), outputs, exited,
            "now", "now", null, hash, null, null)

    @Test fun backInYourWalletNeedsARecordedRefund() {
        val failed = SendController.State.failed
        assertEquals(SendRefund.recorded, SendProgressRules.refund(failed, lightning(), listOf(movement())))
        // Failed before leaving the wallet: no revocation outputs.
        assertEquals(SendRefund.none, SendProgressRules.refund(failed, lightning(), listOf(movement(outputs = emptyList()))))
        // Revocation failed and the coins went to unilateral exit.
        assertEquals(SendRefund.none, SendProgressRules.refund(failed, lightning(), listOf(movement(outputs = emptyList(), exited = listOf("htlc:0")))))
        // Another payment's record, a pending one, or no record at all.
        assertEquals(SendRefund.none, SendProgressRules.refund(failed, lightning(), listOf(movement(hash = "other"))))
        assertEquals(SendRefund.none, SendProgressRules.refund(failed, lightning(), listOf(movement(status = "pending"))))
        assertEquals(SendRefund.none, SendProgressRules.refund(failed, lightning(), emptyList()))
        // Not a failure, or not Lightning.
        assertEquals(SendRefund.none, SendProgressRules.refund(SendController.State.uncertain, lightning(), listOf(movement())))
        assertEquals(SendRefund.none, SendProgressRules.refund(failed, SendAttempt(payment = SendPayment("tark1x", "ark", 1_000), paymentHash = "hash", failed = true), listOf(movement())))
        // The ending: "Payment failed … back in your wallet" only with the evidence.
        assertEquals(SendEnding.refunded, SendEnding.of(failed, lightning(), SendRefund.recorded, false))
        assertEquals(SendEnding.notSent, SendEnding.of(failed, lightning(), SendRefund.none, false))
        assertEquals(SendEnding.notSent, SendEnding.of(failed, lightning(), null, false))
    }

    @Test fun aRetriedInvoicesFailureIsNeverBackInYourWallet() {
        val failed = SendController.State.failed
        // bark reused the hash's movement from the failed first attempt: its outputs hold that attempt's refund, not this one's.
        val reused = movement(id = 2u, outputs = listOf("change:0", "refund:0"))
        assertEquals(SendRefund.untied, SendProgressRules.refund(failed, lightning(), listOf(reused)))
        assertEquals(SendRefund.untied, SendProgressRules.refund(failed, lightning(), listOf(reused.copy(outputVtxoIds = emptyList()))))
        // The first attempt's own movement wasn't in its snapshot: its refund is recorded.
        assertEquals(SendRefund.recorded, SendProgressRules.refund(failed, lightning(before = setOf(1u)), listOf(reused)))
        // "Payment failed · That payment didn’t go through", and the motion stops without the way back.
        assertEquals(SendEnding.failed, SendEnding.of(failed, lightning(), SendRefund.untied, false))
        assertEquals(SendMilestone.stopped, SendProgressRules.milestone(failed, lightning(), true, SendRefund.untied))
        assertNull(SendProgressRules.milestone(failed, lightning(), false, SendRefund.untied))
        assertEquals(mapOf(SendMilestone.submitted to 0L, SendMilestone.stopped to 0L), staticMilestones(SendEnding.failed))
        // Off Lightning it's today's "Payment not sent".
        assertEquals(SendEnding.notSent, SendEnding.of(failed, SendAttempt(payment = SendPayment("tark1x", "ark", 1_000), failed = true), SendRefund.untied, false))
    }

    @Test fun milestonesComeOnlyFromTheControllersState() {
        val payment = SendPayment("tark1x", "ark", 500)
        val journaled = SendAttempt(payment = payment)
        val marker = SendAttempt(payment = SendPayment("lnbcrt1x", "lightning", 500), inspectionPending = true)
        assertNull(SendProgressRules.milestone(SendController.State.ready, null, true, null))
        assertEquals(SendMilestone.submitted, SendProgressRules.milestone(SendController.State.submitting, null, false, null))
        assertEquals(SendMilestone.submitted, SendProgressRules.milestone(SendController.State.submitting, marker, false, null))
        assertEquals(SendMilestone.paying, SendProgressRules.milestone(SendController.State.submitting, journaled, false, null))
        assertEquals(SendMilestone.sent, SendProgressRules.milestone(SendController.State.succeeded, journaled, true, null))
        // Between a failed call and its reconciliation the state is uncertain: not an ending yet.
        assertEquals(SendMilestone.paying, SendProgressRules.milestone(SendController.State.uncertain, journaled, false, null))
        assertEquals(SendMilestone.stopped, SendProgressRules.milestone(SendController.State.uncertain, journaled, true, null))
        val existing = SendAttempt(payment = SendPayment("lnbcrt1x", "lightning", 500), existingLightning = SendLightningProgress(500, 3))
        assertEquals(SendMilestone.pending, SendProgressRules.milestone(SendController.State.uncertain, existing, true, null))
        // A failure waits for the refund evidence before it can end the motion.
        assertNull(SendProgressRules.milestone(SendController.State.failed, lightning(), true, null))
        assertEquals(SendMilestone.refunded, SendProgressRules.milestone(SendController.State.failed, lightning(), true, SendRefund.recorded))
        assertEquals(SendMilestone.stopped, SendProgressRules.milestone(SendController.State.failed, lightning(), true, SendRefund.none))
        // An on-chain success needs its transaction for the block row.
        val chain = SendAttempt(payment = SendPayment("bcrt1q", "onchain", 5_000, SendSource.onchain), receipt = SendReceipt(5_000, txid = "ab"))
        assertEquals(SendMilestone.sent, SendProgressRules.milestone(SendController.State.succeeded, chain, true, null))
        assertEquals(SendMilestone.stopped, SendProgressRules.milestone(SendController.State.succeeded, chain.copy(receipt = SendReceipt(5_000)), true, null))
        // A failed broadcast (today's retry) isn't on the network yet: no network motion until the broadcast succeeds.
        val retrying = chain.copy(receipt = SendReceipt(5_000, txid = "ab", broadcastRetryPending = true))
        assertEquals(SendMilestone.stopped, SendProgressRules.milestone(SendController.State.succeeded, retrying, true, null))
        val stopped = SendMotion(SendRoute.chainWallet, 0).reach(SendMilestone.stopped, 1_000)
        assertTrue(phases(stopped).none { it == SendPhase.drop || it == SendPhase.waiting })
        assertTrue(SendTimeline.resultShown(stopped, 60_000))
        val broadcast = stopped.reach(SendProgressRules.milestone(SendController.State.succeeded, chain, true, null)!!, 30_000)
        assertEquals(SendPhase.waiting, phases(broadcast).last())
        assertEquals(mapOf(SendMilestone.submitted to 0L, SendMilestone.stopped to 0L), staticMilestones(SendEnding.chainSent, broadcastPending = true))
        assertEquals(SendMilestone.stopped, SendProgressRules.milestone(SendController.State.storageBlocked, journaled, true, null))
    }

    @Test fun phaseLinesAndBlockRow() {
        val chain = SendMotion(SendRoute.chainArk, 0).reach(SendMilestone.sent, 100)
        val waiting = SendPhaseLines.of(chain, 60_000, false, "₿ 5,000", 921_345, null, 2) { it.toString() }
        assertEquals(PhaseLine("Waiting for block {height}", "921346"), waiting)
        val confirmed = SendPhaseLines.of(chain, 60_000, false, "₿ 5,000", 921_346, 921_346, 2) { it.toString() }
        assertEquals(PhaseLine("Confirmed in block {height}", "921346", true, 1L to 2L), confirmed)
        val done = SendPhaseLines.of(chain, 60_000, false, "₿ 5,000", 921_347, 921_346, 2) { it.toString() }
        assertNull(done!!.suffix)
        assertEquals(0L, BlockRow.confirmations(10, null)); assertEquals(3L, BlockRow.confirmations(12, 10))
        assertEquals(PhaseLine("Locking {amount}", "₿ 500"),
            SendPhaseLines.of(SendMotion(SendRoute.lightning, 0).reach(SendMilestone.paying, 0), 1_200, false, "₿ 500", null, null, 1) { it.toString() })
    }

    @Test fun backToRecipientAsksForTheRailAgain() = kotlinx.coroutines.runBlocking {
        val m = SendModel(controller(FakeSendWallet(), TestJournal(), this), "regtest", { error("unused") }, { FakeSendWallet.quote }, {}, this)
        m.arkBalance = 10_000; m.amount = "500"; m.continueStage()
        m.setRecipient("bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22?ark=tark1pj8a9t8ezqqpfdvjfqfst3xzwxnhq7r5lyzg8hp2g7gqdun2vevz5jaaqd7ry7xezqyp6e3ghflqezh7k5hkwav8m4fahfaaqtnuqhykjjf5tsvwyt309xkctc2n22")
        m.continueStage()
        assertEquals(SendStage.method, m.stage)
        m.progress = m.progress.copy(selectedRail = SendRail.ark, railConfirmed = true); m.continueStage()
        assertEquals(SendStage.review, m.stage)
        m.back(); assertEquals(SendStage.method, m.stage); assertFalse(m.showReview)
        m.back(); assertEquals(SendStage.recipient, m.stage); assertFalse(m.progress.railConfirmed)
        m.continueStage(); assertEquals("continuing from To offers the rails again", SendStage.method, m.stage)
        m.back(); m.back(); assertEquals(SendStage.amount, m.stage); assertEquals("500", m.amount)
    }

    @Test fun recentsComeFromSuccessfulOutgoingHistoryOnly() {
        fun row(id: String, destination: String, direction: String = "outgoing", status: String = "successful") =
            com.noahwallet.history.HistoryTransaction(id, "Lnurl", 1, "2026-10-22T10:00:00Z", direction, destination = destination, movementStatus = status)
        val rows = listOf(row("1", "maya@localhost.com"), row("2", "MAYA@localhost.com"), row("3", "bob@localhost.com", status = "failed"),
            row("4", "lucas@localhost.com", direction = "incoming"), row("5", "not a destination"))
        assertEquals(listOf("maya@localhost.com"), SendWhoRules.recents(rows, "regtest"))
        assertEquals(SendWho("maya", "m"), SendWhoRules.of("maya@localhost.com", "lnurl", "localhost.com"))
        assertEquals(SendWho("maya@getalby.com", "m"), SendWhoRules.of("maya@getalby.com", "lnurl", "localhost.com"))
        assertEquals("Noah user", SendWhoRules.kind("lnurl", "maya@localhost.com", "localhost.com"))
        assertEquals("Bitcoin address", SendWhoRules.kind("onchain", "bcrt1q", "localhost.com"))
    }
}
