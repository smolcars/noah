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
import kotlin.math.min

/**
 * exit-v2's one rule, swept (exit-v2-notes.md, The picture): in every layout no two drawn elements overlap and none leaves
 * the canvas, required ones always show and keep their spacing, and a long path folds rather than squeezes. Paths of 1–20,
 * 50 and 100 transactions, the explainer, every mined count, your other exits at every pair of levels and every state, on a
 * phone at the default text size and a small phone at twice it, in English and in Swedish with sats, measured with the
 * picture's own text measurement.
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

    /** Lays [scene] out as the picture does (folded to fit) and checks the rule. */
    private fun check(scene: ExitScene, frame: Frame, words: TreeStrings, what: String, height: Float = frame.height): TreeLayout {
        val label = "${frame.name}, $what"
        val cap = TreeGeometry(frame.width, height, 1, frame.scale, false).capacity(if (scene.locked) 1f else 0f)
        val drawn = scene.fold(if (scene.explain) min(cap, 8) else cap)
        val layout = TreeLayout.of(drawn, frame.width, height, frame.scale, false, words, measure, balance = 18_400, received = 30_738)
        val shapes = layout.shapes
        for (shape in shapes) assertTrue("$label: ${shape.group} leaves the canvas: $shape", shape.inside(frame.width, height))
        for (i in shapes.indices) for (j in i + 1 until shapes.size) {
            val (a, b) = shapes[i] to shapes[j]
            if (a.group != b.group) assertFalse("$label: ${a.group} overlaps ${b.group}: $a / $b", a.overlaps(b))
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
        // At packed levels on a long path, they slide or step out, never onto each other.
        val deep = check(path(20, 12, others = listOf(ExitScene.Other(3, 24_500), ExitScene.Other(4, 21_000))), small, w, "packed others")
        val coins = deep.shapes.filter { it.kind == TreeShape.Kind.sibling }
        for (a in coins) for (b in coins) if (a !== b) assertFalse(a.overlaps(b, TreeGeometry.clearance))
    }
}
