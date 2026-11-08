package com.noahwallet.ui.home

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.noahwallet.ui.NoahTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RollingDigitAlignmentTest {
    @get:Rule val compose = createComposeRule()
    private var case by mutableStateOf<Pair<Float, Float>?>(null)

    @Test fun targetDigitSharesTheTopOfItsClippingWindow() {
        compose.setContent {
            NoahTheme {
                case?.let { (density, scale) ->
                    CompositionLocalProvider(LocalDensity provides Density(density)) {
                        key(density, scale) {
                            val base = HomeType.balance
                            val style = base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale)
                            RollingText("9", style, Color.Black, rollFromZero = false)
                        }
                    }
                }
            }
        }
        for ((density, scale) in listOf(2.625f to 1f, 2.625f to .81f, 2.75f to 1f, 3f to .93f, 3f to .81f)) {
            compose.runOnIdle { case = density to scale }
            compose.waitForIdle()
            val window = compose.onNodeWithTag("home-balance-char", useUnmergedTree = true).fetchSemanticsNode()
            val target = compose.onNodeWithText("9", useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("density=$density scale=$scale: target digit is flush with the clipping window",
                window.positionInRoot.y.roundToInt(), target.positionInRoot.y.roundToInt())
        }
    }
}
