package com.noahwallet.ui.home

import android.app.Application
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.ui.NoahTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BalancePrivacyMorphTest {
    @get:Rule val compose = createComposeRule()

    private fun homeUi(hidden: Boolean) = HomeUi(
        state = HomeRules.State.everyday,
        address = null,
        variant = "regtest",
        balance = null,
        unit = BitcoinAmountUnit.bip177,
        locale = Locale.US,
        hidden = hidden,
        fiat = null,
        status = null,
        nudge = null,
        rows = emptyList(),
        historyError = null,
        newRows = emptySet(),
        retrying = false,
        now = Date(0),
        roundsActive = false,
        activity = null,
        backedUpAt = null,
    )

    /** Critically damped reveal never overshoots 1f and dips back down to re-trigger the blur layer. */
    @Test fun morphRevealIsMonotonicAndNeverOscillates() {
        val vectorized = balanceMorphReveal().vectorize(Float.VectorConverter)
        val start = AnimationVector1D(0f)
        val end = AnimationVector1D(1f)
        val zeroVel = AnimationVector1D(0f)
        var previous = 0f
        for (ms in 0..800 step 4) {
            val value = vectorized.getValueFromNanos(ms * 1_000_000L, start, end, zeroVel).value
            assertTrue("morph dropped at ${ms}ms: $value < $previous", value >= previous - 1e-5f)
            assertTrue("morph overshot 1f at ${ms}ms: $value", value <= 1f + 1e-5f)
            previous = value
        }
        assertEquals(1f, previous, 0.01f)
    }

    /** Vertical blur bleed does not expand the balance's layout height or shift the Bitcoin glyph. */
    @Test fun blurBleedPreservesBalanceHeightAndSymbolPosition() {
        var hidden by mutableStateOf(false)
        compose.setContent {
            NoahTheme {
                CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
                    Column(Modifier.width(360.dp)) {
                        BalanceBlock(homeUi(hidden), total = 152_742uL, tuck = 0f, entered = 2_000L, onTap = {}, onToggle = { hidden = !hidden })
                    }
                }
            }
        }
        compose.waitForIdle()
        val expectedHeight = with(Density(2.625f)) { 50.sp.roundToPx() }
        val balanceBefore = compose.onNodeWithTag("home-balance").fetchSemanticsNode()
        val symbolBefore = compose.onAllNodesWithTag("home-balance-char", useUnmergedTree = true).fetchSemanticsNodes().first()
        assertEquals("balance keeps 50 sp height", expectedHeight.toFloat(), balanceBefore.size.height.toFloat(), 2f)
        assertEquals("₿ stays aligned with the balance box top", balanceBefore.positionInRoot.y, symbolBefore.positionInRoot.y, 1f)

        compose.onNodeWithTag("home-balance-visibility").performClick()
        compose.waitForIdle()
        val balanceHidden = compose.onNodeWithTag("home-balance").fetchSemanticsNode()
        val descHidden = balanceHidden.config[SemanticsProperties.ContentDescription].joinToString()
        assertEquals("₿\u00a0••••••", descHidden)
        assertEquals("hidden balance keeps 50 sp height", expectedHeight.toFloat(), balanceHidden.size.height.toFloat(), 2f)

        compose.onNodeWithTag("home-balance-visibility").performClick()
        compose.waitForIdle()
        val balanceShown = compose.onNodeWithTag("home-balance").fetchSemanticsNode()
        val descShown = balanceShown.config[SemanticsProperties.ContentDescription].joinToString()
        assertEquals("₿\u00a0152,742", descShown)
    }

    /** Rapid double-tapping privacy mode mid-fade recovers cleanly to the visible state. */
    @Test fun rapidDoubleToggleSettlesBackToVisibleBalance() {
        var hidden by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            NoahTheme {
                Column(Modifier.width(360.dp)) {
                    BalanceBlock(homeUi(hidden), total = 152_742uL, tuck = 0f, entered = 2_000L, onTap = {}, onToggle = { hidden = !hidden })
                }
            }
        }
        compose.mainClock.advanceTimeBy(100L)
        compose.onNodeWithTag("home-balance-visibility").performClick()
        compose.mainClock.advanceTimeBy(64L)
        compose.onNodeWithTag("home-balance-visibility").performClick()
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        val balance = compose.onNodeWithTag("home-balance").fetchSemanticsNode()
        assertEquals("₿\u00a0152,742", balance.config[SemanticsProperties.ContentDescription].joinToString())
    }
}
