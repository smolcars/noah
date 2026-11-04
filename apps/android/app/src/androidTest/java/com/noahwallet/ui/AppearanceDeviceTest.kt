package com.noahwallet.ui

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.SettingsStorage
import com.noahwallet.ui.settings.SettingsDestination
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Appearance on a device (docs/native-rewrite/design/appearance.md, Tests), inside the real MainActivity so its own uiMode
 * handling, the platform night mode and the system bars are what's tested: a pick saves, re-themes in place and keeps the
 * sheet open; Dark under a light phone draws Home black; Automatic follows the phone; a pick during a reveal waits; fold
 * poses keep the sheet and the choice; a relaunch starts dark; stores from before the setting open on Automatic. Home is
 * public fixtures and no wallet is used. Each test leaves Automatic, a light phone and animations on.
 */
class AppearanceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val stage = AppearanceStage(instrumentation)
    private var activity by stage::activity
    private var model by stage::model
    private val stack get() = stage.stack
    private var home by stage::home
    private var fold by stage::fold
    private var width by stage::width
    private fun phone(dark: Boolean) = stage.phone(dark)
    private fun night(configuration: Configuration) = stage.night(configuration)
    private fun resumed() = stage.resumed()
    private fun background() = stage.background()

    private fun launch() { stage.launch(); compose.waitForIdle() }

    @After fun reset() = stage.reset()

    private fun openSheet() {
        compose.onNodeWithTag("settings-appearance").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("appearance-sheet").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun pick(choice: Appearance) {
        compose.onNodeWithTag("appearance-${choice.name}").performClick()
        compose.waitUntil(5_000) { model.appearance == choice }
        // The reveal (560 ms at 1x) ends before the next pick.
        Thread.sleep(1_000)
        compose.waitForIdle()
    }
    private fun hint(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag("appearance-hint") and hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun assertSelected(choice: Appearance) {
        for (each in Appearance.entries) compose.onNodeWithTag("appearance-${each.name}").assert(if (each == choice) isSelected() else isNotSelected())
    }
    @Test fun aPickSavesReThemesInPlaceAndKeepsTheSheetOpen() {
        phone(dark = false)
        launch()
        openSheet()
        assertSelected(Appearance.automatic)
        hint("Now light · follows your phone")
        for ((choice, line) in listOf(Appearance.dark to "Always dark", Appearance.light to "Always light",
            Appearance.automatic to "Now light · follows your phone")) {
            pick(choice)
            assertEquals(choice.name, model.settings.string(Appearance.key))
            compose.onNodeWithTag("appearance-sheet").assertExists()
            assertSelected(choice)
            hint(line)
            // Re-themed in place: the same activity is still in front, with the same Settings stack.
            assertEquals(listOf(activity), resumed())
            assertEquals(listOf<NavKey>(SettingsDestination.Settings), stack.toList())
            if (Build.VERSION.SDK_INT >= 31) compose.waitUntil(5_000) { night(activity.resources.configuration) == (choice == Appearance.dark) }
        }
    }

    @Test fun darkOnALightPhoneDrawsHomeBlackWithLightBarIcons() {
        phone(dark = false)
        home = true
        launch()
        instrumentation.runOnMainSync { model.chooseAppearance(Appearance.dark) }
        compose.waitForIdle(); Thread.sleep(1_000)
        assertEquals(0xFF000000.toInt(), background())
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        assertFalse("light icons on black", bars.isAppearanceLightStatusBars)
        // And Light on a dark phone: white, with dark icons.
        phone(dark = true)
        instrumentation.runOnMainSync { model.chooseAppearance(Appearance.light) }
        compose.waitForIdle(); Thread.sleep(1_000)
        assertEquals(0xFFFFFFFF.toInt(), background())
        assertTrue("dark icons on white", bars.isAppearanceLightStatusBars)
        assertEquals(listOf(activity), resumed())
    }

    @Test fun automaticFollowsThePhoneWithoutRecreating() {
        phone(dark = false)
        launch()
        openSheet()
        phone(dark = true)
        hint("Now dark · follows your phone")
        phone(dark = false)
        hint("Now light · follows your phone")
        compose.onNodeWithTag("appearance-sheet").assertExists()
        assertEquals(listOf(activity), resumed())
    }

    @Test fun aPickDuringARevealIsIgnored() {
        phone(dark = false)
        launch()
        openSheet()
        // Dark starts a reveal; Light, tapped straight after (in the same main-thread turn), lands while it runs and is ignored.
        val taps = listOf("appearance-dark", "appearance-light").map { compose.onNodeWithTag(it).fetchSemanticsNode() }
        instrumentation.runOnMainSync { for (tile in taps) tile.config[SemanticsActions.OnClick].action?.invoke() }
        compose.waitUntil(5_000) { model.appearance == Appearance.dark }
        Thread.sleep(1_500)
        assertEquals(Appearance.dark, model.appearance)
        assertSelected(Appearance.dark)
        // Once it's done, the next pick goes through.
        pick(Appearance.light)
        assertSelected(Appearance.light)
    }

    @Test fun withAnimationsOffEveryPickChangesAtOnce() {
        stage.shell("settings put global animator_duration_scale 0")
        phone(dark = false)
        launch()
        openSheet()
        compose.onNodeWithTag("appearance-dark").performClick()
        compose.onNodeWithTag("appearance-light").performClick()
        compose.waitUntil(5_000) { model.appearance == Appearance.light }
        assertSelected(Appearance.light)
        hint("Always light")
    }

    @Test fun foldPosesKeepTheSheetAndTheChoice() {
        phone(dark = false)
        width = 400.dp
        launch()
        openSheet()
        pick(Appearance.dark)
        val view = activity.window.decorView
        fun pose(hinge: WindowRegion?) { compose.runOnIdle { fold = hinge }; compose.waitForIdle() }
        fun stillThere() {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("appearance-sheet").fetchSemanticsNodes().isNotEmpty() }
            assertSelected(Appearance.dark)
            hint("Always dark")
            // The three tiles stay in one row.
            val tops = Appearance.entries.map { compose.onNodeWithTag("appearance-${it.name}").fetchSemanticsNode().boundsInWindow.top }
            assertEquals(1, tops.distinct().size)
        }
        pose(WindowRegion(0, view.height / 2 - 12, view.width, view.height / 2 + 12)) // tabletop
        stillThere()
        compose.runOnIdle { width = null }
        pose(WindowRegion(view.width / 2 - 12, 0, view.width / 2 + 12, view.height)) // book: the sheet in the trailing pane
        stillThere()
        assertTrue(compose.onNodeWithTag("appearance-automatic").fetchSemanticsNode().boundsInWindow.left >= view.width / 2 + 12)
        compose.runOnIdle { width = 400.dp }
        pose(null)
        stillThere()
        assertEquals(Appearance.dark, model.appearance)
    }

    private fun assertOldStoresOpenOnAutomatic(parent: File) {
        val noBackup = File(parent, "no-backup").apply { check(mkdir()) }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = parent
            override fun getNoBackupFilesDir() = noBackup
        }
        val root = File(parent, "mmkv").apply { check(mkdir()) }
        for (name in listOf(SettingsStorage.instanceID, "${SettingsStorage.instanceID}.crc"))
            instrumentation.context.assets.open("rn-mmkv/$name").use { input -> File(root, name).outputStream().use { input.copyTo(it) } }
        val migrated = SettingsStorage(isolated)
        assertNull(migrated.string(Appearance.key))
        // The same store reopened is now a native one that has never had the key.
        for (settings in listOf(migrated, SettingsStorage(isolated))) {
            var appearance: Appearance? = null
            instrumentation.runOnMainSync { appearance = AppModel(context.applicationContext as Application, settings).appearance }
            assertEquals(Appearance.automatic, appearance)
        }
    }

    @Test fun aRelaunchStartsDark() {
        phone(dark = false)
        launch()
        instrumentation.runOnMainSync { model.chooseAppearance(Appearance.dark) }
        compose.waitForIdle()
        instrumentation.runOnMainSync { activity.finish() }
        compose.waitUntil(5_000) { resumed().isEmpty() }
        val relaunched = stage.start()
        activity = relaunched
        // From API 31 Android applies the saved night mode before Noah's code runs, so the first frame is already dark.
        if (Build.VERSION.SDK_INT >= 31) assertTrue(night(relaunched.resources.configuration))
        model = stage.loadedModel(relaunched)
        assertEquals(Appearance.dark, model.appearance)
        // The app's own launch screens (no wallet here) animate without end: wait by the clock. Every one is dark.
        Thread.sleep(2_000)
        val pixel = background()
        assertTrue("dark launch: ${Integer.toHexString(pixel)}", listOf(16, 8, 0).all { (pixel shr it and 0xFF) < 0x20 })
    }

    /** RN's real MMKV store (imported) and a native store from before the setting both open on Automatic, and Home follows the phone. */
    @Test fun storesFromBeforeTheSettingOpenOnAutomatic() {
        val parent = File(context.cacheDir, "appearance-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { assertOldStoresOpenOnAutomatic(parent) } finally { check(parent.deleteRecursively()) }
        // On Automatic, Home is drawn in the phone's mode.
        phone(dark = true)
        home = true
        launch()
        compose.waitForIdle(); Thread.sleep(1_000)
        assertEquals(0xFF000000.toInt(), background())
        phone(dark = false)
        compose.waitForIdle(); Thread.sleep(1_000)
        assertEquals(0xFFFFFFFF.toInt(), background())
    }
}
