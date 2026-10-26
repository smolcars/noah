package com.noahwallet.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.TextView
import com.noahwallet.R
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoahWidgetProviderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun language(code: String) { context.getSharedPreferences("gt_store", 0).edit().putString(LegacyLocale.key, code).commit() }
    @Before fun reset() {
        context.getSharedPreferences("gt_store", 0).edit().clear().commit()
        context.getSharedPreferences("com.noahwallet.regtest", 0).edit().clear().commit()
    }

    private fun render(): View {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).bindAppWidgetId(1, ComponentName(context, NoahWidgetRegtestProvider::class.java))
        NoahWidgetProvider::class.java.getDeclaredMethod("updateWidget", Context::class.java, AppWidgetManager::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(NoahWidgetRegtestProvider(), context, manager, 1)
        return shadowOf(manager).getViewFor(1)
    }

    @Test fun placedWidgetShowsTheAppLanguage() {
        context.getSharedPreferences("com.noahwallet.regtest", 0).edit()
            .putLong("pendingBalance", 1234).putLong("closestExpiryBlocks", 1).putLong("expiryThreshold", 288).commit()
        for (locale in LegacyLocale.supported) {
            language(locale)
            val view = render()
            fun text(id: Int) = view.findViewById<TextView>(id).text.toString()
            assertEquals(L10n.text(context, "Noah Wallet"), text(R.id.wallet_title))
            assertEquals(L10n.text(context, "Onchain"), text(R.id.onchain_label))
            assertEquals(L10n.text(context, "Offchain"), text(R.id.offchain_label))
            assertEquals(L10n.text(context, "Pending: {amount}", "₿ 1,234"), text(R.id.pending_balance))
            assertEquals(L10n.plural(context, "VTXO expires: {n} blocks", 1), text(R.id.expiry_text))
        }
        language("es")
        val view = render()
        assertEquals("En la cadena", view.findViewById<TextView>(R.id.onchain_label).text.toString())
        language("en")
        assertEquals("VTXO expires: 1 block", render().findViewById<TextView>(R.id.expiry_text).text.toString())
    }

    /** The layout's preview and the picker's descriptions are resources the launcher resolves itself. */
    @Test fun pickerResourcesMatchTheCatalogsInEveryLocale() {
        val resources = mapOf(R.string.widget_title to "Noah Wallet", R.string.widget_onchain to "Onchain", R.string.widget_offchain to "Offchain",
            R.string.widget_mainnet_description to "View your Bitcoin balance", R.string.widget_signet_description to "View your Bitcoin balance on signet",
            R.string.widget_regtest_description to "View your Bitcoin balance on regtest")
        for (locale in LegacyLocale.supported) {
            language(locale)
            for ((id, source) in resources) assertEquals("$locale: $source", L10n.text(context, source), L10n.context(context).getString(id))
        }
        language("ja")
        assertEquals("オフチェーン", L10n.context(context).getString(R.string.widget_offchain))
    }
}
