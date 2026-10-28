package com.noahwallet.services

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.BuildConfig
import com.noahwallet.MainActivity
import com.noahwallet.services.push.WidgetDataWriter
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.*
import com.noahwallet.ui.RecoveryPhraseInput
import io.github.niteshbalusu11.bark.generateMnemonic
import io.github.niteshbalusu11.bark.validateMnemonic
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ForegroundWidgetDeviceTest {
    /** Capture only empty input; never weaken the production screen's FLAG_SECURE policy. */
    @Test fun emptyTwentyFourWordEntryScreenshot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahWidgetEmptyRestoreShot") == "owned-emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, label: String): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label) return node
            repeat(node.childCount) { find(node.getChild(it), label)?.let { match -> return match } }
            return null
        }
        fun waitFor(label: String): android.view.accessibility.AccessibilityNodeInfo {
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                find(automation.rootInActiveWindow, label)?.let { return it }
                Thread.sleep(100)
            }
            error("Expected empty restore mode control")
        }
        fun click(label: String) {
            var node: android.view.accessibility.AccessibilityNodeInfo? = waitFor(label)
            while (node != null && !node.isClickable) node = node.parent
            check(node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
            instrumentation.waitForIdleSync()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click("Restore Wallet"); click("24 words")
            scenario.onActivity { activity ->
                val input = androidx.lifecycle.ViewModelProvider(activity)[com.noahwallet.ui.RecoveryPhrasePresentation::class.java].input
                check(input.words.size == 24 && input.words.all(String::isEmpty))
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            try {
                instrumentation.waitForIdleSync(); Thread.sleep(500)
                val image = checkNotNull(automation.takeScreenshot())
                File(context.filesDir, "empty-24-restore.png").outputStream().use {
                    check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                image.recycle()
            } finally { scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) } }
        }
    }

    @Test fun barkAcceptsTwelveAndTwentyFourWordPublicVectors() {
        for (count in listOf(12, 24)) {
            val phrase = (List(count - 1) { StoredMnemonic.wordList[0] } + StoredMnemonic.wordList[if (count == 12) 3 else 102]).joinToString(" ")
            assertTrue("Bark accepts public vector", validateMnemonic(phrase))
            val input = RecoveryPhraseInput(); input.update(phrase, 0)
            assertTrue(input.isComplete); assertEquals(count, input.words.size)
            assertFalse(validateMnemonic(List(count) { StoredMnemonic.wordList[0] }.joinToString(" ")))
        }
    }

    @Test fun fundedForegroundRefreshPublishesExpiry() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("noahWidgetFundingURL")
        assumeTrue(url != null)
        check(BuildConfig.APP_VARIANT == "regtest")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "widget-${UUID.randomUUID()}").apply { mkdirs() }
        val session = WalletSession("regtest", WalletDataLocation(root, "regtest"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val admission = ForegroundSyncAdmission()
            scenario.onActivity { it.lifecycle.addObserver(admission) }
            try {
                session.createNew(generateMnemonic(), true, "widget-test")
                val invoice = session.bolt11Invoice(20_000u)
                val connection = URI(url!!).toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"; connection.doOutput = true; connection.readTimeout = 120_000
                    connection.outputStream.use { it.write(org.json.JSONObject().put("invoice", invoice).toString().toByteArray()) }
                    check(connection.responseCode == 200) { "Regtest invoice payment failed" }
                } finally { connection.disconnect() }
                val deadline = System.nanoTime() + 120_000_000_000L
                while (System.nanoTime() < deadline) {
                    session.claimLightningReceives(); session.sync()
                    if (session.summary()!!.spendableSats > 0uL) break
                    delay(1000)
                }
                val caller = currentCoroutineContext()
                fun checkStage() { caller.checkWalletAdmission(); admission.checkActive() }
                var published = false
                ForegroundWidgetRefresh.read(session::summary, session::closestExpiryBlocks, ::checkStage) { balance, expiry ->
                    assertTrue(balance.ark.spendable > 0uL); assertNotNull(expiry)
                    WidgetDataWriter.write(context, balance, expiry, "regtest")
                    WidgetDataWriter.write(context, balance, null, "regtest", preserveExpiry = true)
                    val stored = context.getSharedPreferences("com.noahwallet.regtest", 0)
                    assertEquals(expiry, stored.getLong("closestExpiryBlocks", -999))
                    published = true
                }
                assertTrue(published)
            } finally { session.close(); root.deleteRecursively() }
        }
    }
}
