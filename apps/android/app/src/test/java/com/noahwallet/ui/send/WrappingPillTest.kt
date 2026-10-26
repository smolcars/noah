package com.noahwallet.ui.send

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.noahwallet.onchain.TotalRow
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.settings.SettingsIcons
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The exit pages' wrapping pills and amounts at twice the text on a 360 dp phone, in Swedish: a label too long for one line
 * takes more, breaking only between words, shrinking just enough that its longest word fits ("blockkedjan", "Kopiera").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WrappingPillTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().first().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    private fun breaksBetweenWords(text: String) {
        val result = layout(text)
        for (line in 0 until result.lineCount - 1) {
            val end = result.getLineEnd(line)
            assertTrue("$text breaks inside a word after \"${text.substring(0, end)}\"", text[end - 1] == ' ' || text[end] == ' ')
        }
        // Nothing is cut off: no line runs past the text's box (which can be narrower than the paragraph's layout width), and
        // the last line shown ends the text.
        for (line in 0 until result.lineCount) assertTrue("$text is clipped", result.getLineRight(line) <= result.size.width + .5f)
        assertEquals("$text is cut short", text.length, result.getLineEnd(result.lineCount - 1))
    }

    @Test fun bigAmountsWrapRatherThanClip() {
        // Big amounts in sats, grouped with no-break spaces: in a Choose row's 258 dp amount column, and as the total you get.
        val amount = "1\u00A0000\u00A0000\u00A0000 sats"; val total = "~123\u00A0456\u00A0789\u00A0012 sats"
        compose.setContent { NoahTheme { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
            Column {
                WrappingLabel(amount, HomeType.amount, Modifier.width(258.dp))
                Column(Modifier.width(320.dp)) { TotalRow("Du får", total) }
            }
        } } }
        breaksBetweenWords(amount); breaksBetweenWords(total)
        assertTrue("the amount took two lines", layout(amount).lineCount > 1)
    }

    @Test fun longLabelsBreakOnlyBetweenWords() {
        val add = "Lägg till pengar på blockkedjan"; val copy = "Kopiera"; val hold = "Håll för att lösa in 30 738 sats"
        compose.setContent { NoahTheme { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
            Column {
                // The needs-funds foot (Cancel exit at 0.8, Add on-chain funds at 1) and the deposit sheet's Copy and Share.
                Row(Modifier.width(312.dp)) { SendPill("Avbryt uttaget", Modifier.weight(.8f), wrap = true) {}; SendPill(add, Modifier.weight(1f), height = 56.dp, wrap = true) {} }
                Row(Modifier.width(312.dp)) { SendPill(copy, Modifier.weight(1f), outlined = true, icon = SettingsIcons.copy, wrap = true) {}; SendPill("Dela", Modifier.weight(1f), wrap = true) {} }
                HoldToConfirm(hold, "Löser in", "Lös in", "Lös in", enabled = true, sending = false, modifier = Modifier.width(312.dp), tag = "hold", onConfirm = {}, wrap = true)
            }
        } } }
        for (text in listOf(add, copy, hold)) breaksBetweenWords(text)
        assertTrue("it took more lines rather than shrinking to fit one", layout(add).lineCount > 1)
    }
}
