package com.noahwallet.ui

import com.noahwallet.localization.L10n
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.ui.settings.limitedProfileName
import com.noahwallet.ui.settings.preferenceName
import com.noahwallet.ui.settings.preferenceDetail
import com.noahwallet.ui.settings.SettingsDestination
import java.util.Locale
import kotlinx.serialization.json.jsonPrimitive
import com.noahwallet.services.storage.LegacyLocale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountParityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun english() { context.getSharedPreferences("gt_store", 0).edit().putString(LegacyLocale.key, "en").commit() }

    @Test fun profileNameLimitMatchesRN() {
        for (item in SharedFixtures.load("profile-name")) {
            assertEquals(item.name, item.expected.jsonPrimitive.content, limitedProfileName(item.input.jsonPrimitive.content))
        }
    }

    @Test fun currencyNamesUseTheAppLocaleAndUnitOptionsIncludeExamples() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val spanish = Locale.forLanguageTag("es")
            assertEquals(java.util.Currency.getInstance("USD").getDisplayName(spanish), preferenceName(SettingsDestination.Currency, "USD", spanish))
            assertNotEquals(preferenceName(SettingsDestination.Currency, "USD", Locale.US), preferenceName(SettingsDestination.Currency, "USD", spanish))
            assertEquals("Español", preferenceName(SettingsDestination.Language, "es", Locale.US))
            assertEquals("₿\u00a01,234", preferenceDetail(SettingsDestination.Unit, "bip177", Locale.US))
            assertEquals("1,234 sats", preferenceDetail(SettingsDestination.Unit, "sats", Locale.US))
            assertEquals("1234 sats", preferenceDetail(SettingsDestination.Unit, "sats", spanish))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun replacementKeepsOldAddressAndCannotSkip() {
        val replacement = EmergencyEmailPresentation(false, true, "old@example.com")
        assertFalse(replacement.canSkip)
        assertEquals("Your current email, old@example.com, will stay active until the new email is verified.", replacement.explanation(context))
        assertEquals("Your current email will stay active until the new email is verified.", replacement.copy(email = null).explanation(context))
        assertTrue(replacement.copy(fromOnboarding = true).canSkip)
        assertTrue(replacement.copy(fromOnboarding = true).explanation(context).startsWith("Email is optional."))
        assertFalse(replacement.copy(verified = false).canSkip)
        assertTrue(replacement.copy(verified = false).explanation(context).startsWith("Email is optional."))
        context.getSharedPreferences("gt_store", 0).edit().putString(LegacyLocale.key, "es").commit()
        assertEquals(L10n.text(context, "Your current email, {email}, will stay active until the new email is verified.", "old@example.com"), replacement.explanation(context))
    }
}
