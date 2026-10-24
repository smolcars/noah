package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.ui.activity.*
import org.junit.Rule
import org.junit.Test

/** A renewal in every state it can be in, with its status line. */
internal val renewalStates = ActivityFixtures.records("busy").first { it.isRenewal }.let { renewal ->
    listOf(
        renewal to "Renewed 3 coins",
        renewal.copy(movementStatus = "pending") to "Sending",
        renewal.copy(movementStatus = "failed") to "Failed",
        renewal.copy(movementStatus = "failed", effectiveBalanceSat = 0, offchainFeeSat = 0) to "Failed · nothing left your wallet",
        renewal.copy(movementStatus = "canceled") to "Canceled",
        renewal.copy(movementStatus = "canceled", effectiveBalanceSat = 0, offchainFeeSat = 0) to "Canceled · nothing left your wallet",
        renewal.copy(movementStatus = null) to "Recorded",
    )
}

/**
 * Every state a renewal can be in opens in Activity's detail (Home's rows open it too: [HomeDetailTest]). Only a
 * settled renewal counts its coins: the pending, failed, canceled and recorded lines have no plural resource.
 */
class RenewalDetailTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun everyRenewalStateOpensInActivitysDetail() {
        var index by mutableIntStateOf(0)
        compose.setContent {
            NoahTheme {
                key(index) {
                    val record: HistoryTransaction = renewalStates[index].first
                    val state = rememberActivityState()
                    remember { state.open(record.id) }
                    ActivityPreviewHost(ActivityFixtures.ui("busy").copy(records = listOf(record)), state)
                }
            }
        }
        for (i in renewalStates.indices) {
            compose.runOnUiThread { index = i }
            compose.waitForIdle()
            compose.onNodeWithTag("activity-detail-status", useUnmergedTree = true).assert(hasAnyDescendant(hasText(renewalStates[i].second)))
        }
    }
}
