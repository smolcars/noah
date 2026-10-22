package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.ui.home.FirstPaymentVisits
import com.noahwallet.ui.home.FirstPaymentWatch
import com.noahwallet.ui.home.HomeRules
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The first payment's baseline follows real foreground visits: going to the background resets it, coming back
 * takes a new one once the app's refresh lands, so a Fresh wallet's first payment still plays; a configuration
 * change keeps it.
 */
class FirstPaymentVisitsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val watch = FirstPaymentWatch()
    private var revision by mutableLongStateOf(0L)
    private var rows by mutableStateOf(emptyList<HistoryTransaction>())
    @Volatile private var arrival: FirstPaymentWatch.Arrival? = null

    private fun home() = compose.setContent {
        FirstPaymentVisits(watch, active = true, revision = { revision }, refresh = { revision++ },
            settled = { (if (rows.isEmpty()) HomeRules.State.fresh else HomeRules.State.everyday) to rows })
        // The app refreshes the balance each time it's started, landing a moment later.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { delay(100); revision++ } }
        arrival = watch.arrival(rows, rows.sumOf { it.amount }.toULong())
    }

    @Test fun backFromTheBackgroundAFreshWalletsFirstPaymentStillPlays() {
        home()
        compose.waitUntil(5_000) { watch.baseline?.fresh == true }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertNull("the background ends the visit", watch.baseline)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // Well inside the first retry: the app's own foreground refresh is what this visit catches up on.
        compose.waitUntil(3_000) { watch.baseline?.fresh == true }
        rows = listOf(HistoryTransaction("first", "Arkoor", 21_000, "2026-10-22T10:00:00.000Z", "incoming", source = "ark",
            movementStatus = "successful", movementKind = "arkoor-receive"))
        compose.waitUntil(3_000) { arrival != null }
        assertEquals(21_000L, arrival!!.sats)
        assertEquals("first", arrival!!.row?.id)
    }

    @Test fun aConfigurationChangeKeepsTheVisitsBaseline() {
        home()
        compose.waitUntil(5_000) { watch.baseline?.fresh == true }
        val before = watch.baseline
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals("a configuration change continues the visit", before, watch.baseline)
    }
}
