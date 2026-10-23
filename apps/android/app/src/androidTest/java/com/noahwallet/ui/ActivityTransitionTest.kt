package com.noahwallet.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.noahwallet.ui.activity.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The phone's row → detail push and the way back: the tab bar hides while a payment is open, Back returns to the
 * list, and the back gesture previews the reverse transition (cancelling keeps the payment open). Run on a phone.
 */
class ActivityTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: ActivityState
    private var barHidden = false
    private val seen = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            NoahTheme {
                state = rememberActivityState()
                ActivityPreviewHost(ActivityFixtures.ui("busy"), state, ActivityActions(barHidden = { barHidden = it }, seen = { seen += it }))
            }
        }
        compose.waitForIdle()
    }
    private fun dispatcher() = compose.activity.onBackPressedDispatcher
    private fun edge(progress: Float) = BackEventCompat(0f, 800f, progress, BackEventCompat.EDGE_LEFT)

    @Test fun aRowOpensItsDetailAndBackReturnsToTheList() {
        show()
        assumePhone()
        compose.onNodeWithTag("activity-list").performScrollToKey("movement-4")
        compose.onNodeWithTag("activity-row-movement-4").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("activity-detail").assertIsDisplayed()
        compose.onNodeWithText("Pizza night").assertIsDisplayed()
        assertTrue("the tab bar hides while a payment is open", barHidden)
        assertEquals("movement-4", state.selected)
        assertTrue("opening a row counts as seeing it", "movement-4" in seen)
        compose.onNodeWithTag("activity-detail-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("activity-list").assertIsDisplayed()
        assertNull(state.selected)
        assertFalse(barHidden)
    }

    @Test fun theBackGesturePreviewsTheWayBackAndCanBeCancelled() {
        show()
        assumePhone()
        compose.onNodeWithTag("activity-list").performScrollToKey("movement-3")
        compose.onNodeWithTag("activity-row-movement-3").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { dispatcher().dispatchOnBackStarted(edge(0f)); dispatcher().dispatchOnBackProgressed(edge(.5f)) }
        compose.mainClock.advanceTimeBy(100)
        // Mid-gesture, both screens are composed: the list coming back and the detail shrinking into its row.
        assertTrue(compose.onAllNodesWithTag("activity-list").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithTag("activity-detail").fetchSemanticsNodes().isNotEmpty())
        compose.runOnUiThread { dispatcher().dispatchOnBackCancelled() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("cancelling keeps the payment open", "movement-3", state.selected)
        compose.onNodeWithTag("activity-detail").assertIsDisplayed()
        // Committing the gesture closes it.
        compose.runOnUiThread { dispatcher().dispatchOnBackStarted(edge(0f)); dispatcher().dispatchOnBackProgressed(edge(.7f)); dispatcher().onBackPressed() }
        compose.waitForIdle()
        assertNull(state.selected)
        compose.onNodeWithTag("activity-list").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("activity-detail").fetchSemanticsNodes().isEmpty())
    }

    @Test fun searchAndFilterSurviveOpeningAPayment() {
        show()
        assumePhone()
        compose.runOnUiThread { state.filter = com.noahwallet.history.HistoryFilter.Lightning; state.searching = true; state.query = "bob" }
        compose.waitForIdle()
        compose.onNodeWithTag("activity-row-movement-4").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("activity-detail-back").performClick()
        compose.waitForIdle()
        assertEquals("bob", state.query)
        assertEquals(com.noahwallet.history.HistoryFilter.Lightning, state.filter)
        compose.onNodeWithTag("activity-search-field").assertIsDisplayed()
    }

    @Test fun eachHistoryReadReReadsTheOpenPaymentsChainStatus() {
        val reads = mutableListOf<String>()
        var ui by mutableStateOf(ActivityFixtures.ui("busy"))
        compose.setContent {
            NoahTheme {
                state = rememberActivityState()
                remember { state.open("movement-103") }
                ActivityPreviewHost(ui, state, ActivityActions(shown = { reads += it.id }))
            }
        }
        compose.waitForIdle()
        assertEquals(listOf("movement-103"), reads)
        // The pending offboard's record is unchanged at its first confirmation; the next history read still re-reads.
        compose.runOnUiThread { ui = ui.copy(revision = ui.revision + 1) }
        compose.waitForIdle()
        assertEquals(listOf("movement-103", "movement-103"), reads)
    }

    private fun assumePhone() = org.junit.Assume.assumeTrue("phone layout only",
        compose.onAllNodesWithTag("activity-detail-pane").fetchSemanticsNodes().isEmpty())
}
