package com.noahwallet.onchain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
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
) {
    enum class Mark { unsent, needsFee, waiting, mined }
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

    companion object {
        /** A round VTXO's branch before anything is sent (the explainer and review): radix 4, so a 64-leaf round's four. */
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
                    status?.kind == "awaiting-cpfp-broadcast" -> if (needsFunds) Mark.needsFee else Mark.waiting
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
 * The picture's geometry in dp (prototype `heroGeo`): the ground line, the round's block on it, the trunk's x, the label
 * column, your coin's height and the wallet badge. [scale] is the label size: the font scale, up to 1.25.
 */
internal class TreeGeometry(val width: Float, val height: Float, val n: Int, val scale: Float, wide: Boolean) {
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
    /** Each transaction's square: smaller on long paths. */
    val node = if (room / n.coerceAtLeast(1) < 22) 11f else 14f
    val lineHeight = 14 * scale

    /**
     * Level heights: 0 is the round's block, j the j-th transaction, [n] your coin. When a long path doesn't fit at 26 dp,
     * mined transactions ([mined], 0 → 1 each) stack tighter, like a pile of blocks; the waiting one and your coin never do.
     */
    fun levels(mined: (Int) -> Float): FloatArray {
        val tight = if (room / n.coerceAtLeast(1) >= 26) 1f else .42f
        fun confirmed(j: Int) = if (j == 0) 1f else mined(j - 1)
        val weights = FloatArray(n + 1) { j -> if (j == 0) 0f else 1 + (tight - 1) * min(confirmed(j - 1), confirmed(j)) }
        val sum = weights.sum()
        var acc = 0f
        return FloatArray(n + 1) { j -> acc += weights[j]; roundY - room * acc / sum }
    }

    /** Other people's branches are drawn only where a level has 20 dp to the next. */
    fun stubs(levels: FloatArray, j: Int) = j in 1 until n && levels[j] - levels[j + 1] >= 20

    /** The claim's stem, from your coin to the destination badge (prototype `cub`). */
    fun stem(u: Float): Pair<Float, Float> {
        val x0 = trunk + coinR + 3; val y0 = coinY; val x1 = walletX - 6; val y1 = coinY
        val x3 = walletX; val y3 = walletY - 19; val x2 = walletX; val y2 = coinY + (y3 - coinY) * .3f
        val a = 1 - u
        return (a * a * a * x0 + 3 * a * a * u * x1 + 3 * a * u * u * x2 + u * u * u * x3) to
            (a * a * a * y0 + 3 * a * a * u * y1 + 3 * a * u * u * y2 + u * u * u * y3)
    }
    val claimNode get() = stem(.42f)
}

/**
 * The label column (prototype `heroLabels`): highest priority first, nothing overlaps. Mined transactions that don't fit
 * fold into one range, "Blocks 921,346–921,349"; the waiting transaction, the claim and your coin are never squeezed.
 */
internal object TreeLabels {
    enum class Tone { strong, muted, failure }
    enum class Pill { orange, grey, failure }
    data class Line(val text: String, val tone: Tone = Tone.muted, val bold: Boolean = false)
    /** [x] for a label beside the claim, right-aligned; [height] a mined transaction's block, which may fold into a range. */
    data class Item(val y: Float, val lines: List<Line>, val priority: Float, val pill: Pill? = null, val height: Long? = null,
                    val x: Float? = null, val brace: Pair<Float, Float>? = null, val key: String = lines.joinToString("|") { it.text })
    data class Placed(val item: Item, val top: Float, val bottom: Float) { val right get() = item.x != null }

    fun height(item: Item, lineHeight: Float, scale: Float) = item.lines.size * lineHeight + if (item.pill != null) 5 * scale else 0f

    /**
     * [range] writes the folded heights' label. Mined transactions go in at the latest one's priority: if they don't all fit,
     * the latest keeps its own label when it can and the others fold into one range, as near their middle as it fits.
     */
    fun place(items: List<Item>, lineHeight: Float, scale: Float, range: (Long, Long) -> String): List<Placed> {
        val placed = mutableListOf<Placed>()
        fun clash(top: Float, bottom: Float, right: Boolean, margin: Float) =
            placed.any { p -> p.right == right && !(bottom + margin < p.top || top - margin > p.bottom) }
        fun put(item: Item, lines: Int = item.lines.size, margin: Float = 3f): Boolean {
            val top = item.y - lineHeight / 2
            val bottom = top + if (lines == item.lines.size) height(item, lineHeight, scale) else lines * lineHeight
            if (clash(top, bottom, item.x != null, margin)) return false
            placed += Placed(item, top, bottom); return true
        }
        val blocks = items.filter { it.height != null }.sortedByDescending { it.priority }
        val first = blocks.firstOrNull()?.priority ?: Float.NEGATIVE_INFINITY
        val others = items.filter { it.height == null }.sortedByDescending { it.priority }
        others.filter { it.priority > first }.forEach { put(it) }
        if (blocks.isNotEmpty()) {
            val before = placed.size
            if (!blocks.all { put(it) }) {
                while (placed.size > before) placed.removeAt(placed.lastIndex)
                val rest = if (put(blocks.first())) blocks.drop(1) else blocks
                if (rest.size == 1) put(rest.single(), margin = 1f)
                else if (rest.size > 1) {
                    val heights = rest.mapNotNull { it.height }.sorted()
                    val middle = rest.map { it.y }.average().toFloat()
                    val label = listOf(Line(range(heights.first(), heights.last()), Tone.strong))
                    (listOf(middle) + rest.map { it.y }.sortedBy { kotlin.math.abs(it - middle) })
                        .firstOrNull { y -> put(Item(y, label, 1f, key = "range")) }
                }
            }
        }
        others.filter { it.priority <= first }.forEach { put(it) }
        return placed
    }
}

/** The timelock's dial: one tick per 6 blocks, up to 48; a longer tick every sixth when the lock reads as a clock (72+). */
internal object ExitDial {
    fun ticks(delta: Long) = ((delta + 5) / 6).toInt().coerceIn(1, 48)
    fun major(delta: Long, tick: Int) = delta >= 72 && tick % 6 == 0
}

/** Greedy word wrap for a picture caption; text without spaces (Japanese) breaks between characters. */
internal fun wrapWords(text: String, width: Float, measure: (String) -> Float): List<String> {
    val words = if (' ' in text) text.split(' ') else text.map { it.toString() }
    val glue = if (' ' in text) " " else ""
    val lines = mutableListOf<String>(); var line = ""
    for (word in words) {
        val next = if (line.isEmpty()) word else line + glue + word
        if (measure(next) <= width || line.isEmpty()) line = next else { lines += line; line = word }
    }
    if (line.isNotEmpty()) lines += line
    return lines
}

/**
 * The Exits list's gutters (exit-v2-notes.md, Approved 3: option B). Exits that share transactions share one gutter, their
 * paths drawn once, lying on their side; every other row has only its coin and its state.
 */
internal object ExitGutter {
    enum class St { mined, waiting, unsent, needsFunds }
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
                kind == "awaiting-confirmation" || kind == "awaiting-cpfp-broadcast" -> St.waiting
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

    /** A drawn transaction: its place in the gutter (dp) and its state; [coin] is an exit's own output, at the right. */
    data class Node(val id: String, val x: Float, val y: Float, val st: St, val coin: Boolean)
    data class Link(val from: Pair<Float, Float>, val to: Pair<Float, Float>, val ink: Boolean)
    data class Layout(val root: Pair<Float, Float>, val nodes: List<Node>, val links: List<Link>)

    /** The gutter for a shared group: the round's block at [x0], each transaction [step] further, every coin at [coinX]. */
    const val sharedWidth = 112f
    const val coinWidth = 44f
    fun shared(paths: List<List<Pair<String, St>>>, ys: List<Float>, x0: Float = 2f, coinX: Float = 90f, step: Float = 12f): Layout {
        val longest = paths.maxOfOrNull { it.size } ?: 1
        // A long path steps closer, so its last transaction stays clear of the coins.
        val d = if (longest <= 1) step else min(step, (coinX - 14 - 6 - x0) / (longest - 1))
        val rows = mutableMapOf<String, MutableList<Float>>()
        paths.forEachIndexed { r, path -> path.dropLast(1).forEach { (id, _) -> rows.getOrPut(id) { mutableListOf() } += ys[r] } }
        val root = x0 to ys.average().toFloat()
        val nodes = linkedMapOf<String, Node>(); val links = linkedMapOf<String, Link>()
        paths.forEachIndexed { r, path ->
            var from = root; var previous = "root"
            path.forEachIndexed { i, (id, st) ->
                val coin = i == path.lastIndex
                val at = if (coin) coinX to ys[r] else x0 + (i + 1) * d + 6 to rows.getValue(id).average().toFloat()
                val key = if (coin) "$id@$r" else id
                nodes.putIfAbsent(key, Node(id, at.first, at.second, st, coin))
                links.putIfAbsent("$previous>$key", Link(from.first + 4 to from.second, at.first - (if (coin) 11 else 4) to at.second, st == St.mined))
                from = at; previous = key
            }
        }
        return Layout(root, nodes.values.toList(), links.values.toList())
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
    val growStep = .26f
    val growSeconds = growStep * n + .35f
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
    val labels = when { from == null && to.explain && !grow.isNaN() -> growSeconds - .1f; from != null && from != to -> 0f; else -> none }
    /** How long the move runs; the picture is still after it. */
    val seconds: Float

    init {
        val newly = (0 until n).filter { to.mined(it) && !was.mined(it) }
        newly.forEachIndexed { k, i -> mine[i] = k * .3f }
        val afterMine = if (newly.isEmpty()) grown else newly.size * .3f + .2f
        for (i in 0 until n) if (to.marks[i] == ExitScene.Mark.waiting && was.marks.getOrNull(i) != ExitScene.Mark.waiting) send[i] = afterMine
        val w = to.waiting
        val docked = { s: ExitScene -> w >= 0 && s.marks.getOrNull(w) == ExitScene.Mark.waiting && s.paid.getOrElse(w) { false } }
        fee = if (docked(to) && !docked(was)) (send.getOrNull(w)?.takeIf { !it.isNaN() }?.plus(.5f) ?: 0f) else none
        val sameWait = w >= 0 && was.waiting == w
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
        end(cancel, HouseSpring.soft.seconds); end(wallet, HouseSpring.soft.seconds); end(ark, .8f); end(labels, .5f)
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
    private fun sent(s: ExitScene, i: Int) = flag(s.marks.getOrNull(i).let { it == ExitScene.Mark.waiting || it == ExitScene.Mark.mined })
    fun send(i: Int, t: Float) = if (to.canceled && !was.canceled) sent(was, i) * (1 - cancel(t)) else between(sent(was, i), sent(to, i), send[i], HouseSpring.snappy, t)
    fun mine(i: Int, t: Float) = between(flag(was.mined(i)), flag(to.mined(i)), mine[i], HouseSpring.snappy, t)
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
    /** New labels fade in; the old ones out. */
    fun labels(t: Float) = when { labels.isNaN() -> 1f; to.explain -> ((t - labels) / .5f).coerceIn(0f, 1f); else -> HouseSpring.snappy(t - labels) }
}
