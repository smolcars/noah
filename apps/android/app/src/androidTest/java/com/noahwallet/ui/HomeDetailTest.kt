package com.noahwallet.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.home.*
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * A Home row opens Activity's detail in Home's sheet for every kind of payment, and closing it returns to Home.
 * Runs on any pose: a phone's sheet, or the sheet kept inside the trailing pane.
 */
class HomeDetailTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Case(val record: HistoryTransaction, val status: String, val shows: String? = null)

    private val records = ActivityFixtures.records("busy")
    private fun movement(id: Int) = records.single { it.movementId == id.toUInt() }
    private val cases = listOf(
        Case(movement(3), "Sent", "Proof of payment"),
        Case(records.single { it.source == "onchain-wallet" && it.hasConfirmation != true }, "Arriving", "Waiting for block"),
        Case(movement(103), "Sending · waiting for confirmations", "Confirmed in block"),
        Case(movement(201), "Failed · nothing left your wallet"),
        Case(movement(202), "Refunded · back in your wallet"),
        Case(movement(6), "Sent", "Repeats"),
    ) + renewalStates.map { (record, status) -> Case(record, status) }

    private lateinit var state: ActivityState
    private var index by mutableIntStateOf(0)

    // The sheet's dialog attaches on real frames; its motion (and the block row's blink) runs on the test clock.
    private fun settle() { compose.mainClock.advanceTimeBy(500); Thread.sleep(700); compose.mainClock.advanceTimeBy(1_500) }
    private fun row() = compose.onNode(hasClickAction() and !hasText("See all") and
        hasAnyAncestor(hasTestTag("home-recent") or hasTestTag("home-activity")))

    @Test fun everyKindOfRowOpensActivitysDetail() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NoahTheme {
                key(index) {
                    val record = cases[index].record
                    state = rememberActivityState()
                    HomePreviewHost(HomeFixtures.ui("everyday").copy(rows = listOf(record)), actions = HomeActions(openRow = state::open)) {
                        HomePaymentSheet(ActivityFixtures.ui("busy").copy(records = listOf(record)), state, ActivityActions())
                    }
                }
            }
        }
        for (i in cases.indices) {
            val case = cases[i]
            compose.runOnUiThread { index = i }
            settle()
            row().performClick()
            settle()
            compose.onNodeWithTag("activity-detail-status", useUnmergedTree = true).assert(hasAnyDescendant(hasText(case.status)))
            case.shows?.let { compose.onNodeWithText(it, substring = true, useUnmergedTree = true).assertExists() }
            // × closes the sheet; the last payment closes with Back instead.
            if (i < cases.lastIndex) compose.onNodeWithTag("activity-detail-close").performClick() else InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settle()
            compose.onAllNodesWithTag("activity-detail").assertCountEquals(0)
            assertNull(state.selected)
            row().assertIsDisplayed()
        }
    }
}
