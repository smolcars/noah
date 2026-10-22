package com.noahwallet.ui.home

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.storage.PersistedBackupState
import kotlinx.serialization.json.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class HomeRulesTest {
    @Test fun backupVisibilityMatchesRN() {
        SharedFixtures.load("home-status").filter { it.input.jsonObject["operation"]?.jsonPrimitive?.content == "backup" }.forEach { case ->
            val i = case.input.jsonObject
            val state = PersistedBackupState(backupPending = i.getValue("pending").jsonPrimitive.boolean,
                lastBackupStatus = i.getValue("status").jsonPrimitive.content, lastBackupAt = i["at"]?.jsonPrimitive?.doubleOrNull)
            assertEquals(case.name, case.expected.jsonPrimitive.contentOrNull,
                HomeRules.backup(state, i.getValue("now").jsonPrimitive.long, Locale.US)?.title)
        }
    }
    @Test fun activeRoundsMatchRN() {
        SharedFixtures.load("home-status").filter { it.input.jsonObject["operation"]?.jsonPrimitive?.content == "round" }.forEach { case ->
            val i = case.input.jsonObject
            assertEquals(case.name, case.expected.jsonPrimitive.boolean, HomeRules.activeRound(i.getValue("final").jsonPrimitive.boolean, i.getValue("status").jsonPrimitive.content))
        }
    }
    @Test fun hiddenFiatDoesNotNeedAPriceAndUsesRNSymbols() {
        SharedFixtures.document("home-status").jsonObject.getValue("symbols").jsonObject.forEach { (currency, symbol) ->
            assertEquals("${symbol.jsonPrimitive.content} ••••", HomeRules.fiat(1_234, null, currency, Locale.US, true))
        }
    }
    @Test fun fiatUsesSharedRoundingAndLoadingState() {
        assertEquals("$1.00", HomeRules.fiat(1000, 100_000.0, "USD", Locale.US, false))
        assertEquals("¥2", HomeRules.fiat(1000, 150_000.0, "JPY", Locale.US, false))
        assertEquals("$0.00", HomeRules.fiat(0, 100_000.0, "USD", Locale.US, false))
        assertNull(HomeRules.fiat(1000, null, "USD", Locale.US, false))
        assertNull(HomeRules.fiat(1000, Double.NaN, "USD", Locale.US, false))
    }
    @Test fun visibleFiatWaitsForBothBalanceAndPriceInEitherArrivalOrder() {
        assertNull(HomeRules.fiat(null, 100_000.0, "USD", Locale.US, false))
        assertNull(HomeRules.fiat(1000, null, "USD", Locale.US, false))
        assertEquals("$1.00", HomeRules.fiat(1000, 100_000.0, "USD", Locale.US, false))
        assertEquals("$0.00", HomeRules.fiat(0, 100_000.0, "USD", Locale.US, false))
        assertEquals("$ ••••", HomeRules.fiat(null, null, "USD", Locale.US, true))
    }

    @Test fun backupFailuresAreRetryableAndPendingSuccessIsHidden() {
        val failed = HomeRules.backup(PersistedBackupState(lastBackupStatus = "failed"), 0, Locale.US)!!
        assertTrue(failed.retry); assertTrue(failed.failed)
        assertEquals("An unknown error occurred while backing up.", failed.message)
    }
    @Test fun boardingSuccessIsTransientAndAlwaysInSatsLikeRN() {
        assertEquals("20,000 sats available in Ark", HomeRules.boarding(20_000, 1000, 5999, Locale.US)?.message)
        assertNull(HomeRules.boarding(20_000, 1000, 6000, Locale.US))
    }
    @Test fun storeLinksMatchRN() {
        assertEquals("https://play.google.com/store/apps/details?id=com.noahwallet.mainnet", HomeRules.updateURL("mainnet"))
        assertEquals("https://play.google.com/store/apps/details?id=com.noahwallet.signet", HomeRules.updateURL("signet"))
        assertEquals(HomeRules.updateURL("signet"), HomeRules.updateURL("regtest"))
    }
}
