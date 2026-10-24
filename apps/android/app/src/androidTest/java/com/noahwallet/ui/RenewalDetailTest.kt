package com.noahwallet.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.ui.activity.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Every state a renewal can be in opens in Home's payment sheet and in Activity's detail. Only a settled renewal
 * counts its coins: the pending, failed, canceled and recorded lines have no plural resource.
 */
class RenewalDetailTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val renewal = ActivityFixtures.records("busy").first { it.isRenewal }
    private val states = listOf(
        renewal to "Renewed 3 coins",
        renewal.copy(movementStatus = "pending") to "Sending",
        renewal.copy(movementStatus = "failed") to "Failed",
        renewal.copy(movementStatus = "failed", effectiveBalanceSat = 0, offchainFeeSat = 0) to "Failed · nothing left your wallet",
        renewal.copy(movementStatus = "canceled") to "Canceled",
        renewal.copy(movementStatus = "canceled", effectiveBalanceSat = 0, offchainFeeSat = 0) to "Canceled · nothing left your wallet",
        renewal.copy(movementStatus = null) to "Recorded",
    )

    @Test fun everyRenewalStateOpensInHomesPaymentSheet() {
        val app = AppModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        var record by mutableStateOf(states.first().first)
        compose.setContent { NoahTheme { TransactionDetail(app, record, {}, {}) } }
        for ((state, status) in states) {
            compose.runOnUiThread { record = state }
            compose.waitForIdle()
            assertTrue("${state.movementStatus}: $status", compose.onAllNodesWithText(status).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test fun everyRenewalStateOpensInActivitysDetail() {
        var index by mutableIntStateOf(0)
        compose.setContent {
            NoahTheme {
                key(index) {
                    val record: HistoryTransaction = states[index].first
                    val state = rememberActivityState()
                    remember { state.open(record.id) }
                    ActivityPreviewHost(ActivityFixtures.ui("busy").copy(records = listOf(record)), state)
                }
            }
        }
        for (i in states.indices) {
            compose.runOnUiThread { index = i }
            compose.waitForIdle()
            compose.onNodeWithTag("activity-detail-status", useUnmergedTree = true).assert(hasAnyDescendant(hasText(states[i].second)))
        }
    }
}
