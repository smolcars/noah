package com.noahwallet.onchain

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.onboarding.LocalPose
import com.noahwallet.ui.onboarding.OnboardingFrame
import com.noahwallet.ui.onboarding.OnboardingPose
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The warning page's tree and exits card (exit-v2-notes.md, Approved item 4) each make their entrance once per page. Back from
 * an exit or the list, the page returns from its saved state with a new model that reads the wallet again; the tree and the
 * card then show at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExitWarningEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restore by lazy { StateRestorationTester(compose) }
    private val exits = listOf(ExitModel("a", 31_000, "Processing", ExitDetails(kind = "processing", tip_height = 100), emptyList(), emptyList()),
        ExitModel("b", 12_000, "Claimed", ExitDetails(kind = "claimed", tip_height = 100), emptyList(), emptyList()))
    private var shown by mutableStateOf(emptyList<ExitModel>())
    /** The tree's transactions, from the page's read; null until it reads. */
    private var transactions by mutableStateOf<Int?>(4)

    private fun page() {
        compose.mainClock.autoAdvance = false
        restore.setContent { NoahTheme { OnboardingFrame(null) {
            ExitWarningContent(shown, 100, 144, 5_533, false, { "₿$it" }, {}, {}, {}, {}, transactions = transactions)
        } } }
    }
    private var onPage by mutableStateOf(true)
    /** Split panes, as a phone turned to landscape gets: the page lays its picture out in the leading pane. */
    private var wide by mutableStateOf(false)
    /** The page in a back stack, as Settings keeps it: going on disposes it, Back composes it again from its saved state. */
    private fun pageInStack() {
        compose.mainClock.autoAdvance = false
        compose.setContent { NoahTheme { OnboardingFrame(null) {
            CompositionLocalProvider(LocalPose provides if (wide) OnboardingPose.Wide(450.dp, 450.dp) else OnboardingPose.Compact) {
                val stack = rememberSaveableStateHolder()
                if (onPage) stack.SaveableStateProvider("warning") {
                    ExitWarningContent(shown, 100, 144, 5_533, false, { "₿$it" }, {}, {}, {}, {}, transactions = transactions)
                }
            }
        } } }
    }
    private fun picture() = compose.onNodeWithTag("exit-warn-picture")
    private fun drawn() = picture().captureToImage().asAndroidBitmap()

    /** The first fact, under the card (below the fold, so unclipped): it moves only while the card makes room. */
    private fun facts() = compose.onNodeWithText("Takes a day or more").fetchSemanticsNode().positionInRoot.y
    private fun frames(count: Int) = repeat(count) { compose.mainClock.advanceTimeByFrame() }

    /** What the page's model has read, applied before the next frame (the clock stays still). */
    private fun read(list: List<ExitModel>) { shown = list; compose.waitForIdle() }

    @Test fun theCardMakesRoomOnceAndShowsAtOnceBackOnThePage() {
        page()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("exit-all").assertDoesNotExist()
        val without = facts()
        // The first visit: the exits arrive after the page opens.
        read(exits)
        frames(3)
        val moving = facts()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = facts()
        assertTrue("the facts ease down: $without, $moving, $settled", moving > without && moving < settled)
        // Back on the page, the new model hasn't read the exits yet; when it does, nothing eases.
        read(emptyList())
        restore.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("exit-all").assertDoesNotExist()
        read(exits)
        frames(2)
        compose.onNodeWithTag("exit-all").assertExists()
        assertEquals(settled, facts(), .5f)
    }

    @Test fun theTreeWaitsForYourPathGrowsOnceAndIsGrownBackOnThePage() {
        transactions = null
        pageInStack()
        compose.mainClock.advanceTimeBy(500)
        picture().assertDoesNotExist()
        // The first visit: it grows once the read gives your path, never from a round's usual four first.
        transactions = 6; compose.waitForIdle()
        frames(2)
        val growing = drawn()
        compose.mainClock.advanceTimeBy(4_000)
        val grown = drawn()
        assertFalse("it grows", growing.sameAs(grown))
        // On to an exit, then Back, before the page's new model reads: grown at once, with the path it had; the read changes
        // nothing.
        onPage = false; compose.waitForIdle(); frames(1)
        picture().assertDoesNotExist()
        transactions = null
        onPage = true; compose.waitForIdle(); frames(2)
        assertTrue("grown at once", drawn().sameAs(grown))
        transactions = 6; compose.waitForIdle()
        frames(2)
        assertTrue("still as it was", drawn().sameAs(grown))
    }

    /** Into Choose VTXOs, turn the phone, Back: the page comes back in split panes, its tree grown as it was left. */
    @Config(qualifiers = "w900dp-h600dp")
    @Test fun theTreeIsGrownBackOnThePageAfterTheLayoutChanged() {
        transactions = 6
        pageInStack()
        compose.mainClock.advanceTimeBy(4_000)
        onPage = false; compose.waitForIdle(); frames(1)
        wide = true; transactions = null
        onPage = true; compose.waitForIdle(); frames(2)
        picture().assertExists()
        val back = drawn()
        compose.mainClock.advanceTimeBy(4_000)
        assertTrue("grown at once", back.sameAs(drawn()))
    }

    /** A fold bends or a Duo opens while you're on the page: the tree moves into the leading pane as it stands. */
    @Config(qualifiers = "w900dp-h600dp")
    @Test fun theTreeStaysGrownWhenTheLayoutChangesOnThePage() {
        transactions = 6
        pageInStack()
        compose.mainClock.advanceTimeBy(4_000)
        wide = true; compose.waitForIdle(); frames(2)
        picture().assertExists()
        val moved = drawn()
        compose.mainClock.advanceTimeBy(4_000)
        assertTrue("grown at once", moved.sameAs(drawn()))
    }

    @Test fun aReadThatStallsGrowsTheUsualFourAfterASecond() {
        transactions = null
        page()
        compose.mainClock.advanceTimeBy(900)
        picture().assertDoesNotExist()
        compose.mainClock.advanceTimeBy(200)
        picture().assertExists()
    }

    @Test fun exitsKnownWhenThePageComposesShowAtOnce() {
        shown = exits
        page()
        frames(2)
        compose.onNodeWithTag("exit-all").assertExists()
        val first = facts()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(first, facts(), .5f)
    }
}
