package com.noahwallet.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in visual gallery of every onboarding step on the current device, posture and theme
 * (`-e noahOnboardingShots <label>`). Restore keeps FLAG_SECURE, so it is drawn from the window's own
 * views with only the public BIP-39 test vector entered; no wallet is created or restored.
 */
class OnboardingGalleryTest {
    @Test fun captureEveryStep() {
        val label = InstrumentationRegistry.getArguments().getString("noahOnboardingShots")
        assumeTrue(label != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val folder = File(instrumentation.targetContext.filesDir, "onboarding-shots/$label").apply { deleteRecursively(); mkdirs() }
        InstrumentationRegistry.getArguments().getString("rotation")?.toInt()?.let { instrumentation.uiAutomation.setRotation(it); Thread.sleep(800) }
        // The tree animates continuously, so this drives the activity directly instead of waiting for Compose idleness.
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var model: AppModel
            lateinit var activity: ComponentActivity
            scenario.onActivity {
                activity = it
                model = AppModel(app)
                // As LaunchGateView hosts it: inside the safe-drawing insets, on the redesign background.
                it.setContent { NoahTheme { androidx.compose.material3.Surface(color = com.noahwallet.ui.onboarding.noahPalette().background) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                        .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) { Onboarding(model) }
                } } }
            }
            fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
            fun save(name: String, bitmap: Bitmap) {
                File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            fun screen(name: String, settle: Long = 1500) { Thread.sleep(settle); save(name, checkNotNull(instrumentation.uiAutomation.takeScreenshot())) }
            /** FLAG_SECURE screens: draw the window's own views instead of taking a screenshot. */
            fun secure(name: String, settle: Long = 1500) {
                Thread.sleep(settle)
                lateinit var bitmap: Bitmap
                ui {
                    val view = activity.window.decorView
                    bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(bitmap))
                }
                save(name, bitmap)
            }
            fun step(step: String) = ui { model.step = step }
            val presentation by lazy { ViewModelProvider(activity)[com.noahwallet.ui.onboarding.OnboardingPresentation::class.java] }

            step("welcome"); screen("welcome", 3000)
            ui { presentation.languageOpen = true }; screen("language")
            ui { presentation.languageOpen = false; presentation.betaOpen = true }; screen("beta")
            ui { presentation.betaOpen = false }
            step("creating"); screen("creating", 2600)
            /** The same screen with the keyboard down, to check both panes share one centre. */
            fun keyboardDown(name: String) {
                ui {
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                screen(name)
            }
            step("name"); ui { presentation.name = "maya" }; screen("name-keyboard", 2500); keyboardDown("name")
            step("email"); ui { presentation.email = "maya@gmial.com" }; screen("email-keyboard"); keyboardDown("email")
            ui {
                presentation.email = "maya@gmail.com"; presentation.codeSentAt = android.os.SystemClock.elapsedRealtime()
                presentation.otp = com.noahwallet.ui.onboarding.OtpEntry().typed("482")
                presentation.emailStage = com.noahwallet.ui.onboarding.EmailStage.code
            }
            screen("email-code-keyboard"); keyboardDown("email-code")
            ui { presentation.emailStage = com.noahwallet.ui.onboarding.EmailStage.entry }
            step("notifications"); screen("notifications", 3000)
            ui { presentation.notificationStage = com.noahwallet.ui.onboarding.NotificationStage.off }; screen("notifications-required")
            ui { presentation.notificationStage = com.noahwallet.ui.onboarding.NotificationStage.distributor }; screen("notifications-unifiedpush")
            step("battery"); screen("battery")
            step("done"); screen("done", 3000)
            /** Scrolls the restore form to its end (in tabletop, the pane below the fold). */
            fun scrollToEnd() = repeat(3) {
                val view = activity.window.decorView
                instrumentation.uiAutomation.executeShellCommand("input swipe ${view.width / 2} ${(view.height * .92).toInt()} " +
                    "${view.width / 2} ${(view.height * .6).toInt()} 300").close()
                Thread.sleep(700)
            }
            val input = ViewModelProvider(activity)[RecoveryPhrasePresentation::class.java].input
            // Public BIP-39 test vectors only.
            ui { input.update(List(11) { "abandon" }.plus("about").joinToString(" "), 0) }
            step("restore"); secure("restore"); scrollToEnd(); secure("restore-end", 600)
            step("welcome"); Thread.sleep(600)
            ui { input.update(List(23) { "abandon" }.plus("art").joinToString(" "), 0) }
            step("restore"); secure("restore-24"); scrollToEnd(); secure("restore-24-end", 600)
            step("restoring"); secure("restoring", 2600)
        }
    }
}
