package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.noahwallet.send.*
import com.noahwallet.ui.send.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Hold to send confirms exactly as today's Confirm did, once: an early release rewinds, the accessibility
 * action asks first, and repeated confirmations or pose changes never submit a second payment.
 */
class SendHoldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val wallet = SendFixtures.Wallet(SendFixtures.Outcome.arkSent)
    private val presentation = SendFixtures.presentation(wallet)
    private var fold by mutableStateOf<WindowRegion?>(null)

    private fun review() {
        // Advance the clock by hand: the hold is clock-driven, and an in-flight Lightning bolt never idles.
        compose.mainClock.autoAdvance = false
        runBlocking(Dispatchers.Main) {
            presentation.model.amount = "500"; presentation.model.continueStage()
            presentation.model.setRecipient(SendFixtures.ark); presentation.model.continueStage()
        }
        compose.setContent { NoahTheme { CompositionLocalProvider(LocalFoldingFeatures provides listOfNotNull(fold)) {
            SendPreviewHost(presentation, SendFixtures.env())
        } } }
        compose.onNodeWithTag("send-hold").assertIsDisplayed()
    }
    private fun hold(millis: Long) {
        compose.onNodeWithTag("send-hold").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(millis)
        // A completed hold has already turned the review into the sending step; lift the finger wherever it is.
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    @Test fun anEarlyReleaseRewindsAndAFullHoldSubmitsOnce() {
        review()
        hold(400)
        compose.runOnIdle { assertEquals(0, wallet.calls); assertFalse(presentation.model.controller.locked) }
        hold(1_000)
        compose.waitUntil(5_000) { wallet.calls == 1 }
        compose.runOnIdle {
            assertTrue(presentation.model.controller.locked)
            assertNotNull(presentation.motion)
        }
        // The review (and its hold) is gone; a stale confirmation is a no-op.
        assertTrue(compose.onAllNodesWithTag("send-hold").fetchSemanticsNodes().isEmpty())
        val payment = SendPayment(SendFixtures.ark, "ark", 500)
        compose.runOnUiThread { presentation.confirm(payment, SendQuote(500, 0, 500, walletIdentity = SendFixtures.owner)) }
        compose.runOnIdle { assertEquals(1, wallet.calls) }
        wallet.gate.complete(Unit)
        compose.waitUntil(5_000) { presentation.model.controller.state == SendController.State.succeeded }
    }

    @Test fun overlappingFingersNeverAddUpToAHold() {
        review()
        // A presses, B joins at 500 ms and A lifts at 600 ms: no one finger held for 0.8 s, so nothing is sent.
        compose.onNodeWithTag("send-hold").performTouchInput { down(0, center) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("send-hold").performTouchInput { down(1, center + Offset(40f, 0f)) }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().performTouchInput { up(0) }
        compose.mainClock.advanceTimeBy(700)
        compose.onRoot().performTouchInput { up(1) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, wallet.calls); assertFalse(presentation.model.controller.locked) }
        // A second finger landing ends the hold, even with the first one kept down past 0.8 s.
        compose.onNodeWithTag("send-hold").performTouchInput { down(0, center) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("send-hold").performTouchInput { down(1, center + Offset(40f, 0f)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { up(1); up(0) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, wallet.calls); assertFalse(presentation.model.controller.locked) }
        // One finger held for the full 0.8 s still sends, once.
        hold(1_000)
        compose.waitUntil(5_000) { wallet.calls == 1 }
        compose.runOnIdle { assertTrue(presentation.model.controller.locked) }
    }

    @Test fun scrollingThenHoldingStillNeverSends() {
        // A review taller than the screen: the pill sits in a scrolling column, as on a short phone or under a fold.
        var calls = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { NoahTheme {
            Column(Modifier.size(320.dp, 360.dp).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(240.dp))
                HoldToSend("₿ 500", enabled = true, sending = false) { calls++ }
                Spacer(Modifier.height(900.dp))
            }
        } }
        compose.mainClock.advanceTimeBy(500)
        // Drag to scroll without leaving the pill (it moves along under the finger), then hold still past 0.8 s.
        compose.onNodeWithTag("send-hold").performTouchInput { down(center); repeat(8) { moveBy(Offset(0f, -12f)) } }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertEquals("the scroll took the gesture", 0, calls) }
        // A fresh, still hold on the scrolled pill sends once.
        hold(1_000)
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun accessibilityAsksForConfirmationThenSendsOnce() {
        review()
        compose.onNodeWithTag("send-hold").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("send-hold-confirm").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(5_000) { wallet.calls == 1 }
        compose.runOnIdle { assertTrue(presentation.model.controller.locked) }
        // Dismissing instead never sends.
        assertTrue(compose.onAllNodesWithTag("send-hold-confirm").fetchSemanticsNodes().isEmpty())
    }

    @Test fun repeatedConfirmationsSubmitOnce() {
        review()
        val payment = checkNotNull(presentation.model.payment)
        val quote = checkNotNull(presentation.model.quote)
        compose.runOnUiThread { presentation.confirm(payment, quote); presentation.confirm(payment, quote) }
        compose.waitUntil(5_000) { wallet.calls == 1 }
        Thread.sleep(300)
        compose.runOnIdle { assertEquals(1, wallet.calls) }
    }

    @Test fun poseChangesNeverRepeatASubmission() {
        review()
        val view = compose.activity.window.decorView
        val book = WindowRegion(view.width / 2 - 12, 0, view.width / 2 + 12, view.height)
        val tabletop = WindowRegion(0, view.height / 2 - 12, view.width, view.height / 2 + 12)
        // A pose change mid-hold cancels it: nothing is sent.
        compose.onNodeWithTag("send-hold").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnUiThread { fold = book }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, wallet.calls); assertFalse(presentation.model.controller.locked) }
        // A full hold in the book pose sends once; folding, bending and unfolding afterwards never sends again.
        hold(1_000)
        compose.waitUntil(5_000) { wallet.calls == 1 }
        for (pose in listOf(tabletop, null, book, null)) {
            compose.runOnUiThread { fold = pose }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithTag("send-hold").fetchSemanticsNodes().isEmpty())
        }
        compose.runOnIdle { assertEquals(1, wallet.calls); assertTrue(presentation.model.controller.locked) }
    }
}
