package com.noahwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.noahwallet.localization.LocalizedContent
import com.noahwallet.ui.receive.*
import org.junit.Rule
import org.junit.Test

/** Receive's screen behaviour around its sheets and messages, on the fixtures (never a real wallet). */
class ReceiveContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun invoiceErrorsAreTranslated() {
        val ui = ReceiveFixtures.ui("request").copy(listening = false, invoiceError = "Could not generate a Lightning invoice. Please try again.")
        compose.setContent { LocalizedContent("es") { NoahTheme { ReceivePreviewHost(ui) } } }
        compose.onNodeWithText("No se pudo generar una factura Lightning. Inténtalo de nuevo.").assertExists()
        compose.onAllNodesWithText("Could not generate a Lightning invoice. Please try again.").assertCountEquals(0)
    }
}
