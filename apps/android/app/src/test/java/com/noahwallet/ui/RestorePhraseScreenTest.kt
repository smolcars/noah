package com.noahwallet.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The typed restore screen: words stay in memory through the background and clear on Back. Public BIP39 test words only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestorePhraseScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app by lazy { AppModel(RuntimeEnvironment.getApplication()) }
    private val input get() = ViewModelProvider(compose.activity)[RecoveryPhrasePresentation::class.java].input

    private fun mount() = compose.activity.setContent { NoahTheme { RestoreWalletScreen(app) } }
    private fun field(view: View = compose.activity.window.decorView): RecoveryWordEditText? = when (view) {
        is RecoveryWordEditText -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { field(view.getChildAt(it)) }
        else -> null
    }

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
}
