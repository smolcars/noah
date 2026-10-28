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
        assertEquals(WidgetLayout.small, WidgetLayout.of(180f, 180f))
        assertEquals(WidgetLayout.medium, WidgetLayout.of(372f, 180f))
        assertEquals(WidgetLayout.medium, WidgetLayout.of(600f, 400f))
    }

    /** The boat rides at today, 16 dp (small) or 18 dp (medium) in from the right, its keel ~12% below the line. */
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
        assertEquals(small.y + line.today - .7f * 26, drawn.boatY, .01f)
        // The wave spans the band from under the boat's headroom to 2 dp above its bottom.
        assertTrue(line.today in (Math.ceil(.63 * 26).toFloat() + 1)..(small.band - 2))
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
