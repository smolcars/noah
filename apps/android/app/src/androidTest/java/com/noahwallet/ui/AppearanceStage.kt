package com.noahwallet.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.noahwallet.MainActivity
import com.noahwallet.localization.LocalizedContent
import com.noahwallet.ui.home.HomeFixtures
import com.noahwallet.ui.home.HomePreviewHost
import com.noahwallet.ui.settings.SettingsDestination
import com.noahwallet.ui.settings.SettingsHost
import com.noahwallet.ui.settings.SettingsPreview

/**
 * The real MainActivity with Settings (or Home from public fixtures) in place of its own content, themed by its model's
 * Appearance and hosted as LaunchWindow hosts the app, for AppearanceDeviceTest and AppearanceGalleryTest. No wallet.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class AppearanceStage(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    lateinit var activity: MainActivity
    lateinit var model: AppModel
    lateinit var stack: NavBackStack<NavKey>
    val started get() = ::model.isInitialized
    var home by mutableStateOf(false)
    var fold by mutableStateOf<WindowRegion?>(null)
    /** A narrow window (compact Settings) on a wide device; null fills the window. */
    var width by mutableStateOf<Dp?>(null)
    /** The root sheet Settings opens with. */
    var sheet: String? = null

    fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).let { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }
    fun phone(dark: Boolean) { shell("cmd uimode night ${if (dark) "yes" else "no"}") }
    fun night(configuration: Configuration) = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Starts MainActivity on Automatic, then shows Settings (or Home) in place of its own content. */
    fun launch() {
        activity = start()
        model = loadedModel(activity)
        instrumentation.runOnMainSync { model.chooseAppearance(Appearance.automatic) }
        instrumentation.runOnMainSync {
            activity.setContent { LocalizedContent(model.localeIdentifier) { NoahTheme(model.appearance) {
                WindowFollowsTheme()
                CompositionLocalProvider(LocalFoldingFeatures provides listOfNotNull(fold)) {
                    Box(Modifier.semantics { testTagsAsResourceId = true }.then(width?.let { Modifier.fillMaxHeight().width(it) } ?: Modifier.fillMaxSize())) {
                        if (home) HomePreviewHost(HomeFixtures.ui("everyday"))
                        else Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                            stack = rememberNavBackStack(SettingsDestination.Settings)
                            SettingsHost(model, stack, close = {}, myCode = {}, preview = sheet?.let { SettingsPreview(it) })
                        }
                    }
                }
            } } }
        }
    }

    fun start() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity

    /** [activity]'s model, once MainActivity has set its own content (which would otherwise replace a test's). */
    fun loadedModel(activity: MainActivity): AppModel {
        var loaded: AppModel? = null
        val deadline = System.currentTimeMillis() + 30_000
        while (loaded == null && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                if (content.childCount > 0 && content.getChildAt(0) is ComposeView)
                    loaded = runCatching { ViewModelProvider(activity)[AppModel::class.java] }.getOrNull()
            }
            if (loaded == null) Thread.sleep(100)
        }
        return checkNotNull(loaded) { "MainActivity never loaded its model" }
    }

    fun resumed(): List<Activity> {
        var activities = emptyList<Activity>()
        instrumentation.runOnMainSync { activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList() }
        return activities
    }

    /** The screen's commonest colour on a 24 × 24 grid: the background, in any layout (a wide Home has a rail at its edge). */
    fun background(): Int {
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val samples = (0 until 24 * 24).map { shot.getPixel((it % 24 * 2 + 1) * shot.width / 48, (it / 24 * 2 + 1) * shot.height / 48) }
        shot.recycle()
        return samples.groupingBy { it }.eachCount().maxBy { it.value }.key
    }

    /** Back to Automatic, a light phone and animations on, and MainActivity closed. */
    fun reset() {
        if (started) instrumentation.runOnMainSync { model.chooseAppearance(Appearance.automatic) }
        phone(dark = false)
        shell("settings put global animator_duration_scale 1")
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
    }
}
