package com.noahwallet.onchain

import android.app.Application
import android.content.res.Configuration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min
import com.noahwallet.onchain.ExitScene.Mark as m

/**
 * exit-v2's one rule, swept (exit-v2-notes.md, The picture): in every layout no two drawn elements overlap and none leaves
 * the canvas, required ones always show and keep their spacing, and a long path folds rather than squeezes. Paths of 1 to
 * 100 transactions in every state and as the explainer, with none, some or all mined and your other exits at pairs of levels,
 * measured with the picture's own text measurement. Each case takes the next of four setups in turn (a phone at the default
 * text size or a small phone at twice it, in English or in Swedish with sats), so each state meets all four across the
 * lengths. The Exits list's shared gutters and Review's rail fold the same paths the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExitTreeSweepTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f, 1f), LayoutDirection.Ltr, 512)
    private val widths = HashMap<String, Float>()
    private val measure = TreeMeasure { text, size, bold ->
        widths.getOrPut("$bold|$size|$text") {
            measurer.measure(text, TextStyle(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, fontSize = size.sp)).size.width.toFloat()
        }
    }

    private fun words(language: String): TreeStrings {
        val locale = Locale.forLanguageTag(language)
        val resources = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }).resources
        return TreeStrings.of(resources, context.packageName) { BitcoinAmount.format(it, BitcoinAmountUnit.sats, locale) }
    }

    /** A picture's size in dp and its labels' scale: Android's picture spans the page's column (the screen less 48 dp). */
    private data class Frame(val name: String, val width: Float, val height: Float, val scale: Float)
    private val phone = Frame("phone 412 dp, default text", 364f, 300f, 1f)
    private val small = Frame("small phone 360 dp, 2x text", 312f, 300f, 1.25f)
    private val frames = listOf(phone, small)

    private var layouts = 0

    /** Lays [scene] out as the picture does (folded to fit, or to [levels] when that's fewer) and checks the rule. */
    private fun check(scene: ExitScene, frame: Frame, words: TreeStrings, what: String, levels: Int? = null): TreeLayout {
        val label = "${frame.name}, $what"
        val cap = TreeGeometry(frame.width, frame.height, 1, frame.scale, false).capacity(if (scene.locked) 1f else 0f).let { min(it, levels ?: it) }
        val drawn = scene.fold(if (scene.explain) min(cap, 8) else cap)
        val layout = TreeLayout.of(drawn, frame.width, frame.height, frame.scale, false, words, measure, balance = 18_400, received = 30_738)
        assertApart(label, layout.shapes, frame)
        assertComplete(label, scene, drawn, layout)
        layouts++
        return layout
    }

    /** Every mined block is in a label: its own, or a range with the others that have none. */
    private fun assertBlocksLabelled(label: String, scene: ExitScene, layout: TreeLayout) {
        for (i in 0 until scene.n) {
            val height = scene.heights[i]?.takeIf { scene.mined(i) && !(i == scene.n - 1 && scene.locked) } ?: continue
            assertTrue("$label: block $height has no label (${layout.labels.mapNotNull { it.blocks }})",
                layout.labels.any { it.blocks?.contains(height) == true })
        }
    }

    /** Every shape stays on the canvas and none overlaps another it may not meet. */
    private fun assertApart(label: String, shapes: List<TreeShape>, frame: Frame) {
        for (shape in shapes) assertTrue("$label: ${shape.group} leaves the canvas: $shape", shape.inside(frame.width, frame.height))
        // A branch may meet the stubs, the other branches and the level it joins; nothing else may touch.
        fun meets(a: TreeShape, b: TreeShape) = a.kind == TreeShape.Kind.branch &&
            (b.kind == TreeShape.Kind.stub || b.kind == TreeShape.Kind.branch || b.group == "node" + a.group.removePrefix("sibling").substringBefore('.'))
        for (i in shapes.indices) for (j in i + 1 until shapes.size) {
            val (a, b) = shapes[i] to shapes[j]
            if (a.group != b.group && !meets(a, b) && !meets(b, a)) assertFalse("$label: ${a.group} overlaps ${b.group}: $a / $b", a.overlaps(b))
        }
    }

    /** Everything required is drawn, far enough apart, and every mined block is labelled. */
    private fun assertComplete(label: String, scene: ExitScene, drawn: ExitScene, layout: TreeLayout) {
        val shapes = layout.shapes
        // Required: every drawn level of your path, your coin, the round's block; the pill and the claim's label.
        assertEquals("$label: nodes", drawn.n - 1, shapes.map { it.group }.filter { it.startsWith("node") }.distinct().size)
        assertTrue(label, shapes.any { it.kind == TreeShape.Kind.coin } && shapes.any { it.kind == TreeShape.Kind.round })
        if (drawn.waiting >= 0 || ExitScene.Mark.needsFee in drawn.marks) assertTrue("$label: the pill", layout.labels.any { it.pill != null && it.x == null })
        if (drawn.claiming) assertTrue("$label: the claim's label", layout.labels.any { it.x != null })
        // Never below the minimum spacing; long paths fold instead.
        val geo = layout.geo; val lock = if (drawn.locked && !drawn.claiming) 1f else 0f
        for (j in 1..drawn.n) assertTrue("$label: gap $j",
            layout.ys[j - 1] - layout.ys[j] >= geo.minimumGap(j, geo.node, geo.coinReserve(lock), docked = drawn.docked, ringed = drawn.ringed) - .01f)
        assertEquals("$label: the real count", scene.n, drawn.count)
        if (!drawn.explain && !drawn.claiming) assertBlocksLabelled(label, scene, layout)
        // Every other exit finds a spot here, so none is left out without the overlap check seeing it.
        assertEquals("$label: your other exits", drawn.others.count { it.level in 1 until drawn.n }, layout.siblings.size)
    }

    @Suppress("LongParameterList") // Fixture builder: each parameter is a named, defaulted field of the model it builds.
    private fun path(n: Int, mined: Int, current: ExitScene.Mark? = m.waiting, phase: ExitStage.Phase = ExitStage.Phase.bump,
        attention: ExitStage.Attention? = null, theirs: Boolean = false, others: List<ExitScene.Other> = emptyList()) = ExitScene(
        n, phase, List(n) { when { it < mined -> m.mined; it == mined -> current ?: m.unsent; else -> m.unsent } },
        heights = List(n) { if (it < mined) 921_346L + it else null }, theirs = List(n) { theirs && it == mined },
        paid = List(n) { it < mined || (it == mined && current == m.waiting) }, shared = List(n) { i -> others.any { i < it.level } },
        attention = attention, fee = if (current == m.waiting && !theirs) 3_645 else null, rate = 9, waited = 3, tip = 921_400,
        need = if (current == m.needsFee) 3_765 else null, others = others)

    /** Every state the picture has, for an [n]-transaction path. */
    private fun states(n: Int): List<Pair<String, ExitScene>> {
        val half = n / 2; val done = List(n) { m.mined }
        val locked = { phase: ExitStage.Phase, destination: String? ->
            ExitScene(n, phase, done, List(n) { 921_346L + it }, paid = List(n) { true }, tip = 921_400, lockedFrom = 921_346L + n,
                claimableHeight = 921_490L + n, unlockedAt = 921_490L + n, claimedBlock = 921_491L + n, claimFee = 262, destination = destination)
        }
        val other = "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg"
        return listOf(
            "Start" to path(n, 0, null, ExitStage.Phase.lift), "publish" to path(n, 0, null, ExitStage.Phase.publish),
            "ready" to path(n, half, m.ready, ExitStage.Phase.mempool),
            "needs funds" to path(n, half, m.needsFee, ExitStage.Phase.mempool, ExitStage.Attention.needsFunds),
            "waiting" to path(n, half), "fees rose" to path(n, half, attention = ExitStage.Attention.feesRose),
            "stuck" to path(n, half, attention = ExitStage.Attention.stuck),
            "sent by someone else" to path(n, half, attention = ExitStage.Attention.shared, theirs = true),
            "stale" to path(n, half).copy(stale = true, checkedAt = 921_348), "confirmed" to path(n, half, null, ExitStage.Phase.confirm),
            "on-chain" to ExitScene(n, ExitStage.Phase.onchain, done, List(n) { 921_346L + it }, paid = List(n) { true }),
            "locked" to locked(ExitStage.Phase.wait, null), "claimable" to locked(ExitStage.Phase.claimable, null),
            "claiming to another wallet" to locked(ExitStage.Phase.claiming, other), "claiming to this phone" to locked(ExitStage.Phase.claiming, null),
            "claimed to another wallet" to locked(ExitStage.Phase.claimed, other), "claimed to this phone" to locked(ExitStage.Phase.claimed, null),
            "canceled" to path(n, half, null, ExitStage.Phase.canceled),
        )
    }

    /** Path lengths the sweeps sample: every level drawn up to about a dozen, folded beyond. */
    private val lengths = listOf(1, 2, 3, 5, 8, 13, 20, 100)
    /** A range's ends, its middle and their neighbours. */
    private fun spots(range: IntRange) = listOf(range.first, range.first + 1, (range.first + range.last) / 2, range.last - 1, range.last)
        .filter { it in range }.distinct()

    @Test fun noTwoElementsOverlapAndNoneLeavesThePicture() {
        val strings = listOf("en", "sv").associateWith { words(it) }
        val setups = strings.keys.flatMap { language -> frames.map { language to it } }
        for ((place, n) in lengths.withIndex()) {
            var case = place
            fun draw(scene: ExitScene, what: String, height: Float? = null) {
                val (language, frame) = setups[case++ % setups.size]
                check(scene, if (height == null) frame else frame.copy(height = height), strings.getValue(language), "$language, $n transactions, $what")
            }
            // Every state, and the explainer at the Emergency exit page's height too.
            for ((name, scene) in states(n)) draw(scene, name)
            draw(ExitScene.sample(n), "explainer"); draw(ExitScene.sample(n), "explainer at 268 dp", 268f)
            // The next one waiting, ready, short of funds or not sent yet.
            for (mined in spots(0 until n)) for (current in listOf(m.waiting, m.ready, m.needsFee, null)) {
                draw(path(n, mined, current, if (current == null) ExitStage.Phase.confirm else ExitStage.Phase.bump), "$mined mined, $current")
            }
            drawOthers(n) { scene, what -> draw(scene, what) }
        }
        println("ExitTreeSweepTest: $layouts layouts checked")
    }

    /** Your other exits: one, two at a pair of levels, or a third beside the second. */
    private fun drawOthers(n: Int, draw: (ExitScene, String) -> Unit) {
        val levels = spots(1 until n)
        for (a in levels) {
            draw(path(n, n / 2, others = listOf(ExitScene.Other(a, 24_500))), "one other at $a")
            for (b in levels.filter { it >= a }) {
                val others = listOf(ExitScene.Other(a, 24_500), ExitScene.Other(b, 21_000))
                draw(path(n, n / 2, others = others), "others at $a and $b")
                if (a == b) draw(path(n, n / 2, others = others + ExitScene.Other(a, 15_000)), "three others at $a")
            }
        }
    }

    @Test fun theGutterAndReviewsRailFoldRatherThanSqueeze() {
        var gutters = 0; var rails = 0
        for (n in lengths) {
            // Two to four exits that part after k shared transactions, each a block (or a fifth of the path) behind the one
            // before, with none, some or all mined and the one being sent waiting, ready, short of funds or not sent yet.
            // Or, paired, two branches after k that part again halfway on, listed alternately and sat together as the list does.
            gutters += gutterSweeps(n)
            // Review's rail at a 320, 360 and 412 dp phone's column: neighbours 2 dp apart at least, clear of the round's block
            // and the dial, the first transaction and your coin always drawn, the real count in its stops.
            for (width in listOf(272f, 312f, 364f)) {
                checkRail(n, width)
                rails++
            }
        }
        println("ExitTreeSweepTest: $gutters gutters and $rails rails checked")
    }

    /** [rows] exits of [n] transactions, each [lag] behind the one before, [paired] or not. */
    private data class Gutter(val n: Int, val rows: Int, val lag: Int, val paired: Boolean)

    private fun gutterSweeps(n: Int): Int {
        var checked = 0
        for (rows in 2..4) for (lag in listOf(1, maxOf(2, n / 5)).distinct()) for (paired in listOf(false, true)) {
            checked += gutterSweep(Gutter(n, rows, lag, paired))
        }
        return checked
    }

    private fun gutterSweep(gutter: Gutter): Int {
        var checked = 0
        for (k in spots(0 until gutter.n)) for (mined in spots(0..gutter.n)) {
            for (current in listOf(ExitGutter.St.waiting, ExitGutter.St.ready, ExitGutter.St.needsFunds, ExitGutter.St.unsent)) {
                checkGutter(gutter, k, mined, current)
                checked++
            }
        }
        return checked
    }

    /** The [gutter]'s exits as the list holds them: shared ids up to [k], then each exit's own, [mined] of the first mined. */
    private fun listed(gutter: Gutter, k: Int, mined: Int, current: ExitGutter.St) = List(gutter.rows) { r ->
        val done = (mined - r * gutter.lag).coerceAtLeast(0)
        List(gutter.n) { i ->
            val id = if (i < k) "s$i" else if (gutter.paired && i < (k + gutter.n) / 2) "b${r % 2}.$i" else "r$r.$i"
            id to when {
                i < done -> ExitGutter.St.mined
                i == done -> current
                else -> ExitGutter.St.unsent
            }
        }
    }

    private fun checkGutter(gutter: Gutter, k: Int, mined: Int, current: ExitGutter.St) {
        val paths = ExitGutter.tree(listed(gutter, k, mined, current), { path -> path.dropLast(1).map { it.first } })
        val label = "gutter: ${gutter.rows} exits${if (gutter.paired) " paired" else ""}, ${gutter.n} transactions, parting after $k, $mined mined, " +
            "${gutter.lag} behind, $current"
        val layout = ExitGutter.shared(paths, List(gutter.rows) { 32f + 64f * it })
        val drawn = layout.nodes.filter { !it.coin }
        val columns = drawn.map { it.x }.distinct().sorted()
        assertSpread(label, drawn, columns)
        // What they share is drawn once: they leave the round together.
        if (k >= 1) assertEquals("$label: the first column", 1, drawn.count { it.x == columns.first() })
        // Each exit's transaction being sent keeps its own place.
        val states = ExitGutter.states(paths)
        for (path in paths) path.dropLast(1).firstOrNull { states.getValue(it.first) != ExitGutter.St.mined }?.let { (id, _) ->
            assertTrue("$label: $id is being sent", drawn.any { it.id == id && !it.folded })
        }
    }

    /** Columns a step apart and clear of the coins, and no two nodes on top of each other. */
    private fun assertSpread(label: String, drawn: List<ExitGutter.Node>, columns: List<Float>) {
        for ((a, b) in columns.zipWithNext()) assertTrue("$label: columns $a and $b", b - a >= ExitGutter.minStep - .01f)
        columns.lastOrNull()?.let { assertTrue("$label: clear of the coins", it <= 90f - 14) }
        for (a in drawn) for (b in drawn) if (a !== b)
            assertFalse("$label: ${a.id} on ${b.id}", kotlin.math.abs(a.x - b.x) < 10 && kotlin.math.abs(a.y - b.y) < 10)
    }

    private fun checkRail(n: Int, width: Float) {
        val stops = ExitRail.stops(n, 40f, width * .36f - 44, width / 2 - 21, { "+$it" }) { measure.width(it, ExitRail.moreSize, true) }
        val label = "rail: $n transactions, $width dp"
        for ((a, b) in stops.zipWithNext()) assertTrue("$label: ${a.x} → ${b.x}", b.x - a.x >= a.half + b.half + 2 - .01f)
        assertTrue(label, stops.first().x - stops.first().half >= 26f + 2)
        assertTrue(label, stops.last().coin && stops.last().x + stops.last().half <= width / 2 - 17)
        assertTrue(label, n == 1 || stops.first().more == null)
        assertEquals(label, n, stops.sumOf { it.more ?: 1 })
    }

    @Test fun anOrdinaryPictureKeepsEverything() {
        // The sweep's measure is the real font's, not zero.
        assertTrue(measure.width("Next block", 11.5f, true) in 50f..80f)
        val w = words("en")
        val layout = check(path(4, 1, others = listOf(ExitScene.Other(1, 24_500), ExitScene.Other(3, 21_000))), phone, w, "four transactions, two others")
        assertTrue("stubs", layout.stubs.size >= 6)
        assertNotNull("the caption", layout.caption)
        assertEquals("both other exits, with their amounts", listOf(true, true), layout.siblings.map { it.amount != null })
        assertTrue("the round, the latest block, the pill and your coin", layout.labels.size >= 4)
        val explainer = check(ExitScene.sample(4), phone.copy(height = 268f), w, "explainer")
        assertEquals("${explainer.labels.map { it.key }} ${explainer.stubs.size} stubs", 3, explainer.labels.size)
        assertNotNull("${explainer.stubs}", explainer.caption)
    }

    @Test fun theSwedishFeeWrapsInsideItsColumnAtTwiceTheText() {
        val w = words("sv")
        assertEquals("Nästa block", w.tr("Next block"))
        val layout = check(path(4, 1, m.needsFee, ExitStage.Phase.mempool, ExitStage.Attention.needsFunds), small, w, "needs funds")
        val label = layout.labels.single { it.pill == TreeLabels.Pill.failure }
        assertTrue("it wrapped: ${label.lines}", label.lines.size > 2)
        // Swedish groups digits with a no-break space, which keeps the amount on one line.
        assertTrue(label.lines.toString(), label.lines.any { it.text.replace('\u00a0', ' ').replace('\u202f', ' ').contains("3 765 sats") })
        val box = layout.shapes.single { it.kind == TreeShape.Kind.label && it.group == "label${layout.labels.indexOf(label)}" }
        assertTrue("$box", box.r <= layout.geo.width - 12)
    }

    @Test fun aFoldedRangeCoversEveryBlockWithoutALabel() {
        // Five of twenty mined in nine levels, two of them stacked: whichever blocks have no label of their own, one range
        // covers them all, never just the stack's two.
        for (language in listOf("en", "sv")) for (frame in listOf(phone, small)) for (current in listOf(m.waiting, m.needsFee)) {
            checkFolded(language, frame, current)
        }
    }

    private fun checkFolded(language: String, frame: Frame, current: ExitScene.Mark) {
        val w = words(language); val scene = path(20, 5, current)
        val layout = check(scene, frame, w, "$language, twenty transactions, five mined, $current", levels = 9)
        assertEquals("${frame.name}: ${layout.scene.spans}", 0..1, layout.scene.spans?.first())
        for (height in scene.heights.filterNotNull())
            assertTrue("${frame.name}, $language: block $height", layout.labels.any { it.blocks?.contains(height) == true })
    }

    @Test fun aRefoldFollowsEachTransaction() {
        // Twenty transactions in nine levels, the stack going from five mined to six, another exit leaving after the fourth:
        // the levels shift by one, so the move must match each transaction, never the drawn level.
        val w = words("en"); val others = listOf(ExitScene.Other(4, 24_500))
        for (frame in listOf(phone, small)) {
            val before = check(path(20, 5, others = others), frame, w, "five of twenty mined", levels = 9)
            val after = check(path(20, 6, others = others), frame, w, "six of twenty mined", levels = 9)
            val move = TreeMotion(before.scene, after.scene)
            assertTrue(move.restructured)
            // Only the sixth is mined and only the seventh sent.
            assertEquals(listOf(5), (0 until move.n).filter { !move.mine[it].isNaN() }.map { after.scene.real(it) })
            assertEquals(listOf(6), (0 until move.n).filter { !move.send[it].isNaN() }.map { after.scene.real(it) })
            // Each level starts where its transaction was drawn and ends at its new place.
            val start = move.ys(after.geo, before.ys, 0f); val end = move.ys(after.geo, before.ys, move.seconds)
            for (j in 1 until move.n) {
                val real = after.scene.real(j - 1); val was = checkNotNull(before.scene.spans).indexOfFirst { real in it }
                assertEquals("${frame.name}: level $j starts at transaction $real's place", before.ys[was + 1], start[j], .01f)
                assertEquals("${frame.name}: level $j ends at its place", after.ys[j], end[j], .01f)
            }
            // The third block loses its own level and slides into the stack; the ninth comes out of "+N".
            assertEquals(listOf(1 to 0), move.merging)
            assertTrue(move.emerges(checkNotNull(after.scene.spans).indexOfFirst { it == 8..8 }))
            // Your other exit's coin starts where it was, though its level moved down one, and ends at its place.
            val old = before.siblings.single(); val sibling = after.siblings.single()
            assertEquals(old.level - 1, sibling.level)
            val (x0, y0) = move.sibling(sibling, old, start, before.ys, 0f); val (x1, y1) = move.sibling(sibling, old, end, before.ys, move.seconds)
            assertEquals(old.x, x0, .01f); assertEquals(before.ys[old.level] + old.dy, y0, .01f)
            assertEquals(sibling.x, x1, .01f); assertEquals(after.ys[sibling.level] + sibling.dy, y1, .01f)
        }
    }

    /** No other exit's branch runs through another's coin. */
    private fun assertBranchesClear(frame: Frame, layout: TreeLayout) {
        for (a in layout.siblings) for (b in layout.siblings) if (a !== b) {
            for (i in 0..40) {
                val (x, y) = linkAt(a.x, layout.ys[a.level] + a.dy + 6, layout.geo.trunk, layout.ys[a.level] - 4, i / 40f)
                assertTrue("${frame.name}: $a's branch crosses $b", hypot(x - b.x, y - (layout.ys[b.level] + b.dy)) >= 6 + .7f)
            }
        }
    }

    @Test fun otherExitsStayInsideAndApart() {
        val w = words("en")
        // Three live exits sharing a two-transaction path: the others branch at the root.
        val two = check(path(2, 0, others = listOf(ExitScene.Other(1, 24_500), ExitScene.Other(1, 21_000))), phone, w, "two transactions, two others")
        assertEquals(2, two.siblings.size)
        for (sibling in two.siblings) assertTrue("$sibling", sibling.x - 6 >= 0 && two.ys[sibling.level] + sibling.dy - 6 >= 0)
        // A third at the same level isn't left out while there's room: it steps further out, like the second.
        for (frame in listOf(phone, small)) {
            val others = listOf(ExitScene.Other(1, 24_500), ExitScene.Other(1, 21_000), ExitScene.Other(1, 15_000))
            assertEquals("three others on ${frame.name}", 3, check(path(2, 0, others = others), frame, w, "two transactions, three others").siblings.size)
        }
        // Four transactions, the first waiting, three others leaving at the root: no branch, drawn whole, runs through
        // another's coin.
        for (frame in listOf(phone, small)) {
            val layout = check(path(4, 0, others = List(3) { ExitScene.Other(1, 24_500L - 3_000 * it) }), frame, w,
                "four transactions, three others at the root")
            assertBranchesClear(frame, layout)
        }
        // Three leaving at the last level beside your coin on the small phone: all of them fit, the first stepping out for
        // the rest.
        val top = check(path(8, 4, others = List(3) { ExitScene.Other(7, 24_500L - 3_000 * it) }), small, w, "eight transactions, three others at 7")
        assertEquals("three others at the last level", 3, top.siblings.size)
        // At packed levels on a long path, they slide or step out, never onto each other.
        val deep = check(path(20, 12, others = listOf(ExitScene.Other(3, 24_500), ExitScene.Other(4, 21_000))), small, w, "packed others")
        val coins = deep.shapes.filter { it.kind == TreeShape.Kind.sibling }
        for (a in coins) for (b in coins) if (a !== b) assertFalse(a.overlaps(b, TreeGeometry.clearance))
    }
}
