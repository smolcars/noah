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

    private fun deposit(id: String, sats: Long, confirmed: Boolean = false) = HistoryTransaction("onchain-wallet-$id", "Onchain", sats,
        "2026-10-22T10:00:00Z", "incoming", source = "onchain-wallet", txid = id, txHex = "hex-$id", balanceChangeSat = sats, hasConfirmation = confirmed)
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
}
