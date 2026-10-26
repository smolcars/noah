package com.noahwallet.recurring

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import com.noahwallet.send.SendController
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.send.assertReachable
import com.noahwallet.ui.settings.SettingsNav
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * "Review and pay" on Send's redesigned review and result (iOS `RecurringPaymentsView.approval`): the hold hands the
 * reviewed occurrence to the executor, which pays it once through the shared controller; every result has a way out.
 * The window is short (360 × 480 dp), so each action must also be reachable by touch (`assertReachable`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h480dp")
class RecurringApprovalScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app by lazy { AppModel(RuntimeEnvironment.getApplication()) }
    private val nav = SettingsNav(back = {}, open = {}, pop = {}, myCode = {}, home = {})

    private var world: RecurringWorld? = null

    /** Leaves nothing running for the next test in this JVM: the frame clock advances again and the executor's scope ends. */
    @After fun release() {
        compose.mainClock.autoAdvance = true
        world?.scope?.cancel()
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    /** Steps the wall clock (the motion and storage) and the frame clock together until [done]. */
    private fun until(done: () -> Boolean) {
        repeat(800) {
            if (done()) return
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(1)
        }
        val w = world
        fail("Timed out: paid ${w?.wallet?.calls} times, controller ${w?.controller?.state}, schedule ${w?.schedule?.status}")
    }
    /** Lets the sending motion finish (it goes quiet a beat after its last phase), then lets the test clock drive frames again, as scrolling needs. */
    private fun settle() {
        repeat(50) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); compose.mainClock.advanceTimeBy(50) }
        compose.mainClock.autoAdvance = true
    }
    /** An Ark fee needs approval (RecurringFeePolicy); the executor runs on Main, as in the app. */
    private fun needsApproval(): RecurringWorld {
        val world = RecurringWorld(CoroutineScope(SupervisorJob() + Dispatchers.Main)).also { this.world = it }
        world.add(); world.fee = 1
        CoroutineScope(Dispatchers.Main).launch { world.executor.execute() }
        until { world.schedule?.status == "needs_approval" && !world.executor.running }
        return world
    }
    /** Opens the approval's review in its sheet. */
    private fun review(world: RecurringWorld, fontScale: Float = 1f) {
        compose.activityRule.scenario.onActivity { it.setContent { NoahTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                ScheduleScreen(app, nav, "rent", world.executor, approve = { review -> world.executor.execute(manual = true, approval = review) })
            }
        } } }
        until { has("recurring-review-pay") }
        compose.onNodeWithTag("recurring-review-pay").performClick()
        until { has("send-hold") }
        compose.onNodeWithTag("send-review-title").assertExists()
        assertEquals(0, world.wallet.calls)
    }
    /** Opens the approval and holds to send (the hold's accessibility action, then its confirmation). */
    private fun holdToSend(world: RecurringWorld) {
        review(world)
        // The motion runs on the wall clock: step it by hand from here.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("send-hold").performSemanticsAction(SemanticsActions.OnClick)
        until { has("send-hold-confirm") }
        compose.onNodeWithTag("send-hold-confirm").performClick()
    }

    @Test fun approvingPaysTheOccurrenceOnceAndDoneReleasesTheResult() {
        val world = needsApproval()
        holdToSend(world)
        until { has("send-success-done") }
        assertEquals(1, world.wallet.calls)
        assertEquals(SendController.State.succeeded, world.controller.state)
        until { !world.executor.running }
        assertEquals("active", world.schedule!!.status)
        assertEquals(1, world.schedule!!.occurrencesPaid)
        // Back can't close the sheet while the controller holds the result.
        val sheet = ShadowDialog.getShownDialogs().single { it.isShowing } as ComponentDialog
        compose.runOnIdle { sheet.onBackPressedDispatcher.onBackPressed() }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); compose.mainClock.advanceTimeBy(50) }
        assertTrue(sheet.isShowing && has("send-success-done"))
        // Done acknowledges the retained receipt and closes the sheet; nothing is paid again.
        settle()
        compose.assertReachable("send-success-done")
        compose.onNodeWithTag("send-success-done").performClick()
        until { !has("send-success-done") && !has("send-stage") }
        assertFalse(world.controller.locked)
        assertEquals(1, world.wallet.calls)
        assertEquals(0, app.openReviews.intValue)
    }

    @Test fun anUnresolvedApprovalLeavesByCloseWithTheAttemptAndTheLock() {
        val world = needsApproval()
        world.wallet.payBlock = { error("connection reset") }
        holdToSend(world)
        until { has("send-leave") }
        val attempt = world.controller.attempt
        assertEquals(SendController.State.uncertain, world.controller.state)
        assertEquals(1, world.wallet.calls)
        until { !world.executor.running }
        assertEquals("needs_attention", world.schedule!!.status)
        settle()
        compose.assertReachable("send-check-status")
        // Close only closes the sheet: the attempt and the send lock stay, and nothing is paid again.
        compose.assertReachable("send-leave")
        compose.onNodeWithTag("send-leave").performClick()
        until { !has("send-leave") && !has("send-stage") }
        assertSame(attempt, world.controller.attempt)
        assertTrue(world.controller.locked)
        assertEquals(1, world.wallet.calls)
    }

    @Test fun theReviewKeepsHoldToSendInReach() {
        review(needsApproval())
        compose.assertReachable("send-hold")
    }

    @Test fun theReviewKeepsHoldToSendInReachAtDoubleText() {
        review(needsApproval(), fontScale = 2f)
        compose.assertReachable("send-hold")
    }
}
