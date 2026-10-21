package com.noahwallet.onchain

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.noahwallet.ui.loadAppModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.ParityPreviewActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only public read-only fixture fields; no seed or wallet operations. */
@RunWith(AndroidJUnit4::class)
class WalletParityDeviceTest {
    @Test fun exitUnknownFeeReviews() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahWalletParityUI") == "disposable")
        ActivityScenario.launch(ParityPreviewActivity::class.java).use { scenario ->
            Thread.sleep(2000)
            lateinit var host: ParityPreviewActivity
            scenario.onActivity { host = it }
            val app = runBlocking { withContext(Dispatchers.Main) { loadAppModel(host) } }
            for (action in listOf(OnchainAction.startExit, OnchainAction.claim)) {
                val unknown = OnchainQuote(action, "public-fixture", listOf("public-vtxo:0"),
                    destination = if (action == OnchainAction.claim) "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn" else null,
                    destinationIsMine = if (action == OnchainAction.claim) false else null,
                    amount = 30_000, fee = null, chainFee = null, confirmed = 0)
                scenario.onActivity { activity -> activity.setContent { NoahTheme {
                    Surface { OnchainReview(app, unknown.copy(unknownFeeConsent = unknown.id), {}, { fail("Read-only fixture") }) }
                } } }
                instrumentation.waitForIdleSync(); Thread.sleep(1500)
                fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean = node != null &&
                    (node.text?.toString()?.contains(text) == true || (0 until node.childCount).any { contains(node.getChild(it), text) })
                val deadline = System.currentTimeMillis() + 10_000
                while (!contains(instrumentation.uiAutomation.rootInActiveWindow, "Fees and amount to receive are unknown") && System.currentTimeMillis() < deadline) Thread.sleep(100)
                File(instrumentation.targetContext.getExternalFilesDir(null), "exit-fallback-${action.name}.png").outputStream().use {
                    checkNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val root = instrumentation.uiAutomation.rootInActiveWindow
                assertTrue("Unknown-fee warning is visible for $action", contains(root, "Fees and amount to receive are unknown"))
                assertFalse(contains(root, "Estimated claim fee"))
                assertFalse(contains(root, "Amount after fee"))
            }
        }
    }
    @Test fun exitBlockFields() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahWalletParityUI") == "disposable")
        ActivityScenario.launch(ParityPreviewActivity::class.java).use { scenario ->
            // The preview Activity installs its initial content asynchronously.
            Thread.sleep(2000)
            scenario.onActivity { activity -> activity.setContent { NoahTheme {
                Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Exit Details", style = MaterialTheme.typography.headlineSmall)
                    val details = ExitDetails(kind = "claimed", tip_height = 150,
                        claimable_since = ExitDetails.Block(144, "public-claimable-block"),
                        block = ExitDetails.Block(149, "public-claimed-block"))
                    ExitBlockStatusRows(details, 150)
                    ExitDetailsRows(details)
                } }
            } } }
            instrumentation.waitForIdleSync()
            Thread.sleep(1000)
            val root = instrumentation.uiAutomation.rootInActiveWindow
            fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean =
                node != null && (node.text?.toString() == text || (0 until node.childCount).any { contains(node.getChild(it), text) })
            for (text in listOf("Current Height", "Exit Tip", "Claimable Height", "Now", "Claimed block", "149 (public-claimed-block)")) {
                assertTrue("Visible exit field: $text", contains(root, text))
            }
            val context = instrumentation.targetContext
            File(context.getExternalFilesDir(null), "wallet-parity-exit-blocks.png").outputStream().use {
                checkNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    @Test fun exitOverviewFields() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahWalletParityUI") == "disposable")
        ActivityScenario.launch(ParityPreviewActivity::class.java).use { scenario ->
            Thread.sleep(2000)
            lateinit var host: ParityPreviewActivity
            scenario.onActivity { host = it }
            val app = runBlocking { withContext(Dispatchers.Main) { loadAppModel(host) } }
            scenario.onActivity { activity ->
                activity.setContent { NoahTheme { Surface {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        Text("Emergency Exit", style = MaterialTheme.typography.headlineSmall)
                        val dust = VtxoModel("public-dust", 100, 200, "Spendable", "Arkoor", 1, 400, true,
                            "public-server", 144, "public-anchor:0", "public-point:0", false, 150)
                        val exit = ExitModel("public-exit", 12000, "Claimed", ExitDetails(kind = "claimed", tip_height = 149), emptyList(), emptyList())
                        ExitOverviewSummary(app, OnchainOverview("public", listOf(dust), listOf(exit), 50000, 150, 0, 144))
                    }
                } } }
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(1000)
            fun shot(name: String) {
                File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                    checkNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            shot("wallet-parity-exit-summary.png")
            fun scroll(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean = node != null &&
                ((node.isScrollable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) ||
                    (0 until node.childCount).any { scroll(node.getChild(it)) })
            fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean = node != null &&
                (node.text?.toString() == text || (0 until node.childCount).any { contains(node.getChild(it), text) })
            val deadline = System.currentTimeMillis() + 10_000
            while (!contains(instrumentation.uiAutomation.rootInActiveWindow, "Below dust limit") && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue("Below-dust summary is visible", contains(root, "Below dust limit"))
            assertTrue("Claimed count is visible", contains(root, "Claimed"))
            scroll(root)
            Thread.sleep(1000)
            shot("wallet-parity-exit-summary-bottom.png")
        }
    }

}
