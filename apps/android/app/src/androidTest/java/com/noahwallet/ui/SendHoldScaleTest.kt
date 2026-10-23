package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.noahwallet.ui.send.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * "Remove animations" (animator duration scale 0) finishes every animation on its next frame. The hold must
 * still need 0.8 s with the finger down: a brief touch never sends.
 */
@OptIn(ExperimentalTestApi::class)
class SendHoldScaleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    })

    private val wallet = SendFixtures.Wallet(SendFixtures.Outcome.arkSent)
    private val presentation = SendFixtures.presentation(wallet)

    private fun hold(millis: Long) {
        compose.onNodeWithTag("send-hold").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(millis)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    @Test fun removedAnimationsStillNeedTheFullHold() {
        compose.mainClock.autoAdvance = false
        runBlocking(Dispatchers.Main) {
            presentation.model.amount = "500"; presentation.model.continueStage()
            presentation.model.setRecipient(SendFixtures.ark); presentation.model.continueStage()
        }
        compose.setContent { NoahTheme { SendPreviewHost(presentation, SendFixtures.env()) } }
        compose.onNodeWithTag("send-hold").assertIsDisplayed()
        // A tap, then a hold just short of 0.8 s: the stroke has long finished, but nothing is sent.
        hold(120)
        compose.runOnIdle { assertEquals(0, wallet.calls); assertFalse(presentation.model.controller.locked) }
        hold(700)
        compose.runOnIdle { assertEquals(0, wallet.calls); assertFalse(presentation.model.controller.locked) }
        // A full hold sends once.
        hold(1_000)
        compose.waitUntil(5_000) { wallet.calls == 1 }
        compose.runOnIdle { assertTrue(presentation.model.controller.locked) }
    }
}
