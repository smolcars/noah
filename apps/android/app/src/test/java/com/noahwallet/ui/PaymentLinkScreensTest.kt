package com.noahwallet.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.noahwallet.recurring.RecurringWorld
import com.noahwallet.recurring.ScheduleScreen
import com.noahwallet.send.*
import com.noahwallet.ui.settings.SettingsNav
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * OS deep links against the real screens (deep-links.md, behaviour 3): Send's review step and the recurring approval
 * sheet each reject links while open, and accept them again once left by any route. A link opened into Send's own
 * scanner closes it first, so the request isn't hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PaymentLinkScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app by lazy { AppModel(RuntimeEnvironment.getApplication()) }
    private val links = PaymentLinks()
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** NoahApp's delivery with the real counter the screens hold: true when the link reached the scan route. */
    private fun accepts(send: SendController, value: String = "bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22?amount=0.00002"): Boolean {
        var scanned = false
        links.blocked = false
        links.receive(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
        compose.runOnIdle { links.deliver(send, app.openReviews) { scanned = true } }
        assertNull(links.pending)
        assertEquals(value, !scanned, links.blocked)
        return scanned
    }
    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    /** A predictive back gesture from the edge; [complete] lifts the finger, otherwise it's cancelled. */
    private fun predictiveBack(dispatcher: androidx.activity.OnBackPressedDispatcher, complete: Boolean) = compose.runOnIdle {
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 500f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 500f, 0.5f, BackEventCompat.EDGE_LEFT))
        if (complete) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
    }

    private var quote: suspend () -> SendQuote = { FakeSendWallet.quote }
    private fun mountSend(): SendModel {
        val controller = SendController(SendController.Dependencies(read = { null }, write = {}, reservation = { it(FakeSendWallet()) }))
        val model = SendModel(controller, "regtest", { error("Not used") }, { quote() }, {}).apply { arkBalance = 10_000 }
        compose.activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST") return SendPresentation(model) as T
                }
            })["send-presentation", SendPresentation::class.java]
            activity.setContent { NoahTheme { SendScreen(app) {} } }
        }
        compose.waitForIdle()
        return model
    }
    /** An invoice with its own amount goes straight to review, as a scanned one does. */
    private fun review(model: SendModel) { compose.runOnIdle { main.launch { model.importRecipient(decoderInvoice("10u"), stay = true) } }; compose.waitForIdle() }

    @Test fun sendsReviewRejectsLinksUntilItsLeft() {
        val model = mountSend()
        val send = model.controller
        assertTrue("a draft follows scanning", accepts(send))
        // Preparing: the quote hasn't come back.
        val held = CompletableDeferred<SendQuote>(); quote = { held.await() }
        review(model)
        compose.runOnIdle { assertTrue(model.stage == SendStage.review && model.busy) }
        assertFalse(accepts(send))
        held.complete(FakeSendWallet.quote)
        compose.onNodeWithTag("send-hold").assertIsDisplayed()
        // Showing: two links in a row, and the same review after them.
        val shown = compose.runOnIdle { model.quote }
        assertFalse(accepts(send)); assertFalse(accepts(send, "lightning:lnurlp://example.com/two"))
        compose.runOnIdle { assertSame(shown, model.quote); assertTrue(model.showReview) }
        // Recreation keeps the review and exactly one registration.
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { it.setContent { NoahTheme { SendScreen(app) {} } } }
        compose.onNodeWithTag("send-hold").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, app.openReviews.intValue) }
        assertFalse(accepts(send))
        // Back leaves it.
        quote = { FakeSendWallet.quote }
        back()
        compose.runOnIdle { assertNotEquals(SendStage.review, model.stage); assertEquals(0, app.openReviews.intValue) }
        assertTrue(accepts(send))
        // A cancelled predictive back keeps it; a completed one leaves it.
        review(model)
        compose.onNodeWithTag("send-hold").assertIsDisplayed()
        predictiveBack(compose.activity.onBackPressedDispatcher, complete = false)
        assertFalse(accepts(send))
        predictiveBack(compose.activity.onBackPressedDispatcher, complete = true)
        compose.runOnIdle { assertNotEquals(SendStage.review, model.stage) }
        assertTrue(accepts(send))
        // A preparation that fails goes back to To.
        quote = { throw SendFailure(SendFailure.Reason.invalid) }
        review(model)
        compose.runOnIdle { assertEquals(SendStage.recipient, model.stage); assertNotNull(model.error) }
        assertTrue(accepts(send))
    }

    @Test fun aRecurringApprovalRejectsLinksUntilItsClosed() {
        val world = RecurringWorld(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        // An Ark fee needs approval (RecurringFeePolicy).
        runBlocking { world.add(); world.fee = 1; world.executor.execute() }
        assertEquals("needs_approval", world.schedule!!.status)
        val send = world.controller
        val nav = SettingsNav(back = {}, open = {}, pop = {}, myCode = {}, home = {})
        fun mount() {
            compose.activityRule.scenario.onActivity { it.setContent { NoahTheme { ScheduleScreen(app, nav, "rent", world.executor) } } }
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("recurring-review-pay")).fetchSemanticsNodes().isNotEmpty() }
        }
        fun open() {
            compose.onNodeWithTag("recurring-review-pay").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("send-confirm")).fetchSemanticsNodes().isNotEmpty() }
        }
        fun closed() = compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("send-confirm")).fetchSemanticsNodes().isEmpty() && app.openReviews.intValue == 0
        }
        mount()
        assertTrue("before review", accepts(send))
        // Preparing: Review and pay is quoting.
        val held = CompletableDeferred<Unit>(); world.beforeQuote = { held.await() }
        compose.onNodeWithTag("recurring-review-pay").performClick()
        compose.waitUntil(5_000) { app.openReviews.intValue == 1 }
        assertFalse(accepts(send))
        held.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("send-confirm")).fetchSemanticsNodes().isNotEmpty() }
        world.beforeQuote = {}
        // Open: two links in a row leave the sheet as it is.
        assertFalse(accepts(send)); assertFalse(accepts(send, "lightning:lnurlp://example.com/two"))
        compose.onNodeWithText("Confirm payment").assertIsDisplayed()
        // The sheet's own Back.
        compose.onNodeWithTag("send-review-back").performClick(); closed()
        assertTrue(accepts(send))
        // The system dismissing the sheet: back, a cancelled predictive back that keeps it, then a completed one.
        open()
        compose.runOnIdle { (ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed() }; closed()
        assertTrue(accepts(send))
        open()
        val sheet = compose.runOnIdle { (ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher }
        predictiveBack(sheet, complete = false)
        assertFalse(accepts(send))
        predictiveBack(sheet, complete = true); closed()
        assertTrue(accepts(send))
        // Recreation drops the sheet (today's approval isn't restored) and its registration with it.
        open()
        compose.activityRule.scenario.recreate()
        mount()
        compose.runOnIdle { assertEquals(0, app.openReviews.intValue) }
        assertTrue(accepts(send))
        // A preparation that fails.
        world.beforeQuote = { error("offline") }
        compose.onNodeWithTag("recurring-review-pay").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("offline")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(0, app.openReviews.intValue) }
        assertTrue(accepts(send))
    }

    @Test fun anAcceptedLinkClosesSendsOwnScannerFirst() {
        val model = mountSend()
        compose.onNodeWithTag("send-scan").performClick()
        compose.onNodeWithText("Scan QR code").assertIsDisplayed()
        // NoahApp's scan route for an accepted link.
        val link = "bitcoin:bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22?amount=0.00002"
        assertTrue(accepts(model.controller, link))
        compose.runOnIdle { app.scannedDestination = link }
        compose.waitForIdle()
        compose.onNodeWithText("Scan QR code").assertDoesNotExist()
        compose.runOnIdle { assertEquals(link, model.destination) }
    }
}
