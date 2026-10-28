package com.noahwallet.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.ui.home.BalanceFigures
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Large balances fit a narrow pane in both units: every character keeps its box, and none runs past the edge. */
class BalanceFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var case by mutableStateOf<Pair<String, Dp>?>(null)

    private fun show(text: String, width: Dp) {
        compose.runOnIdle { case = text to width }
        compose.waitForIdle()
    }

    /** Character boxes, unclipped: position and measured size, so an overflow can't hide behind the pane's clip. */
    private fun chars() = compose.onAllNodesWithTag("home-balance-char", useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.positionInRoot.x to it.positionInRoot.x + it.size.width }

    private fun host(density: Density? = null) = compose.setContent { NoahTheme {
        CompositionLocalProvider(LocalDensity provides (density ?: LocalDensity.current)) { case?.let { (text, width) -> key(text, width) {
            Box(Modifier.width(width).testTag("pane")) { BalanceFigures(text, hidden = false, rollFromZero = false, startDelay = 0) }
        } } }
    } }

    @Test fun largeBalancesFitANarrowPaneInBothUnits() {
        host()
        assertLargeBalancesFit()
    }

    /** At 200% font size Android scales large text less than small text, so shrinking the sp size shrinks glyphs by less. */
    @Test fun largeBalancesFitAtTheLargestFontSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        host(Density(context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })))
        assertLargeBalancesFit()
    }

    private fun assertLargeBalancesFit() {
        // A book pane on a Pixel Fold leaves about 292 dp for the balance; 200 dp is a narrower stress.
        for (sats in listOf(100_000_000L, 2_100_000_000_000_000L)) for (unit in BitcoinAmountUnit.entries) for (width in listOf(292.dp, 200.dp)) {
            assertFits(sats, unit, width)
        }
    }

    private fun assertFits(sats: Long, unit: BitcoinAmountUnit, width: Dp) {
        val text = BitcoinAmount.format(sats, unit, Locale.US)
        show(text, width)
        val pane = compose.onNodeWithTag("pane").fetchSemanticsNode()
        val right = pane.positionInRoot.x + pane.size.width
        val boxes = chars()
        // BIP-177 shows ₿ in its own box (the no-break space becomes a 3 dp gap); sats shows every character.
        val expected = if (unit == BitcoinAmountUnit.bip177) 1 + text.removePrefix("₿ ").length else text.length
        assertEquals("$text at $width: every character is laid out", expected, boxes.size)
        for ((start, end) in boxes) {
            assertTrue("$text at $width: no character squeezed to nothing", end - start > 0.5f)
            assertTrue("$text at $width: nothing past the pane's edge ($end > $right)", end <= right + 0.5f)
        }
    }

    @Test fun anOrdinaryBalanceKeepsTheFullSize() {
        host()
        show(BitcoinAmount.format(152_742, BitcoinAmountUnit.bip177, Locale.US), 360.dp)
        val first = compose.onAllNodesWithTag("home-balance-char", useUnmergedTree = true).fetchSemanticsNodes().first()
        val fullLine = with(compose.density) { 50.sp.roundToPx() }
        assertEquals("50 sp when it fits", fullLine.toFloat(), first.size.height.toFloat(), 2f)
    }
}
