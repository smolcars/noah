package com.noahwallet.ui.home

import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Date

/** Home rows read only what the record says: no invented names, monochrome signs, Noah users by domain. */
class HomeRowsTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 22, 15, 0, 0); set(Calendar.MILLISECOND, 0) }.time
    private fun row(type: String, direction: String, destination: String = "", status: String = "successful", kind: String? = null,
                    effective: Long = 1, source: String = "ark", confirmed: Boolean? = null, dateLabel: String? = null) =
        HistoryTransaction("id", type, 2_100, HistoryRules.isoDate(now), direction, dateLabel = dateLabel, source = source,
            destination = destination, movementStatus = status, movementKind = kind, effectiveBalanceSat = effective, hasConfirmation = confirmed)

    @Test fun noahUsersAreTheLocalPartOnYourOwnDomain() {
        val home = HomeRow.of(row("Lnurl", "outgoing", "Lucas@NoahWallet.io"), "noahwallet.io")
        assertEquals("lucas", home.title); assertEquals("Noah user", home.kind)
        assertEquals(HomeRow.Avatar.Initial("l"), home.avatar); assertEquals("−", home.sign)
        val other = HomeRow.of(row("Lnurl", "outgoing", "coffee@getalby.com"), "noahwallet.io")
        assertEquals("coffee@getalby.com", other.title); assertEquals("Lightning", other.kind)
        assertEquals(HomeRow.Avatar.Initial("c"), other.avatar)
    }

    @Test fun withoutACounterpartyRowsKeepTodaysLabelAndARailGlyph() {
        // An invoice without a readable description reads as what happened, on the Lightning rail.
        val bolt = HomeRow.of(row("Bolt11", "incoming", "lnbcrt1invoice", kind = "lightning-receive"), "noahwallet.io")
        assertEquals("Received", bolt.title); assertEquals("Lightning", bolt.kind)
        assertEquals(HomeRow.Avatar.Lightning, bolt.avatar); assertEquals("+", bolt.sign); assertNull(bolt.counterparty)
        val ark = HomeRow.of(row("Arkoor", "outgoing", "tark1qexample"), "noahwallet.io")
        assertEquals("Ark", ark.title); assertEquals(HomeRow.Avatar.Sent, ark.avatar)
        val received = HomeRow.of(row("Arkoor", "incoming", kind = "arkoor-receive"), null)
        assertEquals(HomeRow.Avatar.Received, received.avatar)
        // Incoming records never name a sender, so the first payment never invents a "from".
        assertNull(HomeRow.of(row("Lnurl", "incoming", "maya@noahwallet.io"), "noahwallet.io").counterparty)
    }

    @Test fun onchainRowsUseTheLinkAndInternalTransfersHaveNoSign() {
        val board = HomeRow.of(row("Onchain", "incoming", kind = "onboard"), null)
        assertEquals("Board", board.title); assertEquals("On-chain", board.kind)
        assertEquals(HomeRow.Avatar.Chain, board.avatar); assertEquals("", board.sign)
        val deposit = HomeRow.of(row("Onchain", "incoming", source = "onchain-wallet", confirmed = false, dateLabel = "Unconfirmed"), null)
        assertTrue(deposit.pending); assertEquals("Confirming", deposit.state); assertNull("no time for an unconfirmed deposit", deposit.date)
        assertEquals(HomeRow.Avatar.Chain, HomeRow.of(row("Onchain", "outgoing", kind = "exit"), null).avatar)
    }

    @Test fun pendingFailedAndCanceledRows() {
        val sending = HomeRow.of(row("Lnurl", "outgoing", "bob@getalby.com", status = "pending"), null)
        assertTrue(sending.pending); assertEquals("Sending", sending.state)
        val failed = HomeRow.of(row("Onchain", "incoming", status = "failed", kind = "onboard", effective = 0), null)
        assertTrue(failed.failed); assertEquals("Failed", failed.state)
        val canceled = HomeRow.of(row("Bolt11", "outgoing", status = "canceled", effective = 0), null)
        assertTrue(canceled.canceled); assertFalse(canceled.failed); assertEquals("Canceled", canceled.state)
        assertEquals("Sent", canceled.title)
    }

    @Test fun nothingLeftYourWalletNeedsExplicitZeroDeltas() {
        fun proof(row: HistoryTransaction) = HomeRow.of(row, null).unchanged
        // An Ark movement that recorded a zero balance change and a zero fee.
        assertTrue(proof(row("Bolt11", "outgoing", status = "canceled", effective = 0).copy(offchainFeeSat = 0)))
        // Unknown never counts as unchanged: a missing delta or fee, or a record that isn't an Ark movement.
        assertFalse(proof(row("Bolt11", "outgoing", status = "canceled", effective = 0)))
        assertFalse(proof(row("Bolt11", "outgoing", status = "canceled", effective = 0).copy(effectiveBalanceSat = null, offchainFeeSat = 0)))
        assertFalse(proof(row("Bolt11", "outgoing", status = "canceled", effective = 0, source = "onchain-wallet").copy(offchainFeeSat = 0)))
        assertFalse("a fee left", proof(row("Bolt11", "outgoing", status = "canceled", effective = 0).copy(offchainFeeSat = 3)))
        assertFalse(proof(row("Onchain", "incoming", status = "failed", kind = "onboard", effective = -400).copy(offchainFeeSat = 0)))
        // A failed board moves on-chain funds: a zero Ark change says nothing about the chain side.
        val board = row("Onchain", "incoming", status = "failed", kind = "onboard", effective = 0).copy(offchainFeeSat = 0)
        assertFalse("no chain record merged", proof(board))
        assertFalse("the merged chain record shows money moved", proof(board.copy(balanceChangeSat = -51_000)))
        assertFalse("the on-chain fee is unknown", proof(board.copy(balanceChangeSat = 0)))
        assertFalse("an on-chain fee was paid", proof(board.copy(balanceChangeSat = 0, onchainFeeSat = 150)))
        assertTrue("an explicit zero on both sides", proof(board.copy(balanceChangeSat = 0, onchainFeeSat = 0)))
        // A Lightning row carries no chain values; any it does carry must be zero.
        assertFalse(proof(row("Bolt11", "outgoing", status = "canceled", effective = 0).copy(offchainFeeSat = 0, onchainFeeSat = 20)))
        // Successful rows never carry the phrase.
        assertFalse(proof(row("Bolt11", "outgoing", effective = 0).copy(offchainFeeSat = 0)))
    }

    @Test fun onlyMoneyThatCameInCountsAsReceived() {
        assertTrue(HomeRow.received(row("Arkoor", "incoming")))
        assertFalse("canceled", HomeRow.received(row("Arkoor", "incoming", status = "canceled")))
        assertFalse("failed", HomeRow.received(row("Arkoor", "incoming", status = "failed")))
        assertFalse("outgoing", HomeRow.received(row("Arkoor", "outgoing")))
        assertFalse("own transfer", HomeRow.received(row("Onchain", "incoming", kind = "onboard")))
        assertFalse("nothing arrived", HomeRow.received(row("Arkoor", "incoming").copy(amount = 0)))
    }

    @Test fun daysGroupTodayYesterdayAndDatesKeepingUndatedRowsInPlace() {
        val hour = 3_600_000L
        val rows = listOf<Date?>(null, Date(now.time - hour), Date(now.time - 20 * hour), null, Date(now.time - 24 * 10 * hour))
        val groups = HomeDays.grouped(rows, { it }, now)
        assertEquals(listOf(HomeDays.Day.Today, HomeDays.Day.Yesterday), groups.take(2).map { it.first })
        assertEquals(2, groups[0].second.size)
        assertEquals("the undated row joins the group above it", 2, groups[1].second.size)
        assertTrue(groups[2].first is HomeDays.Day.On)
        assertEquals(3, groups.size)
    }
}
