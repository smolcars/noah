package com.noahwallet.ui.send

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.noahwallet.send.SendController
import com.noahwallet.send.SendPayment
import com.noahwallet.ui.NoahTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/*
 * A short window (360 × 480 dp, a phone in split screen) and large text: Send's tall stage and title must never squeeze
 * the step's action out of reach. Each action is scrolled to, must lie wholly inside the window, and is tapped by touch.
 */
/** Scrolls [tag] into view and checks nothing clips it (a scroller, a pane or the window), so a touch anywhere on it lands. */
internal fun ComposeTestRule.assertReachable(tag: String) {
    assertEquals("nodes tagged $tag", 1, onAllNodesWithTag(tag).fetchSemanticsNodes().size)
    val node = onNodeWithTag(tag).performScrollTo().fetchSemanticsNode()
    val visible = node.boundsInRoot
    assertTrue("$tag has no height", node.size.height > 0)
    assertEquals("$tag is cut off: $visible shows ${visible.size} of ${node.size}", node.size.height.toFloat(), visible.height, 0.5f)
    assertEquals("$tag is cut off: $visible shows ${visible.size} of ${node.size}", node.size.width.toFloat(), visible.width, 0.5f)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h480dp")
class SendShortWindowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun until(done: () -> Boolean) {
        repeat(1_000) {
            if (done()) return
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        fail("Timed out")
    }
    private fun main(work: suspend () -> Unit) { CoroutineScope(Dispatchers.Main).launch { work() } }
    private fun show(presentation: com.noahwallet.ui.SendPresentation, actions: SendActions, fontScale: Float = 1f) {
        compose.activityRule.scenario.onActivity { it.setContent { NoahTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                SendPreviewHost(presentation, SendFixtures.env(), actions)
            }
        } } }
    }

    @Test fun sendsResultCanBeLeftByDone() {
        val wallet = SendFixtures.Wallet(SendFixtures.Outcome.arkSent).apply { gate.complete(Unit) }
        val presentation = SendFixtures.presentation(wallet)
        val controller = presentation.model.controller
        main { controller.submit(SendPayment(SendFixtures.ark, "ark", 500), wallet.quote)?.await() }
        until { controller.state == SendController.State.succeeded }
        var dones = 0
        show(presentation, SendActions(done = { dones++ }))
        until { has("send-success-done") }
        compose.assertReachable("send-success-done")
        compose.onNodeWithTag("send-success-done").performClick()
        compose.runOnIdle { assertEquals(1, dones) }
    }

    private fun review(fontScale: Float) {
        val presentation = SendFixtures.presentation()
        val model = presentation.model
        main { model.amount = "500"; model.continueStage(); model.setRecipient(SendFixtures.ark); model.continueStage() }
        until { model.showReview }
        show(presentation, SendActions(), fontScale)
        until { has("send-hold") }
        compose.assertReachable("send-hold")
    }
    @Test fun sendsReviewKeepsHoldToSendInReachAtDoubleText() = review(2f)
}
