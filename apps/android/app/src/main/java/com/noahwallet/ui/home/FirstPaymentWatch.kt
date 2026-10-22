package com.noahwallet.ui.home

import com.noahwallet.history.HistoryTransaction

/**
 * When the first payment plays. Each visit takes a baseline only after its own balance refresh has landed; the
 * sequence then plays once, for incoming funds that appear after that baseline on a wallet that was Fresh at it.
 * Payments the refresh itself finds (they arrived while Home was away) just open Everyday, and so does anything
 * that shows up before the baseline exists. In memory only; a pose change keeps it, a real hide resets it.
 */
class FirstPaymentWatch {
    data class Baseline(val fresh: Boolean, val incoming: Set<String>)
    data class Arrival(val row: HistoryTransaction?, val sats: Long)

    var baseline: Baseline? = null
        private set

    /** Home was hidden for real (another screen or the background): the next visit takes a new baseline. */
    fun hidden() { baseline = null }

    /** This visit's initial refresh has landed; only the first call per visit sets the baseline. */
    fun caughtUp(state: HomeRules.State, rows: List<HistoryTransaction>) {
        if (baseline == null) baseline = Baseline(state == HomeRules.State.fresh, incoming(rows))
    }

    /** The payment to celebrate: a new incoming row, or the balance rising above zero, after a Fresh baseline. */
    fun arrival(rows: List<HistoryTransaction>, totalSats: ULong?): Arrival? {
        val base = baseline?.takeIf { it.fresh } ?: return null
        val row = rows.firstOrNull { it.isIncoming && it.id !in base.incoming }
        val sats = row?.amount ?: totalSats?.toLong()?.takeIf { it > 0 } ?: return null
        return Arrival(row, sats)
    }

    /** It plays once per visit: afterwards the baseline is no longer Fresh. */
    fun played() { baseline = baseline?.copy(fresh = false) }

    private val HistoryTransaction.isIncoming get() = direction == "incoming" && !isInternalTransfer

    private fun incoming(rows: List<HistoryTransaction>) = rows.filter { it.isIncoming }.map { it.id }.toSet()
}
