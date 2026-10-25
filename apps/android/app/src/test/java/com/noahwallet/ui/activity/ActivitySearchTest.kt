package com.noahwallet.ui.activity

import android.app.Application
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.noahwallet.ui.NoahTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Activity's search: close stays closed and brings the title back, clear stays open, and back closes a payment before the search. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ActivitySearchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: ActivityState

    /** Robolectric's SystemClock stands still, and the list's entrance and the detail's opening tick on it: move it with the frames. */
    private fun settle(ms: Long = 1_500) {
        repeat((ms / 16).toInt()) { compose.mainClock.advanceTimeByFrame(); ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS) }
        compose.waitForIdle()
    }
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { NoahTheme { state = rememberActivityState(); ActivityPreviewHost(ActivityFixtures.ui("everyday"), state) } }
        settle()
    }
    private fun search(text: String? = null) {
        compose.onNodeWithTag("activity-search").performClick()
        settle()
        compose.onNodeWithTag("activity-search-field").assertIsFocused()
        if (text != null) { compose.onNodeWithTag("activity-search-field").performTextInput(text); settle() }
    }
    private fun tap(node: SemanticsNodeInteraction) { node.performClick(); settle() }
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; settle() }

    @Test fun closingSearchStaysClosedAndBringsTheTitleBack() {
        show()
        search()
        tap(compose.onNodeWithContentDescription("Close search"))
        assertFalse(state.searching)
        compose.onNodeWithTag("activity-search").assertExists()
        assertEquals("the large title is back at the top", 0, state.list.firstVisibleItemIndex)
        assertEquals(0, state.list.firstVisibleItemScrollOffset)
    }

    @Test fun clearKeepsSearchOpenWithTheKeyboard() {
        show()
        search("coffee")
        // The keyboard hidden (back or the Search key) while the field keeps focus.
        val input = compose.activity.getSystemService(InputMethodManager::class.java)
        val keyboard = shadowOf(input)
        compose.runOnUiThread { input.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0) }
        assertFalse(keyboard.isSoftInputVisible)
        tap(compose.onNodeWithContentDescription("Clear search"))
        assertTrue(state.searching)
        assertEquals("", state.query)
        compose.onNodeWithTag("activity-search-field").assertIsFocused()
        assertTrue("clear brings the keyboard back", keyboard.isSoftInputVisible)
    }

    @Test fun backClosesSearch() {
        show()
        search("coffee")
        back()
        assertFalse(state.searching)
        assertEquals("", state.query)
        compose.onNodeWithTag("activity-search").assertExists()
    }

    @Test fun backFromAPaymentReturnsToTheResultsWithoutFocusingTheField() {
        show()
        search("coffee")
        tap(compose.onNodeWithTag("activity-row-movement-3"))
        assertEquals("movement-3", state.selected)
        back()
        assertNull(state.selected)
        assertTrue(state.searching)
        assertEquals("coffee", state.query)
        compose.onNodeWithTag("activity-search-field").assertIsNotFocused()
        compose.onNodeWithTag("activity-row-movement-3").assertExists()
    }

    @Test @Config(qualifiers = "w900dp-h600dp-land")
    fun wideBackClosesThePaymentBeforeTheSearch() {
        show()
        search("coffee")
        tap(compose.onNodeWithTag("activity-row-movement-3"))
        assertEquals("movement-3", state.selected)
        back()
        assertNull(state.selected)
        assertTrue(state.searching)
        back()
        assertFalse(state.searching)
    }
}
