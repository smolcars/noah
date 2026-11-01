package com.noahwallet.services

import com.noahwallet.services.logging.LaunchTiming
import com.noahwallet.services.logging.PassTiming
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The launch's timing lines in the app log, which show how long Home's history takes to appear. */
class LaunchTimingTest {
    @Test fun eachMilestoneIsLoggedOnceInMillisecondsSinceTheProcessStarted() {
        val lines = mutableListOf<String>()
        var now = 250L
        val timing = LaunchTiming(lines::add) { now }
        timing.mark(LaunchTiming.Milestone.walletOpened)
        now = 400; timing.mark(LaunchTiming.Milestone.summaryShown)
        now = 9000; timing.mark(LaunchTiming.Milestone.summaryShown)
        now = 1500; timing.mark(LaunchTiming.Milestone.historyShown, " (3 rows)")
        assertEquals(listOf("Wallet open done at +250 ms", "First summary shown at +400 ms", "First history rows shown at +1500 ms (3 rows)"), lines)
    }

    @Test fun aPassLogsEachStageStartingAndEnding() = runBlocking {
        val lines = mutableListOf<String>()
        var nanos = 0L
        val timing = PassTiming(lines::add) { nanos }
        timing.run("wallet sync") { nanos = 1_200_000_000 }
        assertTrue(runCatching { timing.run("on-chain sync") { nanos = 4_500_000_000; error("offline") } }.isFailure)
        assertEquals(listOf("Foreground pass: wallet sync started at +0 ms", "Foreground pass: wallet sync done at +1200 ms",
            "Foreground pass: on-chain sync started at +1200 ms", "Foreground pass: on-chain sync failed at +4500 ms"), lines)
    }
}
