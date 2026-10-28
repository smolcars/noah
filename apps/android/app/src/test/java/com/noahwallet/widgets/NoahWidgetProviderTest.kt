package com.noahwallet.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.noahwallet.R
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.localization.L10n
import com.noahwallet.services.storage.LegacyLocale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
// Layouts are chosen by measured text: real fonts throughout.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoahWidgetProviderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun language(code: String) { context.getSharedPreferences("gt_store", 0).edit().putString(LegacyLocale.key, code).commit() }
    private val now = 1_761_577_200_000L
    private val hour = 3_600_000L
    private val month = WidgetSnapshot(balance = 252_742, arriving = 100_000,
        daily = listOf(222600, 230600, 228100, 228100, 232942, 232942, 228742, 228742, 243742, 233742, 233742, 230542, 251542, 251542,
            245542, 240542, 240542, 250042, 251542, 251542, 243742, 243242, 243242, 255242, 261242, 261242, 257242, 247242, 250642, 252742, 252742),
        writtenAt = now, fiatCurrency = "USD", fiatPrice = 112_000.0)

    @Before fun reset() {
        context.getSharedPreferences("gt_store", 0).edit().clear().commit()
        context.getSharedPreferences("com.noahwallet.regtest", 0).edit().clear().commit()
    }

    @Test fun contentFollowsHomeInEveryLanguage() {
        for (code in LegacyLocale.supported) {
            language(code)
            val locale = Locale.forLanguageTag(LegacyLocale.resolved(context))
            val content = WidgetContent.of(context, month.copy(renewDue = now + 18 * hour + 600_000), "regtest", now)
            assertEquals(BitcoinAmount.format(252_742, BitcoinAmountUnit.bip177, locale), content.balance)
            assertEquals(FiatCurrency.format(283.07, "USD", locale), content.fiat)
            assertEquals("+" + BitcoinAmount.format(30_142, BitcoinAmountUnit.bip177, locale), content.change)
            assertEquals(" · " + L10n.plural(context, "{n} days", 30), content.days)
            assertEquals(L10n.text(context, "Regtest"), content.chip)
            assertEquals(WidgetContent.Status.Renew(L10n.text(context, "Renew"), L10n.plural(context, "Expires in {n} hours", 18)), content.status)
            assertEquals(listOf(L10n.text(context, "Receive"), L10n.text(context, "Scan"), L10n.text(context, "Send")),
                listOf(content.receive, content.scan, content.send))
        }
        language("sv")
        val sv = WidgetContent.of(context, month.copy(amountUnit = "sats"), "signet", now)
        assertEquals("252 742 sats", sv.balance); assertTrue(sv.satsSuffix)
        assertEquals("Signet", sv.chip)
        assertEquals(WidgetContent.Status.Arriving("+100 000 sats på väg"), sv.status)
        language("en")
        val en = WidgetContent.of(context, month, "mainnet", now)
        assertEquals("₿ 252,742", en.balance); assertNull(en.chip)
        assertEquals("+₿ 30,142 · 30 days", en.change + en.days)
        assertEquals("₿ 252,742, \$283.07, +₿ 30,142 · 30 days, +₿ 100,000 arriving", en.description)
    }

    @Test fun hiddenDrawsNoWaveAndDotsEverywhere() {
        language("en")
        val hidden = WidgetContent.of(context, month.copy(hidden = true), "regtest", now)
        assertNull(hidden.wave); assertNull(hidden.change); assertNull(hidden.days)
        assertEquals("₿ ••••••", hidden.balance)
        assertEquals("\$ ••••", hidden.fiat)
        // As Home words it.
        assertEquals(WidgetContent.Status.Arriving("•••• arriving"), hidden.status)
        assertFalse("TalkBack reads no amount", hidden.description.any { it.isDigit() })
        val sats = WidgetContent.of(context, month.copy(hidden = true, amountUnit = "sats"), "regtest", now)
        assertEquals("•••••• sats", sats.balance)
        assertEquals(WidgetContent.Status.Arriving("•••• arriving"), sats.status)
        // The sea is drawn as calm water: a flat line with a ripple at 72% of the band.
        val frame = WidgetViews.frame(WidgetLayout.small, hidden, 180f, 180f)!!
        val line = WidgetSea.line(hidden.wave, frame)
        assertEquals((frame.band * .72f).toInt().toFloat(), line.today, 1f)
    }

    @Test fun calmWaterAndNoLabelForANewWalletOrOldKeys() {
        language("en")
        val fresh = WidgetContent.of(context, WidgetSnapshot(balance = 0, daily = List(31) { 0L }, writtenAt = now), "mainnet", now)
        assertNull(fresh.wave); assertNull(fresh.change); assertEquals("₿ 0", fresh.balance); assertNull(fresh.status)
        val legacy = WidgetContent.of(context, WidgetSnapshot(balance = 1_234_567), "regtest", now)
        assertNull(legacy.wave); assertNull(legacy.fiat); assertEquals("₿ 1,234,567", legacy.balance); assertNull(legacy.status)
    }

    @Test fun renewCountsDownWithoutTheAppAndWinsOverArriving() {
        language("en")
        fun status(due: Long) = WidgetContent.of(context, month.copy(renewDue = due), "regtest", now).status
        assertEquals(WidgetContent.Status.Renew("Renew", "Expires in 2 hours"), status(now + 2 * hour + 59 * 60_000))
        assertEquals(WidgetContent.Status.Renew("Renew", "Expires in 1 hour"), status(now + hour))
        assertEquals(WidgetContent.Status.Renew("Renew", "Expires in under an hour"), status(now + 59 * 60_000))
        // After the due time the line goes; arriving shows again.
        assertEquals(WidgetContent.Status.Arriving("+₿ 100,000 arriving"), status(now))
    }

    @Test fun aLaterDayMovesTheWaveOn() {
        val tomorrow = WidgetContent.of(context, month, "regtest", now + 24 * hour)
        assertEquals(month.daily!!.drop(1) + 252_742L, tomorrow.wave)
    }

    @Test fun layoutsBySize() {
        assertEquals(WidgetLayout.narrow, WidgetLayout.of(130f, 80f))
        assertEquals(WidgetLayout.wide, WidgetLayout.of(350f, 80f))
        // Four columns of an Android 9 launcher's five: too narrow for the buttons or the medium layout.
        assertEquals(WidgetLayout.narrow, WidgetLayout.of(276f, 80f))
        assertEquals(WidgetLayout.small, WidgetLayout.of(276f, 200f))
        assertEquals(WidgetLayout.medium, WidgetLayout.of(330f, 180f))
        assertEquals(WidgetLayout.small, WidgetLayout.of(180f, 180f))
        assertEquals(WidgetLayout.medium, WidgetLayout.of(372f, 180f))
        assertEquals(WidgetLayout.medium, WidgetLayout.of(600f, 400f))
    }

    /** The boat sails on the line at today (hull on the stroke's upper edge), 16 dp (small) or 18 dp (medium) in from the right. */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun seaGeometry() {
        language("en")
        val content = WidgetContent.of(context, month, "regtest", now)
        val small = WidgetViews.frame(WidgetLayout.small, content, 180f, 180f)!!
        // Everyday with fiat and an arriving line: the band ends 4 dp above the balance.
        assertEquals(180f - 16 - 22 - 19 - 34 - 32 - 4, small.band, .01f)
        val drawn = WidgetSea.draw(content.wave, small, 2f)
        assertEquals(360, drawn.bitmap.width); assertEquals(296, drawn.bitmap.height)
        assertEquals(180f - 16 - 26, drawn.boatX, .01f)
        val line = WidgetSea.line(content.wave, small)
        assertEquals(small.y + line.today - .82f * 26 - 1, drawn.boatY, .01f)
        // The wave spans the band from under the boat's headroom to 2 dp above its bottom, so a boat at the top stays inside.
        assertTrue(line.today in (.76f * 26 + 2)..(small.band - 2))
        // The mask's top 18 of 256 rows are empty: the ship itself stays inside the band at the highest point.
        assertTrue(WidgetSea.boatTop(.76f * 26 + 2, 26f) + 18f / 256 * 26 >= 0f)
        val renew = WidgetViews.frame(WidgetLayout.small, content.copy(status = WidgetContent.Status.Renew("Renew", "x")), 180f, 180f)!!
        assertTrue("two-line Renew makes the band shallower", renew.band < small.band)
        // A fiat row grown by large text (31 dp, measured by the plan) lowers the balance, so the band ends 12 dp higher.
        val tallFiat = WidgetViews.frame(WidgetLayout.small, content, 180f, 180f, 22f, fiat = 31f)!!
        assertEquals(small.band - 12f, tallFiat.band, .01f)
        val medium = WidgetViews.frame(WidgetLayout.medium, content, 372f, 180f)!!
        assertEquals(193f, medium.w, .01f); assertEquals(372f - 193f, medium.x, .01f); assertTrue(medium.fade)
        assertEquals(372f - 18 - 26, WidgetSea.draw(content.wave, medium, 1f).boatX, .01f)
        // The sea is Bitcoin orange only, so it suits light, dark and wallpaper colours.
        val pixels = IntArray(drawn.bitmap.width * drawn.bitmap.height)
            .also { drawn.bitmap.getPixels(it, 0, drawn.bitmap.width, 0, 0, drawn.bitmap.width, drawn.bitmap.height) }
        assertTrue(pixels.any { Color.alpha(it) == 255 })
        assertTrue(pixels.filter { Color.alpha(it) == 255 }.all { Color.red(it) == 0xF7 && Color.green(it) == 0x93 && Color.blue(it) == 0x1A })
        assertNull(WidgetViews.frame(WidgetLayout.wide, content, 372f, 84f))
    }

    private val foldSlots = listOf(android.util.SizeF(360f, 600f), android.util.SizeF(660f, 280f), android.util.SizeF(740f, 560f),
        android.util.SizeF(620f, 680f))
    /** The launcher's limit on a Pixel Fold that booted closed: 6 × its 1080 × 2092 cover. */
    private val closedBootCap = 6L * 1080 * 2092
    private fun bytes(built: WidgetViews.Built) = built.bitmaps.distinctBy { System.identityHashCode(it) }.sumOf { it.allocationByteCount.toLong() }

    /**
     * A Fold reports up to four slots and their bitmaps travel in one update, which the launcher refuses above 6 × the
     * pixels of the display it booted on. Unfolded, only the inner display is listed, though it may have booted closed
     * (Sol review: an 18,052,100-byte No-wallet plan against a 13,556,160-byte cap).
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun foldSlotsStayInsideTheLaunchersBitmapBudget() {
        language("en")
        val unfolded = WidgetViews.budget(listOf(2208L * 1840))
        assertTrue("never more than a closed boot allows", unfolded <= closedBootCap)
        assertEquals("a phone's own display still decides", WidgetViews.budget(listOf(720L * 1280)), 6L * 720 * 1280 * 4 / 5)
        val fullSlots = foldSlots.sumOf { Math.round(it.width * 2.75f).toLong() * Math.round(it.height * 2.75f) * 4 }
        assertTrue("the old full-slot seas didn't fit", fullSlots > closedBootCap)
        for (content in listOf(null, WidgetContent.of(context, month, "regtest", now), WidgetContent.of(context, month.copy(hidden = true), "regtest", now))) {
            val built = WidgetViews.buildAll(context, "regtest", content, foldSlots, 2.75f, unfolded)
            assertTrue("${bytes(built)} bytes", bytes(built) <= unfolded)
            assertEquals(foldSlots.toSet(), built.views.keys)
        }
        // Where it fits, full resolution: one slot draws at 2.75 px per dp.
        val one = WidgetViews.buildAll(context, "regtest", null, foldSlots.take(1), 2.75f, unfolded)
        assertEquals(Math.round(360 * 2.75f), one.bitmaps.single().width)
    }

    /**
     * The Fold's two-column 4×4 reports five sizes, its own (380.57 × 468.95) the fifth: keeping four left none that
     * fitted, and Android drew the smallest, the 2×2 design. Every size stays, within the bitmap budget.
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun everyReportedSizeIsKept() {
        language("en")
        val slot = android.util.SizeF(380.57144f, 468.9524f)
        val reported = listOf(android.util.SizeF(368f, 477.7143f), android.util.SizeF(368f, 477.7143f), android.util.SizeF(319.2381f, 541.3333f),
            android.util.SizeF(319.2381f, 541.3333f), android.util.SizeF(595.8095f, 353.5238f), android.util.SizeF(595.8095f, 353.5238f),
            android.util.SizeF(380.57144f, 470.09525f), slot)
        fun options(sizes: List<android.util.SizeF>) =
            android.os.Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, ArrayList(sizes)) }
        val sizes = NoahWidgetProvider.sizes(options(reported))
        assertEquals(reported.distinct(), sizes)
        assertEquals(WidgetLayout.medium, WidgetLayout.of(slot.width, slot.height))
        for (content in listOf(null, WidgetContent.of(context, month, "regtest", now))) {
            val built = WidgetViews.buildAll(context, "regtest", content, sizes, 2.625f, WidgetViews.budget(listOf(2208L * 1840)))
            assertTrue(slot in built.views.keys)
            assertTrue("${bytes(built)} bytes", bytes(built) <= WidgetViews.budget(listOf(2208L * 1840)))
        }
        // A size map takes 16 at most.
        assertEquals(16, NoahWidgetProvider.sizes(options(List(20) { android.util.SizeF(200f + it, 200f) })).size)
    }

    /** A launcher that still refuses the update (a lower limit than we took) gets it again with a quarter of the bytes. */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun aRefusedUpdateIsRetriedWithSmallerBitmaps() {
        language("en")
        // As if the budget came from the inner display alone, with no bound: the cover's limit refuses it.
        val tooMuch = 6L * 2208 * 1840
        val budgets = mutableListOf<Long>(); var built: WidgetViews.Built? = null; var shown: WidgetViews.Built? = null
        NoahWidgetProvider.publish(tooMuch, { budget -> budgets += budget
            WidgetViews.buildAll(context, "regtest", null, foldSlots, 2.75f, budget).also { built = it }.views.values.first() }) {
            require(bytes(built!!) <= closedBootCap) { "RemoteViews for widget update exceeds maximum bitmap memory usage" }
            shown = built
        }
        assertEquals(listOf(tooMuch, tooMuch / 4), budgets)
        assertNotNull("No wallet replaces the deleted balance", shown)
        // A second refusal isn't retried again: the provider logs it.
        var attempts = 0
        assertThrows(IllegalArgumentException::class.java) {
            NoahWidgetProvider.publish(1_000, { WidgetViews.buildAll(context, "regtest", null, foldSlots.take(1), 1f, it).views.values.first() }) {
                attempts++; throw IllegalArgumentException("refused")
            }
        }
        assertEquals(2, attempts)
    }

    /** Large system text never clips the balance: at font scale 2.0 its digits started 5.1 px above the bitmap (Sol review). */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun theBalanceFitsItsBoxAtLargeFontScales() {
        val d = 2.625f
        // 30 sp at the system's font scale (linear here; Android 14+ scales large text a little less).
        fun render(scale: Float, box: Float, tall: Float = 34f) = WidgetType.balancePx(context, "₿\u00a0252,742", false, 30f * d * scale, box, tall, d)
        fun inkTop(bitmap: android.graphics.Bitmap): Int =
            (0 until bitmap.height).first { y -> (0 until bitmap.width).any { x -> android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0 } }
        val normal = render(1f, 400f)
        for ((scale, bitmap) in listOf(1f to normal, 1.3f to render(1.3f, 400f), 2f to render(2f, 400f), 2f to render(2f, 144f))) {
            assertEquals((34 * d).toInt(), bitmap.height)
            assertTrue("scale $scale: ink from row ${inkTop(bitmap)}", inkTop(bitmap) > 0)
            assertTrue("scale $scale: never below the usual size", bitmap.width >= normal.width || (scale == 2f && bitmap.width <= 144 * d))
        }
        // At the usual scale nothing changed: a taller box draws the same figures.
        assertEquals(render(1f, 400f, 60f).width, normal.width)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun slotsShareIdenticalBitmaps() {
        language("en")
        val content = WidgetContent.of(context, month, "regtest", now)
        // Two 2×2 heights: two seas, one balance.
        val built = WidgetViews.buildAll(context, "regtest", content, listOf(android.util.SizeF(180f, 180f), android.util.SizeF(180f, 220f)), 2f,
            Long.MAX_VALUE)
        assertEquals(3, built.bitmaps.distinctBy { System.identityHashCode(it) }.size)
        // No wallet draws only the water below the line, not the empty sky.
        val calm = WidgetViews.frame(WidgetLayout.empty, null, 180f, 180f)!!
        assertTrue(calm.h < 90f)
        assertEquals(calm.y + 6f - .82f * 44 - 1, WidgetSea.draw(null, calm, 1f).boatY, .01f)
    }

    private fun apply(content: WidgetContent?, width: Float, height: Float): View =
        WidgetViews.build(context, "regtest", content, width, height, 1f).apply(context, FrameLayout(context))
    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.shown(id: Int) = findViewById<View>(id)?.visibility == View.VISIBLE
    /** The balance is drawn (Space Grotesk, see WidgetType); its view carries the text. */
    private fun View.balance() = findViewById<android.widget.ImageView>(R.id.widget_balance).let {
        assertNotNull(it.drawable); it.contentDescription.toString()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun theBalanceShrinksToFitDownToHalf() {
        val d = context.resources.displayMetrics.density
        val box = 144f
        fun width(text: String, sats: Boolean = false, wide: Float = box) = WidgetType.balance(context, text, sats, 30f, wide, 34f, d).width
        assertEquals((34 * d).toInt(), WidgetType.balance(context, "₿\u00a0252,742", false, 30f, box, 34f, d).height)
        assertTrue(width("₿\u00a0252,742") <= box * d)
        assertTrue("shrinks to fit", width("₿\u00a012,480,315") <= box * d + 1)
        assertTrue("never below half size", width("₿\u00a021,000,000,000,000,000,000") > box * d)
        assertTrue("sats at about half size", width("252,742 sats", true, 400f) < width("252,742 sats", false, 400f))
    }

    @Test fun everyLayoutRendersItsLines() {
        language("en")
        val content = WidgetContent.of(context, month.copy(renewDue = now + 18 * hour), "regtest", now)
        val small = apply(content, 180f, 180f)
        assertEquals("₿ 252,742", small.balance()); assertEquals("\$283.07", small.text(R.id.widget_fiat))
        assertEquals("+₿ 30,142", small.text(R.id.widget_change)); assertEquals(" · 30 days", small.text(R.id.widget_change_days))
        assertEquals("Renew", small.text(R.id.widget_status_title)); assertEquals("Expires in 18 hours", small.text(R.id.widget_status_detail))
        assertTrue(small.shown(R.id.widget_status_icon)); assertFalse(small.shown(R.id.widget_status_dot)); assertTrue(small.shown(R.id.widget_boat))
        assertEquals("Regtest", small.text(R.id.widget_chip))
        val medium = apply(content, 372f, 180f)
        assertEquals(" · Expires in 18 hours", medium.text(R.id.widget_status_detail))
        assertEquals(listOf("Receive", "Scan", "Send"),
            listOf(R.id.widget_receive_label, R.id.widget_scan_label, R.id.widget_send_label).map { medium.text(it) })
        assertTrue(medium.findViewById<View>(R.id.widget_receive).hasOnClickListeners())
        // One row: the status, or else the fiat.
        val wide = apply(content, 372f, 84f)
        assertEquals("Expires in 18 hours", wide.text(R.id.widget_status_title)); assertFalse(wide.shown(R.id.widget_fiat))
        assertEquals("Receive", wide.findViewById<View>(R.id.widget_receive).contentDescription)
        val narrow = apply(WidgetContent.of(context, month.copy(arriving = 0), "regtest", now), 140f, 84f)
        assertEquals("\$283.07", narrow.text(R.id.widget_fiat)); assertFalse(narrow.shown(R.id.widget_status))
        // No wallet: just the longship on calm water, never ₿ 0.
        val empty = apply(null, 180f, 180f)
        assertTrue(empty.shown(R.id.widget_boat)); assertNull(empty.findViewById<View>(R.id.widget_balance))
    }

    /**
     * Medium: the label takes the width it needs and the balance column the rest, never less than the balance's narrowest.
     * Then " · 30 days" goes, the label's text shrinks to 80%, and last the label takes a line of its own.
     */
    @Test fun theMediumLabelTakesTheWidthItNeeds() {
        // 320 dp leaves a 284 dp row, 14 dp between the columns: a 167 dp label leaves 103 for a balance 72 at its narrowest.
        val usual = WidgetViews.LabelParts(20f, 39f, 56f, 52f, 19f)
        assertEquals(WidgetViews.Label(167f, true), WidgetViews.label(320f, usual, 72f, 28f))
        assertEquals(WidgetViews.Box(103f, 38f, 34f, .45f), WidgetViews.balanceBox(WidgetLayout.medium, 320f, WidgetViews.Label(167f, true)))
        // Eight figures: " · 30 days" goes first, and comes back where there's room.
        val long = WidgetViews.LabelParts(20f, 39f, 95f, 52f, 19f)
        assertEquals(WidgetViews.Label(154f, false), WidgetViews.label(320f, long, 98f, 28f))
        assertEquals(WidgetViews.Label(206f, true), WidgetViews.label(553f, long, 98f, 28f))
        // Then the text shrinks, down to 80%...
        assertEquals(WidgetViews.Label(130f, false, (130f - 22f) / 134f), WidgetViews.label(320f, long, 140f, 28f))
        // ...and below that the label takes its own line, the balance the whole row, where the height leaves room for it
        // and the 4 dp under it...
        assertEquals(WidgetViews.Label(206f, true, line = true, lift = 23f), WidgetViews.label(320f, long, 150f, 23f))
        assertEquals(WidgetViews.Box(284f, 38f, 34f, .45f), WidgetViews.balanceBox(WidgetLayout.medium, 320f, WidgetViews.Label(206f, true, line = true)))
        // ...or else it's left out, the balance again with the whole row.
        val out = WidgetViews.Label(0f, false, shown = false)
        assertEquals(out, WidgetViews.label(320f, long, 150f, 22f))
        assertEquals(WidgetViews.Box(284f, 38f, 34f, .45f), WidgetViews.balanceBox(WidgetLayout.medium, 320f, out))
        // At 200% beside a chip: its own line at 80% even if the amount still doesn't fit; without a chip, at full size;
        // above a status line in 180 dp (28 to spare), neither.
        val chip = WidgetViews.LabelParts(20f, 78f, 260f, 104f, 31f)
        assertEquals(WidgetViews.Label(284f, false, .8f, true, lift = 35f), WidgetViews.label(320f, chip, 197f, 50f))
        assertEquals(WidgetViews.Label(190f, false, line = true, lift = 32f),
            WidgetViews.label(320f, WidgetViews.LabelParts(0f, 0f, 190f, 104f, 28f), 197f, 50f))
        assertEquals(out, WidgetViews.label(320f, chip, 197f, 28f))
        // The other layouts keep their boxes, and the balance's half-size floor.
        assertEquals(WidgetViews.Box(144f, 34f, 30f, .5f), WidgetViews.balanceBox(WidgetLayout.small, 180f))
    }

    private fun laidOut(content: WidgetContent, width: Float, height: Float = 180f, variant: String = "regtest"): View {
        val d = context.resources.displayMetrics.density
        return WidgetViews.build(context, variant, content, width, height, d).apply(context, FrameLayout(context)).apply {
            measure(View.MeasureSpec.makeMeasureSpec((width * d).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((height * d).toInt(), View.MeasureSpec.EXACTLY))
            layout(0, 0, (width * d).toInt(), (height * d).toInt())
        }
    }
    private fun View.whole(id: Int): Boolean =
        findViewById<TextView>(id).let { it.layout.getEllipsisCount(0) == 0 && it.width >= kotlin.math.ceil(it.paint.measureText(it.text.toString())) }
    private fun View.left(id: Int) = generateSequence(findViewById<View>(id)) { it.parent as? View }.sumOf { it.left }
    private fun View.right(id: Int) = left(id) + findViewById<View>(id).width
    private fun View.top(id: Int) = generateSequence(findViewById<View>(id)) { it.parent as? View }.sumOf { it.top }
    private fun View.bottom(id: Int) = top(id) + findViewById<View>(id).height

    /**
     * "+₿ 30,142 · 30 days" was cut to "+₿ 30,142 · …" by a fixed 60/40 split: every medium size from 320 dp now shows it
     * whole, in English and Swedish, in ₿ and sats; the longest amounts drop " · 30 days" before any digit.
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun theMediumLabelIsNeverCutShort() {
        val d = context.resources.displayMetrics.density
        // The edges, the phone's 4×2 (360.38 and 676.57 dp) and the Fold's two-column 4×4 (368 to 595.81 dp), as the
        // launchers report them.
        val widths = listOf(320f, 360.38f, 368f, 380.57f, 553.14f, 595.81f, 676.57f, 720f)
        for ((code, unit) in listOf("en", "sv").flatMap { code -> listOf("bip177", "sats").map { code to it } }) {
            language(code)
            val content = WidgetContent.of(context, month.copy(amountUnit = unit), "regtest", now)
            for (width in widths) {
                val view = laidOut(content, width)
                val at = "$code $unit $width dp"
                assertEquals(at, content.days, view.text(R.id.widget_change_days))
                for (id in listOf(R.id.widget_chip, R.id.widget_change, R.id.widget_change_days)) assertTrue("$at: ${view.text(id)}", view.whole(id))
                assertTrue("$at: inside the padding", view.right(R.id.widget_change_days) <= ((width - 18f) * d).toInt() + 1)
                assertTrue("$at: the balance column ends before the label", view.right(R.id.widget_balance) <= view.left(R.id.widget_chip))
                // The balance's box is the column the label leaves, and the figures are drawn for it: within the text's
                // rounding at 1×, where drawn at half size and scaled down they'd be 11% over.
                val box = WidgetViews.balanceBox(WidgetLayout.medium, width, WidgetViews.plan(context, content, width, 180f).label).width
                val balance = view.findViewById<android.widget.ImageView>(R.id.widget_balance)
                assertEquals(at, box * d, balance.width.toFloat(), d)
                assertTrue(at, balance.drawable.intrinsicWidth <= balance.width * 1.05f)
                // The status (here "+₿ 100,000 arriving") runs under both columns, clear of the boat.
                assertTrue("$at: ${view.text(R.id.widget_status_title)}", view.whole(R.id.widget_status_title))
            }
        }
        language("sv")
        assertEquals("+30\u00a0142 sats", laidOut(WidgetContent.of(context, month.copy(amountUnit = "sats"), "regtest", now), 320f).text(R.id.widget_change))
        // The longest: eight figures in sats, in Swedish, at 320 dp.
        val large = WidgetContent.of(context, month.copy(balance = 12_480_315, daily = List(30) { 1_000_000L } + 12_480_315, amountUnit = "sats"),
            "regtest", now)
        val narrow = laidOut(large, 320f)
        assertEquals("+11\u00a0480\u00a0315 sats", narrow.text(R.id.widget_change)); assertTrue(narrow.whole(R.id.widget_change))
        assertEquals("", narrow.text(R.id.widget_change_days))
        assertEquals(" · 30 dagar", laidOut(large, 553.14f).text(R.id.widget_change_days))
    }

    /**
     * Large system text never hides the balance (Sol review: at 200% beside a Regtest chip, "+12,257,715 sats" measured
     * 281 dp against a 270 dp row and the balance's box went to −11 dp). The balance keeps its narrowest or more; the label
     * drops " · 30 days", shrinks, then takes a line of its own, the amount whole.
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun largeTextNeverHidesTheMediumBalance() = largeText()

    /** As on a phone: 420 dpi, where large text scales the balance less than the label (Android 14+). */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "420dpi")
    @Test
    fun largeTextNeverHidesTheMediumBalanceOnAPhone() = largeText()

    /** All of [id]'s text shows: no ellipsis, no line cut by the view, and the view inside the widget's [clip] (px). */
    private fun View.readable(id: Int, clip: Int): Boolean = findViewById<TextView>(id).let { text ->
        val laid = text.layout ?: return false
        laid.getEllipsisCount(laid.lineCount - 1) == 0 && laid.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom + 1 &&
            top(id) >= 0 && bottom(id) <= clip && right(id) <= width
    }

    private fun largeText() {
        language("en")
        val large = month.copy(balance = 12_480_315, daily = List(30) { 222_600L } + 12_480_315, amountUnit = "sats")
        // Sol's cases (fiat and an Arriving or Renew line), one without a status line, a mainnet one and the usual ones.
        val cases = listOf("regtest" to large, "regtest" to large.copy(arriving = 0, renewDue = now + 18 * hour), "regtest" to large.copy(arriving = 0),
            "mainnet" to large, "regtest" to month, "mainnet" to month.copy(amountUnit = "sats", arriving = 0, renewDue = now + 18 * hour))
        // The widths tested before, the landscape phone's 4×2 and the 2×2s (phone, Fold), at short and usual heights.
        val sizes = listOf(320f, 380.57f, 676.57f).flatMap { w -> listOf(120f, 137f, 160f, 180f).map { w to it } } +
            listOf(172.19f, 151.62f).flatMap { w -> listOf(120f, 137f, 160f, 233.9f).map { w to it } }
        val placements = mutableSetOf<String>()
        val layouts = mutableSetOf<WidgetLayout>()
        val actions = mutableSetOf<String>()
        try {
            for (scale in listOf(1f, 1.3f, 2f)) {
                RuntimeEnvironment.setFontScale(scale)
                for ((variant, snapshot, size) in cases.flatMap { (variant, snapshot) -> sizes.map { Triple(variant, snapshot, it) } }) {
                    val slot = layOut(scale, variant, snapshot, size)
                    layouts += slot.plan.layout
                    checkLargeText(slot, actions)?.let(placements::add)
                }
            }
            assertEquals(setOf("beside", "own line", "left out"), placements)
            assertEquals(setOf("labels", "icons"), actions)
            assertEquals(setOf(WidgetLayout.medium, WidgetLayout.wide, WidgetLayout.small, WidgetLayout.narrow), layouts)
            RuntimeEnvironment.setFontScale(1f)
            // The landscape phone's 4×2 (676.57 × 137.14 dp) with fiat and a status line: the 4×1; without the status line, it fits.
            assertEquals(WidgetLayout.wide, WidgetViews.plan(context, WidgetContent.of(context, month, "regtest", now), 676.57f, 137.14f).layout)
            val quiet = WidgetContent.of(context, month.copy(arriving = 0), "regtest", now)
            assertEquals(WidgetLayout.medium, WidgetViews.plan(context, quiet, 676.57f, 137.14f).layout)
            // Sol's case: at 200% in 180 dp above an Arriving line, the label is left out; without a status line it has its
            // own; at 100% it stays beside the balance, without " · 30 days".
            RuntimeEnvironment.setFontScale(2f)
            val sol = WidgetContent.of(context, large, "regtest", now)
            assertEquals("+12,257,715 sats", sol.change)
            assertFalse(WidgetViews.plan(context, sol, 320f, 180f).label!!.shown)
            assertTrue(WidgetViews.plan(context, WidgetContent.of(context, large.copy(arriving = 0), "regtest", now), 320f, 180f).label!!.line)
            RuntimeEnvironment.setFontScale(1f)
            val usual = WidgetViews.plan(context, sol, 320f, 180f).label!!
            assertTrue(usual.shown); assertFalse(usual.line); assertFalse(usual.days); assertEquals(1f, usual.scale)
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    /** One widget under test, laid out: what it shows, how it was planned, its width (dp) and where its launcher clips it (px). */
    private class LaidOut(val view: View, val content: WidgetContent, val plan: WidgetViews.Plan, val width: Float, val clip: Int, val at: String)

    private fun layOut(scale: Float, variant: String, snapshot: WidgetSnapshot, size: Pair<Float, Float>): LaidOut {
        val (width, height) = size
        val d = context.resources.displayMetrics.density
        val content = WidgetContent.of(context, snapshot, variant, now)
        val plan = WidgetViews.plan(context, content, width, height)
        val view = laidOut(content, width, height, variant)
        val at = "$scale× $variant ${content.change} ${content.status?.javaClass?.simpleName} $width × $height dp, $plan"
        // Two rows end at their bottom padding, where the launcher clips them; one row at the widget's edge.
        val twoRows = plan.layout == WidgetLayout.medium || plan.layout == WidgetLayout.small
        val clip = (((if (twoRows) height - 16f else height)) * d).toInt() + 1
        return LaidOut(view, content, plan, width, clip, at)
    }

    /**
     * The balance and the status line whole inside the widget, then its layout's label, Actions (medium's into [actions])
     * or label row; medium's label placement.
     */
    private fun checkLargeText(slot: LaidOut, actions: MutableSet<String>): String? {
        val view = slot.view; val at = slot.at; val clip = slot.clip
        assertTrue("$at: the balance", view.bottom(R.id.widget_balance) <= clip)
        // The status line whole, at every size and font scale.
        if (slot.content.status != null) {
            assertTrue(at, view.shown(R.id.widget_status))
            for (id in listOf(R.id.widget_status_title, R.id.widget_status_detail, R.id.widget_status_detail_line).filter { view.shown(it) })
                assertTrue("$at: \"${view.text(id)}\" at ${view.top(id)}..${view.bottom(id)} px, clipped at $clip", view.readable(id, clip))
        }
        return when (slot.plan.layout) {
            WidgetLayout.medium -> checkMedium(slot, actions)
            WidgetLayout.wide -> { checkWide(slot); null }
            WidgetLayout.small -> { checkSmall(slot); null }
            else -> null
        }
    }

    private fun checkMedium(slot: LaidOut, actions: MutableSet<String>): String {
        val view = slot.view; val content = slot.content; val at = slot.at; val clip = slot.clip; val width = slot.width
        val d = context.resources.displayMetrics.density
        val label = slot.plan.label!!
        val placement = when { !label.shown -> "left out"; label.line -> "own line"; else -> "beside" }
        // The balance at its narrowest or wider...
        val balance = view.findViewById<android.widget.ImageView>(R.id.widget_balance)
        val narrowest = WidgetType.minWidth(context, content.balance, content.satsSuffix, 34f, 38f, d, WidgetViews.MEDIUM_FLOOR)
        assertTrue("$at: a ${balance.width} px balance", balance.width >= narrowest - 1)
        assertTrue("$at: drawn at its floor or larger", balance.drawable.intrinsicWidth >= narrowest - 1)
        assertTrue("$at: drawn for its box", balance.drawable.intrinsicWidth <= balance.width * 1.05f)
        // ...the label beside it, on its own line or left out, the amount whole...
        assertEquals(at, label.line, view.shown(R.id.widget_label_line))
        assertEquals(at, label.shown && !label.line, view.shown(R.id.widget_label))
        // The boat sails below the label's own line.
        if (label.line) assertTrue("$at: the boat from ${view.top(R.id.widget_boat)} px", view.top(R.id.widget_boat) >= view.bottom(R.id.widget_label_line))
        if (label.shown) {
            val change = if (label.line) R.id.widget_change_line else R.id.widget_change
            assertEquals(at, content.change, view.text(change)); assertTrue(at, view.whole(change))
            assertTrue("$at: inside the padding", view.right(change) <= ((width - 18f) * d).toInt() + 1)
        }
        // ...and under the fiat and the status line, every Action whole inside the widget.
        checkActions(slot, actions)
        return placement
    }

    /** Medium's Actions, each whole inside the widget: its label whole inside the button, or its icon alone, its label the description. */
    private fun checkActions(slot: LaidOut, actions: MutableSet<String>) {
        val view = slot.view; val content = slot.content; val plan = slot.plan; val at = slot.at; val clip = slot.clip
        val d = context.resources.displayMetrics.density
        val above = if (content.status != null) view.bottom(R.id.widget_status) else view.bottom(R.id.widget_fiat)
        actions += if (plan.actions != null) "labels" else "icons"
        for ((id, label, text) in listOf(Triple(R.id.widget_receive, R.id.widget_receive_label, content.receive),
            Triple(R.id.widget_scan, R.id.widget_scan_label, content.scan), Triple(R.id.widget_send, R.id.widget_send_label, content.send))) {
            assertEquals(at, (40 * d).toInt(), view.findViewById<View>(id).height)
            assertTrue("$at: an Action at ${view.top(id)}..${view.bottom(id)} px, the line above to $above, clipped at $clip",
                view.top(id) >= above && view.bottom(id) <= clip)
            assertEquals(at, text, view.findViewById<View>(id).contentDescription)
            assertEquals(at, plan.actions != null, view.shown(label))
            if (plan.actions != null) {
                assertTrue("$at: \"${view.text(label)}\"", view.whole(label))
                assertTrue(at, view.left(label) >= view.left(id) && view.right(label) <= view.right(id) &&
                    view.top(label) >= view.top(id) && view.bottom(label) <= view.bottom(id))
            }
        }
    }

    /** The balance at its floor in the column, and every icon button whole inside the widget. */
    private fun checkWide(slot: LaidOut) {
        val view = slot.view; val content = slot.content; val at = slot.at; val clip = slot.clip
        val d = context.resources.displayMetrics.density
        val balance = view.findViewById<android.widget.ImageView>(R.id.widget_balance)
        assertTrue(at, balance.width >= WidgetType.minWidth(context, content.balance, content.satsSuffix, 24f, 28f, d, .5f) - 1)
        for (id in listOf(R.id.widget_receive, R.id.widget_scan, R.id.widget_send))
            assertTrue("$at: an Action at ${view.top(id)}..${view.bottom(id)} px of $clip",
                view.top(id) >= 0 && view.bottom(id) <= clip && view.right(id) <= view.width)
    }

    /** The label row grows with its text: nothing in it is cut. */
    private fun checkSmall(slot: LaidOut) {
        val view = slot.view
        for (id in listOf(R.id.widget_change, R.id.widget_change_days).filter { view.shown(it) })
            view.findViewById<TextView>(id).let { assertTrue(slot.at, it.layout.height <= it.height + 1) }
    }

    /**
     * Every language's status shows whole in one row and the 2×1 (Sol review: Japanese in a 350 × 60 dp 4×1 at 100% needed
     * 35 dp for two lines and got 30, and Spanish "Vence en menos de una hora" lost its end to a three-line cap in a 2×2
     * slot at 200%). Heights are the real text's, so whatever layout a slot takes, all of its status shows.
     */
    @Test fun everyLanguagesStatusShowsWhole() = statusLanguages()

    @Config(qualifiers = "420dpi")
    @Test
    fun everyLanguagesStatusShowsWholeOnAPhone() = statusLanguages()

    private fun statusLanguages() {
        val cases = listOf(month.copy(amountUnit = "sats"), month.copy(arriving = 0, renewDue = now + 40 * 60_000L),
            month.copy(arriving = 0, renewDue = now + 18 * hour))
        // 4×1s and 2×1s at a launcher's one-row heights, and 2×2 slots that may fall back to the 2×1.
        val sizes = listOf(350f to 60f, 350f to 84f, 300f to 100f, 250f to 90f, 172.19f to 110f, 151.62f to 233.9f, 151.62f to 160f,
            172.19f to 224f)
        val shown = mutableSetOf<String>()
        val layouts = mutableSetOf<WidgetLayout>()
        try {
            for ((code, scale) in listOf("ja", "es", "ko", "en").flatMap { code -> listOf(1f, 2f).map { code to it } }) {
                language(code)
                RuntimeEnvironment.setFontScale(scale)
                for ((snapshot, size) in cases.flatMap { snapshot -> sizes.map { snapshot to it } }) {
                    layouts += checkStatusShows("$code $scale×", snapshot, size, shown)
                }
            }
            // Sol's Spanish case: the 2×2 where it fits, or the 2×1 taking the lines its height holds at a readable size,
            // not three.
            language("es")
            RuntimeEnvironment.setFontScale(2f)
            val es = WidgetViews.plan(context, WidgetContent.of(context, cases[1], "regtest", now), 151.62f, 233.9f)
            assertTrue(es.toString(), es.layout == WidgetLayout.small ||
                (es.layout == WidgetLayout.narrow && es.status!!.lines.single() > 3 && es.status.sp >= WidgetViews.STATUS_FLOOR))
        } finally { RuntimeEnvironment.setFontScale(1f) }
        assertTrue(WidgetLayout.wide in layouts && WidgetLayout.narrow in layouts)
        // Sol's strings among those shown whole.
        assertTrue(shown.toString(), shown.any { "受け取り中" in it })
        assertTrue(shown.toString(), "Vence en menos de una hora" in shown)
    }

    /** One slot's status, on the lines its height holds and all of it shown ([shown] collects its text); returns its layout. */
    private fun checkStatusShows(case: String, snapshot: WidgetSnapshot, size: Pair<Float, Float>, shown: MutableSet<String>): WidgetLayout {
        val (width, height) = size
        val d = context.resources.displayMetrics.density
        val content = WidgetContent.of(context, snapshot, "regtest", now)
        val plan = WidgetViews.plan(context, content, width, height)
        val view = laidOut(content, width, height)
        val at = "$case $width × $height dp, $plan"
        val twoRows = plan.layout == WidgetLayout.medium || plan.layout == WidgetLayout.small
        val clip = ((if (twoRows) height - 16f else height) * d).toInt() + 1
        assertTrue(at, view.shown(R.id.widget_status))
        // Laid out on the lines its height holds, never cut down to them as a last resort.
        assertFalse("$at: the last resort", plan.status!!.forced)
        for (id in listOf(R.id.widget_status_title, R.id.widget_status_detail, R.id.widget_status_detail_line).filter { view.shown(it) }) {
            assertTrue("$at: \"${view.text(id)}\" at ${view.top(id)}..${view.bottom(id)} px, clipped at $clip", view.readable(id, clip))
            shown += view.text(id).trim(' ', '·')
        }
        assertTrue("$at: the balance", view.bottom(R.id.widget_balance) <= clip)
        // The 2×2's sea ends above the balance as laid out, its 4 dp gap give or take the views' whole pixels: its line and
        // boat never cross the digits (a 2×2 at 200% with a Renew line drew them 26 dp into the balance).
        if (plan.layout == WidgetLayout.small) {
            val sea = WidgetViews.frame(plan.layout, content, width, height, plan.status.tall, plan.labelRow, plan.label?.lift ?: 0f,
                plan.fiat?.tall ?: 0f)!!
            assertTrue("$at: the sea ends at ${(sea.y + sea.band) * d} px, the balance starts at ${view.top(R.id.widget_balance)} px",
                (sea.y + sea.band + 3f) * d <= view.top(R.id.widget_balance))
        }
        return plan.layout
    }

    /** The placed widget renders through the provider, from the old keys before the app runs, then the full snapshot. */
    @Test fun placedWidgetRendersOldKeysThenTheSnapshot() {
        language("en")
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).bindAppWidgetId(1, ComponentName(context, NoahWidgetRegtestProvider::class.java))
        fun render(): View {
            NoahWidgetProvider::class.java.getDeclaredMethod("updateWidget", Context::class.java, AppWidgetManager::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(NoahWidgetRegtestProvider(), context, manager, 1)
            return shadowOf(manager).getViewFor(1)
        }
        assertNull("No snapshot: No wallet", render().findViewById<View>(R.id.widget_balance))
        context.getSharedPreferences("com.noahwallet.regtest", 0).edit().putLong("totalBalance", 1_234_567).putLong("onchainBalance", 1)
            .putLong("offchainBalance", 2).putLong("pendingBalance", 12_345).putLong("closestExpiryBlocks", 24).putLong("expiryThreshold", 24).commit()
        val old = render()
        assertEquals("₿ 1,234,567", old.balance())
        assertFalse("old keys never show arriving", old.shown(R.id.widget_status))
        assertEquals("", old.text(R.id.widget_change))
        context.getSharedPreferences("com.noahwallet.regtest", 0).edit().clear()
            .putString(WidgetSnapshot.KEY, month.copy(writtenAt = System.currentTimeMillis()).encode()).commit()
        assertEquals("+₿ 30,142", render().text(R.id.widget_change))
    }

    /** The picker's descriptions are resources the launcher resolves itself. */
    @Test fun pickerDescriptionsMatchTheCatalogsInEveryLocale() {
        val resources = mapOf(R.string.widget_mainnet_description to "View your Bitcoin balance",
            R.string.widget_signet_description to "View your Bitcoin balance on signet",
            R.string.widget_regtest_description to "View your Bitcoin balance on regtest")
        for (locale in LegacyLocale.supported) {
            language(locale)
            for ((id, source) in resources) assertEquals("$locale: $source", L10n.text(context, source), L10n.context(context).getString(id))
        }
    }
}
