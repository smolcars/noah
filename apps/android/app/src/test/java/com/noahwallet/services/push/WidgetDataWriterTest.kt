package com.noahwallet.services.push

import android.content.Context
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.onchain.OnchainWalletAccess
import com.noahwallet.onchain.vtxoModel
import com.noahwallet.services.wallet.ReceiveRequest
import com.noahwallet.services.wallet.WalletBalance
import com.noahwallet.services.wallet.WalletSession
import com.noahwallet.ui.home.HomeRules
import com.noahwallet.widgets.WidgetSnapshot
import com.noahwallet.widgets.WidgetWave
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WidgetDataWriterTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("com.noahwallet.regtest", Context.MODE_PRIVATE)
    private val stored get() = WidgetSnapshot.read(prefs)
    private val balance = WalletBalance(WalletBalance.Ark(10u, 1u, 2u, 3u, 4u, 5u, 6u, 7u, 8u), WalletBalance.Onchain(20u, 9u, 29u))
    private val bip177 = WidgetDataWriter.Display(BitcoinAmountUnit.bip177, false, "USD")
    private val now = 1_761_577_200_000L // 2026-10-27T15:00:00Z

    @Before fun reset() { prefs.edit().clear().commit(); WidgetDataWriter.forgetQuote() }

    private fun movement(id: String, at: Long, sats: Long) = com.noahwallet.history.HistoryTransaction(id, "Arkoor", kotlin.math.abs(sats),
        com.noahwallet.history.HistoryRules.isoDate(java.util.Date(at)), if (sats < 0) "outgoing" else "incoming", source = "ark",
        movementStatus = "successful", effectiveBalanceSat = sats)

    @Test fun snapshotHoldsHomesBalancePendingWaveAndRenew() {
        val rows = listOf(movement("a", now - 3 * 86_400_000L, 25), movement("b", now - 3_600_000, -5))
        val changes = WidgetWave.changes(rows, emptyMap())
        val snapshot = WidgetDataWriter.snapshot(balance, WidgetDataWriter.Extras(rows, 100, 18), bip177, null, emptyMap(), now, TimeZone.getTimeZone("UTC"))
        // Arriving is Home's: the claimable Lightning receive, with no unconfirmed deposit in history.
        assertEquals(75L, snapshot.balance); assertEquals(3L, snapshot.arriving)
        assertEquals(WidgetWave.dailyBalances(75, changes, now, TimeZone.getTimeZone("UTC")), snapshot.daily)
        assertEquals(75L, snapshot.daily!!.last()); assertEquals(55L, snapshot.daily.first())
        assertEquals(now + 18 * 600_000L, snapshot.renewDue)
        assertEquals("bip177", snapshot.amountUnit); assertEquals(now, snapshot.writtenAt)
        // Unreadable history or Renew: calm water, no Renew line.
        val bare = WidgetDataWriter.snapshot(balance, WidgetDataWriter.Extras(), bip177, null, now = now)
        assertNull(bare.daily); assertNull(bare.renewDue)
    }

    /** Home's Arriving rule: incoming only, so an outgoing pending send, a board or a change output never shows. */
    @Test fun arrivingIsHomesRule() {
        val wallet = WalletBalance(WalletBalance.Ark(0u, 0u, 7_000u, 2_000u, 0u, 0u, 50_000u, 0u, 0u), WalletBalance.Onchain(100_000u, 30_000u, 130_000u))
        fun row(id: String, sats: Long, confirmed: Boolean) = com.noahwallet.history.HistoryTransaction(id, "Onchain", kotlin.math.abs(sats),
            "1970-01-01T00:00:00.000Z", if (sats < 0) "outgoing" else "incoming", source = "onchain-wallet", txid = id,
            balanceChangeSat = sats, hasConfirmation = confirmed)
        val rows = listOf(row("deposit", 20_000, false), row("spend", -40_000, false), row("old", 90_000, true))
        val snapshot = WidgetDataWriter.snapshot(wallet, WidgetDataWriter.Extras(rows), bip177, null, now = now)
        assertEquals(HomeRules.arriving(wallet, rows).toLong(), snapshot.arriving)
        assertEquals(22_000L, snapshot.arriving)
        // Without history, only the Lightning receive Home would also count.
        assertEquals(2_000L, WidgetDataWriter.snapshot(wallet, null, bip177, null, now = now).arriving)
    }

    @Test fun fiatIsHomesLatestQuoteForTheChosenCurrencyOnly() {
        val wallet = WalletBalance(WalletBalance.Ark(100_000_000u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))
        val quoted = WidgetDataWriter.snapshot(wallet, null, bip177, WidgetDataWriter.Quote("USD", 112_000.5), now = now)
        assertEquals("USD", quoted.fiatCurrency); assertEquals(112_000.5, quoted.fiatPrice!!, 0.0)
        for (quote in listOf(WidgetDataWriter.Quote("EUR", 99_000.0), WidgetDataWriter.Quote("USD", Double.NaN), WidgetDataWriter.Quote("USD", 0.0), null)) {
            val snapshot = WidgetDataWriter.snapshot(wallet, null, bip177, quote, now = now)
            assertNull(snapshot.fiatCurrency); assertNull(snapshot.fiatPrice)
        }
    }

    @Test fun writeReplacesOldKeysAndKeepsTheQuoteWhileItsCurrencyHolds() = runBlocking {
        prefs.edit().putLong("totalBalance", 1).putLong("closestExpiryBlocks", 3).commit()
        WidgetDataWriter.write(context, balance, null, { bip177 }, "regtest", WidgetDataWriter.ticket())
        assertEquals(setOf(WidgetSnapshot.KEY, WidgetDataWriter.CHAIN_DATES), prefs.all.keys)
        assertEquals(75L, stored!!.balance)
        WidgetDataWriter.republish(context, { bip177 }, "regtest", WidgetDataWriter.Quote("USD", 100_000_000.0))
        assertEquals(100_000_000.0, stored!!.fiatPrice!!, 0.0); assertEquals("USD", stored!!.fiatCurrency)
        // The next write (a push job, say) recomputes fiat from the stored quote.
        val more = WalletBalance(WalletBalance.Ark(200u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))
        WidgetDataWriter.write(context, more, null, { bip177 }, "regtest", WidgetDataWriter.ticket())
        assertEquals(200L, stored!!.balance); assertEquals(100_000_000.0, stored!!.fiatPrice!!, 0.0)
        // Another currency has no quote until Home fetches one.
        WidgetDataWriter.write(context, more, null, { bip177.copy(currency = "EUR") }, "regtest", WidgetDataWriter.ticket())
        assertNull(stored!!.fiatPrice); assertNull(stored!!.fiatCurrency)
    }

    @Test fun displayChangesRepublishWithoutTheWallet() = runBlocking {
        WidgetDataWriter.republish(context, { bip177.copy(hidden = true) }, "regtest")
        assertNull("No wallet: nothing to republish", stored)
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(emptyList(), 100, 5), { bip177 }, "regtest", WidgetDataWriter.ticket())
        val before = stored!!
        WidgetDataWriter.republish(context, { WidgetDataWriter.Display(BitcoinAmountUnit.sats, true, "USD") }, "regtest")
        assertEquals(before.copy(amountUnit = "sats", hidden = true), stored)
        WidgetDataWriter.clear(context, "regtest")
        assertTrue(prefs.all.isEmpty())
    }

    /**
     * A slow background write can't bring back a setting changed while it waited: the display is read under the
     * writers' lock, from its live source (iOS review: a hide reverted to visible).
     */
    @Test fun aSlowWriterTakesTheCurrentDisplay() = runBlocking {
        val base = RuntimeEnvironment.getApplication()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        var reads = 0
        val slow = object : android.content.ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                if (reads++ == 0) { entered.complete(Unit); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                return base.getSharedPreferences(name, mode)
            }
        }
        var settings = bip177; var displayReads = 0
        val push = launch(kotlinx.coroutines.Dispatchers.IO) {
            WidgetDataWriter.write(slow, balance, null, { displayReads++; settings }, "regtest", WidgetDataWriter.ticket())
        }
        entered.await()
        assertEquals("read under the lock, not before it", 0, displayReads)
        // The user hides the balance (and its republish has run) while the push job waits.
        settings = settings.copy(hidden = true)
        release.countDown(); push.join()
        assertTrue(stored!!.hidden); assertEquals(1, displayReads)
    }

    private fun sats(total: Long) = WalletBalance(WalletBalance.Ark(total.toULong(), 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))

    /**
     * Writes are ordered by when their balance was read, not when they commit: a push that read A, stalled, and commits
     * after the foreground published a newer B is dropped (Sol review), and nothing read before a clear comes back.
     */
    @Test fun aStalledPushNeverPublishesAnOlderBalance() = runBlocking {
        val read = kotlinx.coroutines.CompletableDeferred<Unit>(); val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        // The push job reads A, then stalls (its history read, say) before it publishes.
        val push = launch(kotlinx.coroutines.Dispatchers.IO) {
            val ticket = WidgetDataWriter.ticket()
            read.complete(Unit); gate.await()
            WidgetDataWriter.write(context, sats(100), null, { bip177 }, "regtest", ticket)
        }
        // Meanwhile the app reads B and publishes it.
        read.await()
        WidgetDataWriter.write(context, sats(200), null, { bip177 }, "regtest", WidgetDataWriter.ticket())
        val newest = stored!!
        gate.complete(Unit); push.join()
        assertEquals(newest, stored)
        assertEquals(200L, stored!!.balance)
        // Reads published in order both land.
        WidgetDataWriter.write(context, sats(300), null, { bip177 }, "regtest", WidgetDataWriter.ticket())
        assertEquals(300L, stored!!.balance)
        // Deleting the wallet drops a write read before it: the deleted balance never comes back.
        val beforeDelete = WidgetDataWriter.ticket()
        WidgetDataWriter.clear(context, "regtest")
        WidgetDataWriter.write(context, sats(400), null, { bip177 }, "regtest", beforeDelete)
        assertNull(stored)
        WidgetDataWriter.write(context, sats(500), null, { bip177 }, "regtest", WidgetDataWriter.ticket())
        assertEquals(500L, stored!!.balance)
    }

    /** Home's quote can arrive before the first snapshot (a new wallet's first visit): the first write still shows fiat. */
    @Test fun aQuoteBeforeTheFirstSnapshotReachesTheFirstWrite() = runBlocking {
        WidgetDataWriter.republish(context, { bip177 }, "regtest", WidgetDataWriter.Quote("USD", 100_000_000.0))
        assertNull(stored)
        WidgetDataWriter.write(context, balance, null, { bip177 }, "regtest", WidgetDataWriter.ticket())
        assertEquals(100_000_000.0, stored!!.fiatPrice!!, 0.0); assertEquals("USD", stored!!.fiatCurrency)
        // Home's newer quote wins over the one the snapshot carried.
        WidgetDataWriter.republish(context, { bip177 }, "regtest", WidgetDataWriter.Quote("USD", 50_000_000.0))
        WidgetDataWriter.write(context, balance, null, { bip177 }, "regtest", WidgetDataWriter.ticket())
        assertEquals(50_000_000.0, stored!!.fiatPrice!!, 0.0)
    }

    /** A write that started reading before a settings change lands after its republish, and keeps what it changed. */
    @Test fun aPendingWriteKeepsANewerRepublish() = runBlocking {
        WidgetDataWriter.write(context, balance, null, { bip177 }, "regtest", WidgetDataWriter.ticket())
        var settings = bip177
        val push = WidgetDataWriter.ticket()
        settings = WidgetDataWriter.Display(BitcoinAmountUnit.sats, true, "EUR")
        WidgetDataWriter.republish(context, { settings }, "regtest", WidgetDataWriter.Quote("EUR", 90_000.0))
        val republished = stored!!
        WidgetDataWriter.write(context, balance, null, { settings }, "regtest", push)
        assertTrue(stored!!.hidden); assertTrue(stored!!.sats)
        assertEquals("EUR", stored!!.fiatCurrency); assertEquals(90_000.0, stored!!.fiatPrice!!, 0.0)
        assertEquals(republished.copy(writtenAt = stored!!.writtenAt), stored)
    }

    /** Confirmation dates are kept per txid beside the snapshot, never recomputed, and go with the wallet. */
    @Test fun confirmationDatesPersistAndClear() = runBlocking {
        val deposit = com.noahwallet.history.HistoryTransaction("onchain-wallet-d", "Onchain", 60_000, "1970-01-01T00:00:00.000Z", "incoming",
            source = "onchain-wallet", txid = "d", balanceChangeSat = 60_000, hasConfirmation = true, confirmationHeight = 994u)
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(listOf(deposit), 1_000), { bip177 }, "regtest", WidgetDataWriter.ticket())
        val first = prefs.getString(WidgetDataWriter.CHAIN_DATES, null)
        assertNotNull(first); assertTrue(first!!.contains("\"d\""))
        // A later write with the same tip keeps the first date; a known block time replaces it.
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(listOf(deposit), 1_000), { bip177 }, "regtest", WidgetDataWriter.ticket())
        assertEquals(first, prefs.getString(WidgetDataWriter.CHAIN_DATES, null))
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(listOf(deposit), 1_000), { bip177 }, "regtest", WidgetDataWriter.ticket(),
            known = mapOf("d" to 1_700_000_000_000))
        assertTrue(prefs.getString(WidgetDataWriter.CHAIN_DATES, null)!!.contains("1700000000000"))
        // A write without readable history keeps them; so does republishing a display change; deleting the wallet clears them.
        val exact = prefs.getString(WidgetDataWriter.CHAIN_DATES, null)
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(), { bip177 }, "regtest", WidgetDataWriter.ticket())
        assertEquals(exact, prefs.getString(WidgetDataWriter.CHAIN_DATES, null))
        WidgetDataWriter.republish(context, { bip177.copy(hidden = true) }, "regtest")
        assertNotNull(prefs.getString(WidgetDataWriter.CHAIN_DATES, null))
        WidgetDataWriter.clear(context, "regtest")
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun displayReadsHomesPersistedKeys() {
        val settings = com.noahwallet.services.storage.SettingsStorage(context, sync = {})
        assertEquals(bip177, WidgetDataWriter.Display.read(settings))
        settings.set("""{"state":{"bitcoinAmountUnit":"sats","preferredCurrency":"EUR"},"version":0}""", "profile-storage")
        settings.set("""{"state":{"isHomeBalanceHidden":true},"version":0}""", "privacy-storage")
        assertEquals(WidgetDataWriter.Display(BitcoinAmountUnit.sats, true, "EUR"), WidgetDataWriter.Display.read(settings))
    }

    private class Chain(val items: List<Vtxo>, val due: Set<String>, val tipHeight: Long) : OnchainWalletAccess {
        override val walletID = "fixture"
        override suspend fun tip() = tipHeight
        override suspend fun vtxos() = items
        override suspend fun expiring(threshold: UInt) = due
    }

    /** Home's Renew rule, for the same VTXOs: expiring, spendable and not yet expired; the soonest one's blocks. */
    @Test fun renewFollowsHomesRule() = runBlocking {
        val cases = listOf(
            Chain(listOf(vtxo("a", 118u), vtxo("b", 130u)), setOf("a", "b"), 100) to 18L,
            Chain(listOf(vtxo("a", 118u), vtxo("b", 130u)), emptySet(), 100) to null,
            Chain(listOf(vtxo("expired", 100u), vtxo("b", 130u)), setOf("expired"), 100) to null,
            Chain(listOf(vtxo("spent", 110u, VtxoState.Spent), vtxo("b", 130u)), setOf("spent", "b"), 100) to 30L,
            Chain(emptyList(), emptySet(), 100) to null,
        )
        for ((chain, blocks) in cases) {
            val home = HomeRules.renew(chain.items.map { vtxoModel(it, chain.tipHeight, chain.due) })
            assertEquals(home?.blocks, blocks)
            assertEquals(blocks, WidgetDataWriter.extras(WidgetHandles(chain), "regtest", WidgetHandles.empty).renewBlocks)
        }
        // A failed read is no Renew line, and doesn't stop the wave.
        val broken = object : OnchainWalletAccess { override val walletID = "x"; override suspend fun tip() = 100L }
        assertEquals(WidgetDataWriter.Extras(emptyList(), 100, null), WidgetDataWriter.extras(WidgetHandles(broken), "regtest", WidgetHandles.empty))
    }
}

internal fun vtxo(id: String, expiry: UInt = 200u, state: VtxoState = VtxoState.Spendable, amount: ULong = 30_000u) =
    Vtxo("server", 144u, OutPoint("anchor", 0u), OutPoint("point", 0u), id, amount, expiry, "pubkey", state, 1u, 500u, true)

/**
 * A wallet for the widget's reads: each read returns the next of its list, the last one repeated. A null balance or
 * receives entry is a failed read; [balanceDelay] a slow balance read (bark's fetches the chain tip).
 */
@Suppress("LongParameterList")
internal class WidgetHandles(override val m6: OnchainWalletAccess, val balances: List<WalletBalance?> = listOf(empty),
    val histories: List<List<Movement>> = listOf(emptyList()), val receives: List<List<LightningReceive>?> = listOf(emptyList()),
    val boards: List<List<String>> = listOf(emptyList()), val balanceDelay: Long = 0) : WalletSession.Handles {
    companion object {
        val empty = WalletBalance(WalletBalance.Ark(0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))
    }
    var balanceReads = 0
    var historyReads = 0
    private var receiveReads = 0
    private var boardReads = 0
    private fun <T> List<T>.read(index: Int) = this[minOf(index, lastIndex)]
    override suspend fun balance(): WalletBalance {
        kotlinx.coroutines.delay(balanceDelay)
        return balances.read(balanceReads++) ?: error("unavailable")
    }
    override suspend fun history() = histories.read(historyReads++)
    override suspend fun transactions(): List<WalletTransaction> = emptyList()
    override suspend fun pendingLightningReceives() = receives.read(receiveReads++) ?: error("unavailable")
    override suspend fun pendingBoardVtxos() = boards.read(boardReads++).map { vtxo(it) }
    override fun notifications(): WalletSession.Notifications = error("unused")
    override suspend fun receiveRequest(): ReceiveRequest = error("unused")
    override suspend fun receiveInvoice(request: ReceiveRequest, amountSat: Long, description: String): ReceiveRequest = error("unused")
    override suspend fun initializeAddress() = Unit
    override suspend fun peekPublicKey(index: UInt): String = error("unused")
    override suspend fun peekAddress(index: UInt): String = error("unused")
    override suspend fun summary(): WalletSession.Summary = error("unused")
    override suspend fun sync() = Unit
    override suspend fun maintenanceDelegated() = Unit
    override suspend fun bolt11Invoice(amountSats: ULong): String = error("unused")
    override suspend fun claimLightningReceives() = Unit
    override suspend fun createSnapshot(destination: File): WalletSnapshotInfo = error("unused")
    override suspend fun stopDaemonWait() = Unit
    override fun close() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecurringWidgetExpiryTest {
    /** Recurring writes the whole snapshot like every writer; a wave it couldn't read keeps the earlier days, a Renew isn't drawn. */
    @Test fun recurringWriteIsWhole() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val balance = WalletBalance(WalletBalance.Ark(1234u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))
        val display = WidgetDataWriter.Display(BitcoinAmountUnit.bip177, false, "USD")
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(emptyList(), 100, 12), { display }, "regtest", WidgetDataWriter.ticket())
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(), { display }, "regtest", WidgetDataWriter.ticket())
        val stored = WidgetSnapshot.read(context.getSharedPreferences("com.noahwallet.regtest", Context.MODE_PRIVATE))!!
        assertEquals(1234L, stored.balance); assertNull(stored.renewDue); assertEquals(List(31) { 1234L }, stored.daily)
    }
}
