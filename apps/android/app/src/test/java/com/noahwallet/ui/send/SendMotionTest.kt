package com.noahwallet.ui.send

import com.noahwallet.send.*
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

    @Test fun phasesWaitForLateMilestonesAndKeepTheirSpacing() {
        // The wallet answers at 10 s: the ending starts then, and later phases keep their gaps.
        val motion = SendMotion(SendRoute.ark, 0).reach(SendMilestone.paying, 200).reach(SendMilestone.sent, 10_000)
        val starts = SendTimeline.started(motion, 60_000).associate { it.phase to it.start }
        assertEquals(1_150L, starts[SendPhase.sign])
        assertEquals(10_000L, starts[SendPhase.deliver])
        assertEquals(10_350L, starts[SendPhase.finale])
        // Before 10 s, nothing past signing.
        assertEquals(SendPhase.sign, SendTimeline.started(motion, 9_999).last().phase)
        // A fast success still plays every phase at the prototype's pace.
        val fast = SendMotion(SendRoute.ark, 0).reach(SendMilestone.sent, 50)
        assertEquals(2_750L, SendTimeline.started(fast, 60_000).first { it.phase == SendPhase.finale }.start)
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

    @Test fun stillPayingAfterTenSecondsOrWhenReportedPending() {
        val paying = SendMotion(SendRoute.lightning, 0).reach(SendMilestone.paying, 100)
        assertFalse(SendTimeline.stillPaying(paying, 9_999))
        assertTrue(SendTimeline.stillPaying(paying, 10_000))
        assertTrue(SendTimeline.resultShown(paying, 10_000))
        assertFalse(SendTimeline.stillPaying(paying.reach(SendMilestone.sent, 3_000), 20_000))
        assertFalse(SendTimeline.stillPaying(SendMotion(SendRoute.ark, 0).reach(SendMilestone.paying, 100), 20_000))
    }

    @Test fun reduceMotionKeepsTheOrderAndTheGates() {
        val motion = SendMotion(SendRoute.lightning, 0).reach(SendMilestone.paying, 0).reach(SendMilestone.refunded, 100)
        val phases = phases(motion, reduceMotion = true)
        assertTrue(SendPhase.back in phases); assertFalse(SendPhase.key in phases)
        assertTrue(SendTimeline.started(motion, 60_000, true).first { it.phase == SendPhase.back }.start <
            SendTimeline.started(motion, 60_000).first { it.phase == SendPhase.back }.start)
    }

    @Test fun hapticsPlayOncePerMoment() {
        val motion = SendMotion(SendRoute.ark, 0).reach(SendMilestone.sent, 100)
        val started = SendTimeline.started(motion, 60_000)
        val all = SendHaptics.between(started, -1, 60_000)
        assertEquals(listOf(SendHaptics.Kind.light, SendHaptics.Kind.light, SendHaptics.Kind.light, SendHaptics.Kind.success), all)
        assertEquals(all, SendHaptics.between(started, -1, 3_000) + SendHaptics.between(started, 3_000, 60_000))
        assertTrue(SendHaptics.between(started, 60_000, 60_000).isEmpty())
    }

    private fun lightning(state: SendController.State = SendController.State.failed) =
        SendAttempt(payment = SendPayment("lnbcrt1invoice", "lightning", 1_000), paymentHash = "hash", failed = state == SendController.State.failed)
    private fun movement(status: String = "failed", outputs: List<String> = listOf("refund:0"), exited: List<String> = emptyList(),
                         hash: String? = "hash", subsystem: String = "bark.lightning_send") =
        Movement(7u, status, subsystem, "send", "{}", -1_000, 0, 0u, emptyList(), emptyList(), listOf("htlc:0"), outputs, exited,
            "now", "now", null, hash, null, null)

    @Test fun backInYourWalletNeedsARecordedRefund() {
        val failed = SendController.State.failed
        assertTrue(SendProgressRules.refundRecorded(failed, lightning(), listOf(movement())))
        // Failed before leaving the wallet: no revocation outputs.
        assertFalse(SendProgressRules.refundRecorded(failed, lightning(), listOf(movement(outputs = emptyList()))))
        // Revocation failed and the coins went to unilateral exit.
        assertFalse(SendProgressRules.refundRecorded(failed, lightning(), listOf(movement(outputs = emptyList(), exited = listOf("htlc:0")))))
        // Another payment's record, a pending one, or no record at all.
        assertFalse(SendProgressRules.refundRecorded(failed, lightning(), listOf(movement(hash = "other"))))
        assertFalse(SendProgressRules.refundRecorded(failed, lightning(), listOf(movement(status = "pending"))))
        assertFalse(SendProgressRules.refundRecorded(failed, lightning(), emptyList()))
        // Not a failure, or not Lightning.
        assertFalse(SendProgressRules.refundRecorded(SendController.State.uncertain, lightning(), listOf(movement())))
        assertFalse(SendProgressRules.refundRecorded(failed, SendAttempt(payment = SendPayment("tark1x", "ark", 1_000), paymentHash = "hash", failed = true), listOf(movement())))
        // The ending: "Payment failed … back in your wallet" only with the evidence.
        assertEquals(SendEnding.refunded, SendEnding.of(failed, lightning(), true, false))
        assertEquals(SendEnding.notSent, SendEnding.of(failed, lightning(), false, false))
        assertEquals(SendEnding.notSent, SendEnding.of(failed, lightning(), null, false))
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
        assertEquals(SendMilestone.refunded, SendProgressRules.milestone(SendController.State.failed, lightning(), true, true))
        assertEquals(SendMilestone.stopped, SendProgressRules.milestone(SendController.State.failed, lightning(), true, false))
        // An on-chain success needs its transaction for the block row.
        val chain = SendAttempt(payment = SendPayment("bcrt1q", "onchain", 5_000, SendSource.onchain), receipt = SendReceipt(5_000, txid = "ab"))
        assertEquals(SendMilestone.sent, SendProgressRules.milestone(SendController.State.succeeded, chain, true, null))
        assertEquals(SendMilestone.stopped, SendProgressRules.milestone(SendController.State.succeeded, chain.copy(receipt = SendReceipt(5_000)), true, null))
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
