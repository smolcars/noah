package com.noahwallet.onchain

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.ui.NoahTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Opt-in screenshot gallery of the emergency exit from public fixtures (`-e noahExitShots <label>`): every page, phase,
 * failure state and both claim destinations, in the device's current posture, theme and animation setting. No wallet
 * is used. `-e noahExitScenes a,b` limits the scenes. Pull `files/exit-shots/<label>` with `run-as`.
 */
class ExitGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureEveryState() {
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("noahExitShots")
        assumeTrue(label != null)
        val only = args.getString("noahExitScenes")?.split(",")?.toSet()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "exit-shots/$label").apply { deleteRecursively(); mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        var scene by mutableStateOf<Pair<String, @Composable () -> Unit>?>(null)
        compose.setContent { NoahTheme { scene?.let { (name, content) -> key(name) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { ExitPreviewHost(content) }
        } } } }
        fun show(name: String, wait: Long = 2_500, content: @Composable () -> Unit) {
            if (only != null && name !in only) return
            compose.runOnUiThread { scene = name to content }
            // Effects and springs run on the test clock: step it like frames, giving real work time between steps.
            repeat((wait / 100).toInt()) { compose.mainClock.advanceTimeBy(100); Thread.sleep(40) }
            Thread.sleep(300)
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val f = ExitFixtures
        val estimate: suspend (List<String>) -> OnchainQuote? = { ids ->
            val amount = f.vtxos.filter { it.id in ids }.sumOf { it.amount }
            OnchainQuote(OnchainAction.startExit, "fixture", ids.sorted(), amount = amount, fee = 262L * ids.size, chainFee = 5_533L * ids.size,
                confirmed = 18_400, feeRate = 3, transactions = 4L * ids.size)
        }
        show("warn") { ExitWarningContent(emptyList(), f.tip, 144, 5_533, false, f.money, {}, {}, {}, {}) }
        show("warn-exits") { ExitWarningContent(f.list, f.tip, 144, 5_533, false, f.money, {}, {}, {}, {}) }
        show("choose-all") { ExitChooseContent(f.vtxos, f.costs, 18_400, false, true, false, false, f.money, {}, estimate, {}, {}) }
        show("choose-pick") { ExitChooseContent(f.vtxos, f.costs, 18_400, false, true, false, false, f.money, {}, estimate, {}, {}, initial = listOf(f.vtxos[0].id, f.vtxos[8].id)) }
        // bark's All estimate left c02 and dust c13 out; it refused c03. Picked ones that can't exit leave the total.
        show("choose-renew") { ExitChooseContent(f.vtxos, f.costs, 18_400, false, true, false, false, f.money, {}, estimate, {}, {},
            initial = f.vtxos.take(3).map { it.id }, refused = mapOf(f.vtxos[2].id to "Already spent"), excluded = setOf(f.vtxos[1].id, f.vtxos[12].id)) }
        show("choose-unfunded") { ExitChooseContent(f.vtxos, f.costs, 1_000, false, true, false, false, f.money, {}, estimate, {}, {}, initial = listOf(f.vtxos[0].id)) }
        val review = OnchainQuote(OnchainAction.startExit, "fixture", listOf(f.vtxos[0].id), amount = 31_000, fee = 262, chainFee = 5_533,
            confirmed = 18_400, feeRate = 3, transactions = 4)
        show("start") { ExitStartContent(review, null, 18_400, false, false, 144, f.money, {}, {}, {}, {}) }
        show("start-unfunded") { ExitStartContent(review.copy(confirmed = 310), null, 310, false, false, 144, f.money, {}, {}, {}, {}) }
        show("start-unknown") { ExitStartContent(null, review.copy(fee = null, chainFee = null, feeRate = null, transactions = null), 18_400, false, false, 144, f.money, {}, {}, {}, {}) }
        show("start-expired") { ExitStartContent(review.copy(createdAt = System.currentTimeMillis() - 31_000), null, 18_400, false, false, 144, f.money, {}, {}, {}, {}) }
        fun progress(name: String, exit: ExitModel, tip: Long = exit.details.tip_height ?: f.tip, needsFunds: Boolean = false, confirmed: Long = 18_400,
                     context: ExitContext = ExitContext(tip, confirmed, 144, cancelable = exit.cancelable, claimEstimate = 262)) =
            show(name, 4_000) { ExitProgressContent(exit, f.stage(exit, tip, needsFunds), context, false, false, f.money, {}, {}, {}, {}, {}, {}) }
        progress("progress-lift", f.start)
        progress("progress-publish", f.publish)
        progress("progress-mempool", f.mempool)
        progress("progress-bump", f.bump)
        progress("progress-confirm", f.confirm)
        progress("progress-onchain", f.onchain)
        progress("progress-wait", f.wait)
        progress("progress-claimable", f.claimable)
        progress("progress-claiming", f.claiming)
        progress("progress-claimed", f.claimed, context = ExitContext(f.tip + 149, 18_400, 144, claimFee = 262, claimIds = listOf(f.claimed.id),
            claimedTo = "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg"))
        progress("state-nofunds", f.nofunds, needsFunds = true, confirmed = 310)
        progress("state-feesrose", f.feesrose)
        progress("state-stuck", f.stuck)
        progress("state-shared", f.shared)
        progress("state-stale", f.stale, tip = f.tip)
        progress("state-canceled", f.canceled)
        val claimReview = OnchainQuote(OnchainAction.claim, "fixture", listOf(f.claimable.id), "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg",
            31_000, fee = 262, chainFee = 0, confirmed = 18_400, destinationIsMine = false)
        show("claim-other-empty") { ExitClaimContent(listOf(f.claimable), f.tip + 148, null, null, ClaimTo.other, "", null, null, false, false, false, f.money, {}, {}, {}, {}, {}, {}) }
        show("claim-other-invalid") { ExitClaimContent(listOf(f.claimable), f.tip + 148, null, null, ClaimTo.other, "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", null, null, false, false, false, f.money, {}, {}, {}, {}, {}, {}) }
        show("claim-other") { ExitClaimContent(listOf(f.claimable), f.tip + 148, claimReview, null, ClaimTo.other, claimReview.destination!!, claimReview.destination, null, false, false, false, f.money, {}, {}, {}, {}, {}, {}) }
        show("claim-phone") { ExitClaimContent(listOf(f.claimable), f.tip + 148, claimReview.copy(destination = "bcrt1qphonephonephone", destinationIsMine = true), null, ClaimTo.phone, "", null,
            "bcrt1qphonephonephone", false, false, false, f.money, {}, {}, {}, {}, {}, {}) }
        show("list") { ExitListContent(f.list, f.tip + 2, setOf(f.list[5].id), true, false, false, f.money, {}, {}, {}, {}) }
        show("list-deep") { ExitListContent(f.deep, f.tip + 2, emptySet(), true, false, false, f.money, {}, {}, {}, {}) }
        show("deposit") { ExitDepositContent("bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg", null, 4_533, f.money) {} }
    }
}
