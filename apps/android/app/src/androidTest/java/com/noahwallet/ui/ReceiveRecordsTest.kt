package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.ui.receive.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Receive's arrivals from the wallet's records, run as the screen runs them, on made-up history (never a real wallet). */
class ReceiveRecordsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun deposit(id: String, sats: Long, confirmed: Boolean = false, spending: String? = null) = HistoryTransaction("onchain-wallet-$id", "Onchain", sats,
        "2026-10-22T10:00:00Z", "incoming", source = "onchain-wallet", txid = id, txHex = spending?.let { tx(it, sats) } ?: "hex-$id",
        balanceChangeSat = sats, hasConfirmation = confirmed)
    /** A minimal one-input, one-output transaction spending output 0 of [coin] (a txid in hex). */
    private fun tx(coin: String, sats: Long): String {
        fun le(value: Long, bytes: Int) = (0 until bytes).joinToString("") { "%02x".format((value shr (8 * it)) and 0xff) }
        return "02000000" + "01" + coin.chunked(2).reversed().joinToString("") + le(0, 4) + "00" + "fdffffff" +
            "01" + le(sats, 8) + "16" + "0014" + "11".repeat(20) + "00000000"
    }
    private val coin = "ab".repeat(32)
    private val otherCoin = "cd".repeat(32)
    private fun addressPayment(id: String, sats: Long) = HistoryTransaction(id, "Bolt11", sats, "2026-10-22T10:00:00Z", "incoming",
        movementStatus = "successful", movementKind = "lightning-receive", metadataJson = """{"payment_hash":"elsewhere"}""")

    @Test fun aDepositAndAnAddressPaymentInOneUpdateBothPlay() {
        val records = mutableStateOf<ReceiveRecords?>(null)
        val arrival = mutableStateOf<ReceiveArrival?>(null)
        val chain = mutableStateOf<ReceiveArrival?>(null)
        var rows by mutableStateOf(listOf(deposit("old", 5_000, confirmed = true)))
        var payments = 0
        compose.setContent { ReceiveRecordsWatcher(rows, false, ReceiveFixtures.plain, emptySet(), emptySet(), records, arrival, chain) { payments++ } }
        compose.waitForIdle()
        assertNotNull(records.value); assertNull(arrival.value)
        // One history update brings a deposit and a payment to your address.
        compose.runOnIdle { rows = rows + deposit("new", 20_000) + addressPayment("ln", 2_000) }
        compose.waitForIdle()
        assertEquals(ReceiveArrival.Rail.onchain, arrival.value?.rail)
        assertEquals("new", chain.value?.txid)
        assertEquals(0, payments)
        // The deposit's presentation ends, as Receive's timeline ends it, with nothing else changing: the payment plays next.
        compose.runOnIdle { arrival.value = null }
        compose.waitForIdle()
        assertEquals(ReceiveArrival.Rail.lightning, arrival.value?.rail)
        assertEquals(2_000L, arrival.value?.sats)
        assertEquals(1, payments)
        assertEquals("the block row still follows the deposit", "new", chain.value?.txid)
        // Then nothing is left waiting.
        compose.runOnIdle { arrival.value = null }
        compose.waitForIdle()
        assertNull(arrival.value)
        assertEquals(1, payments)
    }

    @Test fun aConfirmedReplacementTakesOverTheBlockRow() {
        val records = mutableStateOf<ReceiveRecords?>(null)
        val arrival = mutableStateOf<ReceiveArrival?>(null)
        val chain = mutableStateOf<ReceiveArrival?>(null)
        val old = deposit("old", 5_000, confirmed = true)
        var rows by mutableStateOf(listOf(old))
        compose.setContent { ReceiveRecordsWatcher(rows, false, ReceiveFixtures.plain, emptySet(), emptySet(), records, arrival, chain) }
        compose.waitForIdle()
        compose.runOnIdle { rows = listOf(old, deposit("a", 20_000, spending = coin)) }
        compose.waitForIdle()
        assertEquals("a", chain.value?.txid)
        assertEquals(listOf("$coin:0"), chain.value?.inputs)
        compose.runOnIdle { arrival.value = null }
        compose.waitForIdle()
        // The sender replaces it (the replacement spends the same coin) and it's mined before the next refresh.
        val replacement = deposit("b", 19_800, confirmed = true, spending = coin)
        compose.runOnIdle { rows = listOf(old, replacement) }
        compose.waitForIdle()
        assertEquals("the block row follows the replacement", "b", chain.value?.txid)
        assertNull("the same deposit isn't announced again", arrival.value)
        assertTrue(replacement.id in checkNotNull(records.value).known)
        // A deposit that just disappears ends the wait.
        compose.runOnIdle { rows = listOf(old) }
        compose.waitForIdle()
        assertNull(chain.value)
        assertNull(arrival.value)
    }

    @Test fun aDepositReplacedAfterALaterOneIsNotAnnouncedAgain() {
        val records = mutableStateOf<ReceiveRecords?>(null)
        val arrival = mutableStateOf<ReceiveArrival?>(null)
        val chain = mutableStateOf<ReceiveArrival?>(null)
        val old = deposit("old", 5_000, confirmed = true)
        var rows by mutableStateOf(listOf(old))
        compose.setContent { ReceiveRecordsWatcher(rows, false, ReceiveFixtures.plain, emptySet(), emptySet(), records, arrival, chain) }
        compose.waitForIdle()
        // A is announced and its card finishes; then B (another coin) is announced and the block row moves on to it.
        val a = deposit("a", 20_000, spending = coin)
        compose.runOnIdle { rows = listOf(old, a) }
        compose.waitForIdle()
        assertEquals("a", arrival.value?.txid)
        compose.runOnIdle { arrival.value = null }
        val b = deposit("b", 7_000, spending = otherCoin)
        compose.runOnIdle { rows = listOf(old, a, b) }
        compose.waitForIdle()
        assertEquals("b", arrival.value?.txid)
        assertEquals("b", chain.value?.txid)
        compose.runOnIdle { arrival.value = null }
        compose.waitForIdle()
        // The sender replaces A with an unconfirmed A′ spending A's coin, then replaces it again.
        for (replacement in listOf(deposit("a2", 19_800, spending = coin), deposit("a3", 19_600, spending = coin))) {
            compose.runOnIdle { rows = listOf(old, replacement, b) }
            compose.waitForIdle()
            assertNull("${replacement.txid} is A again, not a new deposit", arrival.value)
            assertEquals("the block row stays on B", "b", chain.value?.txid)
            assertTrue(replacement.id in checkNotNull(records.value).known)
        }
    }

    @Test fun anUnrelatedDepositAfterTheFollowedOneDisappearsIsStillAnnounced() {
        val records = mutableStateOf<ReceiveRecords?>(null)
        val arrival = mutableStateOf<ReceiveArrival?>(null)
        val chain = mutableStateOf<ReceiveArrival?>(null)
        val old = deposit("old", 5_000, confirmed = true)
        var rows by mutableStateOf(listOf(old))
        compose.setContent { ReceiveRecordsWatcher(rows, false, ReceiveFixtures.plain, emptySet(), emptySet(), records, arrival, chain) }
        compose.waitForIdle()
        compose.runOnIdle { rows = listOf(old, deposit("a", 20_000, spending = coin)) }
        compose.waitForIdle()
        compose.runOnIdle { arrival.value = null }
        compose.waitForIdle()
        // A leaves the records, and a new deposit spending a different coin arrives (to the same address, say).
        compose.runOnIdle { rows = listOf(old, deposit("c", 7_000, spending = otherCoin)) }
        compose.waitForIdle()
        assertEquals("it's announced as its own arrival", "c", arrival.value?.txid)
        assertEquals(ReceiveArrival.Rail.onchain, arrival.value?.rail)
        assertEquals(7_000L, arrival.value?.sats)
        assertEquals("and the block row follows it, not A", "c", chain.value?.txid)
    }
}
