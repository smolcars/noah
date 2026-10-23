package com.noahwallet.ui.send

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Send's automatic refresh: entering Send and the visible-screen loop share one pace of at most every 30 s. */
class SendRefreshPacingTest {
    @Test fun automaticRefreshesRunAtMostEveryThirtySeconds() = runBlocking {
        var now = 1_000L
        var runs = 0
        val pacing = SendRefresh(clock = { now })
        assertEquals(0L, pacing.wait())
        pacing.ifDue { runs++ }
        assertEquals("entering Send refreshes", 1, runs)
        now += 5_000
        pacing.ifDue { runs++ }
        assertEquals("leaving and returning 5 s later doesn't", 1, runs)
        assertEquals("the loop sleeps the rest of the 30 s", 25_000L, pacing.wait())
        now += 25_000
        pacing.ifDue { runs++ }
        assertEquals(2, runs)
        assertEquals(30_000L, pacing.wait())
        now += 31_000
        assertEquals(0L, pacing.wait())
    }
}
