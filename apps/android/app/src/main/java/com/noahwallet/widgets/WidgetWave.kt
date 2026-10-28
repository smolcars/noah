package com.noahwallet.widgets

import com.noahwallet.history.HistoryTransaction
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.sqrt

/**
 * The balance wave (docs/native-rewrite/design/widgets.md, The wave): 31 daily balances, oldest first, and the shape
 * drawn through them. The maths is shared with iOS through `apps/fixtures/widgets.json`.
 */
object WidgetWave {
    const val DAYS = 30
    /** Ten minutes a block, as Home's Renew counts them. */
    const val BLOCK_MILLIS = 600_000L

    /** A balance change: [at] in epoch milliseconds, [sats] signed. */
    data class Change(val at: Long, val sats: Long)

    /**
     * value[30] is [current]; value[i] is [current] minus every change whose local date in [zone] is after day i
     * (today − (30 − i)). Older changes cancel out; a future-dated one (clock skew) counts as today.
     */
    fun dailyBalances(current: Long, changes: List<Change>, now: Long, zone: TimeZone): List<Long> {
        val today = day(now, zone)
        val dated = changes.map { minOf(day(it.at, zone), today) to it.sats }
        return (0..DAYS).map { i ->
            val day = today - (DAYS - i)
            current - dated.sumOf { (date, sats) -> if (date > day) sats else 0L }
        }
    }

    /**
     * The local date of [at] in [zone], as days since 1970-01-01: whole days apart whatever daylight saving does in between.
     * java.util, not java.time, which Android 7 (API 24–25) lacks.
     */
    fun day(at: Long, zone: TimeZone): Long {
        val local = GregorianCalendar(zone).apply { timeInMillis = at }
        val utc = GregorianCalendar(TimeZone.getTimeZone("""UTC""")).apply {
            clear(); set(local.get(GregorianCalendar.YEAR), local.get(GregorianCalendar.MONTH), local.get(GregorianCalendar.DAY_OF_MONTH))
        }
        return Math.floorDiv(utc.timeInMillis, 86_400_000L)
    }

    /** The label's change: exact, not smoothed. */
    fun change(values: List<Long>): Long = values.last() - values.first()

    /** Calm water: every value equal (a new wallet, or no activity in the window). */
    fun calm(values: List<Long>): Boolean = values.all { it == values.first() }

    /** Values written on an earlier local day, moved on to [today]: the days since saw no change. */
    fun shifted(values: List<Long>, writtenDay: Long, today: Long): List<Long> {
        val days = (today - writtenDay).coerceIn(0, values.size.toLong()).toInt()
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
     * What bark-bolt-ffi reports about pending items, read with the rows: the movements of Lightning receives whose
     * preimage is out (`pendingLightningReceives`, "preimage-revealed", which `claimable_lightning_receive` counts) and
     * the board VTXOs `pending_board` counts (`pendingBoardVtxos`).
     */
    data class Pending(val claimableReceives: Set<UInt> = emptySet(), val boardVtxos: Set<String> = emptySet())

    /**
     * The balance changes in the rows Activity reads (bark's movements and the wallet's transactions, before Activity
     * merges them), the same as the iOS widget: anything the displayed total already reflects. Settled movements count at
     * their creation (the date Activity shows). A pending movement holds its sats in the total until it settles (a
     * Lightning or Ark send, a round, an exit), so it isn't a change yet; except a Lightning receive or a board the
     * total already counts ([pending]), and an offboard once its transaction is out ([offboarded]). Transactions are all
     * in the on-chain total, confirmed or not: they count on the day of the movement that names them, any other at its
     * [chainDates] date (txid → epoch ms, see [ChainDate]), or not at all without one. Failed and canceled movements
     * aren't changes.
     */
    fun changes(rows: List<HistoryTransaction>, chainDates: Map<String, Long>, pending: Pending = Pending()): List<Change> {
        val movements = rows.filter { it.source != "onchain-wallet" }
        val owners = owners(movements)
        val changes = movements.mapNotNull { row ->
            val sats = row.effectiveBalanceSat?.takeIf { it != 0L && (row.movementStatus == "successful" || counts(row, pending)) }
                ?: return@mapNotNull null
            row.timestamp?.let { Change(it.time, sats) }
        }.toMutableList()
        for (row in rows) chainChange(row, owners, chainDates)?.let { changes += it }
        return changes
    }

    /** A pending Lightning receive or board the total already counts. */
    private fun counts(row: HistoryTransaction, pending: Pending): Boolean = row.movementStatus == "pending" && when (row.movementKind) {
        "lightning-receive" -> row.movementId in pending.claimableReceives
        "onboard" -> row.outputVtxos.orEmpty().any { it in pending.boardVtxos }
        else -> false
    }

    /** Each pending offboard's amount, for [offboarded]. */
    fun offboards(rows: List<HistoryTransaction>): List<Change> = rows.mapNotNull { row ->
        val sats = row.effectiveBalanceSat?.takeIf { it < 0 && row.movementStatus == "pending" && row.subsystemName == "bark.offboard" }
        sats?.let { row.timestamp?.let { at -> Change(at.time, it) } }
    }

    /**
     * The part of the pending [offboards] the total no longer counts, as one change: bark-bolt-ffi doesn't say which
     * offboard's transaction is out, only what `pending_offboard` still [held] (before broadcast it holds an offboard's
     * sats, or more while change is split off). Dated by the oldest, usually the only one.
     */
    fun offboarded(offboards: List<Change>, held: Long): List<Change> {
        val left = -offboards.sumOf { it.sats } - held
        val oldest = offboards.minOfOrNull { it.at } ?: return emptyList()
        return if (left > 0) listOf(Change(oldest, -left)) else emptyList()
    }

    /** A wallet transaction's change, confirmed or not: on the day of the movement that names it, else at its chain date. */
    private fun chainChange(row: HistoryTransaction, owners: Map<String, HistoryTransaction>, chainDates: Map<String, Long>): Change? {
        if (row.source != "onchain-wallet") return null
        val sats = row.balanceChangeSat?.takeIf { it != 0L } ?: return null
        val owner = row.txid?.let(owners::get)
        if (owner != null) return owner.timestamp?.let { Change(it.time, sats) }
        return row.txid?.let(chainDates::get)?.let { Change(it, sats) }
    }

    /**
     * Which movement owns each transaction it names: a board's anchor or an offboard's transaction outright first;
     * among movements sharing a round's funding transaction, one that moved money on chain before a renewal; the first
     * listed on a tie.
     */
    internal fun owners(movements: List<HistoryTransaction>): Map<String, HistoryTransaction> {
        val named = mutableMapOf<String, Pair<HistoryTransaction, Int>>()
        for (movement in movements) claim(named, movement)
        return named.mapValues { it.value.first }
    }

    /** Records the transactions [movement] names in [named], unless a movement listed earlier ranks as high. */
    private fun claim(named: MutableMap<String, Pair<HistoryTransaction, Int>>, movement: HistoryTransaction) {
        val metadata = movement.metadataJson?.let(com.noahwallet.history.HistoryMetadata::parse) ?: return
        val onchain = movement.movementKind in setOf("onboard", "offboard", "send-onchain")
        for ((txid, rank) in listOf(metadata.offboardTxid to 2, metadata.chainAnchor?.replace(Regex(":\\d+$"), "") to 2,
            metadata.fundingTxid to if (onchain) 1 else 0)) {
            if (txid.isNullOrEmpty()) continue
            if (named[txid]?.let { it.second < rank } != false) named[txid] = movement to rank
        }
    }

    /**
     * When a wallet transaction happened. One first seen unconfirmed takes that moment ([exact] true), which confirming
     * never moves. bark reports a confirmed one's height only, so its first sighting estimates it as
     * now − (tip − height) × 10 min and keeps that ([exact] false) rather than recomputing it, which would drift a day
     * per day while the tip stands still; a block time Activity has read replaces an estimate, never an exact date.
     */
    @kotlinx.serialization.Serializable
    data class ChainDate(val at: Long, val exact: Boolean = false)

    /**
     * The wallet transactions' dates in [rows]: an unconfirmed one [stored] or seen [now]; a confirmed one's [stored]
     * exact date, else a [known] block time, else [stored], else a first estimate.
     */
    fun chainDates(rows: List<HistoryTransaction>, stored: Map<String, ChainDate>, known: Map<String, Long>, tip: Long?, now: Long): Map<String, ChainDate> =
        rows.filter { it.source == "onchain-wallet" && !it.txid.isNullOrEmpty() }.mapNotNull { row ->
            val txid = row.txid!!
            val date = if (row.hasConfirmation != true) stored[txid] ?: ChainDate(now, exact = true)
            else stored[txid]?.takeIf { it.exact } ?: known[txid]?.takeIf { it > 0 }?.let { ChainDate(it, exact = true) } ?: stored[txid]
                ?: row.confirmationHeight?.toLong()?.let { height -> tip?.let { ChainDate(now - (it - height).coerceAtLeast(0) * BLOCK_MILLIS) } }
            date?.let { txid to it }
        }.toMap()
}
