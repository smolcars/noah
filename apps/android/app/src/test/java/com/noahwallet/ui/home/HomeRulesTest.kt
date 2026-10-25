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

    private fun chain(id: String, change: Long, confirmed: Boolean = false) = com.noahwallet.history.HistoryTransaction(id, "Onchain",
        kotlin.math.abs(change), "1970-01-01T00:00:00.000Z", if (change < 0) "outgoing" else "incoming", source = "onchain-wallet", txid = id,
        balanceChangeSat = change, hasConfirmation = confirmed)

    @Test fun atMostOneStatusLineInTheSpecOrder() {
        val busy = balance(1_000u, pendingInRound = 300u, onchainPending = 5_000u)
        val deposit = listOf(chain("d", 5_000))
        assertEquals(HomeRules.Status.Error("e"), HomeRules.status("e", busy, deposit, 2))
        assertEquals(HomeRules.Status.Arriving(5_000uL), HomeRules.status(null, busy, deposit, 2))
        // A round in progress is your own funds, its own line after anything arriving.
        assertEquals(HomeRules.Status.InRound(300uL), HomeRules.status(null, balance(1_000u, pendingInRound = 300u), emptyList(), 2))
        assertEquals(HomeRules.Status.Rounds(2), HomeRules.status(null, balance(1_000u), emptyList(), 2))
        // Backups and background tasks are the top bar's glyph, never a line.
        assertNull(HomeRules.status(null, balance(1_000u), emptyList(), 0))
        assertNull(HomeRules.status(null, null, emptyList(), 0))
    }

    /** A line is tappable only where something explains it; funds in a round open the round while one is active. */
    @Test fun statusLineTapsOnlyWhereSomethingExplainsIt() {
        assertEquals(HomeRules.StatusTap.Balance, HomeRules.Status.Arriving(1uL).tap(roundsActive = true))
        assertEquals(HomeRules.StatusTap.Rounds, HomeRules.Status.InRound(1uL).tap(roundsActive = true))
        assertEquals(HomeRules.StatusTap.Balance, HomeRules.Status.InRound(1uL).tap(roundsActive = false))
        assertEquals(HomeRules.StatusTap.Rounds, HomeRules.Status.Rounds(1).tap(roundsActive = true))
        assertNull(HomeRules.Status.Error("Couldn't load your balance. Pull down to try again.").tap(roundsActive = true))
    }

    @Test fun arrivingCountsIncomingFundsOnly() {
        fun ark(claimable: ULong = 0u, lightningSend: ULong = 0u, inRound: ULong = 0u, exit: ULong = 0u, board: ULong = 0u, arkoorSend: ULong = 0u,
                offboard: ULong = 0u, onchainPending: ULong = 0u) = com.noahwallet.services.wallet.WalletBalance(
            com.noahwallet.services.wallet.WalletBalance.Ark(1_000u, 0u, pendingLightningSend = lightningSend, claimableLightningReceive = claimable,
                pendingInRound = inRound, pendingExit = exit, pendingBoard = board, pendingArkoorSend = arkoorSend, pendingOffboard = offboard),
            com.noahwallet.services.wallet.WalletBalance.Onchain(0u, onchainPending, onchainPending))
        fun status(balance: com.noahwallet.services.wallet.WalletBalance, rows: List<com.noahwallet.history.HistoryTransaction> = emptyList()) =
            HomeRules.status(null, balance, rows, 0)
        // Paying someone over Lightning: the Sending row shows it, never an arriving line.
        val paying = com.noahwallet.history.HistoryTransaction("ln", "Bolt11", 5_000, "1970-01-01T00:00:00.000Z", "outgoing", source = "ark",
            movementStatus = "pending", effectiveBalanceSat = -5_000)
        assertNull(status(ark(lightningSend = 5_000u), listOf(paying)))
        // Your own funds moving aren't arriving either: arkoor sends, offboards, exits, boards.
        assertNull(status(ark(arkoorSend = 1u, offboard = 2u, exit = 3u, board = 4u)))
        // An unconfirmed incoming deposit is, and so is a Lightning receive waiting to be claimed.
        assertEquals(HomeRules.Status.Arriving(100_000uL), status(ark(onchainPending = 100_000u), listOf(chain("d", 100_000))))
        assertEquals(HomeRules.Status.Arriving(2_100uL), status(ark(claimable = 2_100u)))
        assertEquals(HomeRules.Status.Arriving(102_100uL), status(ark(claimable = 2_100u, onchainPending = 100_000u), listOf(chain("d", 100_000))))
        // The change of your own unconfirmed spend sits in bark's on-chain pending too, but isn't arriving.
        assertNull(status(ark(onchainPending = 40_000u), listOf(chain("spend", -60_000))))
        assertEquals(HomeRules.Status.Arriving(100_000uL), status(ark(onchainPending = 140_000u), listOf(chain("spend", -60_000), chain("d", 100_000))))
        // A confirmed deposit has arrived; and history never claims more than the wallet holds as pending.
        assertNull(status(ark(), listOf(chain("d", 100_000, confirmed = true))))
        assertEquals(HomeRules.Status.Arriving(30_000uL), status(ark(onchainPending = 30_000u), listOf(chain("d", 100_000))))
    }

    /** Backups and background tasks are the top bar's glyph: turning while they run, a check (not a toast) when a backup completes. */
    @Test fun backupAndBackgroundWorkAreTheTopBarGlyph() {
        val running = HomeRules.backup(PersistedBackupState(lastBackupStatus = "in_progress"), 0, Locale.US)
        assertEquals("Backing up wallet", HomeRules.activity(running, "Background task in progress..."))
        assertEquals("Background task in progress...", HomeRules.activity(null, "Background task in progress..."))
        assertNull(HomeRules.activity(null, null))
        val done = PersistedBackupState(lastBackupStatus = "success", lastBackupAt = 1_000.0)
        assertEquals(1_000L, HomeRules.backedUpAt(HomeRules.backup(done, 5_999, Locale.US), done))
        assertNull(HomeRules.backedUpAt(HomeRules.backup(done, 6_000, Locale.US), done))
        assertNull(HomeRules.activity(HomeRules.backup(done, 5_999, Locale.US), null))
        // Boarding keeps its toast.
        assertEquals("Boarding completed", HomeRules.boarding(20_000, 1_000, 2_000, Locale.US)?.title)
    }

    @Test fun balanceReReadsFollowAccountingChangesButNotEnrichment() {
        val pending = com.noahwallet.history.HistoryTransaction("onchain-wallet-t", "Onchain", 100_000, "1970-01-01T00:00:00.000Z", "incoming",
            dateLabel = "Unconfirmed", source = "onchain-wallet", txid = "t", balanceChangeSat = 100_000, hasConfirmation = false)
        val ark = com.noahwallet.history.HistoryTransaction("movement-1", "Lnurl", 5_000, "2026-10-22T10:00:00.000Z", "outgoing",
            source = "ark", movementStatus = "pending", effectiveBalanceSat = -5_000, offchainFeeSat = 0)
        val base = HomeRules.accounting(listOf(pending, ark))
        // Block times, prices and the re-sorting they cause don't move money.
        val enriched = listOf(ark.copy(btcPrice = 112_000.0), pending.copy(date = "2026-10-22T09:00:00.000Z", dateLabel = null, sortTimestamp = 1.0, btcPrice = 111_000.0))
        assertEquals(base, HomeRules.accounting(enriched))
        // The same row confirming, or a pending send settling, does.
        assertNotEquals(base, HomeRules.accounting(listOf(pending.copy(hasConfirmation = true, confirmationHeight = 220u), ark)))
        assertNotEquals(base, HomeRules.accounting(listOf(pending, ark.copy(movementStatus = "successful"))))
        assertNotEquals(base, HomeRules.accounting(listOf(pending, ark.copy(effectiveBalanceSat = -5_012, offchainFeeSat = 12))))
        assertNotEquals(base, HomeRules.accounting(listOf(pending)))
    }
}
