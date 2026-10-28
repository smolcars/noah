package com.noahwallet.widgets

import com.noahwallet.history.HistoryTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * The balance wave (docs/native-rewrite/design/widgets.md, The wave): 31 daily balances, oldest first, and the shape
 * drawn through them. The maths is shared with iOS through `apps/fixtures/widgets.json`.
 */
object WidgetWave {
    const val DAYS = 30
    /** Ten minutes a block, as Home's Renew counts them. */
    const val BLOCK_MILLIS = 600_000L

    /** A settled balance change: [at] in epoch milliseconds, [sats] signed. */
    data class Change(val at: Long, val sats: Long)

    /**
     * value[30] is [current]; value[i] is [current] minus every change whose local date in [zone] is after day i
     * (today − (30 − i)). Older changes cancel out; a future-dated one (clock skew) counts as today.
     */
    fun dailyBalances(current: Long, changes: List<Change>, now: Long, zone: ZoneId): List<Long> {
        val today = day(now, zone)
        val dated = changes.map { minOf(day(it.at, zone), today) to it.sats }
        return (0..DAYS).map { i ->
            val day = today.minusDays((DAYS - i).toLong())
            current - dated.sumOf { (date, sats) -> if (date > day) sats else 0L }
        }
    }

    fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /** The label's change: exact, not smoothed. */
    fun change(values: List<Long>): Long = values.last() - values.first()

    /** Calm water: every value equal (a new wallet, or no activity in the window). */
    fun calm(values: List<Long>): Boolean = values.all { it == values.first() }

    /** Values written on an earlier local day, moved on to [today]: the days since saw no change. */
    fun shifted(values: List<Long>, writtenDay: LocalDate, today: LocalDate): List<Long> {
        val days = java.time.temporal.ChronoUnit.DAYS.between(writtenDay, today).coerceIn(0, values.size.toLong()).toInt()
        return values.indices.map { i -> values.getOrElse(i + days) { values.last() } }
    }

    /** Two passes of (prev + 2·v + next) / 4 with clamped ends, so daily steps read as swell. */
    fun swell(series: List<Double>): List<Double> {
        var s = series
        repeat(2) {
            val last = s.lastIndex
            s = s.indices.map { i -> (s[maxOf(0, i - 1)] + 2 * s[i] + s[minOf(last, i + 1)]) / 4 }
        }
        return s
    }

    /** One cubic Bézier segment, from the previous point to ([x], [y]). */
    data class Segment(val c1x: Double, val c1y: Double, val c2x: Double, val c2y: Double, val x: Double, val y: Double)

    /** A monotone cubic through the points (Fritsch–Carlson), so the curve never overshoots them. */
    fun monotone(xs: List<Double>, ys: List<Double>): List<Segment> {
        val n = xs.size
        if (n < 2) return emptyList()
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = DoubleArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
        for (i in 0 until n - 1) {
            if (d[i] == 0.0) { m[i] = 0.0; m[i + 1] = 0.0; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]; val q = a * a + b * b
            if (q > 9) { val t = 3 / sqrt(q); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        return (0 until n - 1).map { i ->
            val h = (xs[i + 1] - xs[i]) / 3
            Segment(xs[i] + h, ys[i] + m[i] * h, xs[i + 1] - h, ys[i + 1] - m[i + 1] * h, xs[i + 1], ys[i + 1])
        }
    }

    /**
     * Settled balance changes in Activity's unified rows ([com.noahwallet.history.HistoryRules.unified]), each movement
     * first [paired] with the wallet transaction it names. A bark movement counts once successful, dated by its creation
     * (the date Activity shows): its Ark delta, plus its on-chain half once that's confirmed, so a board moves the
     * balance by its fee alone. Any other on-chain transaction counts once confirmed, dated by [chainDates] (txid →
     * epoch ms, see [ChainDate]). Pending, failed and canceled movements, and unconfirmed transactions, are not changes.
     */
    fun changes(rows: List<HistoryTransaction>, chainDates: Map<String, Long>): List<Change> = paired(rows).mapNotNull { row ->
        if (row.source == "onchain-wallet") {
            if (row.hasConfirmation != true) return@mapNotNull null
            val at = row.txid?.let(chainDates::get) ?: return@mapNotNull null
            Change(at, row.balanceChangeSat ?: return@mapNotNull null)
        } else {
            if (row.movementStatus != "successful") return@mapNotNull null
            val at = row.timestamp?.time ?: return@mapNotNull null
            Change(at, (row.effectiveBalanceSat ?: 0L) + (row.balanceChangeSat?.takeIf { row.hasConfirmation == true } ?: 0L))
        }
    }.filter { it.sats != 0L }

    /**
     * Activity merges a board or offboard with its transaction by the txid it shows; a round offboard names its
     * transaction only as `funding_txid`. Any movement left unmerged takes the wallet transaction its offboard txid,
     * chain anchor or funding txid names, so both halves of one transfer stay one change on the movement's day.
     */
    internal fun paired(rows: List<HistoryTransaction>): List<HistoryTransaction> {
        val chain = rows.filter { it.source == "onchain-wallet" && !it.txid.isNullOrEmpty() }.associateBy { it.txid!! }
        val taken = mutableSetOf<String>()
        val movements = rows.filter { it.source != "onchain-wallet" }.map { row ->
            if (row.balanceChangeSat != null) return@map row
            val metadata = row.metadataJson?.let(com.noahwallet.history.HistoryMetadata::parse) ?: return@map row
            val txid = listOfNotNull(metadata.offboardTxid, metadata.chainAnchor?.substringBefore(':'), metadata.fundingTxid)
                .firstOrNull { it in chain && it !in taken } ?: return@map row
            taken += txid
            val tx = chain.getValue(txid)
            row.copy(balanceChangeSat = tx.balanceChangeSat, hasConfirmation = tx.hasConfirmation, confirmationHeight = tx.confirmationHeight)
        }
        return movements + rows.filter { it.source == "onchain-wallet" && it.txid !in taken }
    }

    /**
     * When a confirmed transaction was confirmed. bark reports only its height, so the first sighting estimates it as
     * now − (tip − height) × 10 min and keeps that ([exact] false) rather than recomputing it, which would drift a day
     * per day while the tip stands still; a block time Activity has read replaces it ([exact] true).
     */
    @kotlinx.serialization.Serializable
    data class ChainDate(val at: Long, val exact: Boolean = false)

    /** The confirmed wallet transactions' dates in [rows]: [known] block times, else [stored], else a first estimate. */
    fun chainDates(rows: List<HistoryTransaction>, stored: Map<String, ChainDate>, known: Map<String, Long>, tip: Long?, now: Long): Map<String, ChainDate> =
        rows.filter { it.source == "onchain-wallet" && it.hasConfirmation == true && !it.txid.isNullOrEmpty() }.mapNotNull { row ->
            val txid = row.txid!!
            val date = known[txid]?.takeIf { it > 0 }?.let { ChainDate(it, exact = true) } ?: stored[txid]
                ?: row.confirmationHeight?.toLong()?.let { height -> tip?.let { ChainDate(now - (it - height).coerceAtLeast(0) * BLOCK_MILLIS) } }
            date?.let { txid to it }
        }.toMap()
}
