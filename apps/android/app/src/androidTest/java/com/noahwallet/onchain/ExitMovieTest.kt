package com.noahwallet.onchain

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in exit movies for screen recordings, on the device's real frame clock: unlike [ExitGalleryTest]'s Compose rule,
 * nothing here pauses infinite animations, so what waits for the next block breathes and ripples as in the app.
 */
class ExitMovieTest {
    /**
     * Opt-in (`-e noahExitMovie pulse|claim|fold|list`): plays an exit's moves on the real clock for a screen recording.
     * "pulse" is ready, goes to the mempool and waits, then its block comes and the next is sent; "claim" sends the claim
     * and lands it; "fold" is a 60-transaction path, with another exit leaving it, as two blocks come (a block joins the
     * stack, one comes out of "+N"); "list" holds the list, its glowing rows breathing.
     */
    @Test fun playExit() {
        val movie = InstrumentationRegistry.getArguments().getString("noahExitMovie")
        assumeTrue(movie != null)
        val f = ExitFixtures
        var step by mutableStateOf(0)
        val claimed = ExitContext(f.tip + 149, 18_400, 144, claimFee = 262, claimIds = listOf(f.claimed.id),
            claimedTo = "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg")
        val frames: List<Pair<ExitModel, ExitContext>> = when (movie) {
            "pulse" -> listOf(f.mempool to ExitContext(f.tip, 18_400, 144, cancelable = true), f.bump to ExitContext(f.tip, 18_400, 144, cancelable = true),
                f.confirm.copy(details = ExitDetails(kind = "processing", tip_height = f.tip + 1, transactions = listOf(
                    ExitDetails.Transaction(f.ids[0], ExitDetails.Status("confirmed", "fee1", ExitDetails.Block(f.tip + 1), "wallet")),
                    ExitDetails.Transaction(f.ids[1], ExitDetails.Status("awaiting-confirmation", "fee2", null, "wallet")),
                    ExitDetails.Transaction(f.ids[2], ExitDetails.Status("verify-inputs", null, null, null)),
                    ExitDetails.Transaction(f.ids[3], ExitDetails.Status("verify-inputs", null, null, null))))) to ExitContext(f.tip + 1, 17_185,
                    144, cancelable = true),
                f.confirm to ExitContext(f.tip + 2, 15_970, 144, cancelable = true))
            "claim" -> listOf(f.claimable to ExitContext(f.tip + 148, 18_400, 144, claimEstimate = 262),
                f.claiming to claimed.copy(tip = f.tip + 148), f.claimed to claimed)
            "fold" -> (25..27).map { f.long(it) to ExitContext(f.tip + it, 18_400, 144, cancelable = true) }
            else -> emptyList()
        }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity {
            it.enableEdgeToEdge()
            it.setContent { com.noahwallet.ui.NoahTheme { Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { ExitPreviewHost {
                if (movie == "list") ExitListContent(f.list, f.tip + 2, setOf(f.list[5].id), true, false, false, f.money, {}, {}, {}, {})
                else frames.getOrNull(step)?.let { (exit, context) ->
                    ExitProgressContent(exit, f.stage(exit, context.tip), context, false, false, f.money, {}, {}, {}, {}, {}, {},
                        others = if (movie == "fold") listOf(exit, f.longSibling) else emptyList()) }
            } } } } }
        Thread.sleep(6_000)
        for (i in 1 until frames.size) { scenario.onActivity { step = i }; Thread.sleep(6_000) }
        Thread.sleep(2_000)
        scenario.close()
    }
}
