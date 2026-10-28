package com.noahwallet.onchain

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.noahwallet.ui.LocalFoldingFeatures
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.WindowRegion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The exit pages on a device (exit.md, Testing): a hold starts or claims once, Claim stays off until its address is valid
 * and reviewed, Cancel exit shows only while bark allows it, nothing crosses a book or tabletop hinge, and the selection
 * survives the window being recreated. Public fixtures only; no wallet is used.
 */
class ExitDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val f = ExitFixtures
    private val regtest = "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg"

    private fun hold(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    @Test fun holdingToStartSubmitsOnceHoweverOftenItsHeld() {
        compose.mainClock.autoAdvance = false
        var submits = 0
        val running = CompletableDeferred<Unit>()
        val model = OnchainModel(OnchainModel.Dependencies({ true }, { error("offline") },
            { action, ids, _, _ -> OnchainQuote(action, "fixture", ids.sorted(), amount = 31_000, fee = 262, chainFee = 5_533, confirmed = 18_400,
                feeRate = 3, transactions = 4) },
            { submits++; running.await(); "submitted" }, { emptyList() }, {}, { false }, {}, { null }, {}))
        compose.setContent { NoahTheme { ExitPreviewHost {
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { model.review(OnchainAction.startExit, listOf(f.vtxos[0].id)) }
            // As the page wires it: a start runs only with a fresh review and nothing in flight.
            ExitStartContent(model.quote, model.estimateFallback, 18_400, model.busy, false, 144, f.money, {}, {}, {},
                start = { if (!model.busy && model.quote != null) scope.launch { model.confirm() } })
        } } }
        compose.mainClock.advanceTimeBy(1_000)
        hold("exit-hold-start")
        compose.runOnIdle { assertEquals(1, submits); assertTrue(model.busy) }
        // While it runs the review is spent: there's no hold left to press, by finger or accessibility action.
        compose.onNodeWithTag("exit-hold-start").assertDoesNotExist()
        running.complete(Unit)
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, submits); assertNull("the review is spent", model.quote) }
        // With no review left the pill reads Review again: nothing more can start.
        compose.onNodeWithTag("exit-review-again").assertExists()
    }

    @Test fun claimStaysOffUntilTheAddressIsValidForThisNetworkAndReviewed() {
        compose.mainClock.autoAdvance = false
        var typed by mutableStateOf("")
        var claims = 0
        val review = OnchainQuote(OnchainAction.claim, "fixture", listOf(f.claimable.id), regtest, 31_000, fee = 262, chainFee = 0, confirmed = 18_400)
        compose.setContent { NoahTheme { ExitPreviewHost {
            val address = ExitClaim.address(typed, "regtest")
            ExitClaimContent(listOf(f.claimable), f.tip, review.takeIf { address == regtest }, null, ClaimTo.other, typed, address, null, false, false, false,
                f.money, {}, {}, { typed = it }, {}, {}, { claims++ })
        } } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("exit-hold-claim").assertIsNotEnabled()
        typed = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("exit-hold-claim").assertIsNotEnabled()
        compose.onNodeWithText("Enter an on-chain Bitcoin address for this network.").assertExists()
        hold("exit-hold-claim")
        compose.runOnIdle { assertEquals("a mainnet address never claims on regtest", 0, claims) }
        typed = regtest
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("exit-hold-claim").assertIsEnabled()
        compose.onNodeWithText("Check the address. This can’t be undone.").assertExists()
        hold("exit-hold-claim")
        compose.runOnIdle { assertEquals(1, claims) }
    }

    @Test fun whileAClaimIsReviewedItsDestinationStaysPut() {
        compose.mainClock.autoAdvance = false
        var busy by mutableStateOf(true)
        val chosen = mutableListOf<ClaimTo>()
        compose.setContent { NoahTheme { ExitPreviewHost {
            ExitClaimContent(listOf(f.claimable), f.tip, null, null, ClaimTo.other, regtest, regtest, "bcrt1qphonephonephone", false, busy, false,
                f.money, {}, { chosen += it }, {}, {}, {}, {})
        } } }
        compose.mainClock.advanceTimeBy(1_000)
        for (tag in listOf("exit-to-other", "exit-to-phone")) compose.onNodeWithTag(tag).assertIsNotEnabled()
        // A disabled field drops its SetText action: find it by its editable text.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and
            (hasTestTag("exit-claim-address") or hasAnyAncestor(hasTestTag("exit-claim-address")))).assertIsNotEnabled()
        compose.onNodeWithTag("exit-to-phone").performClick()
        compose.runOnIdle { assertTrue("no switch mid-review", chosen.isEmpty()) }
        compose.runOnUiThread { busy = false }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("exit-to-phone").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(ClaimTo.phone), chosen) }
    }

    @Test fun claimableAndClaimingExitsKeepCheckTheChain() {
        compose.mainClock.autoAdvance = false
        var exit by mutableStateOf(f.claimable)
        var checks = 0
        compose.setContent { NoahTheme { ExitPreviewHost {
            val tip = exit.details.tip_height ?: f.tip
            ExitProgressContent(exit, f.stage(exit, tip), ExitContext(tip, 18_400, 144), false, false, f.money, {}, { checks++ }, {}, {}, {}, {})
        } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("exit-claim").assertIsEnabled()
        compose.onNodeWithTag("exit-check").performScrollTo().performClick()
        compose.runOnUiThread { exit = f.claiming }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("exit-claim").assertDoesNotExist()
        compose.onNodeWithTag("exit-check").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, checks) }
    }

    @Test fun aClaimWithNoReviewOffersReviewAgainOnceIdle() {
        compose.mainClock.autoAdvance = false
        var reviewing by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var reviews = 0
        compose.setContent { NoahTheme { ExitPreviewHost {
            ExitClaimContent(listOf(f.claimable), f.tip, null, null, ClaimTo.other, regtest, regtest, null, false, busy, false,
                f.money, {}, {}, {}, { reviews++ }, {}, {}, reviewing = reviewing)
        } } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("exit-review-again").assertDoesNotExist()
        compose.runOnUiThread { reviewing = false; busy = true }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("exit-review-again").assertDoesNotExist()
        // Idle, a valid address, nothing reviewed (a changed fee dropped it, say): Review again, not a Claim that stays off.
        compose.runOnUiThread { busy = false }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("exit-review-again").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reviews) }
    }

    @Test fun cancelExitShowsOnlyWhileBarkAllowsIt() {
        compose.mainClock.autoAdvance = false
        var exit by mutableStateOf(f.mempool)
        compose.setContent { NoahTheme { ExitPreviewHost {
            val tip = exit.details.tip_height ?: f.tip
            ExitProgressContent(exit, f.stage(exit, tip), ExitContext(tip, 18_400, 144, cancelable = exit.cancelable), false, false, f.money,
                {}, {}, {}, {}, {}, {})
        } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("exit-cancel").assertExists()
        compose.onNodeWithTag("exit-check").assertExists()
        for (later in listOf(f.onchain, f.wait, f.claimable, f.claimed)) {
            compose.runOnUiThread { exit = later }
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithTag("exit-cancel").assertDoesNotExist()
        }
        compose.onNodeWithTag("exit-done").assertExists()
    }

    private val book = { width: Int, height: Int -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
    private val tabletop = { width: Int, height: Int -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }

    private fun show(hinge: (Int, Int) -> WindowRegion, content: @Composable () -> Unit): WindowRegion {
        compose.mainClock.autoAdvance = false
        lateinit var fold: WindowRegion
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            fold = hinge(view.width, view.height)
            activity.setContent { NoahTheme { CompositionLocalProvider(LocalFoldingFeatures provides listOf(fold)) { ExitPreviewHost(content) } } }
        }
        compose.mainClock.advanceTimeBy(3_000)
        return fold
    }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { all += node; node.children.forEach(::walk) }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return all.filter { it.config.getOrNull(SemanticsProperties.Text) != null || it.config.getOrNull(SemanticsActions.OnClick) != null ||
            it.config.getOrNull(SemanticsProperties.TestTag) in setOf("exit-picture", "exit-shared", "exit-warn-picture") }
    }

    private fun assertClear(label: String, fold: WindowRegion) {
        val vertical = fold.height > fold.width
        for (node in nodes()) {
            val bounds: Rect = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val what =
                "$label: ${node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.TestTag)} $bounds $fold"
            if (vertical) assertFalse("crosses the vertical fold: $what", bounds.left < fold.left && bounds.right > fold.right)
            else assertFalse("crosses the horizontal fold: $what", bounds.top < fold.top && bounds.bottom > fold.bottom)
        }
        val lead = compose.onAllNodesWithTag("exit-leading", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val trail = compose.onAllNodesWithTag("exit-trailing", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        if (vertical) { assertTrue("$label leads: $lead", lead.right <= fold.left); assertTrue("$label trails: $trail", trail.left >= fold.right) }
        else { assertTrue("$label above: $lead", lead.bottom <= fold.top); assertTrue("$label below: $trail", trail.top >= fold.bottom) }
    }

    @Test fun bookAndTabletopKeepEveryPageOffTheHinge() {
        val estimate: suspend (List<String>) -> OnchainQuote? = { ids -> OnchainQuote(OnchainAction.startExit, "fixture", ids.sorted(), amount = 31_000,
            fee = 262, chainFee = 5_533, confirmed = 18_400, feeRate = 3, transactions = 4) }
        val review = OnchainQuote(OnchainAction.startExit, "fixture", listOf(f.vtxos[0].id), amount = 31_000, fee = 262, chainFee = 5_533,
            confirmed = 18_400, feeRate = 3, transactions = 4)
        val pages: List<Pair<String, @Composable () -> Unit>> = listOf(
            "warning" to @Composable { ExitWarningContent(f.list, f.tip, 144, 5_533, false, f.money, {}, {}, {}, {}) },
            "choose" to @Composable { ExitChooseContent(f.vtxos, f.costs, 18_400, false, true, false, false, f.money, {}, estimate, {}, {},
                initial = listOf(f.vtxos[0].id)) },
            "start" to @Composable { ExitStartContent(review, null, 18_400, false, false, 144, f.money, {}, {}, {}, {}) },
            "claim" to @Composable { ExitClaimContent(listOf(f.claimable), f.tip, null, null, ClaimTo.other, "", null, null, false, false, false, f.money,
                {}, {}, {}, {}, {}, {}) },
            "list" to @Composable { ExitListContent(f.list, f.tip + 2, emptySet(), true, false, false, f.money, {}, {}, {}, {}) },
        ) + listOf(f.publish, f.bump, f.nofunds, f.wait, f.claimable, f.claimed, f.canceled).mapIndexed { i, exit -> "progress $i" to @Composable {
            val tip = exit.details.tip_height ?: f.tip
            ExitProgressContent(exit, f.stage(exit, tip), ExitContext(tip, 18_400, 144, cancelable = exit.cancelable), false, false,
                f.money, {}, {}, {}, {}, {}, {})
        } }
        for ((pose, hinge) in listOf("book" to book, "tabletop" to tabletop)) for ((name, page) in pages) {
            val fold = show(hinge, page)
            assertClear("$pose $name", fold)
        }
    }

    @Test fun reduceMotionStillDrawsEveryPhase() {
        compose.mainClock.autoAdvance = false
        var exit by mutableStateOf(f.start)
        compose.setContent { NoahTheme { ExitPreviewHost {
            CompositionLocalProvider(com.noahwallet.ui.onboarding.LocalReduceMotion provides true) {
                val tip = exit.details.tip_height ?: f.tip
                ExitProgressContent(exit, f.stage(exit, tip), ExitContext(tip, 18_400, 144), false, false, f.money, {}, {}, {}, {}, {}, {})
            }
        } } }
        for (phase in listOf(f.start, f.publish, f.mempool, f.bump, f.confirm, f.onchain, f.wait, f.claimable, f.claiming, f.claimed, f.nofunds,
            f.feesrose, f.stuck, f.shared, f.stale, f.canceled)) {
            compose.runOnUiThread { exit = phase }
            // Every move is a 180 ms fade to the next rest pose; the picture keeps its full size throughout.
            compose.mainClock.advanceTimeBy(90)
            val mid = compose.onNodeWithTag("exit-picture").fetchSemanticsNode().size
            compose.mainClock.advanceTimeBy(400)
            val rest = compose.onNodeWithTag("exit-picture").fetchSemanticsNode().size
            assertTrue("${phase.state}: $mid $rest", mid.width > 0 && mid.height > 0 && rest == mid)
            // And it draws: the canvas inside the fade fills the picture rather than collapsing to nothing.
            val image = compose.onNodeWithTag("exit-picture").captureToImage().asAndroidBitmap()
            val background = image.getPixel(0, 0)
            var drawn = 0
            for (x in 0 until image.width step 4) for (y in 0 until image.height step 4) if (image.getPixel(x, y) != background) drawn++
            assertTrue("${phase.state} draws nothing under Reduce Motion", drawn > 50)
            // Once on-chain, the picture is the locked coin and its dial: the fixture carries what bark recorded while it waited.
            if (phase.state in setOf("AwaitingDelta", "Claimable", "ClaimInProgress", "Claimed")) {
                val tip = phase.details.tip_height ?: f.tip
                val scene = ExitScene.of(phase, f.stage(phase, tip), tip)
                assertTrue("${phase.state} has its timelock to draw", scene.lockedFrom != null && scene.claimableHeight != null)
            }
        }
    }

    @Test fun allCanBeReviewedOnlyOnceEveryVtxoIsClassified() {
        var costs by mutableStateOf<Map<String, ExitCosts.Cost?>?>(null)
        val reviewed = mutableListOf<List<String>>()
        compose.setContent { NoahTheme { ExitPreviewHost {
            ExitChooseContent(f.vtxos, costs, 18_400, false, true, false, false, f.money, {}, { null }, { reviewed += it }, {})
        } } }
        compose.onNodeWithTag("exit-review").assertIsNotEnabled()
        // Choosing one by one needs no estimate.
        compose.onNodeWithText("Choose").performClick()
        compose.onNodeWithTag("exit-vtxo-${f.vtxos[0].id.take(12)}").performClick()
        compose.onNodeWithTag("exit-review").assertIsEnabled()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithTag("exit-review").assertIsNotEnabled()
        compose.runOnUiThread { costs = f.costs }
        compose.onNodeWithTag("exit-review").assertIsEnabled().performClick()
        // Those whose fees are more than they're worth (₿7,500 and below here) stay out.
        compose.runOnIdle { assertEquals(listOf(f.vtxos.filter { it.amount > 7_500 && it.eligible }.map { it.id }), reviewed) }
    }

    @Test fun aVtxoBarkCouldNotEstimateKeepsAllWaitingWithATryAgain() {
        var costs by mutableStateOf<Map<String, ExitCosts.Cost?>?>(f.costs + (f.vtxos[2].id to null))
        var retries = 0
        compose.setContent { NoahTheme { ExitPreviewHost {
            ExitChooseContent(f.vtxos, costs, 18_400, false, true, false, false, f.money, {}, { null }, {}, {}, retry = { retries++ })
        } } }
        compose.onNodeWithTag("exit-review").assertIsNotEnabled()
        compose.onNodeWithTag("exit-costs-retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.runOnUiThread { costs = f.costs }
        compose.onNodeWithTag("exit-review").assertIsEnabled()
        compose.onNodeWithTag("exit-costs-retry").assertDoesNotExist()
    }

    @Test fun needingFundsKeepsCancelAndCheckTheChainBesideAddFunds() {
        compose.mainClock.autoAdvance = false
        var checks = 0
        compose.setContent { NoahTheme { ExitPreviewHost {
            val tip = f.nofunds.details.tip_height ?: f.tip
            ExitProgressContent(f.nofunds, f.stage(f.nofunds, tip), ExitContext(tip, 310, 144, cancelable = true), false, false, f.money,
                {}, { checks++ }, {}, {}, {}, {})
        } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("exit-add-funds").assertIsEnabled()
        compose.onNodeWithTag("exit-cancel").assertIsEnabled()
        compose.onNodeWithTag("exit-check").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun recreatingTheWindowKeepsTheChosenVtxos() {
        val restoration = StateRestorationTester(compose)
        val reviewed = mutableListOf<List<String>>()
        restoration.setContent { NoahTheme { ExitPreviewHost {
            ExitChooseContent(f.vtxos, f.costs, 18_400, false, true, false, false, f.money, {}, { null }, { reviewed += it }, {})
        } } }
        compose.onNodeWithText("Choose").performClick()
        compose.onNodeWithTag("exit-vtxo-${f.vtxos[2].id.take(12)}").performClick()
        compose.onNodeWithTag("exit-vtxo-${f.vtxos[4].id.take(12)}").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("exit-review").performClick()
        compose.runOnIdle { assertEquals(listOf(listOf(f.vtxos[2].id, f.vtxos[4].id)), reviewed) }
    }
}
