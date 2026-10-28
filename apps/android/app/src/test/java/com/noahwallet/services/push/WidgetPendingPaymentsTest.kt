package com.noahwallet.services.push

import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.history.HistoryMapping
import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.onchain.OnchainWalletAccess
import com.noahwallet.services.wallet.WalletBalance
import com.noahwallet.widgets.WidgetSnapshot
import com.noahwallet.widgets.WidgetWave
import io.github.niteshbalusu11.bark.LightningReceive
import io.github.niteshbalusu11.bark.Movement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.TimeZone

/**
 * Pending payments in the wave (docs/native-rewrite/design/widgets.md, The wave): a change is anything the displayed
 * total already reflects, so today's value is the balance, yesterday's is it less today's changes, and the earlier days
 * never move while a payment is pending or when it settles. The same cases as the iOS widget's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WidgetPendingPaymentsTest {
    private val now = 1_761_577_200_000L // 2026-10-27T15:00:00Z
    private val day = 86_400_000L
    private val utc = TimeZone.getTimeZone("UTC")
    private val display = WidgetDataWriter.Display(BitcoinAmountUnit.bip177, false, "USD")

    @Suppress("LongParameterList")
    private fun balance(spendable: Long = 0, sending: Long = 0, claimable: Long = 0, boarding: Long = 0, offboarding: Long = 0,
        confirmed: Long = 0, unconfirmed: Long = 0) = WalletBalance(
        WalletBalance.Ark(spendable.toULong(), 0u, sending.toULong(), claimable.toULong(), 0u, 0u, boarding.toULong(), 0u, offboarding.toULong()),
        WalletBalance.Onchain(confirmed.toULong(), unconfirmed.toULong(), (confirmed + unconfirmed).toULong()))

    @Suppress("LongParameterList")
    private fun movement(subsystem: String, sats: Long, at: Long, status: String = "successful", id: UInt = 9u,
        outputs: List<String> = emptyList(), metadata: String = "{}"): Movement {
        val (name, kind) = subsystem.split(':')
        val date = HistoryRules.isoDate(Date(at))
        return Movement(id, status, name, kind, metadata, sats, sats, 0uL, emptyList(), emptyList(), emptyList(), outputs, emptyList(),
            date, date, null, null, null, null)
    }
    private fun rows(vararg movements: Movement) = movements.map { HistoryMapping.transaction(it) }
    private fun chain(txid: String, sats: Long, height: UInt? = null) = HistoryTransaction("onchain-wallet-$txid", "Onchain", kotlin.math.abs(sats),
        "1970-01-01T00:00:00.000Z", if (sats < 0) "outgoing" else "incoming", source = "onchain-wallet", txid = txid, balanceChangeSat = sats,
        hasConfirmation = height != null, confirmationHeight = height)

    /** The writer's 31 days for [balance] and [rows]; today is always the balance. */
    private fun days(balance: WalletBalance, rows: List<HistoryTransaction>, chainDates: Map<String, Long> = emptyMap(),
        pending: WidgetWave.Pending = WidgetWave.Pending(), now: Long = this.now): List<Long> {
        val days = WidgetDataWriter.snapshot(balance, WidgetDataWriter.Extras(rows, pending = pending), display, null, chainDates, now, utc).daily!!
        assertEquals(balance.totalSats.toLong(), days.last())
        return days
    }

    /** 100,000 sats that arrived ten days ago, settled, and the 30 days before today they draw. */
    private val earlier = movement("bark.arkoor:receive", 100_000, now - 10 * day)
    private val before = List(20) { 0L } + List(10) { 100_000L }

    /** An unconfirmed on-chain receive is in the balance, so it's today's change from when it's first seen; confirming never moves it. */
    @Test fun anUnconfirmedReceiveIsTodaysChangeAndStaysThere() {
        val receive = chain("receive-txid", 50_000)
        val seen = WidgetWave.chainDates(listOf(receive), emptyMap(), emptyMap(), 1_000, now - 3_600_000)
        val pending = days(balance(spendable = 100_000, unconfirmed = 50_000), rows(earlier) + receive, seen.mapValues { it.value.at })
        assertEquals(before + 150_000L, pending)
        // Confirmed the next day, with Activity's block time: nothing earlier moves.
        val tomorrow = now + day
        val confirmed = receive.copy(hasConfirmation = true, confirmationHeight = 1_000u)
        val dates = WidgetWave.chainDates(listOf(confirmed), seen, mapOf("receive-txid" to tomorrow - 600_000), 1_001, tomorrow)
        assertEquals(pending.drop(1) + 150_000L,
            days(balance(spendable = 100_000, confirmed = 50_000), rows(earlier) + confirmed, dates.mapValues { it.value.at }, now = tomorrow))
    }

    /** A Lightning send in flight still holds its sats in the balance (pending_lightning_send): no change until it settles. */
    @Test fun aPendingLightningSendIsNoChangeUntilItSettles() {
        fun send(status: String) = movement("bark.lightning_send:send", -20_010, now - 600_000, status, 3u)
        assertEquals(before + 100_000L, days(balance(spendable = 79_990, sending = 20_010), rows(earlier, send("pending"))))
        assertEquals(before + 79_990L, days(balance(spendable = 79_990), rows(earlier, send("successful"))))
    }

    /**
     * An offboard is a change only as far as pending_offboard lets its sats go: not while it holds change being split
     * off, nor while its transaction is named but not out (a failed broadcast, twice); then once it's out, and nothing
     * moves when it settles. Two at once share the bucket.
     */
    @Test fun anOffboardCountsOnceTheBalanceLetsItsSatsGo() {
        fun offboard(status: String, at: Long, sats: Long = -30_300, id: UInt = 4u) =
            movement("bark.offboard:send_onchain", sats, at, status, id, metadata = """{"offboard_txid":"out"}""")
        val first = offboard("pending", now - 1_200_000)
        assertEquals(before + 100_000L, days(balance(offboarding = 100_000), rows(earlier, first)))
        repeat(2) { assertEquals(before + 100_000L, days(balance(spendable = 69_700, offboarding = 30_300), rows(earlier, first))) }
        assertEquals(before + 69_700L, days(balance(spendable = 69_700), rows(earlier, first)))
        assertEquals(before + 69_700L, days(balance(spendable = 69_700), rows(earlier, offboard("successful", now - 1_200_000))))

        val second = offboard("pending", now - 600_000, -10_000, 5u)
        assertEquals(before + 69_700L, days(balance(spendable = 59_700, offboarding = 10_000), rows(earlier, first, second)))
        assertEquals(before + 59_700L, days(balance(spendable = 59_700), rows(earlier, first, second)))
    }

    /**
     * A board: nothing while its funding transaction isn't out; then the funding transaction and the board (once
     * pendingBoardVtxos reports its VTXO) are today's change, net of the fee, and nothing moves when it confirms and
     * completes. Another board's VTXO doesn't count it.
     */
    @Test fun aPendingBoardCountsBothHalvesFromItsDate() {
        val deposit = chain("deposit-txid", 50_500, 900u)
        val dated = mapOf("deposit-txid" to now - 10 * day)
        fun board(status: String) = movement("bark.board:board", 50_000, now - 600_000, status, 6u, listOf("board-vtxo"),
            """{"chain_anchor":"funding-txid:0"}""")
        val funding = chain("funding-txid", -50_500)
        val start = List(20) { 0L } + List(10) { 50_500L }
        val confirming = WidgetWave.Pending(boardVtxos = setOf("board-vtxo"))
        assertEquals(start + 50_500L, days(balance(confirmed = 50_500), rows(board("pending")) + deposit, dated))
        assertEquals(start + 50_000L, days(balance(boarding = 50_000), rows(board("pending")) + deposit + funding, dated, confirming))
        val confirmed = funding.copy(hasConfirmation = true, confirmationHeight = 1_000u)
        assertEquals(start + 50_000L, days(balance(boarding = 50_000), rows(board("pending")) + deposit + confirmed, dated, confirming))
        assertEquals(start + 50_000L, days(balance(spendable = 50_000), rows(board("successful")) + deposit + confirmed, dated))
        // Not its VTXO: only the funding transaction counts.
        assertEquals(listOf(WidgetWave.Change(now - 600_000, -50_500)),
            WidgetWave.changes(rows(board("pending")) + funding, emptyMap(), WidgetWave.Pending(boardVtxos = setOf("another-board"))))
    }

    /**
     * Lightning receives count by their own state (pendingLightningReceives: "preimage-revealed"), whatever order they
     * progress in; one cancelled, or claimable and then failed, is no change at all, and the earlier days never move.
     */
    @Test fun aReceiveCountsByItsOwnState() {
        fun older(status: String, sats: Long) = movement("bark.lightning_receive:receive", sats, now - 1_200_000, status, 1u)
        fun newer(status: String, sats: Long) = movement("bark.lightning_receive:receive", sats, now - 600_000, status, 2u)
        val both = rows(earlier, older("pending", 10_000), newer("pending", 20_000))
        assertEquals(before + 100_000L, days(balance(spendable = 100_000), both))
        // The newer one's preimage is out first.
        assertEquals(before + 120_000L, days(balance(spendable = 100_000, claimable = 20_000), both, pending = WidgetWave.Pending(setOf(2u))))
        assertEquals(before + 130_000L, days(balance(spendable = 100_000, claimable = 30_000), both, pending = WidgetWave.Pending(setOf(1u, 2u))))
        // The older one is cancelled instead.
        val cancelled = older("canceled", 0)
        assertEquals(before + 120_000L, days(balance(spendable = 100_000, claimable = 20_000), rows(earlier, cancelled, newer("pending", 20_000)),
            pending = WidgetWave.Pending(setOf(2u))))
        assertEquals(before + 120_000L, days(balance(spendable = 120_000), rows(earlier, cancelled, newer("successful", 20_000))))
        // A claimable one that then fails.
        assertEquals(before + 100_000L, days(balance(spendable = 100_000), rows(earlier, cancelled, newer("failed", 0))))
    }

    private val tip = object : OnchainWalletAccess { override val walletID = "x"; override suspend fun tip() = 100L }
    private fun receive(id: UInt, state: String) = LightningReceive(emptyList(), id, "hash", "invoice", 20_000uL, state, "preimage", null, null)
    private fun write(extras: WidgetDataWriter.Extras, balance: WalletBalance, previous: WidgetSnapshot? = null) =
        WidgetDataWriter.snapshot(balance, extras, display, null, emptyMap(), now, utc, previous)

    /** Yesterday's snapshot: 100,000 since ten days back. */
    private val previous get() = write(WidgetDataWriter.Extras(rows(earlier)), balance(spendable = 100_000)).copy(writtenAt = now - day)

    /**
     * A receive settles between the history read and the pending read: the history has it pending, the pending read no
     * longer claimable, so neither counts it. The balance after the reads no longer matches the one shown: the earlier
     * days stay as last written.
     */
    @Test fun aReceiveSettlingBetweenTheReadsKeepsTheEarlierDays() = runBlocking {
        val claimable = movement("bark.lightning_receive:receive", 20_000, now - 600_000, "pending", 2u)
        val shown = balance(spendable = 100_000, claimable = 20_000)
        val wallet = WidgetHandles(tip, listOf(balance(spendable = 120_000)), listOf(listOf(earlier, claimable)))
        val extras = WidgetDataWriter.extras(wallet, "regtest", shown)
        assertEquals(1, wallet.historyReads); assertEquals(1, wallet.balanceReads); assertNull(extras.pending); assertNotNull(extras.rows)
        assertEquals(previous.daily!!.drop(1).dropLast(1) + 100_000L + 120_000L, write(extras, shown, previous).daily)
        // Taken as read, the wave would have lifted every earlier day by the receive.
        assertEquals(List(20) { 20_000L } + List(10) { 120_000L } + 120_000L,
            write(extras.copy(pending = WidgetWave.Pending()), balance(spendable = 120_000)).daily)
    }

    /** A board completes between the reads: its VTXO has left pendingBoardVtxos while the history still has it pending. */
    @Test fun aBoardSettlingBetweenTheReadsKeepsTheEarlierDays() = runBlocking {
        val board = movement("bark.board:board", 50_000, now - 600_000, "pending", 6u, listOf("board-vtxo"))
        val shown = balance(spendable = 100_000, boarding = 50_000)
        val wallet = WidgetHandles(tip, listOf(balance(spendable = 150_000)), listOf(listOf(earlier, board)))
        val extras = WidgetDataWriter.extras(wallet, "regtest", shown)
        assertNull(extras.pending)
        assertEquals(previous.daily!!.drop(1).dropLast(1) + 100_000L + 150_000L, write(extras, shown, previous).daily)
    }

    /** The balance read after the records is capped: slow, it falls back at once rather than holding push work. */
    @Test fun aSlowOrFailingBalanceAfterTheReadsFallsBackWithoutBlocking() = runBlocking {
        val shown = balance(spendable = 100_000)
        val slow = WidgetHandles(tip, listOf(shown), listOf(listOf(earlier)), balanceDelay = 60_000)
        val started = System.nanoTime()
        val extras = WidgetDataWriter.extras(slow, "regtest", shown, settledRead = 100)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertNull(extras.pending); assertNotNull(extras.rows); assertEquals(100L, extras.tip)
        assertEquals(previous.daily!!.drop(1) + 100_000L, write(extras, shown, previous).daily)
        // Failing, the same; without an earlier snapshot, calm water.
        val failing = WidgetDataWriter.extras(WidgetHandles(tip, listOf(null), listOf(listOf(earlier))), "regtest", shown)
        assertNull(failing.pending)
        assertEquals(previous.daily!!.drop(1) + 100_000L, write(failing, shown, previous).daily)
        assertNull(write(failing, shown).daily)
        // A pending read that fails, or no balance shown to compare with: no balance read at all.
        val unread = WidgetHandles(tip, listOf(shown), listOf(listOf(earlier)), receives = listOf(null))
        assertNull(WidgetDataWriter.extras(unread, "regtest", shown).pending); assertEquals(0, unread.balanceReads)
        assertNull(WidgetDataWriter.extras(WidgetHandles(tip, listOf(shown), listOf(listOf(earlier))), "regtest", null).pending)
    }

    /** The balance after the reads matches the one shown: a claimable receive counts, the balance read once. */
    @Test fun aConsistentReadCountsWhatTheBalanceHolds() = runBlocking {
        val claimable = movement("bark.lightning_receive:receive", 20_000, now - 600_000, "pending", 2u)
        val shown = balance(spendable = 100_000, claimable = 20_000)
        val wallet = WidgetHandles(tip, listOf(shown), listOf(listOf(earlier, claimable)),
            receives = listOf(listOf(receive(2u, "preimage-revealed"), receive(3u, "htlcs-ready"))))
        val extras = WidgetDataWriter.extras(wallet, "regtest", shown)
        assertEquals(1, wallet.balanceReads); assertEquals(WidgetWave.Pending(setOf(2u)), extras.pending)
        assertEquals(before + 120_000L, write(extras, shown).daily)
    }
}
