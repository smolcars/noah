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

    /** Medium: the label takes the width it needs and the balance column the rest, down to 45% of the balance's size. */
    @Test fun theMediumLabelTakesTheWidthItNeeds() {
        // 320 dp leaves a 270 dp row: the label's 162 dp leaves 108 for a balance 160 wide at full size.
        assertEquals(WidgetViews.Label(162f, true), WidgetViews.label(320f, 162f, 103f, 160f))
        assertEquals(WidgetViews.Box(108f, 38f, 34f, .45f), WidgetViews.balanceBox(WidgetLayout.medium, 320f, 162f))
        // The floor: 76.5 dp of a balance 170 wide.
        assertEquals(WidgetViews.Label(190f, true), WidgetViews.label(320f, 190f, 130f, 170f))
        assertEquals(WidgetViews.Label(130f, false), WidgetViews.label(320f, 195f, 130f, 170f))
        // Where the balance would fall below it, " · 30 days" goes and the amount stays.
        assertEquals(WidgetViews.Label(154f, false), WidgetViews.label(320f, 206f, 154f, 217f))
        assertEquals(WidgetViews.Label(206f, true), WidgetViews.label(553f, 206f, 154f, 217f))
        // The other layouts keep their boxes, and the balance's half-size floor.
        assertEquals(WidgetViews.Box(144f, 34f, 30f, .5f), WidgetViews.balanceBox(WidgetLayout.small, 180f))
    }

    /**
     * "+₿ 30,142 · 30 days" was cut to "+₿ 30,142 · …" by a fixed 60/40 split: every medium size from 320 dp now shows it
     * whole, in English and Swedish, in ₿ and sats; the longest amounts drop " · 30 days" before any digit.
     */
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun theMediumLabelIsNeverCutShort() {
        val d = context.resources.displayMetrics.density
        fun laidOut(content: WidgetContent, width: Float, height: Float = 180f): View =
            WidgetViews.build(context, "regtest", content, width, height, d).apply(context, FrameLayout(context)).apply {
                measure(View.MeasureSpec.makeMeasureSpec((width * d).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * d).toInt(), View.MeasureSpec.EXACTLY))
                layout(0, 0, (width * d).toInt(), (height * d).toInt())
            }
        fun View.whole(id: Int): Boolean =
            findViewById<TextView>(id).let { it.layout.getEllipsisCount(0) == 0 && it.width >= kotlin.math.ceil(it.paint.measureText(it.text.toString())) }
        fun View.left(id: Int) = generateSequence(findViewById<View>(id)) { it.parent as? View }.sumOf { it.left }
        fun View.right(id: Int) = left(id) + findViewById<View>(id).width
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
                val box = WidgetViews.balanceBox(WidgetLayout.medium, width, WidgetViews.label(context, content, width).width).width
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
