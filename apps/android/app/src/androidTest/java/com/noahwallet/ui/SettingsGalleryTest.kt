package com.noahwallet.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.MainActivity
import com.noahwallet.localization.LocalizedContent
import com.noahwallet.ui.settings.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in screenshot gallery of Settings (`-e noahSettingsShots <label>`) on an owned, disposable regtest AVD whose
 * throwaway wallet is open: the list, every page and the sheets, in the device's current posture and theme. The
 * recovery phrase is only ever the public sample; the live phrase page is never captured. Pull
 * `files/settings-shots/<label>` with `run-as`. `-e noahSettingsOnly a,b` limits the scenes.
 */
@RunWith(AndroidJUnit4::class)
class SettingsGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext

    private class Scene(val name: String, val page: NavKey, val preview: SettingsPreview? = null, val scroll: Boolean = false)

    @Test fun captureSettings() {
        val label = args.getString("noahSettingsShots")
        assumeTrue(label != null)
        val only = args.getString("noahSettingsOnly")?.split(',')
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var loaded: AppModel? = null
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { loaded = runCatching { ViewModelProvider(activity)[AppModel::class.java] }.getOrNull() }
            if (loaded?.launchState != null) break
            Thread.sleep(100)
        }
        val model = checkNotNull(loaded)
        instrumentation.runOnMainSync { model.start() }
        val opened = System.currentTimeMillis() + 30_000
        while (!model.open && System.currentTimeMillis() < opened) Thread.sleep(100)
        assertTrue("The gallery needs the disposable wallet open", model.open)
        Thread.sleep(3_000)
        val folder = File(context.filesDir, "settings-shots/$label").apply { deleteRecursively(); mkdirs() }
        val sample = "sample phrase only never use this example test word copy nothing real".split(' ')
        val vtxo = runCatching { kotlinx.coroutines.runBlocking { model.session.onchainOverview().vtxos.firstOrNull()?.id } }.getOrNull()
        val schedule = com.noahwallet.recurring.RecurringRuntime.shared(context).executor.let { executor ->
            kotlinx.coroutines.runBlocking { executor.reload() }; executor.schedules.firstOrNull()?.id
        }
        val scenes = listOfNotNull(
            Scene("root", SettingsDestination.Settings), Scene("root-end", SettingsDestination.Settings, scroll = true),
            Scene("profile", SettingsDestination.Profile), Scene("lightning", SettingsDestination.Lightning), Scene("email", SettingsDestination.Email),
            Scene("phrase-gated", SettingsDestination.Seed, SettingsPreview(phrase = emptyList())),
            Scene("phrase-shown", SettingsDestination.Seed, SettingsPreview(phrase = sample)),
            Scene("backup", SettingsDestination.Backup), Scene("backup-export", SettingsDestination.Backup, SettingsPreview("export")),
            Scene("vtxos", SettingsDestination.Vtxos), vtxo?.let { Scene("vtxo", VtxoPage(it)) },
            Scene("onchain", SettingsDestination.Board), Scene("recurring", SettingsDestination.Recurring),
            schedule?.let { Scene("schedule", SchedulePage(it)) }, Scene("editor", EditorPage(null)),
            Scene("exit", SettingsDestination.Exits), Scene("notifications", SettingsDestination.Notifications),
            Scene("notifications-unifiedpush", SettingsDestination.Notifications, SettingsPreview(unifiedPush = true)),
            Scene("unifiedpush", SettingsDestination.UnifiedPush),
            Scene("advanced", SettingsDestination.Advanced), Scene("advanced-suspend", SettingsDestination.Advanced, SettingsPreview("suspend")),
            Scene("advanced-reset", SettingsDestination.Advanced, SettingsPreview("reset")),
            Scene("ark", SettingsDestination.Ark), Scene("esplora", SettingsDestination.Esplora), Scene("logs", SettingsDestination.Logs),
            Scene("debug", SettingsDestination.Debug), Scene("feedback", SettingsDestination.Feedback), Scene("about", SettingsDestination.About),
            Scene("unit", SettingsDestination.Settings, SettingsPreview("unit")), Scene("currency", SettingsDestination.Settings, SettingsPreview("currency")),
            Scene("language", SettingsDestination.Settings, SettingsPreview("language")), Scene("delete", SettingsDestination.Settings, SettingsPreview("delete")),
        ).filter { only == null || it.name in only }
        try {
            for (scene in scenes) {
                val ready = java.util.concurrent.CountDownLatch(1)
                instrumentation.runOnMainSync {
                    activity.setContent { LocalizedContent(model.localeIdentifier) { NoahTheme {
                        key(scene.name) {
                            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                val stack = rememberNavBackStack(*SettingsRoutes.chain(scene.page).toTypedArray())
                                SettingsHost(model, stack, {}, {}, scene.preview)
                                SideEffect { ready.countDown() }
                            }
                        }
                    } } }
                }
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
                Thread.sleep(2_500)
                if (scene.scroll) {
                    val size = android.graphics.Point().also { activity.windowManager.defaultDisplay.getRealSize(it) }
                    repeat(3) { instrumentation.uiAutomation.executeShellCommand("input swipe ${size.x / 2} ${size.y * 8 / 10} ${size.x / 2} ${size.y / 10} 250").close(); Thread.sleep(700) }
                    Thread.sleep(800)
                }
                instrumentation.waitForIdleSync()
                assertEquals("Never capture a secure screen", 0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(folder, "${scene.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
