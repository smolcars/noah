package com.noahwallet.ui.activity

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.noahwallet.ui.NoahTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Closing transaction detail on a phone keeps unshared row text (rail/time and non-matching titles) anchored in the
 * list instead of flying back from the detail container, while a matching status title ("Received") glides from the
 * detail header back to its row position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp", application = Application::class)
class ActivityTransitionBoundsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: ActivityState

    private fun stepFrames(count: Int) {
        repeat(count) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame(); ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
        }
    }
    private fun settle(ms: Long = 1_500) {
        stepFrames((ms / 16).toInt())
        compose.waitForIdle()
    }
    private fun show(ui: ActivityUi = ActivityFixtures.ui("everyday")) {
        compose.mainClock.autoAdvance = false
        compose.setContent { NoahTheme { state = rememberActivityState(); ActivityPreviewHost(ui, state) } }
        settle()
    }
    private fun nodeInRow(text: String, rowBounds: androidx.compose.ui.geometry.Rect, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes()
            .single { it.boundsInRoot.top >= rowBounds.top - 1f && it.boundsInRoot.bottom <= rowBounds.bottom + 1f }

    @Test fun closingPlainReceiveKeepsSubtitleStationaryAndTransitionsReceivedFromDetailStatus() {
        val base = ActivityFixtures.ui("everyday")
        val receive = base.records.single { it.id == "movement-15" }
        // Place the plain Ark receive in the visible top section (away from the list's scroll bottom).
        val reordered = base.copy(records = base.records.filterNot { it.id == "movement-15" }.toMutableList().apply { add(3, receive) })
        show(reordered)
        val restRowBounds = compose.onNodeWithTag("activity-row-movement-15", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val restSubtitle = nodeInRow("Ark ·", restRowBounds, substring = true).boundsInRoot
        val restTitle = nodeInRow("Received", restRowBounds).boundsInRoot

        compose.onNodeWithTag("activity-row-movement-15").performClick()
        settle()
        val detailStatus = compose.onNodeWithTag("activity-detail-status", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("detail status sits away from the list row", abs(detailStatus.top - restTitle.top) > 20f)

        compose.onNodeWithTag("activity-detail-back").performClick()
        stepFrames(4)
        val midRowBounds = compose.onNodeWithTag("activity-row-movement-15", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("row stays at its list height", restRowBounds.height, midRowBounds.height, 1f)
        assertEquals("row stays at its list Y", restRowBounds.top, midRowBounds.top, 1f)
        val midSubtitle = nodeInRow("Ark ·", midRowBounds, substring = true).boundsInRoot
        assertEquals("rail/time subtitle never drifts during close", restSubtitle.top, midSubtitle.top, 1f)
        assertEquals("rail/time subtitle keeps its X", restSubtitle.left, midSubtitle.left, 1f)
        val midStatus = compose.onNodeWithTag("activity-detail-status", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("matching Received label moves from detail status toward row title", abs(midStatus.top - detailStatus.top) > 5f)
    }

    @Test fun closingNamedPaymentKeepsBothTitleAndSubtitleStationary() {
        show()
        compose.onNodeWithTag("activity-list").performScrollToKey("movement-3")
        settle(300)
        val restRowBounds = compose.onNodeWithTag("activity-row-movement-3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val restTitle = nodeInRow("Coffee Collective", restRowBounds).boundsInRoot
        val restSubtitle = nodeInRow("Lightning ·", restRowBounds, substring = true).boundsInRoot

        compose.onNodeWithTag("activity-row-movement-3").performClick()
        settle()
        compose.onNodeWithTag("activity-detail-back").performClick()
        stepFrames(4)

        val midRowBounds = compose.onNodeWithTag("activity-row-movement-3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val midTitle = nodeInRow("Coffee Collective", midRowBounds).boundsInRoot
        val midSubtitle = nodeInRow("Lightning ·", midRowBounds, substring = true).boundsInRoot
        assertEquals("non-matching title stays anchored in the list", restTitle.top, midTitle.top, 1f)
        assertEquals("subtitle stays anchored in the list", restSubtitle.top, midSubtitle.top, 1f)
    }
}
