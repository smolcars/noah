package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.noahwallet.send.*
import com.noahwallet.ui.send.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A result that can't be acknowledged still has a way out of Send, which leaves the payment exactly as it is. */
class SendResultExitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aBroadcastRetryLeavesWithTheAttemptAndTheLock() {
        for (fold in listOf(null, "book")) {
            val wallet = SendFixtures.Wallet(SendFixtures.Outcome.broadcastPending).apply { gate.complete(Unit) }
            val presentation = SendFixtures.presentation(wallet)
            val controller = presentation.model.controller
            val payment = SendPayment(SendFixtures.onchain, "onchain", 50_000, SendSource.offchain)
            runBlocking(Dispatchers.Main) {
                controller.submit(payment, SendQuote(50_000, 420, 50_420, approximate = true, walletIdentity = SendFixtures.owner))?.await()
            }
            val attempt = checkNotNull(controller.attempt)
            assertTrue(attempt.receipt!!.broadcastRetryPending)
            var leaves = 0; var dones = 0
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView
                val hinge = fold?.let { WindowRegion(view.width / 2 - 12, 0, view.width / 2 + 12, view.height) }
                activity.setContent { NoahTheme { CompositionLocalProvider(LocalFoldingFeatures provides listOfNotNull(hinge)) {
                    // Leaving disposes Send, which resets only an unlocked payment (SendPresentation.leave).
                    SendPreviewHost(presentation, SendFixtures.env(), SendActions(done = { dones++ }, leave = { leaves++; presentation.leave() }))
                } } }
            }
            compose.waitForIdle()
            assertTrue("$fold: Done can't forget the retry", compose.onAllNodesWithTag("send-success-done").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("send-leave").assertIsDisplayed().assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(fold.toString(), 1, leaves); assertEquals(0, dones)
                assertSame("$fold: the attempt is kept as it was", attempt, controller.attempt)
                assertTrue(controller.locked); assertEquals(SendController.State.succeeded, controller.state)
                assertEquals(1, wallet.calls)
            }
            // Done stays refused for it, so only a confirmed broadcast (or the audited override) ends it.
            runBlocking(Dispatchers.Main) { controller.acknowledge() }
            compose.runOnIdle { assertSame(attempt, controller.attempt); assertTrue(controller.locked) }
        }
    }
}
