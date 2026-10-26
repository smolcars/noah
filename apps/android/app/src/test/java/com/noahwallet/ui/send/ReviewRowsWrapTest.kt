package com.noahwallet.ui.send

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.home.HomeIcons
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review's rows at large font scales on a narrow phone's column: a row too long for one line puts its label above its
 * value and neither is cut off; a row that fits keeps its one line (as iOS's `SendDetailRow(wraps:)`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewRowsWrapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // label, value, number
    private val rows = listOf(
        Triple("Via", "Lightning", false), Triple("Arrives", "After 2 confirmations", false), Triple("Estimated fee", "₿ 420", true),
        Triple("From", "Ark balance", false), Triple("Note", "Coffee and a croissant, thanks!", false),
        Triple("Total (reviewed coins)", "₿ 152,742", true),
    )

    private fun show(fontScale: Float, wraps: Boolean) = compose.setContent {
        NoahTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                // A 360 dp phone less Review's 20 dp margins.
                Column(Modifier.width(320.dp)) {
                    rows.forEachIndexed { index, (label, value, number) ->
                        ValueRow(label, value, number = number, icon = if (index == 0) HomeIcons.lightning else null, wraps = wraps, modifier = Modifier.testTag("row-$index"))
                    }
                }
            }
        }
    }

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }
    /** Ellipsized, or more lines than it may show. */
    private fun cut(text: String) = layout(text).let { it.didOverflowHeight || it.isLineEllipsized(it.lineCount - 1) }
    private fun bounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun twiceTheFontScaleStacksLongRowsAndCutsNothingOff() {
        show(2f, wraps = true)
        var stacked = 0
        rows.forEachIndexed { index, (label, value) ->
            assertFalse(label, cut(label)); assertFalse(value, cut(value))
            val row = compose.onNodeWithTag("row-$index").fetchSemanticsNode().boundsInRoot
            val (title, content) = bounds(label) to bounds(value)
            assertTrue(label, row.contains(title.topLeft) && row.contains(content.bottomRight - Offset(1f, 1f)))
            if (content.top >= title.bottom) stacked++ else assertTrue(label, content.left >= title.right)
        }
        // Total (reviewed coins) and the note at least.
        assertTrue("$stacked", stacked >= 2)
    }

    @Test fun rowsThatFitKeepOneLine() {
        show(1f, wraps = true)
        rows.dropLast(2).forEach { (label, value) ->
            val (title, content) = bounds(label) to bounds(value)
            assertEquals(label, title.center.y, content.center.y, 1f)
            assertTrue(label, content.left >= title.right)
        }
    }
}
