package com.noahwallet.onchain

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.onboarding.OnboardingFrame
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The warning page's exits card (exit-v2-notes.md, Approved item 4) makes room for itself once per page. Back from an exit or
 * the list, the page returns from its saved state with a new model that reads the exits again; the card then shows at once.
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

    private fun page() {
        compose.mainClock.autoAdvance = false
        restore.setContent { NoahTheme { OnboardingFrame(null) { ExitWarningContent(shown, 100, 144, 5_533, false, { "₿$it" }, {}, {}, {}, {}) } } }
    }

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
