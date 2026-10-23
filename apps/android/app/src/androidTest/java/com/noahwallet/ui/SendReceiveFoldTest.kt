package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.noahwallet.send.*
import com.noahwallet.ui.receive.*
import com.noahwallet.ui.send.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Send and Receive take the whole window and split it at a separating fold themselves. Synthetic folds make
 * this independent of the device's posture: nothing straddles the hinge; the amount, title, stage and card lead
 * (or sit above, in tabletop); the keypad, rows, hold and formats trail (or sit below).
 */
class SendReceiveFoldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val book = { width: Int, height: Int -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
    private val tabletop = { width: Int, height: Int -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }

    private fun show(hinge: (Int, Int) -> WindowRegion, content: @Composable () -> Unit): WindowRegion {
        compose.mainClock.autoAdvance = false
        lateinit var fold: WindowRegion
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            fold = hinge(view.width, view.height)
            activity.setContent { NoahTheme { CompositionLocalProvider(LocalFoldingFeatures provides listOf(fold)) { content() } } }
        }
        compose.mainClock.advanceTimeBy(2_500)
        return fold
    }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { all += node; node.children.forEach(::walk) }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return all.filter { it.config.getOrNull(SemanticsProperties.Text) != null || it.config.getOrNull(SemanticsActions.OnClick) != null ||
            it.config.getOrNull(SemanticsProperties.TestTag) in setOf("send-stage", "receive-card", "send-amount") }
    }

    private fun assertClear(label: String, fold: WindowRegion) {
        val vertical = fold.height > fold.width
        for (node in nodes()) {
            val bounds: Rect = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val what = "$label: ${node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.TestTag)} $bounds $fold"
            if (vertical) assertFalse("crosses the vertical fold: $what", bounds.left < fold.left && bounds.right > fold.right)
            else assertFalse("crosses the horizontal fold: $what", bounds.top < fold.top && bounds.bottom > fold.bottom)
        }
    }

    private fun bounds(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
    private fun leads(label: String, fold: WindowRegion, leading: String, trailing: String) {
        val lead = bounds(leading)
        val trail = bounds(trailing)
        if (fold.height > fold.width) {
            assertTrue("$label: $leading leads: $lead", lead.right <= fold.left)
            assertTrue("$label: $trailing trails: $trail", trail.left >= fold.right)
        } else {
            assertTrue("$label: $leading above: $lead", lead.bottom <= fold.top)
            assertTrue("$label: $trailing below: $trail", trail.top >= fold.bottom)
        }
    }

    private fun send(setup: suspend (SendPresentation) -> Unit): SendPresentation {
        val presentation = SendFixtures.presentation(SendFixtures.Wallet(SendFixtures.Outcome.arkSent).apply { gate.complete(Unit) })
        runBlocking(Dispatchers.Main) { setup(presentation) }
        return presentation
    }

    @Test fun sendStepsSplitAtTheFold() {
        for ((pose, hinge) in listOf("book" to book, "tabletop" to tabletop)) {
            val amount = send { it.model.amount = "500" }
            var fold = show(hinge) { SendPreviewHost(amount, SendFixtures.env()) }
            assertClear("$pose amount", fold); leads("$pose amount", fold, "send-amount", "send-key-5")
            leads("$pose amount paste", fold, "send-paste-from-amount", "send-key-5")
            val review = send { it.model.amount = "500"; it.model.continueStage(); it.model.setRecipient(SendFixtures.ark); it.model.continueStage() }
            fold = show(hinge) { SendPreviewHost(review, SendFixtures.env()) }
            assertClear("$pose review", fold); leads("$pose review", fold, "send-review-title", "send-hold")
            val method = send { it.model.amount = "500"; it.model.continueStage(); it.model.setRecipient(SendFixtures.request()); it.model.continueStage() }
            fold = show(hinge) { SendPreviewHost(method, SendFixtures.env()) }
            assertClear("$pose method", fold)
            val sent = send { it.model.controller.submit(SendPayment(SendFixtures.ark, "ark", 500), SendQuote(500, 0, 500, walletIdentity = SendFixtures.owner))?.await() }
            fold = show(hinge) { SendPreviewHost(sent, SendFixtures.env()) }
            assertClear("$pose sent", fold); leads("$pose sent", fold, "send-stage", "send-success-done")
        }
    }

    @Test fun receiveCardLeadsAndFormatsTrail() {
        for ((pose, hinge) in listOf("book" to book, "tabletop" to tabletop)) for (state in listOf("address", "amount")) {
            val fold = show(hinge) { ReceivePreviewHost(ReceiveFixtures.ui(state)) }
            assertClear("$pose $state", fold)
            leads("$pose $state", fold, "receive-card", "receive-format-request")
            // The formats are in the pane; there's no sheet button.
            assertTrue(compose.onAllNodesWithTag("receive-formats", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
    }
}
