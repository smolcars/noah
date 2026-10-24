package com.noahwallet.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.noahwallet.BuildConfig
import com.noahwallet.MainActivity
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * With App lock off, the restore shield alone keeps FLAG_SECURE on from "Recovery phrase found" through Restoring and
 * Ready, across recreation, until Home replaces onboarding. [storedPhraseRestoreStaysSecure] is opt-in
 * (`-e noahRestoreShield disposable-emulator`) on a disposable regtest emulator, built with `-PnoahRegtestServerURL` at a
 * host port that accepts and never answers, so Restore holds in Restoring and then fails. The stored phrase is the
 * public BIP-39 test vector.
 */
class RestoreShieldDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    private fun waitFor(message: String, timeout: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(message, condition())
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun text(value: String) = find(automation.rootInActiveWindow) { it.text?.toString() == value }
    private fun button(title: String) = find(automation.rootInActiveWindow) { node ->
        node.isClickable && node.isEnabled && find(node) { it.text?.toString() == title } != null
    }
    private fun resumed(): Activity? {
        var activity: Activity? = null
        instrumentation.runOnMainSync { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
        return activity
    }
    private fun secure(activity: Activity): Boolean {
        var on = false
        instrumentation.runOnMainSync { on = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
        return on
    }

    @Test fun storedPhraseRestoreStaysSecure() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahRestoreShield") == "disposable-emulator")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Requires a disposable app without wallet data", location.hasAnyData)
        val seeds = SeedStorage(context, "regtest")
        val settings = SettingsStorage(context)
        var activity: Activity? = null
        try {
            assertTrue(seeds.save(List(11) { "abandon" }.plus("about").joinToString(" ")) is SecretResult.Found)
            val first = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = first
            val model = withContext(Dispatchers.Main) { loadAppModel(first) }
            waitFor("Stored phrase without wallet data") { model.launchState == LaunchState.seedWithoutWalletData }
            waitFor("Restore offered") { model.canRestoreWallet && button("Restore Wallet") != null }
            assertFalse("App lock is off, so only the restore shield secures the window", model.walletLock.enabled)
            assertTrue("Recovery phrase found is secure", secure(first))
            SecureWindowSampler(instrumentation).use { sampler ->
                assertTrue(checkNotNull(button("Restore Wallet")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("Restoring shown") { model.restore.running && text("Restoring your wallet") != null }
                // Well past the screen's exit animation, which used to take FLAG_SECURE with it.
                Thread.sleep(2_000)
                assertEquals("restoring", model.step)
                instrumentation.runOnMainSync { first.recreate() }
                waitFor("Recreated in Restoring") { resumed().let { it != null && it !== first } && text("Restoring your wallet") != null }
                activity = resumed()
                Thread.sleep(2_000)
                assertTrue("Still restoring after recreation", model.restore.running)
                waitFor("The silent server fails the restore", 120_000) { model.restore.issue != null && !model.busy }
                waitFor("Back on Recovery phrase found") { text("Recovery phrase found") != null && button("Restore Wallet") != null }
                assertTrue("Never unshielded: ${sampler.gaps.get()} of ${sampler.samples.get()} samples", sampler.gaps.get() == 0)
                // A loaded emulator's main thread answers slowly; this floor only proves sampling ran the whole way.
                assertTrue("Sampled throughout: ${sampler.samples.get()}", sampler.samples.get() > 100)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("shieldSamples", "${sampler.samples.get()}") })
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            seeds.deleteWalletSeed(); settings.removeAll()
            location.directory.deleteRecursively(); location.restoreDirectory.deleteRecursively()
        }
    }

    /**
     * The typed path: LaunchGateView's loading pass comes before onboarding after a recreation, so the shield is handed to
     * the recreated screen rather than released. The phrase is the public BIP-39 test vector.
     */
    @Test fun typedPhraseRestoreStaysSecure() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahRestoreShield") == "disposable-emulator")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Requires a disposable app without wallet data", location.hasAnyData)
        var activity: Activity? = null
        try {
            val first = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = first
            val model = withContext(Dispatchers.Main) { loadAppModel(first) }
            waitFor("Onboarding") { model.launchState == LaunchState.onboarding }
            assertFalse("App lock is off, so only the restore shield secures the window", model.walletLock.enabled)
            fun click(title: String) {
                waitFor("$title appears") { text(title) != null }
                var target = text(title)
                while (target != null && !target.isClickable) target = target.parent
                assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            }
            click("I already have a wallet")
            val word = { find(automation.rootInActiveWindow) { it.isEditable && it.hintText?.toString()?.startsWith("Word ") == true } }
            waitFor("Word fields") { word() != null }
            assertTrue(checkNotNull(word()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, List(11) { "abandon" }.plus("about").joinToString(" "))
            }))
            waitFor("Restore enabled") { button("Restore") != null }
            SecureWindowSampler(instrumentation).use { sampler ->
                assertTrue(checkNotNull(button("Restore")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("Restoring shown") { model.restore.running && text("Restoring your wallet") != null }
                Thread.sleep(2_000)
                instrumentation.runOnMainSync { first.recreate() }
                waitFor("Recreated in Restoring") { resumed().let { it != null && it !== first } && text("Restoring your wallet") != null }
                activity = resumed()
                Thread.sleep(2_000)
                assertTrue("Still restoring after recreation", model.restore.running)
                waitFor("The silent server fails the restore", 120_000) { model.restore.issue != null && !model.busy }
                assertTrue("Never unshielded: ${sampler.gaps.get()} of ${sampler.samples.get()} samples", sampler.gaps.get() == 0)
                assertTrue("Sampled throughout: ${sampler.samples.get()}", sampler.samples.get() > 100)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("typedShieldSamples", "${sampler.samples.get()}") })
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            SettingsStorage(context).removeAll()
            location.directory.deleteRecursively(); location.restoreDirectory.deleteRecursively()
        }
    }

    /** Leaving onboarding for Home (or a launch gate) is what ends the shield; Restoring keeps it. */
    @Test fun shieldLastsThroughRestoringUntilHomeReplacesOnboarding() {
        val app = context.applicationContext as Application
        val home = mutableStateOf(false)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            lateinit var model: AppModel
            scenario.onActivity {
                activity = it
                model = AppModel(app)
                model.step = "storedSeed"
                // As LaunchGateView does when the route moves from onboarding to the app.
                it.setContent { NoahTheme { if (home.value) Text("Home") else Onboarding(model) } }
            }
            assumeTrue("App lock must be off", !model.walletLock.enabled)
            // The tree animates continuously, so wait on time rather than Compose idleness.
            Thread.sleep(1_500)
            assertTrue("Recovery phrase found is secure", secure(activity))
            SecureWindowSampler(instrumentation).use { sampler ->
                // Stored phrase into Restoring, then the typed path (Restore into Restoring), each past its exit animation.
                for (step in listOf("restoring", "restore", "restoring")) {
                    instrumentation.runOnMainSync { model.step = step }
                    Thread.sleep(2_000)
                }
                assertEquals("Restoring stays secure", 0, sampler.gaps.get())
                assertTrue("Sampled throughout: ${sampler.samples.get()}", sampler.samples.get() > 50)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("shieldSamples", "${sampler.samples.get()}") })
            }
            instrumentation.runOnMainSync { home.value = true }
            waitFor("Home is not shielded with App lock off", 5_000) { !secure(activity) }
        }
    }
}
