package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.noahwallet.ui.home.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Home takes the whole window and splits it at a separating fold itself. Synthetic folds make this
 * independent of the device's posture: nothing may straddle the hinge, the balance leads (or sits above in
 * tabletop) and the card or list trails (or sits below, with the navigation bar).
 */
class HomeFoldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(ui: HomeUi, hinge: ((Int, Int) -> WindowRegion)?): WindowRegion? {
        compose.mainClock.autoAdvance = false
        var fold: WindowRegion? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            fold = hinge?.invoke(view.width, view.height)
            activity.setContent { NoahTheme { CompositionLocalProvider(LocalFoldingFeatures provides listOfNotNull(fold)) {
                Box(Modifier.fillMaxSize()) { HomePreviewHost(ui, HomeMemory().apply { enteredAt = 0L }) }
            } } }
        }
        compose.mainClock.advanceTimeBy(2_500)
        return fold
    }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { all += node; node.children.forEach(::walk) }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return all.filter { it.config.getOrNull(SemanticsProperties.Text) != null || it.config.getOrNull(SemanticsActions.OnClick) != null }
    }

    private fun assertClear(label: String, fold: WindowRegion) {
        val vertical = fold.height > fold.width
        for (node in nodes()) {
            val bounds: Rect = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            // A modal sheet's scrim is a whole-window dismiss target, not content.
            if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Close sheet") == true) continue
            val what = "$label: ${node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()} $bounds $fold"
            if (vertical) assertFalse("crosses the vertical fold: $what", bounds.left < fold.left && bounds.right > fold.right)
            else assertFalse("crosses the horizontal fold: $what", bounds.top < fold.top && bounds.bottom > fold.bottom)
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
    private fun text(value: String) = compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow

    private val book = { width: Int, height: Int -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
    private val tabletop = { width: Int, height: Int -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }

    @Test fun bookPostureSplitsAtTheHinge() {
        for (state in listOf("fresh", "everyday", "busy", "suspended")) {
            // Regtest's server-assigned names are long; the chip must never push the buttons across the fold.
            val fold = show(HomeFixtures.ui(state, address = "grandstanders49@localhost.com"), book)!!
            assertClear(state, fold)
            val settings = bounds("home-settings-button")
            assertTrue("$state: settings stays in the leading pane: $settings", settings.right <= fold.left && settings.width > 0)
            if (state == "suspended") continue
            assertTrue("$state: balance leads", bounds("home-balance").right <= fold.left)
            val trailing = if (state == "fresh") bounds("home-get-paid") else bounds("home-activity")
            assertTrue("$state: card or list trails: $trailing", trailing.left >= fold.right)
        }
    }

    @Test fun tabletopPutsBalanceAboveAndCardListAndNavigationBelow() {
        for (state in listOf("fresh", "everyday", "busy")) {
            val fold = show(HomeFixtures.ui(state), tabletop)!!
            assertClear(state, fold)
            assertTrue("$state: balance above", bounds("home-balance").bottom <= fold.top)
            val lower = if (state == "fresh") bounds("home-get-paid") else bounds("home-activity")
            assertTrue("$state: card or list below: $lower", lower.top >= fold.bottom)
            // The navigation bar (not a rail crossing the fold) sits in the bottom half.
            for (tab in listOf("Home", "Receive", "Send", "Activity")) compose.onAllNodesWithText(tab, useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInWindow }.filter { it.height > 0 }.forEach { assertTrue("$state: $tab below the fold: $it", it.top >= fold.bottom) }
        }
    }

    @Test fun balanceSheetStaysInTheTrailingPane() {
        val fold = show(HomeFixtures.ui("everyday"), book)!!
        compose.onNodeWithTag("home-balance").performSemanticsAction(SemanticsActions.OnClick)
        // The sheet's dialog window attaches on real frames; its show animation runs on the test clock.
        compose.mainClock.advanceTimeBy(500); Thread.sleep(700); compose.mainClock.advanceTimeBy(1_500)
        val title = compose.onAllNodesWithText("Balance", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInWindow }
            .first { it.height > 0 }
        assertTrue("sheet title in the trailing pane: $title", title.left >= fold.right)
        assertClear("sheet", fold)
    }

    @Test fun poseChangeKeepsTheSheetHideStateAndScroll() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        val memory = HomeMemory().apply { enteredAt = 0L }
        var hidden by mutableStateOf(false)
        restoration.setContent { NoahTheme { HomePreviewHost(HomeFixtures.ui("busy", hidden), memory, actions = HomeActions(toggleHidden = { hidden = !hidden })) } }
        compose.mainClock.advanceTimeBy(2_000)
        val compact = compose.onAllNodesWithTag("home-scroll").fetchSemanticsNodes().isNotEmpty()
        if (compact) {
            compose.onNodeWithTag("home-scroll").performTouchInput { swipeUp(startY = bottom * .8f, endY = bottom * .5f, durationMillis = 300) }
            // Let the fling settle before taking positions.
            compose.mainClock.advanceTimeBy(4_000)
        }
        val fiat = text("$283.07").top
        compose.onNodeWithTag("home-balance").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("hiding the balance keeps its line box, so the fiat line stays put", fiat, text("$ ••••").top, 1f)
        compose.onNodeWithTag("home-balance").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(1_500)
        assertTrue(hidden)
        fun range() = compose.onNodeWithTag("home-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        fun scrolled() = range().value()
        // Only when Busy is taller than this window (the closed Pixel Fold); on a tall phone it fits without scrolling.
        val before = if (compact && range().maxValue() > 0f) scrolled() else null
        if (before != null) assertTrue("the swipe scrolled Home: $before", before > 0f)
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1_500)
        assertTrue("the balance sheet is still open", compose.onAllNodesWithText("Balance", useUnmergedTree = true).fetchSemanticsNodes().any { it.boundsInWindow.height > 0 })
        if (before != null) assertEquals("scroll position kept", before, scrolled(), 1f)
        assertTrue(hidden)
    }

    @Test fun firstPaymentContinuesAcrossRecreationWithoutStartingAgain() {
        compose.mainClock.autoAdvance = false
        val restoration = StateRestorationTester(compose)
        val payment = HomeMemory.FirstPayment(android.os.SystemClock.elapsedRealtime(), 21_000, null)
        val memory = HomeMemory().apply { enteredAt = 0L; firstPayment = payment }
        restoration.setContent { NoahTheme { HomePreviewHost(HomeFixtures.ui("everyday", newRows = setOf("f-1")), memory) } }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("home-get-paid").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(300)
        assertSame("the same sequence continues", payment, memory.firstPayment)
        compose.onNodeWithTag("home-get-paid").assertExists()
        Thread.sleep(3_200)
        compose.mainClock.advanceTimeBy(500)
        assertNull("the sequence ends once", memory.firstPayment)
        compose.onAllNodesWithTag("home-get-paid").assertCountEquals(0)
    }

    @Test fun suspendedShowsOnlyTheSuspendedState() {
        show(HomeFixtures.ui("suspended"), null)
        compose.onNodeWithText("Wallet Suspended", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Open Settings", useUnmergedTree = true).assertExists()
        for (tag in listOf("home-balance", "home-recent", "home-activity", "home-get-paid")) compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher("a nudge") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("home-nudge") == true }, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Scan", useUnmergedTree = true).assertCountEquals(0)
    }
}
