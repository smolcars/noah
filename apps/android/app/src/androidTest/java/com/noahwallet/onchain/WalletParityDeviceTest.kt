package com.noahwallet.onchain

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.ParityPreviewActivity
import com.noahwallet.ui.loadAppModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
                while (!contains(instrumentation.uiAutomation.rootInActiveWindow, "Fees and amount to receive are unknown") &&
                    System.currentTimeMillis() < deadline) Thread.sleep(100)
                File(instrumentation.targetContext.getExternalFilesDir(null), "exit-fallback-${action.name}.png").outputStream().use {
                    checkNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                val root = instrumentation.uiAutomation.rootInActiveWindow
                assertTrue("Unknown-fee warning is visible for $action", contains(root, "Fees and amount to receive are unknown"))
                assertFalse(contains(root, "Estimated claim fee"))
                assertFalse(contains(root, "Amount after fee"))
                assertFalse(contains(root, "The fees shown are estimates and may change."))
            }
        }
    }
}
