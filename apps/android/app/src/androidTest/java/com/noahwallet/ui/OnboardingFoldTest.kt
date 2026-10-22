package com.noahwallet.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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

    private fun show(step: String, hinge: (Int, Int) -> WindowRegion): WindowRegion {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        lateinit var model: AppModel
        lateinit var fold: WindowRegion
        compose.activityRule.scenario.onActivity { activity ->
            model = AppModel(app)
            model.step = step
            val bounds = activity.window.decorView
            fold = hinge(bounds.width, bounds.height)
            activity.setContent { NoahTheme { CompositionLocalProvider(LocalFoldingFeatures provides listOf(fold)) {
                Box(Modifier.fillMaxSize()) { Onboarding(model) }
            } } }
        }
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
        return fold
    }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { all += node; node.children.forEach(::walk) }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return all.filter {
            it.config.getOrNull(SemanticsProperties.Text) != null || it.config.getOrNull(SemanticsActions.OnClick) != null ||
                it.config.getOrNull(SemanticsProperties.EditableText) != null
        }
    }

    private fun assertClear(step: String, fold: WindowRegion) {
        val vertical = fold.height > fold.width
        for (node in nodes()) {
            val bounds: Rect = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val label = "$step: ${node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()} $bounds $fold"
            if (vertical) assertFalse("crosses the vertical fold: $label", bounds.left < fold.left && bounds.right > fold.right)
            else assertFalse("crosses the horizontal fold: $label", bounds.top < fold.top && bounds.bottom > fold.bottom)
        }
    }

    @Test fun bookPostureSplitsAtTheHingeWithHeadlineLeadingAndControlsTrailing() {
        for (step in listOf("name", "email", "done", "notifications")) {
            val fold = show(step) { width, height -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
            assertClear(step, fold)
        }
        val fold = show("name") { width, height -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
        val headline = compose.onNodeWithText("Pick a name", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val button = compose.onNodeWithText("Continue", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        assertTrue("headline leads: $headline", headline.right <= fold.left)
        assertTrue("controls trail: $button", button.left >= fold.right)
    }

    @Test fun tabletopPutsTheHeadlineAboveAndControlsBelow() {
        for (step in listOf("name", "email", "done", "notifications")) {
            val fold = show(step) { width, height -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }
            assertClear(step, fold)
        }
        val fold = show("name") { width, height -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }
        val headline = compose.onNodeWithText("Pick a name", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val button = compose.onNodeWithText("Continue", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        assertTrue("headline above: $headline", headline.bottom <= fold.top)
        assertTrue("controls below: $button", button.top >= fold.bottom)
    }
}
