package com.noahwallet.ui.home

import com.noahwallet.history.HistoryTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    // A wallet whose balance refreshes land by advancing a revision, as AppModel's do.
    private val revision = MutableStateFlow(7L)
    private var rows = emptyList<HistoryTransaction>()
    private fun settled() = (if (rows.isEmpty()) HomeRules.State.fresh else HomeRules.State.everyday) to rows
    private suspend fun visit(refresh: suspend () -> Unit = {}) =
        watch.catchUp(revision, refresh, { settled() }, firstPause = 40, longestPause = 160)

    @Test fun aVisitCatchesUpOnlyOnceARefreshLandsAfterItBegan() = runBlocking {
        val visit = async { visit() }
        repeat(5) { yield() }
        assertNull("the refresh hasn't landed yet", watch.baseline)
        revision.value++
        withTimeout(2_000) { visit.await() }
        assertEquals(FirstPaymentWatch.Baseline(fresh = true, incoming = emptySet()), watch.baseline)
    }

    @Test fun aFailedRefreshIsRetriedWithBackoffUntilOneLands() = runBlocking {
        var asked = 0
        val started = System.nanoTime()
        // The visit's own refresh failed; the next two retries fail too (offline), the third lands.
        withTimeout(5_000) { visit { asked++; if (asked == 3) revision.value++ } }
        val waited = (System.nanoTime() - started) / 1_000_000
        assertEquals(3, asked)
        assertTrue("backed off 40, 80 then 160 ms between asks: $waited ms", waited >= 40 + 80 + 160 - 20)
        assertNotNull(watch.baseline)
    }

    @Test fun backInTheForegroundTheFirstPaymentStillPlays() = runBlocking {
        // Shown on a Fresh wallet, then the app goes to the background and comes back.
        val first = async { visit() }
        yield(); revision.value++; first.await()
        watch.hidden()
        assertNull(watch.baseline)
        val back = async { visit() }
        yield()
        assertNull("not before the foreground refresh lands", watch.arrival(listOf(incoming("a")), 21_000u))
        revision.value++
        back.await()
        // The wallet's first payment lands while Home is on screen: it plays.
        assertEquals(21_000L, watch.arrival(listOf(incoming("a")), 21_000u)?.sats)
    }

    @Test fun aConfigurationChangeContinuesTheVisit() = runBlocking {
        watch.caughtUp(HomeRules.State.fresh, emptyList())
        var asked = 0
        withTimeout(1_000) { visit { asked++ } }
        assertEquals("no new baseline, no refresh asked for", 0, asked)
        assertEquals(true, watch.baseline?.fresh)
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
        // A canceled or failed receive brought nothing in.
        assertNull(watch.arrival(listOf(incoming("c").copy(movementStatus = "canceled"), incoming("f").copy(movementStatus = "failed")), 0u))
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
