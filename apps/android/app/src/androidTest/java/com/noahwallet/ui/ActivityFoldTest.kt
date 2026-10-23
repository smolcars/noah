package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.noahwallet.history.HistoryFilter
import com.noahwallet.ui.activity.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Activity takes the whole window and splits it at a separating fold itself. Synthetic folds make this independent
 * of the device's posture: nothing may straddle the hinge; in book pose the list (with the rail) leads and the
 * detail trails, in tabletop the list sits above and the detail below; and changing pose keeps the selection,
 * search, filter, list position and Details.
 */
class ActivityFoldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var fold by mutableStateOf<WindowRegion?>(null)
    private lateinit var state: ActivityState

    private fun show(setup: ActivityState.() -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NoahTheme {
                CompositionLocalProvider(LocalFoldingFeatures provides listOfNotNull(fold)) {
                    state = rememberActivityState()
                    remember { state.setup() }
                    Box(Modifier.fillMaxSize()) { ActivityPreviewHost(ActivityFixtures.ui("busy"), state) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_500)
    }
    private fun pose(hinge: ((Int, Int) -> WindowRegion)?) {
        val view = compose.activity.window.decorView
        compose.runOnUiThread { fold = hinge?.invoke(view.width, view.height) }
        compose.mainClock.advanceTimeBy(2_500)
    }
    private val book = { width: Int, height: Int -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
    private val tabletop = { width: Int, height: Int -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { all += node; node.children.forEach(::walk) }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return all.filter { it.config.getOrNull(SemanticsProperties.Text) != null || it.config.getOrNull(SemanticsActions.OnClick) != null }
    }
    private fun assertClear(label: String, region: WindowRegion) {
        val vertical = region.height > region.width
        for (node in nodes()) {
            val bounds = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Close sheet") == true) continue
            val what = "$label: ${node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()} $bounds $region"
            if (vertical) assertFalse("crosses the vertical fold: $what", bounds.left < region.left && bounds.right > region.right)
            else assertFalse("crosses the horizontal fold: $what", bounds.top < region.top && bounds.bottom > region.bottom)
        }
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

    @Test fun bookPoseHasTheListBesideTheRailAndTheDetailAcrossTheHinge() {
        show { open("movement-4"); details = true }
        pose(book)
        val region = fold!!
        assertClear("book", region)
        assertTrue("list leads", bounds("activity-list-pane").right <= region.left)
        assertTrue("detail trails", bounds("activity-detail-pane").left >= region.right)
        compose.onNodeWithText("Pizza night").assertExists()
        compose.onNodeWithTag("activity-details").assertExists()
        // The export sheet stays in the detail's pane.
        compose.runOnUiThread { state.exporting = true }
        compose.mainClock.advanceTimeBy(500); Thread.sleep(700); compose.mainClock.advanceTimeBy(1_500)
        assertClear("book export", region)
        assertTrue(bounds("activity-export-sheet").left >= region.right)
    }

    @Test fun tabletopPutsTheListAboveAndTheDetailBelow() {
        show { open("movement-103") }
        pose(tabletop)
        val region = fold!!
        assertClear("tabletop", region)
        assertTrue("list above", bounds("activity-list-pane").bottom <= region.top)
        assertTrue("detail below", bounds("activity-detail-pane").top >= region.bottom)
        compose.onNodeWithTag("activity-block-row").assertExists()
    }

    @Test fun poseChangesKeepEverything() {
        show { filter = HistoryFilter.Lightning; searching = true; query = "bob"; open("movement-4"); details = true }
        val before = state.shownList.firstVisibleItemIndex to state.shownList.firstVisibleItemScrollOffset
        for (next in listOf(book, tabletop, null, book)) {
            pose(next)
            assertEquals("movement-4", state.selected)
            assertEquals(HistoryFilter.Lightning, state.filter)
            assertEquals("bob", state.query); assertTrue(state.searching)
            assertTrue(state.details)
        }
        assertEquals(before.first, state.shownList.firstVisibleItemIndex)
        compose.onNodeWithTag("activity-details").assertExists()
    }
}
