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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WidgetDataWriterTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("com.noahwallet.regtest", Context.MODE_PRIVATE)
    private val stored get() = WidgetSnapshot.read(prefs)
    private val balance = WalletBalance(WalletBalance.Ark(10u, 1u, 2u, 3u, 4u, 5u, 6u, 7u, 8u), WalletBalance.Onchain(20u, 9u, 29u))
    private val bip177 = WidgetDataWriter.Display(BitcoinAmountUnit.bip177, false, "USD")
    private val now = 1_761_577_200_000L // 2026-10-27T15:00:00Z

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun snapshotHoldsHomesBalancePendingWaveAndRenew() {
        val changes = listOf(WidgetWave.Change(now - 3 * 86_400_000L, 25), WidgetWave.Change(now - 3_600_000, -5))
        val snapshot = WidgetDataWriter.snapshot(balance, WidgetDataWriter.Extras(changes, 18), bip177, null, now, ZoneId.of("UTC"))
        assertEquals(75L, snapshot.balance); assertEquals(44L, snapshot.pending)
        assertEquals(WidgetWave.dailyBalances(75, changes, now, ZoneId.of("UTC")), snapshot.daily)
        assertEquals(75L, snapshot.daily!!.last()); assertEquals(55L, snapshot.daily!!.first())
        assertEquals(now + 18 * 600_000L, snapshot.renewDue)
        assertEquals("bip177", snapshot.amountUnit); assertEquals(now, snapshot.writtenAt)
        // Unreadable history or Renew: calm water, no Renew line.
        val bare = WidgetDataWriter.snapshot(balance, WidgetDataWriter.Extras(null, null), bip177, null, now)
        assertNull(bare.daily); assertNull(bare.renewDue)
    }

    @Test fun fiatIsHomesLatestQuoteForTheChosenCurrencyOnly() {
        val wallet = WalletBalance(WalletBalance.Ark(100_000_000u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))
        val quoted = WidgetDataWriter.snapshot(wallet, null, bip177, WidgetDataWriter.Quote("USD", 112_000.5), now)
        assertEquals(112_000.5, quoted.fiatValue!!, 0.0); assertEquals("USD", quoted.fiatCurrency); assertEquals(112_000.5, quoted.fiatPrice!!, 0.0)
        for (quote in listOf(WidgetDataWriter.Quote("EUR", 99_000.0), WidgetDataWriter.Quote("USD", Double.NaN), WidgetDataWriter.Quote("USD", 0.0), null)) {
            val snapshot = WidgetDataWriter.snapshot(wallet, null, bip177, quote, now)
            assertNull(snapshot.fiatValue); assertNull(snapshot.fiatCurrency); assertNull(snapshot.fiatPrice)
        }
    }

    @Test fun writeReplacesOldKeysAndKeepsTheQuoteWhileItsCurrencyHolds() = runBlocking {
        prefs.edit().putLong("totalBalance", 1).putLong("closestExpiryBlocks", 3).commit()
        WidgetDataWriter.write(context, balance, null, bip177, "regtest")
        assertEquals(setOf(WidgetSnapshot.KEY), prefs.all.keys)
        assertEquals(75L, stored!!.balance)
        WidgetDataWriter.republish(context, bip177, "regtest", WidgetDataWriter.Quote("USD", 100_000_000.0))
        assertEquals(75.0, stored!!.fiatValue!!, 0.0)
        // The next write (a push job, say) recomputes fiat from the stored quote.
        val more = WalletBalance(WalletBalance.Ark(200u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))
        WidgetDataWriter.write(context, more, null, bip177, "regtest")
        assertEquals(200.0, stored!!.fiatValue!!, 0.0)
        // Another currency has no quote until Home fetches one.
        WidgetDataWriter.write(context, more, null, bip177.copy(currency = "EUR"), "regtest")
        assertNull(stored!!.fiatValue); assertNull(stored!!.fiatCurrency)
    }

    @Test fun displayChangesRepublishWithoutTheWallet() = runBlocking {
        WidgetDataWriter.republish(context, bip177.copy(hidden = true), "regtest")
        assertNull("No wallet: nothing to republish", stored)
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(emptyList(), 5), bip177, "regtest")
        val before = stored!!
        WidgetDataWriter.republish(context, WidgetDataWriter.Display(BitcoinAmountUnit.sats, true, "USD"), "regtest")
        assertEquals(before.copy(amountUnit = "sats", hidden = true), stored)
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

    private fun vtxo(id: String, expiry: UInt, state: VtxoState = VtxoState.Spendable, amount: ULong = 30_000u) =
        Vtxo("server", 144u, OutPoint("anchor", 0u), OutPoint("point", 0u), id, amount, expiry, "pubkey", state, 1u, 500u, true)

    private class Chain(val items: List<Vtxo>, val due: Set<String>, val tipHeight: Long) : OnchainWalletAccess {
        override val walletID = "fixture"
        override suspend fun tip() = tipHeight
        override suspend fun vtxos() = items
        override suspend fun expiring(threshold: UInt) = due
    }

    private class Handles(override val m6: OnchainWalletAccess, val movements: List<Movement> = emptyList()) : WalletSession.Handles {
        override fun notifications(): WalletSession.Notifications = error("unused")
        override suspend fun history(): List<Movement> = movements
        override suspend fun transactions(): List<WalletTransaction> = emptyList()
        override suspend fun receiveRequest(): com.noahwallet.services.wallet.ReceiveRequest = error("unused")
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
            assertEquals(blocks, WidgetDataWriter.extras(Handles(chain), "regtest", now).renewBlocks)
        }
        // A failed read is no Renew line, and doesn't stop the wave.
        val broken = object : OnchainWalletAccess { override val walletID = "x"; override suspend fun tip() = 100L }
        assertEquals(WidgetDataWriter.Extras(emptyList(), null), WidgetDataWriter.extras(Handles(broken), "regtest", now))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecurringWidgetExpiryTest {
    /** Recurring writes the whole snapshot like every writer; a wave or Renew it couldn't read just isn't drawn. */
    @Test fun recurringWriteIsWhole() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val balance = WalletBalance(WalletBalance.Ark(1234u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u), WalletBalance.Onchain(0u, 0u, 0u))
        val display = WidgetDataWriter.Display(BitcoinAmountUnit.bip177, false, "USD")
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(emptyList(), 12), display, "regtest")
        WidgetDataWriter.write(context, balance, WidgetDataWriter.Extras(null, null), display, "regtest")
        val stored = WidgetSnapshot.read(context.getSharedPreferences("com.noahwallet.regtest", Context.MODE_PRIVATE))!!
        assertEquals(1234L, stored.balance); assertNull(stored.renewDue); assertNull(stored.daily)
    }
}
