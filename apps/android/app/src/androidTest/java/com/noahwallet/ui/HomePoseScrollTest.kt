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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
 * the activity row at the top stays at the top, between one column and two panes too, including when Home is
 * recreated straight into the other layout without ever laying it out live. Needs a large window.
 */
class HomePoseScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Posture(val size: DpSize) {
        Closed(DpSize(400.dp, 420.dp)), Open(DpSize(820.dp, 660.dp)), Book(DpSize(820.dp, 660.dp)), Tabletop(DpSize(660.dp, 660.dp)),
        /** Open, but short enough that Fresh's card scrolls in its pane. */
        OpenShort(DpSize(820.dp, 420.dp))
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

    /** Home at [posture]'s size and fold, as the window shows it. */
    @Composable private fun Host(posture: Posture, memory: HomeMemory, home: HomeUi = ui) {
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
            Box(Modifier.size(size).onGloballyPositioned { origin = it.positionInWindow() }.testTag("home-window")) { HomePreviewHost(home, memory) }
        }
    }
    private fun assumeLargeWindow() {
        val window = with(compose.density) { compose.activity.window.decorView.let { DpSize(it.width.toDp(), it.height.toDp()) } }
        assumeTrue("needs a large window such as an open Pixel Fold: $window", window.width >= 820.dp && window.height >= 660.dp)
    }
    private fun settle() { repeat(3) { compose.mainClock.advanceTimeBy(500); Thread.sleep(150) } }
    /** Recreates Home from its saved state; frames run meanwhile, or the old composition is never disposed. */
    private fun StateRestorationTester.recreate() {
        compose.mainClock.autoAdvance = true
        emulateSavedInstanceStateRestore()
        compose.mainClock.autoAdvance = false
    }
    /** Closed: scroll Recent so that a row past the first sits at the top edge of the column; returns that row. */
    private fun scrollRecent(): Int {
        repeat(2) {
            compose.onNodeWithTag("home-scroll").performTouchInput { swipeUp(startY = bottom * .9f, endY = bottom * .1f, durationMillis = 600) }
            settle()
        }
        return topRow(top("home-scroll"), 5).also { assertTrue("the swipe brought a later row to the top: $it", it >= 1) }
    }

    @Test fun theTopRowStaysOnTopAcrossClosedOpenBookTabletopAndBack() {
        assumeLargeWindow()
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        var posture by mutableStateOf(Posture.Closed)
        val memory = HomeMemory().apply { enteredAt = 0L }
        restoration.setContent { NoahTheme { Host(posture, memory) } }
        fun pose(next: Posture) {
            compose.runOnIdle { posture = next }
            restoration.recreate()
            settle()
        }
        settle()
        val start = scrollRecent()
        // Into two panes: the same row heads the activity list (its title, a day header and padding above it).
        for (posture in listOf(Posture.Open, Posture.Book, Posture.Tabletop, Posture.Book, Posture.Open)) {
            pose(posture)
            assertEquals("$posture keeps the row at the top", start, topRow(top("home-activity") + with(compose.density) { 28.dp.toPx() }, 12))
        }
        // And back to one column.
        pose(Posture.Closed)
        assertEquals("closed again keeps the row at the top", start, topRow(top("home-scroll"), 5))
    }

    /** [text]'s box lies inside Home's window (so on screen, scrolled into view), with its top below [below] px. */
    private fun onScreen(text: String, below: Float = 0f): Boolean {
        val window = compose.onNodeWithTag("home-window").fetchSemanticsNode()
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        return node.positionInRoot.y >= window.positionInRoot.y + below &&
            node.positionInRoot.y + node.size.height <= window.positionInRoot.y + window.size.height
    }

    @Test fun theFreshCardKeepsItsPlaceRecreatedIntoEitherLayout() {
        assumeLargeWindow()
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        val fresh = HomeFixtures.ui("fresh", now = now, backedUp = false)
        var posture = Posture.OpenShort
        val memory = HomeMemory().apply { enteredAt = 0L }
        restoration.setContent { NoahTheme { Host(remember { posture }, memory, fresh) } }
        fun recreateAs(next: Posture) {
            posture = next
            restoration.recreate()
            settle()
        }
        settle()
        // Two panes: Copy and Share start below the card's pane; scroll that pane (the right half) down to them.
        assertFalse("the card overflows its pane", onScreen("Copy"))
        for (i in 0 until 3) {
            if (onScreen("Copy")) break
            compose.onNodeWithTag("home-window").performTouchInput { swipe(Offset(width * .75f, bottom * .9f), Offset(width * .75f, bottom * .2f), 600) }
            settle()
        }
        assertTrue("scrolled to Copy", onScreen("Copy"))
        // Closing the fold recreates Home as one column: the card's place carries over, not the balance pane's.
        recreateAs(Posture.Closed)
        assertTrue("closed, Copy is still in view", onScreen("Copy", below = with(compose.density) { 64.dp.toPx() }))
        assertTrue("Share too", onScreen("Share", below = with(compose.density) { 64.dp.toPx() }))
        // And opening it again puts the card's pane back at Copy and Share.
        recreateAs(Posture.OpenShort)
        assertTrue("open again, Copy is still in view", onScreen("Copy"))
    }

    private fun activityEdge() = top("home-activity") + with(compose.density) { 28.dp.toPx() }
    /** A payment that landed after the others: the newest row in history. */
    private val newest = HistoryTransaction("n-0", "Lnurl", 777L, HistoryRules.isoDate(now), "outgoing", sortTimestamp = now.time + 1.0,
        source = "ark", destination = "newest@example.com", movementStatus = "successful", effectiveBalanceSat = -777L, offchainFeeSat = 0)

    @Test fun aPaymentInsertedAboveKeepsTheSameOneOnTop() {
        assumeLargeWindow()
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        // Neither is state: only the recreated Home sees the new pose and history, as after the process was gone.
        var posture = Posture.Closed
        var home = ui
        val memory = HomeMemory().apply { enteredAt = 0L }
        restoration.setContent { NoahTheme { Host(remember { posture }, memory, remember { home }) } }
        settle()
        val start = scrollRecent()
        home = ui.copy(rows = listOf(newest) + rows)
        posture = Posture.Open
        restoration.recreate(); settle()
        assertEquals("open, the same payment is on top", start, topRow(activityEdge(), 12))
        posture = Posture.Closed
        restoration.recreate(); settle()
        // It's now Recent's row start + 1; the column scrolls it to the top unless it's the last row, which it can't.
        if (start + 2 < Recent.rows) assertEquals("closed, the same payment is on top", start, topRow(top("home-scroll"), 5))
        assertTrue("closed, the same payment is in view", onScreen(title(start), below = with(compose.density) { 64.dp.toPx() }))
    }

    @Test fun removalsKeepTheSamePaymentOrFallBackToTheOneAfterIt() {
        assumeLargeWindow()
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        var posture = Posture.Closed
        var home = ui
        val memory = HomeMemory().apply { enteredAt = 0L }
        restoration.setContent { NoahTheme { Host(remember { posture }, memory, remember { home }) } }
        settle()
        val start = scrollRecent()
        // An earlier payment is gone: the same one is still on top.
        home = ui.copy(rows = rows.filterNot { it.id == "p-0" })
        posture = Posture.Open
        restoration.recreate(); settle()
        assertEquals("open, the same payment is on top", start, topRow(activityEdge(), 12))
        // Now that payment is gone too: the one that followed it takes its place.
        home = ui.copy(rows = rows.filterNot { it.id == "p-0" || it.id == "p-$start" })
        restoration.recreate(); settle()
        assertEquals("the next payment takes its place", start + 1, topRow(activityEdge(), 12))
    }

    @Test fun aFailedThenSlowHistoryLoadKeepsTheSavedPlace() {
        assumeLargeWindow()
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        var posture = Posture.Closed
        var history by mutableStateOf(ui)
        val memory = HomeMemory().apply { enteredAt = 0L }
        restoration.setContent { NoahTheme { Host(remember { posture }, memory, history) } }
        settle()
        val start = scrollRecent()
        // History fails to load and its rows go, with an error, while Home is shown...
        history = ui.copy(rows = emptyList(), historyError = "Couldn't load transactions. Please try again.")
        settle()
        // ...then Home is recreated as two panes while history is still loading, for a while.
        history = HomeFixtures.ui("loading", now = now, backedUp = false)
        posture = Posture.Open
        restoration.recreate(); repeat(3) { settle() }
        // It loads.
        history = ui
        settle()
        assertEquals("the saved payment is on top once history is back", start, topRow(activityEdge(), 12))
    }

    @Test fun recreatedStraightIntoTheOtherLayoutTheTopRowStaysOnTop() {
        assumeLargeWindow()
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        // Not state: only the recreated Home sees a new pose, as an activity recreated after folding does.
        var posture = Posture.Closed
        val memory = HomeMemory().apply { enteredAt = 0L }
        restoration.setContent { NoahTheme { Host(remember { posture }, memory) } }
        fun recreateAs(next: Posture) {
            posture = next
            restoration.recreate()
            settle()
        }
        settle()
        val start = scrollRecent()
        recreateAs(Posture.Open)
        assertEquals("open, recreated from closed, keeps the row at the top", start,
            topRow(top("home-activity") + with(compose.density) { 28.dp.toPx() }, 12))
        recreateAs(Posture.Closed)
        assertEquals("closed, recreated from open, keeps the row at the top", start, topRow(top("home-scroll"), 5))
    }
}
