package com.noahwallet.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Switching to any other tab (including Home) plays a selection haptic; re-tapping the active tab does not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdaptiveNavigationHapticsTest {
    @get:Rule val compose = createComposeRule()

    private fun verifyTabSwitchingHaptics() {
        val played = mutableListOf<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                played += hapticFeedbackType
            }
        }
        var selected by mutableStateOf(Destination.Home)
        compose.setContent {
            NoahTheme {
                CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                    AdaptiveNavigation(selected, { selected = it }) {}
                }
            }
        }
        compose.waitForIdle()

        // Re-tapping Home while already on Home should not play haptics.
        compose.onNodeWithText("Home").performClick()
        compose.waitForIdle()
        assertEquals(Destination.Home, selected)
        assertEquals(emptyList<HapticFeedbackType>(), played)

        // Switching to Receive plays one selection haptic.
        compose.onNodeWithText("Receive").performClick()
        compose.waitForIdle()
        assertEquals(Destination.Receive, selected)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), played)

        // Re-tapping Receive while already on Receive should not play haptics.
        compose.onNodeWithText("Receive").performClick()
        compose.waitForIdle()
        assertEquals(Destination.Receive, selected)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), played)

        // Switching to Send, Activity, then back to Home each play a selection haptic.
        compose.onNodeWithText("Send").performClick()
        compose.waitForIdle()
        assertEquals(Destination.Send, selected)
        assertEquals(2, played.size)

        compose.onNodeWithText("Activity").performClick()
        compose.waitForIdle()
        assertEquals(Destination.History, selected)
        assertEquals(3, played.size)

        compose.onNodeWithText("Home").performClick()
        compose.waitForIdle()
        assertEquals(Destination.Home, selected)
        assertEquals(List(4) { HapticFeedbackType.SegmentTick }, played)

        // Re-tapping Home again does not play haptics.
        compose.onNodeWithText("Home").performClick()
        compose.waitForIdle()
        assertEquals(4, played.size)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun bottomBarPlaysSelectionHapticOnEveryTabSwitchIncludingHome() = verifyTabSwitchingHaptics()

    @Test
    @Config(qualifiers = "w840dp-h600dp")
    fun navigationRailPlaysSelectionHapticOnEveryTabSwitchIncludingHome() = verifyTabSwitchingHaptics()
}
