package com.noahwallet.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Onboarding takes the whole window and splits it at a separating fold itself: no text, field or button
 * may straddle the hinge. Synthetic folds make this independent of the device's posture.
 */
class OnboardingFoldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(step: String, stage: com.noahwallet.ui.onboarding.NotificationStage = com.noahwallet.ui.onboarding.NotificationStage.ask,
        words: List<String>? = null, hinge: (Int, Int) -> WindowRegion): WindowRegion {
        compose.mainClock.autoAdvance = false
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        lateinit var model: AppModel
        lateinit var fold: WindowRegion
        compose.activityRule.scenario.onActivity { activity ->
            model = AppModel(app)
            model.step = step
            androidx.lifecycle.ViewModelProvider(activity)[com.noahwallet.ui.onboarding.OnboardingPresentation::class.java].notificationStage = stage
            // Public BIP-39 test vectors only.
            words?.let { androidx.lifecycle.ViewModelProvider(activity)[RecoveryPhrasePresentation::class.java].input.update(it.joinToString(" "), 0) }
            val bounds = activity.window.decorView
            fold = hinge(bounds.width, bounds.height)
            // Host it as MainActivity and LaunchGateView do: adjustResize and the safe-drawing insets (keyboard included).
            @Suppress("DEPRECATION")
            activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            activity.setContent { NoahTheme { CompositionLocalProvider(LocalFoldingFeatures provides listOf(fold)) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) { Onboarding(model) }
            } } }
        }
        // The tree and the caret animate forever; a paused clock lets layout settle without waiting for idleness.
        compose.mainClock.advanceTimeBy(3_000)
        return fold
    }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { all += node; node.children.forEach(::walk) }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        // Text, controls, fields, and the recovery word cells (platform fields tagged restore-word-N).
        return all.filter {
            it.config.getOrNull(SemanticsProperties.Text) != null || it.config.getOrNull(SemanticsActions.OnClick) != null ||
                it.config.getOrNull(SemanticsProperties.EditableText) != null ||
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("restore-word-") == true
        }
    }

    private fun assertClear(step: String, fold: WindowRegion) {
        val vertical = fold.height > fold.width
        if (InstrumentationRegistry.getArguments().getString("foldShots") != null) {
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "fold-$step-${if (vertical) "v" else "h"}.png")
                .outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        for (node in nodes()) {
            val bounds: Rect = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            val label = "$step: $text $bounds $fold"
            if (vertical) assertFalse("crosses the vertical fold: $label", bounds.left < fold.left && bounds.right > fold.right)
            else assertFalse("crosses the horizontal fold: $label", bounds.top < fold.top && bounds.bottom > fold.bottom)
        }
    }

    @Test fun bookPostureSplitsAtTheHingeWithHeadlineLeadingAndControlsTrailing() {
        for (step in listOf("name", "email", "done", "notifications", "storedSeed")) {
            val fold = show(step) { width, height -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
            assertClear(step, fold)
        }
        // UnifiedPush distributor setup (devices without Google Play Services) stays in the trailing pane.
        val vertical = show("notifications", com.noahwallet.ui.onboarding.NotificationStage.distributor) { width, height ->
            WindowRegion(width / 2 - 12, 0, width / 2 + 12, height)
        }
        assertClear("distributor", vertical)
        compose.onNodeWithText("Select Distributor", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.let {
            assertTrue("distributor setup trails: $it", it.left >= vertical.right)
        }
        val fold = show("name") { width, height -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
        val headline = compose.onNodeWithText("Pick a name", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val button = compose.onNodeWithText("Continue", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        assertTrue("headline leads: $headline", headline.right <= fold.left)
        assertTrue("controls trail: $button", button.left >= fold.right)
        // Stored-phrase recovery: the headline leads; Restore Wallet and Delete Wallet trail.
        val stored = show("storedSeed") { width, height -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
        compose.onNodeWithText("Recovery phrase found", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.let {
            assertTrue("stored-phrase headline leads: $it", it.right <= stored.left)
        }
        for (title in listOf("Restore Wallet", "Delete Wallet")) compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.let {
            assertTrue("$title trails: $it", it.left >= stored.right)
        }
    }

    @Test fun tabletopPutsTheHeadlineAboveAndControlsBelow() {
        for (step in listOf("name", "email", "done", "notifications", "storedSeed")) {
            val fold = show(step) { width, height -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }
            assertClear(step, fold)
        }
        val horizontal = show("notifications", com.noahwallet.ui.onboarding.NotificationStage.distributor) { width, height ->
            WindowRegion(0, height / 2 - 12, width, height / 2 + 12)
        }
        assertClear("distributor", horizontal)
        compose.onNodeWithText("Select Distributor", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.let {
            assertTrue("distributor setup below the fold: $it", it.top >= horizontal.bottom)
        }
        val fold = show("name") { width, height -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }
        val headline = compose.onNodeWithText("Pick a name", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val button = compose.onNodeWithText("Continue", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        assertTrue("headline above: $headline", headline.bottom <= fold.top)
        assertTrue("controls below: $button", button.top >= fold.bottom)
        val stored = show("storedSeed") { width, height -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }
        // Name left the keyboard up, which lifts tabletop controls above the fold; this screen has no field.
        compose.activityRule.scenario.onActivity {
            it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
        }
        Thread.sleep(1_500)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Recovery phrase found", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.let {
            assertTrue("stored-phrase headline above: $it", it.bottom <= stored.top)
        }
        for (title in listOf("Restore Wallet", "Delete Wallet")) compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.let {
            assertTrue("$title below: $it", it.top >= stored.bottom)
        }
    }

    @Test fun tabletopScrollsOnlyThePaneBelowTheFold() {
        val horizontal = { width: Int, height: Int -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }
        for (words in listOf(List(11) { "abandon" } + "about", List(23) { "abandon" } + "art")) {
            // Leaving Restore clears its words by design; let any previous Restore go before entering these.
            show("name", hinge = horizontal)
            val fold = show("restore", words = words, hinge = horizontal)
            // Measure the keyboard-hidden layout (Name may have opened the keyboard).
            compose.activityRule.scenario.onActivity {
                it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
            }
            Thread.sleep(1_500)
            compose.mainClock.advanceTimeBy(1_000)
            assertEquals(words.size, compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("word cell") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("restore-word-") == true
            }, useUnmergedTree = true).fetchSemanticsNodes().size)
            val label = "restore-${words.size}"
            assertNoneOverlap("$label top", fold)
            val headline = compose.onNodeWithText("Welcome back", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            // Scroll the lower pane to its end, as reaching Restore needs with 24 words. Restore has no endless
            // Compose animation, so the clock can run while the scroll animates.
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("Restore", useUnmergedTree = true).performScrollTo()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            assertNoneOverlap("$label end", fold)
            val after = compose.onNodeWithText("Welcome back", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            assertEquals("$label: the headline stays fixed above the fold", headline, after)
            assertTrue("$label: headline above the fold", after.bottom <= fold.top)
            val restore = compose.onNodeWithText("Restore", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            assertTrue("$label: Restore reachable below the fold: $restore", restore.top >= fold.bottom && restore.height > 0f)
            val lastWord = compose.onNodeWithTag("restore-word-${words.size}").fetchSemanticsNode().boundsInWindow
            assertTrue("$label: last word below the fold: $lastWord", lastWord.height <= 0f || lastWord.top >= fold.bottom)
        }
    }

    /** No field, word cell, button or text may overlap the hinge band at all. */
    private fun assertNoneOverlap(step: String, fold: WindowRegion) {
        for (node in nodes()) {
            val bounds: Rect = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            assertFalse("$step overlaps the fold: ${node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.TestTag)} $bounds $fold",
                bounds.top < fold.bottom && bounds.bottom > fold.top)
        }
    }
}
