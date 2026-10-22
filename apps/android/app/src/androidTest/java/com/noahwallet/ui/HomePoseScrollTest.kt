package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.ui.home.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.Date

/**
 * Folding, unfolding and bending keep Home's scroll position, across real transitions: closed → open → book →
 * tabletop and back. Each step recreates Home (as a pose change recreates the activity) at the new size and fold;
 * the activity row at the top stays at the top, between one column and two panes too. Needs a large window.
 */
class HomePoseScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Posture(val size: DpSize) {
        Closed(DpSize(400.dp, 420.dp)), Open(DpSize(820.dp, 660.dp)), Book(DpSize(820.dp, 660.dp)), Tabletop(DpSize(660.dp, 660.dp))
    }

    // Twelve payments to distinct people, a few a day, so Recent and the grouped list both show distinct titles.
    private val now = Date()
    private val rows = List(12) { i ->
        val at = Date(now.time - (i / 3) * 86_400_000L - i * 60_000L)
        HistoryTransaction("p-$i", "Lnurl", 1_000L + i, HistoryRules.isoDate(at), "outgoing", sortTimestamp = at.time.toDouble(),
            source = "ark", destination = "payee$i@example.com", movementStatus = "successful", effectiveBalanceSat = -1_000L - i, offchainFeeSat = 0)
    }
    private val ui = HomeFixtures.ui("everyday", now = now, backedUp = false).copy(rows = rows)

    private fun title(i: Int) = "payee$i@example.com"

    /** The first row (of the first [count]) whose title still shows below [edge]: the row at the top of a scroller. */
    private fun topRow(edge: Float, count: Int): Int = (0 until count).first { i ->
        val nodes = compose.onAllNodesWithText(title(i), useUnmergedTree = true).fetchSemanticsNodes()
        nodes.any { it.positionInRoot.y + it.size.height > edge + 1 }
    }
    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    @Test fun theTopRowStaysOnTopAcrossClosedOpenBookTabletopAndBack() {
        val window = with(compose.density) { compose.activity.window.decorView.let { DpSize(it.width.toDp(), it.height.toDp()) } }
        assumeTrue("needs a large window such as an open Pixel Fold: $window", window.width >= 820.dp && window.height >= 660.dp)
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        var posture by mutableStateOf(Posture.Closed)
        val memory = HomeMemory().apply { enteredAt = 0L }
        restoration.setContent { NoahTheme {
            val density = LocalDensity.current
            var origin by remember { mutableStateOf(Offset.Zero) }
            val size = posture.size
            val hinge = with(density) {
                when (posture) {
                    Posture.Book -> WindowRegion((origin.x + (size.width / 2).toPx()).toInt() - 4, 0, (origin.x + (size.width / 2).toPx()).toInt() + 4,
                        LocalWindowInfo.current.containerSize.height)
                    Posture.Tabletop -> WindowRegion(0, (origin.y + (size.height / 2).toPx()).toInt() - 4, LocalWindowInfo.current.containerSize.width,
                        (origin.y + (size.height / 2).toPx()).toInt() + 4)
                    else -> null
                }
            }
            CompositionLocalProvider(LocalFoldingFeatures provides listOfNotNull(hinge)) {
                Box(Modifier.size(size).onGloballyPositioned { origin = it.positionInWindow() }) { HomePreviewHost(ui, memory) }
            }
        } }
        fun settle() { repeat(3) { compose.mainClock.advanceTimeBy(500); Thread.sleep(150) } }
        fun pose(next: Posture) {
            compose.runOnIdle { posture = next }
            restoration.emulateSavedInstanceStateRestore()
            settle()
        }
        settle()
        // Closed: scroll Recent so that a row past the first sits at the top edge of the column.
        repeat(2) {
            compose.onNodeWithTag("home-scroll").performTouchInput { swipeUp(startY = bottom * .9f, endY = bottom * .1f, durationMillis = 600) }
            settle()
        }
        val start = topRow(top("home-scroll"), 5)
        assertTrue("the swipe brought a later row to the top: $start", start >= 1)
        // Into two panes: the same row heads the activity list (its title, a day header and padding above it).
        for (posture in listOf(Posture.Open, Posture.Book, Posture.Tabletop, Posture.Book, Posture.Open)) {
            pose(posture)
            assertEquals("$posture keeps the row at the top", start, topRow(top("home-activity") + with(compose.density) { 28.dp.toPx() }, 12))
        }
        // And back to one column.
        pose(Posture.Closed)
        assertEquals("closed again keeps the row at the top", start, topRow(top("home-scroll"), 5))
    }
}
