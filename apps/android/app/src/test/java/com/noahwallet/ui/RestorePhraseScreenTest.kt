package com.noahwallet.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The typed restore screen: words stay in memory through the background and clear on Back; Paste fills every word from
 * each shape a phrase is copied in and rejects anything else visibly, keeping the entries. Public BIP39 test words only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestorePhraseScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app by lazy { AppModel(RuntimeEnvironment.getApplication()) }
    private val clipboard by lazy { RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java) }
    private val input get() = ViewModelProvider(compose.activity)[RecoveryPhrasePresentation::class.java].input
    private val rejected = "Paste exactly 12 or 24 words. Your entries haven't been replaced."

    private fun mount() = compose.activity.setContent { NoahTheme { RestoreWalletScreen(app) } }
    private fun field(view: View = compose.activity.window.decorView): RecoveryWordEditText? = when (view) {
        is RecoveryWordEditText -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { field(view.getChildAt(it)) }
        else -> null
    }
    private fun words(count: Int) = List(count - 1) { "abandon" } + if (count == 12) "about" else "art"
    private fun clipText() = clipboard.primaryClip?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text } }
        ?.joinToString("").orEmpty()

    @Test fun wordsStayThroughTheBackgroundAndClearOnBack() {
        mount()
        compose.runOnIdle { checkNotNull(field()).setText("abandon") }
        compose.runOnIdle { assertEquals("abandon", input.words[0]) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertEquals("abandon", input.words[0])
            assertEquals("abandon", checkNotNull(field()).text.toString())
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle {
            assertTrue(input.words.all { it.isEmpty() })
            assertEquals("", checkNotNull(field()).text.toString())
        }
    }

    @Test fun pasteFillsEveryWordFromEachCopiedShape() {
        mount()
        for (count in listOf(12, 24)) {
            val words = words(count)
            val line = words.joinToString(" ")
            val shapes = listOf(ClipData.newPlainText("note", line), ClipData.newPlainText("note", "$line\n"),
                ClipData.newPlainText("note", words.joinToString("\r\n")), ClipData.newPlainText("note", words.joinToString(" ")),
                ClipData.newPlainText("note", words.joinToString(" ")), ClipData.newHtmlText("note", "$line\n", "<p>$line</p>"),
                ClipData.newPlainText("note", words.take(count / 2).joinToString(" ")).apply {
                    addItem(ClipData.Item(words.drop(count / 2).joinToString(" ")))
                })
            for ((shape, clip) in shapes.withIndex()) {
                compose.runOnIdle { clipboard.setPrimaryClip(clip) }
                compose.onNodeWithText("Paste").performClick()
                compose.runOnIdle {
                    assertTrue("Shape $shape of $count fills every word", input.words == words)
                    assertEquals("Shape $shape of $count leaves the clipboard", "", clipText())
                }
            }
        }
    }

    @Test fun pasteRejectsAnythingElseVisiblyAndKeepsTheEntries() {
        mount()
        compose.runOnIdle { checkNotNull(field()).setText("abandon") }
        val words = words(12)
        for (clip in listOf(ClipData.newPlainText("note", words.first()), ClipData.newPlainText("note", words.take(11).joinToString(" ")),
            ClipData.newPlainText("note", words.joinToString(" ") + " abandon"), ClipData.newRawUri("note", Uri.parse("content://example/phrase")))) {
            compose.runOnIdle { clipboard.setPrimaryClip(clip) }
            compose.onNodeWithText("Paste").performClick()
            compose.onNodeWithText(rejected).assertExists()
            compose.runOnIdle { assertEquals(listOf("abandon") + List(11) { "" }, input.words) }
        }
    }
}
