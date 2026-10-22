package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
