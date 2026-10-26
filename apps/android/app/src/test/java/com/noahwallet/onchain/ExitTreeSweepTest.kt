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
import com.noahwallet.onchain.ExitScene.Mark as m
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

/**
 * exit-v2's one rule, swept (exit-v2-notes.md, The picture): in every layout no two drawn elements overlap and none leaves
 * the canvas, required ones always show and keep their spacing, and a long path folds rather than squeezes. Paths of 1–20,
 * 50 and 100 transactions, the explainer, every mined count, your other exits at every pair of levels and every state, on a
 * phone at the default text size and a small phone at twice it, in English and in Swedish with sats, measured with the
 * picture's own text measurement. The Exits list's shared gutters and Review's rail fold the same paths the same way.
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
    private fun check(scene: ExitScene, frame: Frame, words: TreeStrings, what: String, height: Float = frame.height, levels: Int? = null): TreeLayout {
        val label = "${frame.name}, $what"
        val cap = TreeGeometry(frame.width, height, 1, frame.scale, false).capacity(if (scene.locked) 1f else 0f).let { min(it, levels ?: it) }
        val drawn = scene.fold(if (scene.explain) min(cap, 8) else cap)
        val layout = TreeLayout.of(drawn, frame.width, height, frame.scale, false, words, measure, balance = 18_400, received = 30_738)
        val shapes = layout.shapes
        for (shape in shapes) assertTrue("$label: ${shape.group} leaves the canvas: $shape", shape.inside(frame.width, height))
        // A branch may meet the stubs, the other branches and the level it joins; nothing else may touch.
        fun meets(a: TreeShape, b: TreeShape) = a.kind == TreeShape.Kind.branch &&
            (b.kind == TreeShape.Kind.stub || b.kind == TreeShape.Kind.branch || b.group == "node" + a.group.removePrefix("sibling").substringBefore('.'))
        for (i in shapes.indices) for (j in i + 1 until shapes.size) {
            val (a, b) = shapes[i] to shapes[j]
            if (a.group != b.group && !meets(a, b) && !meets(b, a)) assertFalse("$label: ${a.group} overlaps ${b.group}: $a / $b", a.overlaps(b))
        }
        // Required: every drawn level of your path, your coin, the round's block; the pill and the claim's label.
        assertEquals("$label: nodes", drawn.n - 1, shapes.map { it.group }.filter { it.startsWith("node") }.distinct().size)
        assertTrue(label, shapes.any { it.kind == TreeShape.Kind.coin } && shapes.any { it.kind == TreeShape.Kind.round })
        if (drawn.waiting >= 0 || ExitScene.Mark.needsFee in drawn.marks) assertTrue("$label: the pill", layout.labels.any { it.pill != null && it.x == null })
        if (drawn.claiming) assertTrue("$label: the claim's label", layout.labels.any { it.x != null })
        // Never below the minimum spacing; long paths fold instead.
        val geo = layout.geo; val lock = if (drawn.locked && !drawn.claiming) 1f else 0f
        for (j in 1..drawn.n) assertTrue("$label: gap $j", layout.ys[j - 1] - layout.ys[j] >= geo.minimumGap(j, geo.node, geo.coinReserve(lock), docked = drawn.docked, ringed = drawn.ringed) - .01f)
        assertEquals("$label: the real count", scene.n, drawn.count)
        // Every mined block is in a label: its own, or a range with the others that have none.
        if (!drawn.explain && !drawn.claiming) for (i in 0 until scene.n) {
            val height = scene.heights[i]?.takeIf { scene.mined(i) && !(i == scene.n - 1 && scene.locked) } ?: continue
            assertTrue("$label: block $height has no label (${layout.labels.mapNotNull { it.blocks }})", layout.labels.any { it.blocks?.contains(height) == true })
        }
        // Every other exit finds a spot here, so none is left out without the overlap check seeing it.
        assertEquals("$label: your other exits", drawn.others.count { it.level in 1 until drawn.n }, layout.siblings.size)
        layouts++
        return layout
    }

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
            "ready" to path(n, half, m.ready, ExitStage.Phase.mempool), "needs funds" to path(n, half, m.needsFee, ExitStage.Phase.mempool, ExitStage.Attention.needsFunds),
            "waiting" to path(n, half), "fees rose" to path(n, half, attention = ExitStage.Attention.feesRose),
            "stuck" to path(n, half, attention = ExitStage.Attention.stuck), "sent by someone else" to path(n, half, attention = ExitStage.Attention.shared, theirs = true),
            "stale" to path(n, half).copy(stale = true, checkedAt = 921_348), "confirmed" to path(n, half, null, ExitStage.Phase.confirm),
            "on-chain" to ExitScene(n, ExitStage.Phase.onchain, done, List(n) { 921_346L + it }, paid = List(n) { true }),
            "locked" to locked(ExitStage.Phase.wait, null), "claimable" to locked(ExitStage.Phase.claimable, null),
            "claiming to another wallet" to locked(ExitStage.Phase.claiming, other), "claiming to this phone" to locked(ExitStage.Phase.claiming, null),
            "claimed to another wallet" to locked(ExitStage.Phase.claimed, other), "claimed to this phone" to locked(ExitStage.Phase.claimed, null),
            "canceled" to path(n, half, null, ExitStage.Phase.canceled),
        )
    }

    private val lengths = (1..20).toList() + listOf(50, 100)

    @Test fun noTwoElementsOverlapAndNoneLeavesThePicture() {
        for (language in listOf("en", "sv")) {
            val w = words(language)
            for (frame in frames) for (n in lengths) {
                val sampled = n > 20
                // Every state, and the explainer at the Emergency exit page's height too.
                for ((name, scene) in states(n)) check(scene, frame, w, "$language, $n transactions, $name")
                for (height in listOf(frame.height, 268f)) check(ExitScene.sample(n), frame, w, "$language, explainer of $n at $height dp", height)
                // Every mined count: the next one waiting, ready, short of funds, or not sent yet.
                for (mined in (0 until n).filter { !sampled || it % 7 == 0 || it >= n - 3 }) for (current in listOf(m.waiting, m.ready, m.needsFee, null)) {
                    check(path(n, mined, current, if (current == null) ExitStage.Phase.confirm else ExitStage.Phase.bump), frame, w, "$language, $n transactions, $mined mined, $current")
                }
                // Your other exits at every pair of levels (one, two at one level, or a third further along).
                val levels = (1 until n).filter { !sampled || it % 9 == 1 || it == n - 1 }
                for (a in levels) for (b in levels.filter { it >= a }) {
                    val others = listOf(ExitScene.Other(a, 24_500), ExitScene.Other(b, 21_000))
                    check(path(n, n / 2, others = others), frame, w, "$language, $n transactions, others at $a and $b")
                    if (a == b) check(path(n, n / 2, others = others + ExitScene.Other(a, 15_000)), frame, w, "$language, $n transactions, three others at $a")
                }
                for (a in levels) check(path(n, n / 2, others = listOf(ExitScene.Other(a, 24_500))), frame, w, "$language, $n transactions, one other at $a")
            }
        }
        // Reported by the test's name; at least this many layouts were checked.
        assertTrue("$layouts", layouts > 10_000)
        println("ExitTreeSweepTest: $layouts layouts checked")
    }

    @Test fun theGutterAndReviewsRailFoldRatherThanSqueeze() {
        var gutters = 0; var rails = 0
        val st = ExitGutter.St.entries
        for (n in (1..20) + listOf(50, 100)) {
            val sampled = n > 20
            // Two to four exits that part after k shared transactions, each a block (or a fifth of the path) behind the one
            // before, at every mined count and with the one being sent waiting, ready, short of funds or not sent yet.
            // Or, paired, two branches after k that part again halfway on, listed alternately and sat together as the list does.
            for (rows in 2..4) for (lag in listOf(1, maxOf(2, n / 5)).distinct()) for (paired in listOf(false, true)) for (k in (0 until n).filter { !sampled || it % 9 == 1 || it >= n - 2 })
                for (mined in (0..n).filter { !sampled || it % 7 == 0 || it >= n - 2 }) for (current in listOf(ExitGutter.St.waiting, ExitGutter.St.ready, ExitGutter.St.needsFunds, ExitGutter.St.unsent)) {
                    val listed = List(rows) { r ->
                        val done = (mined - r * lag).coerceAtLeast(0)
                        List(n) { i -> (if (i < k) "s$i" else if (paired && i < (k + n) / 2) "b${r % 2}.$i" else "r$r.$i") to when { i < done -> ExitGutter.St.mined; i == done -> current; else -> ExitGutter.St.unsent } }
                    }
                    val paths = ExitGutter.tree(listed, { path -> path.dropLast(1).map { it.first } })
                    val label = "gutter: $rows exits${if (paired) " paired" else ""}, $n transactions, parting after $k, $mined mined, $lag behind, $current"
                    val layout = ExitGutter.shared(paths, List(rows) { 32f + 64f * it })
                    val drawn = layout.nodes.filter { !it.coin }
                    val columns = drawn.map { it.x }.distinct().sorted()
                    for ((a, b) in columns.zipWithNext()) assertTrue("$label: columns $a and $b", b - a >= ExitGutter.minStep - .01f)
                    columns.lastOrNull()?.let { assertTrue("$label: clear of the coins", it <= 90f - 14) }
                    for (a in drawn) for (b in drawn) if (a !== b) assertFalse("$label: ${a.id} on ${b.id}", kotlin.math.abs(a.x - b.x) < 10 && kotlin.math.abs(a.y - b.y) < 10)
                    // What they share is drawn once: they leave the round together.
                    if (k >= 1) assertEquals("$label: the first column", 1, drawn.count { it.x == columns.first() })
                    // Each exit's transaction being sent keeps its own place.
                    val states = ExitGutter.states(paths)
                    for (path in paths) path.dropLast(1).firstOrNull { states.getValue(it.first) != ExitGutter.St.mined }?.let { (id, _) ->
                        assertTrue("$label: $id is being sent", drawn.any { it.id == id && !it.folded })
                    }
                    gutters++
                }
            // Review's rail at a 320, 360 and 412 dp phone's column: neighbours 2 dp apart at least, clear of the round's block
            // and the dial, the first transaction and your coin always drawn, the real count in its stops.
            for (width in listOf(272f, 312f, 364f)) {
                val stops = ExitRail.stops(n, 40f, width * .36f - 44, width / 2 - 21, { "+$it" }) { measure.width(it, ExitRail.moreSize, true) }
                val label = "rail: $n transactions, $width dp"
                for ((a, b) in stops.zipWithNext()) assertTrue("$label: ${a.x} → ${b.x}", b.x - a.x >= a.half + b.half + 2 - .01f)
                assertTrue(label, stops.first().x - stops.first().half >= 26f + 2)
                assertTrue(label, stops.last().coin && stops.last().x + stops.last().half <= width / 2 - 17)
                assertTrue(label, n == 1 || stops.first().more == null)
                assertEquals(label, n, stops.sumOf { it.more ?: 1 })
                rails++
            }
        }
        println("ExitTreeSweepTest: $gutters gutters and $rails rails checked")
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
        val explainer = check(ExitScene.sample(4), phone, w, "explainer", 268f)
        assertEquals("${explainer.labels.map { it.key }} ${explainer.stubs.size} stubs", 3, explainer.labels.size); assertNotNull("${explainer.stubs}", explainer.caption)
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

    @Test fun theRoundsCaptionTriesOneShrunkLineThenGivesWay() {
        // The root waiting right above the round on a small phone at twice the text: two lines don't fit, one does.
        val w = words("sv")
        val layout = check(path(3, 0), small, w, "root waiting")
        val round = layout.labels.firstOrNull { it.lines.any { line -> line.text.contains(w.tr("already on Bitcoin")) } }
        if (round != null && round.lines.size == 1) assertTrue("shrunk to 75 % at least", round.size >= .75f)
    }

    @Test fun aFoldedRangeCoversEveryBlockWithoutALabel() {
        // Five of twenty mined in nine levels, two of them stacked: whichever blocks have no label of their own, one range
        // covers them all, never just the stack's two.
        for (language in listOf("en", "sv")) for (frame in listOf(phone, small)) for (current in listOf(m.waiting, m.needsFee)) {
            val w = words(language); val scene = path(20, 5, current)
            val layout = check(scene, frame, w, "$language, twenty transactions, five mined, $current", levels = 9)
            assertEquals("${frame.name}: ${layout.scene.spans}", 0..1, layout.scene.spans?.first())
            for (height in scene.heights.filterNotNull()) assertTrue("${frame.name}, $language: block $height", layout.labels.any { it.blocks?.contains(height) == true })
        }
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
            val layout = check(path(4, 0, others = List(3) { ExitScene.Other(1, 24_500L - 3_000 * it) }), frame, w, "four transactions, three others at the root")
            for (a in layout.siblings) for (b in layout.siblings) if (a !== b) for (i in 0..40) {
                val (x, y) = linkAt(a.x, layout.ys[a.level] + a.dy + 6, layout.geo.trunk, layout.ys[a.level] - 4, i / 40f)
                assertTrue("${frame.name}: $a's branch crosses $b", hypot(x - b.x, y - (layout.ys[b.level] + b.dy)) >= 6 + .7f)
            }
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
