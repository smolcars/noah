package com.noahwallet.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in Appearance tools in the real MainActivity, at real speed (no Compose test rule, which would own the frame
 * clock): screenshots of the sheet and Home for appearance.md's fixtures, and a hold for recordings driven by adb. No
 * wallet: Settings and Home come from public fixtures.
 */
class AppearanceGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val stage = AppearanceStage(instrumentation)

    @After fun reset() = stage.reset()

    /**
     * `-e noahAppearanceShots <label>`: the sheet under a light and a dark phone with each choice selected, then Home
     * likewise, in the device's posture. Pull `files/appearance-shots/<label>` with `run-as`.
     */
    @Test fun captureSheetsAndHome() {
        val label = args.getString("noahAppearanceShots")
        assumeTrue(label != null)
        val folder = File(instrumentation.targetContext.filesDir, "appearance-shots/$label").apply { deleteRecursively(); mkdirs() }
        fun save(name: String) {
            Thread.sleep(1_200)
            val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(folder, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
        }
        for (home in listOf(false, true)) {
            stage.home = home
            stage.sheet = "appearance".takeIf { !home }
            stage.launch()
            for (phoneDark in listOf(false, true)) {
                stage.phone(phoneDark)
                for (choice in Appearance.entries) {
                    instrumentation.runOnMainSync { stage.model.chooseAppearance(choice) }
                    save("${if (home) "home" else "sheet"}-phone-${if (phoneDark) "dark" else "light"}-${choice.name}")
                }
            }
            instrumentation.runOnMainSync { stage.activity.finish() }
        }
    }

    /** `-e noahAppearanceHold <seconds>` (and `-e noahAppearanceHome true` for Home): stays on screen for adb or a person. */
    @Test fun hold() {
        val seconds = args.getString("noahAppearanceHold")?.toLongOrNull()
        assumeTrue(seconds != null)
        stage.home = args.getString("noahAppearanceHome") == "true"
        stage.launch()
        Thread.sleep(seconds!! * 1_000)
    }
}
