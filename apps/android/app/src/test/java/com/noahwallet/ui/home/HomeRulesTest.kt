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

    // Home redesign (docs/native-rewrite/design/home.md).
    @Test fun freshNeedsAKnownZeroBalanceAndALoadedEmptyHistory() {
        assertEquals(HomeRules.State.fresh, HomeRules.state(false, 0uL, false, null, false))
        assertEquals(HomeRules.State.everyday, HomeRules.state(false, 0uL, false, null, true))
        assertEquals(HomeRules.State.everyday, HomeRules.state(false, 1uL, false, null, false))
        assertEquals(HomeRules.State.everyday, HomeRules.state(false, null, true, null, true))
        assertEquals(HomeRules.State.loading, HomeRules.state(false, null, false, null, false))
        assertEquals(HomeRules.State.loading, HomeRules.state(false, 0uL, true, null, false))
        // A history error is not "empty": show Everyday with the error rather than the get-paid card.
        assertEquals(HomeRules.State.everyday, HomeRules.state(false, 0uL, false, "Couldn't load transactions. Please try again.", false))
        assertEquals(HomeRules.State.suspended, HomeRules.state(true, 5_000uL, false, null, true))
    }

    private fun vtxo(id: String, amount: Long, expiry: Long, expiring: Boolean = true, state: String = "Spendable", tip: Long = 1_000) =
        com.noahwallet.onchain.VtxoModel(id, amount, expiry, state, "", 0, 0, true, "", 0, "", "", expiring, tip)

    @Test fun renewSumsOnlyRefreshableExpiringVtxosAndTakesTheSoonest() {
        assertNull(HomeRules.renew(emptyList()))
        assertNull(HomeRules.renew(listOf(vtxo("a", 1_000, 1_500, expiring = false))))
        val renew = HomeRules.renew(listOf(
            vtxo("b", 20_000, 1_108), vtxo("a", 5_000, 1_140),
            vtxo("locked", 9_000, 1_050, state = "Locked"), vtxo("expired", 7_000, 1_000), vtxo("later", 3_000, 2_000, expiring = false),
        ))!!
        assertEquals(25_000L, renew.sats)
        assertEquals(108L, renew.blocks)
        assertEquals(listOf("a", "b"), renew.ids)
        assertEquals(18L, renew.hours)
    }

    @Test fun renewHoursAtTenMinutesABlockNeverOverstateTimeLeft() {
        assertEquals(24L, HomeRules.Renew(1, 144, emptyList()).hours)
        assertEquals(1L, HomeRules.Renew(1, 7, emptyList()).hours)
        assertEquals(0L, HomeRules.Renew(1, 5, emptyList()).hours)
        assertEquals(0L, HomeRules.Renew(1, -3, emptyList()).hours)
    }

    @Test fun oneNudgeAtATimeInTheSpecOrder() {
        val failed = HomeRules.backup(PersistedBackupState(lastBackupStatus = "failed"), 0, Locale.US)
        val renew = HomeRules.Renew(20_000, 108, listOf("a"))
        assertEquals(HomeRules.Nudge.Notice("n"), HomeRules.nudge(listOf("n", "m"), renew, failed, false, true, true))
        assertEquals(HomeRules.Nudge.Renewal(renew), HomeRules.nudge(emptyList(), renew, failed, false, true, true))
        assertEquals(HomeRules.Nudge.BackupFailed(failed!!), HomeRules.nudge(emptyList(), null, failed, false, true, true))
        assertEquals(HomeRules.Nudge.BackUp, HomeRules.nudge(emptyList(), null, null, false, true, true))
        assertEquals(HomeRules.Nudge.Email, HomeRules.nudge(emptyList(), null, null, true, true, true))
        assertEquals(HomeRules.Nudge.Update, HomeRules.nudge(emptyList(), null, null, true, false, true))
        assertNull(HomeRules.nudge(emptyList(), null, null, true, false, false))
        // An in-progress backup is a status line, not a nudge.
        val running = HomeRules.backup(PersistedBackupState(lastBackupStatus = "in_progress"), 0, Locale.US)
        assertEquals(HomeRules.Nudge.BackUp, HomeRules.nudge(emptyList(), null, running, false, false, false))
    }

    private fun balance(spendable: ULong, pendingInRound: ULong = 0u, onchainPending: ULong = 0u) = com.noahwallet.services.wallet.WalletBalance(
        com.noahwallet.services.wallet.WalletBalance.Ark(spendable, 0u, 0u, 0u, pendingInRound, 0u, 0u, 0u, 0u),
        com.noahwallet.services.wallet.WalletBalance.Onchain(0u, onchainPending, onchainPending))

    @Test fun atMostOneStatusLineInTheSpecOrder() {
        val running = HomeRules.backup(PersistedBackupState(lastBackupStatus = "in_progress"), 0, Locale.US)
        val busy = balance(1_000u, pendingInRound = 300u, onchainPending = 5_000u)
        assertEquals(HomeRules.Status.Error("e"), HomeRules.status("e", busy, 2, running, "bg"))
        assertEquals(HomeRules.Status.Arriving(5_300uL), HomeRules.status(null, busy, 2, running, "bg"))
        // Bark's pending total includes a round in progress, so the arriving line comes first.
        assertEquals(HomeRules.Status.Rounds(2), HomeRules.status(null, balance(1_000u), 2, running, "bg"))
        assertEquals(HomeRules.Status.BackingUp, HomeRules.status(null, balance(1_000u), 0, running, "bg"))
        assertEquals(HomeRules.Status.Background("bg"), HomeRules.status(null, balance(1_000u), 0, null, "bg"))
        assertNull(HomeRules.status(null, balance(1_000u), 0, HomeRules.backup(PersistedBackupState(lastBackupStatus = "failed"), 0, Locale.US), null))
        assertNull(HomeRules.status(null, null, 0, null, null))
    }

    @Test fun backupAndBoardingCompletionAreToastsWithinTodaysExpiry() {
        val done = PersistedBackupState(lastBackupStatus = "success", lastBackupAt = 1_000.0)
        assertEquals("Backup completed", HomeRules.toast(HomeRules.backup(done, 5_999, Locale.US), null))
        assertNull(HomeRules.toast(HomeRules.backup(done, 6_000, Locale.US), null))
        assertEquals("Boarding completed", HomeRules.toast(null, HomeRules.boarding(20_000, 1_000, 2_000, Locale.US)))
        assertNull(HomeRules.toast(HomeRules.backup(PersistedBackupState(lastBackupStatus = "in_progress"), 0, Locale.US), null))
    }
}
