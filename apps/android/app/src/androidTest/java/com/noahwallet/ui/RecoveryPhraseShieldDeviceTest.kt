package com.noahwallet.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.noahwallet.BuildConfig
import com.noahwallet.MainActivity
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.storage.SettingsStorage
import com.noahwallet.services.storage.WalletDataLocation
import com.noahwallet.ui.settings.RevealedPhrase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * With App lock off, the Settings recovery-phrase page alone keeps FLAG_SECURE on through a rotation (an activity
 * recreation) of the revealed page, and the rotation still hides the words so Show asks again. Opt-in
 * (`-e noahPhraseShield disposable-emulator`) on a disposable regtest emulator: it creates a throwaway wallet to reach
 * Settings, so the Ark server must be reachable. The shown words are sample words standing in for a successful
 * authentication; no real phrase is read, logged or captured.
 */
class RecoveryPhraseShieldDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    private fun waitFor(message: String, timeout: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(message, condition())
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(automation.rootInActiveWindow)
    }
    private fun text(value: String) = find { it.text?.toString() == value }
    private fun click(message: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        waitFor(message) { find(predicate) != null }
        var target = find(predicate)
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(message, target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
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

    @Test fun revealedPhraseStaysSecureAcrossRotation() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahPhraseShield") == "disposable-emulator")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Requires a disposable app without wallet data", location.hasAnyData)
        var activity: Activity? = null
        var model: AppModel? = null
        // Home, not the battery prompt or the notifications gate (a fresh install on API 33+ starts denied), follows creation.
        shell("cmd deviceidle whitelist +${context.packageName}")
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        try {
            val first = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = first
            val app = withContext(Dispatchers.Main) { loadAppModel(first) }
            model = app
            waitFor("Onboarding") { app.launchState == LaunchState.onboarding }
            withContext(Dispatchers.Main) { app.create() }
            // finish() marks onboarding done before it starts server services, which may wait on the network.
            CoroutineScope(Dispatchers.Main).launch { runCatching { app.finish() } }
            click("Home's Settings") { it.contentDescription?.toString() == "Settings" }
            assertFalse("App lock is off, so only the page secures the window", app.walletLock.enabled)
            assertFalse("Home isn't secure", secure(first))
            click("Recovery phrase row") { it.text?.toString() == "Recovery phrase" }
            waitFor("The page secures the window") { secure(first) }
            // No screen lock on this emulator: the page offers its warning first. Cancel; never "Show anyway".
            click("No screen lock warning") { it.text?.toString() == "Cancel" }
            waitFor("Warning dismissed") { text("No screen lock") == null }
            val sample = "sample phrase only never use this example test word copy nothing real".split(' ')
            lateinit var revealed: RevealedPhrase
            // Stand-in for a successful authentication in this generation, as SettingsDeviceTest does.
            instrumentation.runOnMainSync { revealed = ViewModelProvider(first)[RevealedPhrase::class.java]; revealed.show(sample, app.walletLock.generation) }
            waitFor("Sample words shown") { text("sample") != null }
            SecureWindowSampler(instrumentation).use { sampler ->
                instrumentation.runOnMainSync { first.recreate() }
                waitFor("Recreated") { resumed().let { it != null && it !== first } }
                activity = resumed()
                // The accepted behaviour stays: the rotation hides the words, and Show asks again.
                waitFor("Words hidden after the rotation") { revealed.words.isEmpty() && text("sample") == null }
                Thread.sleep(2_000)
                assertTrue("Still on the page", secure(checkNotNull(activity)))
                assertTrue("Never unshielded: ${sampler.gaps.get()} of ${sampler.samples.get()} samples", sampler.gaps.get() == 0)
                assertTrue("Sampled throughout: ${sampler.samples.get()}", sampler.samples.get() > 50)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("phraseShieldSamples", "${sampler.samples.get()}") })
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            runCatching { model?.services?.lifecycle?.delete() }
            runCatching { shell("cmd deviceidle whitelist -${context.packageName}") }
            SettingsStorage(context).removeAll()
            location.directory.deleteRecursively(); location.restoreDirectory.deleteRecursively()
        }
    }
}
