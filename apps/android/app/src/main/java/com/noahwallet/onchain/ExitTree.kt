package com.noahwallet.onchain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The house springs as curves of time (prototype `spring`): SwiftUI's response and damping, so a picture computed from the
 * seconds since a change moves as iOS's and the prototype's do, frame for frame.
 */
internal enum class HouseSpring(response: Double, private val damping: Double) {
    snappy(.30, .85), bouncy(.40, .60), soft(.50, 1.0), calm(.70, 1.0);

    private val w0 = 2 * PI / response
    private fun x(t: Double): Double = if (damping < 1) {
        val root = sqrt(1 - damping * damping)
        1 - exp(-damping * w0 * t) * (cos(w0 * root * t) + damping / root * sin(w0 * root * t))
    } else 1 - exp(-w0 * t) * (1 + w0 * t)

    /** Seconds until it stays within 0.2 % of rest for a quarter of a second. */
    val seconds: Float = run {
        var end = .05
        while (end < 3 && (0..25).any { kotlin.math.abs(1 - x(end + it * .01)) > .002 }) end += .01
        end.toFloat()
    }

    /** Progress [dt] seconds after it starts: 0 before, exactly 1 once settled. */
    operator fun invoke(dt: Float): Float = when { dt <= 0f -> 0f; dt >= seconds - .001f -> 1f; else -> x(dt.toDouble()).toFloat() }
}

/**
 * The exit picture's rest pose (exit-v2-notes.md, The picture): your branch of the round's tree, root first, each
 * transaction as bark reports it, your coin at the top with the timelock, and the claim. The picture only moves when this
 * changes; nothing in it is timed.
 */
internal data class ExitScene(
    val n: Int, val phase: ExitStage.Phase,
    /** Per transaction, root first. The last one's output is your coin, so it's drawn as the coin. */
    val marks: List<Mark>, val heights: List<Long?> = List(n) { null },
    /** Another wallet paid its fee child; this wallet did; your other exits share it; it's a node of the round's tree. */
    val theirs: List<Boolean> = List(n) { false }, val paid: List<Boolean> = List(n) { false },
    val shared: List<Boolean> = List(n) { false }, val nodes: List<Boolean> = List(n) { it < n - 1 },
    val attention: ExitStage.Attention? = null, val fee: Long? = null, val rate: Long? = null, val waited: Long = 0,
    /** Noah can't vouch that it moves: bark last recorded it at [checkedAt]. */
    val stale: Boolean = false, val checkedAt: Long? = null,
    val tip: Long = 0, val lockedFrom: Long? = null, val claimableHeight: Long? = null, val unlockedAt: Long? = null,
    val claimedBlock: Long? = null, val claimFee: Long? = null,
    /** Another wallet's address once a claim went there; null for this phone. */
    val destination: String? = null,
    /** Your other exits from this round: the level where each one's branch leaves yours, and its amount. */
    val others: List<Other> = emptyList(),
    /** What the transaction waiting for funds still needs on-chain. */
    val need: Long? = null,
    /** The explainer: the same tree before anything is sent, with its three captions. */
    val explain: Boolean = false,
    /** Once a long path is folded ([fold]): the real transactions each drawn level stands for, root first; null, one each. */
    val spans: List<IntRange>? = null,
    /** The real path's length, which the labels say even when fewer levels are drawn. */
    val count: Int = n,
    /** A folded stack's oldest block, beside [heights]' newest. */
    val oldest: List<Long?>? = null,
) {
    /** [ready]: bark has it ready but hasn't sent it with its fee child yet; [waiting]: it's in the mempool. */
    enum class Mark { unsent, ready, needsFee, waiting, mined }
    data class Other(val level: Int, val amount: Long)

    val waiting get() = marks.indexOf(Mark.waiting)
    val coin get() = marks[n - 1]
    val locked get() = phase in setOf(ExitStage.Phase.wait, ExitStage.Phase.claimable, ExitStage.Phase.claiming, ExitStage.Phase.claimed)
    val open get() = phase in setOf(ExitStage.Phase.claimable, ExitStage.Phase.claiming, ExitStage.Phase.claimed)
    val claiming get() = phase == ExitStage.Phase.claiming || phase == ExitStage.Phase.claimed
    val claimed get() = phase == ExitStage.Phase.claimed
    val canceled get() = phase == ExitStage.Phase.canceled
    val lifting get() = phase == ExitStage.Phase.lift && !explain
    /** Fees leave this wallet once anything is ready or sent: the phone badge shows from then on. */
    val wallet get() = !explain && !canceled && (locked || marks.any { it != Mark.unsent })
    fun mined(i: Int) = marks.getOrNull(i) == Mark.mined
    /** Blocks the timelock has counted, 0 → 1. */
    val dial get() = claimableHeight?.let { end -> lockedFrom?.let { start ->
        if (open) 1f else ((tip - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f) } } ?: if (open) 1f else 0f
    /** Blocks left in the timelock. */
    val left get() = claimableHeight?.let { (it - tip).coerceAtLeast(0) } ?: 0
    /** The level (1-based) whose fee child is docked below it: the waiting transaction's, when it has one and isn't your coin. */
    val docked get() = waiting.takeIf { it in 0 until n - 1 && paid[it] }?.plus(1) ?: -1
    /** The level (1-based) ringed red, short of funds, when it isn't your coin. */
    val ringed get() = marks.indexOf(Mark.needsFee).takeIf { it in 0 until n - 1 }?.plus(1) ?: -1
    /** The first real transaction drawn level [i] stands for. */
    fun real(i: Int) = spans?.getOrNull(i)?.first ?: i
    /** Level [i] stands for several transactions: a stack of mined ones, or "+N" not sent yet. */
    fun folded(i: Int) = spans?.getOrNull(i)?.let { it.last > it.first } == true

    /**
     * The path drawn in at most [cap] levels (a long one folds rather than squeezes): the oldest mined transactions in one
     * stack at the base, the one being sent, the next few to send and your coin each on their own, and the rest still to
     * send in one "+N". Counts stay real ([count]).
     */
    fun fold(cap: Int): ExitScene {
        if (n <= cap || spans != null) return this
        val coin = n - 1
        val first = marks.indexOfFirst { it != Mark.mined }.let { if (it < 0) n else it }
        val kinds = List(coin) { i -> when { i < first -> ExitFold.Kind.mined; i == first -> ExitFold.Kind.current; else -> ExitFold.Kind.unsent } }
        val spans = ExitFold.spans(kinds, cap - 1) + listOf(coin..coin)
        val single = { r: IntRange -> r.first == r.last }
        return copy(n = spans.size, marks = spans.map { if (single(it)) marks[it.first] else if (marks[it.first] == Mark.mined) Mark.mined else Mark.unsent },
            heights = spans.map { heights[it.last] }, theirs = spans.map { single(it) && theirs[it.first] }, paid = spans.map { single(it) && paid[it.first] },
            shared = spans.map { r -> r.any { shared[it] } }, nodes = spans.map { single(it) && nodes[it.first] },
            others = others.map { other -> other.copy(level = spans.indexOfFirst { other.level - 1 in it } + 1) },
            spans = spans, count = n, oldest = spans.map { heights[it.first] })
    }

    companion object {
        /** A round VTXO's branch before anything is sent (the explainer and review): radix 4, so a 64-leaf round's four. The
         *  explainer draws 8 levels at most ([fold]) and says the real count. */
        fun sample(n: Int = 4) = n.coerceAtLeast(1).let { k -> ExitScene(k, ExitStage.Phase.publish, List(k) { Mark.unsent }, explain = true) }

        /**
         * [others]: the wallet's other exits, for the shared transactions and their coins; [confirmed]: the on-chain balance
         * the next fee comes from; [destination]: a claim's address when it went to another wallet.
         */
        fun of(exit: ExitModel, stage: ExitStage, tip: Long, others: List<ExitModel> = emptyList(), confirmed: Long? = null,
               claimFee: Long? = null, destination: String? = null): ExitScene {
            val n = stage.total.coerceAtLeast(1)
            val txs = exit.path
            val done = stage.phase in setOf(ExitStage.Phase.onchain, ExitStage.Phase.wait, ExitStage.Phase.claimable, ExitStage.Phase.claiming, ExitStage.Phase.claimed)
            val needsFunds = stage.attention == ExitStage.Attention.needsFunds
            val marks = List(n) { i ->
                val status = txs.getOrNull(i)?.status
                when {
                    stage.phase == ExitStage.Phase.lift -> Mark.unsent
                    status?.block != null || status?.kind == "confirmed" || done -> Mark.mined
                    stage.phase == ExitStage.Phase.canceled -> Mark.unsent
                    status?.kind == "awaiting-confirmation" -> Mark.waiting
                    status?.kind == "awaiting-cpfp-broadcast" -> if (needsFunds) Mark.needsFee else Mark.ready
                    else -> Mark.unsent
                }
            }
            val heights = List(n) { i -> txs.getOrNull(i)?.status?.block?.height ?: if (marks[i] == Mark.mined) stage.lockedFrom ?: stage.lastBlock else null }
            val theirs = List(n) { i -> (txs.getOrNull(i)?.status?.origin ?: exit.packages.getOrNull(i)?.childOrigin).let { it != null && it != "wallet" } }
            val paid = List(n) { i -> txs.getOrNull(i)?.status?.child_txid != null || exit.packages.getOrNull(i)?.childTxid != null }
            val live = others.filter { it.id != exit.id && it.state !in ExitTimeline.terminal }
            val sharedIds = live.flatMap { other -> other.packages.map { it.txid } }.toSet()
            val shared = List(n) { i -> exit.packages.getOrNull(i)?.txid?.let { it in sharedIds } ?: false }
            val nodes = List(n) { i -> exit.packages.getOrNull(i)?.outputs?.takeIf { it > 0 }?.let { it > 2 } ?: (i < n - 1) }
            // Where another exit's branch leaves yours: after the transactions you share, root first.
            val coins = live.mapNotNull { other ->
                val k = exit.packages.zip(other.packages).takeWhile { (a, b) -> a.txid == b.txid }.size
                if (k in 1 until n) Other(k, other.amount) else null
            }.take(3)
            val current = stage.current.takeIf { it < n && marks[it] == Mark.waiting }
            return ExitScene(n, stage.phase, marks, heights, theirs, paid, shared, nodes, stage.attention,
                fee = current?.let { stage.fee }, rate = stage.rate, waited = stage.waited,
                stale = stage.stale && current != null, checkedAt = exit.details.tip_height, tip = tip,
                lockedFrom = stage.lockedFrom, claimableHeight = stage.claimableHeight, unlockedAt = stage.unlockedAt,
                claimedBlock = stage.claimedBlock, claimFee = claimFee, destination = destination, others = coins,
                need = if (needsFunds) exit.nextPackageFee?.let { fee -> confirmed?.let { (fee - it).coerceAtLeast(0) } } else null)
        }
    }
}

/**
 * How a path too long for its room folds (exit-v2-notes.md): the picture's levels, the Exits list's gutter and Review's
 * rail all draw a long path the same way rather than squeezing it.
 */
internal object ExitFold {
    /** A transaction as the fold sees it: in a block, being sent (the first not in a block on its path), or still to send. */
    enum class Kind { mined, current, unsent }

    /**
     * The places [kinds] (root first, your coin left out) get when only [cap] fit: each one being sent on its own, and between
     * them runs that fold. A run of blocks keeps its latest on their own and stacks the oldest in one place; a run still to
     * send keeps its next ones and folds the rest in one "+N". The next two to send and the latest block keep their own
     * places longest; folding one alone saves nothing. Returns the transactions each place stands for, in order.
     */
    fun spans(kinds: List<Kind>, cap: Int): List<IntRange> {
        if (kinds.size <= cap) return kinds.indices.map { it..it }
        class Run(val range: IntRange, val old: Boolean) { var own = range.count() }
        val runs = mutableListOf<Run>(); var start = 0
        for (i in 0..kinds.size) if (i == kinds.size || kinds[i] == Kind.current) {
            if (i > start) runs += Run(start until i, (start until i).all { kinds[it] == Kind.mined })
            start = i + 1
        }
        val required = kinds.count { it == Kind.current }
        fun total() = required + runs.sumOf { it.own + if (it.own < it.range.count()) 1 else 0 }
        fun shrink(old: Boolean, floor: Int) {
            while (total() > cap) (runs.filter { it.old == old && it.own > floor }.maxByOrNull { it.own } ?: return).own--
        }
        shrink(false, 2); shrink(true, 1); shrink(false, 1); shrink(true, 0); shrink(false, 0)
        for (run in runs) if (run.range.count() - run.own == 1) run.own = run.range.count()
        return buildList {
            var i = 0
            while (i < kinds.size) {
                val run = runs.firstOrNull { i in it.range }
                if (run == null) { add(i..i); i++; continue }
                val r = run.range; val folded = r.count() - run.own
                if (run.old) { if (folded > 0) add(r.first until r.first + folded); for (k in r.first + folded..r.last) add(k..k) }
                else { for (k in r.first until r.first + run.own) add(k..k); if (folded > 0) add(r.first + run.own..r.last) }
                i = r.last + 1
            }
        }
    }
}

/**
 * The picture's geometry in dp (prototype `heroGeo`): the ground line, the round's block on it, the trunk's x, the label
 * column, your coin's height and the wallet badge. [n] is the drawn levels; [scale] the labels' size, the font scale up to 1.25.
 */
internal class TreeGeometry(val width: Float, val height: Float, val n: Int, val scale: Float, val wide: Boolean) {
    val ground = (height - 24 - 13 * scale).roundToInt().toFloat()
    val block = 22f
    val roundY = ground - block / 2
    val trunk = (width * if (wide) .40f else .34f).roundToInt().toFloat()
    val labels = trunk + 46
    val coinR = (width * .045f).coerceIn(15f, 19f).roundToInt().toFloat()
    val coinY = (12 + coinR + 11).roundToInt().toFloat()
    val walletX = width - 32
    val walletY = ground - 21
    val room = roundY - coinY
    val spread = min(26f, width * .068f)
    val lineHeight = 14 * scale
    val fontSize = 11.5f * scale
    val subSize = 10.5f * scale

    /** The room your coin keeps: its dashed ring, or the timelock's dial (ticks and all) while it's locked ([lock], 0 → 1). */
    fun coinReserve(lock: Float = 0f) = coinR + 5 + 13 * lock.coerceIn(0f, 1f)

    /**
     * The least gap below level [level] of [count]: half of each neighbour (the round's block, a transaction's square, or
     * your coin with its [reserve]) and the clearance, so every link shows and nothing touches. The level with its fee child
     * docked ([docked]) keeps the dot's room below it; the one short of funds ([ringed]) its red ring's, both sides.
     */
    fun minimumGap(level: Int, side: Float, reserve: Float, count: Int = n, docked: Int = -1, ringed: Int = -1): Float {
        val below = when { level - 1 == 0 -> block / 2; level - 1 == ringed -> ring(side); else -> side / 2 }
        val above = when { level == count -> reserve; level == ringed -> ring(side); level == docked -> max(side / 2, feeDrop); else -> side / 2 }
        return below + above + clearance
    }
    /** The red dashed ring around a transaction short of funds, stroke and all. */
    fun ring(side: Float) = side * .95f + 1

    /** Each transaction's square: 14 dp where levels have 22 dp, else 11, smaller only if even that doesn't keep the spacing. */
    val node: Float = run {
        var side = if (room / n.coerceAtLeast(1) < 22) 11f else 14f
        while (side > 7 && (1..n).sumOf { minimumGap(it, side, coinReserve(1f)).toDouble() } + allowance(side) > room) side -= 1
        side
    }

    /**
     * Level heights: 0 is the round's block, j the j-th drawn level, [n] your coin. When the path doesn't fit at 26 dp,
     * mined transactions ([mined], 0 → 1 each) stack tighter, like a pile of blocks; no gap goes below its minimum, and
     * the waiting one and your coin never shrink.
     */
    fun levels(lock: Float = 0f, docked: Int = -1, ringed: Int = -1, mined: (Int) -> Float): FloatArray {
        val tight = if (room / n.coerceAtLeast(1) >= 26) 1f else .42f
        fun confirmed(j: Int) = if (j == 0) 1f else mined(j - 1)
        val weights = FloatArray(n) { 1 + (tight - 1) * min(confirmed(it), confirmed(it + 1)) }
        val gaps = share(room, weights, FloatArray(n) { minimumGap(it + 1, node, coinReserve(lock), docked = docked, ringed = ringed) })
        val ys = FloatArray(n + 1); ys[0] = roundY
        for (j in 1..n) ys[j] = ys[j - 1] - gaps[j - 1]
        ys[n] = coinY
        return ys
    }

    /** How many levels fit at their minimum spacing ([lock]: the dial's room around your coin): a longer path folds. */
    fun capacity(lock: Float = 0f): Int {
        var k = 1
        while (k < 200 && (1..k + 1).sumOf { minimumGap(it, 11f, coinReserve(lock), k + 1).toDouble() } + allowance(11f) <= room) k++
        return k
    }
    /** Room for the one level that reaches past its square: a docked fee child below it, or a red ring both sides. */
    private fun allowance(side: Float) = max(feeDrop - side / 2, 2 * (ring(side) - side / 2))

    /**
     * The other people's stubs at level [level]: three short faint branches ending in rings, or none where levels have less
     * than 20 dp. Each of your [others] branching here takes one's place.
     */
    fun stubs(levels: FloatArray, level: Int, others: Int = 0): List<Pair<Float, Float>> {
        if (level !in 1 until n) return emptyList()
        val y = levels[level]; val up = levels[level + 1]
        if (y - up < 20) return emptyList()
        val length = min((y - up).coerceAtLeast(10f) * .5f, 22f)
        return listOf(-.75f to 1f, -1.35f to .8f, .7f to .85f).filterIndexed { i, _ -> i >= min(2, others) }
            .map { (dx, l) -> trunk + dx * spread to y - length * l - 3 }
    }

    /**
     * Where your [index]th other exit branching at [level] sits first, each further out and higher than the one before; it
     * can slide down its branch from there ([TreeLayout]).
     */
    fun other(levels: FloatArray, level: Int, index: Int): Pair<Float, Float> {
        val y = levels.getOrElse(level) { roundY }; val gap = (y - levels.getOrElse(level + 1) { coinY }).coerceAtLeast(12f)
        val first = y - gap * .95f
        return trunk - (1.15f + 1.05f * index) * spread to maxOf(otherTop, if (index == 0) first else min(y - gap * (1.5f + .55f * (index - 1)), first - 14 * index))
    }
    /** The highest another exit's coin goes: room above its 6 dp radius. */
    val otherTop get() = 6 + 6 * scale

    /**
     * How wide a label in the right-hand column can be between [top] and [bottom]: to the canvas's edge, or short of the
     * phone's badge (and, while the Ark line shows, the server's) where it would reach them, a pill's 8 dp and the clearance.
     */
    fun column(top: Float, bottom: Float, server: Boolean = false): Float {
        var right = width - 12
        if (bottom > walletY - 20 && top < walletY + 18) right = min(right, walletX - 15 - clearance - 9)
        if (server && bottom > coinY - 16 && top < coinY + 16) right = min(right, width - 44 - clearance - 9)
        return right - labels
    }
    /** How wide the claim's label can be, right-aligned at [x]: it stays right of your coin. */
    fun claimColumn(x: Float) = x - (trunk + coinR + 8)
    /** Where labels can go: inside the top (a pill's outline reaches 2.5 dp above its text) and above the ground line. */
    val labelTop = 4f
    val labelBottom get() = ground - 3

    /** The claim's stem, from your coin to the destination badge (prototype `cub`). */
    fun stem(u: Float): Pair<Float, Float> {
        val x0 = trunk + coinR + 3; val y0 = coinY; val x1 = walletX - 6; val y1 = coinY
        val x3 = walletX; val y3 = walletY - 19; val x2 = walletX; val y2 = coinY + (y3 - coinY) * .3f
        val a = 1 - u
        return (a * a * a * x0 + 3 * a * a * u * x1 + 3 * a * u * u * x2 + u * u * u * x3) to
            (a * a * a * y0 + 3 * a * a * u * y1 + 3 * a * u * u * y2 + u * u * u * y3)
    }
    val claimNode get() = stem(.42f)

    companion object {
        /** What every two drawn elements keep between them. */
        const val clearance = 2f
        /** How far a docked fee child's dot reaches below its transaction's centre: 7 dp down, 4.8 dp across. */
        const val feeDrop = 11.8f

        /** [total] shared by [weights], each share at least its minimum; when the minimums don't fit, in proportion to them. */
        fun share(total: Float, weights: FloatArray, minimums: FloatArray): FloatArray {
            val need = minimums.sum()
            if (need >= total) return FloatArray(minimums.size) { minimums[it] * total / need.coerceAtLeast(1f) }
            val fixed = mutableSetOf<Int>()
            while (true) {
                val free = weights.indices.filter { it !in fixed }
                val left = total - fixed.sumOf { minimums[it].toDouble() }.toFloat(); val sum = free.sumOf { weights[it].toDouble() }.toFloat()
                val short = free.filter { left * weights[it] / sum < minimums[it] }
                if (short.isEmpty()) return FloatArray(weights.size) { if (it in fixed) minimums[it] else left * weights[it] / sum }
                fixed += short
            }
        }
    }
}

/**
 * The label column (prototype `heroLabels`): highest priority first, nothing overlaps, each wrapped to its room and placed at
 * its wrapped height. Mined transactions that don't fit fold into one range, "Blocks 921,346–921,349"; the waiting
 * transaction's pill and the claim always show.
 */
internal object TreeLabels {
    enum class Tone { strong, muted, failure }
    enum class Pill { orange, grey, failure }
    data class Line(val text: String, val tone: Tone = Tone.muted, val bold: Boolean = false)
    /**
     * [head]: how many lines the first line wrapped to (drawn larger, in the pill); [shrinks]: one line that shrinks to fit
     * (to 75 % at most, [size]) rather than wrapping; [fallbacks]: where it goes instead when it doesn't fit here, in order;
     * [blocks]: a mined transaction's block (or a stack's), which may fold into a range; [x]: right-aligned there (the
     * claim's), else the column. [top] and [bottom] are set once placed.
     */
    data class Item(val y: Float, val lines: List<Line>, val priority: Float, val pill: Pill? = null, val head: Int = 1,
                    val shrinks: Boolean = false, val size: Float = 1f, val fallbacks: List<Item> = emptyList(), val blocks: LongRange? = null,
                    val x: Float? = null, val brace: Pair<Float, Float>? = null, val key: String = lines.joinToString("|") { it.text },
                    val top: Float = 0f, val bottom: Float = 0f) {
        val right get() = x != null
    }

    fun height(item: Item, lineHeight: Float, scale: Float) = item.lines.size * lineHeight + if (item.pill != null) 5 * scale else 0f

    /** [text] in lines no wider than [width], broken between words, and inside a word only when the word alone is too wide. */
    fun wrap(text: String, width: Float, measure: (String) -> Float): List<String> {
        val lines = mutableListOf<String>(); var line = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (measure(next) <= width) { line = next; continue }
            if (line.isNotEmpty()) lines += line
            line = ""
            for (character in word) {
                if (line.isNotEmpty() && measure(line + character) > width) { lines += line; line = "" }
                line += character
            }
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines += line
        return lines
    }

    /**
     * [items] placed by priority between [top] and [bottom], none overlapping, each [fit] to its room first. A lone mined
     * block that doesn't fit gets a second chance with a tighter margin; otherwise the blocks that don't fit fold, with every
     * older one placed alone, into one range ([range]) wherever it fits, the latest too if need be, so every mined block is
     * in a label and no range leaves one out.
     */
    fun place(items: List<Item>, lineHeight: Float, scale: Float, top: Float, bottom: Float, fit: (Item) -> Item, range: (Long, Long) -> String): List<Item> {
        val placed = mutableListOf<Item>(); val skipped = mutableListOf<Item>()
        fun clash(item: Item, margin: Float) = placed.any { it.right == item.right && !(item.bottom + margin < it.top || item.top - margin > it.bottom) }
        fun span(item: Item): Item {
            val height = height(item, lineHeight, scale)
            val y = item.y.coerceAtMost(bottom - height + lineHeight / 2).coerceAtLeast(top + lineHeight / 2)
            return item.copy(y = y, top = y - lineHeight / 2, bottom = y - lineHeight / 2 + height)
        }
        for (item in items.withIndex().sortedWith(compareBy({ -it.value.priority }, { it.index })).map { span(fit(it.value)) }) {
            if (!clash(item, 3f)) { placed += item; continue }
            val fallback = item.fallbacks.map { span(fit(it)) }.firstOrNull { !clash(it, 3f) }
            if (fallback != null) placed += fallback else if (item.blocks != null) skipped += item
        }
        if (skipped.size == 1 && !clash(skipped[0], 1f)) placed += skipped.removeAt(0)
        if (skipped.isEmpty()) return placed
        // The older blocks fold together, those placed alone too; the latest keeps its own label while the range fits.
        fun fold(folded: List<Item>): Item? {
            val from = folded.minOf { it.blocks!!.first }; val to = folded.maxOf { it.blocks!!.last }
            val middle = folded.map { it.y }.average().toFloat()
            val ys = listOf(middle) + (folded.map { it.y } + generateSequence(top) { it + 2 }.takeWhile { it <= bottom }).sortedBy { kotlin.math.abs(it - middle) }
            val spots = ys.map { y -> span(fit(Item(y, listOf(Line(range(from, to), Tone.strong)), 0f, blocks = from..to, key = "range"))) }
            return spots.firstOrNull { !clash(it, 3f) } ?: spots.firstOrNull { !clash(it, 1f) }
        }
        val alone = placed.filter { it.blocks != null && it.priority < 2 }
        val latest = placed.filter { it.blocks != null && it.priority >= 2 }
        placed.removeAll(alone)
        val range = fold(skipped + alone) ?: run { placed.removeAll(latest); fold(skipped + alone + latest) }
        if (range != null) placed += range else placed += latest
        return placed
    }
}

/** The timelock's dial: one tick per 6 blocks, up to 48; a longer tick every sixth when the lock reads as a clock (72+). */
internal object ExitDial {
    fun ticks(delta: Long) = ((delta + 5) / 6).toInt().coerceIn(1, 48)
    fun major(delta: Long, tick: Int) = delta >= 72 && tick % 6 == 0
}

/**
 * The Exits list's gutters (exit-v2-notes.md, Approved 3: option B). Exits that share transactions share one gutter, their
 * paths drawn once, lying on their side; every other row has only its coin and its state.
 */
internal object ExitGutter {
    enum class St { mined, waiting, ready, unsent, needsFunds }
    /** What the coin shows beside its path. */
    enum class After { none, ready, lock, claimed, canceled }

    /** Each of [exit]'s transactions, root first, as the gutter draws it. */
    fun path(exit: ExitModel, stage: ExitStage): List<Pair<String, St>> {
        val txs = exit.path
        val done = stage.phase in setOf(ExitStage.Phase.onchain, ExitStage.Phase.wait, ExitStage.Phase.claimable, ExitStage.Phase.claiming, ExitStage.Phase.claimed)
        val ids = exit.packages.map { it.txid }.ifEmpty { txs.map { it.txid } }
        return ids.mapIndexed { i, id ->
            val kind = txs.getOrNull(i)?.status?.kind
            id to when {
                kind == "confirmed" || txs.getOrNull(i)?.status?.block != null || done -> St.mined
                stage.phase == ExitStage.Phase.canceled -> St.unsent
                i == stage.current && stage.attention == ExitStage.Attention.needsFunds -> St.needsFunds
                kind == "awaiting-confirmation" -> St.waiting
                kind == "awaiting-cpfp-broadcast" -> St.ready
                else -> St.unsent
            }
        }
    }

    fun after(stage: ExitStage) = when (stage.phase) {
        ExitStage.Phase.claimable -> After.ready
        ExitStage.Phase.wait -> After.lock
        ExitStage.Phase.claimed -> After.claimed
        ExitStage.Phase.canceled, ExitStage.Phase.spent, ExitStage.Phase.swept -> After.canceled
        else -> After.none
    }

    /**
     * Each shared transaction's furthest state among the exits that have it, so a restarted exit's placeholders never
     * hide a sibling's progress: in a block, then in the mempool, then short of funds, then ready.
     */
    fun states(paths: List<List<Pair<String, St>>>): Map<String, St> = paths.flatten().groupBy({ it.first }, { it.second }).mapValues { (_, seen) ->
        seen.maxBy { listOf(St.unsent, St.ready, St.needsFunds, St.waiting, St.mined).indexOf(it) }
    }

    /**
     * A drawn transaction: its place in the gutter (dp) and its state; [coin] is an exit's own output, at the right; [folded]
     * stands for several (a stack of blocks, or ones still to send).
     */
    data class Node(val id: String, val x: Float, val y: Float, val st: St, val coin: Boolean, val folded: Boolean = false)
    data class Link(val from: Pair<Float, Float>, val to: Pair<Float, Float>, val ink: Boolean)
    data class Layout(val root: Pair<Float, Float>, val nodes: List<Node>, val links: List<Link>)

    /**
     * [rows] in the order a shared gutter draws them: those that share more sit together, so each branch has its own band
     * of rows and no two branches' transactions land on each other; otherwise as they came. [ids]: a row's transactions.
     */
    fun <T> tree(rows: List<T>, ids: (T) -> List<String>, depth: Int = 0): List<T> =
        if (rows.size < 2) rows else rows.groupBy { ids(it).getOrNull(depth) }.flatMap { (id, group) -> if (id != null && group.size > 1) tree(group, ids, depth + 1) else group }

    /** The gutter for a shared group: the round's block at [x0], each transaction [step] further, every coin at [coinX]. */
    const val sharedWidth = 112f
    const val coinWidth = 44f
    /** The least distance between two transactions' centres: their 8 dp squares and 3 dp of link between. */
    const val minStep = 11f
    fun shared(paths: List<List<Pair<String, St>>>, ys: List<Float>, x0: Float = 2f, coinX: Float = 90f, step: Float = 12f): Layout {
        val states = states(paths)
        val ids = paths.map { path -> path.dropLast(1).map { it.first } }
        val depth = ids.maxOfOrNull { it.size } ?: 0
        // Each exit's transaction being sent: its first not in a block (shared ones in their furthest state).
        val sending = ids.map { row -> row.indexOfFirst { states.getValue(it) != St.mined } }
        fun kinds(group: List<Int>, from: Int, to: Int) = (from until to).map { i -> when {
            group.any { sending[it] == i } -> ExitFold.Kind.current
            group.all { states.getValue(ids[it][i]) == St.mined } -> ExitFold.Kind.mined
            else -> ExitFold.Kind.unsent
        } }
        // A long path steps closer, down to the least step, so its last transaction stays clear of the coins; longer still,
        // it folds as the picture's does. What exits share is one trunk, folded alike for all of them, then each branch where
        // they part folds in the room the trunk leaves it, the trunk leaving its branches the least they need: so every path
        // keeps the least step, each exit's transaction being sent on its own.
        val room = coinX - 14 - 6 - x0; val fit = (room / minStep).toInt()
        val columns = List(paths.size) { mutableListOf<IntRange>() }
        fun parting(group: List<Int>, from: Int) = generateSequence(from) { it + 1 }.first { i -> group.any { ids[it].size <= i } || group.map { ids[it][i] }.distinct().size > 1 }
        fun branches(group: List<Int>, at: Int) = group.filter { ids[it].size > at }.groupBy { ids[it][at] }.values
        fun least(group: List<Int>, from: Int): Int {
            val p = parting(group, from); val trunk = kinds(group, from, p)
            val own = trunk.count { it == ExitFold.Kind.current } + trunk.indices.count { i -> trunk[i] != ExitFold.Kind.current && trunk.getOrNull(i - 1).let { it == null || it == ExitFold.Kind.current } }
            return own + (branches(group, p).maxOfOrNull { least(it, p) } ?: 0)
        }
        fun fold(group: List<Int>, from: Int, budget: Int) {
            val p = parting(group, from); val parts = branches(group, p)
            val trunk = if (depth <= fit) (from until p).map { it..it }
                else ExitFold.spans(kinds(group, from, p), max(1, budget - (parts.maxOfOrNull { least(it, p) } ?: 0))).map { it.first + from..it.last + from }
            for (r in group) columns[r] += trunk
            for (part in parts) fold(part, p, budget - trunk.size)
        }
        fold(paths.indices.toList(), 0, fit)
        // A path that parts too often for that: each exit's folds alone.
        val alone = columns.any { it.size > fit }
        if (alone) for (r in paths.indices) { columns[r].clear(); columns[r] += ExitFold.spans(kinds(listOf(r), 0, ids[r].size), fit) }
        val d = (columns.maxOfOrNull { it.size } ?: 0).let { if (it == 0) step else min(step, room / it) }
        // Each drawn transaction once (a folded one by its last), at the height of the rows it leads to.
        fun key(r: Int, span: IntRange) = ids[r][span.last].let { if (alone) "$it@$r" else it }
        val rows = mutableMapOf<String, MutableList<Float>>()
        for (r in paths.indices) for (span in columns[r]) rows.getOrPut(key(r, span)) { mutableListOf() } += ys[r]
        val root = x0 to ys.average().toFloat()
        val nodes = linkedMapOf<String, Node>(); val links = linkedMapOf<String, Link>()
        paths.forEachIndexed { r, path ->
            var from = root; var previous = "root"
            columns[r].forEachIndexed { c, span ->
                val key = key(r, span); val id = ids[r][span.last]; val folded = span.count() > 1
                val st = if (!folded) states.getValue(id) else if (span.all { states.getValue(ids[r][it]) == St.mined }) St.mined else St.unsent
                val at = x0 + (c + 1) * d + 6 to rows.getValue(key).average().toFloat()
                nodes.putIfAbsent(key, Node(id, at.first, at.second, st, false, folded))
                links.putIfAbsent("$previous>$key", Link(from.first + 4 to from.second, at.first - 4 to at.second, st == St.mined))
                from = at; previous = key
            }
            val (id, st) = path.last(); val at = coinX to ys[r]; val key = "$id@$r"
            nodes.putIfAbsent(key, Node(id, at.first, at.second, st, true))
            links.putIfAbsent("$previous>$key", Link(from.first + 4 to from.second, at.first - 11 to at.second, st == St.mined))
        }
        return Layout(root, nodes.values.toList(), links.values.toList())
    }
}

/**
 * Review's one-line path on phones (ExitStart's `ExitPath`): your transactions and your coin, [room] dp of steps from the
 * first at [start], or as far as your coin can reach ([end]) where even the fewest stops don't fit. A long path folds as the
 * picture's does, to the stops that keep their spacing; the captions say the real count.
 */
internal object ExitRail {
    /** A transaction's circle; [more] the "+N" of those folded, [width] its pill's; or your coin, drawn 4 dp past its place. */
    data class Stop(val x: Float, val more: Int? = null, val width: Float = 0f, val coin: Boolean = false) {
        /** Half its drawn width, stroke and all. */
        val half get() = when { coin -> coinR + .8f; more != null -> width / 2 + .7f; else -> node }
    }
    /** A transaction's circle's radius, stroke and all, and your coin's. */
    const val node = 5.8f
    const val coinR = 9f
    /** "+N" in its pill: 8 dp, semibold, as the picture's. */
    const val moreSize = 8f

    /** [label] writes "+N"; [measure] how wide it is at [moreSize]. */
    fun stops(path: Int, start: Float, room: Float, end: Float, label: (Int) -> String, measure: (String) -> Float): List<Stop> {
        if (path <= 1) return listOf(Stop(start + 4, coin = true))
        fun stops(cap: Int, far: Boolean = false): List<Stop> {
            val spans = ExitScene.sample(path).fold(cap).spans ?: (0 until path).map { it..it }
            val step = if (far) (end - coinR - .8f - 4 - start) / (spans.size - 1) else room / spans.size
            return spans.mapIndexed { i, span ->
                val x = start + i * step
                when {
                    i == spans.lastIndex -> Stop(x + 4, coin = true)
                    span.count() > 1 -> Stop(x, span.count(), max(2 * node, measure(label(span.count())) + 6))
                    else -> Stop(x)
                }
            }
        }
        // As many as keep a clearance of 2 dp between neighbours, 8 at most; the transaction being sent first and your coin
        // always stay.
        fun fits(s: List<Stop>) = s.zipWithNext().all { (a, b) -> b.x - a.x >= a.half + b.half + 2 }
        val caps = min(path, 8) downTo 3
        return caps.map { stops(it) }.firstOrNull(::fits) ?: caps.map { stops(it, far = true) }.firstOrNull(::fits) ?: stops(2, far = true)
    }
}

/** The Emergency exit page's Exits row and the list's line: the counts that aren't zero, in progress first. */
internal object ExitCounts {
    data class Counts(val inProgress: Int, val ready: Int)
    fun of(stages: Collection<ExitStage>) = stages.count { it.live && it.phase != ExitStage.Phase.claimable }.let { live ->
        Counts(live, stages.count { it.phase == ExitStage.Phase.claimable })
    }
}

/**
 * One move between two rest poses (exit-v2-notes.md, Motion): when each part of the picture starts moving after bark
 * reports a new state, and with which spring, so every value is a function of the seconds since. [from] null is arriving
 * on the page: only lifting off Ark and the branch growing play then. From [to] to itself nothing moves.
 */
internal class TreeMotion(val from: ExitScene?, val to: ExitScene) {
    private val was = from ?: to
    val n = to.n
    private val none = Float.NaN
    /** Each link's share of the grow: 0.26 s, closer on a long path, so any path grows in about a second. */
    val growStep = min(.26f, 1.05f / n.coerceAtLeast(1))
    val growSeconds = growStep * n + .35f
    /** The level [from] drew level [i]'s first transaction at (folded paths slide as blocks come). */
    fun was(i: Int): Int {
        val before = from ?: return i
        if (before.spans == null && to.spans == null) return i
        val real = to.real(i)
        return before.spans?.indexOfFirst { real in it }?.takeIf { it >= 0 } ?: real.takeIf { it < before.n } ?: -1
    }
    /** What level [i]'s transaction was before this move. */
    fun wasMark(i: Int) = was.marks.getOrNull(was(i))
    /**
     * The path folds differently after this move (a block joins the stack, one comes out of "+N"): every level slides from
     * where its transaction was drawn, matched by the real transaction, never by the drawn level.
     */
    val restructured = from != null && from.spans != to.spans
    /** Levels of [from] with no level of their own here, each with the level they fold into (a block into the stack). */
    val merging: List<Pair<Int, Int>> = if (!restructured) emptyList() else {
        val before = checkNotNull(from)
        val kept = (0 until n - 1).mapNotNull { i -> was(i).takeIf { it >= 0 && before.folded(it) == to.folded(i) } }.toSet()
        (0 until before.n - 1).filter { it !in kept }.map { level ->
            val real = before.real(level)
            level to (to.spans?.indexOfFirst { real in it }?.takeIf { it >= 0 } ?: real).coerceAtMost(n - 2)
        }
    }
    /** Level [i] comes out of a folded level (one out of "+N"), or folds several that had levels of their own: it grows in. */
    fun emerges(i: Int) = restructured && i < n - 1 && was(i).let { it >= 0 && checkNotNull(from).folded(it) != to.folded(i) }
    /** Start times in seconds; NaN where nothing moves. */
    val lift = if (from == null && to.lifting) .15f else none
    val grow = when {
        from == null && (to.explain || to.phase == ExitStage.Phase.publish) -> 0f
        from != null && from.lifting && !to.lifting && !to.canceled -> 0f
        else -> none
    }
    private val grown = if (grow.isNaN()) 0f else growSeconds
    val mine = FloatArray(n) { none }
    val send = FloatArray(n) { none }
    val fee: Float; val bump: Float; val flash: Float
    val lock: Float; val dial: Float; val open: Float
    val claim: Float; val claimSend: Float; val claimMine: Float
    val cancel = if (was.canceled != to.canceled) 0f else none
    val wallet: Float; val ark: Float
    /** When the levels and your other exits' coins slide to their new places: after a block's stamp when one came. */
    val slide: Float
    /** The explainer's labels fade in once the branch has grown, by about 1.5 s. */
    val labels = when { from == null && to.explain && !grow.isNaN() -> growStep * n - .05f; from != null && from != to -> 0f; else -> none }
    /** How long the move runs; the picture is still after it. */
    val seconds: Float

    init {
        val newly = (0 until n).filter { to.mined(it) && wasMark(it) != ExitScene.Mark.mined }
        newly.forEachIndexed { k, i -> mine[i] = k * .3f }
        val afterMine = if (newly.isEmpty()) grown else newly.size * .3f + .2f
        slide = when { restructured -> if (newly.isEmpty()) 0f else newly.size * .3f; from != null && from != to -> 0f; else -> none }
        for (i in 0 until n) if (to.marks[i] == ExitScene.Mark.waiting && wasMark(i) != ExitScene.Mark.waiting) send[i] = afterMine
        val w = to.waiting
        val docked = to.paid.getOrElse(w) { false } && was.paid.getOrElse(was(w)) { false } && wasMark(w) == ExitScene.Mark.waiting
        fee = if (w >= 0 && to.paid[w] && !docked) (send.getOrNull(w)?.takeIf { !it.isNaN() }?.plus(.5f) ?: 0f) else none
        val sameWait = w >= 0 && was.waiting >= 0 && was.waiting == was(w)
        bump = if (sameWait && to.attention == ExitStage.Attention.feesRose && was.attention != ExitStage.Attention.feesRose) 0f else none
        flash = if (sameWait && (to.waited > was.waited || !bump.isNaN())) 0f else none
        lock = if (to.locked && !was.locked) (mine[n - 1].takeIf { !it.isNaN() }?.plus(.55f) ?: 0f) else none
        dial = if (to.dial != was.dial) (if (lock.isNaN()) 0f else lock + .5f) else none
        open = if (to.open && !was.open) (if (lock.isNaN()) 0f else lock + .9f) else none
        claim = if (to.claiming && !was.claiming) (if (open.isNaN()) 0f else open + .9f) else none
        claimSend = if (claim.isNaN()) none else claim + .75f
        claimMine = if (to.claimed && !was.claimed) (if (claim.isNaN()) 0f else claimSend + .75f) else none
        wallet = if (to.wallet != was.wallet) (send.filter { !it.isNaN() }.minOrNull()?.minus(.35f)?.coerceAtLeast(0f) ?: 0f) else none
        ark = if (arkTarget(to) != arkTarget(was) && cancel.isNaN() && lift.isNaN()) grown else none
        val ends = mutableListOf(0f)
        fun end(start: Float, length: Float) { if (!start.isNaN()) ends += start + length }
        end(lift, HouseSpring.snappy.seconds); end(grow, growSeconds + .9f)
        for (i in 0 until n) { end(mine[i], maxOf(HouseSpring.bouncy.seconds, HouseSpring.snappy.seconds / 1.6f, .8f)); end(send[i], HouseSpring.snappy.seconds) }
        end(fee, HouseSpring.soft.seconds); end(bump, .15f + HouseSpring.soft.seconds); end(flash, .5f)
        end(lock, maxOf(HouseSpring.soft.seconds, HouseSpring.bouncy.seconds)); end(dial, HouseSpring.snappy.seconds); end(open, HouseSpring.bouncy.seconds)
        end(claim, HouseSpring.calm.seconds); end(claimSend, HouseSpring.snappy.seconds); end(claimMine, 1.35f + HouseSpring.soft.seconds)
        end(cancel, HouseSpring.soft.seconds); end(wallet, HouseSpring.soft.seconds); end(ark, .8f); end(labels, .5f); end(slide, HouseSpring.soft.seconds)
        seconds = ends.max()
    }

    private fun arkTarget(s: ExitScene) = when { s.explain || s.canceled -> 1f; s.lifting -> .4f; else -> 0f }
    private fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u
    private fun between(a: Float, b: Float, start: Float, spring: HouseSpring, t: Float) = if (start.isNaN()) b else lerp(a, b, spring(t - start))
    private fun flag(value: Boolean) = if (value) 1f else 0f

    // Your branch, at t seconds into the move.
    fun grow(j: Int, t: Float) = if (grow.isNaN()) flag(!to.lifting) else ((t - grow - (j - 1) * growStep) / growStep).coerceIn(0f, 1f)
    /** A node's pop as the growing branch reaches it; before the branch grows (Start) only its ghost shows. */
    fun pop(j: Int, t: Float) = if (grow.isNaN()) flag(!to.lifting) else HouseSpring.bouncy(t - grow - (j * growStep - .05f))
    fun ghost(t: Float) = when { to.lifting -> .5f; from?.lifting == true && !grow.isNaN() -> .5f * (1 - ((t - grow) / growSeconds).coerceIn(0f, 1f)); else -> 0f }
    fun lift(t: Float) = when {
        to.lifting -> if (lift.isNaN()) 1f else HouseSpring.snappy(t - lift)
        from?.lifting == true && !grow.isNaN() -> 1 - ((t - grow - growSeconds) / .8f).coerceIn(0f, 1f)
        else -> 0f
    }
    fun ark(t: Float) = when {
        to.explain -> 1f
        !cancel.isNaN() -> between(arkTarget(was), arkTarget(to), cancel, HouseSpring.soft, t)
        to.lifting -> 1 - .6f * lift(t)
        !ark.isNaN() -> lerp(arkTarget(was), arkTarget(to), ((t - ark) / .7f).coerceIn(0f, 1f))
        else -> arkTarget(to)
    }
    fun cancel(t: Float) = between(flag(was.canceled), flag(to.canceled), cancel, HouseSpring.soft, t)
    private fun sent(mark: ExitScene.Mark?) = flag(mark == ExitScene.Mark.waiting || mark == ExitScene.Mark.mined)
    fun send(i: Int, t: Float) = if (to.canceled && !was.canceled) sent(wasMark(i)) * (1 - cancel(t)) else between(sent(wasMark(i)), sent(to.marks[i]), send[i], HouseSpring.snappy, t)
    fun mine(i: Int, t: Float) = between(flag(wasMark(i) == ExitScene.Mark.mined), flag(to.mined(i)), mine[i], HouseSpring.snappy, t)
    fun stamp(i: Int, t: Float) = if (mine[i].isNaN()) 1f else HouseSpring.bouncy(t - mine[i])
    fun ripple(i: Int, t: Float) = if (mine[i].isNaN()) 0f else ((t - mine[i]) / .8f).coerceIn(0f, 1f)
    fun fee(t: Float) = if (fee.isNaN()) 1f else HouseSpring.soft(t - fee)
    fun bump(t: Float) = if (bump.isNaN()) flag(to.attention == ExitStage.Attention.feesRose) else HouseSpring.soft(t - bump - .15f)
    fun flash(t: Float) = !flash.isNaN() && t >= flash && t - flash < .5f
    fun wallet(t: Float) = between(flag(was.wallet), flag(to.wallet), wallet, HouseSpring.soft, t)
    // The timelock.
    fun lock(t: Float) = between(flag(was.locked), flag(to.locked), lock, HouseSpring.soft, t)
    fun padlock(t: Float) = if (lock.isNaN()) flag(to.locked) else HouseSpring.bouncy(t - lock)
    fun dial(t: Float) = between(was.dial, to.dial, dial, HouseSpring.snappy, t)
    fun open(t: Float) = between(flag(was.open), flag(to.open), open, HouseSpring.bouncy, t)
    // The claim.
    fun dim(t: Float) = between(flag(was.claiming), flag(to.claiming), claim, HouseSpring.soft, t)
    fun stem(t: Float) = between(flag(was.claiming), flag(to.claiming), claim, HouseSpring.calm, t)
    fun claimSend(t: Float) = between(flag(was.claiming), flag(to.claiming), claimSend, HouseSpring.snappy, t)
    fun claimMine(t: Float) = between(flag(was.claimed), flag(to.claimed), claimMine, HouseSpring.snappy, t)
    fun claimStamp(t: Float) = if (claimMine.isNaN()) 1f else HouseSpring.bouncy(t - claimMine)
    fun claimRipple(t: Float) = if (claimMine.isNaN()) 0f else ((t - claimMine) / .8f).coerceIn(0f, 1f)
    fun land(t: Float) = if (claimMine.isNaN()) flag(to.claimed) else HouseSpring.soft(t - claimMine - .5f)
    fun arrive(t: Float) = if (claimMine.isNaN()) flag(to.claimed) else HouseSpring.bouncy(t - claimMine - 1.25f)
    fun ring(t: Float) = if (claimMine.isNaN()) flag(to.claimed) else HouseSpring.soft(t - claimMine - 1.35f)
    /** The phone badge becomes the other wallet's as the claim goes there. */
    fun swap(t: Float) = if (to.destination == null || !to.claiming) 0f else dim(t)
    fun slide(t: Float) = if (slide.isNaN()) 1f else HouseSpring.soft(t - slide)
    /**
     * Each level's y at t in [geo] (the drawn path's): as the move's springs set it and, when the path folds differently,
     * from where its transaction was drawn in [before] (the levels' rest before this move).
     */
    fun ys(geo: TreeGeometry, before: FloatArray?, t: Float): FloatArray {
        val ys = geo.levels(lock(t) * (1 - stem(t)), to.docked, to.ringed) { mine(it, t) }
        if (restructured && before != null) slide(t).let { u ->
            for (j in 1 until n) was(j - 1).takeIf { it >= 0 && it + 1 < before.size }?.let { ys[j] = lerp(before[it + 1], ys[j], u) }
        }
        return ys
    }
    /**
     * Your other exit's coin [sibling] at t, riding its level ([ys] now): from where it was ([old], at [before]'s levels) to
     * its place, matched by the exit rather than by the level it leaves from.
     */
    fun sibling(sibling: TreeLayout.Sibling, old: TreeLayout.Sibling?, ys: FloatArray, before: FloatArray?, t: Float): Pair<Float, Float> {
        val y = ys[sibling.level]
        if (old == null || before == null) return sibling.x to y + sibling.dy
        val start = if (restructured) was(sibling.level - 1).takeIf { it >= 0 }?.let { before.getOrNull(it + 1) } else before.getOrNull(sibling.level)
        val u = slide(t)
        return lerp(old.x, sibling.x, u) to y + lerp(before[old.level] + old.dy - (start ?: y), sibling.dy, u)
    }
    /** New labels fade in; the old ones out. */
    fun labels(t: Float) = when { labels.isNaN() -> 1f; to.explain -> ((t - labels) / .5f).coerceIn(0f, 1f); else -> HouseSpring.snappy(t - labels) }
}
