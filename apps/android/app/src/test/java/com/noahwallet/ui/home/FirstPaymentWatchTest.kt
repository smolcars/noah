package com.noahwallet.ui.home

import com.noahwallet.history.HistoryTransaction
import org.junit.Assert.*
import org.junit.Test

/** The first payment celebrates only funds that arrive after this visit's caught-up baseline, never a catch-up. */
class FirstPaymentWatchTest {
    private fun incoming(id: String, amount: Long = 21_000) = HistoryTransaction(id, "Arkoor", amount, "2026-10-22T10:00:00.000Z", "incoming",
        source = "ark", movementStatus = "successful", movementKind = "arkoor-receive")
    private val watch = FirstPaymentWatch()

    @Test fun aPaymentFoundByTheVisitsRefreshIsACatchUpNotACelebration() {
        // Home shows a cached Fresh; the payment arrived while it was away. The refresh finds it, then the baseline is taken.
        assertNull("nothing plays before the visit has caught up", watch.arrival(listOf(incoming("a")), 21_000u))
        watch.caughtUp(HomeRules.State.everyday, listOf(incoming("a")))
        assertNull(watch.arrival(listOf(incoming("a")), 21_000u))
        // Later payments in this visit aren't a first payment either.
        assertNull(watch.arrival(listOf(incoming("b"), incoming("a")), 42_000u))
    }

    @Test fun aRealPaymentRightAfterTheBaselinePlaysOnce() {
        watch.caughtUp(HomeRules.State.fresh, emptyList())
        assertNull(watch.arrival(emptyList(), 0u))
        // However soon after the baseline it lands, it plays; there's no settling delay to skip it.
        val arrival = watch.arrival(listOf(incoming("a", 5_000)), 0u)!!
        assertEquals("a", arrival.row!!.id)
        assertEquals(5_000L, arrival.sats)
        watch.played()
        assertNull("once per visit", watch.arrival(listOf(incoming("b"), incoming("a", 5_000)), 26_000u))
    }

    @Test fun theBalanceRisingCountsWhenHistoryHasNoRowYet() {
        watch.caughtUp(HomeRules.State.fresh, emptyList())
        assertEquals(FirstPaymentWatch.Arrival(null, 21_000), watch.arrival(emptyList(), 21_000u))
    }

    @Test fun outgoingAndInternalRowsAndLaterBaselinesDontPlay() {
        watch.caughtUp(HomeRules.State.fresh, emptyList())
        val board = HistoryTransaction("board", "Onchain", 50_000, "2026-10-22T10:00:00.000Z", "incoming", source = "ark", movementKind = "onboard")
        val sent = HistoryTransaction("sent", "Lnurl", 1_000, "2026-10-22T10:00:00.000Z", "outgoing", source = "ark")
        assertNull(watch.arrival(listOf(board, sent), 0u))
        // The first refresh of a visit sets the baseline; later ones don't move it.
        watch.caughtUp(HomeRules.State.everyday, listOf(incoming("x")))
        assertEquals(true, watch.baseline!!.fresh)
    }

    @Test fun aRealHideStartsTheNextVisitWithoutABaseline() {
        watch.caughtUp(HomeRules.State.fresh, emptyList())
        watch.hidden()
        assertNull(watch.baseline)
        // Away, a payment arrived; the next visit's refresh finds it and opens Everyday.
        assertNull(watch.arrival(listOf(incoming("a")), 21_000u))
        watch.caughtUp(HomeRules.State.everyday, listOf(incoming("a")))
        assertNull(watch.arrival(listOf(incoming("a")), 21_000u))
    }
}
