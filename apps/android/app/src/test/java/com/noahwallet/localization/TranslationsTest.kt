package com.noahwallet.localization

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.services.storage.LegacyLocale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("gt_store", 0).edit().clear().commit() }
    private fun language(code: String) { context.getSharedPreferences("gt_store", 0).edit().putString(LegacyLocale.key, code).commit() }

    @Test fun welcomeLanguageNeedsNoWalletState() {
        language("ja")
        assertEquals("ja", LegacyLocale.read(context))
        assertEquals("ja", LegacyLocale.resolved(context))
        assertNotEquals("Create wallet", L10n.text(context, "Create wallet"))
        language("sv")
        assertEquals("sv", L10n.context(context).resources.configuration.locales[0].language)
    }

    @Test fun mixedNetworkURIIsRejectedInEveryLanguageAndErrorsRerender() {
        val invoice = SharedFixtures.load("send-utils").first { it.name == "destination 5" }.input.jsonObject.getValue("destination").jsonPrimitive.content
        val uri = "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?lightning=$invoice"
        val expected = com.noahwallet.send.SendDestinationError.InvalidMethods(listOf(com.noahwallet.send.SendDestinationError.NetworkMismatch("mainnet", "testnet")))
        for (locale in LegacyLocale.supported) {
            language(locale)
            val parsed = com.noahwallet.send.SendDestination.parse(uri, "mainnet")
            assertNull(parsed.destinationType); assertNull(parsed.bip321); assertEquals(expected, parsed.error)
            val cached = com.noahwallet.send.SendDestination.parse(invoice, "mainnet").error!!
            language("en")
            assertEquals("Network mismatch: expected mainnet, got testnet", cached.localized(context))
            language(locale)
            assertEquals(L10n.text(context, "Network mismatch: expected {expected}, got {actual}", "mainnet", "testnet"), cached.localized(context))
            if (locale != "en") assertNotEquals("Network mismatch: expected mainnet, got testnet", cached.localized(context))
        }
    }

    @Test fun vtxoStatusIdentifiersPreserveDisplayAndUnknownStates() {
        val base = com.noahwallet.onchain.VtxoModel("id", 1000, 101, "Spendable", "", 0, 0, true, "", 0, "", "", false, 100)
        assertEquals(com.noahwallet.onchain.VtxoStatus.active, base.statusID); assertEquals("Active", base.status)
        val expiring = base.copy(expiring = true)
        assertEquals(com.noahwallet.onchain.VtxoStatus.expiring, expiring.statusID); assertEquals("Expiring", expiring.status)
        val expired = expiring.copy(expiry = 100)
        assertEquals(com.noahwallet.onchain.VtxoStatus.expired, expired.statusID); assertEquals("Expired", expired.status)
        val locked = expired.copy(state = "Locked")
        assertEquals(com.noahwallet.onchain.VtxoStatus.locked, locked.statusID); assertEquals("Locked", locked.status)
        val future = base.copy(state = "FutureSDKState")
        assertNull(future.statusID); assertEquals("FutureSDKState", future.status)
    }

    @Test fun selectedLanguageControlsAmountsWithAnEnglishDevice() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            language("es")
            val locale = L10n.context(context).resources.configuration.locales[0]
            assertEquals("1.234.567 sats", com.noahwallet.formatting.BitcoinAmount.format(1234567, BitcoinAmountUnit.sats, locale))
            assertTrue(com.noahwallet.formatting.FiatCurrency.format(1234567.89, "USD", locale).contains("1.234.567,89"))
            assertEquals("12,3 km (7,6 mi)", com.noahwallet.btcmap.BtcMapDistance.format(12.3, locale))
        } finally { java.util.Locale.setDefault(previous) }
    }

    @Test fun everyUsedNativeKeyResolvesInEveryLocale() {
        val document = SharedFixtures.document("translations-coverage").jsonObject
        val sources = document.getValue("coverage").jsonObject.getValue("android").jsonObject.getValue("used").jsonArray
        assertTrue(sources.size > 400)
        for (locale in document.getValue("locales").jsonArray) {
            language(locale.jsonPrimitive.content)
            val resources = L10n.context(context).resources
            for (source in sources) {
                val name = L10n.resourceName(source.jsonPrimitive.content)
                val id = resources.getIdentifier(name, "string", context.packageName).takeIf { it != 0 }
                    ?: resources.getIdentifier(name, "plurals", context.packageName)
                assertTrue("$locale: $source", id != 0)
                assertTrue(resources.getResourceName(id).endsWith(name))
            }
        }
    }

    @Test fun templatesMatchTheRNResolver() {
        for (case in SharedFixtures.load("translations")) {
            val input = case.input.jsonObject
            language(input.getValue("locale").jsonPrimitive.content)
            val args = input.getValue("args").jsonArray.map { it.jsonPrimitive.content }.toTypedArray()
            assertEquals(case.name, case.expected.jsonPrimitive.content, L10n.text(context, input.getValue("source").jsonPrimitive.content, *args))
        }
    }

    @Test fun pluralCategoriesAndArgumentsSurviveResourceCompilation() {
        val used = SharedFixtures.document("translations-coverage").jsonObject.getValue("coverage").jsonObject
            .getValue("android").jsonObject.getValue("used").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val plurals = SharedFixtures.document("translations").jsonObject.getValue("plurals").jsonArray
            .filter { it.jsonObject.getValue("source").jsonPrimitive.content in used }
        // The places count; the exits list's stale warning now has its own native plural.
        assertEquals(1, plurals.size)
        for (locale in LegacyLocale.supported) {
            language(locale)
            for (plural in plurals) {
                val entry = plural.jsonObject
                val source = entry.getValue("source").jsonPrimitive.content
                val forms = entry.getValue("translations").jsonObject.getValue(locale).jsonObject
                for (count in listOf(0, 1, 2, 5, 1_000_000)) {
                    val category = if (locale in listOf("en", "es", "sv") && count == 1) "one" else "other"
                    val displayed = if (count == 1_000_000) when (locale) { "es" -> "1.000.000"; "sv" -> "1\u00a0000\u00a0000"; else -> "1,000,000" } else count.toString()
                    val expected = (forms[category] ?: forms.getValue("other")).jsonPrimitive.content
                        .replace("{_gt_value_4}", displayed).replace("{_gt_value_3}", " · suffix")
                    val actual = if (source.contains("{_gt_value_3}")) L10n.plural(context, source, count, " · suffix") else L10n.plural(context, source, count)
                    assertEquals("$locale/$count/$source", expected, actual)
                }
            }
        }
    }

    @Test fun persistedPreferenceAndSystemFallbackMatchRN() {
        assertEquals("ja", LegacyLocale.resolve(null, listOf("de-DE", "ja-JP")))
        assertEquals("ko", LegacyLocale.resolve("ko-KR", listOf("es-ES")))
        assertEquals("sv", LegacyLocale.resolve("unsupported", listOf("sv_SE")))
        assertEquals("en", LegacyLocale.resolve(null, listOf("de-DE")))
        language("es")
        assertEquals("Cancelar", L10n.text(context, "Cancel"))
        language("ja")
        assertEquals("キャンセル", L10n.text(context, "Cancel"))
        assertEquals("ja", LegacyLocale.resolved(context))
        assertEquals("Native-only 100% copy", L10n.text(context, "Native-only 100% copy"))
        val source = "Automatic payment to {recipient} was skipped. Open the schedule to review its fee and pay manually."
        for (locale in LegacyLocale.supported.filter { it != "en" }) {
            language(locale)
            val translated = L10n.text(context, source, "Alice")
            assertTrue(translated.contains("Alice"))
            assertFalse(translated.contains("{recipient}") || translated.contains("%1$"))
            assertNotEquals("Automatic payment to Alice was skipped. Open the schedule to review its fee and pay manually.", translated)
        }
    }

    @Test fun backgroundCopyRereadsPersistedLanguageWithoutAnAppModel() {
        language("es")
        assertEquals("Has recibido ₿\u00a01234 (≈ US$1.23)", NotificationCopy.received(context, 1234, "1.23", BitcoinAmountUnit.bip177).body)
        language("ja")
        assertEquals("₿\u00a01,234を受け取りました", NotificationCopy.received(context, 1234, null, BitcoinAmountUnit.bip177).body)
        for (locale in LegacyLocale.supported) {
            language(locale)
            val expected = L10n.text(context, "{amount} to {recipient} will be sent tomorrow.", "1,234 sats", "Alice")
            assertEquals(expected, NotificationCopy.reminder(context, "1,234 sats", "Alice", true).body)
            assertEquals(L10n.text(context, "Open Noah to send {amount} to {recipient}.", "1,234 sats", "Alice"), NotificationCopy.reminder(context, "1,234 sats", "Alice", false).body)
        }
    }

    @Test fun everyTypedDeletePromptAsksForTheWordTheCheckAccepts() {
        val word = com.noahwallet.ui.settings.SettingsRules.deleteWord
        assertTrue(com.noahwallet.ui.settings.SettingsRules.deleteConfirmed(word))
        for (locale in LegacyLocale.supported) {
            language(locale)
            for (prompt in listOf("Type delete to confirm",
                "This removes your wallet and recovery phrase from this device. Make sure you have saved your recovery phrase. Type delete to confirm.")) {
                assertTrue("$locale: $prompt", L10n.text(context, prompt).contains(word))
            }
        }
    }
}
