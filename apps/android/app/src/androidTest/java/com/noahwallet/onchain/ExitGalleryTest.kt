package com.noahwallet.onchain

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.ui.NoahTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Opt-in screenshot gallery of the emergency exit from public fixtures (`-e noahExitShots <label>`): every page, phase,
 * failure state and both claim destinations, in the device's current posture, theme and animation setting. No wallet
 * is used. `-e noahExitScenes a,b` limits the scenes; `-e noahExitLocale sv` and `-e noahExitUnit sats` show another language
 * and unit. Pull `files/exit-shots/<label>` with `run-as`.
 */
class ExitGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureEveryState() {
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("noahExitShots")
        assumeTrue(label != null)
        val only = args.getString("noahExitScenes")?.split(",")?.toSet()
        val language = args.getString("noahExitLocale")
        val sats = args.getString("noahExitUnit") == "sats"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "exit-shots/$label").apply { deleteRecursively(); mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val locale = language?.let { java.util.Locale.forLanguageTag(it) } ?: java.util.Locale.US
        val money: (Long) -> String = if (sats) { amount ->
            com.noahwallet.formatting.BitcoinAmount.format(amount, com.noahwallet.formatting.BitcoinAmountUnit.sats, locale) }
        else ExitFixtures.money
        val shots = Shots(folder, only, money)
        compose.setContent { NoahTheme { Localized(language) { shots.scene?.let { (name, content) -> key(name) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { ExitPreviewHost(content) }
        } } } } }
        val f = ExitFixtures
        val estimate: suspend (List<String>) -> OnchainQuote? = { ids ->
            val amount = f.vtxos.filter { it.id in ids }.sumOf { it.amount }
            OnchainQuote(OnchainAction.startExit, "fixture", ids.sorted(), amount = amount, fee = 262L * ids.size, chainFee = 5_533L * ids.size,
                confirmed = 18_400, feeRate = 3, transactions = 4L * ids.size)
        }
        shots.warnings()
        shots.choosing(estimate)
        val review = OnchainQuote(OnchainAction.startExit, "fixture", listOf(f.vtxos[0].id), amount = 31_000, fee = 262, chainFee = 5_533,
            confirmed = 18_400, feeRate = 3, transactions = 4)
        shots.starting(review)
        shots.progressing(review)
        shots.claimsAndLists()
    }

    /** Shows each scene and screenshots it into [folder], skipping those not in [only]. */
    private inner class Shots(val folder: File, val only: Set<String>?, val money: (Long) -> String) {
        val f = ExitFixtures
        var scene by mutableStateOf<Pair<String, @Composable () -> Unit>?>(null)

        fun show(name: String, wait: Long = 2_500, realtime: Boolean = false, content: @Composable () -> Unit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            if (only != null && name !in only) return
            compose.runOnUiThread { scene = name to content }
            // Effects and springs run on the test clock: step it like frames, giving real work time between steps. A
            // realtime scene keeps the test clock in step with the wall clock, so a screen recording shows its motion at real speed.
            if (realtime) {
                val start = android.os.SystemClock.uptimeMillis(); val from = compose.mainClock.currentTime
                while (compose.mainClock.currentTime - from < wait) {
                    val behind = android.os.SystemClock.uptimeMillis() - start - (compose.mainClock.currentTime - from)
                    if (behind >= 16) compose.mainClock.advanceTimeBy(behind) else Thread.sleep(4)
                }
            } else repeat((wait / 100).toInt()) { compose.mainClock.advanceTimeBy(100); Thread.sleep(40) }
            Thread.sleep(300)
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun Shots.warnings() {
        show("warn") { ExitWarningContent(emptyList(), f.tip, 144, 5_533, false, money, {}, {}, {}, {}) }
        show("warn-one") { ExitWarningContent(listOf(f.bump), f.tip, 144, 5_533, false, money, {}, {}, {}, {}) }
        show("warn-exits") { ExitWarningContent(f.list, f.tip + 2, 144, 5_533, false, money, {}, {}, {}, {}, needsFunds = setOf(f.list[5].id)) }
        show("warn-past") { ExitWarningContent(f.list.filter { !f.stage(it, f.tip + 2).live }, f.tip + 2, 144, 5_533, false, money, {}, {}, {}, {}) }
        // The exits read 1.5 s after the page opens, as a slow wallet's would: the picture stays and the card fades in under it.
        show("warn-late", 4_000, realtime = true) {
            val exits by produceState(emptyList<ExitModel>()) { kotlinx.coroutines.delay(1_500); value = f.list }
            ExitWarningContent(exits, f.tip + 2, 144, 5_533, false, money, {}, {}, {}, {}, needsFunds = setOf(f.list[5].id))
        }
    }

    private fun Shots.choosing(estimate: suspend (List<String>) -> OnchainQuote?) {
        show("choose-all") { ExitChooseContent(f.vtxos, f.costs, 18_400, false, true, false, false, money, {}, estimate, {}, {}) }
        show("choose-pick") { ExitChooseContent(f.vtxos, f.costs, 18_400, false, true, false, false, money, {}, estimate, {}, {},
            initial = listOf(f.vtxos[0].id, f.vtxos[8].id)) }
        // bark's All estimate left c02 and dust c13 out; it refused c03. Picked ones that can't exit leave the total.
        show("choose-renew") { ExitChooseContent(f.vtxos, f.costs, 18_400, false, true, false, false, money, {}, estimate, {}, {},
            initial = f.vtxos.take(3).map { it.id }, refused = mapOf(f.vtxos[2].id to "Already spent"), excluded = setOf(f.vtxos[1].id, f.vtxos[12].id)) }
        show("choose-unfunded") { ExitChooseContent(f.vtxos, f.costs, 1_000, false, true, false, false, money, {}, estimate, {}, {},
            initial = listOf(f.vtxos[0].id)) }
    }

    private fun Shots.starting(review: OnchainQuote) {
        show("start") { ExitStartContent(review, null, 18_400, false, false, 144, money, {}, {}, {}, {}) }
        show("start-unfunded") { ExitStartContent(review.copy(confirmed = 310), null, 310, false, false, 144, money, {}, {}, {}, {}) }
        show("start-unknown") { ExitStartContent(null, review.copy(fee = null, chainFee = null, feeRate = null, transactions = null), 18_400, false, false,
            144, money, {}, {}, {}, {}) }
        show("start-expired") { ExitStartContent(review.copy(createdAt = System.currentTimeMillis() - 31_000), null, 18_400, false, false, 144,
            money, {}, {}, {}, {}) }
    }

    private fun Shots.progressing(review: OnchainQuote) {
        fun progress(name: String, exit: ExitModel, tip: Long = exit.details.tip_height ?: f.tip, needsFunds: Boolean = false, confirmed: Long = 18_400,
            context: ExitContext = ExitContext(tip, confirmed, 144, cancelable = exit.cancelable, claimEstimate = 262), others: List<ExitModel> = emptyList()) =
            show(name, 4_000) { ExitProgressContent(exit, f.stage(exit, tip, needsFunds), context, false, false, money, {}, {}, {}, {},
                {}, {}, others = others) }
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
        progress("progress-claimed-phone", f.claimed, context = ExitContext(f.tip + 149, 18_400, 144, claimFee = 262, claimIds = listOf(f.claimed.id)))
        progress("progress-claiming-other", f.claiming, context = ExitContext(f.tip + 148, 18_400, 144, claimFee = 262, claimIds = listOf(f.claiming.id),
            claimedTo = "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg"))
        progress("progress-shared3", f.sharing, others = f.list.map { if (it.id == f.sharing.id) f.sharing else it },
            context = ExitContext(f.tip + 2, 18_400, 144, cancelable = true, sharedFees = 2_430))
        // Three of your other exits leave yours at one transaction: the third steps further out, like the second.
        val mine = f.list.first { it.amount == 31_000L }; val twin = f.list.first { it.amount == 24_500L }
        progress("progress-siblings3", mine,
            others = listOf(mine, twin, twin.copy(id = twin.id + "b", amount = 15_000), twin.copy(id = twin.id + "c", amount = 12_000)))
        progress("progress-deep", f.deepHero)
        // A 60-transaction path, folded to the levels that fit: the oldest blocks stacked, the rest to send as +N.
        progress("progress-long", f.long, context = ExitContext(f.tip + 25, 18_400, 144, cancelable = true))
        show("start-deep") { ExitStartContent(review, null, 18_400, false, false, 144, money, {}, {}, {}, {}, depth = 100) }
        progress("state-nofunds", f.nofunds, needsFunds = true, confirmed = 310)
        progress("state-feesrose", f.feesrose)
        progress("state-stuck", f.stuck)
        progress("state-shared", f.shared)
        progress("state-stale", f.stale, tip = f.tip)
        progress("state-canceled", f.canceled)
    }

    private fun Shots.claimsAndLists() {
        val claimReview = OnchainQuote(OnchainAction.claim, "fixture", listOf(f.claimable.id),
            "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg",
            31_000, fee = 262, chainFee = 0, confirmed = 18_400, destinationIsMine = false)
        show("claim-other-empty") { ExitClaimContent(listOf(f.claimable), f.tip + 148, null, null, ClaimTo.other, "", null, null, false, false, false,
            money, {}, {}, {}, {}, {}, {}) }
        show("claim-other-invalid") { ExitClaimContent(listOf(f.claimable), f.tip + 148, null, null, ClaimTo.other,
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", null, null, false, false, false, money, {}, {}, {}, {}, {}, {}) }
        show("claim-other") { ExitClaimContent(listOf(f.claimable), f.tip + 148, claimReview, null, ClaimTo.other, claimReview.destination!!,
            claimReview.destination, null, false, false, false, money, {}, {}, {}, {}, {}, {}) }
        show("claim-phone") { ExitClaimContent(listOf(f.claimable), f.tip + 148,
            claimReview.copy(destination = "bcrt1qphonephonephone", destinationIsMine = true), null, ClaimTo.phone, "", null,
            "bcrt1qphonephonephone", false, false, false, money, {}, {}, {}, {}, {}, {}) }
        show("list") { ExitListContent(f.list, f.tip + 2, setOf(f.list[5].id), true, false, false, money, {}, {}, {}, {}) }
        show("list-deep") { ExitListContent(f.deep, f.tip + 2, emptySet(), true, false, false, money, {}, {}, {}, {}) }
        // Three exits sharing a 60-transaction path: their gutter folds as the picture does.
        show("list-long") { ExitListContent(listOf(f.long, f.longTwin, f.longSibling), f.tip + 25, emptySet(), true, false, false, money, {}, {}, {}, {}) }
        show("list-one") { ExitListContent(listOf(f.bump), f.tip, emptySet(), true, false, false, money, {}, {}, {}, {}) }
        show("deposit") { ExitDepositContent("bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg", null, 4_533, money) {} }
    }
}

/** The gallery in another language: its strings and number formats, without changing the device's. */
@Composable
private fun Localized(language: String?, content: @Composable () -> Unit) {
    if (language == null) return content()
    val base = androidx.compose.ui.platform.LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val context = remember(language, configuration) {
        base.createConfigurationContext(android.content.res.Configuration(configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(language))
        })
    }
    // The activity stays the context (sheets need it); only the strings and formats change.
    CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides context.resources.configuration,
        androidx.compose.ui.platform.LocalResources provides context.resources, content = content)
}
