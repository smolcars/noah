package com.noahwallet.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.File

/**
 * Opt-in captures of "Recovery phrase found" on the current device, posture and theme (`-e noahStoredSeedShots <label>`):
 * suspended, idle, restoring and a failed restore. It is reached as `RestoreUIDeviceTest` does, with a stored phrase and
 * no wallet data, on a disposable regtest emulator. The phrase is the public BIP-39 test vector and the screen never
 * shows it. Build with `-PnoahRegtestServerURL` at a host port that accepts and never answers, so Restore holds, then
 * fails. The screen keeps FLAG_SECURE, so captures are drawn from the window's own views.
 */
class StoredSeedShotsTest {
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

    @Test fun captureStoredSeedStates() = runBlocking<Unit> {
        val label = InstrumentationRegistry.getArguments().getString("noahStoredSeedShots")
        assumeTrue(label != null)
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Requires a disposable app without wallet data", location.hasAnyData)
        val folder = File(context.filesDir, "stored-seed-shots/$label").apply { deleteRecursively(); mkdirs() }
        val seeds = SeedStorage(context, "regtest")
        val settings = SettingsStorage(context)
        var activity: MainActivity? = null
        suspend fun launch(): AppModel {
            val started = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = started
            // MainActivity builds its model off the main thread; wait for that same instance.
            val model = withContext(Dispatchers.Main) { loadAppModel(started) }
            waitFor("Stored phrase without wallet data") { model.launchState == LaunchState.seedWithoutWalletData }
            waitFor("Recovery screen shown") { find(automation.rootInActiveWindow) { it.text?.toString() == "Recovery phrase found" } != null }
            return model
        }
        fun capture(name: String, settle: Long = 1500) {
            Thread.sleep(settle)
            lateinit var bitmap: Bitmap
            instrumentation.runOnMainSync {
                val view = activity!!.window.decorView
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
            }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            // Public BIP-39 test vector only.
            assertTrue(seeds.save(List(11) { "abandon" }.plus("about").joinToString(" ")) is SecretResult.Found)
            settings.setPersistedState(PersistedWalletState(isInitialized = true, isWalletSuspended = true), PersistedWalletState.key, 0)
            var model = launch()
            assertTrue(model.isWalletSuspended)
            capture("suspended")
            instrumentation.runOnMainSync { activity!!.finish() }
            Thread.sleep(1000)
            settings.removeAll()
            model = launch()
            waitFor("Restore offered") { model.canRestoreWallet && button("Restore Wallet") != null }
            capture("idle")
            assertTrue(checkNotNull(button("Restore Wallet")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Restore running") { model.restore.running }
            capture("restoring", 2600)
            waitFor("The silent server fails the restore", 120_000) { model.restore.issue != null && !model.busy }
            // A failure returns to Recovery phrase found with its error, and Restore Wallet is offered again.
            val message = checkNotNull(model.restore.issue).message
            waitFor("Back on Recovery phrase found with the error and Restore Wallet enabled") {
                text("Recovery phrase found") != null && text(message) != null && button("Restore Wallet") != null
            }
            assertEquals("storedSeed", model.step)
            capture("error")
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            seeds.deleteWalletSeed(); settings.removeAll()
            location.directory.deleteRecursively(); location.restoreDirectory.deleteRecursively()
        }
    }
}
