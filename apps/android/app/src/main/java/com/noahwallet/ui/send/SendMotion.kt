package com.noahwallet.ui.send

import com.noahwallet.send.SendAttempt
import com.noahwallet.send.SendController
import com.noahwallet.send.SendPayment
import com.noahwallet.send.SendSource
import io.github.niteshbalusu11.bark.Movement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Which motion a payment plays (docs/native-rewrite/design/send-receive.md, "Motion language"). */
@Serializable
enum class SendRoute {
    ark, lightning, chainArk, chainWallet;

    val chain get() = this == chainArk || this == chainWallet

    companion object {
        fun of(payment: SendPayment) = when (payment.method) {
            "ark" -> ark
            "onchain" -> if (payment.source == SendSource.onchain) chainWallet else chainArk
            else -> lightning
        }
    }
}

/** How far the wallet has really got. A phase never starts before its milestone is reached. */
@Serializable
enum class SendMilestone {
    /** The hold completed and the controller took the submission. */
    submitted,
    /** The attempt and its snapshot are journaled: the wallet is inside the payment call. */
    paying,
    /** Succeeded: Ark accepted it, Lightning settled with a preimage, or the transaction was broadcast. */
    sent,
    /** Lightning failed and bark recorded the revocation that returned the coins. */
    refunded,
    /** Lightning reported the payment still in flight once the submission ended. */
    pending,
    /** Any other ending (not sent, unknown, unavailable, already paid): the motion stops without a finale. */
    stopped,
}

@Serializable
enum class SendPhase { appear, split, sign, deliver, finale, lock, hold, pay, key, paid, fizzle, signBack, back, swap, drop, waiting, stop }

/** A motion started by this screen's hold: [startedAt] is elapsedRealtime, [reached] is ms after it per milestone. */
@Serializable
data class SendMotion(val route: SendRoute, val startedAt: Long, val reached: Map<SendMilestone, Long> = mapOf(SendMilestone.submitted to 0L)) {
    fun reach(milestone: SendMilestone, at: Long): SendMotion = if (milestone in reached) this else copy(reached = reached + (milestone to at))
    /** A stop is provisional: a later Check status that proves the payment sent or refunded plays that ending instead. */
    val ending: SendMilestone? get() = listOf(SendMilestone.sent, SendMilestone.refunded).filter { it in reached }.minByOrNull { reached.getValue(it) }
        ?: SendMilestone.stopped.takeIf { it in reached }
}

/**
 * The prototype's sequences (`startSend`, `arkSend`, `lnSend`, `chainSend`) as phases with their nominal start
 * times. Each phase starts at its nominal time plus any wait already accumulated, but never before the
 * wallet reaches its milestone; while a milestone is outstanding the motion holds (the bolt keeps looping).
 */
internal object SendTimeline {
    data class Step(val phase: SendPhase, val at: Long, val needs: SendMilestone)
    data class Started(val phase: SendPhase, val start: Long)

    const val stillPayingMillis = 10_000L
    /** Reduce Motion shortens the sequence as the prototype does (`T` × 0.45). */
    private const val reducedPace = .45f

    private val s = SendMilestone.submitted
    private val p = SendMilestone.paying
    private val sent = SendMilestone.sent
    private val refunded = SendMilestone.refunded

    private fun inflight(route: SendRoute) = listOf(Step(SendPhase.appear, 0, s), Step(SendPhase.split, 420, s)) + when (route) {
        SendRoute.ark -> listOf(Step(SendPhase.sign, 1_150, p))
        SendRoute.lightning -> listOf(Step(SendPhase.lock, 1_150, p), Step(SendPhase.hold, 1_700, p), Step(SendPhase.pay, 2_300, p))
        SendRoute.chainArk -> listOf(Step(SendPhase.swap, 1_150, p))
        SendRoute.chainWallet -> emptyList()
    }

    private fun ending(route: SendRoute, ending: SendMilestone): List<Step> = when {
        ending == sent -> when (route) {
            SendRoute.ark -> listOf(Step(SendPhase.deliver, 2_400, sent), Step(SendPhase.finale, 2_750, sent))
            SendRoute.lightning -> listOf(Step(SendPhase.key, 2_950, sent), Step(SendPhase.paid, 4_100, sent), Step(SendPhase.finale, 4_400, sent))
            SendRoute.chainArk -> listOf(Step(SendPhase.drop, 1_800, sent), Step(SendPhase.waiting, 2_400, sent))
            SendRoute.chainWallet -> listOf(Step(SendPhase.drop, 1_150, sent), Step(SendPhase.waiting, 1_900, sent))
        }
        ending == refunded && route == SendRoute.lightning ->
            listOf(Step(SendPhase.fizzle, 2_950, refunded), Step(SendPhase.signBack, 3_600, refunded), Step(SendPhase.back, 4_150, refunded))
        // Any other ending (a stop, or a refund on a rail that has none) just ends the motion when it's reached.
        else -> listOf(Step(SendPhase.stop, inflight(route).last().at + 300, ending))
    }

    fun steps(route: SendRoute, ending: SendMilestone?): List<Step> = inflight(route) + (ending?.let { ending(route, it) } ?: emptyList())

    /** Every phase that has started by [elapsed] ms, with its actual start. */
    fun started(motion: SendMotion, elapsed: Long, reduceMotion: Boolean = false): List<Started> {
        val ending = motion.ending
        // An ending implies the earlier milestones; they can't be reached later than it.
        val endAt = ending?.let { motion.reached.getValue(it) }
        fun reachedAt(milestone: SendMilestone): Long? = when (milestone) {
            SendMilestone.submitted -> 0L
            SendMilestone.paying -> listOfNotNull(motion.reached[milestone], endAt).minOrNull()
            else -> motion.reached[milestone]
        }
        val result = mutableListOf<Started>()
        var shift = 0L
        for (step in steps(motion.route, ending)) {
            val nominal = if (reduceMotion) (step.at * reducedPace).toLong() else step.at
            val start = reachedAt(step.needs)?.let { maxOf(nominal + shift, it) }
            if (start == null || start > elapsed) break
            shift = start - nominal
            result += Started(step.phase, start)
        }
        return result
    }

    /** Lightning still in flight: reported pending, or the payment call has run [stillPayingMillis]. */
    fun stillPaying(motion: SendMotion, elapsed: Long, reduceMotion: Boolean = false): Boolean {
        if (motion.route != SendRoute.lightning || motion.ending != null) return false
        if (started(motion, elapsed, reduceMotion).none { it.phase == SendPhase.pay }) return false
        return SendMilestone.pending in motion.reached || elapsed >= stillPayingMillis
    }

    /**
     * When the picture stops changing until the wallet reaches another milestone: a beat after the last phase
     * it can start. Null while something loops: Lightning still paying (the bolt) or a transaction waiting for
     * its block ([waitingForBlock], the blink). Under Reduce Motion neither loops: the still bolt only waits for
     * the "Still paying" line at [stillPayingMillis].
     */
    fun quietAt(motion: SendMotion, reduceMotion: Boolean = false, waitingForBlock: Boolean = false): Long? {
        val all = started(motion, Long.MAX_VALUE / 4, reduceMotion)
        val last = all.lastOrNull() ?: return 0
        if (motion.route == SendRoute.lightning && motion.ending == null && all.any { it.phase == SendPhase.pay })
            return if (reduceMotion) maxOf(last.start, stillPayingMillis) + 1_600 else null
        if (last.phase == SendPhase.waiting && waitingForBlock && !reduceMotion) return null
        return last.start + 1_600
    }

    /** When the result rows appear: with the finale, the refund, the block wait, the stop, or the pending line. */
    fun resultShown(motion: SendMotion, elapsed: Long, reduceMotion: Boolean = false): Boolean {
        val phases = started(motion, elapsed, reduceMotion).map { it.phase }
        return phases.any { it in setOf(SendPhase.finale, SendPhase.back, SendPhase.waiting, SendPhase.stop) } || stillPaying(motion, elapsed, reduceMotion)
    }
}

/** What a failed Lightning send's records show came back to the wallet. */
enum class SendRefund {
    /** bark recorded the revocation's coins on this attempt's own movement: "back in your wallet". */
    recorded,
    /** Nothing came back: the send failed before leaving the wallet, or its coins went to an exit. */
    none,
    /** A retried invoice: bark reused the hash's earlier movement, so what came back can't be tied to this attempt. */
    untied,
}

/** Reading the controller: which milestone its current state proves, if any. */
internal object SendProgressRules {
    /**
     * [finished]: the submission's job has returned, so an uncertain state is its answer rather than the
     * moment between a failed call and its reconciliation. [refund]: what the wallet's records show came
     * back; null until they've been read, so a refund can't be missed by deciding too early.
     */
    fun milestone(state: SendController.State, attempt: SendAttempt?, finished: Boolean, refund: SendRefund?): SendMilestone? = when (state) {
        SendController.State.ready -> null
        SendController.State.submitting -> if (attempt != null && !attempt.inspectionPending) SendMilestone.paying else SendMilestone.submitted
        SendController.State.succeeded -> when {
            attempt?.payment?.method == "onchain" && attempt.receipt?.txid == null -> SendMilestone.stopped
            // Today's broadcast retry: submitted but not yet on the network. The network motion waits for the broadcast.
            attempt?.receipt?.broadcastRetryPending == true -> SendMilestone.stopped
            else -> SendMilestone.sent
        }
        SendController.State.failed -> when (refund) {
            SendRefund.recorded -> SendMilestone.refunded
            SendRefund.none, SendRefund.untied -> SendMilestone.stopped.takeIf { finished }
            null -> null
        }
        SendController.State.uncertain -> when {
            !finished -> SendMilestone.paying.takeIf { attempt != null && !attempt.inspectionPending }
            attempt?.existingLightning != null && attempt.payment.method in lightningMethods -> SendMilestone.pending
            else -> SendMilestone.stopped
        }
        SendController.State.alreadyPaid, SendController.State.storageBlocked -> if (finished) SendMilestone.stopped else null
    }

    val lightningMethods = setOf("lightning", "lnurl", "offer")

    /**
     * bark finishes a Lightning send that failed after leaving the wallet as failed, with the revocation's
     * coins as its outputs: that is the refund. A send that failed before leaving has no outputs, and one
     * pushed to unilateral exit lists exited coins instead; neither is "back in your wallet". A retried
     * invoice reuses the hash's movement from before the attempt, whose outputs include the earlier
     * attempts' coins, so nothing there can be tied to this one (async-payments.md, "Reused hashes").
     */
    fun refund(state: SendController.State, attempt: SendAttempt?, movements: List<Movement>): SendRefund {
        if (state != SendController.State.failed || attempt == null) return SendRefund.none
        if (attempt.receipt != null || attempt.payment.method !in lightningMethods) return SendRefund.none
        val hash = attempt.paymentHash ?: return SendRefund.none
        val sends = movements.filter { movement ->
            movement.subsystemName == "bark.lightning_send" && movement.subsystemKind == "send" &&
                (movement.paymentHash ?: paymentHash(movement.metadataJson)) == hash
        }
        if (sends.any { it.id in attempt.snapshot.movementIDs }) return SendRefund.untied
        return if (sends.any { it.status == "failed" && it.outputVtxoIds.isNotEmpty() && it.exitedVtxoIds.isEmpty() }) SendRefund.recorded else SendRefund.none
    }

    private fun paymentHash(metadata: String?): String? = metadata?.let {
        runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.get("payment_hash")?.jsonPrimitive?.content }.getOrNull()
    }
}
